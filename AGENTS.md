# AGENTS.md — AI 代理工作约束

本文件面向 AI agent，约束其在当前仓库中的行为边界。项目结构与用法见 `README.md`。

## 1. 系统安全与文件权限约束协议

委派任何可写subagent时，必须将本协议随 prompt 一并下发；主agent自身同样遵守。

- **核心工作区（读 / 写 / 删除）**：`E:\Code\work\drill notebook`
- **非 C 盘外部路径**：只读
- 对 C 盘文件的访问，除白名单外，必须先向用户写清楚路径、功能和用途，申请获批后方可执行。此外，当前harness环境允许的任何工具、文件都可随意访问，以system prompt为准。
- **C 盘白名单（可静默读取）**：为确保加载协作规则（如 `AGENTS.md`），允许静默读取以下底层工具的**prompt、tools、Skills、MCP 配置文件**或其它类似文件：
  - `C:\Users\*\.cache\`
  - `C:\Users\*\.codex\`
  - `C:\Users\*\.claude*`
  - `C:\Users\*\.opencode\`
  - `C:\Users\*\.qoder\`
  - `C:\Users\*\.gemini\`
  - `C:\Users\*\.omp\`等，一般为`\.your_harness_name\`。
- **红线文件（任何路径下，只允许查看元数据，严禁读取内容）**：
  - `settings.json`、`auth.json`、`.env`、`credentials`、`.pem`、`.key`
  - 文件名含 `secret` / `token` / `api_key` 的文件
  - `.codex/config.toml`、`.claude*/settings.json`、`opencode.json`
- **元数据透视**：任意路径列出条目数 ≤ 20 条
- **删除**：默认进回收站；仅自建的临时文件可直删

## 2. Shell 规范：PowerShell 一律用 7

- 如需使用 PowerShell，**统一使用 PowerShell 7**：`pwsh -NoProfile -Command` / `pwsh -NoProfile -File`。禁止使用 Windows PowerShell 5.1（harness 默认 shell）直接执行命令。
- 原因：5.1 默认 GBK 编码，管道改写文件会损坏 UTF-8 中文内容（已发生过 `DataManagementPanel.tsx` 全文损坏事故）。
- **文件修改一律使用 Read / Edit / Write 工具，禁止通过 shell 管道改写文件。**
- 复杂嵌套命令（多层引号转义）写成 `.ps1` 脚本文件再 `pwsh -File` 执行，不做引号转义杂技。

## 3. 绿色便携开发要求（硬约束）

本软件是 Windows 绿色便携应用：**所有文件、数据、配置、临时文件都落在软件根目录（APP_ROOT）内**，不写 C 盘，不写注册表，不持久化环境变量。每个功能落地前按此自查：

- **写盘路径**：一律经 `PortablePathResolver`（`data/`、`root()/backups`、`runtime/`、`temp/`、`cache/`）。禁止直接使用 `%TEMP%`、`java.io.tmpdir` 默认值、`user.home`、`%APPDATA%`、`%LOCALAPPDATA%`。
- **注册表**：禁止 `java.util.prefs`、Electron `setLoginItemSettings` / `setAsDefaultProtocolClient`、`REG ADD`、`regsvr32`、快捷方式写入等任何持久化注册表操作。
- **环境变量**：子进程可传进程级 env（随进程消失）；禁止 `setx`、`[Environment]::SetEnvironmentVariable(User/Machine)` 等持久化写法。
- **Electron 落点**：`userData` / `sessionData` / `cache` / `temp` 已由 `electron/paths.ts` 重定向进 APP_ROOT，新增 Chromium 侧能力时确认不引入新的系统目录落点。
- **JVM 兜底**：`BackendApplication.main` 已设 `java.io.tmpdir → root/runtime/tmp`；已知残余限制——Spring Boot 3.x loader 在 main() 之前会污染 `File.createTempFile` 的 tmpdir 缓存，裸 `java -jar` 启动时 Tomcat 小目录仍落系统 %TEMP%（Electron 正式启动经 `java-bridge.ts` 显式传 `-Djava.io.tmpdir`，不受影响）。
- **测试例外**：`mvn test` 的测试 fixture 会在系统 %TEMP% 产生 `drill-notebook-*` 临时目录（开发期产物，不影响应用便携性）；审计时勿与运行时泄漏混淆。
- **审计工具**：
  - `pwsh -NoProfile -File scripts/portable-audit.ps1` — 运行时落盘审计（embedding 缓存 / %APPDATA% / %LOCALAPPDATA% / %TEMP% 监控）
  - `pwsh -NoProfile -File cache/portable-registry-audit.ps1` — 源码静态扫描（注册表 / 持久化环境变量 API）
  - 新功能涉及写盘时，两项审计都应复跑并保持通过。
