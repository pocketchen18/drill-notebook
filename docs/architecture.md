# Drill Notebook 架构说明

当前仓库实现层面的架构边界；若与代码冲突，以代码为准。

## 1. 进程与通信

| 进程 | 技术 | 职责 |
|---|---|---|
| Electron Main | Node | 窗口、菜单、便携路径、单实例、spawn Java、生命周期 |
| Renderer | React 18 + TS + Vite + Arco | 全部 UI；通过 `window.api` 与主进程交互；HTTP 访问后端 |
| Backend | Spring Boot + SQLite | 领域 API：题库、练习会话、导入导出辅助、计划、SRS、AI 代理与会话 |

- 后端绑定 **`127.0.0.1`**，端口由启动参数/协商文件决定（见 `electron/java-bridge.ts`）。  
- 渲染进程 **不直连** 用户 AI 厂商密钥所在的业务逻辑：由后端代发。  

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

学习页通过 `useRegisterPageContext` 注册 `{ kind, title, markdown, ... }`。  
仅内容变化时写入 store，防止 React #185 最大更新深度错误。

### 2.3 界面状态记忆（v0.5.2）

`lib/viewState.ts` 用**单个** localStorage key `ui.viewState.v1` 保存「上次页面 + 各页视图状态」：

- **读**：`readViewState()` / `readPageSlice(page)` / `readLastRoute()`，先与存储原文比对再解析（模块内缓存，7 个页面共用一次 `JSON.parse`），并把未落盘的待写入补丁叠加上去，因此同一渲染内读到的永远是最新值。  
- **写**：`persistViewState(page, partial)` 按页浅合并 + JSON 去重 + 300ms 尾部防抖；`recordRoute(pathname)` 只接受侧栏白名单路径，并把 `/quiz`、`/memorize` 折叠为 `/practice`；`pagehide` / `beforeunload` 调 `flushViewState()` 兜底。  
- **容错**：`normalizeViewState` 永不抛错——损坏 JSON、未知页名、非正整数 id、越界枚举一律丢弃；勾选列表按 `IdSet`/`KeySet` 编码（整库勾选 → `all` 哨兵），上限 500 id / 200 key / 4 个作用域。作用域图另存 `recent`（旧→新，封顶 4 条）：数字型 key 在对象里只会按升序枚举，光靠键序表达不了「最近用过」，所以淘汰时按 `recent` 从旧到新出局、且永不淘汰正在写入的那个库。超预算先截断后放弃写入。  
- **硬约束**：**绝不**持久化密码（如刷题页的题库主密码）、笔记脏缓冲、`answerStates`、SM-2 会话内去重表与任何「进行中的会话」状态；进行中的日队列会话仍留在 sessionStorage（`drill.dayQueueSession`）。  
- **写入时序**：勾选类面板（题库、刷题、背题、背知识点）在本库记忆**尚未套用前不得写回**，否则切库瞬间的空值会把用户记下的选择静默覆盖；而这个「已套用」标记**必须是 state 而不是渲染期读的 ref**——套用结果与当前值相同时 React 会跳过重渲染，ref 门闩会让写入器再也不运行（背知识点切库记不下来就是这么来的）。日历跨零点跟随「今天」时也要判断当前视图年月是否就是旧的今天所在月——停在记忆里翻到的上月等同「用户在看别处」，只刷数据不跳视图。  
- **开关**：`sessionPrefs` 的 `ui.rememberViewState`（默认开）；关闭时读写全部 no-op，且设置页会立即 `clearViewState()`。  
- **落点**：`App.tsx` 的 `/` 与 `*` 重定向到 `readLastRoute() ?? '/notebooks'`；各页遵循「URL/深链 > 记忆 > 默认首项」的优先级，删除实体后由各页既有的剪枝 effect 回落。

## 3. 后端领域（概念）

| 域 | 内容 |
|---|---|
| 题库 | bank / question / FTS / 导入哈希 |
| 练习 | quiz session、answer_record、错题查询 |
| 笔记 | notebook / note_page / 题目快照块 |
| 附件 | `note_attachment`：上传/列表/内容/删除 API，SHA-256 去重，存储于 `data/attachments/` |
| 计划 | 日历计划组与条目（题目/知识点/笔记） |
| 复习 | spaced repetition 配置、enrollment、schedule、log |
| AI | 配置密文、多会话、消息密文、chat/summarize 代理 |
| 知识点总结 | `knowledge_point_original` 双角色快照（original/summary）；`KnowledgePointSummaryService` + `AiService.summarizeKnowledgePoint` / `summarizeMarkdown`（v0.5） |

Schema 以 `backend/src/main/resources/schema.sql` 为准。

## 4. 导入 / 导出

- **导入**：Markdown 解析器、PDF 服务（规则 + AI 兜底）、JSON 等，见 `docs/import-formats.md`；知识点 Markdown 导入见 `docs/knowledge-point-import.md`。  
- **导出**：前端 `lib/export.ts` + 页面 `ExportActions`；题库/笔记/错题/会话等。  
- **导出格式 round-trip**（v0.1.0+）：题库导出的叙述式 `.md`（`### 颜干` + `**答案：**` + `---` 分隔）可再走「导入 Markdown」入口导回，由 `ExportMarkdownParser` 解析，无需手动改成 frontmatter 格式。详见 `docs/import-formats.md` §3。  
- **AI 总结知识点**（v0.5）：双角色快照 + 三条总结路径，见 `docs/ai-summary.md`。

## 5. 便携与打包

- 开发：未打包时默认在工作区旁建立便携数据根（`APP_ROOT`，不入库）。  
- 生产：`electron/paths.ts` 将 userData/cache/temp 指到可执行文件旁。  
- 打包：`docs/jlink.md` + `npm run package:portable`。  

## 6. 测试

- 前端：Vitest（`frontend` 内 `*.test.ts` / `*.test.tsx`，如 `AiSummaryModal.test.tsx`、`TodayQueuePanel.urgency.test.ts`、`dayQueueSession.test.ts`、`studyPlan.test.ts`、`sessionCurve.test.ts`、`viewState.test.ts`）。
- 后端：JUnit（`backend/src/test`，含 `KnowledgePointSummaryServiceTest`、`AiServiceSummarizeTest`、`KnowledgePointOriginalRepositoryTest`、`KnowledgePointControllerSummaryTest`、`ReviewRepositorySyncTest`、`ReviewServiceWireTest`、`CompletionSyncServiceTest`、`TodayQueueServiceTest`、`StudyPlanServiceTest` 等）。
- 脚本：`scripts/smoke-mvp.ps1`、`scripts/portable-audit.ps1`、`scripts/mvp-test.ps1`（v0.5+ 后端核心接口冒烟）、`scripts/seed-test-bank.py`（Python 造题种子）。

## 7. 版本演进（git 摘要）

| 阶段 | 主题 |
|---|---|
| MVP / v0.1–0.2 | 壳 + 题库刷题笔记 AI 基础 + 便携 |
| v0.3 | PDF/导出/高级挑题/知识点导入增强 |
| v0.4 | SM-2 复习、日历计划、今日队列、AI 多会话 |
| v0.5 | 背知识点 UI 重构（库/卡片/全屏视图、标题 Tooltip 气泡提示、大纲视口自适应双向滚动与统一缩进）、AI 总结知识点（双角色快照 + 三条总结路径）、健康端点加固、冒烟与种子脚本 |
| v0.5.1 | 会话内短周期记忆曲线（多轮循环出场、错题重复策略、背诵设置弹窗与预设）、背知识点独立选材（只背叶子）、题库选题继承、会话→日历联动（终值评分 + 顽固项加练，后端零改动）、日历实时「今天」 |
| v0.5.2 | 界面状态记忆：启动回到上次停留页面 + 各页选择/切换/筛选持久化（单一 localStorage key、容错归一、防抖写入、开关与清除） |

更细提交说明用：`git log --oneline`。
