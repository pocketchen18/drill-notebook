# 测试编写与执行规范 (Testing Guidelines)

本文档旨在统一 Drill Notebook 项目的前后端自动化测试标准与质量门禁，所有开发及测试人员均须严格遵守。

---

## 一、 测试分层架构与目录规范

项目遵循测试金字塔模型，分为**单元测试 (Unit Test)**、**组件交互测试 (Component Test)** 与 **后端接口/服务测试 (Integration Test)**。

### 1. 前端测试规范 (React + TypeScript + Vitest)

* **文件就近原则 (Colocation)**：测试文件必须与被测源文件放在同一目录下。
  - 纯函数/工具库：`<name>.test.ts`（例如：`src/lib/knowledgeTree.test.ts`）
  - React 组件：`<ComponentName>.test.tsx`（例如：`src/pages/knowledge/KnowledgeFullCardView.test.tsx`）
* **测试框架与断言**：
  - 测试运行器：`vitest`
  - 组件测试库：`@testing-library/react` + `@testing-library/user-event`
  - 断言库：`vitest` 原生 `expect`
* **覆盖率底线**：
  - 核心业务算法（如树状构建、SM-2 记忆算法、文本解析）：**分支覆盖率 (Branch Coverage) $\ge 90\%$**。
  - 页面级/组件级测试：必须覆盖核心主流程与至少 2 个异常边界状态（如空数据、加载中、错误捕获）。

### 2. 后端测试规范 (Spring Boot + JUnit 5 + Mockito)

* **目录结构**：遵循 Maven 标准测试目录结构 `backend/src/test/java/...`。
* **命名规范**：被测类名 + `Test.java`（例如：`KnowledgePointImportServiceTest.java`）。
* **测试原则**：
  - 单元测试：隔离数据库与外部服务，使用 Mockito 模拟 Repository / AI 外部依赖。
  - 边界测试：空文本、超长文本、特殊转义字符、非法 ID 等异常分支必须显式校验。

---

## 二、 提交前强制质量门禁 (Quality Gates)

在提交任何代码前，本地必须依次执行并全部通过以下三道门禁：

从仓库根目录使用 PowerShell 7 执行：

```powershell
# 门禁 1: 前端自动化测试全量运行
npm test --prefix frontend

# 门禁 2: 前端 TypeScript 类型检查与生产构建
npm run build --prefix frontend

# 门禁 3: 后端单元与集成测试全量运行
npm run test:backend
```

另外执行 `git diff --check` 检查差异格式。最新版本的已执行范围、测试数量和未验收项记录在 [QA_CHECKLIST.md](QA_CHECKLIST.md)，不能把“前端通过”写成“全端门禁通过”。

Electron 主进程有改动时，执行 `npm run test:electron` 和 `npm run build:electron`。入口回归在内存中执行真实 `electron/main.ts`，隔离 Electron / 后端 API；已接入根目录 `npm test`。另用 `npm run test:electron:native` 在 Windows 桌面会话中验证原生单实例锁和窗口恢复：加载重构建后的真实入口 / 路径模块，使用独立工作区 `tmp/` 和后端桩，结束后清理；不运行真实 Java 或发行打包。原生测试不并入默认无桌面测试流程。

发布配置或打包脚本有改动时，额外执行 `node --test scripts/test-release-config.mjs`（Windows + PowerShell 7）。该测试用假打包器验证版本注入、ZIP 命名、非法输入及失败退出，不生成真实发行包。GitHub 发布工作流在 `npm ci` 后执行此测试及前后端全量测试，全部通过才构建、打包、发布；本地 `package:portable` 仍由调用者先完成门禁。

---

## 三、 测试编写准则

1. **测试隔离性 (Isolation)**：每个测试用例必须独立运行，禁止依赖其他用例的执行顺序或残留状态。
2. **拒绝假通过 (No False Positives)**：禁止写只调用函数但不做任何断言（Assertion）的无效测试。
3. **修复 Bug 先写测试 (Test-First Bug Fixing)**：
   - 收到 Bug 报告后，先编写一个能够稳定复现该问题的失败测试（Red Test）。
   - 修复问题使测试通过（Green Test）后，方可完成修复。
4. **Mock 使用节制**：优先测试真实的纯函数输入输出；仅对网络请求、文件 IO、定时器及外部 AI 服务使用 Mock。
5. **编辑器闭环**：涉及节点视图、插入、转换或焦点的修复，必须覆盖 `onChange → 父组件 state → content` 回传。选区 / JSON / DOM / 撤销应保持一致，不能只验证命令返回 true。
6. **焦点与初始光标**：断言真实 `document.activeElement`、`selectionStart/selectionEnd` 及下一次输入落点，覆盖 Enter / Esc / blur、重新编辑、空节点、末尾节点；失焦和用户手选位置不得被延迟聚焦覆盖。
7. **复制与异步边界**：公式复制走实际 `clipboardTextSerializer`，同时测软换行、富文本和统计口径；异步上传覆盖落点映射、光标移动、多文件顺序与编辑器销毁。保存重开的单测应经 JSON 重建，并明确不等于真实持久化验收。
8. **运行结果**：退出码、失败 / 跳过数和 unhandled error 一并记录；React/Arco 开发告警单独说明，不以屏蔽全局错误换取通过。浏览器键盘 / 剪贴板和 Electron 实机结果分别记录。
