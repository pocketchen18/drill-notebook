# v0.7.2 文档归档

2026-11-09，在开始 v0.7.3 前完成 v0.7.2 文档归档：补齐人工排队与插话施工材料、草图、本地截图，以及 [该版功能清单](features.md)。此前已归档的会话导航、跨会话查阅与派发材料继续保留。

归档保存该版范围与设计依据；当前架构、数据模型和接口仍在 `docs/state/` 原位维护，已同步 schema 14 与人工排队的实际行为。验证与未覆盖项见 [QA 清单](../../test/QA_CHECKLIST.md) 模块十五至十八及 [发布说明](../../release/v0.7.2-release-notes.md)。

| 切片 | 施工材料 | 完成情况 |
|---|---|---|
| 会话导航与自动标题 | [UI/UX](ai-session-navigation/ui-ux.md)、[交互草图](ai-session-navigation/session-navigation-preview.html)、[Spec](ai-session-navigation/spec.md)、[Plan](ai-session-navigation/plan.md) | 导航 MVP 与未保存的新会话页已人工确认并提交（173b7a4） |
| 两个会话工具与来源预览 | [Spec](ai-session-tools/spec.md)、[Plan](ai-session-tools/plan.md) | 实现与针对性验收完成，已由用户随四个会话工具提交（1072f3e） |
| 会话创建、发送与并行运行 | [UI/UX](ai-session-dispatch/ui-ux.md)、[交互草图](ai-session-dispatch/preview.html)、[Spec](ai-session-dispatch/spec.md)、[Plan](ai-session-dispatch/plan.md) | 两个写工具、队列与并行状态已实现，相关验证完成，已提交（1072f3e） |
| 人工排队与插话 | [UI/UX](ai-chat-queue/ui-ux.md)、[交互草图](ai-chat-queue/chat-queue-preview.html)、[Spec](ai-chat-queue/spec.md)、[Plan](ai-chat-queue/plan.md) | 已实现并完成针对性验收，用户已提交（6342de9）；版本切换前同步状态并归档 |

验收截图只含虚构测试数据，随原目录移动且仍只留本地、不入 Git。归档不代表已发布或新增验收通过；发布前业务全量、真实供应商与发行包的既有待验项保持原记录。v0.7.3 从 [新功能清单](../../state/v0.7.3-features.md) 开始，不能将本目录中的历史“下一步”当成当前任务。
