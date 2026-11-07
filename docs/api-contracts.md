# 核心接口契约

后端只监听 `127.0.0.1`，基础路径 `/api`。本文记录稳定契约与核心端点；完整 DTO 以 controller / service 代码为准，专题细节见对应领域文档。

## 1. 通用约定

### 1.1 错误响应

普通错误统一为：

```json
{ "error": "invalid_request", "errorCode": "invalid_request", "message": "用户可读说明" }
```

- 业务异常：400；非法请求体：400；请求体超限：413；未处理异常：500，不暴露堆栈与内部路径。
- 聊天轮次被拒绝时额外带 `runningTurn` 等 details，由 `ChatTurnRejectException` 输出。

### 1.2 时间与编码

- 普通业务时间使用 ISO-8601 字符串。
- v9 AI 记忆、会话轮次与用量时间使用 UTC 固定毫秒，保证字典序等于时间序。
- 聊天消息正文、reasoning、附件与记忆载荷按列加密存储；接口返回前按会话密钥解密。

## 2. 学习域

### 2.1 题库

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / POST | `/banks` | 题库列表 / 新建 |
| GET / PUT / DELETE | `/banks/{id}` | 题库详情 / 更新 / 删除 |
| GET / POST | `/banks/{id}/questions` | 题目列表 / 新建 |
| PUT / DELETE | `/questions/{id}` | 更新 / 删除题目 |
| PUT | `/questions/{id}/wrong-excluded` | 手动移出错题本 |
| POST | `/banks/{id}/import/markdown` | Markdown 导入 |
| POST | `/banks/{id}/import/json` | JSON 导入 |
| POST | `/banks/{id}/import/pdf` | PDF 导入（规则 + AI 兜底） |

### 2.2 刷题与错题

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/quiz/sessions` | 创建刷题会话 |
| POST | `/quiz/sessions/{sessionId}/submit` | 提交单题答案 |
| GET | `/quiz/sessions/{sessionId}/summary` | 会话汇总 |
| GET | `/quiz/wrong` | 错题列表（含错误次数 / 错误率） |

### 2.3 复习与计划

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/review/enroll` / `/review/unenroll` | 加入 / 移出复习方案 |
| GET | `/review/due` | 到期队列 |
| POST | `/review/submit` | 提交复习评分 |
| GET | `/review/stats` / `/review/calendar-stats` | 统计 / 日历叠加 |
| GET | `/review/schedule/{type}/{id}` | 单项调度 |
| POST | `/review/reset/{type}/{id}` | 重置单项 |
| GET / POST | `/review/configs` | 复习方案列表 / 新建 |
| GET / PUT / DELETE | `/review/configs/{id}` | 方案详情 / 更新 / 删除 |
| GET | `/study/today` / `/study-plans/day` | 今日队列 / 某日计划 |
| POST | `/study/complete` | 完成今日项 |
| GET | `/study-plans` | 计划列表 |
| POST | `/study-plans/groups` | 新建计划组 |
| PUT / DELETE | `/study-plans/groups/{id}` | 更新 / 删除计划组 |
| PUT / DELETE | `/study-plans/items/{id}` | 计划条目更新 / 删除 |
| POST | `/study-plans/items/complete-by-resources` | 按资源批量完成 |
| POST | `/study-plans/recommend` / `recommend/ai-schedule` / `recommend/apply-schedule` / `recommend/session-apply` / `recommend/ai-note` | 会话推荐、AI 排程、应用排程、会话应用、AI 备注 |

### 2.4 知识点

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / POST | `/knowledge-points` | 列表 / 新建 |
| GET / PUT / DELETE | `/knowledge-points/{id}` | 详情 / 更新 / 删除 |
| POST | `/knowledge-points/batch-delete` | 批量删除 |
| PUT | `/knowledge-points/sort-all` | 全量排序 |
| POST | `/knowledge-points/import/markdown` | Markdown 分级导入 |
| POST | `/knowledge-points/summarize` / `resummarize` / `summarize-import` | 章节 / 整库总结、重跑与导入后总结 |
| POST | `/knowledge-points/restore-original` / `restore-summary` | 批量双态恢复 |
| POST | `/knowledge-points/{id}/summarize` / `resummarize` | 单卡总结 / 重跑 |
| GET | `/knowledge-points/{id}/original` | 原文快照 |
| POST | `/knowledge-points/{id}/restore-original` / `restore-summary` | 单卡双态恢复 |

### 2.5 笔记与附件

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / POST | `/notebooks` | 笔记本列表 / 新建 |
| GET / PUT / DELETE | `/notebooks/{id}` | 详情 / 更新 / 删除 |
| GET / POST | `/notebooks/{id}/pages` | 页面列表 / 新建 |
| GET / PUT / DELETE | `/note-pages/{id}` | 页面详情 / 更新 / 删除 |
| POST | `/notes/pages/{pageId}/questions/{questionId}` | 页面关联题目快照（兼容 `/notes/{pageId}/...`） |
| POST / GET | `/note-pages/{pageId}/attachments` | 上传 / 列表 |
| GET / DELETE | `/attachments/{id}` | 元数据 / 删除 |
| GET | `/attachments/{id}/content` / `slides` / `entries` / `media` | 内容与预览衍生接口 |

### 2.6 备份与健康

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / PUT | `/data/backup/config` | 备份配置 |
| GET | `/data/backup/status` | 备份状态 |
| GET / POST | `/data/backups` | 列表 / 创建 |
| DELETE | `/data/backups/{name}` | 删除备份 |
| POST | `/data/backups/export` / `import` | 导出 / 导入 |
| POST | `/data/backups/{name}/restore` | 恢复（staged 校验 + 安全事务） |
| GET | `/health` | 健康检查 |

## 3. AI 域

### 3.1 配置与模型

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / PUT | `/ai/config` | 读取 / 保存模型配置（密钥密文） |
| POST | `/ai/models` | 拉取可用模型列表 |
| POST | `/ai/models/test` | 测试连接 |
| POST | `/ai/summarize` | 知识点 / Markdown 总结 |

`PUT /ai/config` 的 `params` 保存协议、流式、缓存、工具开关等；未显式提供的字段沿用既有值。

### 3.2 对话

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/ai/chat` | 普通对话 |
| POST | `/ai/chat/stream` | SSE 流式对话 |

v2 请求核心字段：

```json
{
  "protocolVersion": 2,
  "requestId": "客户端生成的请求 ID",
  "sessionId": 1,
  "input": {
    "role": "user",
    "userText": "用户输入",
    "attachments": [{ "blockId": "...", "kind": "text|image", "text": "..." }]
  },
  "pageContext": { "kind": "quiz|notebook|knowledge", "pageId": 1, "markdown": "页面上下文" },
  "retrievalOptions": { "scope": "all|current", "notebookId": 1 },
  "memoryOptions": { "use": true }
}
```

- `protocolVersion` 必须为 2；v1 仅保留旧客户端兼容路径，不是新功能目标。
- `input.attachments` 的文本去 `data:` 前缀后按 base64 传输；图片大小受请求体上限约束。
- 同一会话同一时刻只允许一个 `RUNNING` 轮次；重复请求返回 409 与 `runningTurn`。

SSE 事件格式：`event:<name>` + `data:<json>`；事件名：

| 事件 | 载荷 | 说明 |
|---|---|---|
| `text` | `{ requestId, text }` | 回答增量 |
| `reasoning` | `{ requestId, text }` | 思考增量 |
| `metadata` | 含 `requestId`、`planId` 等 | 本轮计划状态；前端据此登记请求 ID |
| `done` | `reply`、`reasoning`、`view` | 正常结束；`view` 含 citations / memoryContext / contextUsage / contextAudit / usage / planId / dispatchState |
| `error` | `{ requestId, code, message, view }` | 失败结束 |

若流在没有 `done` / `error` 的情况下 EOF，客户端必须用 `requestId` 查询轮次状态，不能把占位内容当作完成。

#### 3.2.1 对话内记忆工具

仅当 `protocolVersion = 2`、`memoryOptions.use = true`、聊天模型有效 `tools = true` 且服务端已注册工具时，聊天请求才携带工具定义。`create_memory` / `update_memory` 与读工具共用同一个「工具调用」开关；关闭后模型不接收任何记忆工具，也不会写入。

- `PUT /ai/config` 的 `params.tools` 是显式覆盖值；未提供时保留原值。`GET /ai/config` 的 `chat.tools` 返回当前有效值。
- 默认开启：`api.openai.com`、`api.deepseek.com`，以及 `api.anthropic.com` + Claude Messages。自定义或未验证端点默认关闭，须由用户显式开启。
- 导入兜底模型不启用工具。

| 工具 | 参数 | 输出 |
|---|---|---|
| `search_memories` | `query`：1–200 字符 | `{status, availability?, facts:[{text,kind,scope,expiry}], incomplete}`；单次 ≤5 条 |
| `list_memories` | `limit?`：1–20，默认 10；`scope?`：`GLOBAL` / `NOTEBOOK` / `ALL`，默认 `ALL` | `{status, availability?, items:[{title,scope,updatedAt}], incomplete}`；单次声明上限 20 条 |
| `create_memory` | `text`：1–1000 字符；`kind`：`PREFERENCE` / `GOAL` / `BACKGROUND` / `TASK`；`scope?`：`GLOBAL` / `NOTEBOOK`；`expiry?`：ISO-8601 UTC | `{status, memoryId?, version?, availability?, reason?}` |
| `update_memory` | `text`：1–1000 字符；定位二选一：`currentText`（检索结果中的现有正文）或 `memoryId` + `expectedVersion`；`expiry?`：ISO-8601 UTC，省略保留原值 | `{status, memoryId?, version?, availability?, reason?}` |

- `search_memories` 只返回回答所需事实，不返回记忆 ID、内部谓词、来源密文或原始分数。
- `list_memories` 只返回标题、作用域与更新时间；标题由正文压缩空白后取前 20 个码点派生，空值兜底「未命名记忆」；`updatedAt` 取 `last_confirmed_at`，无值时为 `null`。不返回正文、记忆 ID、内部谓词或来源密文。
- 作用域：GLOBAL 会话只列/写 GLOBAL；NOTEBOOK 会话列「本册 + GLOBAL」，写入默认本册，也可显式指定 GLOBAL。GLOBAL 会话请求 `scope=NOTEBOOK` 返回 `INVALID_ARGUMENT`。`update_memory` 不允许跨作用域移动：只能更新当前会话可见的 NOTEBOOK 本册或 GLOBAL 记忆，其他笔记本的记忆返回 `NOT_FOUND`。
- `list_memories` 当前不提供 cursor；结果超出 `limit` 或索引快照不完整时用 `incomplete=true` / `LIMIT_REACHED` 表示还有未返回内容。
- `create_memory` 按归一值（折叠空白、小写）全库精确去重；`DUPLICATE` 返回已有 `memoryId/version`，不新增版本。凭据与敏感内容返回 `REJECTED`，`reason` 为 `CREDENTIAL` / `SENSITIVE`。成功时返回 `CREATED` 与定位信息，不回显正文。
- `create_memory` 写入来源在 `ai_memory_item.origin` 记为 `AUTO`（v9 约束只有 `AUTO/MANUAL`），载荷记录 `createdBy=AI_TOOL`；事实按 `manualProtected=true` 落库，删除来源会话时只断证据、不删除这条长期记忆。证据指向当前会话 / 轮次 / 用户消息。
- 家族抑制与显式重记：`create_memory` 发现新事实的 `FAMILY` 键命中删除留下的抑制时，只有当前用户消息本身含明确记忆请求（「记住」「加入记忆」等）才解除该 `FAMILY` 抑制并继续写入；否则返回 `SUPPRESSED`，不落库、不声称成功。`PREDICATE` / `EVIDENCE` 抑制保留给自动提取，不随显式重记解除。解除抑制后会同步重建索引，已有同值行也重新可见；若本次只命中已有同值行（`DUPLICATE`）而没有新建版本，不推进 vault revision，但仍会重建快照。
- `update_memory` 优先用 `currentText`（`search_memories` 返回的正文原样）在当前可见作用域内按归一值定位；歧义（同一作用域存在多条同值当前记忆）返回 `AMBIGUOUS`。也可用 `create_memory` 返回的 `memoryId + expectedVersion` 精确定位，版本不符返回 `STALE`。
- `update_memory` 归一值未变化返回 `NO_CHANGE`，不新增版本、不推进 revision；成功时旧版本转 `SUPERSEDED`、新版本 `ACTIVE`，证据继承到新版本，`origin` 转为 `MANUAL` 且 `manualProtected=true`。凭据与敏感内容返回 `REJECTED`，`reason` 为 `CREDENTIAL` / `SENSITIVE`。
- 写入会推进 vault revision；服务端在本轮内刷新计划快照并同步重建索引，使同轮后续检索能看到新版本。
- 读工具 `status`：`OK` / `NO_MATCH` / `UNAVAILABLE` / `STALE` / `LIMIT_REACHED` / `INVALID_ARGUMENT`；写工具另有 `CREATED` / `UPDATED` / `DUPLICATE` / `NO_CHANGE` / `NOT_FOUND` / `AMBIGUOUS` / `SUPPRESSED` / `REJECTED`。`availability`：`READY` / `BUILDING` / `LOCKED` / `TIMEOUT` / `NOT_REQUESTED` / `DENIED`。
- 预算：工具阶段 ≤2、执行 ≤3、上游请求 ≤3；单轮记忆条目合计 ≤15；单轮记忆写入（`create_memory` + `update_memory`）≤3；单次参数 ≤4096 UTF-8 字节；单次结果 ≤16 KiB。
- 工具调用与结果只存在于本轮内存，不写入会话消息、历史、SSE 或日志；SSE 只回放最终 `text` / `reasoning` / `done`。

### 3.3 轮次状态与上下文

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/ai/turns/{requestId}` | 查询轮次终态（reply / reasoning / 状态） |
| POST | `/ai/turns/{requestId}/cancel` | 取消运行中的轮次 |
| GET | `/ai/turns/{requestId}/context` | 读取上下文审计详情 |
| POST | `/ai/turns/{requestId}/context/preview` | 预览上下文计划 |

### 3.4 会话与历史

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / POST | `/ai/sessions` | 会话列表 / 新建 |
| PUT / DELETE | `/ai/sessions/{id}` | 重命名 / 删除（运行中删除返回 409） |
| GET | `/ai/sessions/{id}/messages` | 消息列表（解密） |
| GET | `/ai/messages` | 全局消息查询 |
| GET | `/ai/sessions/{id}/history` | 历史查询 |
| GET / PUT | `/ai/history/storage` | 历史容量配置 / 状态 |
| POST | `/ai/history/cleanup/preview` / `cleanup` | 清理预览 / 执行 |
| GET | `/ai/sessions/{id}/export` | 导出会话 |

### 3.5 记忆

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / PUT | `/ai/memory/settings` | 记忆启用、自动提取、摘要、策略 |
| POST / DELETE | `/ai/memory/consents` / `consents/{purpose}` | 授予 / 撤回用途同意 |
| POST | `/ai/memory/vault` / `vault/unlock` / `vault/lock` | 创建 / 解锁 / 锁定记忆库 |
| PUT | `/ai/memory/vault/password` | 修改密码 |
| POST | `/ai/memory/vault/reset` | 重置记忆库 |
| POST / GET / PATCH / DELETE | `/ai/memory/items...` | 新建 / 详情 / 编辑 / 单条删除 |
| POST | `/ai/memory/items/{memoryId}/review` | 复核记忆版本 |
| GET / POST | `/ai/memory/proposals` / `proposals/{id}/review` | 提案列表 / 复核 |
| POST | `/ai/memory/clear` | 清空授权范围记忆 |
| POST | `/ai/memory/search` | 管理页文本过滤（不是模型上下文语义检索） |
| GET | `/ai/memory/status` | 初始化、解锁、活动事实与队列状态 |

### 3.6 检索与嵌入

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/ai/retrieval/status` | 索引状态、覆盖率、providerReady |
| POST | `/ai/retrieval/reindex` | 全库重建（missing / full） |
| POST | `/ai/retrieval/retry-failed` | 重试失败任务 |
| POST | `/ai/embeddings/test` | 测试远程 / 本地嵌入 |
| GET | `/ai/embeddings/catalog` | 模型目录 |
| POST | `/ai/embeddings/models/{id}/download` | 下载模型 |
| DELETE | `/ai/embeddings/downloads/{jobId}` | 取消下载 |
| POST | `/ai/embeddings/models/{id}/activate` / `disable` | 启用 / 停用模型 |
| DELETE | `/ai/embeddings/models/{id}` | 卸载模型与向量 |

检索、嵌入、摘要的请求 / 响应细节见 [ai-retrieval.md](ai-retrieval.md) 与 [ai-summary.md](ai-summary.md)。
