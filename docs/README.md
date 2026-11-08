# 文档体系

本项目采用规范驱动开发（SDD）与敏捷纵向切片。文档按生命周期分成三类，各自只回答一个问题：

| 类型 | 回答的问题 | 生命周期 |
|---|---|---|
| 状态文档 | 项目现在是什么样 | 长期保留，随已落地事实覆盖更新 |
| 施工文档 | 当前功能怎么做 | 只服务当前切片，完成后归档 |
| 交接记忆 | 下一任接手者从哪里继续 | 根目录 `MEMORY.md`，不入库，保持精简 |

## 1. 状态文档

状态文档只写**已真实落地并验证**的内容；功能清单可以保留当前规划摘要用于指路，完整需求与步骤放在施工文档。

| 文档 | 内容 |
|---|---|
| [../README.md](../README.md) | 项目定位、技术栈、功能说明、操作逻辑、开发命令、约束 |
| [../AGENTS.md](../AGENTS.md) | AI 代理工作约束：文件权限、PowerShell、便携落盘、提交边界 |
| [../DESIGN.md](../DESIGN.md) | 视觉与交互契约：设计令牌、布局、组件与编辑约定 |
| [features.md](features.md) | 功能清单：当前 MVP 核心闭环、当前规划摘要、后续候选、Non-Goals；历史版本见 README §1 |
| [data-model.md](data-model.md) | 当前数据库表与关键约束（schema 11）；完整 DDL 以 `backend/src/main/resources/schema.sql` 为准 |
| [api-contracts.md](api-contracts.md) | 核心 REST / SSE 契约与错误格式 |
| [architecture.md](architecture.md) | 进程边界、前端分层、后端领域、便携与测试 |
| [ai-retrieval.md](ai-retrieval.md) | 笔记本混合检索（RAG）：schema v8、API、worker 协议、降级与索引维护 |
| [ai-summary.md](ai-summary.md) | AI 总结知识点：双角色快照、REST API、前端交互 |
| [review-srs.md](review-srs.md) | 间隔重复复习：配置、调度、今日队列、日历叠加 |
| [session-curve.md](session-curve.md) | 会话内短周期记忆曲线与循环背诵 |
| [import-formats.md](import-formats.md) | PDF / JSON 等导入格式 |
| [knowledge-point-import.md](knowledge-point-import.md) | 知识点 Markdown 导入 |
| [quiz-markdown-flow.md](quiz-markdown-flow.md) | 刷题 Markdown 解析与渲染 |
| [jlink.md](jlink.md) | 便携包嵌入 JRE |
| [testing/QA_CHECKLIST.md](testing/QA_CHECKLIST.md) | 功能验收与回归测试手册 |
| [testing/TESTING_GUIDELINES.md](testing/TESTING_GUIDELINES.md) | 测试编写规范与提交前质量门禁 |
| [testing/BUG_REPORT_TEMPLATE.md](testing/BUG_REPORT_TEMPLATE.md) | 缺陷报告模板 |

其中 README / AGENTS / DESIGN 是全局背景与规范；features / data-model / api-contracts 与各领域文档记录已落地现状。

功能完成时，先把最终落地数据结构与接口同步到 `data-model.md` / `api-contracts.md` / 对应领域文档，再归档施工文档。

## 2. 施工文档

只针对功能清单中**当前正在推进的功能**编写，放在 `docs/work/<feature>/`：

| 文档 | 内容 |
|---|---|
| `requirements.md` | 当前版本的功能需求：目标、范围、验收口径、Non-Goals |
| `ui-ux.md` | 页面结构、表单字段、交互状态流、验收表现；需人工审阅确认 |
| `spec.md` | 基于确认后的 UI/UX 补齐的最小数据变更、API 契约与后端核心逻辑 |
| `plan.md` | 颗粒度清晰、限制修改文件范围、可逐步验证的执行清单 |

规则：

1. 先定需求，再定 UI/UX；人工确认后再写后端 spec 与 plan。
2. 需求变更直接原地覆盖，不新增「补充决策」文档。
3. 功能验收完成、状态文档同步后，整个目录移入 `docs/archive/<version>/`，不留在活跃上下文。

当前没有活跃功能施工文档。v0.7.1 已完成切片的材料归档至 [archive/v0.7.1/](archive/v0.7.1/)；v0.8 的侧栏与设置讨论尚未进入施工。

## 3. 交接记忆

根目录 `MEMORY.md`（已 gitignore，不入库）。只写三类信息：

- **当前断点**：正在推进的功能、施工文档位置、代码半成品状态、未完成事项。
- **经验教训**：尝试过但报错、不可行或被人工否决的方案，避免接手者重蹈覆辙。
- **人工决策**：人工敲定且仍然有效的产品/工程决定。

禁止把状态文档的数据结构、施工文档的需求与步骤抄进 `MEMORY.md`，禁止写成只增不减的流水账。切换模型或工具前更新；新会话优先读它。

## 4. 归档

`docs/archive/<version>/` 保存已完成版本的施工文档与验收证据，只读，不作为当前事实来源。v0.7.1 的入口、范围与后置项目见 [归档说明](archive/v0.7.1/README.md)；更早归档保留其历史口径。

`docs/plans/v<版本>-release-notes.md` 保存各版发行记录，旧版原文保留。当前为 [v0.7.1 发布说明](plans/v0.7.1-release-notes.md)。工作流按标签的完整基础版本选择正文；例如 `v0.7.1-rc.1` 选择 v0.7.1，缺失或空白即失败。发布配置回归覆盖此选择规则，不能只在本地存在而被 `.gitignore` 忽略。

## 5. 维护规则

1. **单一事实来源**：需求或实现变化时直接修改原文档；不靠新增补丁文档制造第二套事实。
2. **状态与施工分离**：状态文档只记录已落地事实；施工文档记录当前切片的计划与取舍。
3. **完成即归档**：功能完成后先同步状态文档，再归档施工文档。
4. **链接可追踪**：文档使用相对路径；移动文件时同步更新索引与引用。
5. **状态与施工不重复**：已落地的能力、数据结构、接口契约与实时进度只维护在状态文档；施工文档只写当前切片的需求、设计与执行步骤，通过链接引用状态文档，不复制状态表、验证结果或进度。
