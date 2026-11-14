# 联网搜索工具接入：规格

> v0.8.2 F02，2026-11-14 已实现并通过工程验证。需求见 [requirements.md](requirements.md)，界面见 [ui-ux.md](ui-ux.md)，实施见 [plan.md](plan.md)，真实探测及验证边界见 [qa.md](qa.md)。

## 工具与边界

| 工具 | 参数 | 返回 |
|---|---|---|
| `search_web` | `query`（1–500 字符且不超过 2 KiB），`count`（1–5，默认 5） | `items`：真实 `title`、`url`、`snippet`；仅服务明确返回时附日期 |
| `fetch_web` | `urls`（1–3 个），`maxCharacters`（500–12,000，默认 6,000），`offset`（默认 0） | `items`：`title`、`url`、正文 `content`、`truncated`，截短时给出 `nextOffset` |

参数仍受现有 4 KiB 上限约束，拒绝重复 JSON 字段、尾随内容和未知参数。两项工具使用统一普通 / SSE 工具循环，各自受全局开关、每轮执行数、阶段数、时间及上下文预算约束。结果正文合计不超过 45,000 UTF-8 字节，再由统一循环按 64 KiB 工具结果上限复核。

来源显式标注 `untrusted=true`。网页和搜索片段不产生任何写入、删除、记忆或会话派发授权。来源以实际标题与公开 URL 链接引用；不自动抓取搜索列表中的全部页面，不把原始查询、正文、密钥或外部会话标识写入活动摘要。

## 服务与路由

服务适配器使用固定官方 HTTPS 地址。Exa 按已核对的 MCP `initialize → notifications/initialized → tools/call` 流程调用，只接 `web_search_exa` / `web_fetch_exa`，会话标识仅在单次尝试内存中使用。搜索参数包含官方要求的 `query` 和 `objective`；兼容 JSON 与 SSE 响应，按 RPC ID 选取结果。当前基础工具的实际结果为标记文本，解析真实的标题 / URL / 正文，不假定存在 JSON 搜索数组。匿名 Exa 使用公开 IPv4 直连。

Tavily 默认备用使用 `X-Tavily-Access-Mode: keyless`；自定义模式使用用户密钥认证，两种认证不混用。TinyFish 搜索为 GET、读取为 POST，两项共用用户自己的 `X-API-Key`。HTTP 200 的 MCP / API 业务错误和逐 URL 失败均独立判断。

search 默认 Exa → Tavily，匿名每家每次操作最多一次。fetch 默认本地正文提取 → Exa → Tavily，只为尚未读到正文的网址尝试后续服务，保留已成功的页面。单个远程尝试最多 10 秒，整个工具最多 20 秒且受单轮剩余时间约束。限流遵守 `Retry-After`；缺少恢复时间时应用设 60 秒短冷却，不宣称供应商额度恢复。冷却按供应商与能力分别记录在内存，不假设两类 API 共用匿名额度。参数错误、安全拒绝与停止不以远程降级掩盖。

用户明确选择 Tavily / TinyFish 时，鉴权、额度或连接失败直接反馈，不更换另一家远程服务。实际供应商及有界失败路径进入现有工具活动；错误展示受枚举白名单约束，不回显任意服务错误正文。

## 原生联网支持矩阵

原生优先默认关闭，实际执行前按本轮受理时冻结的端点、协议与模型判断。未知网关与未经适配的模型使用所选服务，普通 function tools 能力不作为原生 Web 支持证据。

| 主连接 | 已适配能力 | 实际原生协议 |
|---|---|---|
| Anthropic 官方 HTTPS Messages 端点，官方列出的 Claude Fable / Mythos 5、Opus / Sonnet 4.5–5、Haiku 4.5 / 5.5 等明确型号 | search、fetch | 基础 `web_search_20250305` / `web_fetch_20250910`；不启用动态代码过滤 |
| OpenAI 官方 HTTPS Chat Completions 连接，明确支持 Responses web search 的 GPT-5、5.1、5.2、5.4、GPT-6 Astra | search | 以同一已选模型发起隔离的 Responses `web_search` 请求；主对话协议不变 |
| OpenAI 上述连接 | fetch | 尚无独立原生 fetch 适配，使用所选网页读取服务 |
| 自定义兼容网关、DeepSeek、其它未列入能力表的协议或模型 | 无原生优先路径 | 使用所选服务；保持全局工具权限 |

准确型号由 `NativeWebCapabilities` 的明确能力表决定，不按任意名称前缀开放。Anthropic 单次搜索 `max_uses=1`，fetch 限于给定网址及域名；OpenAI `max_tool_calls=1`，取得实际搜索动作与来源后才算有效结果。调用仅携带必要查询 / URL，原生返回的引用与正文转换为相同的有界工具结果。

原生调用复用现有阶段 / 输出 / 时间预算、停止句柄和请求级用量账本；额外模型请求单独记录实际 token 用量，不能产生未统计的后台模型调用。支持但调用失败时保留原生服务原因，不偷偷重复向另一家供应商发送查询。

官方依据：[OpenAI Web search](https://developers.openai.com/api/docs/guides/tools-web-search)、[GPT-5.2 支持的工具](https://developers.openai.com/api/docs/models/gpt-5.2)、[Anthropic Web search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool)、[Anthropic Web fetch](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-fetch-tool)。协议桩验证与持钥实测分开记录；能力表不保证用户账户有权访问工具。

## 安全 HTTP 与正文提取

采用纯 JVM 的 OkHttp 4.12 与 jsoup 1.21.2，无浏览器、Python、Docker 或 MCP 子进程。客户端不使用 Cookie、磁盘缓存、代理凭证或自动重试。保留正常 TLS 证书与主机名校验。

仅接受公开 HTTP(S)、80 / 443 端口，拒绝用户信息、内网域名、私网 / 回环 / 链路本地 / 云元数据 / 保留地址以及 IPv6 映射和过渡范围。DNS 由有界线程池执行；在实际连接中校验解析出的全部地址，防止先检查后连接的 DNS 重绑定。重定向最多 5 次，每个目标先校验再连接。正常正文读取上限 1 MiB（解压后），服务响应最多 2 MiB。

本地读取接受 HTML、纯文本和 Markdown；jsoup 去除脚本、导航、表单及隐藏内容，优先正文区域，过滤登录墙 / 验证页面和只有链接的导航页。正文不足时才走远程提取；不能把 HTTP 200 登录页或空响应当正文。

## 设置接口与保存

| 接口 | 语义 |
|---|---|
| `GET /api/ai/tool-settings` | 既有接口增加 `search_web` / `fetch_web`，默认均开启 |
| `PUT /api/ai/tool-settings` | `revision` + 局部权限更新；地球按钮一次原子写入两项权限 |
| `GET /api/ai/web-settings` | `revision`、两类服务、`preferNative`、两家供应商的 `hasKey`；不返回密文或明文 |
| `PUT /api/ai/web-settings` | CAS 保存服务草稿 / 密钥，或仅修改即存的 `preferNative`；空密钥保留，`clear=true` 才删除 |
| `POST /api/ai/web-settings/test` | 测试当前服务草稿，默认分别测试 search / fetch；使用固定公开查询和网址，不保存配置、不改权限、不返回正文或密钥 |

搜索与读取服务分别选择；同一家只保存一份 AES-GCM / Argon2id 加密密钥，沿用本机指纹材料。配置进入 `ai_web_settings`，数据库结构版本 19，经原有升级前快照与 SQLite schema 初始化迁移，所有文件位于 APP_ROOT。

权限与原生优先即点即存，服务 / 密钥属于独立草稿。成功后通过既有窗口消息总线只广播缓存失效键，不广播凭证。读取旧版本不能覆盖刚确认的新版本；冲突保留草稿并刷新当前配置，用户再次保存或取消后生效。当前页面上下文只包含已保存的公开设置。
