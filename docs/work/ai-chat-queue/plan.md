# 当前切片执行计划

依据 [UI/UX](ui-ux.md) 和 [Spec](spec.md)，不提交 Git、不改版本号和发布工作流。

- [x] 加密人工队列存储、输入校验和恢复；范围：schema / DatabaseInitializer、队列 repository/service、相关测试。
- [x] 调度接入暂停、编辑、撤销及保留人工待发；范围：SessionDispatchService/Repository、SessionRuntimeController、测试。
- [x] 接入插话模型请求边界、历史、上下文、跨会话读取及导出；范围：SessionExecution、AiService、相关 repository/service 和测试。
- [x] 实现贴边队列、输入区与会话状态；范围：AiAssistant、useAiChatTurn、新队列组件/hook、SessionNavigation、相关样式和测试。
- [x] 针对性回归、前端类型构建和隔离浏览器验收；停止时序、来源标识、未知提交身份及导出遗漏均已补验。
- [x] 更新当前 UI/UX、Spec、[QA 记录](../../test/QA_CHECKLIST.md#模块十八人工对话排队与插话v072) 和 MEMORY 断点；便携检查只记录实际可验证的范围。
- [ ] 人工体验确认后，同步共享状态文档并归档。本轮遵循用户「先不修改原本状态文档，避免冲突」的约束，未提前完成此项。

验证结果：后端首轮 223 项通过，后续恢复／停止／导出定向复验均通过；前端最终覆盖 99 项（合跑中一项停止时序断言调整后，其所在 20 项全部复验通过）。TypeScript + Vite 和最终开发 JAR 构建通过。具体命令与范围见 QA 记录；未执行业务全量或构建发行包。
