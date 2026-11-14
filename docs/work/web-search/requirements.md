# 联网搜索工具接入：需求

> v0.8.2 F02，2026-11-14 已实现并通过工程验证，真实匿名服务探测通过。默认采用已确认的「内置自动」方案。范围见[本版功能清单](../../state/v0.8.2-features.md#4-f02-范围与默认方案)，界面见 [UI/UX](ui-ux.md)，工具、协议与能力矩阵见[规格](spec.md)，验证范围见 [QA](qa.md)。

## 1. 目标

助手按需搜索互联网，再读取指定网页正文，取得可引用的实时资料。无自定义配置时即可使用默认服务；用户也能配置 TinyFish / Tavily，或开启优先使用当前模型内置 Web 工具。保持 Windows 绿色便携，不增加本地服务部署或额外浏览器安装。

## 2. 功能需求

| 编号 | 需求 | 验收口径 |
|---|---|---|
| R01 | search 与 fetch | `search_web` 返回标题、URL 和相关片段；`fetch_web` 读取指定 URL 的可用正文，参数与返回上限见规格 |
| R02 | 免配置默认服务 | 无账号、密钥或自定义配置时默认启用；可通过既有工具开关关闭，关闭后模型内置联网也不得绕过 |
| R03 | 首批自定义供应商 | 支持 TinyFish、Tavily 的搜索与抓取；两类能力可分别选供应商，配置可保存、取消和测试连接 |
| R04 | 模型内置 Web 优先 | 提供独立开关；按实际模型、供应商和协议分别判断 search / fetch，不能把普通 function tools 支持视为内置联网支持 |
| R05 | 来源与内容预算 | 返回真实来源与实际可用内容，未知发布日期不编造；不自动抓取所有搜索结果，正文按现有工具预算分段或截断 |
| R06 | 运行与错误反馈 | 遵守现有工具启停、停止、并发与整轮预算；正确处理超时、限流、额度不足和逐 URL 失败；显示安全的真实原因 |
| R07 | 数据与凭证 | 仅发送本次必要查询或目标 URL，密钥沿用后端加密存储；不把密钥、私有正文或完整查询写入普通日志与活动摘要 |
| R08 | 安全与便携 | 配置 / 缓存 / 临时数据经 PortablePathResolver 落 APP_ROOT；抓取仅公开 HTTP(S)，校验重定向及目标地址，不访问本机、私网或云元数据 |
| R09 | 设置与快捷入口 | 「设置 → 工具」分别控制搜索 / 网页读取，助手输入栏增加地球按钮，快速同步修改同一对全局权限；权限与服务配置跨会话、重启保存，不另加会话级联网开关 |
| R10 | 配置编辑与反馈 | 工具开关即点即存，服务选择 / 密钥使用独立草稿，保存后生效、取消恢复；同一供应商供两类能力使用时共用一份密钥；实际服务与失败原因沿用现有工具活动显示 |

截图用于参考地球快捷按钮、工具设置与服务选择的布局；首批可选自定义供应商仍为 TinyFish / Tavily，不照搬参考软件的供应商列表。

## 3. 已确认的默认方案与依据

**默认选择「内置自动」：search 优先 Exa 官方免密钥 MCP，失败时有界尝试 Tavily Keyless；fetch 优先本地 HTTP 正文提取，正文不足时再尝试远程抓取。** Tavily 备用复用首批自定义适配器，不单独增加账号流程。Exa 通过现有本地后端直接使用远程 MCP 的 HTTP 协议，只开放 `web_search_exa` / `web_fetch_exa`，无需打包 MCP 子进程、npx、Python、Docker 或额外浏览器。该组合降低对单一匿名端点的依赖，仍受供应商额度与网络条件限制。默认开启表示模型可以按需使用，不能据此强制每次提问联网。

| 模式 | search | fetch |
|---|---|---|
| 内置自动首选 | `https://mcp.exa.ai/mcp?tools=web_search_exa,web_fetch_exa` 中的 `web_search_exa` | 原 URL 的公开 HTTP(S) 正文提取；不足时使用同一 MCP 的 `web_fetch_exa` |
| 内置自动备用 | Tavily Keyless：`POST https://api.tavily.com/search` | Tavily Keyless：`POST https://api.tavily.com/extract` |
| 自定义 Tavily | 同一 HTTP API，使用用户配置的 API Key | 同一 HTTP API，使用用户配置的 API Key |
| 自定义 TinyFish | `GET https://api.search.tinyfish.ai` | `POST https://api.fetch.tinyfish.ai` |

Tavily 免密钥请求按官方文档设置固定的 `X-Tavily-Access-Mode: keyless`；用户配置密钥时改用密钥认证，不同时混用两种模式。TinyFish 两类接口均要求用户自己的 `X-API-Key`，免费日额度不等于免配置，不随软件内置共享 Key。

Tavily 官方将 Keyless 定位为探索与轻量使用，准备生产使用时建议配置 API Key。匿名限流与 API Key 账户限流独立，错误格式也不同；不能把账户免费额度或 RPM 套用到匿名模式。本次未查到公开的匿名固定额度或 SLA，不写无限、永久免费或无条件稳定的承诺。

2026-11-14 使用公开查询 / 官方文档 URL，各探测一次 Tavily 无密钥 `/search` 与 `/extract`，两项均返回 **HTTP 429、`error.code=daily_cap_reached`、`window=day`**。响应只提供 `Retry-After` / `retry_after_seconds` 的剩余等待时间，没有日上限次数；等待秒数不是额度数量。官方 Keyless 页、限流页及帮助中心限流说明均未给出可核实的匿名次数。当前没有取得 Tavily 有效搜索 / 正文结果，不能据此确认其开箱即用或地区可达性；也不能从单次探测推断它始终不可用、额度按 IP 划分或搜索 / 抓取共用额度。因此它作为可冷却的备用，不作为唯一默认依赖。

同日完成 Exa MCP 的 `initialize → notifications/initialized → tools/list → tools/call` 匿名链路，仅请求上述两项基础工具。`web_search_exa` 使用公开文档查询，实际返回 **3 个带真实标题 / URL / 片段的来源**；`web_fetch_exa` 实际返回指定 Exa 文档的约 3,000 字符正文，两项均 HTTP 200 且没有 MCP 业务错误。搜索 schema 要求 `query` 和 `objective`，接入时须按实际 schema 构造参数。此结果证明当前环境能完成搜索与抓取，不代表中文质量、持续可用性、所有地区或代理环境已经验证。

Exa 官方 MCP 的 403 说明明确：匿名访问要求 IPv4 直接连接 `https://mcp.exa.ai/mcp`；IPv6 或代理路径需 OAuth / API Key。因此本地桌面架构也不能保证所有用户免配置可用。本次未找到「匿名 3 QPS、单 IP 每天 50–150 次」的官方依据；[账户 API 限流](https://exa.ai/docs/admin/billing)当前普通搜索为 10 QPS，不能套到匿名 MCP。软件的 Java 后端运行在每位用户本机，不是共享云端转发服务，但仍需处理匿名限制、网络不可达和真实报错。

如果后续要求对大量用户承诺持续可用，需要有预算的项目托管搜索服务或供应商合作额度，服务密钥保存在服务端；这是另行立项的服务运营工作。不能把共享密钥藏进客户端，或轮换公共实例来替代服务保证。

## 4. 路由规则

- 「优先使用模型内置 Web 工具」默认关闭。开启后，仅对已明确支持且已完成协议适配的对应能力优先使用；缺少能力时使用用户选定的服务，未配置时使用内置服务。
- search 与 fetch 分别路由，可组合不同供应商。已禁用的工具不会因为内置优先开关或服务降级而重新开启。
- 一次任务按确定的路径执行；不同时调用模型内置与外部服务完成同一操作，避免重复请求和费用。用户明确选择的自定义服务出现无效密钥 / 额度错误时如实报告，不静默换成其它远程供应商。
- 默认 search 顺序为 Exa → Tavily Keyless；首选限流、匿名鉴权受限、网络失败或无可用结果时才进入备用。匿名调用每家最多尝试一次，尊重 `Retry-After` 设冷却，下一轮冷却期间直接跳过该供应商；参数错误不以降级掩盖。单个远程尝试至多 10 秒、整个搜索工具至多 20 秒，同时受现有整轮预算和停止信号约束，全部不可用时返回真实原因。
- 默认模式记录实际使用的供应商；不轮换 IP 或公共实例规避额度。初版不按语言硬编码「中文必走 Bing、英文必走 Exa」，也不把 Bing / DuckDuckGo HTML 抓取加入默认链；此类方案的结构维护、反爬与发行使用条件仍需评估。
- 默认 fetch 先通过现有 HTTP 能力和成熟正文提取库读取公开静态网页，正文不足再有界尝试 Exa fetch / Tavily Extract；与 search 的冷却及总预算配合。需要登录、交互或未能取得有效正文时明确说明，不把导航页 / 登录页假称正文。无法搜索时仍可读取已知 URL。
- 设置页的 search / fetch 是独立全局工具权限。地球按钮显示两项的合并状态：全开时点击全关，全关或部分开启时点击全开；一次原子保存同一对权限。开关保存失败或版本冲突时不显示伪成功，服务端确认后同步其它窗口与会话。关闭后模型内置 Web 与远程降级也不能绕过权限。

## 5. 候选比较与官方依据

以下于 2026-11-14 核对官方文档与 README，并执行上述 Tavily 匿名探测及 Exa 搜索 / 抓取样本；未进行搜索质量、速度或地区可达性对照测试。

| 候选 | 已核对的能力与限制 | 本项目建议 |
|---|---|---|
| [Tavily Keyless](https://docs.tavily.com/documentation/keyless) | 官方无账号 / Key 的 Search、Extract；直接 HTTP；匿名独立限流，当前两次探测均为日额度 429，官方建议生产使用 Key | 已选为可冷却的自动备用，复用 Tavily 适配器；不作为唯一默认依赖 |
| [Exa 官方 MCP](https://exa.ai/docs/get-started/exa-mcp) | 开源 MCP 适配器连接商业搜索服务；匿名限流下支持 search / fetch，官方要求 IPv4 直连，IPv6 / 代理需 OAuth / Key；当前两项实际调用成功 | 已选为内置自动 search 首选与远程 fetch 服务；只接两项基础工具，不启动本地 MCP 进程，不承诺固定匿名额度 |
| [SearXNG Search API](https://docs.searxng.org/dev/search_api.html) | 元搜索服务，需服务部署；许多公共实例禁用 JSON，未启用格式返回 403；查询仍转发给外部搜索服务 | 适合可控托管 / 自建，不依赖随机公共实例；自建不等于没有第三方数据传输 |
| [Open-WebSearch](https://github.com/Aas-ee/open-webSearch) | 支持免 Key 搜索和抓取，可纯 HTTP、不强制浏览器；README 明示搜索引擎封锁与 HTML 结构变化风险，另有仅供个人使用声明 | 可参考请求与正文提取思路，不据此宣称更稳定或直接选作发行默认 |
| [Crawl4AI](https://github.com/unclecode/crawl4ai) | Cloud 有 search / scrape / extract，但需 Key；本地库 / 自托管主要用于抓取，安装流程包含浏览器依赖 | 可供后续复杂抓取评估，不作为轻量免配置默认服务 |

首批自定义接口依据：[TinyFish Search](https://docs.tinyfish.ai/search-api/reference)、[TinyFish Fetch](https://docs.tinyfish.ai/fetch-api/reference)、[Tavily API](https://docs.tavily.com/documentation/api-reference/endpoint/search)；限流依据：[Tavily Rate Limits](https://docs.tavily.com/documentation/rate-limits)、[官方帮助中心](https://help.tavily.com/articles/3240802908-rate-limits)。账户的每月 1,000 免费 credits 与账户 RPM 不能当作匿名额度，价格与额度以供应商当时的正式规则为准。

客户端实践参考：[OpenCode 当前联网搜索文档](https://opencode.ai/docs/websearch/)支持多个供应商、首次选择与可选 random 模式；random 在 429 后给原供应商设冷却并尝试其它可用供应商，全部冷却则立即失败。该实现支持供应商抽象与有界降级的方向，不能概括成所有客户端都固定默认 Exa。[Exa 当前价格](https://exa.ai/docs/admin/pricing)中的 Agent 固定努力档位最高为 $1，但 auto / ultra 是按使用计费且有更高默认上限；本切片只开放基础 search / fetch，不接这些研究工具。自然语言查询是 Exa 的[官方建议](https://exa.ai/docs/search/best-practices)，中文质量、特定错误码与弱模型表现仍不能由此推定。

## 6. 验收口径

- 无自定义配置可完成 search 和指定 URL 的 fetch；已关闭的能力不得调用，离线 / 匿名额度不足时返回真实可恢复状态。
- TinyFish、Tavily 各自覆盖搜索与抓取、错误响应及部分 URL 失败；不把 HTTP 200 内的业务错误当成功。
- 内置优先只在实际支持的协议路径生效，切换模型后重新判定；能力不支持时有正确路由，不重复请求或重复计费。
- 工具设置与助手地球按钮覆盖全开 / 全关 / 部分开启、保存失败与多窗口冲突；设置跨会话、重启保留。服务草稿取消不改权限，供应商的密钥字段不重复，也不进入页面读取上下文。
- 停止、超时、预算与来源引用有效；恶意网页正文只作资料，不成为新的工具权限或执行指令。凭证、笔记和记忆不意外外传。
- 验证公开 URL 与重定向限制、正文 / 搜索结果上限；新增写盘能力后复跑运行时落盘与静态便携审计。

## 7. 范围边界

本切片接入软件明确支持的联网工具；不开放 Shell、任意本地文件、通用脚本、浏览器登录或其它网站自动操作。网页抓取不复用用户浏览器 Cookie / 登录状态，不增加批量爬站、通用 MCP 市场、第三方账号自动注册或本地搜索服务安装。实现采用 OkHttp / jsoup，原生联网支持矩阵、接口与存储已在[规格](spec.md)明确；持钥实测和 Electron 实机等尚未执行项见 [QA](qa.md)。
