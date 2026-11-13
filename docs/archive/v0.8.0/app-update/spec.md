# v0.8.0 软件内更新与关于：实现规格

> 已于 2026-11-12 随 v0.8.0 归档，仅作历史参考；下文的「当前」与待验项均保留施工时口径，归档不新增验证结论。版本收尾与后续调整见 [归档说明](../README.md)。

> 状态：已实现，工程验证通过（2026-11-12），尚未发布。产品规则见[需求](requirements.md)与 [UI/UX](ui-ux.md)，当前范围见 [F09](../features.md)，测试与交付边界见[计划](plan.md)。本切片不新增数据库迁移，继续使用 schema 18。

## 1. 模块与依赖

| 模块 | 职责 |
|---|---|
| [AppUpdater](../../../../electron/app-updater.ts) | 单一更新状态、偏好、检查计时、说明范围、下载、校验、保存等待、事务与清理 |
| [update-feed](../../../../electron/update-feed.ts) | 固定公开仓库、语义版本、Release / 平台资产、分页与范围筛选 |
| [update-archive](../../../../electron/update-archive.ts)、[program-manifest](../../../../electron/program-manifest.ts) | ZIP 预检、原始文件读写、路径与程序清单、逐文件 SHA-256 |
| [PortableUpdateHost](../../../../electron/update-host.ts)、[java-bridge](../../../../electron/java-bridge.ts) | 受信 IPC、两窗口保存握手、后端退出、辅助程序交接、健康确认与恢复 |
| [Rust helper](../../../../updater/src/main.rs) | 等待应用退出、程序 / 数据库备份、替换、重启、回滚与中断恢复；不负责网络检查或下载 |
| [app-update 组件](../../../../frontend/src/components/app-update/) | 关于页、顶部入口、逐版说明、范围菜单、连续进度与启动等待 |
| [updatePreparation](../../../../frontend/src/lib/updatePreparation.ts)、[assistantDrafts](../../../../frontend/src/lib/assistantDrafts.ts) | 保存笔记、界面状态、助手草稿；检查业务编辑与待发 / 正在运行任务 |
| [PortableUpdateRequestGate](../../../../backend/src/main/java/com/drillnotebook/app/config/PortableUpdateRequestGate.java) | HTTP 与后台写任务租约，防止最后退出检查后出现新的写入 |

复用 npm `semver` 的语义版本比较、`yauzl` 的 ZIP 流读取、已有 Markdown renderer 与 Arco Modal。Rust 辅助程序使用 `serde` / `serde_json`、`sha2`、`semver` 和 Win32 进程等待 API；`yazl` 仅用于测试包。依赖版本由各自锁文件固定。参考软件的布局与流程见 [UI/UX §6](ui-ux.md#6-参考源码与可复用范围)，不复用其安装器或系统配置落点。

Electron 对 `.asar` 的普通 `node:fs` 有虚拟目录语义。更新文件操作统一经 [update-fs](../../../../electron/update-fs.ts)：Electron 中用 `original-fs`，普通 Node 测试中用 `node:fs`，保证 `app.asar` 按原始程序包下载、解压、哈希与替换。

## 2. 偏好、缓存与运行状态

所有路径由已有 `PortablePaths.root` / APP_ROOT 派生并检查链接，禁止落到用户 AppData 或系统临时目录。

| 文件 / 目录 | 格式与内容 |
|---|---|
| `config/update-settings.json` | `format:1`；`settings:{automaticUpdate,automaticCheck,intervalHours,startupReminder}`；成功检查 / 实际提醒时间、跳过版、上次成功运行版、已知新版、失败目标与原因 |
| `cache/updates/releases.json` | 有界 Release 正文与资产缓存；供离线恢复顶部状态及手动阅读 |
| `cache/updates/<id>/` | 当前事务的 ZIP；带 `.drill-update-owned.json` 所有权标记 |
| `runtime/updater/<id>/` | 暂存程序、辅助 EXE、`transaction.json`、`status.json`、`ready.json`、`baseline.json`、`health.json` 及所有权标记 |
| `runtime/updater/active.json` | 当前可恢复事务的 UUID，不接受任意文件路径 |
| `backups/updates/<id>/` | `program/`、`database/` 和必要的 `failed-data/`，带所有权标记 |
| `config/update-failure.json` | `format:1`、事务 ID、失败版本、真实原因；阻止同目标启动自动更新循环 |
| `config/assistant-drafts.json` / `assistant-drafts-key.bin` | AES-256-GCM 草稿与目录内 32 字节密钥；不依赖机器绑定加密 |

配置通过临时文件、fsync 与原子替换落盘。缺失 / 损坏设置回到默认值，自动安装仍默认关闭。间隔仅接受 1–168 整数小时，未知字段与错误类型拒绝；修改设置不重置真实时间。缓存重新校验版本与资产路径，不信任缓存中的任意下载文件名。

完整渲染契约以 [update-types.ts](../../../../electron/update-types.ts) 为准：检查状态为 `unchecked / current / available / error`；安装阶段为 `idle / downloading / verifying / preparing / blocked / restarting / failed`；弹窗展示独立以 `presentation:{id,kind,version?}` 控制。关闭只能作用于当前 presentation ID，不能误关闭后续弹窗或停止事务。下载与阅读目标固定，不被后台检查更换。

成功检查与实际显示新版说明分别计时。运行中计时只在主进程调度，检查失败单独退避；辅助窗口不创建计时器。关于页只读范围读取不推进检查 / 提醒时间。自动更新每次启动都真实检查，忽略自动检查开关和间隔；已跳过与失败目标不自动安装。运行中开启仅保存偏好。

## 3. Release 与说明筛选

固定仓库 `qilin-software/drill-notebook`，使用 `/releases?per_page=100&page=...`，最多 10 页；单页响应限制 16 MiB，每次请求有超时。排除 draft，规范化 `v` 前缀后使用语义版本排序，兼容所有 v0.x 预发行；不使用 Latest。手动与自动检查合并同一在途请求。

匹配 `Drill-Notebook-<version>-win-x64-portable.zip`，资产大小限制 1 GiB，下载源必须为本仓库公开 HTTPS Release 地址。允许 GitHub 的公开资产重定向，拒绝其他目标、嵌入凭证与额外端口。请求不使用用户账号或浏览器会话凭证。

- 新版说明：`installed < version <= target`，仅未安装版本，按新到旧逐版保留完整正文。
- 关于「上次更新以来」：刚升级时取 `previousRun < version <= installed`；无有效旧运行版时只取当前安装版。可在前面附上已知目标以内的待安装说明。
- 关于历史 N：`version <= installed`，最多 N+1 版；N 为 10 / 20 / 50 / 100。切换范围不检查新版、下载或改变跳过记录，重开恢复 `since`。
- 失败可返回所选范围内已有缓存并附 `cached/error`，不能混入范围外正文或将失败写成已是最新。空正文保留空状态与对应 Release 原文。

## 4. 下载与程序清单

包摘要优先使用 GitHub `digest:sha256:<hex>`，否则读取同名 `.zip.sha256`，校验文件名与 64 位摘要。下载按已知资产大小计数，拒绝超量、缺失与不符的 Content-Length；30 秒没有数据则终止。只有下载阶段可取消，× / Esc 仅隐藏进度。

便携 ZIP 根目录包含：

```json
{
  "format": 1,
  "version": "0.8.0",
  "files": [
    { "path": "resources/app.asar", "size": 123, "sha256": "<64位小写摘要>" }
  ]
}
```

此示例只说明字段，实际清单必须包含全部程序文件。清单排除自身，要求 `Drill Notebook.exe`、`resources/app.asar`、`resources/backend/app.jar`、`resources/jre/bin/java.exe` 与 `resources/updater/drill-updater.exe`。允许固定 Electron 根文件、`locales/*.pak` 与 `resources/`；禁止把 `data/`、运行配置、模型、附件或自定义根文件纳入替换。

先检查整个 ZIP 目录与清单，再向暂存目录写文件；ZIP 文件集合、路径大小写、逐文件大小与摘要必须完全匹配。最多 20,000 个清单文件、30,000 个 ZIP 条目、2 GiB 解压总量；限制异常压缩比例。拒绝路径穿越、绝对路径、ADS、设备名、尾点 / 空格、大小写重复、文件与目录冲突、链接、目录联接和加密 ZIP。辅助程序交接前再次校验暂存清单与文件摘要。

根目录须可写并有足够空间；下载前保守预留约 ZIP 大小五倍加 64 MiB，暂存后再次按实际旧程序与数据库备份大小预检。开发模式只检查和阅读，不覆盖源码。EXE 与 APP_ROOT 必须为同一 Windows x64 便携目录；存在根目录自定义 backend / jre / embedding-worker 覆盖组件时拒绝自动替换。

## 5. 保存与正常退出

校验后先核对后端写任务与 AI 活动，再向所有存活窗口广播保存请求。每轮请求使用新 UUID，最多等待 15 秒；只接受对应窗口、对应 ID 的结果。主界面未就绪、窗口无响应、保存失败或任务仍在运行时进入 `blocked`，每 5 秒重新检查；「返回处理当前工作」只关闭进度，完成保护条件后自动继续，不重新下载。

两渲染进程分别阻止新的交互，排空 `NoteAutosave`，逐个执行注册的保存 participant，检查 React Query mutations、beforeunload 与仍在编辑的业务弹窗，最后刷新界面状态。助手串行保存各会话输入、附件、选择与阅读位置，并校验草稿身份；读取未完成或损坏时不能用空草稿覆盖。正在回复、待发、提交中、交付未知、附件恢复或标题保存未完成会阻止退出。

后端 [PortableUpdateController](../../../../backend/src/main/java/com/drillnotebook/app/controller/PortableUpdateController.java) 使用由 Electron 随 JVM 进程生成的退出能力，仅主进程持有，不暴露给 renderer：

- `/api/app/update/ready`：核对 HTTP / 后台写租约、ActiveTurnRegistry 与 SessionDispatch 队列。
- `/api/app/update/shutdown`：最后原子检查并关闭新写入和派发，忙碌返回 409 并恢复服务；接受后正常关闭 Spring context，等待任务与 SQLite 完成。

标题、嵌入作业 / 模型下载、检索维护、记忆维护等后台写任务均持有 gate 租约，排队任务也计入。退出期间新的业务写请求返回 503 `UPDATE_RESTARTING`。具体接口见[长期契约](../../../state/api-contracts.md#4-软件更新与退出协调v080)。

辅助程序确认暂存就绪后才请求 JVM 正常退出，再退出主 / 助手窗口。更新成功路径不调用普通强制终止后端逻辑；保存失败不强杀业务任务。启动更新会先显示启动等待界面，业务路由在后端与主界面就绪后恢复；`backend:get-base-url` 等待后端启动结束，防止提前请求缓存错误端口。

## 6. 替换、提交与恢复

事务 `format:1`，固定 UUID 与 APP_ROOT，记录 `fromVersion/toVersion`、主程序 / JVM PID、90 秒新版启动期限、要替换的程序文件及旧清单中废弃的程序路径。辅助 EXE 必须位于同事务目录，不接受外部事务文件或降级。

1. 校验事务、所有路径与暂存文件，写入 `ready.json`。用 Win32 句柄验证并等待旧主程序和 JVM 退出，保留句柄防止 PID 复用；路径规范化后忽略大小写。进程退出过程中映像路径查询失败时仍等待同一句柄，只有确认退出才允许替换；超时保留原状。
2. 备份全部受管理的旧程序与停机后的 `study.db / -wal / -shm`。记录每个路径原来是否存在、大小与摘要，完整不可变 `baseline.json` fsync 后才开始替换。
3. 将已验证暂存文件替换到各自程序路径，废弃程序有备份后才删除；数据、附件、模型、配置和自定义文件保持原位。逐文件再次校验。
4. 以 APP_ROOT 和事务 ID 启动新 EXE。主进程确认事务、实际运行版本、真实后端健康和受信主 renderer 就绪后持久化 `health:healthy`，再恢复业务页面。该确认是提交点，不能仅以复制完成记为成功。
5. 未健康确认的失败：只在新版主程序与 JVM 都退出后回滚；先验证完整唯一恢复基线及所有备份摘要，再保留失败数据库副本，恢复旧程序和停机数据库，写入失败目标并自动启动旧版。备份损坏、仍有活进程或恢复出错时保留现状与材料，不覆盖数据。
6. 中断后重开检查 active 事务；无在途辅助进程时重新启动同事务恢复。已经健康确认的事务只补记完成，不能回滚后续用户编辑；未提交事务恢复可重复执行。

辅助程序无管理员提权、注册表、系统服务、计划任务或持久化环境变量操作。正常更新使用隐藏辅助进程；仅无法安全恢复时显示真实错误。

## 7. 清理与发布

只能清理具有匹配 UUID / 所有权标记的更新材料。下载取消、校验失败及未交接事务清理自身暂存；成功或已回滚且 helper 已退出后清理 ZIP 与工作目录。未完成事务保留可恢复材料，程序 / 数据库备份最多保留最近两份，用户目录不进入清理范围。

[package-portable.ps1](../../../../scripts/package-portable.ps1)与[发布工作流](../../../../.github/workflows/release-portable.yml)先构建内置 Rust helper，目录打包后生成程序清单，再生成 ZIP 与 `.zip.sha256` 并上传校验材料。实际版本继续由发行参数注入，发布准备已将云端与本地默认同步为 0.8.0。业务全量测试仍是本地发布前门禁，云端先编译发布配置夹具所需的 Electron 模块，再做配置检查与其余编译打包。

完整应用专项只在隔离 `temp/` 包中注入 QA driver，生产打包配置不包含它，并在注入前断言生产 ASAR 没有该入口。测试不会发布 Release、注册系统协议或修改用户安装目录。实现后的全部验证结果与尚未验收范围见[计划](plan.md#2-验证结果)。
