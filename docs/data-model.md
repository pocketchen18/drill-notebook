# 数据模型

当前 schema 版本：**9**。完整 DDL 以 `backend/src/main/resources/schema.sql` 为准；本文只记录已落地表、职责与关键约束，便于快速定位，不复制字段级定义。

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

## 4. AI 会话、记忆与用量域（schema v9）

### 4.1 配置与会话

| 表 | 职责 | 关键约束 |
|---|---|---|
| `ai_config` | 模型配置 | `purpose` 主键；密钥密文；`params` JSON 存协议、流式、缓存、工具等开关 |
| `ai_chat_session` | 会话 | 标题、归档、模型、标签 |
| `ai_chat_message` | 消息 | 正文与 reasoning 分列密文 |
| `ai_chat_turn` | 单轮请求 | `request_id` 唯一；同一会话仅一个 `RUNNING`；scope 与时间线约束 |
| `ai_chat_attachment` | 轮次附件 | `TEXT` / `IMAGE` 密文；`byte_length` 校验 |

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

### 4.3 用量与容量

| 表 | 职责 | 关键约束 |
|---|---|---|
| `ai_usage_attempt` | 单次上游调用用量 | 预留 / 发送 / 结算 / 未知 / 释放状态；缓存读写分列 |
| `ai_usage_daily` | 按日聚合 | `(budget_date, budget_timezone, target_fingerprint, purpose)` 主键 |
| `ai_chat_storage` | 逻辑容量账本 | 单行；历史与附件上限、已用与预留字节 |

## 5. 版本与迁移

- `schema_version` 表记录当前迁移版本；v8 → v9 的迁移与兼容逻辑在 `DatabaseInitializer` 及迁移测试中。
- 旧备份缺列时按兼容路径读取；恢复流程在换库前做 staged 校验，换库后执行安全事务（重置记忆启用/同意指纹、推进 vault epoch 与会话 generation、处理 RUNNING turn 与活动 job）。
- 修改表结构时同步更新本文与 `schema.sql`，并补迁移测试；数据模型属于状态文档，不写未落地的计划字段。
