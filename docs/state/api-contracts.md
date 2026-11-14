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
| GET / PUT / DELETE | `/questions/{id}` | 题目详情（含 bankId） / 更新 / 删除；目标不存在返回 404 |
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

当前 AI 连接管理统一使用 `/ai/connections`，响应返回 `{revision, providers, chatModelId, chatReasoningEffort, importModelId}`，密钥不回传。修改携带 revision，冲突返回 409；配置编辑、助手模型选择和导入绑定分别操作。chatModelId / chatReasoningEffort 保存新会话继承的最近选择，已有会话使用各自持久化的模型与档位。

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
| PUT | `/ai/connections/bindings` | `{revision, purpose:import, modelId}`；null 恢复跟随助手模型，其他用途拒绝 |
| PUT | `/ai/connections/chat-model` | 保存 `{revision, modelConfigId, reasoningEffort, sessionId?}`；更新新会话继承默认，有 sessionId 时同事务更新该会话；成功返回新集合及 `sessionChoice?` |
| POST | `/ai/connections/models/{id}/test` | 短连接测试，不带聊天历史 |
| POST | `/ai/connections/tests/{requestId}/cancel` | 取消连接测试或新增验证，支持提前取消 |

- 供应商视图包含 `connectionVersion`；`verify-add` 接收 `revision`、`connectionVersion`、`requestId`、`model`、可选 `masterPassword`。UI 最多 3 个模型并发验证；同连接的结果合入最新集合，不因其他模型刚保存而相互覆盖。连接身份变更时拒绝迟到结果。
- 文本返回有效正文才添加；图片/工具验证各自返回 status/message/elapsedMs，附 `added`、`modelId`、`connections`。工具验证成功才自动开启工具偏好；已存在同 ID 模型不重复验证或改写。添加不改变助手或导入模型的选择。
- 更新上游 `modelId` 保留内部 ID、用途绑定与用户参数，只清理旧验证/探测记录；字段缺省继承，明确空值不重新补填预设。模型 ID 输入只修改 ID，点击/回车选择预设才应用参考参数。
- 模型必须填写 `contextWindow/maxOutputTokens`：均为 1–2147483647 的整数，输出不得超过窗口。预设可填入真实容量，旧缺值模型保留原配置与绑定并提示补全；保存、选择、测试、对话及后台维护缺值时拒绝执行，不再使用缺省保守窗口或发出 WINDOW_UNSET。`maxInputTokens` 已取消，旧值忽略、再次保存时移除；输入与输出共用窗口，正常安全余量及全局任务预算继续生效。每轮冻结配置，改选模型从下一次受理生效，已受理的轮次和待发消息保留原配置；被用途绑定或运行中请求引用的对象不能删除。
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
| `retry.httpRetries` | 4 | 0–100；0 关闭请求重试 |
| `retry.streamRetries` | 5 | 0–100；0 关闭回答重连 |
| `retry.networkPersistent` | true | 布尔；持续等待网络，仍受整轮时间和停止约束 |
| `retry.networkRetries` | 5 | 0–100；仅有限断网模式使用，0 关闭断网重试 |

v0.8.1 将上述四项嵌套在 `values.retry` / `defaults.retry`。旧持久化 JSON 缺少 retry 时补默认值；旧客户端 PUT 未提交 retry 时保留当前已保存配置。显式提交时须提供完整四项并严格校验类型 / 范围，原 revision 冲突规则继续生效；不新增 schema 或改变已有预算。

输出预算按各请求实际 output usage 累计，缺少 usage 时仅在执行预算中保守扣除分配额度。工具阶段/执行/输出达限后撤工具，并保留一次最终回答请求；接近总期限提前收口。相同工具名、规范化参数和相同结果连续达到阈值才判无进展；模型上下文容量仍可提前收紧执行。

会话工具产生的自动派发链共用来源轮的时间、输出和工具预算；并发请求先原子预留输出额度，实际结算后释放余量。人工在任一会话发起的新轮次建立自己的预算。

#### 3.1.2 联网服务设置（v0.8.2）

| 方法 | 路径 | 契约 |
|---|---|---|
| GET | `/ai/web-settings` | `{revision,searchProvider,fetchProvider,preferNative,providers:{tavily:{hasKey},tinyfish:{hasKey}}}`；只返回公开字段 |
| PUT | `/ai/web-settings` | `{revision,searchProvider?,fetchProvider?,preferNative?,keys?}`；局部 CAS 更新，返回同形快照；冲突 409 AI_SETTINGS_CONFLICT |
| POST | `/ai/web-settings/test` | 使用相同服务 / 密钥草稿，可选 `capability=search\|fetch\|both`（默认 both）；返回 `{checks:[{capability,status,count,webProvider?,webRoute}]}`，不保存、不改权限、不返回正文 |

searchProvider / fetchProvider 分别为 `auto|tavily|tinyfish`，默认 auto，preferNative 默认 false。`keys` 以供应商名为键，每家只有一份 `{apiKey?,clear?}`；空密钥保留，明确 clear=true 删除，不允许同时删除和修改，选择自定义服务须有可用密钥。明文输入只用于后端加密与实际请求，不在 GET、测试结果、页面上下文或活动摘要回显。配置坏值 / 未知字段拒绝，不用默认值掩盖。

测试使用固定公开查询与 URL，成功要求有效来源 / 正文，不能以握手或 HTTP 200 代替；能力错误独立返回。原生优先按本轮受理的模型、端点与协议执行，测试草稿不另发原生模型请求。连接模型公开元数据增加 `nativeWeb:{search,fetch,protocol}`，protocol 为 none / anthropic_messages / openai_responses；不代表账户权限已验证。支持矩阵见[规格](../work/web-search/spec.md#原生联网支持矩阵)。

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
  "pageSnapshot": {
    "kind": "quiz", "title": "刷题", "route": "/practice", "questionId": 1,
    "capturedAt": "2026-11-09T10:00:00Z", "markdown": "提交时已显示的题面",
    "isDraft": false, "truncated": false
  },
  "memoryOptions": { "use": true }
}
```

- `protocolVersion` 必须为 2；v1 仅保留旧客户端兼容路径，不是新功能目标。
- `pageSnapshot` 可选，只提交到本地后端，模型初始请求只收到页面定位提示，正文经 `read_current_page` 按需取得。快照正文和选区不进入明文 request/result 元数据；请求选项仅含快照指纹，参与幂等校验。新前端不发送 `pageContext` / `retrievalOptions`，不读取旧检索偏好；后端仍接受旧客户端的自动上下文协议。
- 快照 `kind` 支持 `none / note / knowledge / bank / quiz / wrong / manual / settings / calendar`。设置与日历可不带资料 ID，分别通过 `/settings?tab=...` 与 `/calendar?date=...` 定位；正文依然上限 32,000 码点、选区 2,000 码点，沿用 `isDraft / truncated`。设置只登记当前分区允许读取的选项与状态，密钥 / 密码、连接地址、请求头及记忆正文不进入设置快照；日历登记当前月份、日期、计划与筛选后的学习队列。加载失败须在正文说明，不能伪装成空数据。
- `input.attachments` 的文本去 `data:` 前缀后按 base64 传输；图片大小受请求体上限约束。
- 同一会话同一时刻只允许一个 `RUNNING` 轮次；v2 同 requestId、同输入的重试复用原收据与结果，输入改变返回 `409 REQUEST_ID_CONFLICT`。直接向聊天接口提交忙碌会话的另一条请求仍返回 `409 SESSION_BUSY`；前端人工排队改走 §3.4.1 队列接口，与工具派发按序接续。尚无记录的请求 ID 仍须满足 UUIDv7 时效要求。
- `retryOf` 是 v2 顶层可选字段，指向同一会话中要重新回答的当前 requestId。v0.8.2 起重新生成使用新 requestId，在同一事务中复用原 turnId / userMessageId / assistantMessageId，替换原回答，不追加问答；原提问与完整附件必须一致，可通过 §3.3 的只读接口取得。按受理时的目标会话模型与本次页面快照生成，以原轮次为上下文截止点，不带入其后问答。原消息时间与顺序保留，本次重新受理冻结新的运行配置；自动恢复仍共用正在运行的原请求与预算。
- 原地重试将被替换 requestId 写入 `ai_chat_retry_receipt`，旧 ID 返回 `409 TURN_SUPERSEDED`，details 带 `replacementRequestId` 和 sessionId。同新 requestId 的重发仍复用本次收据；资料写入工具可沿当前轮次与旧回执的 retryOf 链查重，参数改变仍拒绝冲突。历史累计用量包含原轮次各次尝试的实测记录，不能把旧 ID 作为另一条新消息再次执行。

SSE 事件格式：`event:<name>` + `data:<json>`；事件名：

| 事件 | 载荷 | 说明 |
|---|---|---|
| `text` | `{ text }` | 回答增量；身份由本连接 metadata 确认 |
| `reasoning` | `{ text }` | 思考增量 |
| `metadata` | 含 `requestId`、`planId`、`recovery?` 等 | 本轮计划与恢复状态；前端据此登记请求 ID |
| `tool_activity` | `{requestId, sessionId, toolActivities, phase}` | 有界活动快照；phase 为 tools / answer，精确读取等待期间可为 waiting |
| `done` | 普通 chat 响应字段，额外含 reasoning | 顶层含 requestId/sessionId/status/persistenceStatus/stateDurable、finishReason、toolActivities、recovery? 及上下文/用量字段 |
| `error` | `{message, partial, requestId?, sessionId?, code?, persistenceStatus?, stateDurable?, toolActivities?, recovery?}` | 如实保留身份、保存状态、恢复原因与已确认活动 |

若流在没有 `done` / `error` 的情况下 EOF，客户端必须用 `requestId` 查询轮次状态，不能把占位内容当作完成。

SSE 每 10 秒发送注释心跳，不再固定五分钟关闭。v2 任务由后端持有，断连只取消订阅，任务继续；客户端从运行快照或同 requestId 查询恢复，停止必须显式调用。旧 v1 保留断连取消的兼容行为。关闭模型正文流式不关闭活动事件。`finishReason` 包含 `budget_stages/executions/output/time/context/repeated`、`request_timeout`、`request_timeout_idle`、`stream_error` 等；达限后的「继续」使用新 requestId 和可见后续消息，不自动重放原工具。

#### 3.2.1 对话内记忆工具

仅当 `protocolVersion = 2`、`memoryOptions.use = true`、聊天模型有效 `tools = true` 且服务端已注册记忆工具时，聊天请求才携带记忆工具定义。`create_memory` / `update_memory` 与读工具共用同一个「工具调用」开关；关闭后模型不接收任何记忆工具，也不会写入。会话及资料读取工具独立于记忆开关，见 §3.2.3、§3.2.4。

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

`toolActivities` 最多 1200 项，按服务端 `sequence` 关联，字段为 `toolName/state/outcome/startedAt/elapsedMs/count/availability/incomplete`；toolName 只允许已注册工具或 `unknown_tool`。会话派发额外允许严格校验的本地 `sessionLink` 与 UUIDv7 `requestId`，供用户明确点击打开目标；不包含发送正文、原始参数、查询文本、记忆正文/ID/版本、密钥或上游错误正文。state 为 RUNNING / FINISHED / UNKNOWN，count 只描述本次返回/写入数。断连不推断写入失败；没有最终确认的运行项投影为 UNKNOWN。状态与历史读取不会重新执行工具。

会话工具显示「查找历史会话 / 读取会话」，查询范围为当前本地数据中的会话。`read_session` 的 count 只计含可读正文的消息片段，不把解密失败占位计为已读取；`incomplete` 标注部分范围，`availability=UNREADABLE` 表示内容无法解密。

写工具显示「新建会话 / 发送消息」，CREATED/SENT/QUEUED 只表示受理，不代表目标任务完成。读取仍在排队/运行的发送项时只说明查询到的状态，不声称已读到回复。活动详情不保存发出的消息正文。

资料工具显示「读取当前页面 / 查找学习资料 / 读取笔记 / 读取知识点 / 读取题库 / 读取错题 / 新增学习资料」。活动额外允许严格校验的本地 `sourceLink`，成功新增的详情提供「查看资料」；不保存标题或正文。保存结果未知显示核对提示，已确认保存仍有效。正文引用沿用同一来源格式。

联网活动显示「搜索网页 / 读取网页」与实际服务；额外允许 `webProvider` 和最多 4 项 `webRoute:[{provider,status,httpStatus?,retryAt?}]`。provider 仅 exa / tavily / tinyfish / local / anthropic / openai，状态和 HTTP 状态码经白名单校验，retryAt 只接受有效 UTC 时刻。保留有界失败路径，不保存查询、URL、外部会话标识、网页正文、密钥或上游任意错误文字。

#### 3.2.3 对话内会话工具（v0.7.2）

`protocolVersion=2`、模型有效 `tools=true` 时提供；不依赖 `memoryOptions.use`、记忆库建立/授权或索引就绪。与记忆工具共用双协议适配、取消、全局预算和活动记录。导入模型与旧 v1 请求不带这些工具。

| 工具 | 参数 | 返回 |
|---|---|---|
| `list_sessions` | `query?` 标题子串（1–200 字符）、`beforeSessionId?` 正整数、`limit?` 1–20（默认 10）、`includeArchived?` 默认 false | `status/items/hasMore/nextBeforeSessionId/incomplete`；按 ID 倒序排，排除当前会话，不读取正文 |
| `read_session` | `session` 完整本地链接 / 32 位引用 / 数字 ID / 标题关键词（字符串，1–200 字符）、`cursor?` 原样续页游标、`limit?` 1–20（默认 10） | `status/session/messages/hasMore/nextCursor/incomplete` 与读取范围；多候选返回 `AMBIGUOUS/candidates/hasMoreCandidates`，没有正文 |
| `read_session` 精确发送结果 | `session` 精确链接 / 引用 / ID、`requestId`、`waitSeconds?` 0–60（默认 0）；不能与 cursor/limit 混用 | `status/runStatus/requestId/session/messages/incomplete`；排队/运行时返回状态与空正文，终态只读该次问答，最多 6000 码点并明确截断 |
| `create_session` | `message` 1–2000 Unicode 码点，`title?` 1–80 码点，仅临时标题 | `CREATED`、稳定 sessionLink、session 摘要、requestId、queuePosition、state；创建普通会话与首条收据后异步运行 |
| `send_message` | `session` 精确链接 / 引用 / ID、`message` 1–2000 码点；不接受模糊标题 | 空闲返回 SENT，忙碌返回 QUEUED；附同形受理字段。queuePosition=0 表示队头，1 表示有一项在前 |

- 标题/候选条目为 `sessionId/title/titleTruncated/sessionLink/updatedAt/archived/scope/notebookId`；列表额外带 `runStatus/queueCount`。工具内标题最多 200 个 Unicode 码点，原库标题不改。GLOBAL 与 NOTEBOOK 都在本机单用户可访问范围，记忆 scope 不作为会话权限；精确链接可读归档会话。外部 URL/路径不解引用。
- 正文按消息 ID 正序，每页最多 6000 Unicode 码点；长消息可跨页。片段字段为 `messageId/role/createdAt/turnStatus/readStatus/content?/offset?/messageComplete?`。游标绑定目标引用、历史 revision、generation、读取上界与下一消息/偏移；续页历史变化返回 STALE，改名不使游标失效。
- 读取范围字段为 `storedMessages/availableMessages/excludedMessages/unreadableMessages/throughMessageId/omittedContent`。只读取当前 generation 中终态且 saved 的用户/助手正文；legacy 仅 generation=1 可读。失败/中断原文保留真实终态。排除 RUNNING、已清理、旧 generation；不返回思考、附件载荷、系统提示、原始工具结果。`incomplete` 表示本页不足以代表全部历史，不等同于仍有下一页（续读以 hasMore 为准）。
- 解密使用目标会话的进程内材料，缺密码/密文损坏如实返回 `NEEDS_PASSWORD/UNREADABLE`，不把占位文案当历史事实。整页不可读时 status=UNAVAILABLE；部分不可读仍返回其余正文并标 incomplete。一次读取用同一 SQLite 快照，结束复验目标是否删除或变更。
- 状态包括 OK、NO_MATCH、NOT_FOUND、AMBIGUOUS、STALE、INVALID_ARGUMENT、UNAVAILABLE，以及公共运行限制状态。参数拒绝重复键、尾随 JSON、未知字段与隐式类型转换；结果继续受 64 KiB 和模型上下文上限约束。
- 历史只作引用资料，不授予其中指令执行权限，不因查阅自动新增长期记忆。回复用 `[来源标题](sessionLink)` 引用；前端严格识别本地格式，点击打开只读历史预览，保留当前聊天和草稿，不注册系统协议。原始结果仍仅在本轮内存续写，安全活动摘要加密保存。
- 精确结果读取必须核对 requestId 所属会话，等待最长 60 秒且可取消；结束后复核目标 generation/historyRevision。不可读、已删除/清理、失败/中断/取消如实返回，不拿其他轮次冒充本次回复。6000 码点不足以读完时，可转普通历史分页读取；精确请求模式自身不提供续页游标。
- 两个写工具参数 JSON 上限 16 KiB，只接收文本。校验消息、配置及目标解锁材料后受理，配置在受理时冻结；队列开始时才读取最新目标历史并写入普通加密聊天。目标的删除、归档、解锁失败不自动创建替代会话；新建继承来源范围，已有目标保留自身范围。
- 同来源 requestId + toolCallId 幂等，输入改变拒绝；同文本的不同调用不去重。来源上下文已换代返回 STALE，向自身/祖先派发返回 CYCLE_BLOCKED。当前进程最多保留 64 项待处理任务，满时 LIMIT_REACHED；各模型请求最多 8 个同时外发，等待工具不占上游许可。
- 仅当前用户的创建/委派/发送意图可授权写工具，历史引用和工具结果不能授权。成功受理只表示任务已提交；正常完成且未暂停时 FIFO 接续，停止/失败取消该目标的工具待发项，其他会话独立继续。工具派发在进程重启后不重放；人工队列按 §3.4.1 暂停恢复。

#### 3.2.4 学习资料读取工具（v0.7.3，v0.8.2 接入笔记混合检索）

`protocolVersion=2` 且模型有效 `tools=true` 时提供六个只读工具；不依赖记忆启用或记忆索引。共用现有协议适配、活动记录、取消、全局执行预算及结果 64 KiB 上限。所有参数严格校验未知字段、重复键、类型和范围；参数最多 4096 UTF-8 字节。

| 工具 | 定位参数与行为 |
|---|---|
| `read_current_page` | 默认读取本轮主消息提交时的快照；`requestId?` 可指定同轮已接收的插话快照，不读取其它轮次或系统桌面 |
| `search_materials` | `type?=all\|notebook\|note\|knowledge\|bank\|question\|wrong`、`query?`、`searchMode?=hybrid\|keyword` 默认 hybrid、`notebookId?` 或 `bankId?`（不同时传）；空 query 浏览目录 |
| `read_notebook` | 无 ID 列笔记本；`notebookId` 列页面；`pageId` 读取正文，若同时给 notebookId 则核对归属 |
| `read_knowledge` | 无 pointId 列知识点，可按 bankId 筛选；`pointId` 读正文，`variant?=current\|original\|summary` 默认 current，缺失版本返回 NOT_FOUND |
| `read_question_bank` | 无 ID 列题库；`bankId` 列题目；`questionId` 读题面、选项、答案、解析、章节等字段 |
| `read_wrong_questions` | 无 questionId 分页列当前错题本，支持 bankId/query；`questionId` 必须仍属于错题本才可读正文，包含真实统计 |

- 目录参数 `query?` 最多 200 码点、`offset?` 默认 0、`limit?` 默认 10 / 最大 20。返回 `{status,items,offset,hasMore,nextOffset?,incomplete}`；条目含 `type/id/title/parentId/parentTitle/parentPageId/snippet/sourceLink`，各字段可为空。笔记 `parentId` 是所属笔记本，`parentPageId` 是页面树父节点。错题额外返回 `attemptCount/wrongCount/errorRate`（0–1），沿用业务错题成员规则，不伪造统计。
- `searchMode` 仅 `search_materials` 接受，非空查询时回显请求模式；不能仅据此判断向量实际参与。`hybrid` 只对非空的 `type=note/all` 且无 bankId 的查询接入笔记混合检索；有 notebookId 时限制在该笔记本。其它资料仍使用业务表关键词查询。空查询和明确 ID 读取不调用 embedding。
- 混合搜索先保留字面匹配，再补充 BM25 / 向量经 RRF 排序的候选，按页面合并多块命中。词法与向量各最多 40 块，保留有界并集后再按页面去重；最终资料候选最多 80 项，offset/limit 在候选内分页。同名不同 ID 仍分别返回。`keyword` 只做参数化字面匹配，保留原有目录分页，适用于精确原文、名称、编号或符号。
- 搜索中的笔记额外返回 `pageId/notebookId`；非空查询含 `matchTypes`（`keyword/bm25/vector` 的实际来源）。混合补充项可含最佳命中 `chunkId/headingPath`，不返回内部哈希。每个 snippet 最多 240 Unicode 码点，单页最多 4,800；同次调用中重复笔记片段保留全部来源定位，后续项的 snippet 为空并给出 `snippetDuplicateOf` 来源链接。
- 混合候选池达到上限时 `searchLimited=true/incomplete=true`，notice 提示缩小范围或改用 keyword；`hasMore` 只表示当前候选还有可续页项，不能当作全库完整召回保证。向量未配置不附 notice；选中空间不健康、未就绪、重建 / 覆盖不足、provider 不匹配或异常 / 超时则使用 BM25，并附 `retrievalNotice:{code:"vector-index-unavailable",message}` 和 incomplete=true，不使工具因向量故障失败。返回前复核页面存在、当前归属与分块哈希。
- 目录结果在共享 64 KiB 硬上限前使用 60 KiB 软预算；Unicode 元数据导致超量时减少条目并返回 `byteLimited=true/hasMore=true/incomplete=true`，nextOffset 依据实际返回数量推进，续页不丢项。片段足够时可直接引用，需要前后文才调用 ID 读取工具。
- 正文使用 `offset?`、`maxChars?`（默认 4000 / 最大 8000 Unicode 码点）、`version?`；不接受 query/limit。返回单条 items，含定位字段、`text/version/offset/endOffset/totalChars`，顶层含 `version/hasMore/nextOffset?/incomplete`。offset > 0 必须带上次 version；版本变化返回 STALE，重新从 0 读取。目录不接受 maxChars/version/variant。offset 范围为 0–10,000,000。
- 已保存的笔记、知识点 current、题目 / 错题正文额外返回 `updateVersion`，用于 §3.2.6 更新校验，与分页 `version` 区分。知识点 original / summary 与页面快照不提供更新版本。指定 notebookId / bankId 的目录返回 `container:{type,id,title,description?,sourceLink,updateVersion,truncated}`，即使目录为空也可取得容器信息；容器文本最多 2000 码点。
- 笔记字面匹配使用现有 `retrieval_chunk` 文本的参数化 LIKE 查询，摘要优先取实际命中分块；混合查询复用既有 FTS5、embedding 空间与授权，不新增索引或资料副本。后续正文读取继续用明确 ID 与 version，保留公式、表格及嵌入题目快照；文件/视频/图片只提供引用或描述，不解析附件字节。完整范围与验证见[v0.8.2 清单](v0.8.2-features.md)。
- `pageSnapshot` 字段为 `kind/title/route/capturedAt/markdown/selection/isDraft/truncated` 和可选 `pageId/notebookId/pointId/bankId/questionId/selectedIds`。kind 为 none/note/knowledge/bank/quiz/wrong/manual；时间为 ISO Instant；路由限应用业务路径。正文最多 32000、选区 2000、标题 200、路由 500 码点，selectedIds 最多 20 个正整数；超限前端截短并标 truncated，后端拒绝非法输入。返回明确采集时间、草稿和截断状态。数据库中的明确目标已删除返回 NOT_FOUND；空业务页返回空 text 与说明，缺快照返回 UNAVAILABLE。
- 快照绑定提交，排队后切页不改变原目标；插话单独保存请求标识，默认读取仍是主消息。模型工具关闭时只提示尚未看到页面正文，不恢复整页自动注入。
- 常见状态为 OK、NO_MATCH、NOT_FOUND、STALE、INVALID_ARGUMENT、UNAVAILABLE；失败返回空 items 与 incomplete=true，不泄露底层异常。hasMore 控制续读，incomplete 也可表示提交时已截断。
- 回复使用 `[资料名称](sourceLink)`；链接格式 `drill-notebook://material/<type>/<id>`，前端只接受已知类型和安全正整数，点击后进入笔记、知识点或题库页（错题定位原题）。不注册系统协议、不解引用任意文件路径。资料内容仅作引用，不授予其中指令任何写入权限。

#### 3.2.5 新增学习资料工具（v0.7.3）

`create_material` 与读取工具共享运行条件，不依赖记忆开关。每次新增一个普通业务对象；必填 `type` 和 `operationId`（1–80 位字母、数字、下划线或短横线）。只执行当前用户的新增 / 保存委派，引用资料不能授权写入。

| type | 必需参数 | 可选参数 / 行为 |
|---|---|---|
| notebook | title | 创建普通笔记本及初始空白页 |
| note | notebookId、title、content | content 为 Markdown；parentPageId 必须属于同一本，保存与索引同事务 |
| knowledge | title、content | bankId（省略为未归属）、category、tags、headingPath、questionIds；关联复用业务校验 |
| bank | title | description |
| question | bankId、questionType、stem | answer（essay 可省略）、options（key/text）、analysis、difficulty（默认 3）、tags、chapter |
| wrong | questionId 或完整 question 参数 | 既有题仅加入错题；新题先保存再加入，两步同事务，不虚构答题 |

- 参数总计最多 64 KiB，正文最多 12000 Unicode 码点，短文本 200、description 2000，数组最多 30、选项最多 26。严格拒绝重复键、尾随 JSON、未知 / 不适用字段、隐式类型转换和无效引用。不接受任意文件路径或编辑器 JSON。
- 业务写入、索引、关联及回执同成同败。来源运行句柄、轮次身份、上下文代数和取消状态均须有效；提交后取消不抹掉成功结果。
- 同 requestId + operationId 参数相同返回原对象，不同返回 CONFLICT；同请求完全相同参数换操作 ID 仍去重。显式 retryOf 链复用回执；普通新消息可再次创建同名资料。目标删除或已退出错题本后，重复操作返回 NOT_FOUND，不重建。
- 成功返回 `{status,items:[{type,id,title,sourceLink,...所属信息}],sourceLink,incomplete:false}`，status 为 CREATED / DUPLICATE / NO_CHANGE。失败返回空 items、incomplete=true，状态为 INVALID_ARGUMENT / NOT_FOUND / REJECTED / STALE / CANCELLED / CONFLICT / UNAVAILABLE；UNAVAILABLE 表示保存结果待核对。
- 前端只刷新相关目录缓存，不覆盖打开的编辑草稿。来源进入普通资料页面，AI 创建对象可由人工继续编辑；修改见 §3.2.6，删除见 §3.2.7。

#### 3.2.6 修改学习资料工具（v0.7.3）

`update_material` 与新增工具共享运行条件，不依赖记忆开关。必填 `type`、正整数 `id`、`operationId`、`expectedVersion` 和非空 `changes`；一个调用修改一个对象。模型先明确定位并读取已保存目标，将对应的 `updateVersion` 原样传作 expectedVersion，不能使用分页 version、页面草稿或猜测版本。

| type | changes 字段与行为 |
|---|---|
| notebook | title |
| note | title、edits（oldText/newText 数组）、append（追加 Markdown） |
| knowledge | title、content、category、tags、headingPath、questionIds；只改当前卡片，保留原文 / 总结档案 |
| bank | title、description |
| question / wrong | questionType、stem、options（key/text）、answer、analysis、difficulty、tags、chapter；wrong 修改仍在错题本的原题 |

- 缺省字段保留，不补默认难度或重写未指定答案；可选文本用空字符串清除，列表用空数组清除，拒绝 null、未知 / 不适用字段、重复键、隐式转换。题型与答案等合并旧值后复用业务校验；不修改归属、顺序、作答统计、错题成员状态、计划或复习记录。
- 参数最多 64 KiB；operationId 为 1–80 位字母 / 数字 / 下划线 / 短横线，expectedVersion 为 64 位小写十六进制。正文与每项 oldText/newText 最多 12000 码点，短文本 200，描述 / 选项文本 2000；数组 30、选项 26、edits 20。
- 笔记 edits 逐项执行，oldText 须在单个可编辑文本叶或 Markdown / 公式 / Mermaid 属性中唯一精确匹配；不匹配或多处匹配返回 CONFLICT，整次回滚。不支持跨富文本叶的整段替换，不接受整页 content 覆盖；append 追加现有 Markdown 块。其余节点、格式、图片、附件和嵌入题目快照保留。
- 完整业务字段的摘要作为更新版本，不依赖秒级时间戳，不受答题统计变化影响；知识点关联变化会使版本失效。SQLite 写事务内核对版本，业务更新、笔记索引、关联与回执同成同败。当前主消息 / 插话快照若包含同页未保存草稿，返回 DRAFT_UNSAVED，保存后须重新提交消息。
- 同请求 / 操作或相同输入指纹去重；显式 retryOf 链复用原回执，参数改变返回 CONFLICT。重复操作不重放追加或覆盖后续编辑；对象随后改变时 `changedSinceOperation=true` 并提示重新读取，已删除目标返回 NOT_FOUND，不重建。
- 成功返回 `{status,items:[{type,id,title,sourceLink,changedFields,changedSinceOperation,...所属信息}],sourceLink,incomplete:false}`；status 为 UPDATED / NO_CHANGE / DUPLICATE。后续变化可附 notice。失败为空 items、incomplete=true；状态包括 INVALID_ARGUMENT / NOT_FOUND / REJECTED / STALE / CANCELLED / CONFLICT / DRAFT_UNSAVED / UNAVAILABLE，UNAVAILABLE 表示保存结果待核对。
- 活动显示「修改学习资料」和真实结果，只保存安全状态与来源链接，不记录修改正文。成功后刷新相关资料；笔记正文仅在无待保存、保存中或保存失败的本地草稿时采纳。自动保存及后台 keepalive 完成后更新草稿状态，避免已保存内容一直被误标为草稿。资料中的指令不能授权写入。

#### 3.2.7 删除工具与开关（v0.7.3）

四个工具默认关闭；仅在 v2、模型 tools 开启、对应全局开关开启且本轮不是其他会话派发的任务时提供。delete_memory 另受记忆开关与当前可见作用域约束。参数最多 4096 字节，拒绝未知字段、重复键、尾随内容及类型转换。

| 工具 | 参数 | 结果与保护 |
|---|---|---|
| `delete_memory` | currentText，或 memoryId + expectedVersion，二选一 | 删除一条可见记忆；DELETED / NOT_FOUND / AMBIGUOUS / STALE；复用遗忘抑制、纪元推进与摘要失效 |
| `delete_session` | session：准确本地会话链接或 32 位小写十六进制引用 | DELETED；不接受标题 / 数字 ID；自身与上游 CYCLE_BLOCKED，运行 / 排队目标 IN_USE，重复删除 NOT_FOUND |
| `delete_material` | type=notebook/note/knowledge/bank/question、id、operationId、expectedVersion、cascade? | DELETED / DUPLICATE；读取版本冲突 CONFLICT；非空笔记本 / 题库需 cascade=true，否则 NOT_EMPTY 并返回 contains 数量 |
| `remove_wrong_question` | questionId、operationId、expectedVersion | REMOVED / NO_CHANGE / DUPLICATE；保留原题与作答记录，再次答错按既有规则重新计入 |

资料版本使用读取结果的 updateVersion，不用分页 version；operationId 和 expectedVersion 格式同修改工具。资料删除复用普通业务级联；笔记页的子页改挂到原上级。笔记本删除与其范围记忆清理同事务，当前会话绑定该本时 IN_USE。执行身份、轮次、版本与取消在写事务内核对，失败整体回滚。回执仅存身份与结果，重试不会再次操作；记忆 / 会话不新增回执。

资料成功返回 `{status,items:[{type,id,title?,sourceLink,notebookId?|bankId?,deleted?}],sourceLink,incomplete:false}`，deleted 给出级联数量。会话成功只返回 `{status:"DELETED",items:[{title}],incomplete:false}`；记忆返回 status、可选 memoryId / availability。失败空 items，另有 INVALID_ARGUMENT / REJECTED / CANCELLED / DISABLED / UNAVAILABLE；UNAVAILABLE 表示结果未确认。仅本轮自己的已确认删除可以刷新计划的记忆版本，额外并发变更仍触发上下文变化。

删除记忆活动刷新记忆列表，删除会话刷新会话列表与容量，资料删除刷新目录与关联学习记录。活动新增 DELETED / REMOVED / NOT_EMPTY / IN_USE / DISABLED；原始参数与内容不进入活动。已删资料的 sourceLink 仅用于缓存更新，不提供打开按钮；移出错题保留「查看原题」。笔记删除会丢弃本地草稿，并忽略迟到的保存结果与编辑器回声。

| 方法 | 路径 | 契约 |
|---|---|---|
| GET | `/ai/tool-settings` | `{revision,tools:[{name,group,enabled,defaultEnabled}]}`，全部 22 个内置工具，分组及顺序见下表 |
| PUT | `/ai/tool-settings` | `{revision,enabled:{<name>:boolean}}`，部分更新，返回同形快照；未知名称 / 非布尔值 400，版本冲突 409 AI_SETTINGS_CONFLICT |

| group | 工具顺序 | defaultEnabled |
|---|---|---|
| `web` | search_web、fetch_web | true |
| `material` | read_current_page、search_materials、read_notebook、read_knowledge、read_question_bank、read_wrong_questions、create_material、update_material | true |
| `memory` | search_memories、list_memories、create_memory、update_memory | true |
| `session` | list_sessions、read_session、create_session、send_message | true |
| `delete` | delete_memory、delete_session、delete_material、remove_wrong_question | false |

全局开关不跟随模型，设置面板即点即存；恢复默认提交各项 defaultEnabled，即 18 开 / 4 关。无记录或旧配置缺项时使用该项默认值，保留原有删除选择；仅持久化与默认值不同的选择，部分更新不丢失其它组的偏好。工具权限沿用 v17 表结构，v19 的联网服务配置独立保存。助手地球按钮一次提交两项 Web 权限：全开时全关，全关或部分开启时全开；服务端确认后同步状态。

开启从下一轮生效，关闭在执行前再次复核，未执行的调用返回 DISABLED，活动显示「工具已在设置中关闭，未执行」。全关仍可正常聊天。记忆工具仍受记忆启用 / 授权约束，全部工具仍受模型工具能力约束，不自动打开其它工具。读取设置异常或已知配置项损坏时接口报错、聊天暂时不提供工具，不能以默认开启值绕过用户选择；未知工具配置忽略。

按本轮可用工具生成系统指引；全局关闭与派发任务的删除限制分别说明，不能用清空 / 修改冒充删除。联网服务配置见 §3.1.2，关闭对应权限时原生 Web 或远程备用均不能绕过。

#### 3.2.8 自动失败恢复（v0.8.1）

普通 / 流式对话、SSE metadata / done / error、轮次状态、runtime.current 及历史助手消息共用可选 `recovery`：

```json
{
  "kind": "stream",
  "state": "waiting",
  "attempt": 2,
  "limit": 5,
  "error": "stream_disconnected\nerror decoding response body",
  "nextRetryAt": 1794535200800
}
```

- kind 为 request / stream / network；state 为 waiting / connecting / failed / stopped。attempt 为实际发起的恢复请求数，初始请求另计；等待不提前增加计数。持续断网的 limit 为 null，有限模式包含 0。nextRetryAt 为 Unix 毫秒截止时刻，停止 / 失败时清除；成功时 recovery 为 null，清除临时反馈。缺字段兼容旧响应。
- 配置在受理时冻结。普通请求与流式恢复从 400ms 起按翻倍退避，约 ±10% 抖动，首轮约 `[360, 440)` ms；纯连接失败等待 5 / 10 / 20 / 40 / 60 秒后保持 60 秒。合法服务端等待建议优先，按接收响应时建立的截止时刻等待，不因读取错误体或显示反馈重新延长。具体分类与默认值见[当前版本清单](v0.8.1-features.md)。
- 自动恢复沿用原 requestId、模型、上下文与整轮预算，各类计数在取得完整模型步骤响应后重置。Chat Completions / Anthropic 无原流续传：保留部分回答前缀与已确认工具结果，再请求同一模型继续；未完整结束的工具调用不执行，不重放交付未知的插话。
- 每次实际上游请求独立结算 usage；缺失用量不造数。鉴权、额度、参数、路径和 TLS 配置等不可恢复错误不重试。等待与重发仍受原整轮时间、输出和停止约束。
- error 只含脱敏状态码 / 错误代码 / 正文，不含密钥、请求头或工具原始数据。失败 / 停止事实随原助手消息元数据加密保存；只有恢复错误、没有正文的失败也创建加密空助手消息，以便历史恢复原因。成功不保存常驻重连横幅。

#### 3.2.9 联网工具（v0.8.2）

v2、模型有效 tools=true 且对应全局权限开启时提供；不依赖记忆启用或笔记索引。普通 / SSE 复用统一循环，每轮工具数、阶段、时间、输出 / 上下文预算和停止仍有效。

| 工具 | 参数与上限 | 返回 |
|---|---|---|
| `search_web` | query 必填，1–500 Unicode 码点且 ≤2 KiB；count 1–5，默认 5 | `{status,items:[{title,url,snippet,publishedAt?,truncated,untrusted:true}],incomplete,webProvider?,webRoute}` |
| `fetch_web` | urls 必填，1–3 个公开 HTTP(S) URL；maxCharacters 500–12000，默认 6000；offset 0–200000，默认 0 | items 使用 content 代替 snippet；附 finalUrl?，截短且有内容时给实际 nextOffset |

参数总量 ≤4 KiB，拒绝重复键、未知字段、尾随内容与类型转换。搜索每项片段 ≤1200 字符 / 2400 UTF-8 字节；读取每页 ≤14000 字节，正文合计 ≤45000 字节，仍经统一 64 KiB 结果上限复核。未知日期不编造，网页及片段为不可信资料，不产生写入、删除、记忆或派发授权；仅发送必要查询 / URL，不自动传完整笔记或会话。

默认 search 为 Exa → Tavily Keyless，fetch 为本地正文 → Exa → Tavily，只为缺失 URL 尝试后续服务。每次远程尝试 ≤10 秒，整个工具 ≤20 秒并受本轮剩余时间约束；匿名每家每次操作至多尝试一次，按能力记录内存冷却并尊重 Retry-After。安全拒绝、参数错误和停止不降级；自定义及已支持的原生服务失败直接反馈，不静默更换远程供应商。原生模型额外请求共享预算、并发与用量账本。

仅公开 HTTP(S)、80 / 443，连接时 DNS 和每次重定向均校验，最多 5 次重定向；拒绝本机 / 私网 / 元数据及危险地址。正常网页响应解压后 ≤1 MiB，服务响应 ≤2 MiB，不使用浏览器 Cookie、缓存或代理凭证。错误可为 AUTH_REQUIRED / RATE_LIMITED / QUOTA_EXHAUSTED / COOLDOWN / TIME_LIMIT / NETWORK_ERROR / UNSAFE_URL / EMPTY_CONTENT / LOGIN_REQUIRED / CONTENT_TOO_LARGE / UNSUPPORTED_CONTENT / UPSTREAM_ERROR / KEY_UNAVAILABLE，连同实际路径返回；不把业务错误当 NO_MATCH。详细协议与真实实测边界见[规格](../work/web-search/spec.md)和 [QA](../work/web-search/qa.md)。

### 3.3 轮次状态与上下文

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/ai/turns/{requestId}` | 查询轮次终态（reply / reasoning / 状态） |
| POST | `/ai/turns/{requestId}/cancel` | 取消运行中的轮次 |
| GET | `/ai/turns/{requestId}/input` | 只读原提问与完整附件，供重新生成使用 |
| GET | `/ai/turns/{requestId}/context` | 读取上下文审计、逐请求组成及本轮累计；可选 beforeAttemptId 向前分页 |
| POST | `/ai/turns/{requestId}/context/preview` | 预览上下文计划 |

v2 状态查询同时覆盖尚未建立轮次的派发收据。运行中返回 active/status/phase 和内存 reply/reasoning/toolActivities/recovery；只有已经开始的项才带 userText。终态优先读取现有轮次记录，并在有恢复事实时返回 recovery；未开始就取消的收据只返回真实状态，不伪造聊天正文。取消旧 requestId 不得结束同目标后来的新轮。

原输入接口返回 `{requestId,sessionId,input:{role:"user",userText,attachments?}}`，成功响应带 `Cache-Control: no-store`。附件条目为 `{clientId,kind,name,mimeType,text?,dataBase64?}`：文本返回正文，图片返回 base64；无附件时省略 attachments。接口不调用模型、不重新提取记忆、不写历史或修改资料，前端只在点击重新生成时读取，不存入浏览器存储。

无效 ID 返回 400 `RETRY_ID_INVALID`；原轮次不存在返回 404 `TURN_NOT_FOUND`；正文已清理返回 410 `TURN_CONTENT_DELETED`；原轮次运行中返回 409 `RETRY_TARGET_ACTIVE`；受保护正文未解锁返回 423 `CHAT_PASSWORD_REQUIRED`；原提问、加密元数据或附件无法完整恢复返回 409 `RETRY_INPUT_UNAVAILABLE`。恢复的附件数量必须与保存的原输入一致，不能丢失部分附件后继续发送。

原地重试受理另校验：原提问或附件 origin / digest 不一致返回 `409 RETRY_INPUT_CHANGED`；会话上下文代数变化返回 `409 RETRY_CONTEXT_CHANGED`；原轮未保存助手消息位置且已经有后续消息时返回 `409 RETRY_TARGET_INCOMPLETE`。原轮没有输出但仍是最新轮次时允许重试。已替换的 requestId 在执行、轮次读取及原输入读取中返回 `409 TURN_SUPERSEDED`，指向最新 replacementRequestId；容量不足时不替换原内容或修改原账本。

#### 3.3.1 上下文详情（v0.8.2）

有可读的已保存审计时，`GET /ai/turns/{requestId}/context` 在原响应 / audit 上增加 `requestContexts` 和 `usage`。沿用原记录存在、清理和解锁检查，读取不建计划、不重放工具、不调用模型；来源正文仍通过现有 preview 接口显式读取。

- `requestContexts:{items,totalRequests,hasMore,nextBeforeAttemptId,limit}`，limit 固定 50。默认最新 50 条 SENT / SETTLED / UNKNOWN 的对应 CHAT 请求，页内升序；`?beforeAttemptId=<本轮 attempt ID>` 读取更早页。关联当前 requestId 及旧原地重试回执，totalRequests 不受分页限制；错误 / 跨轮游标返回 400 `CONTEXT_REQUEST_CURSOR_INVALID`。
- 每项包含 `attemptId,operationId,ordinal,createdAt,settledAt,state,usageSource`、归一化的 `inputTokens,outputTokens,cacheReadTokens,cacheMissTokens,totalTokens` 及各字段的 `*Complete` 标志，以及 `context`。SENT 未结算时实测字段为 null；部分回报只代表已知下界。
- `context` 为版本 1 的 `RequestContextAudit` 或 null，字段为 `schemaVersion,estimatorVersion,model,kind,estimatedInputTokens,contextWindow,categories,detailsTruncated`，kind 为 CHAT / NATIVE_WEB。每分类包含 `kind,estimatedTokens,itemCount,items:[{key,name,estimatedTokens}]`；固定类别为 SYSTEM / TOOL_DEFINITIONS / USER / INJECTED_CONTEXT / ASSISTANT / TOOL_CALLS / TOOL_RESULTS / IMAGE / OTHER。
- 组成是最终请求体的本地估算，明细最多 256 项 / 32 KiB，只含数字、位置与允许的工具名；分类总数 / 合计不因截明细而减少。分项在 UI 标 `≈`，不按实测总量缩放或用来填补缺失实测。旧记录或无法读取的组成返回 null。
- `usage` 为本轮所有已结算 / UNKNOWN 请求的累计，字段口径与下面的 `cumulative` 相同，包含工具、自动恢复、原生联网及旧原地重试尝试。正在发送的 SENT 请求只进入请求列表，未结算用量不造数；累计不取最后一次 done 的快照代替。

### 3.4 会话与历史

| 方法 | 路径 | 用途 |
|---|---|---|
| GET / POST | `/ai/sessions` | 会话列表（允许为空） / 显式创建；前端仅首次发送时调用 POST |
| GET | `/ai/sessions/runtime?sessionId=` | 列表运行状态，可选所选会话当前/最近轮快照 |
| POST | `/ai/sessions/{id}/stop` | `{expectedRequestId}`；停止目标当前轮，取消工具待发、暂停保留人工待发，过期身份返回 409 STALE |
| PUT / DELETE | `/ai/sessions/{id}` | 重命名 / 删除（允许删除最后一条；运行中删除返回 409） |
| POST | `/ai/sessions/{id}/title` | 异步生成 / 重新生成标题（202） |
| GET | `/ai/sessions/{id}/messages` | 消息列表（解密） |
| GET | `/ai/messages` | 全局消息查询 |
| GET | `/ai/sessions/{id}/history` | 历史查询 |
| GET | `/ai/sessions/{id}/navigation/{roundNumber}` | v0.8.1 当前会话逐轮定位与有界预览 |
| GET / PUT | `/ai/history/storage` | 历史容量配置 / 状态 |
| POST | `/ai/history/cleanup/preview` / `cleanup` | 清理预览 / 执行 |
| GET | `/ai/sessions/{id}/export` | 导出会话 |

轮次状态和历史助手消息返回 `toolActivities` 与 `finishReason`，有失败 / 停止恢复事实时附 `recovery`；旧消息缺少活动时返回空列表。删除会话或清理历史仅断开相应来源证据，保留已保存长期记忆及原来源类型；删除笔记本的 NOTEBOOK scope 清理仍独立执行。

v0.8.1 历史与定位契约：

- history 使用 beforeMessageId 向旧页翻阅，或 afterMessageId 正向读取目标之后的页；返回 `{sessionId,messages,historyRevision,hasMore,limit,totalRounds,firstRound?,hasOlder?}`。limit 默认 / 最大 50；totalRounds 为当前保留的用户提问数量，firstRound 为本页首条用户提问的全会话轮次，没有用户消息时省略。正向页额外返回 hasOlder，表示页首之前仍有消息；hasMore 继续表示当前方向还有页。
- navigation 返回 `{roundNumber,totalRounds,messageId,question,answer,createdAt,status,historyRevision}`。roundNumber 从 1 开始，以现有用户消息顺序定位；question / answer 最多 240 / 600 Unicode 码点，正文仍可通过历史页读取。缺少回答返回空字符串，保留真实状态；会话缺失为 404 SESSION_NOT_FOUND，轮次不存在 / 已清理为 404 CONVERSATION_ROUND_NOT_FOUND。
- 两接口只读现有加密历史，不调用模型、不创建会话或建立全量正文索引。前端对超出驻留区的目标加载有界阅读窗口，最多 500 条消息；回到最新恢复最新窗口。迟到页、定稿和新输出不得覆盖旧阅读位置，草稿、附件、运行与队列保持。

runtime 响应为 `{sessions:[{sessionId,requestId,status,phase?,paused?,queueCount}],current?}`。QUEUED/RUNNING 表示未结束，PAUSED 表示暂停保留；phase 可表达 running/thinking/tools/answer/waiting/stopping。queueCount 不包含正在执行的队头；人工待发与插话详情由队列接口读取。该查询不外发模型请求、不改变会话排序或选择；停止返回 STOPPING 后仍等服务端终态。有运行/排队项时删除、归档与历史清理被拒绝。

会话列表和旧版全局消息查询不外发模型请求或创建会话；v0.8.2 会话列表可幂等初始化旧空模型选择。无会话时返回 `[]`，不补建默认记录。前端新会话页没有会话 ID，首次有效发送时调用创建接口，再用返回的 ID 发送该条消息；进入页面、编辑草稿、删除当前会话均不调用创建接口。已有空白记录不自动删除或复用；旧客户端未提供会话 ID 的实际聊天请求仍可按兼容规则创建默认会话。

会话列表、新建、更新和标题任务响应增加 `sessionLink`、`titleSource`、`titleStatus`、`titleRevision`、`canGenerateTitle`。`sessionLink` 格式为 `drill-notebook://session/<32位小写十六进制引用>`；仅是本地引用，不注册系统协议。可复制到新会话，由 `read_session` 读取；回复引用可打开只读预览。复制成功不等同于模型已经读取。

标题 POST 请求为 `{expectedRevision: number, overwriteManual?: boolean}`。首个完整且保存成功的问答可作为素材；无素材返回 409 `TITLE_NO_EXCHANGE`，人工名未确认覆盖返回 `TITLE_CONFIRM_REQUIRED`，版本变化返回 `TITLE_CHANGED`，任务重复或旧任务仍在收尾返回 `TITLE_BUSY`。缺少助手所选模型返回 `TITLE_MODEL_UNAVAILABLE`，队列满返回 503 `TITLE_QUEUE_FULL`，会话不存在返回 404 `SESSION_NOT_FOUND`。202 返回已认领的会话快照，随后从会话列表读取 generating → ready/failed；失败保留原名。解锁材料只从进程会话取，不进 URL。

新会话首个 COMPLETED 且持久化成功的问答自动提交标题任务；中断/失败不触发，之后首个成功问答可触发。自动失败、重启或切模型都不自动重试；人工重命名始终优先，标题变更不改变列表顺序。自动任务沿用本轮模型快照，手动任务使用目标会话自己的模型与档位；仅发送首个完整问答的正文片段，不带附件、工具结果或整段历史。

- v0.8.2 起模型与思考档位由助手输入区按会话选择。`/ai/connections/chat-model` 有 sessionId 时仅改该会话并更新新会话继承默认；无 sessionId 时保存新草稿的继承默认，不覆盖已有会话。额外响应 `sessionChoice` 为 `{sessionId, modelConfigId, reasoningEffort, revision}`；会话视图返回独立的 modelChoiceRevision，标题版本与模型版本分别合并，迟到响应不能覆盖更新选择。
- `POST /ai/sessions` 可显式携带 modelConfigId / reasoningEffort，未提供时继承最近默认并固定；`PUT /ai/sessions/{id}` 可更新该会话的独立选择。模型须存在且连接 / 容量完整，显式档位须属于目标模型支持的 effort；助手只换模型且旧档位不适用时回到模型默认。旧空选择优先恢复最近实际模型，无法唯一匹配时只继承一次默认；已有选择不持续跟随全局。受理、排队与自动恢复冻结目标会话配置，其它会话改选不影响运行。模型不可用时拒绝外发，历史仍返回当时实际使用的 `model`（供应商 · 模型）与 `reasoningEffort`。

- `GET /ai/sessions/{id}/context-usage`（只读）返回 `{sessionId,current,latest,cumulative,model,contextWindow,outputReserve}`。后三项使用目标会话自己的当前模型，模型不可用时为 null；占用读取暂时失败只令 current=null，保留有效模型窗口与累计实测。
- `current:{tokens,categories,estimated,complete,estimatorVersion,contextGeneration,historyRevision,requestId,active,summaryRevision}` 是当前会话保留的全局上下文。tokens 为 Long、本地估算；complete 表示已知组成是否完整，缺失旧内容时保留已知下界。当前代、未清理的提问 / 回答与 RECEIVED 插话逐份计一次，初始代包含 legacy；不受 500 条驻留、32 轮扫描或单请求预算截断。有效摘要按 coverage 替代旧原文，最近两轮和缺口保留；尚未落库的回复读活跃数值，终态使用正文，二者不叠加。
- `current.categories:[{kind,tokens}]` 只含正数分类，kind 沿用 SYSTEM / TOOL_DEFINITIONS / USER / INJECTED_CONTEXT / ASSISTANT / TOOL_CALLS / TOOL_RESULTS / IMAGE / OTHER，tokens 为 Long。分类与总量来自同一次计数，合计等于 current.tokens；缺失或合计无效的旧额外分类归入 OTHER，已知总量保持。该列表不来自所选历史请求或累计计费，不保存正文 / 工具原始数据。
- current 包含一份固定规则 / 当前模型可用工具定义和一份当前轮保留的工具、注入资料、图片及续接指令；图片用固定估算，独立思考列按正常历史重放规则不计。requestId 指向当前工作集对应轮次，活跃轮优先，较早轮原地重试完成后使用该次结果；只读刷新不触发检索、维护、压缩或模型调用。
- `latest:{requestId,inputTokens,contextWindow,model,cacheReadTokens,outputTokens}` 保留当前代里最近一次回报输入的请求实测或 null，用于请求对账。圆环和顶部只使用 `current.tokens / 当前 contextWindow`；最近请求输入和累计输入均不能顶替全局占用。图形封顶 100%，总量及实际百分比允许超过窗口。
- v0.8.2 的 `cumulative:{requests,inputTokens,outputTokens,cacheReadTokens,cacheMissTokens,totalTokens,inputComplete,outputComplete,cacheReadComplete,cacheMissComplete,totalComplete}` 来自持久化账本，包含该会话所有对应 CHAT 的已结算 / UNKNOWN 请求及旧重试回执，排除标题、维护、其它会话和未发送预留。输入归一化后包括缓存读写，total 为输入加输出；OpenAI 包含桶不再相加，Anthropic 独立桶加回。原始计费账本保持。
- 缺失数字为 null，部分回报仍返回已知 subtotal 并令对应 Complete=false，前端显示 `≥`；没有请求也不造全零。只有累计输入与命中都完整且输入大于 0 才计算命中率。旧缓存口径无法从独立日账本核实时保持未知；上下文换代 / 正文清理不改变已发生的累计，500 条驻留限制不截断统计。聚合使用 Long。
- 历史助手消息的 `usage` 使用同一份本轮累计和完整性字段，包含旧原地重试尝试；用户消息的 `page:{title,link?}` 保留提交时的业务页面。没有对应请求 / 页面记录时不返回，不补估算。

- 会话置顶（v0.8）：`PUT /ai/sessions/{id}` 接受布尔 `pinned`，会话视图返回 `pinnedAt`（ISO 时刻或 null）。重复置顶保留最初的时刻，置顶不改变 `updatedAt`；列表接口的顺序不变，置顶分组与排序由前端按 `pinnedAt` 升序处理。
- v0.8.2 小卡片顶部显示当前全局「上下文总量」与窗口进度，圆环悬停提示保留「估算」；下方为 cumulative 的输入总量、缓存命中、未命中及输出。缓存命中率使用累计命中 / 累计输入，独立于圆环的窗口占用率；模型名在左下与「查看上下文详情」入口同一行。详情标题为「上下文」，顶部「当前上下文」复用 current / model / contextWindow，位于逐请求浏览器上方；移除底部累计块，接口的本轮 usage 仍保留供现有历史 / 对账使用。
- 上下文占用响应里的顶层 `model` 与 `latest.model` 只返回模型 ID，不加供应商前缀；历史助手消息的实际模型显示仍为「供应商 · 模型」。
- 窗口固定（v0.8，Electron IPC）：`assistant:set-fixed` 接受布尔值开关助手窗口的 `alwaysOnTop`；助手窗口状态 `assistant:get-state` / `assistant:state` 增加 `fixed` 字段。

#### 3.4.1 人工排队与插话（v0.7.2）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/ai/sessions/{id}/queue` | 返回 `{sessionId,paused,editingId,items,steering,storedBytes}`；待发按 sequence 排序，附件只含标识、名称和类型 |
| POST | `/ai/sessions/{id}/queue` | 接收 v2 输入及 `mode=queue\|steer`，插话另带 `targetRequestId`；返回 requestId、sessionId 与真实受理状态 |
| POST | `/ai/sessions/{id}/queue/pause` | `{paused:boolean}`，暂停或继续，返回队列快照 |
| POST | `/ai/sessions/{id}/queue/{requestId}/action` | `action`、`revision`；动作含 beginEdit / save / cancelEdit / remove / undo / steer |

- 普通队列消息每条独立成轮，与工具派发共用 FIFO。受理时冻结配置及页面/附件输入，开始时使用最新目标历史；保存成功才返回受理。原 requestId 和原输入可核对收据，输入不同返回冲突，不能换身份重发结果未知的提交。
- v0.7.3 的 pageSnapshot 随既有加密待发载荷保存，重启暂停恢复和编辑正文保留原快照；队列只展示页面标题，不暴露整页正文。插话绑定自身快照，不覆盖主消息的页面定位。
- 编辑先暂停接续；save 可修改 `userText` 并通过 `keepAttachments` 保留附件，结束编辑后恢复此前暂停状态。已开始项不能编辑或转插话；版本变化、已有其他编辑项、撤销过期分别返回 QUEUE_CHANGED / QUEUE_EDITING / UNDO_EXPIRED。移除允许短暂撤销，不删除已发送历史。
- 队列项转插话也要带 `targetRequestId`。插话绑定点击时的运行，在下一次模型请求前接入，不取消当前上游或工具；受理、发送中、已接收、交付未知分别展示。未知交付不自动重发；最终响应后也处理已经受理的补充，沿用原轮次预算。
- 停止或失败保留未开始的人工内容并暂停，取消工具待发。后台重启只恢复未执行人工项为暂停，配置失效或解密失败如实反馈；不自动重放已开始或交付未知内容。
- 已接收补充按原运行及正文偏移参与历史、上下文、`read_session` 与 Markdown / HTML / JSON 导出。待发和交付未知项不冒充已发送历史，无法解密时标明内容缺失。

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

## 4. 软件更新与退出协调（v0.8.0）

### 4.1 主进程专用后端接口

路径仍以 `/api` 为前缀。这两个端点只供 Electron 正常退出协调，不是 renderer 或 AI 工具能力；必须携带 `X-Drill-Update-Exit`，值由主进程每次启动 JVM 时生成，仅以进程环境传给后端。缺失、错误或未配置能力均返回 403；不得将该值写日志、配置或暴露给渲染进程。

| 方法 | 路径 | 响应 |
|---|---|---|
| GET | `/app/update/ready` | 200 `{ready:boolean,reason:string}`；HTTP / 后台写任务、正在回复或队列忙碌时 ready 为 false |
| POST | `/app/update/shutdown` | 接受时 200 `{accepted:true}`，稍后正常关闭 Spring context；并发写或 AI 任务忙碌时 409 `{accepted:false,reason}`，恢复正常接收任务 |

后端原子进入退出状态后拒绝新业务写请求，返回 503 `{code:"UPDATE_RESTARTING",message}`。标题、模型下载、嵌入、检索与记忆后台写任务使用同一 gate 租约，排队与在途任务均计数；readiness 不是一次不加保护的空闲快照。

### 4.2 Electron 更新契约

`window.api.update` 类型以 [update-types.ts](../../electron/update-types.ts) 为准。所有 IPC 必须来自本应用受信入口；除状态读取、保存握手和助手草稿外，用户更新命令只接受主窗口。

| API | 结果 / 行为 |
|---|---|
| `getState / onState` | `AppUpdateState`：实际版本、开发 / 替换能力、设置、检查结果 / 时间、目标、跳过 / 失败版、顶部可见性、启动等待、真实进度与当前弹窗 |
| `setSettings(patch)` | 部分更新四个设置，校验布尔值及 1–168 整数小时，返回实际状态，不重置时间 |
| `check()` | 手动合并检查，忽略间隔与跳过记录；发现新版打开待安装说明 |
| `openAvailable / openHistory / closePresentation(id)` | 新版或进度 / 关于只读入口；关闭只影响当前展示 ID，不取消更新 |
| `notes(range,version?,refresh?)` | `{releases,range,cached,error?}`；范围为 `since / 10 / 20 / 50 / 100`，指定目标时只取未安装版；历史 N 以实际安装版为上限 |
| `skip(version) / restoreReminder()` | 持久化目标版跳过或清除；清除不重置已有提醒时间 |
| `install(version)` | 冻结更高目标，一次操作下载、校验、保存、退出、替换并重启；同一在途事务不重复下载 |
| `cancelDownload / resumeInstall` | 仅下载阶段可取消；保存保护解除后继续原事务，不重新下载 |
| `onPrepare / prepared / onPreparationEnd` | 请求 `{id}`、回复 `{id,ok,reason?}`；只接受本轮对应窗口的回复，失败或超时暂停并解除编辑锁 |
| `rendererReady()` | 只以受信主窗口完成启动握手，结合真实后端健康确认提交；辅助窗口不能替代 |
| `readAssistantDrafts / saveAssistantDrafts` | 目录内加密草稿；读取返回 unknown 由前端严格校验，损坏 / 超限拒绝，不以空值覆盖原文件 |

`backend.getBaseUrl()` 在后端启动完成或明确失败前保持等待，避免启动更新期间的提前请求缓存备用地址。程序清单、事务、健康提交点、恢复边界与 APP_ROOT 路径见[更新规格](../archive/v0.8.0/app-update/spec.md)。本切片不新增 schema。
