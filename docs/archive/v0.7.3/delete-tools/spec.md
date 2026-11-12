# 删除工具与全局工具设置规格

> 已于 2026-11-11 随 v0.7.3 归档，仅作历史参考；版本收尾见 [归档说明](../README.md)。下文保留实施时的验证结论。

> 按 [UI/UX](ui-ux.md) 实现四个删除工具，并为全部 20 个现有内置工具提供独立全局开关；不实现移动、批量删除或联网搜索配置。

## 工具与参数

| 工具 | 参数 | 行为 |
|---|---|---|
| `delete_memory` | `currentText`，或 `memoryId` + `expectedVersion`；二选一 | 删除当前会话可见范围（本册 + GLOBAL）内的一条长期记忆 |
| `delete_session` | `session`：本地会话链接或 32 位引用 | 删除一个历史会话及其消息 |
| `delete_material` | `type`（notebook/note/knowledge/bank/question）、`id`、`operationId`、`expectedVersion`、`cascade?` | 删除一个学习资料对象 |
| `remove_wrong_question` | `questionId`、`operationId`、`expectedVersion` | 将题目移出待巩固，不删除原题和作答记录 |

- 参数最多 4096 字节，严格拒绝未知字段、重复键、尾随内容和隐式类型转换。`operationId`、`expectedVersion` 的格式与修改工具一致。
- `expectedVersion` 取自对应读取工具的 `updateVersion`（笔记本 / 题库在目录的 `container`，错题取 `read_wrong_questions`），证明模型已定位并读取该对象；不符返回 CONFLICT。
- `cascade` 只适用于 notebook / bank。笔记本含页面、题库含题目或知识点时必须为 true，否则返回 NOT_EMPTY 和 `contains` 数量，不删除。
- `delete_session` 不接受数字 ID 或标题；链接来自 `list_sessions` / `read_session` 或用户提供。

## 删除语义

- 资料删除复用人工删除路径的级联：笔记页清理索引、题目引用与计划项；笔记本同时清理其 NOTEBOOK 记忆；知识点、题目清理关联、复习与计划记录；题库连同其题目、作答记录和所属知识点。附件文件沿用现有策略，不新增文件删除路径。
- 删除笔记页时把它的子页面改挂到原上级页面，人工删除路径同样生效，不留下悬空的上级引用。
- 笔记本删除与记忆清理在同一事务内完成；随后刷新本轮计划的记忆库版本，本轮可以继续回答。调用会话的记忆范围绑定该笔记本时返回 IN_USE。
- 删除不受同页未保存草稿阻止；草稿由前端丢弃。
- `remove_wrong_question` 只置「已移出」，再次答错按既有规则重新计入；题目本就不在错题本时返回 NO_CHANGE。
- 会话删除复用现有清理：保留长期记忆，只清理来源证据。目标是当前会话或其上游返回 CYCLE_BLOCKED；目标有运行中的回答或待发送消息返回 IN_USE。
- 会话删除须核对注册中的执行句柄；在删除事务中再次检查来源轮次、上下文代数、取消状态、目标引用及运行/排队状态，过期调用不得执行。
- 记忆删除复用记忆库的遗忘语义：写入抑制，推进库纪元并使旧摘要检查点失效；仅在本工具确认 DELETED 后刷新本轮计划，使本轮继续。同时运行的其他会话会按既有规则收到「上下文已变化」。
- 本轮计划仅接受自身这一次删除造成的版本增量；期间还有其他库变更或摘要替换时，仍须按上下文变化停止，不能把并发变更一并放行。

## 回执与幂等

- schema 16 → 17：新增 `ai_material_deletion` 回执表（请求 / 操作标识、来源会话、输入指纹、目标类型 / ID、结果），不保存标题或正文；会话删除清回执。新增 `ai_tool_settings` 单行表。
- 两个资料工具共用回执流程：事务先占回执取得写锁，核对运行句柄、轮次、上下文代数与取消，再读版本并执行。同请求 / 操作、相同指纹或显式重试链命中已完成回执时返回 DUPLICATE，不再执行；参数改变返回 CONFLICT。失败整体回滚，不留回执。
- 记忆与会话删除不建回执：重复调用返回 NOT_FOUND，表示目标当前已不存在。

## 返回

- 资料：`{status,items:[{type,id,title,sourceLink,notebookId?|bankId?,deleted?}],sourceLink,incomplete:false}`；status 为 DELETED / REMOVED / NO_CHANGE / DUPLICATE。`deleted` 为级联数量（`pages`，或 `questions` / `knowledgePoints`）。移出错题本的 sourceLink 指向原题。
- 会话：`{status:"DELETED",items:[{title}],incomplete:false}`，不返回已失效的链接。
- 记忆：`{status,memoryId?,availability?}`。
- 失败为空 items、incomplete=true；状态另有 INVALID_ARGUMENT / NOT_FOUND / CONFLICT / NOT_EMPTY / IN_USE / CYCLE_BLOCKED / AMBIGUOUS / STALE / CANCELLED / REJECTED / DISABLED / UNAVAILABLE。UNAVAILABLE 表示结果待核对。
- 活动白名单新增四个工具名与 DELETED / REMOVED / NOT_EMPTY / IN_USE / DISABLED 五个结果；DELETED、REMOVED 计数为 1。

## 开关

- `GET /api/ai/tool-settings` 返回 `{revision,tools:[{name,group,enabled,defaultEnabled}]}`，按下表顺序分组；非删除项 defaultEnabled=true，删除项 false。

| group | 工具 |
|---|---|
| `material` | read_current_page、search_materials、read_notebook、read_knowledge、read_question_bank、read_wrong_questions、create_material、update_material |
| `memory` | search_memories、list_memories、create_memory、update_memory |
| `session` | list_sessions、read_session、create_session、send_message |
| `delete` | delete_memory、delete_session、delete_material、remove_wrong_question |

- `PUT /api/ai/tool-settings` 接收 `{revision,enabled:{<name>:boolean}}`，只更新给出的项，返回同形视图；未知工具名或非布尔值 400，版本不符 `409 AI_SETTINGS_CONFLICT`。
- 沿用 schema 17 的单行表，无需迁移；只保存与默认值不同的选择。无记录或旧配置没有某项时按该项默认值，已有删除选择不得被覆盖；局部更新不丢失其它组的选择。恢复默认提交接口返回的全部 defaultEnabled。
- 存储读取失败或已知项损坏时，设置接口报错，不用默认值冒充已保存状态；聊天暂时关闭所有工具，防止重新开启用户已关闭的项。未知工具名忽略，兼容跨版本配置。
- 每轮开始读取一次：所有关闭项，以及派发任务中的删除工具，不进入模型工具列表。执行前再读一次，已关闭返回 DISABLED。记忆工具另受本轮记忆开关约束，所有工具仍受模型工具能力约束，不自动启用依赖工具。
- 系统指引仅适用于本轮可用工具；关闭项说明能力不可用及设置入口，派发任务的删除限制单独说明。删除能力不可用时不得用修改 / 清空替代。全部关闭仍有不可用说明并可正常聊天。

## 前端

- `toolActivity.ts`：四个工具名称、五个新结果、文案（见 UI/UX 表）与分组标题；DELETED / REMOVED 为成功色。
- `ToolActivityGroup`：操作范围；`delete_material` 不显示「查看资料」，`remove_wrong_question` 显示「查看原题」；删除结果未确认的提示。
- `AiAssistant`：删除成功后刷新相关查询；笔记页 / 笔记本删除通过 `lib/materialEvents.ts` 通知 `NotebookPage` 丢弃对应草稿并移除页面缓存；记忆、会话删除刷新各自列表。
- 设置：`lib/aiToolSettings.ts` 定义分组与用途，名称复用活动名称；`AiToolSettingsPanel` 分组展示全部开关；沿用 `SettingsPage` 的 `tools` Tab 与 `viewState`。DISABLED 结果文案适用于全部工具。

## 验证

真实 SQLite 覆盖五类资料删除与级联、子页面改挂、版本冲突、非空保护、绑定笔记本、错题移出与统计保留、重试 / 冲突 / 取消 / 过期轮次、迁移；会话删除的忙碌 / 自身 / 上游 / 证据保留；记忆删除的定位、作用域、抑制与计划刷新。本机模拟上游验证默认不提供、开启后读取→删除→回答、执行前关闭、派发任务不提供，以及删除带记忆的笔记本后本轮完成。前端测试文案、来源、刷新、草稿丢弃与设置面板，执行构建。结果记录在 QA。

本轮补充：全部工具纳管与分组、16 开 4 关默认值、旧配置兼容、跨组局部保存与恢复默认、损坏配置不意外放开；普通工具关闭后不提供且不可执行、运行中关闭、重新开启下一轮生效、全部关闭正常聊天、记忆与派发限制仍有效。前端验证分组、持久化回显、恢复默认、失败 / 冲突与 DISABLED 文案。
