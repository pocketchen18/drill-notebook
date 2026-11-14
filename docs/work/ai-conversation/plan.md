# v0.8.1 实施计划

> 2026-11-13，三项实现及工程验证完成，尚未发布。最终交互采用用户确认的 [UI/UX](ui-ux.md) 和 [规格](spec.md)。

以下保留各阶段实施记录；现行模型选择与手动重试已按 v0.8.2 修正，见[最新验证](#原地重试与会话独立选模修正2026-11-14)。

1. 已完成：去掉重复跳转与报错展开，采用粗体提问 / 浅色回答，保留轮次和时间；最多 64 个固定间距、整组居中的连续刻度，滚轮、三行回答 `...` 与悬停动态效果同步到代码和草图。
2. 已完成：全局重试设置、旧 JSON / 旧客户端兼容、上游错误分类、服务端等待建议、原轮次续答及加密历史。
3. 已完成：原回答处恢复反馈、真实计数 / 倒计时、停止、成功清除及失败 / 空回答历史恢复。
4. 已完成：轮次定位查询、跨页有界阅读窗口、整卡点击和键盘；保持草稿、附件、生成、队列及原阅读位置。
5. 已完成：前次针对性测试、前后端全量、生产构建和相关便携审计；同步版本清单、接口、QA、数据模型及入口。
6. 已完成：普通请求与流式恢复改为 400ms 起步；移除设置主模型应用，助手模型 / 档位全局持久化，导入模型独立选择。
7. 已完成：本轮相关回归、前后端构建及便携审计；同步最新规则，保留前次全量的真实范围。

## 当前进度

工程验证通过，实机观感与供应商验收尚未执行。施工材料保留在本目录，下一版本开始前再统一归档。

## 前次全量验证基线

| 检查 | 结果与范围 |
|---|---|
| 前端全量 | 95 文件：93 通过、2 既有跳过；939 项：935 通过、4 既有跳过，0 失败 |
| 后端全量 | 157 类 / 1534 项：1531 通过、3 既有跳过，0 失败、0 错误 |
| 导航 / 助手专项 | 导航 6、窗口 25、流式 20 项全部通过；全量包含最终刻度宽度、滚轮、截断、选区互斥和缩放保持所选轮次可见的断言 |
| 恢复链路 | 上游 38、恢复集成 11、策略 4 项通过；覆盖部分输出、完整工具结果、真实计数、服务端等待、停止、预算与加密空回答 |
| 构建 | TypeScript / Vite 生产构建通过；后端在 `temp/ai-conversation-check/pom.xml` 使用相同依赖及源码完整编译、封装 JAR，并同步开发 JAR 输出成功 |
| 草图 | 90 轮虚拟历史，脚本 / 样式语法和离线交互通过；含 64 连续刻度、静止 / 悬停、滚轮、键盘、设置、停止 / 重新生成及草稿保持，无网络调用或运行错误 |
| 静态便携审计 | `cache/portable-registry-audit.ps1` 扫描 705 文件，只有构建脚本合法的 Process 环境恢复；没有注册表 / 持久化环境变量写入 |
| 实际设置落盘审计 | `scripts/portable-audit.ps1` 包裹运行设置仓储 5 项复验通过；软件目录外受监控文件新增 / 变化为 0，JVM 两类临时目录均在工作区 |
| 文档与差异 | 65 个文本 UTF-8、353 个相对链接、36 个标题锚点及 `git diff --check` 通过；未忽略生成产物为 0，v0.8.0 发行说明保持接手时内容 |

旧并发派发测试原先把流中断视为立即终态；现改为显式取消后核对 ABORTED 和句柄释放，仍保留唯一外发与唯一轮次断言，11 项全部通过。没有用删除断言来掩盖自动恢复行为。

复跑入口：前端 `npm.cmd test --prefix frontend -- --silent=true` / `npm.cmd run build --prefix frontend`；后端 `temp/ai-conversation-check/run-backend.ps1`、`build-backend.ps1`；设置审计以该目录的 `run-portability.ps1` 传入 `scripts/portable-audit.ps1 -RunScript`。临时脚本、夹具与产物不入 Git。

## 刻度与悬停修正验证（2026-11-13）

针对固定间距、鼠标焦点和悬停卡顿，同步修正实际组件与草图。容量改为观察父消息区，鼠标点击不锁定焦点；首次等待后逐帧合并目标，重复轮次去重，文本截断使用短前缀及按布局条件复用的缓存。

| 检查 | 本次结果与范围 |
|---|---|
| 相关前端回归 | 导航 9、助手窗口 25、流式 20 项，共 54 项通过，0 失败；覆盖 13 轮高 76px、固定 5px 间距、鼠标只定位一次且不聚焦、首次等待不延长、逐帧合并与取消、加载中目标、迟到响应、截断缓存、缩放、跨页及草稿保留 |
| 前端构建 | TypeScript / Vite 生产构建通过；保留既有大 chunk 告警 |
| 草图 | 脚本 / 样式语法与 8 组离线交互检查通过；补验固定间距、父区缩放、指针更新、文本缓存及宽度变化失效、触屏选择取消旧悬停、全部 8 种已保存回答状态复原；无网络访问或运行错误 |

该次未重跑前后端全量、后端构建或便携落盘审计；上表之前的全量数字属于前次基线。改动不增加应用写盘路径。该次尚未进行实机观感验收，后续用户已确认悬停流畅。

## 刻度尺寸调整验证（2026-11-13）

刻度间距由 5px 调为 10px，保持整组居中，13 轮高 136px。高度上限改由 64 个刻度计算为 646px，长会话能利用更多消息区高度；矮窗口按实际空间减少数量。实际组件与草图同步，悬停合并、缓存及鼠标焦点行为沿用已通过的实现。

导航 **9 项全部通过**，覆盖固定间距、鼠标命中、悬停去重 / 取消、文本缓存及 64 ↔ 13 个刻度的缩放；TypeScript / Vite 生产构建、草图脚本 / 样式语法及 8 组离线交互复验通过。窗口 25 项、流式 20 项及前后端全量沿用前次基线，本次未重跑。用户已确认前次悬停流畅，用户随后已接受 10px 刻度尺寸。

## 400ms 退避与模型选择验证（2026-11-13）

普通请求与流式恢复以 400ms 起步，保留翻倍、约 ±10% 抖动、服务端截止时刻优先和独立断网分支。Codex 原始 200ms 基线与本版间隔分别记录。设置只选择导入模型，助手模型 / 档位即时写入现有便携数据库，旧会话与草稿不再覆盖，已受理工作保留原配置。

| 检查 | 本轮结果与范围 |
|---|---|
| 前端 | 9 文件 / 124 项通过，另 1 文件 / 2 项既有跳过；覆盖模型菜单、跨会话 / 新会话 / 重挂载恢复、保存失败与提交门禁、导入绑定 / 恢复跟随、助手窗口 / 流式 / 历史 / 上下文及运行设置 |
| 后端 | 10 类 / 115 项全部通过，0 失败、0 错误、0 跳过；策略 4、恢复 11、全局选择 11、连接 24、能力验证 16、受理快照 11、记忆工具 22、缓存 5、总结 3、结构化调用 8；重开数据源与服务后保留模型和档位 |
| 构建 | TypeScript / Vite 生产构建通过；后端以正式源码、资源及相同依赖封装，`backend/target/drill-notebook-backend-0.1.0.jar` 已同步更新 |
| 便携审计 | 静态扫描 705 文件，仅构建脚本合法的 Process 环境恢复；落盘审计包裹连接 24 项及能力 / 派发 / 记忆 49 项，均通过，软件目录外受监控新增 / 变化为 0 |
| 交付核对 | 开发 Boot JAR 内 645 个应用 class 与本轮隔离编译结果逐一 SHA-256 一致；41 个文本文件 UTF-8、12 个文档（含归档规格）的 206 个相对链接 / 20 个标题锚点及 `git diff --check` 通过，旧 QA 标题锚点继续兼容历史链接，临时核对脚本与构建产物均被 Git 忽略 |

新增测试夹具最初遗漏必填 modelId，另有旧回归仍调用设置的 chat 绑定入口；修正夹具并改用助手选择入口后，对应连接与派发回归全部通过。前后端全量、导航和草图沿用此前基线，本轮未重跑。

未执行本轮实机视觉、DPI / 多显示器、读屏、真实供应商矩阵或 GitHub Actions 发布构建。Chat Completions / Anthropic 的恢复是保留前缀后请求续答，不承诺恢复原流。本次没有 Electron / Rust 运行代码变更，不重复无关专项；既有告警和历史验收缺口保留。未提交、推送、改打包默认版本、打标签或发布。

## 会话跳转时间修正验证（2026-11-13）

数据库时间本身为真实 UTC 提问时刻，原预览直接 `new Date(createdAt)` 将无时区字符串解释为本地时间。回归测试先复现 UTC+8 下应显示 `05:54` 却显示 `21:54`；现与会话列表共用 UTC 解析，明确时区偏移保留原时刻，显示使用系统时区，缺失或不可解析时隐藏时间。

时间解析 12、跳转导航 16、会话列表 12、历史分页 15、助手窗口 26、流式会话 20 项，共 **6 文件 / 101 项通过，0 失败、0 跳过**。覆盖跨日换算、不同查看时区、UTC / 明确偏移等价、毫秒精度、无效时间隐藏和本地午夜分组；TypeScript / Vite 生产构建通过，保留既有大 chunk 告警。当前 UI/UX、规格、版本清单与 QA 已同步时间规则。

本次不新增应用写盘路径；前后端全量、草图及便携审计沿用此前基线，未重跑。未执行本轮实机视觉验收。

## v0.8.1 发布准备（2026-11-13）

补齐[发行说明](../../release/v0.8.1-release-notes.md)，云端与本地打包默认版本同步为 v0.8.1，上传仅匹配本次版本的便携 ZIP 与 SHA-256 文件。忽略软件根目录备份和复制到仓库根目录的发行产物，文档入口与打包指南同步。

发布配置 10 项及 Electron 编译通过，覆盖版本 / rc 正文、版本注入、产物完整与缺失校验文件失败；忽略规则核对通过。详细范围见[QA](../../test/QA_CHECKLIST.md)。本次未执行完整应用打包、云端运行、标签或发布；业务全量与便携审计沿用前序结果。

## 生成中上游错误重连修正（2026-11-14）

本地供应商夹具先复现：回复已开始、以及读取当前页完成后收到 SSE `upstream_error`，都会直接结束原回答。修正网关错误分类后，两种供应商协议均沿用原轮次续答；明确的鉴权、权限、参数、上下文或额度失败仍停止，等待建议不将其改为可恢复。

后端相关回归共 **9 类 / 120 项通过，0 失败、0 错误、0 跳过**。其中恢复集成 22、错误分类 16、退避策略 4 项在全新隔离目录重新编译后复验通过；其余上游解析 38、缓存 5、派发 11、流式生命周期 1、阻塞响应 1、记忆工具链 22 项在本轮通过。覆盖生成中失败、首次请求恢复后再次失败、连续流式失败、0 / 2 次上限、停止、已确认读取 / 写入不重复，以及部分回答与各次用量结算。

前端流式会话 20、助手窗口 26、恢复反馈 2、SSE 读取 12 项，共 **4 文件 / 60 项通过**。后端隔离打包通过，645 个应用 class 与本轮编译结果逐一一致，开发 JAR 已同步且 SHA-256 一致。复跑脚本和产物位于工作区 `temp/ai-stream-recovery-check/`，不入 Git。

本次只调整错误分类，不新增运行时存储路径或数据库迁移；没有修改前端、Electron 或 Rust 运行代码。前后端业务全量、便携审计与前端生产构建沿用此前记录，本轮未重跑；未执行真实供应商、Electron 实机或云端发布验收。开发运行需完全退出软件再打开以加载新后端。

## 原地重试与会话独立选模修正（2026-11-14）

按用户要求，手动重试 / 重新生成改为原轮次原地生成，各会话独立记住模型、供应商和档位，新会话继承最近选择。复用原 turn / user / assistant ID，保留轮次、提问时间与阅读位置；旧 requestId 移入回执链，较早轮次重试不纳入其后的问答。标题生成、上下文占用、直接请求和队列受理统一使用目标会话配置，迟到响应按独立模型版本处理。

| 检查 | 结果与范围 |
|---|---|
| 后端相关回归 | 26 类 / 335 项全部通过，0 失败、0 错误、0 跳过；按本次执行类的最终 XML 去重汇总，未将隔离目录里其它任务的报告计入 |
| 原地重试专项 | 4 项通过：较早轮身份 / 时间 / 导航数量与上下文截止、完整附件多次重试和旧 ID 拦截、同新 ID 幂等 / usage 累计、容量或改原提问拒绝时事务不损坏、无输出轮次边界 |
| 会话选模与标题 | 会话模型 5、标题 9 项通过；覆盖会话与供应商隔离、新会话继承、数据库重开、显式独立选择、受理快照、非法会话不改默认和手动标题使用目标模型 |
| 前端相关回归 | 9 文件 / 143 项：141 通过、2 项既有证据截图测试跳过；窗口 29 项最终复验通过。覆盖原位置更新与原提问数量、受理拒绝后再次重试、草稿保持、会话切换 / 重启 / 迟到响应与分页驻留窗口 |
| 构建与开发包 | TypeScript / Vite 生产构建、后端隔离打包通过；680 个应用 class 与本轮编译逐一 SHA-256 一致，开发 JAR 已同步；JAR SHA-256 为 `8EA19009508BE38893B56D204100B2D68802AF7C78DD2A6BDC1335E67BA056D0` |
| 便携审计 | 静态扫描 752 文件，仅已有构建脚本合法的 Process 环境恢复；落盘审计包裹模型 / 标题 / 数据库迁移 32 项通过，软件目录外受监控新增 / 变化为 0，JVM 临时目录在工作区 |

前端复跑：`npm test -- --run src/components/AiAssistant.window.test.tsx src/components/AiAssistant.stream.test.tsx src/components/AiAssistant.test.tsx src/components/AiAssistant.history.test.tsx src/components/AiAssistant.context.test.tsx src/components/AiAssistant.evidence.test.tsx src/components/ai-session/ModelPicker.test.tsx src/hooks/useChatHistoryPages.test.tsx src/hooks/useAiChatTurn.test.ts`，在 frontend 目录执行；构建为同目录 `npm run build`。

后端使用 `temp/web-search-review/run-backend-web-tests.ps1 -TestSelection`，本次三组选择分别为：

```text
SessionModelChoiceTest,AiChatSessionServiceTest,AiConnectionsServiceTest,ChatTurnServiceLifecycleTest,ChatTurnAttachmentTest,ChatTurnRepositoryTest,AiServiceTurnPathTest,WebSearchSettingsTest
ChatInPlaceRetryTest,ChatContextServiceTest,ChatContextPayloadGoldenTest,ChatContextBudgetTest,ConversationProjectionTest,ChatHistoryReadTest,ChatHistoryCleanupTest,MemoryJobRepositoryTest,MaterialCreateToolTest,MaterialUpdateToolTest,MaterialDeleteToolTest,MaterialToolChatPathTest,SessionDispatchServiceTest,AiConversationRecoveryTest
SessionModelChoiceTest,AiSessionTitleServiceTest,DatabaseInitializerTest,Schema9MigrationTest
```

第三组由 `scripts/portable-audit.ps1 -RunScript temp/run-conversation-portable-tests.ps1` 包裹执行；静态扫描使用 `cache/portable-registry-audit.ps1`。后端打包用同目录脚本的 `-Package`，再由 `verify-isolated-build.ps1 -SyncJar` 核对并同步开发包。测试报告、便携证据与汇总脚本只在根 temp/，不入 Git。

本轮未执行前后端全量、真实供应商或 Electron 实机验收；不把重新生成描述成供应商原流续传，未专门验收全部写工具跨多次手动重试的去重。已验证范围如上，前次全量保留为历史基线。当前接口、数据模型、功能清单与 UI/UX 已同步；未暂存、提交、推送、修改发布版本或工作流。开发运行需完全退出后重新打开以加载新后端。

## 上下文统计与逐请求详情（2026-11-15）

当前圆环 / 卡片顶部使用全局保留上下文占用，下方为累计输入总量、缓存命中、未命中和输出；模型名在左下与详情入口同一行。「查看上下文详情」打开「上下文」，顶部为当前会话卡片，其下提供按请求选择、分类展开与前次请求增减对比；底部本轮累计块已移除。参考 [dsh-context](https://github.com/bowenliang123/dsh-context/tree/eadff0dd29cd2312e33501ffc9f6075e0b529eae) 的组成浏览方式，本项目保留中日韩加权估算器，分项标 `≈`，实测用量独立，不复制费用仪表盘或插件代码。阈值触发压缩和统一上下文策略一起规划在 [v0.8.3](../../state/v0.8.3-features.md)；当前发送仍采用既有有界投影与输入预算。

以下为全局占用修正前的累计与逐请求详情验证基线；现行全局占用和本次复验结果见本节末。

| 检查 | 结果与范围 |
|---|---|
| 后端相关回归 | 19 类 / 213 项通过，0 失败、0 错误、0 跳过；只汇总本次执行类的最终报告，未将隔离目录里其它任务的报告计入 |
| 累计与旧库 | SessionContextUsage 12 项：多轮 / 工具 / 原生请求关联、旧重试回执去重、OpenAI / Anthropic 缓存、null / 零 / 下界、Long、301 轮及 600 条消息、清理 / 重开、schema 20 → 21 快照与保守迁移 |
| 请求组成 | RequestContextAnalyzer 7 项：最终 Chat Completions / Anthropic / Responses 请求分类、工具定义 / 参数 / 结果、图片预留、较长工具名 / 701 个结果的数量与字节上限、不保存正文以及无效数值摘要拒绝 |
| 自动与手动重试 | 原地重试 4、自动恢复 22、真实派发 11 项通过；部分回答续接及已完成工具结果在每次请求摘要中保留，实测分别累计，已受理模型快照与同会话下一轮改选分别正确 |
| 前端相关回归 | 5 文件 / 77 项通过，0 失败、0 跳过；ContextMeter、RequestContextBrowser、ChatContextInspector 及助手窗口 / 上下文，覆盖估算与实测独立、移除分类对比、分页 / 去重、旧请求不随刷新丢失、跨轮迟到正文与本轮累计优先 |
| 构建与开发包 | TypeScript / Vite 生产构建与后端隔离打包通过，688 个应用 class 与同轮编译逐一一致，开发 JAR 已同步；SHA-256 `8376F74F51AED5986DA79A74D66A051BED70942D95DEE589355B396ECBC568D0` |
| 便携审计 | 静态扫描 760 文件，仅既有构建脚本合法的 Process 环境恢复；模型 / 派发 16 项、自动恢复 22 项分别在落盘审计内通过，应用外受监控新增 / 改写为 0，JVM 临时目录在工作区 temp/ |

后端在本地隔离 POM 中用正式源码 / 同依赖重新编译 260 个生产文件和 188 个测试文件；广泛回归 186 项之后，将旧选模夹具改为显式选择当前会话，派发与会话选模 16 项、补充的自动恢复 22 项复验通过。前端分页验证先发现刷新丢掉旧请求的缺口，修正保留策略后最终 77 项与生产构建通过。

后端本地复跑用 `pwsh -NoProfile -File temp/web-search-review/run-backend-web-tests.ps1 -TestSelection`，本次选择为：

```text
SessionContextUsageTest,RequestContextAnalyzerTest,ChatInPlaceRetryTest,ChatHistoryReadTest,ChatTurnServiceLifecycleTest,TokenEstimatorTest,AiUpstreamClientTest,AiUpstreamClientCacheTest,ChatContextBudgetTest,ChatContextPayloadGoldenTest,ChatContextServiceTest,WebToolChatPathTest,NativeWebClientTest,DatabaseInitializerTest,ChatUpstreamDispatchTest,AiRetryPolicyTest,SessionModelChoiceTest,AiConversationRecoveryTest
```

前端在 frontend 目录运行 `npm test -- --run src/components/ai-memory/RequestContextBrowser.test.tsx src/components/ai-memory/ChatContextInspector.test.tsx src/components/ai-session/ContextMeter.test.tsx src/components/AiAssistant.window.test.tsx src/components/AiAssistant.context.test.tsx` 和 `npm run build`。落盘审计分别包裹 `temp/context-usage-review/run-context-focused.ps1` 与 `run-context-recovery.ps1`；静态扫描用 `cache/portable-registry-audit.ps1`。打包 / 逐 class 校验继续使用上述隔离构建脚本的 `-Package` 和 `verify-isolated-build.ps1 -SyncJar`。临时脚本、报告与参考材料仅在根 temp/，不入 Git。

旧请求未保存的组成明细不可恢复，旧缓存口径无法核实时仍为未知；分项估算不能当作精确账单。真实供应商 Token 对账、Electron 实机、前后端全量及完整发行验收本轮未执行。用户数据库未读取或修改，数据库验证使用工作区隔离夹具；开发 JAR 已同步，完全退出软件再打开即可加载。当前功能清单、UI/UX、接口、数据模型与 QA 已同步，未暂存、提交、推送或改发布配置。

### 全局占用与卡片布局修正（2026-11-15）

`SessionContextOccupancyService` 逐行读取当前代未清理的全部正文与 RECEIVED 插话，不受历史驻留或单请求预算限制。有效摘要按 coverage 替换旧原文，最近两轮与缺口保留；固定前缀、当前轮额外组成及尚未落库的回复各计一份。新加密元数据保存版本化数值，旧密文按需解密、只缓存数字；当前占用无法完整估算时保留下界。发送仍使用原有投影 / 输入预算，v0.8.3 再加入按设置阈值触发的压缩。

| 检查 | 本次结果 |
|---|---|
| 后端相关回归 | 14 类 / 169 项通过，0 失败、0 错误、0 跳过；采用所选类最终报告并去重，未使用隔离目录其它旧报告的总数 |
| 全局与额外组成 | 占用 8、额外组成 4 项：600 条 / 300 轮、超窗仍保留数值、清理 / 换代、旧回答原地替换、实时输出与终态去重、旧数据下界、RECEIVED 插话、原生子请求不顶替主工作集、图片固定预留 |
| 加密与估算 | 加密 8、估算器 7 项：数值元数据锁定可读、旧加密数值缓存、无效 / 未来口径保持未知、Long 全局加法、分帧余数与跨帧 emoji |
| 既有路径回归 | 累计 13、投影 20、自动恢复 23、原地重试 4、真实派发 11、人工队列 / 插话 19、请求组成 7、生命周期 27、附件 13、容量 5 项通过；摘要缺口 / 最近两轮和旧检查点只读边界、计费及容量事务保持 |
| 前端相关回归 | 5 文件 / 80 项通过；圆环与累计 / 最新请求独立、关闭卡片仍更新、窗口变化与超限、会话迟到响应、输入总量和模型位置、请求详情 / 分页及助手窗口回归 |
| 构建与开发包 | TypeScript / Vite 构建与后端隔离打包通过；695 个应用 class 与编译逐一一致，开发 JAR 已同步，SHA-256 `52EC17B8C0B6A1C5607737B48806CEC0FE0A1401C94E4B91EAEBEB0823600BD2`；124 个前端输出文件与已验证构建逐一同哈希，已同步 frontend/dist 启动目录 |
| 便携审计 | 全局占用 / 投影 / 生命周期 / 附件 / 容量 73 项在落盘审计内通过，应用外受监控新增 / 改写为 0；静态扫描 764 个文件，仅既有合法 Process 环境恢复 |

后端复跑仍使用 `temp/web-search-review/run-backend-web-tests.ps1 -TestSelection`，本次去重选择如下：

```text
SessionContextOccupancyTest,SessionContextExtrasTest,TokenEstimatorTest,ChatMessageCipherTest,SessionContextUsageTest,ConversationProjectionTest,AiConversationRecoveryTest,ChatInPlaceRetryTest,ChatUpstreamDispatchTest,SessionDispatchServiceTest,RequestContextAnalyzerTest,ChatTurnServiceLifecycleTest,ChatTurnAttachmentTest,ChatStorageCapacityTest
```

前端在 frontend 目录使用前述五个文件的测试命令；生产构建为 `npm run build -- --outDir ../temp/context-occupancy-review/frontend-build`，再由同目录 `sync-context-frontend.ps1` 按文件哈希同步到启动目录。落盘审计用 `scripts/portable-audit.ps1 -AppRoot temp/context-occupancy-review -RunScript temp/context-occupancy-review/run-context-portable.ps1`，本次选择报告由同目录 `report-context-tests.ps1` 汇总。打包与逐 class 同步沿用 `-Package` / `verify-isolated-build.ps1 -SyncJar`；UTF-8 与文档链接校验脚本也仅保留在根 temp/。

当前占用是本地估算，累计用量继续使用供应商回报；旧内容无法读取时为已知下界。用户数据库未读取或修改，真实供应商 Token 对账、Electron 实机、前后端全量及发行验收未执行。功能清单、UI/UX、规格、接口、数据模型和 QA 已同步；当前仍为 v0.8.2，压缩仅规划在 v0.8.3，未暂存、提交、推送或改发布配置。开发运行需完全退出软件再打开以加载新后端。

### 当前卡片与名称调整（2026-11-15）

按用户确认的设计，标题统一为「上下文」、入口为「查看上下文详情」；移除原底部「本轮累计用量」，在上下文浏览器上方加入当前会话卡片。占用总量、分类及流式快照来自同一次计数，当前卡片与圆环共享会话查询，不随历史请求选择变化。未保存有效分类的旧额外内容归入 OTHER，累计用量继续在输入区小卡片与现有账本保留。

pi、Codex、OpenCode 和 Claude Code 的调查结果、当前发送 / 占用差异及统一方向已写入 [v0.8.3 规划](../../state/v0.8.3-features.md)，与阈值压缩一起实现。本次不改外发历史预算或压缩策略。

| 检查 | 本次结果 |
|---|---|
| 后端回归 | 14 类 / 172 项全部通过，0 失败、0 错误、0 跳过；从新建隔离目录编译正式源码和同版本依赖。新增当前分类与历史实测独立、旧分类无效回退、流式 / 终态分类一致，保留累计、摘要、恢复、原地重试和容量回归 |
| 前端回归 | 7 文件 / 96 项全部通过；覆盖当前卡片与圆环共享、历史账本缺失仍显示、上下顺序、名称与累计块移除、空值 / 下界 / 超窗、会话迟到响应及现有请求浏览器 / 助手交互 |
| 构建与同步 | TypeScript / Vite、后端隔离打包通过；124 个前端文件逐一核对哈希并同步，697 个应用 class 与隔离编译一致，开发 JAR 已同步，SHA-256 为 `E10D7A7271C291140B6570EF2C6FFB18C0B4C391917F97B927B7F252DEE307C4` |
| 便携审计 | 数值元数据 / 占用 / 生命周期 / 容量 51 项在落盘审计内通过；外部受监控新增 / 改写为 0。静态扫描 767 个文件，仅既有合法 Process 环境恢复，无新增系统目录、注册表或持久化环境变量写入 |

本次后端复跑：`pwsh -NoProfile -File temp/context-inspector-20261115/run-validation.ps1`；打包追加 `-Package`，同步用同目录 `sync-artifacts.ps1 -Backend` / `-Frontend`。前端从根目录执行 `npm --workspace frontend run test -- src/components/ai-memory/CurrentContextCard.test.tsx src/components/ai-memory/ChatContextInspector.test.tsx src/components/ai-memory/RequestContextBrowser.test.tsx src/components/ai-memory/MemoryUsageBadge.test.tsx src/components/ai-session/ContextMeter.test.tsx src/components/AiAssistant.context.test.tsx src/components/AiAssistant.window.test.tsx`，构建输出在新建根 temp/ 下。落盘审计使用 `scripts/portable-audit.ps1 -AppRoot temp/context-inspector-20261115 -RunScript temp/context-inspector-20261115/run-portable.ps1`，静态扫描沿用 `cache/portable-registry-audit.ps1`。

当前清单、接口、数据模型、规格、UI/UX 和 QA 已同步，v0.8.3 仍为规划。真实供应商 Token 对账、浏览器视觉 / Electron 实机和完整发行验收未执行；没有修改用户数据库、暂存、提交或发布。
