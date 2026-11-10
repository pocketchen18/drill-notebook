# 修改学习资料工具规格

> 已实现并完成针对性自动化验证（2026-11-10），真实供应商与 Electron 实机待验收。按已确认的 v0.7.3 范围实现 `update_material`，不实现删除、移动或工具设置。

## 输入与读取版本

必填 `type`（notebook/note/knowledge/bank/question/wrong）、`id`、`operationId`、`expectedVersion`、非空 `changes`。一个调用只修改一个对象。`operationId` 沿用新增工具的稳定操作标识；`expectedVersion` 来自对应读取工具的 `updateVersion`，与正文续读 `version` 分开。

| 类型 | changes 可编辑字段 |
|---|---|
| notebook | title |
| note | title、edits（oldText/newText 精确替换数组）、append（追加 Markdown） |
| knowledge | title、content、category、tags、headingPath、questionIds |
| bank | title、description |
| question / wrong | questionType、stem、options、answer、analysis、difficulty、tags、chapter |

字段缺省保留；允许空字符串清除可选文本、空数组清除列表，拒绝 null、未知字段、重复键及错误类型。标题 / 题干 / 知识点正文不可清空。题型与答案合并原值后复用业务校验，题目仓储只写请求提供的列，避免修改解析时顺带补默认难度或重写旧答案。参数最多 64 KiB，正文 12000 码点，短文本 200，描述 / 选项文本 2000，数组 30、选项 26。笔记 edits 最多 20，每项 oldText 非空，须在可编辑文本叶或 Markdown / 公式 / 代码属性中恰好命中一次；零次或多次返回 CONFLICT，整次回滚。禁止把富文本转换为单块 Markdown 覆盖。append 保留原文档并追加现有 Markdown 块。

对应正文读取返回 `updateVersion`；笔记本 / 题库的目录读取增加 `container` 元数据及版本。知识点仅 current 返回更新版本，原文 / 总结和页面快照不返回。版本对完整原始业务字段与知识点关联计算摘要，不依赖秒级更新时间，不受答题统计变化影响。读取结果仍有界。

## 保存与幂等

- schema 15 → 16，新增 `ai_material_update` 回执表：请求 / 操作标识、来源会话、输入指纹、目标类型 / ID、提交后版本与结果；不保存修改正文。会话删除清回执，资料保留。
- 事务先预占回执获取 SQLite 写锁，验证真实运行句柄、来源轮次 / 代数及取消，再读取并核对版本。对象更新、笔记索引、关联与回执同成同败。
- 相同请求 / 操作参数返回 DUPLICATE；同请求换操作 ID 的完全重复参数也去重；显式 retryOf 链复用回执。参数变更返回 CONFLICT。重试发现后续编辑时仅报告原操作已处理、当前已变化，不重放写入；已删目标不重建。
- `wrong` 仅作用于仍在错题本的原题，作答记录、人工加入基线、掌握状态均不改。知识点保留原文 / 总结、复习与计划数据；各类型不改所属容器或顺序。
- 当前消息 / 插话提交的同一笔记若标记为草稿，返回 DRAFT_UNSAVED；模型应请用户保存后发起新轮次。页面快照与资料中的文字不授权写入。
- 成功返回 UPDATED / NO_CHANGE / DUPLICATE、items、sourceLink 及安全字段名称摘要；普通失败为空 items。结果未知返回 UNAVAILABLE，不谎称写入失败。活动只留结果与来源，前端更新目录并保护编辑草稿。

## 验证

真实 SQLite 测试六类型、字段保留、富文本 / 引用保留、关联 / 索引回滚、错题统计、并发版本冲突、重试及后续编辑、取消 / 草稿 / 删除与迁移；本机模拟上游验证读取→修改→引用链路。前端测试状态、来源、刷新及草稿 / 保存中保护，执行构建。验证结果集中记录 QA，不运行真实供应商或修改用户资料。

结果与未覆盖范围见 [QA 模块二十一](../../test/QA_CHECKLIST.md#模块二十一修改学习资料v073)。实现契约已同步 [状态文档 §3.2.6](../../state/api-contracts.md#326-修改学习资料工具v073)，本目录保留至下一版本开始前归档。
