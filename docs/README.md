# 文档体系

本项目采用规范驱动开发（SDD）与敏捷纵向切片。文档按生命周期分成三类，各自只回答一个问题：

| 类型 | 回答的问题 | 生命周期 |
|---|---|---|
| 状态文档 | 当前架构、契约与本版需求推进到哪里 | 已落地事实原位更新；版本清单区分规划与完成状态 |
| 施工文档 | 当前功能怎么做 | 只服务当前切片，完成后归档 |
| 交接记忆 | 下一任接手者从哪里继续 | 根目录 `MEMORY.md`，不入库，保持精简 |

目录按以下约定组织：

```text
docs/
├── README.md          文档入口与维护规则
├── DESIGN.md          全局视觉、交互与编辑器设计规范
├── features.md        全局功能清单，版本发布前同步实际交付能力
├── jlink.md           便携包嵌入 JRE 说明
├── state/             各版本功能需求清单、数据模型、接口契约与架构
├── work/<feature>/    当前切片的需求、UI/UX、Spec、Plan 与草图
├── test/              测试规范、验收清单与缺陷模板
├── reference/         各领域专题说明与历史参考
├── release/           各版本发布说明
└── archive/<version>/ 已完成切片的施工文档与验收证据
```

根目录 [README.md](../README.md) 保留项目入口，[AGENTS.md](../AGENTS.md) 保留代理规则入口，`MEMORY.md` 保留私人交接记忆。`docs/DESIGN.md` 与 `docs/features.md` 是全局文档，不放入 `docs/state/`。

## 1. 状态文档

`docs/state/` 保存各版本功能需求清单及当前架构、数据模型、接口契约。版本清单按 `v<版本>-features.md` 命名，记录该版目标、功能切片、范围、Non-Goals 与推进状态，明确区分待设计、开发中和已验收；架构、数据模型与接口契约只记录**已真实落地并验证**的内容。

| 文档 | 内容 |
|---|---|
| [state/architecture.md](state/architecture.md) | 项目架构、进程与模块边界、数据与便携约定 |
| [state/v0.7.2-features.md](state/v0.7.2-features.md) | v0.7.2 功能需求清单：AI 会话标题与跨会话查阅，当前 UI/UX 草案待确认 |
| [state/data-model.md](state/data-model.md) | 当前数据库表与关键约束（schema 11）；完整 DDL 以 `backend/src/main/resources/schema.sql` 为准 |
| [state/api-contracts.md](state/api-contracts.md) | 核心 REST / SSE 契约与错误格式 |

版本启动时先在 `docs/state/` 编写该版功能需求清单，开发期间在原文档维护范围与状态，不把本版需求提前并入全局清单。切片验收完成后更新该版清单，同步已落地的数据结构、接口等状态文档，再归档施工文档；涉及全局设计规范或专题说明时原位维护。

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
3. 功能验收完成、状态文档同步后，整个目录移入 `docs/archive/<version>/`，不留在活跃上下文。

当前活跃施工文档为 [v0.7.2 会话列表与菜单 UI/UX](work/ai-session-navigation/ui-ux.md)，同目录包含交互草图，待人工确认后再补当前切片 Spec / Plan；需求范围见[版本清单](state/v0.7.2-features.md)。v0.7.1 已完成切片的材料归档至 [archive/v0.7.1/](archive/v0.7.1/)；v0.8 的侧栏与设置讨论尚未进入施工。

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

`docs/release/v<版本>-release-notes.md` 保存各版发行记录，当前为 [v0.7.1 发布说明](release/v0.7.1-release-notes.md)。工作流按标签的完整基础版本选择正文；例如 `v0.7.1-rc.1` 选择 v0.7.1，缺失或空白即失败。

## 5. 交接记忆

根目录 `MEMORY.md`（已 gitignore，不入库）。只写三类信息：

- **当前断点**：正在推进的功能、施工文档位置、代码半成品状态、未完成事项。
- **经验教训**：尝试过但报错、不可行或被人工否决的方案，避免接手者重蹈覆辙。
- **人工决策**：人工敲定且仍然有效的产品/工程决定。

禁止把状态文档的数据结构、施工文档的需求与步骤抄进 `MEMORY.md`，禁止写成只增不减的流水账。切换模型或工具前更新；新会话优先读它。

## 6. 归档

`docs/archive/<version>/` 保存已完成版本的施工文档与验收证据，只读，不作为当前事实来源。v0.7.1 的入口、范围与后置项目见 [归档说明](archive/v0.7.1/README.md)；更早归档保留其历史口径。

归档目录结构与历史结论保持不变；文档迁移时只修正指向现行文档的链接与路径。

## 7. 维护规则

1. **单一事实来源**：需求或实现变化时直接修改原文档；不靠新增补丁文档制造第二套事实。
2. **全局与版本分离**：本版功能需求先写入 `docs/state/v<版本>-features.md`，发布前才按实际验收结果更新 `docs/features.md`；不在两处重复维护本版需求。
3. **完成即归档**：功能完成后先同步状态文档，再归档施工文档。
4. **链接可追踪**：文档使用相对路径；移动文件时同步更新索引与引用。
5. **状态与施工不重复**：版本范围与推进状态维护在版本清单，已落地架构、数据结构与接口契约维护在对应状态文档；施工文档只写当前切片的具体需求、设计与执行步骤，通过链接引用版本清单与状态文档。
6. **目录与跟踪一致**：新增文档按上述目录分类，迁移后同步相对链接、文档索引与 `.gitignore`；截图继续仅保留本地。
