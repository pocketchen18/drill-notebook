# Drill Notebook

Drill Notebook is a Windows-first, green-portable learning app for Markdown question banks, quizzes, memorization, knowledge cards, wrong-answer practice, TipTap notebooks, a **study calendar / plan**, an **SM-2-style spaced-repetition review** module, and optional backend-proxied AI assistance (chat + optional multi-day scheduling).

## Requirements

- Node.js 20 or newer
- JDK 17 or newer (the Maven project targets Java 17; JDK 21 is also compatible)
- Maven 3.9 or newer, or use the checked-in `mvnw.cmd` wrapper

## Development

### One-click start

Double-click `start-mvp.cmd`. On the first run it installs missing Node dependencies, builds the frontend/Electron entrypoints and backend jar, then opens the desktop app. The app uses `runtime-portable/` as its development data root; closing the Electron window stops the local Java backend.

The equivalent commands are:

```powershell
npm start
npm run start:check
```

`npm run start:check` only verifies Node, Java, dependencies and build outputs. Use `npm start -- -Rebuild` after changing source when a fresh build is needed. `-NoInstall` prevents the startup script from installing dependencies automatically.

```powershell
npm install
npm run build:backend
npm run dev
```

`npm run dev` starts the Vite renderer and Electron. Electron starts the backend jar and uses `runtime-portable/` as the simulated application root during development. The backend can also be started directly with `npm run dev:backend`; set `APP_ROOT` to choose a different portable root.

Useful checks:

```powershell
npm run build:frontend
npm run build:electron
npm test
pwsh -File scripts/smoke-mvp.ps1
pwsh -File scripts/portable-audit.ps1
```

### Manual self-test

1. Run `start-mvp.cmd` and wait for the main window.
2. Open `题库`, import `resources/sample-bank.md`, then open `刷题`.
3. Submit one incorrect answer, open `错题`, and verify the question appears.
4. Open `笔记本`, create or open a page, edit a formula/diagram/Markdown block; formula/Mermaid/Markdown blocks render by default (click to edit). Add the current page (or multi-select pages) via **加入计划**.
5. In `刷题`, use `1-4` to choose, `Enter` to submit, `Left/Right` or `P/N` to move between questions.
6. Open `设置` / AI config, set an OpenAI-compatible provider (or local `mock://local` if available), load a wrong question/bank/note as context, and confirm the Markdown response can be inserted into a note.
7. After a quiz / memorize / knowledge session ends, the **本轮结束后的学习计划** dialog appears: pick candidates, set start/optional end dates, optionally enable **让 AI 帮忙排计划**, then write to the calendar. Open `日历` to review and **去学习**.

Automated checks are available with `npm test`, `pwsh -File scripts/smoke-mvp.ps1`, and `pwsh -File scripts/portable-audit.ps1 -RunSmoke`.

## Portable behavior

In a packaged build, the application root is the directory containing the executable. Electron user data, cache, temporary files, logs, runtime PID files, and the SQLite database are redirected below that root. The backend only listens on `127.0.0.1` and receives `APP_ROOT` from Electron.

The repository does not currently contain a built `.exe` or a JRE binary. To create a runnable portable package, install/build the workspace dependencies, create `jre\bin\java.exe` using [docs/jlink.md](docs/jlink.md), then run:

```powershell
npm run package:portable
```

The resulting portable `.exe` is written under `dist\`. The packaging script refuses to run without the embedded JRE, so a generated package is expected to run without a system Java installation.

## MVP scope

**Included**

- Question banks: single / multiple / fill / true-false / essay; Markdown import; PDF import (rules + AI fallback); JSON import paths
- Quiz sessions, memorization (questions + knowledge points), wrong-book tracking
- Notebooks with autosave, KaTeX / Mermaid / Markdown live blocks, export; note pages can be planned for review
- **Study calendar / plans**: add questions, knowledge points, and note pages to dates; plan groups; same resource allowed multiple times on one day; complete while studying; year/month navigation
- **Spaced-repetition review (SM-2 style)**: enroll questions / knowledge points into a review config; per-item EF / interval / repetitions / status (`new` → `learning` → `review` → `mastered`); review log history; multiple configs (`标准模式` / `考前突击` / `保守学习`), default flag, custom interval tables, wrong-answer strategy, daily new/review limits and priority mode
- **Today queue**: enrolled-due items (curve) and calendar todos (plan) merged into one per-day queue with quality scoring and auto-advance; resumable across reloads
- **Calendar SRS overlay**: per-day due counts and red overdue markers come from `review_schedule`, independent of the manual plan items
- **Join plan / post-session plan** (user chooses AI or not):
  - Manual: required start date, optional end date; no end → all on start day; with end → round-robin spread across the window
  - Optional AI schedule: user prompt + enriched context (difficulty, wrong counts, tags, …) within the date window (default 5 days if end omitted; **no system hard max**); prompt cannot expand past the date controls; rule fallback if AI fails
- AI chat (encrypted local history), advisory essay grading

**Deferred**

- Knowledge graphs, exam simulation
- Video or heavy multimodal import, collaboration, SQLCipher full-database encryption
- Natural-language rewriting of the date window controls

## Documentation

| Doc | Description |
|---|---|
| [docs/jlink.md](docs/jlink.md) | Build embedded JRE for portable packaging |
| [docs/import-formats.md](docs/import-formats.md) | PDF / JSON bank import formats |
| [docs/knowledge-point-import.md](docs/knowledge-point-import.md) | Knowledge-point Markdown import |
| [docs/quiz-markdown-flow.md](docs/quiz-markdown-flow.md) | Quiz Markdown parse / render flow |
| [docs/review-srs.md](docs/review-srs.md) | Spaced-repetition review (SM-2): schema, API, configs, today queue, calendar overlay |

Design specs and implementation plans under `docs/superpowers/` are **local only** (gitignored). Word (`.docx` / `.doc` / `.docm`) is also not stored in git.

---

## 中文说明

### 环境与启动

需要 Node.js 20+、JDK 17+。双击 `start-mvp.cmd` 即可启动；首次运行会自动安装依赖并构建前端、Electron 和 Java 后端。命令行等价方式：

```powershell
npm start
npm run start:check
```

开发数据只写入工作区的 `runtime-portable\`。关闭 Electron 窗口会停止本地 Java 后端。

### 功能说明

- **题库**：导入或手动维护单选（`single`）、多选（`multiple`）、填空（`fill`）、判断（`true_false`）和解答题（`essay`），重复导入会自动跳过。支持 Markdown；也可导入 PDF（规则解析为主，不足时由已配置的 AI 兜底）及 JSON 等格式（见 `docs/import-formats.md`）。
- **刷题**：数字键 `1-4` 选择答案，`Enter` 提交；`←`/`→`、`PageUp`/`PageDown` 或 `P`/`N` 切题。设置页可批量「加入计划」。
- **背题 / 背知识点**：按题型、章节、标签或具体条目批量选择，支持随机重排、手动顺序和会话内跳转；可加入计划。
- **错题**：最近答错且未纠正的题目；可勾选加入计划或再练。
- **笔记本**：公式、Mermaid 和 Markdown 块默认渲染，点击进入编辑。**当前页或勾选多页可加入计划**（笔记也可复习）；日历「去学习」可打开笔记。
- **日历**：按月查看学习计划，可切换**年份 / 月份**。计划条目含题目、知识点、笔记页；支持完成勾选、整组删除、按日/组「去学习」（刷题 / 背知识点 / 复习笔记）。**同一天允许同一资源多条待办**（一天可复习多次）。日历还会叠加**间隔重复复习**的到期/逾期标记，与手动计划互相独立。
- **复习（间隔重复 SM-2 风格）**：把题目或知识点加入复习方案后，系统按 EF / 间隔 / 连续正确次数等推算下次到期时间，状态在 `new → learning → review → mastered` 之间流转；每次提交记录一条 `review_log`。可同时存在多套方案（`标准模式` / `考前突击` / `保守学习`），其中一套标记为默认；每套方案可自定义间隔表、答错策略（间隔减半 / 重置 / 减少 25% / 固定天数）、每日新学/复习上限与排序策略。配置入口：**设置 → 复习方案** 或独立的「复习配置」页。
- **今日队列**：日历页顶部的「今日队列」面板把**已加入复习方案且今日到期**的条目（curve）和**当日计划待办**（plan）合并成一个队列；答完一题自动推进到下一条；刷新或重启会保留进度。
- **加入计划**（各学习页）：
  - **起始日必填，终止日可选**（是否开 AI 都可用）。
  - **不开启 AI**：无终止 → 全部写到起始日；有终止 → 在窗口内按天**轮询均分**条目（除不尽时多出来的摊在前面几天，不是堆最后一天）。
  - **开启「让 AI 排计划」**（可选）：可填需求/薄弱点提示词；系统附带难度、错题次数、标签等上下文；在起始～终止窗口内生成多日方案（无终止时默认 5 天窗口；**不设系统硬上限**）。提示词中的更大天数不能扩大日期窗口。AI 失败时规则降级。确认后一键写入。
- **会话结束**（刷题 / 背题 / 背知识点结束，或背知识点「结束并推荐」）：
  - 弹窗标题：**本轮结束后的学习计划**。
  - 先展示规则候选并勾选；设起始/可选终止。
  - **「让 AI 帮忙排计划」默认关闭**——由用户自主选择是否用 AI。
  - 关 AI：手动写入（单日或日期范围均分）。
  - 开 AI：填提示词 → 生成方案 → 一键添加到日历。
- **从计划学习**：带 `planDate` / `planGroupId` / `planItemId` 时，刷题提交、背题标记、知识点揭示等会把对应计划项标为完成（中途退出也保留已完成项）。
- **AI 助手**：全局悬浮球 + 侧边栏（`Ctrl+J`），多会话；消息经 AES-256-GCM 加密写入本地 SQLite。刷题/错题/笔记等可带上下文；回复可「插入笔记」。连接在「设置」中配置。

### Markdown 题型格式

每道题使用 YAML frontmatter，题目之间用单独一行 `===` 分隔。选择题必须提供选项和 `answer`；填空题提供文本 `answer`；判断题的标准答案使用 `true` 或 `false`。解答题不要求固定答案，可在正文中加入可选的参考答案：

```markdown
---
type: essay
tags: [jvm, gc]
---
### 题干
简述垃圾回收的目标。
### 参考答案
识别并回收不可达对象，释放内存。
### 解析
可结合可达性分析展开。
```

解答题提交后会尝试调用「设置」中已配置的模型。模型只提供建议得分、置信度和说明，不写入确定的对错；AI 未配置或调用失败时答案仍会保存，也不会被误记为错题。

### 文档与 docx

- 产品文档见上文 **Documentation** 表及 `docs/` 下已放行的 Markdown。
- `docs/superpowers/`（设计 spec / 实现 plan）为本地工作材料，**已 gitignore，不提交**。
- 仓库**不提交** `.docx` / `.doc` / `.docm`。需要 Word 时请从 Markdown 本地导出。

### 打包

仓库默认不携带 JRE 和 exe。按照 `docs/jlink.md` 在仓库根目录生成 `jre\bin\java.exe`，然后执行 `npm run package:portable`，输出在 `dist\`。
