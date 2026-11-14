# 笔记混合检索接入资料工具：规格

> v0.8.2 F01，已实现并通过工程验证（2026-11-14）。需求见[requirements.md](requirements.md)，长期接口以[api-contracts.md §3.2.4](../../state/api-contracts.md)为准。

## 1. 数据与服务边界

不新增 schema、业务表、索引或资料副本。复用 retrieval_chunk、FTS5、embedding_space、retrieval_embedding 及现有 provider 注册与授权。

```text
MaterialReadTools.search_materials
  → MaterialReadService（严格参数、模式、分页、字符 / 字节预算）
    → MaterialSearchService（字面优先、候选合并、页面去重、范围复核）
      → HybridRetrievalService.retrieveCandidates
        → RetrievalService（BM25）
        → 既有 EmbeddingProvider / vector scan（可选）
        → RRF 排名并集
      → MaterialReadRepository.noteSearchTargets（页面归属、分块哈希）
  → 片段与 sourceLink；需要上下文时 read_notebook(pageId)
```

## 2. 参数与路由

新增可选字符串 searchMode，枚举 hybrid/keyword，默认 hybrid；仅 search_materials 的 schema 接受。继续严格拒绝重复键、未知字段、隐式类型转换和无效范围，参数最多 4096 UTF-8 字节。

| 条件 | 路径 |
|---|---|
| 非空 query、mode=hybrid、type=note/all、无 bankId | 新 MaterialSearchService，notebookId 为空则全部笔记本，否则限定本笔记本 |
| mode=keyword | 原有 LIKE 字面查找与目录分页 |
| 空 query | 原有目录，不调用 embedding |
| 其它 type 或有 bankId | 原有业务表关键词查找，不调用 embedding |
| 明确资料 ID 读取 | 原有正文与版本校验，不经过向量层 |

query 最多 200 Unicode 码点；offset 默认 0，范围 0–10,000,000；limit 默认 10、范围 1–20。searchMode 在非空 search_materials 结果中回显请求模式，不表示向量必然参与。

## 3. 排序与去重

1. 查询最多 81 项字面候选，保留前 80 项，并用额外一项判断截断；笔记摘要优先取实际命中分块。
2. BM25 和向量各最多 40 个分块。保留最多 80 个 RRF 排名并集，使用既有 `1/(60+ftsRank)+1/(60+vectorRank)`，不混合原始分数。原 retrieve 的 RRF 融合结果仍最多 10 块，BM25 单独返回保留既有行为。
3. 通过 chunkId 重新查当前页面、笔记本和 chunkHash，核对检索时的 contentHash；页面缺失、移动越范围、孤立或已改写分块不返回。
4. 按 type/id 合并。字面匹配保留原先顺序，重复笔记合并实际 matchTypes；其它候选按最佳存活分块的 RRF 顺序补充，最终最多 80 项。
5. 页面合并后应用 offset/limit；同一调用内相同笔记 snippet 只保留第一次正文，其它来源仍返回并给出 snippetDuplicateOf。

候选分支达到 40 块或资料池发生截断时保守标记 searchLimited。该模式属于有界候选搜索，不能声称全库枚举完整。

## 4. 返回与预算

沿用 `{status,items,offset,hasMore,nextOffset?,incomplete}` 和 type/id/title/parentId/parentTitle/sourceLink 等字段。

笔记搜索额外返回 pageId/notebookId，非空查询含实际 matchTypes（keyword/bm25/vector）；混合补充项可含 chunkId、headingPath。内部哈希不外泄。每个片段最多 240 码点，每页最多 20 项，总片段最多 4,800 码点。

- 达到候选边界：searchLimited=true，incomplete=true，notice 建议缩小范围或改 keyword。
- 当前候选还有项：hasMore=true，nextOffset 指向实际返回项之后；不存在可续页项时不提供 nextOffset。
- UTF-8 目录序列化超过 60 KiB：减少末尾项，设 byteLimited/hasMore/incomplete，并按实际行数调整 nextOffset，维持工具 64 KiB 硬上限。
- 向量降级：返回 BM25 与 retrievalNotice，并设 incomplete=true；无选中向量空间时无 notice。

## 5. 降级、授权与隐私

沿用向量 1500ms 总 deadline 与 provider 500ms 有界就绪等待。选中空间须 ACTIVE、coverage=1.0，provider 的类型、模型与维度须匹配；未就绪、重建、模型不匹配、embed/scan 异常或超时均降级 BM25。超时取消 future；中断保留线程中断标记，服务销毁关闭执行器。

远程 OpenAI/Ollama 继续通过 EmbeddingConfigService 的有效授权注册，不新增直连；变更 endpoint/model 导致授权失效后停止 embedding 请求。keyword / 空 query / ID 读取不发 embedding 请求。日志只记录异常类型，不包含查询或上游正文。

AiService 的工具指引允许片段足够时直接引用，需要前后文再按 ID 读取。工具原始结果不进入活动历史，继续使用现有安全摘要、运行预算、工具开关及取消检查。所有写盘路径沿用 APP_ROOT；本次代码没有新增文件落点。

## 6. 验证映射

核心搜索行为与边界见 MaterialHybridSearchTest；真实模型工具编排见 MaterialToolChatPathTest；远程授权、索引和查询协议桩见 EmbeddingConfigServiceTest。既有词法、融合、索引 / 维护与资料读写回归一起执行，结果见[plan.md](plan.md#3-验证结果)。
