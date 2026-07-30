# 笔记本混合检索（RAG）功能说明

本文档规定 **AI 侧栏笔记本混合检索**（BM25 关键词 + 可选向量）的架构、数据模型、REST 接口、离线 worker 协议、模型目录、隐私授权、降级行为、索引维护与开发命令，对应后端 `RetrievalService` / `HybridRetrievalService` / `NoteIndexingService` / `EmbeddingModelService` / `EmbeddingConfigService` / `RetrievalMaintenanceService` 与 Rust `embedding-worker` 的实际实现。前端入口在 AI 侧栏的「检索」开关与设置页的「嵌入 / 模型目录」卡片。

## 目录

1. [功能总览](#1-功能总览)
2. [架构与数据流](#2-架构与数据流)
3. [数据模型（schema v8）](#3-数据模型schema-v8)
4. [分块与索引](#4-分块与索引)
5. [REST 接口](#5-rest-接口)
6. [离线 Embedding Worker 协议](#6-离线-embedding-worker-协议)
7. [模型目录与下载状态机](#7-模型目录与下载状态机)
8. [混合检索与 RRF 融合](#8-混合检索与-rrf-融合)
9. [隐私与授权](#9-隐私与授权)
10. [降级与错误处理](#10-降级与错误处理)
11. [索引维护](#11-索引维护)
12. [开发命令与自动 QA](#12-开发命令与自动-qa)
13. [v0.6 corpus 边界](#13-v06-corpus-边界)
14. [常见问题](#14-常见问题)

---

## 1. 功能总览

AI 侧栏对话可选择性地把笔记本内容作为上下文注入回答（RAG）。检索分两层：

| 层 | 是否默认启用 | 说明 |
|---|---|---|
| **BM25 关键词检索** | 是 | 基于 SQLite FTS5（`trigram` 分词）。笔记一旦保存即自动建索引，**无需任何下载**，始终可用。 |
| **向量检索** | 否（opt-in） | 需要用户**主动**启用一个 embedding 来源（本地模型下载，或配置 OpenAI/Ollama）。未启用时检索契约 = 纯 BM25。 |

两层命中通过 **Reciprocal Rank Fusion（RRF）** 融合成统一排序的引用列表。向量层出现任何故障都会**静默降级**为 BM25-only，并附带一个 `retrievalNotice`，**绝不让对话失败**。

**核心约束**：
- 默认**不下载任何模型**，默认**不向任何远程服务发送笔记内容**。
- 远程 embedding（OpenAI/Ollama）只有在用户勾选「同意发送笔记内容」后才启用。
- 本地模型下载是可选、可校验、可断点续传、可取消、可彻底卸载的。
- 所有检索产物（数据库、模型文件、缓存、日志）都落在应用根目录 `APP_ROOT` 策略下。

## 2. 架构与数据流

```
笔记保存 / 启动回填
        │
        ▼
NoteIndexingService ── NoteChunker（2600/300/200）── content_hash
        │                         │
        ▼                         ▼
retrieval_chunk + retrieval_chunk_fts（BM25，trigram）
        │
        ▼（仅当选中 embedding space）
embedding_job（QUEUED）── EmbeddingJobExecutor（poller）
        │                         │
        ▼                         ▼
EmbeddingProvider ──────── Rust embedding-worker（本地）
（local/openai/ollama）     或 OpenAI/Ollama HTTP（远程）
        │
        ▼
retrieval_embedding（float32 BLOB，L2 归一化）
        │
        ▼  coverage=100% → space ACTIVE
对话：AiService.chat ── HybridRetrievalService
        │                    ├── RetrievalService（BM25）
        │                    └── 向量 Top-K（点积）→ RRF 融合
        ▼
{ reply, citations[], retrievalNotice? }
```

- **写路径**：笔记保存触发 `savePageAndIndex`——在同一事务里重写 `retrieval_chunk` / FTS，并为已选中的 embedding space 排队 `embedding_job`。向量生成由后台 poller 异步完成，不阻塞保存。
- **读路径**：`chat` 在调用模型前先做混合检索，把至多 10 个片段（12,000 chars 预算）拼成唯一 RAG system message，连同 `citations` 一并返回。

## 3. 数据模型（schema v8）

v7 数据库首次启动时一次性升级到 v8（幂等，可重复启动），新增六张表。

### 3.1 `retrieval_chunk` — 检索分块

```sql
CREATE TABLE IF NOT EXISTS retrieval_chunk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    corpus_type TEXT NOT NULL DEFAULT 'NOTEBOOK',
    corpus_id INTEGER NOT NULL,        -- 笔记本 id
    source_id INTEGER NOT NULL,        -- 页面 id
    chunk_index INTEGER NOT NULL,
    title TEXT,
    heading_path TEXT,
    text TEXT NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    content_hash TEXT NOT NULL,        -- 用于 supersede / 增量判断
    UNIQUE(corpus_type, source_id, chunk_index)
);
```

### 3.2 `retrieval_chunk_fts` — BM25 全文索引

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS retrieval_chunk_fts USING fts5(
    title, heading_path, text,
    tokenize='trigram'                 -- 大小写不敏感，支持中文子串
);
```

### 3.3 `embedding_model` — 模型目录安装状态

```sql
CREATE TABLE IF NOT EXISTS embedding_model (
    catalog_id TEXT NOT NULL UNIQUE,
    provider_model_id TEXT NOT NULL,
    artifact_revision TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    installation_state TEXT NOT NULL DEFAULT 'AVAILABLE'
        CHECK (installation_state IN
          ('AVAILABLE','DOWNLOADING','VERIFYING','READY','UNINSTALLING','FAILED','PAUSED')),
    manifest_json TEXT,
    download_progress_json TEXT,
    download_error TEXT
);
```

### 3.4 `embedding_space` — 向量空间（激活契约）

```sql
CREATE TABLE IF NOT EXISTS embedding_space (
    embedding_space_id TEXT PRIMARY KEY,   -- 由 canonical contract 派生
    canonical_contract_json TEXT NOT NULL,
    provider_type TEXT NOT NULL,           -- local / openai / ollama
    model_identifier TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    state TEXT NOT NULL DEFAULT 'DISABLED'
        CHECK (state IN ('DISABLED','REBUILDING','ACTIVE','ERROR','UNINSTALLING')),
    coverage REAL NOT NULL DEFAULT 0.0 CHECK (coverage >= 0.0 AND coverage <= 1.0),
    is_selected INTEGER NOT NULL DEFAULT 0 CHECK (is_selected IN (0, 1))
);
CREATE UNIQUE INDEX idx_embedding_space_selected
    ON embedding_space(is_selected) WHERE is_selected = 1;   -- 至多一个选中空间
```

`embedding_space_id` 由 canonical contract（provider/endpoint/model/dimensions）规范化后派生，**同一契约幂等地指向同一空间**。`is_selected` 的唯一索引保证全局只有一个激活向量空间。

### 3.5 `retrieval_embedding` — 向量存储

```sql
CREATE TABLE IF NOT EXISTS retrieval_embedding (
    chunk_id INTEGER NOT NULL REFERENCES retrieval_chunk(id) ON DELETE CASCADE,
    corpus_type TEXT NOT NULL,
    embedding_space_id TEXT NOT NULL REFERENCES embedding_space(embedding_space_id),
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    content_hash TEXT NOT NULL,
    vector_blob BLOB NOT NULL,             -- 小端 float32，L2 归一化
    UNIQUE(chunk_id, embedding_space_id)
);
```

向量以**小端 float32 BLOB** 存储（`dimensions * 4` 字节），写入前经 `EmbeddingVectorCodec` 校验数量/NaN/Inf/零范数并做 L2 归一化。维度强制触发器在 `DatabaseInitializer` 中以独立 `exec()` 创建。

### 3.6 `embedding_job` — 异步嵌入任务

```sql
CREATE TABLE IF NOT EXISTS embedding_job (
    corpus_type TEXT NOT NULL,
    source_id INTEGER NOT NULL,
    source_content_hash TEXT NOT NULL,
    embedding_space_id TEXT NOT NULL REFERENCES embedding_space(embedding_space_id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','CLAIMED','RETRY','COMPLETED','SUPERSEDED','FAILED')),
    claim_token TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_run_at TEXT,
    error TEXT,
    UNIQUE(corpus_type, source_id, source_content_hash, embedding_space_id)
);
```

`status` 生命周期：`QUEUED → CLAIMED →（COMPLETED | FAILED | RETRY）`；内容被新保存覆盖时旧任务被标记 `SUPERSEDED`（见 §11）。

## 4. 分块与索引

### 4.1 分块算法（`NoteChunker`，冻结契约）

| 参数 | 值 | 说明 |
|---|---|---|
| `BASE_MAX` | 2600 | 基础段最大 UTF-16 code unit 数，在单元边界切分 |
| `TAIL_MIN` | 300 | 末段短于 300 时并入前一段（并入后仍 ≤2600） |
| `OVERLAP_MAX` | 200 | 每个非首块前置 `min(200, 前块长度)` 重叠 |
| 单块上限 | 2800 | 含重叠后每块不超过 2800 chars |

有序文本单元以 `\n\n` 拼接成全文后切分；超长单单元做硬窗口切分。每块记录 `start_offset` / `end_offset` / `heading_path` / `content_hash`。

### 4.2 查询规范化（`RetrievalService`）

- 原始查询先截断到 `MAX_QUERY_CODEPOINTS = 512` 个码点。
- `normalizeQuery`：NFKC 规范化 + 小写化，分隔符转空格。
- `buildMatchExpression`：3+ 码点的查询生成 trigram shingle，`OR` 连接并包裹为 FTS 字面量（`trigram` 分词大小写不敏感）。
- 片段 `snippet` 取命中前后各约 80 字符上下文。

### 4.3 写入与自动保存竞争

`savePageAndIndex` 在单事务内重写 chunk/FTS 并对已选中空间 `upsertJob`（按最新 `content_hash`）。若一次 full rebuild 已 claim 旧 hash，而自动保存写入新 hash，则旧任务被 `supersedeJobs` 标记为 `SUPERSEDED`，最终 chunk/FTS/vector 只保留新 hash（见 §11.3）。

## 5. REST 接口

所有接口前缀 `/api/ai`，返回 JSON，错误用 HTTP 4xx/5xx + `{error: "..."}`。

### 5.1 对话检索：`POST /api/ai/chat`

请求体在常规 chat 字段外新增 `retrievalOptions`：

```json
{
  "messages": [{ "role": "user", "content": "光合作用" }],
  "retrievalOptions": { "enabled": true, "scope": "all", "notebookId": null }
}
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `enabled` | boolean | 关闭时不检索，纯对话 |
| `scope` | `all` / `current` | `current` 需配 `notebookId`，仅检索该笔记本 |
| `notebookId` | number? | `scope=current` 时必填 |

响应：

```json
{
  "reply": "…",
  "sessionId": 12,
  "citations": [
    {
      "corpusType": "NOTEBOOK", "notebookId": 1, "pageId": 5, "chunkId": 42,
      "title": "…", "headingPath": "…", "snippet": "…",
      "matchTypes": ["bm25", "vector"],
      "ftsRank": 1, "vectorRank": 3, "rrfScore": 0.029
    }
  ],
  "retrievalNotice": { "code": "vector-index-unavailable", "message": "…" }
}
```

`retrievalNotice` 仅在向量层降级时出现（见 §10）。注入片段与返回 `citations` 一一对应。

### 5.2 索引状态：`GET /api/ai/retrieval/status`

`?scope=all` 或 `?scope=current&notebookId=X`。`coverage` / `indexState` 始终描述**整个选中空间**（激活是 corpus 级属性），页/块/任务计数按 scope 过滤。

```json
{
  "scope": "all", "notebookId": null,
  "totalPages": 10, "totalChunks": 30,
  "indexedChunks": 30, "staleChunks": 0,
  "queuedJobs": 0, "failedJobs": 0,
  "coverage": 1.0, "indexState": "ACTIVE",
  "embeddingSpaceId": "…", "provider": "local"
}
```

未选中任何空间时：`indexState=DISABLED`、`coverage=0`、计数字段为 0。

### 5.3 维护端点

| 接口 | 方法 | 入参 | 语义 |
|---|---|---|---|
| `/retrieval/reindex` | POST | `{scope, notebookId?, mode}` | `mode=missing`（默认）仅补无最新向量的页；`mode=full` 全量重建。首次 `202 {jobId,state}`；同一 space+scope 仍有可运行重建任务时重复请求返回 `200` 同 `jobId`（单一活跃重建）。无选中空间 → `409 NO_SELECTED_SPACE`；空间状态不可重建 → `409 SPACE_NOT_INDEXABLE`。 |
| `/retrieval/retry-failed` | POST | `{scope, notebookId?}` | 重排 FAILED 任务，返回 `{requeued}`。恒 `200`；无选中空间时为 0。 |

### 5.4 模型生命周期：`/api/ai/embeddings/*`

| 接口 | 方法 | 语义 |
|---|---|---|
| `/catalog` | GET | 返回内置模型目录（含安装状态）。 |
| `/models/{id}/download` | POST | 开始/恢复下载（校验 size/sha256，断点续传）。 |
| `/downloads/{jobId}` | DELETE | 取消下载。 |
| `/models/{id}/activate` | POST | 激活本地模型：选中空间 → REBUILDING → 排队回填。 |
| `/models/{id}/disable` | POST | 停用。 |
| `/models/{id}?confirm=true` | DELETE | 彻底卸载：删模型文件 + 该空间向量。 |
| `/test` | POST | 探测已保存的 embedding 配置连通性。 |

### 5.5 embedding 配置：`PUT /api/ai/config`

与主模型共用配置端点，以 `purpose` 区分。embedding slot：

```json
{
  "purpose": "embedding",
  "provider": "disabled | local | openai | ollama",
  "endpoint": "…", "model": "…", "dimensions": 1536,
  "apiKey": "…", "remoteContentConsent": true
}
```

- `provider=disabled`：停止远程 embedding，清除本地激活 provider。
- `provider=local`：走模型目录激活流程。
- `provider=openai|ollama`（远程）：无有效授权时以 `enabled=false` 保存并返回 `code=CONSENT_REQUIRED`；已授权时选中 REBUILDING 空间并排队回填（见 §9）。

## 6. 离线 Embedding Worker 协议

本地向量由 Rust `embedding-worker` 子进程提供，stdin/stdout 走 **NDJSON**（每行一个 JSON 对象）。`protocolVersion = 1`，每个请求/响应含 `protocolVersion`、`requestId`、`type` 标签。

### 6.1 请求（stdin）

| `type` | 关键字段 | 说明 |
|---|---|---|
| `hello` | — | 握手，期望返回 `ready` |
| `load_model` | `modelId`, `modelDir`, `requiredFiles[]`, `dimensions` | 加载 ONNX 模型，成功返回 `model_loaded{dimensions}` |
| `embed` | `mode`(`query`/`document`), `inputs[]` | `query` 模式加查询前缀；返回 `embed_result{embeddings[][]}` |
| `unload` | — | 卸载模型，返回 `ok` |
| `shutdown` | — | 退出进程，返回 `ok` |

### 6.2 响应（stdout）

`ready` / `model_loaded{dimensions}` / `embed_result{embeddings}` / `ok` / `error{code,message,retryable}`。

### 6.3 错误码

`MALFORMED_REQUEST`、`PROTOCOL_VERSION_MISMATCH`、`MODEL_FILES_MISSING`、`MODEL_LOAD_FAILED`、`MODEL_NOT_LOADED`、`DIMENSION_MISMATCH`、`EMBEDDING_FAILED`、`REQUEST_TOO_LARGE`、`INTERNAL_ERROR`。`retryable` 指示后端是否可重试。

### 6.4 限额（按 Unicode 标量字符计）

| 常量 | 值 |
|---|---|
| `MAX_EMBED_INPUTS` | 100 条/请求 |
| `MAX_INPUT_LENGTH` | 10,000 字符/条 |
| `MAX_TOTAL_INPUT_LENGTH` | 100,000 字符/请求 |

## 7. 模型目录与下载状态机

内置目录 `backend/src/main/resources/embedding-model-catalog-v1.json`（`catalogVersion=1`），当前唯一模型：

| 字段 | 值 |
|---|---|
| `id` | `bge-small-zh-v1.5` |
| `providerModelId` | `Qdrant/bge-small-zh-v1.5` |
| `dimensions` | 512 |
| `license` | MIT |
| 文件数 | 7（5 个 `runtimeRequired=true`），总计 95,332,206 字节 |
| 主权重 | `model_optimized.onnx`（94,781,076 字节） |

每个文件 `sha256` 均 pinned，下载后逐文件校验；`manifest` 记录 id/revision/size/hash，**产品 API 拒绝任意未入目录的 model ID**。

**安装状态机**（`embedding_model.installation_state`）：
`AVAILABLE → DOWNLOADING → VERIFYING → READY`；可 `PAUSED`（暂停/断点续传）、`FAILED`（可重试）；卸载经 `UNINSTALLING` 回到可下载态。

**空间状态机**（`embedding_space.state`）：
`DISABLED → REBUILDING →（coverage=100%）→ ACTIVE`；故障 `ERROR`；卸载 `UNINSTALLING`。仅 `ACTIVE` 且 `coverage=1.0` 的空间才参与向量检索。

## 8. 混合检索与 RRF 融合

`HybridRetrievalService` 的融合契约（冻结）：

| 常量 | 值 | 说明 |
|---|---|---|
| `VECTOR_TOP_K` | 40 | 向量分支用有界最小堆保留点积最大的 40 个块 |
| `RRF_K` | 60 | RRF 平滑常数 |
| `MAX_RESULTS` | 10 | 融合后最多返回 10 个命中 |
| `timeout-ms` | 1500 | 向量分支（embed + scan）硬 deadline |
| `worker-ready-ms` | 500 | provider 未就绪时的有界等待 |

- **相似度**：对 Java 侧 L2 归一化的 float32 向量做**点积**。
- **融合公式**：`score = 1/(60+ftsRank) + 1/(60+vectorRank)`；仅出现在单一排序的命中保留其单边项。并列按 `source_id`、`chunk_index` 升序打破。
- **向量过滤**：按 corpus/scope/space/dimensions 及当前块 `content_hash` 过滤，陈旧向量不参与。
- **原始 BM25 分数与点积分数绝不混合**，只通过 rank 融合。
- 对话层再对融合命中应用 12,000-char 上下文预算（`RAG_CONTEXT_CHAR_BUDGET`），按 rank 截断正文，citation 元数据永不截断。

## 9. 隐私与授权

| 来源 | 是否外发笔记内容 | 授权要求 |
|---|---|---|
| BM25 | 否 | 无 |
| 本地模型（local） | 否（本机 worker 子进程） | 无（用户主动下载即启用） |
| OpenAI / Ollama（远程） | **是** | 必须 `remoteContentConsent=true` |

- 远程授权以 `consentFingerprint = f(provider, endpoint, model)` 绑定；**更换 endpoint/model 后旧授权失效**，需重新勾选。
- 未授权的远程保存以 `enabled=false` 落库并返回 `CONSENT_REQUIRED`，同时立即停止任何远程 embedding 流量。
- API Key 加密存储；日志与错误**绝不记录查询文本或笔记内容**。

## 10. 降级与错误处理

向量分支遵循「**永不因向量原因抛异常**」原则：

| 触发条件 | 行为 |
|---|---|
| 从未选中空间 | BM25-only，**无 notice**（这是完整契约，非降级） |
| 空间非 ACTIVE 或 coverage<100% | BM25-only + notice `vector-index-unavailable` |
| provider 缺失 / 维度不匹配 / 未就绪超时 | BM25-only + notice |
| embed/scan 抛异常或超过 1500ms deadline | BM25-only + notice（取消 future） |

**关键语义**：远程 embedding provider 不可达时，空间保持 `REBUILDING`、coverage 0 → 检索降级为 BM25 + notice，但**对话模型（主模型）不受影响**，`chat` 仍返回 200 与 BM25 citations。注意这与「对话模型失败」不同——对话模型失败会抛异常（非 200）。

## 11. 索引维护

### 11.1 启动回填

`NoteIndexingStartupBackfill` 在启动时为 `content_hash` 为空的旧页（v7 遗留）补建 chunk/FTS，**不阻塞启动**，回填期间笔记仍可被 BM25 搜索。

### 11.2 全库重建与重试

- `reindex mode=full`：`enqueueAllJobs` 为所有页排队，空间进入 REBUILDING。
- `reindex mode=missing`：`enqueueMissingJobs` 仅补无最新向量的页。
- `retry-failed`：`retryFailedJobs` 重排 FAILED 任务。
- 完成后 `activateSpaceIfComplete` 在 coverage=100% 时把空间转 ACTIVE。

### 11.3 自动保存获胜（supersede）

重建 claim 旧 hash 期间若发生自动保存（新 hash），`supersedeJobs` 把 hash ≠ 当前 hash 的任务标记 `SUPERSEDED`；drain 后 chunk/FTS/vector 仅含新 hash，无重复活跃重建。

### 11.4 模型切换与旧向量清理

切换 embedding 来源时：`deselectCurrentSpace` → `upsertSelectedRebuildingSpace`（新空间）→ `enqueueMissingJobs`。旧空间向量由 `scheduleDisabledSpaceCleanup` **异步**清理（`cleanupDisabledSpaceVectors`），不删模型文件、不阻塞激活。

## 12. 开发命令与自动 QA

```powershell
npm run test:rag                 # 串联 embedding-worker(cargo) + backend + frontend 测试
npm run test:embedding-worker    # 仅 Rust worker（需本地 Rust 工具链）
pwsh -File scripts/mvp-test.ps1           # 后端 HTTP 冒烟，含 Section 8 RAG
pwsh -File scripts/portable-audit.ps1     # 绿色路径审计，含 RAG 受监控根目录快照
node scripts/qa-electron.mjs              # Electron CDP UI QA，含 RAG 软检查
```

- **Rust 工具链仅在开发/测试本地 embedding worker 时需要**；普通 retrieval-off 构建（只用 BM25 或远程 provider）不强制 Rust。
- `mvp-test.ps1` Section 8：建两个笔记本 + 已知笔记 → 验证 scope=all/current BM25 citations、status 端点、reindex 409 / retry-failed、远程 embedding 失败时 chat 仍 200 + BM25 降级、删除清理。
- `portable-audit.ps1`：运行前后递归快照固定受监控根目录（HuggingFace/fastembed 缓存、`Drill Notebook` 本地/漫游目录、TEMP 下 `fastembed/ort/drill-notebook` 条目），比较 path/size/mtime/hash，证明未在 `APP_ROOT` 外新增项目文件。
- `qa-electron.mjs`：软检查设置页嵌入卡片、AI 侧栏检索开关/范围控件，截图留证（软检查不影响退出码）。

Evidence 由测试内 `writeEvidence` 在 `DRILL_EVIDENCE_DIR` 环境变量指向 `.omo/evidence` 时写出（断言始终运行，写文件被门控）。

## 13. v0.6 corpus 边界

当前 `corpus_type` 仅有 `NOTEBOOK`。v0.6 可将 `CONVERSATION_MEMORY` 作为**独立 corpus** 加入，约束：

- 不改变笔记本数据所有权：`retrieval_chunk` 以 `(corpus_type, source_id, chunk_index)` 唯一，新 corpus 用独立 `corpus_type` 隔离。
- 保持向量空间不变量：`retrieval_embedding` 仍按 `embedding_space_id` + `corpus_type` 过滤，激活/coverage 仍是 corpus 级属性。
- 复用同一套 chunk/FTS/job/space 表与 RRF 融合，无需新建并行管线。

## 14. 常见问题

### 14.1 不下载模型能用检索吗？

能。BM25 关键词检索默认启用、无需下载。向量检索是可选增强，未启用时检索结果就是纯 BM25，且这是完整契约而非「残缺模式」。

### 14.2 配置了远程 embedding 但服务不可达，对话会失败吗？

不会。空间保持 REBUILDING，检索降级为 BM25 + `retrievalNotice`，对话模型照常返回 200。只有**对话主模型**本身失败才会让 chat 非 200。

### 14.3 切换 embedding 模型后旧向量怎么办？

旧空间向量异步清理（按 `embedding_space_id` 删除 `retrieval_embedding`），模型文件不在此流程删除；新空间回填到 100% 后转 ACTIVE，hybrid 恢复。切换期间检索为 BM25-only + notice。

### 14.4 重建索引时继续编辑笔记会丢数据吗？

不会。自动保存写入新 `content_hash` 后，旧 hash 的重建任务被 `SUPERSEDED`，最终 chunk/FTS/vector 只保留最新内容。

### 14.5 远程 embedding 会把笔记发给谁？

只发给用户自己配置的 OpenAI/Ollama endpoint，且必须先勾选「同意发送笔记内容」。更换 endpoint/model 后授权失效，需重新同意。本地模型则完全在本机 worker 子进程内计算，不出机器。
