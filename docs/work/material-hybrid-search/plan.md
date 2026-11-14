# 笔记混合检索接入资料工具：实施与验证

> v0.8.2 F01，已完成（2026-11-14）。需求、界面与服务边界见[requirements.md](requirements.md)、[ui-ux.md](ui-ux.md)、[spec.md](spec.md)。

## 1. 实施结果

- [x] 确认既有笔记 RAG、资料工具、授权、活动与便携路径，沿用已规划的笔记范围。
- [x] 为 search_materials 增加 hybrid/keyword 模式，保持未知参数和范围的严格校验。
- [x] 新增 MaterialSearchService，复用 BM25 / 可选向量 / RRF 的有界排名并集，字面优先，按页合并后分页。
- [x] 返回匹配片段、真实标题、页面 / 笔记本 ID 与来源，复核分块哈希与页面归属，去重重复笔记正文。
- [x] 保持向量故障降级，补齐 provider 类型 / 模型一致性、异常与中断处理和执行器生命周期。
- [x] 保留空目录、精确字面查找、其它资料与 ID 读取；助手可直接引用充分片段，按需读取上下文。
- [x] 复现 Unicode 元数据越过 64 KiB 的问题；以失败测试验证后加入 60 KiB 软预算及不丢项续页。
- [x] 通过相关前后端回归、隔离构建、运行时落盘与静态便携审计；同步开发 JAR。
- [x] 更新全局 / 版本清单、接口、架构、专题、文档入口与 QA。

## 2. 修改范围

主要代码位于 backend 的 MaterialSearchService、MaterialReadService、MaterialReadTools、HybridRetrievalService、MaterialReadRepository、AiService，以及 frontend 的 aiToolSettings.ts 工具说明。没有数据库迁移、运行时路径或发行配置变更。

新增 MaterialHybridSearchTest 与真实工具装配辅助 MaterialReadTestSupport，扩展聊天链路和远程配置测试，相关资料工具测试改用真实装配。RetrievalMaintenanceEvidenceTest 的空间 B 夹具改为使用该空间声明的模型，保留原有维护断言。

## 3. 验证结果

| 验证 | 结果与覆盖 |
|---|---|
| 后端相关回归 | 16 类 / 202 项：201 通过、0 失败、0 错误、1 项既有跳过 |
| MaterialHybridSearchTest | 17 项通过；覆盖语义 provider 桩、字面优先、模式、范围、同页 / 片段去重、候选上限、字符 / 字节预算与续页、旧分块及向量降级 |
| MaterialToolChatPathTest | 14 项通过；包含 Chat Completions 普通 / 流式和 Anthropic 的混合搜索 → ID 读取 → 回答，现有页面、创建和修改链路继续通过 |
| EmbeddingConfigServiceTest | 13 项通过；包含 OpenAI / Ollama 协议桩的授权保存 → 后台索引 → 工具查询；keyword 不外发，授权失效停止查询 |
| 前端相关回归 | pageSnapshot、AiAssistant、EmbeddingSettingsCard、AiToolSettingsPanel，4 文件 / 38 项通过，无失败 / 跳过 |
| 前端构建 | TypeScript / Vite 通过，隔离输出到工作区 temp/，不覆盖现有 dist |
| 后端打包与同步 | Boot 可执行 JAR 构建通过；647 个应用 class 与同轮编译逐一一致，开发包 SHA-256 校验一致，保留此前生成中 upstream_error 恢复修复 |
| 运行时便携审计 | 测试烟测退出 0；禁用目录新文件 0，RAG 监控根外新建 / 改写文件 0 |
| 静态便携审计 | 退出 0；唯一环境变量模式命中是既有 build-updater.ps1 的 Process 级恢复，符合进程级例外；Electron 路径重定向仍在 |

后端汇总包括 AiServiceChatRetrievalTest、DeleteToolChatPathTest、EmbeddingConfigServiceTest、EmbeddingJobExecutorTest、EmbeddingModelServiceTest、HybridRetrievalServiceTest、MaterialCreateToolTest、MaterialDeleteToolTest、MaterialHybridSearchTest、MaterialReadToolsTest、MaterialToolChatPathTest、MaterialUpdateToolTest、NoteIndexingServiceTest、RetrievalMaintenanceEvidenceTest、RetrievalMaintenanceServiceTest、RetrievalServiceTest。

唯一跳过项为 EmbeddingModelServiceTest.interruptedDownloadResumesWithRangeVerifiesAndActivates：其既有条件要求 model_optimized.onnx 真实模型夹具，当前环境未提供；没有把该项计为通过，也没有为本次工具接入自动下载模型。

本轮临时脚本、夹具、报告与构建产物位于 temp/material-vector-tool-check/，被 Git 忽略。运行时审计证据位于 temp/portable-audit-63d8012a-c88e-4188-811f-3cb53340a2ef/。开发包同步至 backend/target/drill-notebook-backend-0.1.0.jar，未终止用户正在运行的软件；完全退出后重新打开才会加载新后端。

## 4. 复验入口与边界

保留的临时脚本可在当前工作区复验（不作为入库依赖）：

```powershell
pwsh -NoProfile -File temp/material-vector-tool-check/run-backend.ps1 -Tests 'AiServiceChatRetrievalTest,DeleteToolChatPathTest,EmbeddingConfigServiceTest,EmbeddingJobExecutorTest,EmbeddingModelServiceTest,HybridRetrievalServiceTest,MaterialCreateToolTest,MaterialDeleteToolTest,MaterialHybridSearchTest,MaterialReadToolsTest,MaterialToolChatPathTest,MaterialUpdateToolTest,NoteIndexingServiceTest,RetrievalMaintenanceEvidenceTest,RetrievalMaintenanceServiceTest,RetrievalServiceTest'
python temp/material-vector-tool-check/verify.py reports
pwsh -NoProfile -File temp/material-vector-tool-check/run-portable-audit.ps1
```

本轮没有执行全项目全量门禁、真实本地模型下载、真实供应商准确度 / Token 对照、Electron 实机和完整发行打包 / 发布。已有 React / Arco、Vite 体积和 JDK / Mockito 开发告警不属于新测试失败。接入完成不代表任何固定准确度或 Token 节省幅度，既有发行事实与历史验收边界保留。
