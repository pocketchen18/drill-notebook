# 联网搜索工具接入：验证记录

> v0.8.2 F02，2026-11-14。已实现并通过工程验证，真实匿名搜索 / 抓取探测通过；本版尚未发布。范围见[需求](requirements.md)，契约与能力矩阵见[规格](spec.md)。

## 1. 结果

| 项目 | 实际结果 |
|---|---|
| 后端相关回归 | 46 类 / 443 项：442 通过，1 项真实匿名探测默认按条件跳过；随后显式运行该项通过，最终报告 443 通过、0 失败、0 错误、0 跳过 |
| Web 核心协议与编排 | 8 类 / 82 项全部通过；覆盖设置、路由、传输、正文提取、公开 URL、原生客户端及完整对话工具循环 |
| 前端相关回归 | 17 文件 / 180 项：178 通过、2 项既有证据截图测试跳过、0 失败、0 未处理错误 |
| 构建与开发 JAR | TypeScript / Vite 构建、后端隔离编译与 Boot 打包通过；679 个应用 class 与同轮编译逐一校验，开发 JAR SHA-256 一致 |
| 真实匿名搜索 | 默认生产适配器返回 `OK`，实际路由为 Exa，取得有效来源 |
| 真实匿名抓取 | 指定 Exa 公开文档返回 `OK`；本地 HTTP 读取超时后由 Exa 取得正文，`untrusted=true` |
| 运行时落盘审计 | 禁用目录新增文件 0，RAG 外部监控目录新建 / 改写文件 0，探测退出码 0 |
| 静态便携审计 | 扫描 749 个源文件；唯一环境变量命中为既有 updater 构建脚本的 Process 级恢复；无注册表或 User / Machine 级持久化写入，Electron 路径重定向保持有效 |
| 文档与差异检查 | 66 个变更文本文件 UTF-8、275 个本地 Markdown 链接 / 锚点、当前功能状态一致性及 git diff --check 通过 |

回归默认跳过的是 `WebLiveSmokeTest` 的显式联网条件，随后设置 `drill.web.smoke=true` 实际运行，并非未验证的功能。前端两项跳过是 `AiAssistant.evidence.test.tsx` 的既有 `DRILL_EVIDENCE_DIR` 条件，本次未开启证据截图输出。既有 Arco / React `act`、Vite chunk 体积、JDK / Mockito 开发告警仍存在，不影响上述结果。

## 2. 已覆盖的行为

- 普通 Chat Completions、SSE 与 Anthropic 的完整搜索 / 读取工具循环；结果进入本轮，活动与历史不保存查询、网页正文或密钥。
- 工具默认开启、执行前关闭权限、停止、总时限、输出及阶段预算；原生调用使用受理时冻结的模型、共享并发许可，并记入真实用量账本。
- Exa MCP 初始化与调用、RPC ID、JSON / SSE、实际标记文本；Tavily Search / Extract 的 keyless 与 Key 认证隔离；TinyFish GET search / POST fetch、共享密钥、逐 URL 错误和业务错误。
- 限流及 `Retry-After` 冷却；未知恢复时间不虚构；自定义或原生失败不静默替换供应商；本地与远程读取只重试缺失 URL 并保留已有页面。
- 私网 / 回环 / 元数据、IPv6 过渡范围、歧义 IPv4 字面量、混合 DNS 与重绑定、危险重定向、TLS 正常校验、响应及解压上限；安全拒绝不进入远程备用。
- 静态 HTML / Markdown 正文、导航和登录墙过滤、来源与 Unicode / UTF-8 内容上限、实际续读偏移；不把上游广告或错误文字作为正文。
- 加密配置重读、revision 冲突、空密钥保留与显式删除、同供应商凭证去重、独立搜索 / 读取选择、服务草稿取消和即存开关隔离。
- 助手地球按钮全开 / 全关 / 部分开启、一次原子保存、保存中防重复、失败回退、旧 GET 不覆盖新确认状态、旧接口缺字段时禁用；复用窗口失效消息，页面上下文排除密钥及密钥草稿。

## 3. 命令与证据

本轮使用工作区 `temp/web-search-review/pom.xml` 隔离编译实际后端源码，避免开发目录的 IDE 编译产物影响测试。隔离 POM 的依赖与 `backend/pom.xml` 逐项核对；Maven / JVM 临时路径位于工作区 `temp/`。

```powershell
pwsh -NoProfile -File temp/web-search-review/run-backend-web-tests.ps1 -TestSelection 'Ai*Test,Material*Test,Web*Test,NativeWebClientTest,PublicWebPolicyTest,LocalWebFetcherTest,Schema9*Test,SnapshotServiceTest,ToolActivityTest,ChatContext*Test'
pwsh -NoProfile -File scripts/portable-audit.ps1 -AppRoot temp -RunScript temp/web-search-review/run-live-audit.ps1
pwsh -NoProfile -File cache/portable-registry-audit.ps1
pwsh -NoProfile -File temp/web-search-review/run-backend-web-tests.ps1 -Package
pwsh -NoProfile -File temp/web-search-review/verify-isolated-build.ps1 -SyncJar
npm run build:frontend
node temp/web-search-review/check-source-and-docs.mjs
git diff --check
```

前端回归包含 `AiAssistant` 的请求 / stream / context / window / history / evidence 六套、`AiConnectionsPanel`、`App.pageContext`、`useAssistantBridge`、`useAiChatTurn`、`pageSnapshot`、`toolActivityWeb`、`WebSearchButton`、`WebSearchSettingsPanel`、`AiToolSettingsPanel`、`ToolActivityGroup` 与 `PageContextProvider`。地球按钮 8 项、服务配置 6 项通过；未屏蔽新增产品入口来使既有助手测试通过。

Surefire 证据位于 `temp/web-search-review/package-target/surefire-reports/`；运行时审计位于 `temp/portable-audit-97a2ddef-a6cc-4675-aff4-07a4fbe2eb0b/`，两类文件均不入 Git。开发包为 `backend/target/drill-notebook-backend-0.1.0.jar`，SHA-256：`5EC4AD72808D432E21F6D5CC4893AF15DAB52D93F99D8ECECABEC006A2C3105A`。

## 4. 实测边界

本次真实联网只使用公开查询、公开 URL、虚拟数据库和匿名端点。Exa 搜索 / 抓取均实际成功；本地正文提取主要由有界 HTTP / HTML 夹具验证。此前 Tavily Keyless 两次公开探测返回 `429 daily_cap_reached`，本轮默认成功路径未调用 Tavily；其降级、冷却及响应格式以协议桩验证，不宣称实际匿名额度充足。

没有使用真实 Tavily / TinyFish Key 或 OpenAI / Anthropic 原生账户，相关协议桩通过不等于真实账户权限、费用或地区可达性验收。Electron 的明暗 / 窄宽布局、键盘 / 触控、完整发行包和升级兼容验收尚未执行。单次匿名成功不能作为持续可用性、中文质量、准确度或 Token 收益保证。

应用配置只新增便携数据库内的表，HTTP / DNS 状态与冷却在内存中，不新增浏览器、Python、Docker、本地 MCP 服务或系统目录落点。本次未暂存、提交、推送、打标签或发布，也未修改版本默认值。
