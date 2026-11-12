# v0.8.0 软件内更新与关于：实施与验证

> 状态：所有实施项完成，工程验证通过（2026-11-12）；文档在代码与测试完成后统一同步。功能实现已由用户提交，现进入 v0.8.0 发布准备，尚未发布。产品规则见[需求](requirements.md)、[UI/UX](ui-ux.md)，协议见[规格](spec.md)。

## 1. 已完成切片

- [x] 关于与说明：在设置末尾接入 AboutPanel，实际版本、GitHub 图标、唯一更新内容入口、Star；顶部入口、两类逐版说明、范围菜单与独立进度共用主进程状态。修改范围限于设置 / App 接入、`components/app-update/`、preload 与类型。
- [x] 主进程更新：`app-updater / update-feed / update-types` 实现独立启动自动更新、共享周期与提醒间隔、跳过 / 失败目标、并发合并、范围缓存、目标固定及取消。检查失败不伪造最新版。
- [x] 下载与校验：`update-archive / program-manifest / update-fs` 实现 HTTPS / digest、大小与逐文件清单验证、原始 ASAR 文件系统、路径边界、空间预检与有界清理。
- [x] 保存与退出：`update-host / java-bridge`、`UpdateLifecycleBridge / updatePreparation`、笔记自动保存与助手草稿串行持久化；两窗口握手、忙碌保护、后端最后退出检查、后台写租约与正常停机。
- [x] 原生便携替换：`updater/` 与构建脚本实现 Win32 进程等待、完整程序 / 停机数据库基线、替换重启、新版健康提交点、失败恢复与中断恢复。更新助手只在 APP_ROOT 内运行。
- [x] 便携发布接入：目录 ZIP 内置 helper 与程序清单，ZIP 附 SHA-256；更新构建与发布配置用例、锁文件和 `.gitignore`，保留实际版本与预发行规则。
- [x] 逐层验证：前后端全量、Electron 与发布配置、Rust、原生替换、实际 Electron + renderer + JAR + JRE 的闭环及便携审计全部完成。
- [x] 文档同步：需求、UI/UX、规格、计划、本版与全局清单、架构、接口、打包说明和 QA 原位更新；施工材料保留至下一版本开始前统一归档。

## 2. 验证结果

所有已执行的最终检查均通过；保留原有跳过项，工程验证与真实 UI 人工验收分别记录。

| 层级 | 结果 | 范围 |
|---|---|---|
| 前端全量 | 91 文件通过 / 2 文件跳过；917 项通过 / 4 项既有跳过，0 失败 | 新增更新组件 9 项、保存准备 3 项、草稿解析 3 项，助手草稿与笔记排空回归均在全量中 |
| 前端 / Electron 构建 | 通过 | TypeScript、Vite 生产构建及 Electron 编译 |
| 后端全量 | 155 类，1515 项：1512 通过 / 3 项既有跳过，0 失败 / 0 错误 | 更新 ready / shutdown、能力校验、HTTP / 后台写 gate、派发及全部既有领域回归 |
| 实际后端 JAR | 打包通过 | `backend/target/drill-notebook-backend-0.1.0.jar`，用于完整应用专项 |
| Electron / 更新器 / 原生单实例 / 发布配置合跑 | 50 项全部通过 | 主入口 17、更新逻辑 22、原生单实例 1、发布配置 10；含实际 ZIP 校验与模拟发布打包 |
| Rust 单元 | 5 项全部通过 | 路径 / ID、完整恢复基线、损坏备份不覆盖、路径查询失败时仍等待真实进程退出 |
| 原生更新专项 | 5 项全部通过 | 真 Electron ASAR、真实 Windows 应用 / 后端进程桩等待、失败程序 / 数据恢复、健康提交后保留编辑、中断恢复可重复 |
| 完整应用专项 | 3 项全部通过 | 实际 Electron、renderer、JAR、内嵌 JRE；手动连续更新、启动自动更新、真实新版 JAR 启动失败恢复 / 防循环 |
| 公开 GitHub 检查 | 通过 | 生产 `fetchReleases` 识别现有 7 个预发行版、平台 ZIP 和 SHA-256 digest；最新为 0.7.3 |
| 便携运行审计 | 通过 | 监控完整应用三项测试；用户 AppData / 应用目录、embedding 缓存与相关系统临时目录新增 / 变化均为 0 |
| 注册表 / 持久化环境变量审计 | 通过 | 693 个文件静态扫描，唯一环境变量匹配为合法 Process 范围恢复；Rust / mjs 补充扫描无匹配 |
| 功能实现阶段文档 / Git 完整性 | 通过 | 78 个改动文本 UTF-8、171 个相对链接和 11 个锚点；差异格式通过，0 个未忽略的生成产物 |

本轮修复并回归：Electron 对暂存 `.asar` 的虚拟目录处理；Windows 进程退出过程中路径分隔符、规范化与路径查询竞态；损坏恢复备份的覆盖保护；提前请求后端端口的等待；助手草稿身份与慢读取保护。QA 指令绑定具体进程，不能在回滚后的同版本重启时重放安装操作；失败测试清理时只处理经 Unicode 路径核验的自身进程。

完整应用测试先用真实发行配置生成隔离目录，断言生产 ASAR 无 QA 入口，再仅在测试 ASAR 注入 driver。公开 Release URL 在夹具中映射到本地受控 feed，下载、ZIP 摘要、逐文件校验、保存、正常退出、辅助替换、重启与健康确认仍执行生产代码。测试包声明的 0.7.3 → 0.8.0 仅用于版本断言；没有下载或发布公开新版安装包。

验证创建的真实 SQLite 笔记内容在更新 / 恢复后仍一致，附件、模型、配置与自定义文件逐字节保留，加密助手草稿可跨新实例恢复；旧停机数据库备份存在。启动自动更新在自动检查关闭、未满间隔时仍只检查和下载一次，旧版本不启动 JVM；启动失败恢复后不会自动重新安装同一失败目标。

## 3. 复跑入口

从仓库根目录用 PowerShell 7。前后端全量与 Electron 入口见[测试规范](../../test/TESTING_GUIDELINES.md)，原生与完整应用专项需要 Windows 桌面会话。

```powershell
npm run test:frontend
npm run build
npm run test:backend
npm run test:electron
npm run test:electron:native
npm run test:updater:native
```

完整应用专项还需本地实际 JAR、根目录 `jre/` 和安装的 Rust 工具链。JRE 配方见 [jlink.md](../../jlink.md)；先构建后端，再执行：

```powershell
npm run build:backend
npm run test:updater:desktop
pwsh -NoProfile -File scripts/portable-audit.ps1 -RunScript scripts/test-app-update-desktop.mjs
pwsh -NoProfile -File cache/portable-registry-audit.ps1
```

最后一条静态脚本为本机已有的私人审计工具。完整应用测试在 `temp/update-desktop-<随机>/` 构建，结束清理自身包和数据。Rust 构建产物位于 `updater/target/`，Cargo 缓存位于 `.tooling/cargo/`，均已忽略；测试不写生产 APP_ROOT、不需要 GitHub 登录。

最终轻量日志与证据仅在本地：`temp/app-update-check/frontend-all.log`、`electron-complete.log`、`native-final.log`、`desktop-verified.log`、`desktop-package-check.log`、`build-web-electron.log`、`backend-package.log`；后端全量计数来自 `backend/target/surefire-reports/`；运行审计证据在 `temp/portable-audit-8eff3fc7-c294-4774-9624-1bddcf1128cd/`。最后追加的生产 ASAR 无测试入口断言与手动更新闭环再次通过，临时包自动清理。截图、日志、QA 包、运行数据与测试材料不入库。本轮测试遗留的约 2.8 GiB 失败 / 中止构建材料已在确认无自身进程后清理，根目录 JRE 保留作打包依赖。

## 4. 尚未发布与验收边界

- v0.8.0 尚未发布；功能实现已由用户提交，发布准备已将云端与本地默认版本同步为 0.8.0。本机完整应用验证使用 Java 21 生成的 JRE，源码继续 target 17，云端仍构建 Java 17 JRE；本轮未执行 GitHub Actions 发行构建。
- 尚未验证公开新版本资产到用户真实安装目录的升级、真实 UI 人工观感、不同 DPI / 多显示器 / 读屏。UI 行为有组件测试，实际原生更新与回滚闭环已通过；两者不替代人工视觉验收。
- 保留既有 React / Arco 测试告警及 7 项跳过，未通过屏蔽全局错误获取通过。本轮记忆索引并发用例通过，历史偶发失败根因仍未调查，见 [QA 模块二十六 / 二十八](../../test/QA_CHECKLIST.md#模块二十六上下文反馈与体验完善v08)，不算本切片修复。
- 只支持 Windows x64 目录便携 ZIP，不增加强制更新、安装器、差分补丁或系统常驻更新任务。v0.8.1 混合检索规划保持原版本归属。

通用验收场景维护在 [QA 模块三十](../../test/QA_CHECKLIST.md#模块三十软件内更新与关于v080)。单个切片完成不归档；发布及下一个版本开始仍按[文档维护规则](../../README.md)执行。

## 5. v0.8.0 发布准备（2026-11-12）

[工作流](../../../.github/workflows/release-portable.yml)与[本地打包脚本](../../../scripts/package-portable.ps1)默认版本统一为 v0.8.0，补齐[发行说明](../../release/v0.8.0-release-notes.md)。干净检出中先运行 `npm run build:electron`，生成发布配置夹具所需的清单模块，再运行配置测试和前端构建；Electron 只编译一次。更新助手、程序清单、ZIP 与 SHA-256 仍进入发行产物。

本轮 `node --test scripts/test-release-config.mjs` **10 项全部通过、0 跳过**，覆盖 v0.8.0 / rc 版本与说明选择、默认应用版本和 ZIP 命名、缺失依赖及构建失败阻断；`npm run build:electron` 和 `npm run build:frontend` 均通过，前端保留既有分块大小提示。业务全量与完整应用结果沿用上表。

保持标签触发 Pre-release、`make_latest=false`；手动触发默认只上传产物，开启 `publish_release` 才发布。本轮仅准备工作区文件，没有创建或推送标签，没有执行云端发行；实际发布结果以 GitHub Actions / Release 为准。
