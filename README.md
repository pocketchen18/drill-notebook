# Drill Notebook

Drill Notebook is a Windows-first, green-portable Standard MVP for Markdown question banks, quizzes, memorization, knowledge cards, wrong-answer practice, a TipTap notebook, and optional backend-proxied AI assistance.

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
4. Open `笔记本`, create or open a page, edit a formula/diagram/Markdown block, switch to `预览`, and verify Markdown, LaTeX, Mermaid and question-block content render.
5. In `刷题`, use `1-4` to choose, `Enter` to submit, `Left/Right` or `P/N` to move between questions.
6. Open `AI`, configure the local `mock://local` provider, load a wrong question/bank/note as summary context, attach a text file or image, and confirm the Markdown response can be inserted into a note.

Automated checks are available with `npm test`, `pwsh -File scripts/smoke-mvp.ps1`, and `pwsh -File scripts/portable-audit.ps1 -RunSmoke`.

## Portable behavior

In a packaged build, the application root is the directory containing the executable. Electron user data, cache, temporary files, logs, runtime PID files, and the SQLite database are redirected below that root. The backend only listens on `127.0.0.1` and receives `APP_ROOT` from Electron.

The repository does not currently contain a built `.exe` or a JRE binary. To create a runnable portable package, install/build the workspace dependencies, create `jre\bin\java.exe` using [docs/jlink.md](docs/jlink.md), then run:

```powershell
npm run package:portable
```

The resulting portable `.exe` is written under `dist\`. The packaging script refuses to run without the embedded JRE, so a generated package is expected to run without a system Java installation.

## MVP scope

Included: single-choice, multiple-choice, fill-in, true/false, and essay Markdown import; advanced session ordering; question and knowledge-point memorization; wrong-answer tracking; notebooks with autosave; KaTeX, Mermaid, snapshots, exports; encrypted OpenAI-compatible AI chat and advisory essay grading.

Deferred: PDF import, spaced repetition, knowledge graphs, exam simulation, video or multimodal import, collaboration, SQLCipher full-database encryption, and AI content generation beyond chat and summary.

## 中文说明

### 环境与启动

需要 Node.js 20+、JDK 17+。双击 `start-mvp.cmd` 即可启动；首次运行会自动安装依赖并构建前端、Electron 和 Java 后端。命令行等价方式：

```powershell
npm start
npm run start:check
```

开发数据只写入工作区的 `runtime-portable\`。关闭 Electron 窗口会停止本地 Java 后端。

### 功能说明

- `题库`：导入或手动维护单选（`single`）、多选（`multiple`）、填空（`fill`）、判断（`true_false`）和解答题（`essay`），重复导入会自动跳过。
- `刷题`：数字键 `1-4` 选择答案，`Enter` 提交；`←`/`→`、`PageUp`/`PageDown` 或 `P`/`N` 切换上一题/下一题。
- `背题` / `背知识点`：按题型、章节、标签或具体条目批量选择，支持随机重排、手动调整顺序和会话内跳转。
- `笔记本`：公式、Mermaid 和 Markdown 块**默认渲染**，点击块进入编辑，完成/失焦后回到渲染视图（类似 Obsidian Live Preview）。
- `AI`：全局悬浮球 + 侧边栏（`Ctrl+J`），在刷题/错题/笔记页自动携带当前上下文；助手回复可一键「插入笔记」。API 连接在「设置」中配置。

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

### 打包

仓库默认不携带 JRE 和 exe。按照 `docs/jlink.md` 在仓库根目录生成 `jre\bin\java.exe`，然后执行 `npm run package:portable`，输出在 `dist\`。
