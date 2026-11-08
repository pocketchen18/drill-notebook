# 数据模型

当前 schema 版本：**12**。完整 DDL 以 `backend/src/main/resources/schema.sql` 为准；本文只记录已落地表、职责与关键约束，便于快速定位，不复制字段级定义。

## 1. 通用约定

- 数据库为 SQLite，文件位于便携数据目录，路径由 `PortablePathResolver` 统一解析。
- v9 AI 表的时间列不使用 SQL 默认值，由服务端写入 ISO-8601 UTC、固定毫秒精度（`uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`），保证字典序等于时间序。
- 敏感内容以 `*_cipher` + `*_meta` 密文列存储；`*_hmac` 用于去重、族键或来源标识，不保存明文。
- `ai_chat_storage` 是逻辑容量账本，统计落库字节，不代表磁盘物理上限。
- 删除策略：会话、页面、计划组等父实体使用 `ON DELETE CASCADE`；记忆证据与关联表按业务语义级联或置空。

## 2. 学习域

| 表 | 职责 | 关键约束 |
|---|---|---|
| `question_bank` | 题库 | — |
| `question` | 题目 | `(bank_id, content_hash)` 唯一；题型、选项、答案、解析、标签、章节 |
| `question_fts` | 题目全文检索 | FTS5，内容映射 `question` |
| `knowledge_point` | 知识点 | `bank_id`、`category`、`heading_path`、`sort_index` |
| `knowledge_point_question` | 知识点与题目关联 | `(knowledge_point_id, question_id)` 复合主键 |
| `knowledge_point_original` | 原文 / 总结双快照 | `(point_id, role)` 唯一，`role` 仅 `original` / `summary` |
| `answer_record` | 答题记录 | 按题目与时间倒序索引 |
| `notebook` | 笔记本 | — |
| `note_page` | 笔记页面 | 归属笔记本，支持父页面与排序 |
| `note_question_ref` | 笔记中的题目快照 | `(note_id, question_id)` 复合主键 |
| `note_attachment` | 笔记附件 | 页面级联删除；SHA-256 校验；存储路径 |
| `study_plan_group` | 日历计划组 | 计划日期、来源、标题 |
| `study_plan_item` | 计划条目 | 日期 / 状态 / 资源索引；组级联删除 |
| `spaced_repetition_config` | 复习方案 | 名称唯一；内置标准、考前突击、保守三套 |
| `review_schedule` | 复习调度 | `(item_type, item_id, config_id)` 唯一；到期与条目索引 |
| `review_log` | 复习日志 | 按 schedule 级联删除 |

## 3. 检索域（schema v8）

| 表 | 职责 | 关键约束 |
|---|---|---|
| `retrieval_chunk` | 笔记分块 | `(corpus_type, source_id, chunk_index)` 唯一 |
| `retrieval_chunk_fts` | 分块全文检索 | FTS5 trigram |
| `embedding_model` | 本地模型目录与安装状态 | `catalog_id` 唯一；安装状态枚举 |
| `embedding_space` | 向量空间 | 仅一个 `is_selected = 1`；覆盖率 0–1 |
| `retrieval_embedding` | 分块向量 | `(chunk_id, embedding_space_id)` 唯一 |
| `embedding_job` | 向量化任务队列 | 输入版本唯一；状态含 QUEUED / CLAIMED / RETRY / COMPLETED / SUPERSEDED / FAILED |

## 4. AI 配置、会话、记忆与用量域（schema v9–v12）

### 4.1 配置与会话

| 表 | 职责 | 关键约束 |
|---|---|---|
| `ai_config` | 旧配置迁移源与其他用途配置 | `purpose` 主键；chat/import 迁移后由集合绑定解析；memory/embedding 仍使用原表 |
| `ai_connections` | 供应商、模型与用途绑定（v10） | 单行 `id=1`、`revision`、`document` JSON；供应商和模型均为稳定 UUID，密钥保持密文，主模型与可空导入模型绑定不复制配置 |
| `ai_runtime_settings` | 全局对话运行限制（v11） | 单行 `id=1`、`revision`、`document` JSON；不存在时读取默认值，保存按 revision 检查冲突，不随模型切换 |
| `ai_chat_session` | 会话 | 标题、归档、模型、标签；v12 增加唯一 `session_ref`、`title_source`、`title_status`、`title_revision`，标题更新不推进聊天活动时间 |
| `ai_chat_message` | 消息 | 正文与 reasoning 分列密文 |
| `ai_chat_turn` | 单轮请求 | `request_id` 唯一；同一会话仅一个 `RUNNING`；scope 与时间线约束 |
| `ai_chat_attachment` | 轮次附件 | `TEXT` / `IMAGE` 密文；`byte_length` 校验 |

v12 会话引用为随机 128 位标识的 32 位小写十六进制表示，改名和重启保持不变。标题来源为 temporary/manual/ai/legacy；状态为 pending/idle/generating/ready/failed。任务认领、结果提交和人工改名按标题 revision 条件更新，人工保存使更早的任务失效；不改变 history_revision。迁移保留已有标题和消息，已有历史的旧会话为 legacy/idle；重启把残留 generating 标为 failed，不重新外发。

标题请求用量复用 CHAT 分类，以 `session-title:<ref>:<revision>` 独立 operation 标识，不生成额外聊天消息或记忆。标题与引用跟随现有数据库和升级前快照保存，不引入新应用外写盘目录。

会话表允许零行。未发送的新会话页只在前端存在，首次发送才创建数据库记录；启动、列表读取、删除最后一条均不补建默认会话。旧消息迁移仅在存在 `session_id IS NULL` 的历史消息时建立/使用归属会话，不删除已有空白会话；表结构版本仍为 12。

`ai_connections.document` 保存各供应商的地址、协议、认证与密钥密文，各模型的上游 ID、名称、分组、容量、流式/工具偏好、预设引用、思考设置与验证记录。上游 ID 可编辑，内部 UUID 与用途绑定不变；修改 ID 清理旧探测记录，但不重套预设或重置用户参数。`importModelId=null` 表示跟随主模型。两张单行设置表各自独立检查 revision。

工具活动复用 `ai_chat_turn.result_meta_cipher/result_meta_crypto` 加密保存，最多 1200 项白名单摘要。开始和完成时更新检查点，首条活动建立助手占位，最终回答复用同一消息；检查点不推进历史 revision。活动不保存工具原始参数/结果，历史查询只读取，不重放工具。终止原因保存在 `finish_reason` 及结果元数据。

### 4.2 记忆

| 表 | 职责 | 关键约束 |
|---|---|---|
| `ai_memory_vault` | 记忆库密钥 | 单例；`fingerprint` / `password` 模式；`epoch` 与 `revision` |
| `ai_memory_settings` | 记忆设置 | 单行；启用、自动提取、会话摘要、双同意指纹、策略 JSON |
| `ai_memory_item` | 记忆版本 | `(memory_id, version)` 唯一；同族仅一个 `ACTIVE`；GLOBAL / NOTEBOOK scope 约束 |
| `ai_memory_evidence` | 来源证据 | `(vault_id, source_hmac)` 唯一；引用会话 / 轮次 / 消息 |
| `ai_memory_item_evidence` | 记忆与证据关联 | 复合主键，双向级联 |
| `ai_memory_proposal` | 替换 / 撤回提案 | 去重 HMAC 唯一；`PENDING` / `ACCEPTED` / `REJECTED` / `STALE` |
| `ai_session_context` | 会话摘要上下文 | `schema_version = 2`；记录 turn 水位与上下文代数 |
| `ai_memory_job` | 提取 / 摘要任务 | 状态机含 `WAITING_UNLOCK`、`WAITING_CONSENT`、`PAUSED_BUDGET`；租约与输入版本唯一 |
| `ai_memory_suppression` | 抑制项 | `FAMILY` / `PREDICATE` / `EVIDENCE` 复合主键 |
| `ai_memory_operation` | 幂等操作 | `operation_id` 主键；`IN_PROGRESS` / `COMPLETED` |

v0.7.1 的对话内记忆工具不新增表：`search_memories` 与 `list_memories` 读取 `MemoryIndexService` 的内存快照（由 `ai_memory_item` 的 ACTIVE 版本构建），工具结果不落库。`list_memories` 的 `updatedAt` 取自 `ai_memory_item.last_confirmed_at`。

已保存的长期记忆独立于聊天来源存活。删除会话/清理历史时，同一事务清理证据与关联、废弃依赖旧证据的待处理状态；不因来源消失删除长期事实，也不将其 `origin` 改为 `MANUAL`。管理页缺少来源时显示「来源已被删除」。删除笔记本仍清理其 NOTEBOOK scope，不等同于删除来源会话。

`create_memory` 复用 `ai_memory_item` / `ai_memory_evidence` / `ai_memory_item_evidence`：事实与证据在同一事务写入，证据的 `session_id` / `turn_id` / `message_id` 指向当前轮，`source_block_id = user-message`；归一值重复时不插行。受 v9 的 `origin CHECK (AUTO, MANUAL)` 约束，工具写入记为 `AUTO`，载荷内 `createdBy = AI_TOOL` 保留真实来源；管理页来源列仍显示自动。事实同时记为 `manual_protected = 1`，删除来源会话时只断证据、不删除这条长期记忆。写入会推进 `ai_memory_vault.revision`，并同步重建内存检索快照。若同一 `FAMILY` 键已有删除留下的抑制，只有用户当轮明确要求记住时 `create_memory` 才解除该 `FAMILY` 抑制（`PREDICATE` / `EVIDENCE` 保留），否则返回 `SUPPRESSED` 且不插行。解除抑制本身不推进 revision；若只命中已有同值行（`DUPLICATE`），仍会重建内存检索快照，让该行重新可见。

`update_memory` 不新增表：按 `currentText` 定位时用 `value_hmac` 在当前可见作用域内查当前版本，按 `memoryId + expectedVersion` 定位时走版本 CAS。成功更新复用 `ai_memory_item` 的版本链，旧版本转 `SUPERSEDED`、新版本 `ACTIVE`，`ai_memory_item_evidence` 链接复制到新版本；内容变更后 `origin` 转 `MANUAL` 且 `manual_protected = 1`。归一值未变化时不产生新版本；vault revision 只在真正写入时推进。

### 4.3 用量与容量

| 表 | 职责 | 关键约束 |
|---|---|---|
| `ai_usage_attempt` | 单次上游调用用量 | 预留 / 发送 / 结算 / 未知 / 释放状态；缓存读写分列 |
| `ai_usage_daily` | 按日聚合 | `(budget_date, budget_timezone, target_fingerprint, purpose)` 主键 |
| `ai_chat_storage` | 逻辑容量账本 | 单行；历史与附件上限、已用与预留字节 |

工具循环的每次上游请求按 `ai_usage_attempt` 单独结算，供应商缺少 usage 时按未知记账。工具结果发送给模型后已包含在上游输入用量内，不另重复计费；原始工具结果不写入会话历史。结果元数据原文上限 512 KiB，单轮回复容量预留 3 MiB，包含正文、思考与加密开销；检查点已用字节在最终结算时扣除，避免双计。

## 5. 版本与迁移

- `schema_version` 表记录当前迁移版本；v8 → v9 增加记忆/用量域，v10 增加 `ai_connections`，v11 增加 `ai_runtime_settings`。升级前 `DatabaseInitializer` 在 APP_ROOT 的 `backups/` 创建快照；失败则停止升级，未来版本的库拒绝降级打开。
- `AiConnectionsStore` 在首次管理读取时幂等迁移旧 chat/import，保留密文与参数；旧表保留为迁移源，已迁移的用途写入统一走连接接口。全局预算不由模型参数迁移，不创建重复的每模型副本。
- 旧备份缺列时按兼容路径读取；恢复流程在换库前做 staged 校验，换库后执行安全事务（重置记忆启用/同意指纹、推进 vault epoch 与会话 generation、处理 RUNNING turn 与活动 job）。
- 修改表结构时同步更新本文与 `schema.sql`，并补迁移测试；数据模型属于状态文档，不写未落地的计划字段。
