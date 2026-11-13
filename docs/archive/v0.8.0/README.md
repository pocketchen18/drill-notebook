# v0.8.0 文档归档

2026-11-12，按用户要求在开始 v0.8.1 前归档[本版功能清单](features.md)、两个施工目录及其中的交互草图。AI 助手改版已由用户验收，软件内更新已实现并通过工程验证；归档保留原有验收范围及发布准备记录。

| 切片 | 归档材料 |
|---|---|
| 独立 AI 助手窗口、会话模型、上下文反馈与消息体验 | [UI/UX](ai-window/ui-ux.md)、[Spec](ai-window/spec.md)、[Plan](ai-window/plan.md)、[窗口草图](ai-window/window-frame-preview.html) |
| 软件内更新与关于 | [需求](app-update/requirements.md)、[UI/UX](app-update/ui-ux.md)、[Spec](app-update/spec.md)、[实施与验证](app-update/plan.md)、[更新草图](app-update/app-update-preview.html) |

本目录只作历史参考。各文档中的「当前」、待验收与尚未发布均为施工时记录，归档不补写测试结论，也不代替发行确认。原有跳过项、更新 UI 人工观感、真实供应商、DPI / 多显示器 / 读屏及公开新版升级等验证边界继续保留；记忆索引的历史偶发问题未查明，不因后续测试通过记为已修复。

原记录及草图中的 v0.8.1 向量检索安排属于当时规划或示例。用户于本次版本切换明确将**读取工具接通笔记向量检索顺延至 v0.8.2**；既有 embedding RAG 保留，当前决定见[全局混合检索规划](../../features.md#笔记混合检索接入资料工具计划-v082)。

长期架构、数据模型与接口继续在 `docs/state/` 原位维护；[发行说明](../../release/v0.8.0-release-notes.md)留在 `docs/release/`，[通用测试与历史结果](../../test/QA_CHECKLIST.md)留在 `docs/test/`。本地日志、截图、审计与隔离测试材料继续按实施记录保留在忽略目录，不新增入库副本。实际发行状态以标签、GitHub Actions 与 Release 为准。

当前从 [v0.8.1 功能清单](../../state/v0.8.1-features.md)开始，范围为失败重连和会话内对话跳转。
