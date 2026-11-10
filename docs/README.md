# 文档体系

本项目采用规范驱动开发（SDD）与敏捷纵向切片。文档按生命周期分成三类，各自只回答一个问题：

| 类型 | 回答的问题 | 生命周期 |
|---|---|---|
| 状态文档 | 当前架构、契约与本版需求推进到哪里 | 已落地事实原位更新；版本清单区分规划与完成状态 |
| 施工文档 | 当前功能怎么做 | 按当前切片编写，保留到下一版本开始前统一归档 |
| 交接记忆 | 下一任接手者从哪里继续 | 根目录 `MEMORY.md`，不入库，保持精简 |

目录按以下约定组织：

```text
docs/
├── README.md          文档入口与维护规则
├── DESIGN.md          全局视觉、交互与编辑器设计规范
├── features.md        全局功能清单，版本发布前同步实际交付能力
├── jlink.md           便携包嵌入 JRE 说明
├── state/             当前版本功能需求清单、数据模型、接口契约与架构
├── work/<feature>/    当前切片的需求、UI/UX、Spec、Plan 与草图
├── test/              测试规范、验收清单与缺陷模板
├── reference/         各领域专题说明与历史参考
├── release/           各版本发布说明
└── archive/<version>/ 上一版本功能清单、施工文档与验收证据
```

根目录 [README.md](../README.md) 保留项目入口，[AGENTS.md](../AGENTS.md) 保留代理规则入口，`MEMORY.md` 保留私人交接记忆。`docs/DESIGN.md` 与 `docs/features.md` 是全局文档，不放入 `docs/state/`。

## 1. 状态文档

`docs/state/` 保存当前版本功能需求清单及长期维护的架构、数据模型、接口契约。版本清单按 `v<版本>-features.md` 命名，记录该版目标、功能切片、范围、Non-Goals 与推进状态，明确区分待设计、开发中和已验收；架构、数据模型与接口契约只记录**已真实落地并验证**的内容。下一版本开始前，上一版清单移入对应归档，长期状态文档继续原位维护。

| 文档 | 内容 |
|---|---|
| [state/architecture.md](state/architecture.md) | 项目架构、进程与模块边界、数据与便携约定 |
| [state/v0.7.3-features.md](state/v0.7.3-features.md) | 当前版本：界面清理、资料读取 / 新增 / 修改、删除工具、全部内置工具开关、模型容量必填已实现；真实供应商与 Electron 实机待验收 |
| [state/data-model.md](state/data-model.md) | 当前数据库表与关键约束（schema 17）；完整 DDL 以 `backend/src/main/resources/schema.sql` 为准 |
| [state/api-contracts.md](state/api-contracts.md) | 核心 REST / SSE 契约与错误格式 |

版本启动前先收尾并归档上一版材料，再在 `docs/state/` 编写新版功能需求清单。开发期间原位维护范围与状态，不把规划提前写成全局已交付能力。切片验收完成后更新该版清单，同步已落地的数据结构、接口等状态文档；施工材料继续保留在 `docs/work/`，标明完成状态，不因单个功能完成立即归档。涉及全局设计规范或专题说明时原位维护。

**版本发布前**，依据最终验收结果将实际交付能力更新到 [全局功能清单](features.md)，未交付项保持真实状态。全局清单概括产品能力，版本清单保留该版范围与完成情况，详细设计和执行步骤仅放在施工文档。

## 2. 施工文档

只针对当前版本功能需求清单中**正在推进的功能**编写，放在 `docs/work/<feature>/`：

| 文档 | 内容 |
|---|---|
| `requirements.md` | 当前切片的功能需求：目标、范围、验收口径、Non-Goals |
| `ui-ux.md` | 页面结构、表单字段、交互状态流、验收表现；需人工审阅确认 |
| `spec.md` | 基于确认后的 UI/UX 补齐的最小数据变更、API 契约与后端核心逻辑 |
| `plan.md` | 颗粒度清晰、限制修改文件范围、可逐步验证的执行清单 |

规则：

1. 先定需求，再定 UI/UX；人工确认后再写后端 spec 与 plan。
2. 需求变更直接原地覆盖，不新增「补充决策」文档。
3. 功能完成后标记结果并同步状态，施工材料保留至本版收尾；**下一版本开始前**统一移入 `docs/archive/<version>/`。归档时保留真实未完成项和验收限制，不以归档代替验收。

v0.7.2 的版本清单、会话导航、四个会话工具、人工排队与插话材料已在开始 v0.7.3 前整理至 [版本归档](archive/v0.7.2/README.md)。v0.7.3 当前施工材料包括[上下文卡片与入口清理](work/ai-chat-entry-cleanup/ui-ux.md)、[资料读取工具](work/material-read-tools/spec.md)、[新增资料工具](work/material-create-tool/spec.md)、[修改资料工具](work/material-update-tool/spec.md)、[删除工具与全局工具设置](work/delete-tools/spec.md)及[模型容量必填](work/model-required-limits/spec.md)，均保留至下一版开始前归档；推进状态见 [版本清单](state/v0.7.3-features.md)。既有验证与未覆盖项见[测试清单](test/QA_CHECKLIST.md)和 [v0.7.2 发布说明](release/v0.7.2-release-notes.md)。v0.8 独立 AI 窗口与联网搜索配置尚未进入施工。

## 3. 测试文档

测试规范、可复用验收清单与缺陷模板统一放在 `docs/test/`，测试源码仍留在各模块源码目录。

| 文档 | 内容 |
|---|---|
| [test/TESTING_GUIDELINES.md](test/TESTING_GUIDELINES.md) | 测试编写规范与提交前质量门禁 |
| [test/QA_CHECKLIST.md](test/QA_CHECKLIST.md) | 功能验收与回归测试手册 |
| [test/BUG_REPORT_TEMPLATE.md](test/BUG_REPORT_TEMPLATE.md) | 缺陷报告模板 |

已归档的版本验收记录继续随该版本保留在 `docs/archive/`，不搬入通用测试目录。

## 4. 全局文档、专题与历史说明

全局功能清单 `features.md`、设计规范 `DESIGN.md`、便携说明 `jlink.md` 与文档入口 `README.md` 一起放在 `docs/` 根目录。各领域专题说明与历史参考集中到 `docs/reference/`，由状态文档按需引用：

| 文档 | 内容 |
|---|---|
| [DESIGN.md](DESIGN.md) | 全局视觉、交互与编辑器契约 |
| [features.md](features.md) | 全局产品能力、后续方向与长期边界；版本发布前同步实际交付能力 |
| [jlink.md](jlink.md) | 便携包嵌入 JRE |
| [reference/ai-retrieval.md](reference/ai-retrieval.md) | 笔记本混合检索（RAG）：schema v8、API、worker 协议、降级与索引维护 |
| [reference/ai-summary.md](reference/ai-summary.md) | AI 总结知识点：双角色快照、REST API、前端交互 |
| [reference/review-srs.md](reference/review-srs.md) | 间隔重复复习：配置、调度、今日队列、日历叠加 |
| [reference/session-curve.md](reference/session-curve.md) | 会话内短周期记忆曲线与循环背诵 |
| [reference/import-formats.md](reference/import-formats.md) | PDF / JSON 等导入格式 |
| [reference/knowledge-point-import.md](reference/knowledge-point-import.md) | 知识点 Markdown 导入 |
| [reference/quiz-markdown-flow.md](reference/quiz-markdown-flow.md) | 刷题 Markdown 解析与渲染 |

`docs/release/v<版本>-release-notes.md` 保存各版发行记录，当前发布准备为 [v0.7.2 发布说明](release/v0.7.2-release-notes.md)。**v1.0 之前均为不保证稳定性的预发行版**，没有预发行后缀也不代表稳定。工作流固定使用 Pre-release，不标记 Latest；正式发行渠道在 v1.0 时另行确认。正文按标签的完整基础版本选择，例如 `v0.7.2-rc.1` 选择 v0.7.2，缺失或空白即失败。

## 5. 交接记忆

根目录 `MEMORY.md`（已 gitignore，不入库）。只写三类信息：

- **当前断点**：正在推进的功能、施工文档位置、代码半成品状态、未完成事项。
- **经验教训**：尝试过但报错、不可行或被人工否决的方案，避免接手者重蹈覆辙。
- **人工决策**：人工敲定且仍然有效的产品/工程决定。

禁止把状态文档的数据结构、施工文档的需求与步骤抄进 `MEMORY.md`，禁止写成只增不减的流水账。切换模型或工具前更新；新会话优先读它。

## 6. 归档

`docs/archive/<version>/` 在下一版本开始前接收上一版的功能清单（`features.md`）、施工文档、草图与版本验收证据。长期架构、数据模型、接口留在 `docs/state/` 原位更新，发布说明留在 `docs/release/`，通用测试文档留在 `docs/test/`，不复制成第二份事实来源。v0.7.2 的入口见 [归档说明](archive/v0.7.2/README.md)。

归档后只作历史参考，不自动加载为当前施工依据；已经提前归档的旧切片不来回搬动，本次补齐上一版材料即可。迁移时修正相对链接和状态说明，保留历史测试结论、未完成验收与风险，不把 PARTIAL 改为 PASS。截图仍只留本地。

## 7. 维护规则

1. **单一事实来源**：需求或实现变化时直接修改原文档；不靠新增补丁文档制造第二套事实。
2. **全局与版本分离**：本版功能需求先写入 `docs/state/v<版本>-features.md`，发布前才按实际验收结果更新 `docs/features.md`；不在两处重复维护本版需求。
3. **版本切换前归档**：单个功能完成时同步状态并标记施工结果；下一版本开始前统一归档上一版功能清单与施工材料。
4. **链接可追踪**：文档使用相对路径；移动文件时同步更新索引与引用。
5. **状态与施工不重复**：版本范围与推进状态维护在版本清单，已落地架构、数据结构与接口契约维护在对应状态文档；施工文档只写当前切片的具体需求、设计与执行步骤，通过链接引用版本清单与状态文档。
6. **目录与跟踪一致**：新增文档按上述目录分类，迁移后同步相对链接、文档索引与 `.gitignore`；截图继续仅保留本地。
