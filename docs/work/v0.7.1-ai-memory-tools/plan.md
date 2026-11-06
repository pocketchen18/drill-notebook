# v0.7.1 AI 记忆工具：执行计划

状态：`search_memories` 与工具框架已完成（工作树，未提交）。本计划覆盖剩余 3 个工具与配套能力，按顺序推进；每步完成并验证后再进入下一步。

## 1. 当前进度

| 步骤 | 内容 | 状态 |
|---|---|---|
| P0 | 工具协议与通用循环（OpenAI / Anthropic，普通 + SSE） | 完成 |
| P1 | `search_memories` 只读检索工具 | 完成 |
| P2 | 设置页「工具调用」开关与有效值回显 | 完成 |
| P3 | `list_memories` 标题列表工具 | 待开始 |
| P4 | `create_memory` 新增记忆工具 | 待开始 |
| P5 | `update_memory` 更新记忆工具 | 待开始 |
| P6 | 记忆操作状态展示 | 待开始 |
| P7 | 语义检索与用量统计 | 待开始 |

## 2. P3：`list_memories`

### 目标

模型按授权范围分批查看记忆标题，用于「我记了哪些事」类问题，不返回正文。

### 文件范围

- 新增 `backend/src/main/java/com/drillnotebook/app/service/MemoryListTool.java`
- `AiService.MemoryTool` 注册位与工具选择：允许按请求选择不同工具，或先用一个 `MemoryTools` 聚合
- `ChatContextService`：新增只读标题查询，复用现有 scope / vault / policy 校验
- `MemoryIndexService` 或 `MemoryRepository`：按 scope + 时间倒序取标题，不返回正文
- 测试：`MemoryListToolTest.java`、扩展 `MemoryToolChatPathTest.java`

### 约束

- 参数：`limit`（1–20，默认 10）、可选 `scope`（GLOBAL / NOTEBOOK / ALL）；未知字段拒绝。
- 返回：`{status, items:[{title, scope, updatedAt}], nextCursor?, incomplete}`。
- 不返回正文、记忆 ID、内部谓词、来源密文。
- 授权与 `search_memories` 相同；未解锁 / 构建中返回 `UNAVAILABLE`。
- 结果 ≤ 1200 token；单轮累计 ≤ 15 条。

### 验收

- 「我记了哪些事？」触发列表工具，回答只引用标题。
- 无记忆返回 `NO_MATCH`，不编造。
- 关闭工具开关后请求不携带定义。

## 3. P4：`create_memory`

### 目标

把对话中具有长期价值、且用户希望保留的信息新增为记忆；不重复写入归一值完全相同的记忆。

### 文件范围

- 新增 `backend/src/main/java/com/drillnotebook/app/service/MemoryCreateTool.java`
- `MemoryItemService` 或新的写入服务：复用自动提取的归一化、去重、scope 与证据写入
- `MemoryPolicyService`：写入资格、手动写入指纹与同意检查
- `ChatContextService`：写入后推进 vault revision / 会话 generation，避免本轮后续读取旧快照
- 测试：`MemoryCreateToolTest.java`、`MemoryToolChatPathTest.java` 增加写路径

### 约束

- 参数：`text`（1–1000 字符）、`kind`、可选 `scope`、可选 `expiry`；未知字段拒绝。
- 写入前必须通过手动写入资格与用户同意；AI 自主写入需要显式策略开关。
- 归一值完全相同 → 返回 `DUPLICATE`，不新增版本。
- 写入来源标记为 `AI`，证据指向当前会话 / 轮次 / 消息。
- 写入成功后返回 `{status:"CREATED", memoryId, version}`；不返回正文回显。
- 单轮最多新增 3 条；超过返回 `LIMIT_REACHED`。
- 不写日志明文、不写上下文审计明文。

### 验收

- 用户说「记住：我每周三晚上复习」→ 新增一条 GLOBAL 记忆，管理页可见。
- 重复说同一内容 → 返回 `DUPLICATE`，管理页不出现两条相同记忆。
- 未同意 / 未解锁 → `UNAVAILABLE`，不落库。
- 写入后当轮搜索能读到新版本（revision 已推进）。

## 4. P5：`update_memory`

### 目标

用户纠正或补充时更新对应记忆，旧版本归档，新版本继承来源证据，避免新旧内容并存。

### 文件范围

- 新增 `backend/src/main/java/com/drillnotebook/app/service/MemoryUpdateTool.java`
- 复用 `MemoryItemService` 的版本写入、证据继承、手动来源标记与 CAS
- `ChatContextService`：更新后推进 revision / generation
- 测试：`MemoryUpdateToolTest.java`、`MemoryToolChatPathTest.java` 增加更新路径

### 约束

- 参数：`memoryId`、`expectedVersion`、`text`、可选 `expiry`；未知字段拒绝。
- 必须带 `expectedVersion` 做乐观锁；版本不匹配返回 `STALE`，不覆盖他人修改。
- 更新后旧版本转 `SUPERSEDED`，新版本 `ACTIVE`；继承证据；来源转人工。
- 归一值与原值相同 → `NO_CHANGE`，不新增版本。
- 单轮最多更新 3 条。
- 不允许跨 scope 移动；需要移动时先删除再新建。

### 验收

- 「把每周三改成每周四」→ 同一 memoryId 新版本，旧版本归档，管理页只显示一条。
- 并发修改 → 后到者 `STALE`，不覆盖。
- 更新后当轮搜索读到新版本。

## 5. P6：记忆操作状态展示

### 目标

让用户知道本轮是否使用了记忆、用了哪些、是否被截断，而不是只看到一句回答。

### 交互

- 在回答下方显示一行状态：`已参考 3 条记忆` / `记忆索引构建中，本轮未使用` / `未找到相关记忆`。
- 可展开查看被引用记忆的标题与范围；不显示内部 ID、谓词、来源密文。
- 工具调用失败不弹错误，只在状态行说明原因。

### 文件范围

- 后端：`ChatContextPlan` / 轮次结果增加 `memoryToolStatus` 摘要（已加密审计 + 明文字段仅标题 / scope / 数量）
- 前端：`AiAssistant.tsx` / `useAiChatTurn.ts` 渲染状态行与展开区
- 测试：组件测试 + `MemoryToolChatPathTest` 断言状态字段

### 验收

- 工具命中 → 显示数量与标题；无命中 → 显示未找到；不可用 → 显示原因。
- 关闭工具开关 → 不显示状态行。

## 6. P7：语义检索与用量统计

### 语义检索

- 复用现有 embedding 空间与 `retrieval_embedding`，为 `ai_memory_item` 的 ACTIVE 版本建立向量索引。
- 检索时先按 scope / 过期 / 抑制过滤，再做向量召回，最后按阈值与预算截断。
- 索引未就绪时回退现有索引检索，不阻塞回答。
- 需要新增记忆向量表或复用检索分块表；优先复用现有 `embedding_job` 队列与模型选择。

### 用量统计

- 按模型 / 日期 / 用途聚合 `ai_usage_attempt` 与 `ai_usage_daily`。
- 展示输入、输出、缓存读 / 写、节省估算；不把缓存子集重复计数。
- 设置页或用量页新增查询接口与只读视图。

## 7. 验证命令

```powershell
# 后端定向
.\mvnw.cmd -q '-Dtest=MemoryListToolTest,MemoryCreateToolTest,MemoryUpdateToolTest,MemoryToolChatPathTest,MemorySearchToolTest' test

# 后端全量
.\mvnw.cmd -B -f backend/pom.xml test

# 前端
cd frontend
npm test
npm run build
```

每个工具至少覆盖：正常、参数非法、未授权、范围不符、索引不可用、重复 / 版本冲突、达到上限。

## 8. 风险与回滚

- **写入放大**：AI 自主写入必须有策略开关与单轮上限；默认关闭，避免污染记忆库。
- **版本并发**：更新必须 CAS；失败返回 `STALE`，不自动重试覆盖。
- **作用域泄漏**：所有读写先校验 GLOBAL / NOTEBOOK；notebook 记忆不得出现在其他笔记本会话。
- **索引不一致**：写入 / 更新后推进 revision；本轮后续工具调用重新读取快照，不使用旧索引。
- **回滚**：每个工具独立开关与独立测试；关闭工具开关即回到 v0.7.0 的纯预注入路径。

## 9. 完成标准

- 4 个工具全部实现并通过定向 + 全量测试。
- 设置页开关、状态展示、管理页来源与版本行为符合 UI/UX 文档。
- 状态文档 `docs/features.md`、`docs/data-model.md`、`docs/api-contracts.md` 已同步。
- 本目录归档到 `docs/archive/v0.7.1/`，不留在活跃上下文。
