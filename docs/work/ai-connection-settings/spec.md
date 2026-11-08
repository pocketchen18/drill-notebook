# AI 连接设置实现规格

2026-11-08 更新，依据已获确认的 ui-ux.md。仅服务当前切片。

## 存储与兼容

- schema v10 新增单行 ai_connections：revision 与 JSON document。文档包含供应商、各供应商的模型、主模型 ID 和可空导入模型 ID。一个事务原子更新整个小型配置集合，revision 必须匹配，否则 409；没有独立的重复用途配置副本。
- 供应商和模型使用稳定 UUID；上游 ID 可编辑，保存后保留内部身份与用途绑定。供应商共享密文与加密元数据，响应不返回密钥内容。继续使用现有 Argon2id / AES-256-GCM；不引入任何文件路径。
- 首次读取管理接口时事务迁移旧 chat/import；两者独立保存，不合并账号。保留原 ai_config 作为迁移源，失败不落半成品，可重试。空 import 才跟随；残缺行可见待补全。mock://local 仅接受存量迁移。
- AiConfigRepository 对已迁移 chat/import 直接解析绑定形成不可变 ConfigRow；memory/embedding 仍读原表。旧 GET /api/ai/config 保持形态；迁移后的旧 chat/import 写接口拒绝，提示使用新设置，避免两处真相。

## API

### 自动验证新增模型

- `POST /providers/{id}/verify-add`：revision、connectionVersion、requestId、model（模型 ID 与可选编辑字段）、可选 masterPassword。供应商视图附带 connectionVersion，基于连接身份、地址、协议、认证及密文版本生成不透明摘要，不随模型列表或绑定变化。同一模型只允许一个进行中的验证；已有同 ID 模型返回现有配置而不重复探测或覆盖偏好。旧调用方未传 connectionVersion 时仍须通过入场 revision 校验。
- 先验证候选字段并取得供应商快照，释放配置锁再访问上游。复用现有协议适配器、密钥解密和取消句柄；测试输入全部在内存生成。返回每项 status/message/elapsedMs，以及 added、modelId、connections 快照；不返回上游正文、工具参数或密钥。
- 文本必须返回有效正文；通过后尝试图片与测试工具，各项失败独立记录。入场和最终提交核对连接版本；地址、协议、认证、密钥变化或供应商删除时拒绝旧结果。提交在锁内读取最新状态，重新检查重复与容量，将新模型合入最新列表并保留其他编辑/绑定，以最新 revision 原子保存。文本通过才创建模型，tools 由本次真实工具探测决定，并保存 verification 与 visionVerified；不重复收费探测来处理版本冲突。
- `/tests/{requestId}/cancel` 同时取消新增验证与基础测试；保存最多 128 个、最长 2 分钟的提前取消标识，消除取消先于请求登记的竞态。幂等接受并返回 cancelled=true。读取线程退出后释放活跃供应商引用。前端先等待取消确认再 abort 验证请求；取消接口 5 秒超时，失败时保留当前等待且停止后续队列。
- 不改原有直接模型 CRUD 契约和后台任务；新增前端入口统一走验证新增路径，已有模型修改仍使用 PUT。

统一 /api/ai/connections；GET 返回 {revision, providers, chatModelId, importModelId}。所有修改携带 revision，成功返回完整脱敏快照。

- POST /providers；PUT /providers/{id}；DELETE /providers/{id}。
- POST /providers/{id}/models；PUT /models/{id}；DELETE /models/{id}。
- PUT /bindings：purpose=chat/import、modelId；import 的 null 表示恢复跟随。
- POST /providers/{id}/discover：仅访问该已保存连接，返回模型 ID，不自动入库。
- POST /models/{id}/test：prompt、可选 masterPassword/requestId；仅短测试，无工具、用户资料和历史。客户端 AbortController 取消等待并屏蔽迟到结果；POST /tests/{requestId}/cancel 关闭后端活跃测试请求，30 秒超时兜底，并支持提前取消标识。
- 文本 trim，名称/组长度与整数约束遵照 UI 文档。模型 ID 最大 240 字，URL 最大 2048 字，密钥最大 8192 字。发现列表一次最多选择 20 项，前端以 3 个 worker 并发调用 verify-add，携带打开验证流程时的 connectionVersion；响应和重载仅采用不低于缓存 revision 的快照。取消逐一等待全部在途请求的服务端确认再 abort，阻止后续排队与迟到结果。
- PUT /models/{id} 允许修改上游 modelId，内部 id 与用途绑定保持稳定；去空白后检查同供应商重复。ID 变化只清除旧 verification、visionVerified、cacheBreakpoints 探测记录，所有用户设置与预设引用继承原值，再应用本次显式字段；字段缺省时不清空、不按新 ID 重套预设，名称和工具偏好也保留。未改 ID 的更新保持原语义。

## 请求与边界

- 一轮聊天只读一次配置；全部工具请求沿用该快照，编辑/应用从下一轮生效。运行时引用登记覆盖聊天与上游调用；被引用或运行中对象拒绝删除。
- 无需密钥时不生成 Authorization/x-api-key 请求头；有密钥仍复用原解密方式。独立导入无效时明确失败，不偷偷跟随。
- contextWindow/maxInputTokens/maxOutputTokens 为可空正整数，逐个不超过窗口。最大输出取任务上限与模型上限较小值；上下文整理保留当前问题，输入上限进入既有 fit；外发前含工具定义/工具结果的输入再次估算，超限明确拒绝。
- 沿用 TokenEstimator 的保守估算与图片预留，不能宣称厂商 tokenizer 精确计数。设置不能扩大原任务上限。
- 缓存能力降级回写必须匹配快照身份/版本，不能污染后来应用的模型。
- 修改供应商端点或协议后清除原端点的缓存能力降级标记；修改端点、协议、认证方式或密钥时清除旧验证记录。显式流式/工具偏好保留。协议优先使用显式 apiFormat，存量未指定协议时兼容原 Anthropic/Claude 供应商名称。

## 本次预设与思考参数增量（已确认）

- 本地资源 `ai-model-presets.json` 固定保存来源、版本与归一化字段；参考 AI Toolbox 和 CC Switch，只读取资料，不执行第三方配置。精确 ID/明确别名匹配，官方域名限制厂商，第三方连接仅使用唯一精确匹配，不剥离日期或任意后缀。协议不符时仅补名称与容量；默认 effort、空档位、服务默认。
- `GET /providers/{id}/presets` 返回当前连接可用的参考资料。获取模型仍返回 ID 列表，前端展示命中；服务端首次新增可填预设，显式用户字段优先。已有模型更新（包括修改 ID）和重复批量添加不重填；读取旧模型不写库、不改偏好。明确选择预设时，前端连同参数提交 presetId；服务端校验该参考属于当前供应商可用目录，只更新参考来源/版本，参数按显式字段保存。视图优先使用已存 presetId，使自定义上游 ID 与参考预设相互独立。
- 模型 params 增加 reasoningControl、reasoningEfforts、defaultReasoningEffort、thinkingBudgetTokens 及预设引用。缺失控制方式视为 effort，缺失等级视为空、默认值为空表示服务默认。允许 none/minimal/low/medium/high/xhigh/max，拒绝 ultra；默认等级必须属于勾选集合。Anthropic 不接受 none/minimal；Chat Completions 不支持预算模式。
- 普通聊天、工具循环和模型测试使用不可变参数快照。Chat Completions 发送 reasoning_effort；Anthropic 发送 output_config.effort（不自动附加 adaptive），预算模式发送 thinking.type=enabled/budget_tokens。服务默认不发思考参数；后台结构化任务不继承聊天默认。预算 >=1024 且小于本次实际输出上限，不能放宽原任务预算。连接测试默认输出上限 256，显式设置强度/预算时为 4096，均再受模型上限限制。
- 思考参数和缓存降级/输出预算复制兼容。Anthropic 工具续接保留本轮签名思考块（仅内存、有界），不写消息历史/日志；OpenAI 兼容工具续接保留必要的 reasoning_content。
- UI 使用独立的思考设置组件、可搜索多选与底部默认单选；默认值被取消勾选时回服务默认；编辑保存失败保留草稿，Esc 优先关闭弹层。模型 ID 共用预设候选输入，筛选支持 ID/显式别名/名称；手打、粘贴或删除只更新 draft.modelId，禁止按 ID 或异步查询结果重算任何其他草稿字段。点击/回车选择候选才应用完整 ID 与预设字段；保留分组、流式及工具偏好。已有值、未保存修改及参考资料不随 ID 变化，改回原 ID 也不还原设置。保存始终提交完整草稿（含空值），查询中或失败也不得省略字段让服务端补填。协议变化后允许取消原来已选但不适配的档位。

## 界面与验证（原有切片）

新组件独立在 components/ai-connections，SettingsPage 只替换原两张卡片。已有连接编辑和模型设置分别占用完整右侧二级页，与摘要/模型列表互斥渲染；新增供应商保留弹窗。二级页返回后恢复列表状态、滚动位置与入口焦点。发现与测试独立弹窗。统一未保存提示；失败保留草稿。普通 mutation 串行，验证新增以 3 个并发独立合并；响应后更新缓存与旧 ai-config 查询。路由/分区切换纳入离开保护。

验证迁移、幂等、密文保留、无密钥、模型隔离、应用原子性、冲突、引用删除、预算与快照；前端验证核心操作与异常状态，并做真实浏览器视口/主题检查。原状态文档与 MEMORY.md 按用户要求保持不改，收尾事实记录在本目录。
