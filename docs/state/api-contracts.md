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
| GET / PUT | `/ai/config` | 兼容读取与非集合用途配置；已迁移 chat/import 写入须改用 connections |
| POST | `/ai/models` | 拉取可用模型列表 |
| POST | `/ai/models/test` | 测试连接 |
| POST | `/ai/summarize` | 知识点 / Markdown 总结 |

当前 AI 连接管理统一使用 `/ai/connections`，响应返回 `{revision, providers, chatModelId, importModelId}`，密钥不回传。修改携带 revision，冲突返回 409；保存与应用绑定独立。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/ai/connections` | 读取脱敏配置集合，首次读取幂等迁移旧配置 |
| POST | `/ai/connections/providers` | 新建供应商 |
| PUT / DELETE | `/ai/connections/providers/{id}` | 更新 / 删除供应商 |
| GET | `/ai/connections/providers/{id}/presets` | 当前连接可用的本地参考预设 |
| POST | `/ai/connections/providers/{id}/discover` | 获取模型 ID；不直接保存或应用 |
| POST | `/ai/connections/providers/{id}/models` | 直接新增模型配置 |
| POST | `/ai/connections/providers/{id}/verify-add` | 验证候选并按文本结果添加 |
| PUT / DELETE | `/ai/connections/models/{id}` | 更新 / 删除模型；路径 ID 为稳定 UUID |
| PUT | `/ai/connections/bindings` | `{revision, purpose:chat|import, modelId}`；import 的 null 恢复跟随 |
| POST | `/ai/connections/models/{id}/test` | 短连接测试，不带聊天历史 |
| POST | `/ai/connections/tests/{requestId}/cancel` | 取消连接测试或新增验证，支持提前取消 |

- 供应商视图包含 `connectionVersion`；`verify-add` 接收 `revision`、`connectionVersion`、`requestId`、`model`、可选 `masterPassword`。UI 最多 3 个模型并发验证；同连接的结果合入最新集合，不因其他模型刚保存而相互覆盖。连接身份变更时拒绝迟到结果。
- 文本返回有效正文才添加；图片/工具验证各自返回 status/message/elapsedMs，附 `added`、`modelId`、`connections`。工具验证成功才自动开启工具偏好；已存在同 ID 模型不重复验证或改写。添加不自动应用。
- 更新上游 `modelId` 保留内部 ID、用途绑定与用户参数，只清理旧验证/探测记录；字段缺省继承，明确空值不重新补填预设。模型 ID 输入只修改 ID，点击/回车选择预设才应用参考参数。
- 模型可配置 `contextWindow/maxInputTokens/maxOutputTokens`，运行时仍受任务预算约束。每轮冻结模型配置，切换应用从下一轮生效；被用途绑定或运行中请求引用的对象不能删除。
- `reasoningControl` 默认为 effort；`reasoningEfforts` 可选 `none/minimal/low/medium/high/xhigh/max`，拒绝 ultra，默认档位必须属于集合。Anthropic 不接受 none/minimal；预算方式仅用于 Anthropic。服务默认不发送思考参数；协议映射为 Chat Completions 的 `reasoning_effort`、Anthropic 的 `output_config.effort` 或 `thinking` 预算。

#### 3.1.1 全局对话运行限制

`GET /ai/runtime-settings` 返回 `{revision, values, defaults}`；`PUT` 接收 `{revision, values}`，保存成功返回同形快照。严格校验整数与跨字段关系，冲突返回 `409 AI_SETTINGS_CONFLICT`。独立于供应商/模型，每轮开始读取一次快照；恢复默认由前端回填 defaults 草稿，再显式保存。

| 字段 | 默认值 | 范围 |
|---|---:|---:|
| `maxToolStages` | 50 | 1–500 |
| `maxToolExecutions` | 100 | 1–1000 |
| `turnTimeoutMinutes` | 30 | 1–240 |
| `requestTimeoutMinutes` | 15 | 1–120，不能大于整轮时间 |
| `maxOutputTokens` | 128000 | 1024–2000000 |
| `requestOutputTokens` | 16384 | 256–131072 |
| `finalOutputTokens` | 8192 | 256–65536，必须小于整轮输出上限 |
| `repeatedToolLimit` | 3 | 0–20；0 关闭检查 |

输出预算按各请求实际 output usage 累计，缺少 usage 时仅在执行预算中保守扣除分配额度。工具阶段/执行/输出达限后撤工具，并保留一次最终回答请求；接近总期限提前收口。相同工具名、规范化参数和相同结果连续达到阈值才判无进展；模型上下文容量仍可提前收紧执行。

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
| `text` | `{ text }` | 回答增量；身份由本连接 metadata 确认 |
| `reasoning` | `{ text }` | 思考增量 |
| `metadata` | 含 `requestId`、`planId` 等 | 本轮计划状态；前端据此登记请求 ID |
| `tool_activity` | `{requestId, sessionId, toolActivities, phase}` | 有界活动快照；phase 为 tools / answer |
| `done` | 普通 chat 响应字段，额外含 reasoning | 顶层含 requestId/sessionId/status/persistenceStatus/stateDurable、finishReason、toolActivities 及上下文/用量字段 |
| `error` | `{message, partial, requestId?, sessionId?, code?, persistenceStatus?, stateDurable?, toolActivities?}` | 如实保留身份、保存状态与已确认活动 |

若流在没有 `done` / `error` 的情况下 EOF，客户端必须用 `requestId` 查询轮次状态，不能把占位内容当作完成。

SSE 每 10 秒发送注释心跳，不再固定五分钟关闭；生命周期由任务与全局时间预算管理，断连取消仍在执行的轮次。关闭模型正文流式不关闭活动事件。`finishReason` 包含 `budget_stages/executions/output/time/context/repeated`、`request_timeout`、`request_timeout_idle`、`stream_error` 等；达限后的「继续」使用新 requestId 和可见后续消息，不自动重放原工具。

#### 3.2.1 对话内记忆工具

仅当 `protocolVersion = 2`、`memoryOptions.use = true`、聊天模型有效 `tools = true` 且服务端已注册工具时，聊天请求才携带工具定义。`create_memory` / `update_memory` 与读工具共用同一个「工具调用」开关；关闭后模型不接收任何记忆工具，也不会写入。

- 模型配置的 `params.tools` 是显式覆盖值；当前通过 `/ai/connections/models/{id}` 更新。`GET /ai/config` 的 `chat.tools` 仍返回当前有效值。
- 默认开启：`api.openai.com`、`api.deepseek.com`，以及 `api.anthropic.com` + Claude Messages。未验证的自定义端点默认关闭；新增模型工具验证成功自动开启，显式偏好优先。
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
- `list_memories` 当前不提供 cursor；结果超出 `limit` 或索引快照不完整时标记 `incomplete=true`。聊天编排将带有效条目的旧 `LIMIT_REACHED` 读结果投影为 `OK + incomplete`，与真正耗尽调用预算区分。
- `create_memory` 按归一值（折叠空白、小写）全库精确去重；`DUPLICATE` 返回已有 `memoryId/version`，不新增版本。凭据与敏感内容返回 `REJECTED`，`reason` 为 `CREDENTIAL` / `SENSITIVE`。成功时返回 `CREATED` 与定位信息，不回显正文。
- `create_memory` 写入来源在 `ai_memory_item.origin` 记为 `AUTO`（v9 约束只有 `AUTO/MANUAL`），载荷记录 `createdBy=AI_TOOL`；事实按 `manualProtected=true` 落库，删除来源会话时只断证据、不删除这条长期记忆。证据指向当前会话 / 轮次 / 用户消息。
- 家族抑制与显式重记：`create_memory` 发现新事实的 `FAMILY` 键命中删除留下的抑制时，只有当前用户消息本身含明确记忆请求（「记住」「加入记忆」等）才解除该 `FAMILY` 抑制并继续写入；否则返回 `SUPPRESSED`，不落库、不声称成功。`PREDICATE` / `EVIDENCE` 抑制保留给自动提取，不随显式重记解除。解除抑制后会同步重建索引，已有同值行也重新可见；若本次只命中已有同值行（`DUPLICATE`）而没有新建版本，不推进 vault revision，但仍会重建快照。
- `update_memory` 优先用 `currentText`（`search_memories` 返回的正文原样）在当前可见作用域内按归一值定位；歧义（同一作用域存在多条同值当前记忆）返回 `AMBIGUOUS`。也可用 `create_memory` 返回的 `memoryId + expectedVersion` 精确定位，版本不符返回 `STALE`。
- `update_memory` 归一值未变化返回 `NO_CHANGE`，不新增版本、不推进 revision；成功时旧版本转 `SUPERSEDED`、新版本 `ACTIVE`，证据继承到新版本，`origin` 转为 `MANUAL` 且 `manualProtected=true`。凭据与敏感内容返回 `REJECTED`，`reason` 为 `CREDENTIAL` / `SENSITIVE`。
- 写入会推进 vault revision；服务端在本轮内刷新计划快照并同步重建索引，使同轮后续检索能看到新版本。
- 读工具 `status`：`OK` / `NO_MATCH` / `UNAVAILABLE` / `STALE` / `LIMIT_REACHED` / `INVALID_ARGUMENT`；写工具另有 `CREATED` / `UPDATED` / `DUPLICATE` / `NO_CHANGE` / `NOT_FOUND` / `AMBIGUOUS` / `SUPPRESSED` / `REJECTED`。`availability`：`READY` / `BUILDING` / `LOCKED` / `TIMEOUT` / `NOT_REQUESTED` / `DENIED`。
- 运行预算见 §3.1.1，读取/写入共享执行次数；取消旧的整轮 15 条记忆与独立 3 次写入限制。单次参数 ≤4096 UTF-8 字节、结果 ≤64 KiB；单响应最多解析 128 个调用，超量明确标记捕获截断。结果大小、上下文、重复与时间限制各有独立状态码。
- 原始工具调用与结果只存在于本轮内存，不写入会话消息、历史、SSE 或日志；工具模式只回放最终正文/思考。安全活动摘要通过独立事件与最终结果输出并加密持久化。

#### 3.2.2 工具活动摘要

`toolActivities` 最多 1200 项，按服务端 `sequence` 关联，字段为 `toolName/state/outcome/startedAt/elapsedMs/count/availability/incomplete`；toolName 只允许已注册工具或 `unknown_tool`。state 为 RUNNING / FINISHED / UNKNOWN，count 只描述本次返回/写入数。不得包含原始参数、查询文本、记忆正文、ID、版本、密钥或上游错误正文。断连不推断写入失败；没有最终确认的运行项投影为 UNKNOWN。状态与历史读取不会重新执行工具。

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
| POST | `/ai/sessions/{id}/title` | 异步生成 / 重新生成标题（202） |
| GET | `/ai/sessions/{id}/messages` | 消息列表（解密） |
| GET | `/ai/messages` | 全局消息查询 |
| GET | `/ai/sessions/{id}/history` | 历史查询 |
| GET / PUT | `/ai/history/storage` | 历史容量配置 / 状态 |
| POST | `/ai/history/cleanup/preview` / `cleanup` | 清理预览 / 执行 |
| GET | `/ai/sessions/{id}/export` | 导出会话 |

轮次状态和历史助手消息返回 `toolActivities` 与 `finishReason`；旧消息缺少活动时返回空列表。删除会话或清理历史仅断开相应来源证据，保留已保存长期记忆及原来源类型；删除笔记本的 NOTEBOOK scope 清理仍独立执行。

会话列表、新建、更新和标题任务响应增加 `sessionLink`、`titleSource`、`titleStatus`、`titleRevision`、`canGenerateTitle`。`sessionLink` 格式为 `drill-notebook://session/<32位小写十六进制引用>`；仅是本地引用，不注册系统协议，当前 MVP 只支持复制，跨会话读取工具尚未接入。

标题 POST 请求为 `{expectedRevision: number, overwriteManual?: boolean}`。首个完整且保存成功的问答可作为素材；无素材返回 409 `TITLE_NO_EXCHANGE`，人工名未确认覆盖返回 `TITLE_CONFIRM_REQUIRED`，版本变化返回 `TITLE_CHANGED`，任务重复或旧任务仍在收尾返回 `TITLE_BUSY`。缺少应用模型返回 `TITLE_MODEL_UNAVAILABLE`，队列满返回 503 `TITLE_QUEUE_FULL`，会话不存在返回 404 `SESSION_NOT_FOUND`。202 返回已认领的会话快照，随后从会话列表读取 generating → ready/failed；失败保留原名。解锁材料只从进程会话取，不进 URL。

新会话首个 COMPLETED 且持久化成功的问答自动提交标题任务；中断/失败不触发，之后首个成功问答可触发。自动失败、重启或切模型都不自动重试；人工重命名始终优先，标题变更不改变列表顺序。自动任务沿用本轮模型快照，手动任务用当前应用模型；仅发送首个完整问答的正文片段，不带附件、工具结果或整段历史。

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

检索、嵌入、摘要的请求 / 响应细节见 [ai-retrieval.md](../reference/ai-retrieval.md) 与 [ai-summary.md](../reference/ai-summary.md)。
