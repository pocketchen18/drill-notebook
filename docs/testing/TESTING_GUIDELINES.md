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

```bash
# 门禁 1: 前端自动化测试全量运行
npm test --prefix frontend

# 门禁 2: 前端 TypeScript 类型检查与生产构建
npm run build --prefix frontend

# 门禁 3: 后端单元与集成测试全量运行
.\mvnw.cmd test
```

---

## 三、 测试编写准则

1. **测试隔离性 (Isolation)**：每个测试用例必须独立运行，禁止依赖其他用例的执行顺序或残留状态。
2. **拒绝假通过 (No False Positives)**：禁止写只调用函数但不做任何断言（Assertion）的无效测试。
3. **修复 Bug 先写测试 (Test-First Bug Fixing)**：
   - 收到 Bug 报告后，先编写一个能够稳定复现该问题的失败测试（Red Test）。
   - 修复问题使测试通过（Green Test）后，方可完成修复。
4. **Mock 使用节制**：优先测试真实的纯函数输入输出；仅对网络请求、文件 IO、定时器及外部 AI 服务使用 Mock。
