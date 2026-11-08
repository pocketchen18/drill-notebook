# v0.7.1 施工归档

2026-11-08 发布准备时完成状态文档同步并归档。这里保存已确认设计、实施过程、历史验证和草图；不作为下一功能的默认上下文，也不替代当前代码与状态文档。

## 已完成切片

| 切片 | 材料 |
|---|---|
| AI 连接：多供应商、多模型、二级设置、预设与思考参数、并发验证、编辑 ID 保留草稿 | [UI/UX](ai-connection-settings/ui-ux.md) · [Spec](ai-connection-settings/spec.md) · [Plan / 验证](ai-connection-settings/plan.md) · [交互草图](ai-connection-settings/preview.html) |
| 记忆工具、工具活动与历史、全局运行限制 | [原始需求](ai-memory-tools/requirements.md) · [UI/UX](ai-memory-tools/ui-ux.md) · [Spec](ai-memory-tools/spec.md) · [Plan / 验证](ai-memory-tools/plan.md) · [活动草图](ai-memory-tools/tool-activity-preview.html) |

原始需求包含未全部交付的范围：语义记忆检索、独立用量详情页面、`list_memories` 分页尚未实现；当前检索仍为词法索引。保留这些需求是为了追溯，不能据此宣称已完成或直接继续施工。v0.8 侧栏的模型/思考档位选择、系统提示词、搜索和自动压缩设置仍是后续讨论；不新增「最近对话保留策略」设置。

各 Plan 中的数字和浏览器记录属于对应日期、对应切片，不能相加或冒充本次全量门禁。截图按用户要求仅保留本地，不上传 Git；HTML 草图继续保留，可用于了解当时设计。

## 当前事实来源

- [功能清单](../../features.md)
- [数据模型（schema 11）](../../data-model.md)
- [REST / SSE 接口契约](../../api-contracts.md)
- [架构](../../architecture.md)与[交互契约](../../../DESIGN.md)
- [QA 清单](../../testing/QA_CHECKLIST.md)
- [v0.7.1 发布说明、验证与升级](../../plans/v0.7.1-release-notes.md)

后续需求变化在新的当前切片中规划，已落地现状覆盖更新上述状态文档；不在本目录追加补充决策。
