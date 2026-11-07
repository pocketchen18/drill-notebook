# v0.7.1 AI 记忆工具：后端规格

当前已落地的工具契约与状态见 [api-contracts.md](../../api-contracts.md) 与 [features.md](../../features.md)；本文只保留后端设计规格，不重复维护状态表。

需求基线：[requirements.md](requirements.md)。

## 1. 目标与边界

- 让聊天模型在对话中按需检索当前会话可见的长期记忆，而不是把所有记忆预先注入上下文。
- 工具只返回回答所需的最小事实；授权、范围、版本与索引状态由现有记忆服务统一判断。
- 不改变 v0.7.0 的管理页检索、自动提取、对话预注入与用量记账链路。
- 工具调用是聊天模型的专属能力；导入兜底模型不启用。

## 2. 配置与能力判定

### 2.1 存储

`ai_config.params` 增加可选布尔字段 `tools`：

- 存在显式值：优先使用，`true` / `false` 都可覆盖官方端点默认值。
- 不存在：由 `ProviderCapabilities.of(endpoint, apiFormat, model, null).tools()` 推断。
- 已验证官方端点：`api.openai.com`、`api.deepseek.com`、`api.anthropic.com` + Claude Messages，默认 `true`。
- 自定义 / 未验证端点：默认 `false`。
- `PUT /api/ai/config` 未提供 `tools` 时保留既有值，不因保存其他字段被重置。

### 2.2 回显

`GET /api/ai/config` 的 `chat` 增加 `tools`，返回**当前有效值**（显式值或默认推断），供前端开关回显。

### 2.3 启用条件

一次聊天请求只有在以下条件全部满足时才携带工具定义：

```text
protocolVersion == 2
&& memoryOptions.use == true
&& MemoryTool 已注册
&& 当前模型有效 tools == true
```

任一项不满足：按原有无工具路径处理，不报错、不提示模型。

## 3. 工具协议

### 3.1 工具定义

```json
{
  "name": "search_memories",
  "description": "只读检索当前会话可见的长期记忆。",
  "parameters": {
    "type": "object",
    "additionalProperties": false,
    "required": ["query"],
    "properties": {
      "query": {
        "type": "string",
        "minLength": 1,
        "maxLength": 200,
        "description": "要检索的长期记忆关键词或问题，最多 200 个字符。"
      }
    }
  }
}
```

### 3.2 参数校验

- 原始参数 ≤ 4096 UTF-8 字节。
- 必须是 JSON 对象，只接受一个字段 `query`；重复字段、尾随 token、未知字段均拒绝。
- `query` 必须为非空字符串，≤ 200 个 Unicode code point。
- 校验失败返回 `INVALID_ARGUMENT`，不执行检索。

### 3.3 输出

```json
{
  "status": "OK | NO_MATCH | UNAVAILABLE | STALE | LIMIT_REACHED | INVALID_ARGUMENT",
  "availability": "READY | BUILDING | LOCKED | TIMEOUT",
  "facts": [
    {
      "text": "记忆正文",
      "kind": "FACT | PREFERENCE | ...",
      "scope": "GLOBAL:0 | NOTEBOOK:1",
      "expiry": "2026-11-06T00:00:00Z | null"
    }
  ],
  "incomplete": false
}
```

- `availability` 只在需要说明不可用原因时出现。
- `facts` 最多 5 条 / 次，累计最多 15 条 / 轮；结果 JSON ≤ 16 KiB。
- `incomplete = true` 表示索引损坏、截断或结果被预算截断，模型不应把结果当成完整事实。
- 不返回记忆 ID、来源会话 / 消息、内部谓词、族键、HMAC、密文或原始引用。

## 4. 授权、范围与版本

执行前由 `ChatContextService.readMemoriesForTool(plan, memoryUseRequested, query)` 统一把关：

1. 记忆功能启用，`PURPOSE_USE` 同意有效。
2. 当前会话作用域合法：GLOBAL 或当前 NOTEBOOK。
3. 记忆库已解锁，vault epoch / revision 与计划快照一致。
4. 策略 revision 与计划一致；不一致返回 `STALE`。
5. 索引 `READY` 才检索；`BUILDING` / `LOCKED` / `TIMEOUT` 返回 `UNAVAILABLE` 与对应 availability。
6. 只检索 ACTIVE、未过期、范围合法的记忆；notebook 局部覆盖优先于全局兜底。
7. 返回事实仍按现有过滤规则排除被抑制项与过期项。

工具执行不重新读取会话历史，不重复发送图片附件，不改变本轮已冻结的上下文计划。

## 5. 对话循环

### 5.1 限制

| 限制 | 值 |
|---|---|
| 工具阶段 | ≤ 2 |
| 工具执行次数 | ≤ 3 |
| 上游请求次数 | ≤ 3（另含一次既有重试预算，总上限 4） |
| 单次工具结果 token | ≤ 1200 |
| 单轮工具结果 token | ≤ 4096 |
| 工具定义 token | ≤ 1024 |
| 单次参数 | ≤ 4096 UTF-8 字节 |
| 结果 JSON | ≤ 16 KiB |

工具按返回顺序串行执行，不并行。

### 5.2 流程

1. 建立本轮 `ChatContextPlan`，冻结历史、摘要、记忆与作用域快照。
2. 首次请求携带工具定义；OpenAI Chat Completions 使用 `tools` / `tool_calls`，Anthropic Messages 使用 `tools` / `tool_use` / `tool_result`。
3. 工具模式下缓冲文本与 reasoning 增量，不立即回放给前端。
4. 收到工具调用后：校验参数 → 执行 → 把 assistant tool call 与 tool result 追加到本轮消息 → 再次请求。
5. 模型不再调用工具时，回放缓冲的最终回答增量，正常结束。
6. 达到阶段 / 次数上限时，下一次请求不再携带工具定义，要求模型基于已有结果作答。
7. 整轮只 `begin` / `finalize` 一次；每次上游请求单独结算 usage；失败不把「只有工具调用、没有回答」记为成功。

### 5.3 流式与取消

- 普通与 SSE 两条路径共用同一循环。
- 工具调用帧不进入前端 SSE；只回放最终回答的 `text` / `reasoning` / `done`。
- 客户端取消时终止整个循环，关闭上游连接，保留已产生的部分文本。
- 下游断开时不再发起新的工具阶段。

## 6. 隐私与记账

- 工具参数与结果只存在于本轮内存，不写入会话消息、历史、SSE、日志或上下文审计明文。
- 日志只记录长度、状态、耗时等元数据。
- 上下文审计只保存加密的元数据摘要，≤ 16 KiB。
- usage 按每次上游请求记录；OpenAI cached token 是输入的子集，Anthropic cache read / write 独立分桶，不跨请求求和。
- 工具执行本身不计费；只有上游模型请求计费。

## 7. 失败语义

| 情况 | 行为 |
|---|---|
| 参数非法 | 返回 `INVALID_ARGUMENT`，模型可修正后重试（受次数限制） |
| 记忆未启用 / 未同意 | 不携带工具；或按现有策略返回 `UNAVAILABLE` |
| 索引构建中 / 锁定 / 超时 | `UNAVAILABLE` + availability，不阻塞回答 |
| 范围或版本过期 | `STALE`，不返回旧事实 |
| 无匹配 | `NO_MATCH`，`facts` 为空 |
| 达到次数 / 预算 | `LIMIT_REACHED`，停止继续调用 |
| 上游不支持工具 | 请求失败按现有错误路径返回；不回退成伪造的工具结果 |
| 工具执行异常 | 返回 `UNAVAILABLE`，本轮仍尽量给出回答 |

## 8. 涉及文件

后端：

- `backend/src/main/java/com/drillnotebook/app/service/MemorySearchTool.java`
- `backend/src/main/java/com/drillnotebook/app/service/AiService.java`
- `backend/src/main/java/com/drillnotebook/app/service/AiUpstreamClient.java`
- `backend/src/main/java/com/drillnotebook/app/service/ChatContextService.java`
- `backend/src/main/java/com/drillnotebook/app/service/ProviderCapabilities.java`
- `backend/src/main/java/com/drillnotebook/app/service/ContextMemorySource.java`
- `backend/src/main/java/com/drillnotebook/app/service/IndexedMemorySource.java`

测试：

- `MemorySearchToolTest.java`
- `MemoryToolChatPathTest.java`
- `AiServiceConfigToolsTest.java`
- `AiUpstreamClientTest.java`

前端：

- `frontend/src/components/AiModelSlotCard.tsx`
- `frontend/src/lib/types.ts`
- `frontend/src/components/AiModelSlotCard.test.tsx`
