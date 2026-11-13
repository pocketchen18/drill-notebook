# Drill Notebook 架构说明

当前仓库实现层面的架构边界；若与代码冲突，以代码为准。

## 1. 进程与通信

| 进程 | 技术 | 职责 |
|---|---|---|
| Electron Main | Node | 窗口、菜单、便携路径、单实例、spawn Java、生命周期、唯一更新状态与保存 / 退出协调 |
| Renderer | React 18 + TS + Vite + Arco | 全部 UI；通过 `window.api` 与主进程交互；HTTP 访问后端 |
| Backend | Spring Boot + SQLite | 领域 API：题库、练习会话、导入导出辅助、计划、SRS、AI 代理与会话 |
| Portable Updater | Rust + Win32 | 仅更新事务期间运行，等待应用退出、备份 / 替换程序和停机数据库、重启与恢复 |

- 后端绑定 **`127.0.0.1`**，端口由启动参数/协商文件决定（见 `electron/java-bridge.ts`）。  
- 渲染进程 **不直连** 用户 AI 厂商密钥所在的业务逻辑：由后端代发。  
- 单实例锁在 `setupPortablePaths()` 重定向 `userData` 后、`app.whenReady()` / Java 启动前申请，范围为同一便携数据目录。未获锁的进程直接退出，不注册共享临时目录的退出清理。
- 主实例收到 `second-instance` 时恢复最小化 / 隐藏窗口并聚焦；后端尚未启动完成时只记录唤起请求，窗口创建后兑现，不重复启动后端。锁随主进程退出释放，不提前释放后再清理资源。

## 2. 前端分层

```
pages/          路由页面（题库、刷题、背题、知识点、错题、日历、笔记、设置）
pages/knowledge/  知识点 v0.5 重构子目录：KnowledgeLibraryView（库总览）/ KnowledgeItemCard（卡片）/ KnowledgeFullCardView（单卡全屏）
components/     复用 UI（AI 侧栏、导出、高级选题、编辑器、计划弹窗、AiSummaryModal、TodayQueuePanel、DayQueueSessionBar…）
components/editor/  TipTap 编辑器扩展：MathBlock / MermaidBlock / MarkdownBlock / QuestionBlock / **FileBlock（附件预览）/ VideoBlock（视频嵌入三视图）** + `preview/`（ImagePreview / DocxPreview / PdfPreview / DownloadOnlyPreview）
stores/         Zustand（主题、AI 开关、页面上下文、复习会话、笔记本专注/全屏模式等）
lib/            API 客户端、导入导出、复习/计划算法、dayQueueSession、knowledgeApi、mermaidTheme、planProgress、sessionPrefs、**viewState（界面状态记忆）**、**attachments（上传/列表/删除）、videoEmbed（YouTube/Bilibili URL 解析）**、工具
hooks/          如 useRegisterPageContext（避免 setState 死循环）、useReviewSession、**useViewState（切片读取 / 去重写入 / 切库不清空）**
styles/app.css  设计令牌（明暗同源）+ Arco 变量桥接 + 壳层 / 页面 / 编辑器 / AI 侧栏样式 + 工作台几何契约（DESIGN.md）
components/BrandMark.tsx  品牌标识（与 resources/icon/icon.svg、public/favicon.svg 同一几何；`npm run build:icons` 生成 icon.png / icon.ico）
```

### 2.1 主题

1. `uiStore.theme`: `'light' | 'dark'`  
2. `html[data-theme]`：CSS 变量（`--text`、`--panel-bg`…）  
3. `body[arco-theme=dark]`：Arco 组件库暗色  
4. `localStorage['drill-notebook-theme']`：首屏兜底  
5. Electron `config`：跨会话持久化  
6. Arco 变量桥接：`app.css` 晚于 `arco.css` 加载，在 `body` / `body[arco-theme=dark]` 上覆盖 `--arcoblue-*`、圆角、文本 / 边框 / 填充变量，Arco 组件与自有令牌同源（v0.6）

Drawer/Modal 等 Portal 挂到 `#root` 或依赖 `html` 级变量 + `arco-theme`，避免「侧栏不跟主题」。

### 2.2 AI 页面上下文

学习页通过 `useRegisterPageContext` 注册业务内容、目标 ID、选区与草稿状态，仅内容变化时写入 store。离开页面清理旧内容，隐藏的练习标签停止注册；答题 / 背诵快照只含已显示内容。发送前由 `capturePageSnapshot` 克隆并限长，直接发送、队列与插话分别绑定提交时的页面，不因后续切页更换目标。

设置与日历自 v0.8.1 接入同一登记机制：`PageContextProvider` 按页面定位组合当前面板的 `usePageContextSection`，只接受各组件挑选的展示字段，不扫描 DOM 或读取输入框。切换分区或日期后按范围隔离面板，旧面板的清理不能覆盖新登记内容。设置提供当前分区的选项和状态，密钥 / 密码只提供配置状态，连接地址和请求头不进入快照；记忆管理仅登记状态、筛选和数量。日历登记月份、日期、计划和月历统计，`TodayQueuePanel` 补入当前筛选的队列。两页分别使用 `settings` / `calendar` 类型，仍只向模型提供定位，正文经已有读取工具按需取得。

v0.7.3 前端已移除顶部上下文卡片与右下角悬浮入口，保留右上角、快捷键和业务定向入口。不再发送自动注入用的 `pageContext` / `retrievalOptions` 或读取旧检索偏好。快照提交到本地后端后只向模型提供定位，正文由 `read_current_page` 按需读取；无工具能力时不恢复整页注入。后端保留旧客户端协议兼容。

AI 助手自 v0.8 起是独立系统窗口：主进程维护唯一的助手窗口（同一前端入口加 `#/assistant`），关闭只收起，随主窗口退出；窗口位置、尺寸与「固定」保存在 `APP_ROOT/config/assistant-window.json`。主窗口入口是切换：助手在前台时收起，否则切到前台。两个渲染进程各有查询缓存，只经主进程中转约定的消息（当前页面、资料定位、已确认的资料写入、查询失效、运行计数），`lib/windowBus` 与 `hooks/useAssistantBridge` 封装两侧逻辑；「当前页面」始终来自主窗口。

会话导航由 `components/ai-session/SessionNavigation` 维护停靠在助手窗口会话栏的列表（另保留浮动列表形态）、固定目标 ID 的菜单、重命名草稿和确认；`AiAssistant` 负责缓存与聊天切换。复制/导出子菜单独立 portal，窄视口点击进入返回式布局，避免悬停替换导致误触。标题生成由后端 `AiSessionTitleService` 有界后台执行；会话列表每 1.5 秒刷新以发现后台新会话和标题变化，自动标题不进入聊天正文或工具活动，也不使用聊天运行圆环。

跨会话查阅由 `SessionListTool/SessionReadTool` → `SessionLookupService` → `SessionLookupRepository` 完成，直接查询现有 SQLite 并按目标会话材料解密，不依赖记忆索引。列表按标题筛选/ID 分页；正文以版本绑定游标分段，复核删除/换代。`AiService.ChatTool` 统一运行五个记忆工具、五个会话工具、六个资料读取工具及资料新增 / 修改 / 删除 / 错题移出四个工具，`MemoryTool` 子接口仍受记忆开关控制。全部 20 个工具受独立全局开关管理，非删除默认开启，四个删除默认关闭且不提供给派发任务。每轮提供工具前读取配置，执行前复核；存储异常暂时关闭工具，不能重启被用户关闭的能力。设置页按学习资料 / 记忆 / 会话 / 删除分组，即点即存并可恢复默认。回复中的本地链接由 Markdown 渲染器严格识别，`SessionReferencePreview` 复用历史分页做只读预览，保持主聊天与草稿；资料链接导航至普通业务页面。

`useAiChatTurn` 按 sessionId 跟踪请求，每 900ms 查询运行快照；SSE 负责当前本地发送的增量，快照恢复切换、外部工具启动和断线后的正文。每个会话独立保存本次前端生命周期内的草稿、附件与阅读位置。忙碌时普通发送进入队列，`useChatQueue` 与 `ChatQueue` 管理贴边待发区、编辑、移除撤销、暂停继续和插话；输入草稿与待发项隔离。运行圆环、暂停、待发送数量和停止均按目标会话展示，临时失联显示重连状态。

新会话页作为无 ID 的前端草稿存在，点击新建或删除当前会话只切换到该页。首次发送才显式创建会话，随后发送同一份输入；同步防重复与渲染后发送保证快速流式事件不会丢失。列表允许为空，初始化和只读查询不补建默认会话；迁移仅为已有未归属的旧消息建立会话。

### 2.3 界面状态记忆（v0.5.2）

`lib/viewState.ts` 用**单个** localStorage key `ui.viewState.v1` 保存「上次页面 + 各页视图状态」：

- **读**：`readViewState()` / `readPageSlice(page)` / `readLastRoute()`，先与存储原文比对再解析（模块内缓存，7 个页面共用一次 `JSON.parse`），并把未落盘的待写入补丁叠加上去，因此同一渲染内读到的永远是最新值。  
- **写**：`persistViewState(page, partial)` 按页浅合并 + JSON 去重 + 300ms 尾部防抖；`recordRoute(pathname)` 只接受侧栏白名单路径，并把 `/quiz`、`/memorize` 折叠为 `/practice`；`pagehide` / `beforeunload` 调 `flushViewState()` 兜底。  
- **容错**：`normalizeViewState` 永不抛错——损坏 JSON、未知页名、非正整数 id、越界枚举一律丢弃；勾选列表按 `IdSet`/`KeySet` 编码（整库勾选 → `all` 哨兵），上限 500 id / 200 key / 4 个作用域。作用域图另存 `recent`（旧→新，封顶 4 条）：数字型 key 在对象里只会按升序枚举，光靠键序表达不了「最近用过」，所以淘汰时按 `recent` 从旧到新出局、且永不淘汰正在写入的那个库。超预算先截断后放弃写入。  
- **硬约束**：**绝不**持久化密码（如刷题页的题库主密码）、笔记脏缓冲、`answerStates`、SM-2 会话内去重表与任何「进行中的会话」状态；进行中的日队列会话仍留在 sessionStorage（`drill.dayQueueSession`）。  
- **写入时序**：勾选类面板（题库、刷题、背题、背知识点）在本库记忆**尚未套用前不得写回**，否则切库瞬间的空值会把用户记下的选择静默覆盖；而这个「已套用」标记**必须是 state 而不是渲染期读的 ref**——套用结果与当前值相同时 React 会跳过重渲染，ref 门闩会让写入器再也不运行（背知识点切库记不下来就是这么来的）。日历跨零点跟随「今天」时也要判断当前视图年月是否就是旧的今天所在月——停在记忆里翻到的上月等同「用户在看别处」，只刷数据不跳视图。  
- **开关**：`sessionPrefs` 的 `ui.rememberViewState`（默认开）；关闭时读写全部 no-op，且设置页会立即 `clearViewState()`。  
- **落点**：`App.tsx` 的 `/` 与 `*` 重定向到 `readLastRoute() ?? '/notebooks'`；各页遵循「URL/深链 > 记忆 > 默认首项」的优先级，删除实体后由各页既有的剪枝 effect 回落。

### 2.4 笔记编辑器稳定性（v0.6.3）

仍使用 TipTap 2.27，交互契约见 [DESIGN.md](../DESIGN.md) §5，回归映射见 [QA 清单](../test/QA_CHECKLIST.md)模块十二。

- `blockCommands.ts` 统一工具栏、块菜单与快捷键转换，事务内摊平公式并映射选区；`CodeBlockView.tsx` 的 `mathSpans` 只进入 JSON。单块来源完全匹配才恢复，多块只保留源码，手打 `$…$` 不猜测转换。
- `extensions.ts` 的公式 `renderText` 同时供 TipTap 默认剪贴板与 `getText()` 使用；状态栏复用 schema 序列化器，去空白并按 Unicode 码点计数。无需额外 `MathLeafText` 插件，软换行保持换行。
- `MathNode.tsx` 管理编辑 / 预览、草稿与退出防重入；`EditorChrome.focusFieldSoon` 仅在主动交接时定位源码末尾。块公式浮窗通过事务元数据请求编辑，不修改文档；提交恢复正文焦点，取消恢复预览或行内公式后的光标，blur 不抢焦点。
- `fileDropTarget.ts` 捕获上传前落点并跟随文档事务映射，`literalSearchQuery.ts` 保证替换文字的美元符号不被解释。
- 文件拖放先区分内部拖动与外部上传：`NotebookEditor` 的 `moved` / `view.dragging` 优先于文件载荷，内部移动与 Ctrl 复制都交回 ProseMirror、不重复上传。`NodeViewDragGuard` 在编辑器 DOM 的捕获阶段阻止不可编辑预览本体拖动，避免被 TipTap `stopEvent` 截断；销毁时移除监听，不影响外侧手柄或正文选区拖动。
- `NotebookEditor` 忽略自己发出的草稿回声；`NotebookPage` 先填充新页列表 / 详情缓存再切选中页，`NoteAutosave` 按 pageId 管理保存，避免旧列表回跳与跨页串写。

## 3. 后端领域（概念）

| 域 | 内容 |
|---|---|
| 题库 | bank / question / FTS / 导入哈希 |
| 练习 | quiz session、answer_record、错题查询 |
| 笔记 | notebook / note_page / 题目快照块 |
| 附件 | `note_attachment`：上传/列表/内容/删除 API，SHA-256 去重，存储于 `data/attachments/` |
| 计划 | 日历计划组与条目（题目/知识点/笔记） |
| 复习 | spaced repetition 配置、enrollment、schedule、log |
| AI | 多供应商与模型、助手全局模型选择与导入绑定、全局运行限制、多会话与加密历史、chat/summarize 代理、长期记忆与工具活动 |
| 知识点总结 | `knowledge_point_original` 双角色快照（original/summary）；`KnowledgePointSummaryService` + `AiService.summarizeKnowledgePoint` / `summarizeMarkdown`（v0.5） |

当前 schema 18，完整 DDL 以 `backend/src/main/resources/schema.sql` 为准；表与迁移边界见 [data-model.md](data-model.md)。

### 3.1 AI 连接、轮次与工具（v0.7.1）

- `AiConnectionsService` 管理供应商/模型集合与使用绑定，密钥加密存储；模型内部 ID 稳定，编辑上游模型 ID 不改变绑定。全局预算独立存储；一轮开始时解析配置并冻结快照，后续保存只影响下一轮。
- 模型上下文窗口和最大输出为必填正整数；`AiModelLimits` 统一约束保存、应用、探测与实际请求。旧缺值配置提示补全，不再使用缺省窗口；最大输入字段已取消。输入与输出共享窗口，正常安全余量和全局运行限制仍生效。
- 模型发现与验证使用后端代理；最多三个模型并发验证，各模型的文本、图片与工具请求分别记录。文本决定是否加入，工具验证成功才自动开启对应能力。配置版本用于避免旧验证覆盖后续编辑。
- 对话工具循环按阶段、执行次数、输出额度、单请求/整轮期限和重复无进展共同约束；达限停止新工具并尽量总结。上游请求可取消，SSE 有心跳；正文与活动独立传递，「继续」建立新一轮。
- 活动按 `requestId` 与轮内 `sequence` 关联，服务端只发白名单摘要，不暴露上游调用 ID；原始参数与工具结果不入 SSE、日志或历史。安全活动元数据随助手消息加密持久化，检查点不推进 `history_revision`，避免使本轮冻结计划失效。
- `AiToolSettingsRepository` 持久化全局删除开关；`AiService` 在轮次开始过滤，在执行前重新读取防止已关闭项继续运行。工具派发通过 `SessionExecution.delegated` 禁用删除能力。`AiToolSettingsPanel` 提供「工具」Tab 的即存开关、失败回退与恢复默认，其他工具和搜索设置尚未开放。
- 前端连接页使用供应商/模型列表与二级编辑页；`ToolActivityGroup` 展示、折叠活动，保留用户手动选择和滚动位置。失败轮次提示归属助手回复；正文流式更新不强制打断向上阅读。
- 记忆词法索引以 vault、epoch、revision 判定是否可复用，同版本构建合并、解锁时异步预热；聊天模型不参与索引身份。实际记忆变更或重启后的预热仍可能需要时间，不承诺零延迟。
- 删除来源会话/历史只清理来源证据，长期记忆及原 origin 保留；删除笔记本仍清理所属 NOTEBOOK scope。接口与安全边界见 [api-contracts.md](api-contracts.md)。

### 3.2 后台会话派发（v0.7.2）

- v2 聊天由 `SessionDispatchService` 持有执行任务，浏览器 SSE 只订阅；切页、收起侧栏、刷新和断开订阅不取消任务。停止必须显式调用，并核对当前 requestId，避免迟到请求误伤新轮。
- `SessionCreateTool` / `SessionSendTool` 先受理后异步运行，正常会话无创建者标签。同会话 FIFO、跨会话并行；当前配置在受理时冻结，开始执行时获取最新目标历史，复用现有加密、用量、标题与轮次结算。
- 进程最多持有 64 项任务，执行线程与 8 个上游请求许可分开；`read_session` 的有界等待不持有上游许可。同一派发链共享时间、输出与工具预算，输出预留和阶段/执行计数原子更新，禁止向自身或祖先会话派发。
- 工具派发在 SQLite 保存幂等收据，待发正文及配置只在内存；人工队列另以 `ChatPendingRepository` 加密保存请求体、配置引用及暂停状态。失败/停止取消工具待发，保留人工待发并暂停，其他会话独立继续。重启只恢复未执行人工内容为暂停状态，不重放已开始或交付未知的内容。
- 插话经 `SessionExecution` 在下一次模型请求边界领取，不中止当前输出或工具，沿用本轮预算。最终响应结束时也检查已经受理的补充；送达未知不自动重试。接收后的独立用户补充由 `ChatSupplement` 参与历史、上下文、跨会话查阅和导出，按正文偏移保留原回复顺序。

### 3.3 学习资料读取、新增与修改（v0.7.3）

- `MaterialReadTools` 注册六个独立 `ChatTool`，由 `MaterialReadService` 处理严格参数、资料归属、分页与正文版本；`MaterialReadRepository` 对现有业务表作有界只读查询。笔记关键词复用 `retrieval_chunk` 文本进行 LIKE 匹配，不新增表、索引或语义检索。
- `MaterialCreateTool` 由 `MaterialCreateService` 处理六种新增类型。复用普通仓储与 `NoteIndexingService`；预占回执取得 SQLite 写锁后校验来源轮次，业务写入、索引和回执同事务提交。重试复用结果，参数改变拒绝，取消或错误回滚；保存结果未知不假称失败。
- `MaterialUpdateTool` → `MaterialUpdateService` 在写事务中核对 `MaterialVersion` 完整业务摘要，复用仓储更新及独立修改回执。部分字段更新不清空其余字段，题目只写提供的列；重复调用和显式重试不覆盖后续编辑。`MaterialNoteEdits` 原位精确替换文本或追加 Markdown，保留富文本和附件节点，不做整页转换；知识点保留原文 / 总结，错题修改原题但不动统计。
- `MaterialDeleteService` 承接资料删除与错题移出，复用普通级联和同事务回执；非空容器需显式 cascade，当前会话绑定的笔记本不可删。`MemoryDeleteTool` 复用遗忘抑制与纪元推进，自己的已确认变更可更新计划，额外并发变更仍会阻止旧计划继续。`SessionDeleteTool` 在同一写事务内核对真实来源轮次与目标状态，保留长期记忆并清理来源证据。
- `toolMutationEffects` 从本次运行的安全活动刷新目录，笔记删除先通过 `materialEvents` 同步通知 `NotebookPage` 丢弃草稿，再清除缓存。`NoteAutosave` 忽略已删页面的迟到结果和回声。历史活动不重放删除；已删对象不显示打开入口，错题移出仍可查看原题。
- 人工错题仅记录真实作答基线，普通错题页与读取工具共享成员 SQL，不生成虚假答题。前端成功活动刷新相关资料、提供「查看资料」。笔记正文刷新由 `NoteAutosave` 的待保存、在途和失败状态保护，普通自动保存与后台 keepalive 都跟踪到结果，并及时刷新提交快照的 isDraft，避免已保存内容一直被误判为草稿。
- `MaterialText` 将指定笔记页的 TipTap 数据转为可读正文，保留公式及嵌入题目 / 文件引用，不改变既有 `NoteNormalizer` 的索引规则。知识点当前、原文、总结分别读取；错题成员 SQL 与人工页面共用，统计来自真实答题记录。
- `PageSnapshot` 校验消息级快照、生成指纹与无正文定位提示。`AiService` 在本轮内存中按 requestId 保存主消息与插话快照，只在调用工具时提供正文。队列复用既有加密载荷，元数据只记录指纹；工具原始资料结果仍不落历史，仅安全活动摘要随既有加密元数据保存。
- 目录最多 20 条，正文最多 8000 Unicode 码点，续读校验版本；删除、缺失和不可读明确返回。来源链接只支持六种本地资料类型与正整数 ID；点击题目时通过单题 GET 取得所属题库，不注册系统协议。契约见 [api-contracts.md §3.2.4](api-contracts.md)。

### 3.3 失败恢复、会话内定位与模型选择（v0.8.1）

- `AiConnectionsStore.State` 在现有便携数据库 JSON 中保存助手全局模型与档位；`AiConnectionsService.selectChatModel` 使用 revision 和原记忆授权事务，配置编辑与导入绑定保留选择。助手只读数据库选择，旧会话 / 草稿选择不参与派发；受理时冻结模型，后续改选不影响既有任务。
- `AiRetrySettings` 嵌入全局 `AiRuntimeSettings`，沿用 `ai_runtime_settings` 的 JSON 与 revision；旧文档缺项补默认，旧客户端保存保留已有重试偏好。配置随受理时快照冻结，不新增 schema 或模型专属预算。
- `AiUpstreamException` 统一 HTTP / SSE / 传输错误及脱敏正文、服务端等待截止时刻；`AiRetryPolicy` 分别管理请求、流式和纯连接失败计数；普通请求和流式恢复从 400ms 起翻倍并抖动，断网保留独立的 5 秒起步策略。`AiService` 在原轮次内等待 / 重发，保持原模型、整轮预算、部分回答与已确认工具上下文；未完整终止的工具调用不执行，交付未知插话不重放。两类协议的断流恢复通过同模型请求续答完成，没有原流续传协议。
- `AiRecovery` 作为安全状态在 metadata、终态、轮次 / runtime 查询和加密历史之间传递。`RecoveryFeedback` 只在原助手回答处显示真实次数、一次报错和倒计时；停止 / 失败保留事实，成功清除。零正文的恢复失败也保存加密空助手消息，刷新不丢原因。
- `ChatHistoryRepository` 对已有用户消息计数并查询轮次对应 ID；`ChatHistoryService` 提供有界预览及 history 的 totalRounds / firstRound / hasOlder。`useChatHistoryPages` 按目标加载阅读窗口，50 条一页、最多 500 条驻留，回到最新恢复最新窗口；请求身份和阅读锚点保护会话切换、定稿与迟到响应。
- `ConversationNavigation` 使用正文右侧单一键盘定位控件，最多 64 个连续刻度，矮窗按高度减少；非 passive 滚轮翻阅窗口并阻止正文滚动。静止短线、悬停邻近刻度渐长；整卡显示轮次 / 时间、两行粗体提问、三行浅色回答及 `...`。预览只读取已有消息，文字复制 / 引用或覆盖详情期间隐藏导航。

本片的工程验证与尚未执行的实机 / 供应商验收见[实施计划](../work/ai-conversation/plan.md)和[测试清单](../test/QA_CHECKLIST.md)，接口见[api-contracts.md](api-contracts.md)。

## 4. 导入 / 导出

- **导入**：Markdown 解析器、PDF 服务（规则 + AI 兜底）、JSON 等，见 `docs/reference/import-formats.md`；知识点 Markdown 导入见 `docs/reference/knowledge-point-import.md`。  
- **导出**：前端 `lib/export.ts` + 页面 `ExportActions`；题库/笔记/错题/会话等。  
- **导出格式 round-trip**（v0.1.0+）：题库导出的叙述式 `.md`（`### 颜干` + `**答案：**` + `---` 分隔）可再走「导入 Markdown」入口导回，由 `ExportMarkdownParser` 解析，无需手动改成 frontmatter 格式。详见 `docs/reference/import-formats.md` §3。  
- **AI 总结知识点**（v0.5）：双角色快照 + 三条总结路径，见 `docs/reference/ai-summary.md`。

## 5. 便携与打包

- 开发：未打包时默认在工作区旁建立便携数据根（`APP_ROOT`，不入库）。  
- 生产：`electron/paths.ts` 将 userData/cache/temp 指到可执行文件旁。  
- 发行包由 `.github/workflows/release-portable.yml` 构建发布，本地 `package-portable.ps1` 可生成不发布的验证包。前后端业务全量测试作为本地发布前门禁；云端检查版本、发布说明和发布配置，进行类型检查、编译并生成内嵌 JRE、更新 helper、应用目录、程序清单、ZIP 与 SHA-256。后端使用 `-DskipTests package`，继续使用 Pre-release。操作见 [README §4.3](../../README.md#43-云端便携发布)，JRE 与更新材料见 [jlink.md](../jlink.md)。

### 5.1 软件内更新（v0.8.0，已实现）

主进程 `AppUpdater` 唯一管理偏好、公开 Releases、检查 / 提醒时间、跳过版和更新事务；renderer 通过受信 `window.api.update` 观察状态与发起操作，不直接下载或执行程序。关于提供实际版本、GitHub 主页、只读更新内容和 Star；新版说明与安装进度有各自的展示身份，关闭不取消事务。产品规则见[需求](../archive/v0.8.0/app-update/requirements.md)，实际协议见[规格](../archive/v0.8.0/app-update/spec.md)。

公开 GitHub Releases 按语义版本排序，兼容 v0.x 预发行；匹配 Windows x64 目录 ZIP，校验公开源、大小 / SHA-256、程序清单与逐文件摘要。更新文件使用 Electron `original-fs`，避免 `.asar` 虚拟目录语义；只允许程序路径，用户数据和配置不在清单内。

偏好及失败目标在 `config/`，Release / ZIP 在 `cache/updates/`，暂存、helper 和事务在 `runtime/updater/`，程序 / 停机数据库备份在 `backups/updates/`，全部从 PortablePaths.root 派生。助手未发送草稿用目录内密钥加密持久化，可随便携目录迁移；读取失败不覆盖现有草稿。缓存与完成事务按所有权标记清理，保留最近两份备份。

替换前两窗口分别排空笔记保存、助手草稿和界面状态，并检查业务编辑 / AI 任务。后端 `PortableUpdateRequestGate` 将 HTTP 与后台写任务共同计数；最后退出检查关闭新写入及派发，正常关闭 Spring context。更新路径不强杀业务任务，忙碌 / 保存失败时等待并继续处理。

Rust helper 验证事务和 Win32 进程身份，等待 Electron 与 JVM 都退出后备份，完整恢复基线持久化后才替换并重启。新主进程确认实际版本、后端健康和受信主界面就绪，再恢复业务页面；提前请求后端地址会等待启动完成。健康确认是提交点；失败或中断只在相关进程已退出、备份完整时恢复旧程序与停机数据库，保留失败数据副本，阻止同目标自动循环。确认健康后的用户编辑不能被恢复流程覆盖。

真实应用的手动更新、启动自动更新、真实 JAR 启动失败恢复及便携审计已通过；完整范围见[验证记录](../archive/v0.8.0/app-update/plan.md)。QA driver 仅注入隔离测试 ASAR，生产包不包含它；该轮验证没有发布 Release。

## 6. 测试

- 前端：Vitest（`frontend` 内 `*.test.ts` / `*.test.tsx`，如 `AiSummaryModal.test.tsx`、`TodayQueuePanel.urgency.test.ts`、`dayQueueSession.test.ts`、`studyPlan.test.ts`、`sessionCurve.test.ts`、`viewState.test.ts`）。
- 后端：JUnit（`backend/src/test`，含 `KnowledgePointSummaryServiceTest`、`AiServiceSummarizeTest`、`KnowledgePointOriginalRepositoryTest`、`KnowledgePointControllerSummaryTest`、`ReviewRepositorySyncTest`、`ReviewServiceWireTest`、`CompletionSyncServiceTest`、`TodayQueueServiceTest`、`StudyPlanServiceTest` 等）。
- 脚本：`scripts/smoke-mvp.ps1`、`scripts/portable-audit.ps1`、`scripts/mvp-test.ps1`（v0.5+ 后端核心接口冒烟）、`scripts/seed-test-bank.py`（Python 造题种子）。
- 更新：`test-app-updater.mjs`、`updater/` 单元、`test-portable-updater.mjs` 原生专项、`test-app-update-desktop.mjs` 完整应用专项；最后一项使用实际 Electron / renderer / JAR / JRE 和独立 `temp/` 包，验证更新与恢复，不修改用户安装目录。运行入口见[测试规范](../test/TESTING_GUIDELINES.md)。

## 7. 版本演进（git 摘要）

| 阶段 | 主题 |
|---|---|
| MVP / v0.1–0.2 | 壳 + 题库刷题笔记 AI 基础 + 便携 |
| v0.3 | PDF/导出/高级挑题/知识点导入增强 |
| v0.4 | SM-2 复习、日历计划、今日队列、AI 多会话 |
| v0.5 | 背知识点 UI 重构（库/卡片/全屏视图、标题 Tooltip 气泡提示、大纲视口自适应双向滚动与统一缩进）、AI 总结知识点（双角色快照 + 三条总结路径）、健康端点加固、冒烟与种子脚本 |
| v0.5.1 | 会话内短周期记忆曲线（多轮循环出场、错题重复策略、背诵设置弹窗与预设）、背知识点独立选材（只背叶子）、题库选题继承、会话→日历联动（终值评分 + 顽固项加练，后端零改动）、日历实时「今天」 |
| v0.5.2 | 界面状态记忆：启动回到上次停留页面 + 各页选择/切换/筛选持久化（单一 localStorage key、容错归一、防抖写入、开关与清除） |
| v0.6.2 | TipTap 编辑器现代化、斜杠 / 块手柄 / 浮层；草稿回传与按页自动保存加固 |
| v0.6.3 | 编辑器稳定性：公式转换 / 复制 / 焦点 / 浮窗、统一统计、字面替换、文件落点、新建页面回跳与斜体修复 |
| v0.7.0 | AI 跨会话记忆、用量/缓存适配、删除与恢复安全；预发行，完整 120 §7 评测仍待补齐 |
| v0.7.1 | 多供应商/模型与预设、并发验证、四种记忆工具与活动历史、全局运行限制；schema 11；修复滚动、索引复用与来源删除 |
| v0.7.2 | 会话标题与导航、四个会话工具、多会话并行、人工排队与插话；schema 14；版本材料在开始 v0.7.3 前统一归档 |

更细提交说明用：`git log --oneline`。
