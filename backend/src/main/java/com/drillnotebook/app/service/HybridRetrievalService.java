package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hybrid BM25 + vector retrieval with Reciprocal Rank Fusion.
 *
 * <p>Fusion contract (frozen):
 * <ul>
 *   <li>Vector search runs only when the selected embedding space is
 *       {@code ACTIVE} with 100% coverage and the registered provider is
 *       healthy with matching dimensions. Vectors are filtered by
 *       corpus/scope/space/dimensions and current chunk {@code content_hash}.
 *   <li>Similarity is a plain dot product over Java-L2-normalized float32
 *       vectors; top {@value #VECTOR_TOP_K} kept via a bounded min-heap.
 *   <li>{@code score = 1/(60+ftsRank) + 1/(60+vectorRank)}; a hit present in
 *       only one ranking still gets that one term. Ties break by
 *       {@code source_id} then {@code chunk_index} ascending.
 *   <li>At most {@value #MAX_RESULTS} fused hits are returned (the chat
 *       layer applies the 12,000-char context budget).
 *   <li>Any vector failure (no ACTIVE space, provider missing/unhealthy,
 *       worker crash, deadline reached) degrades to BM25-only with notice
 *       {@code vector-index-unavailable}; retrieval never throws for vector
 *       reasons. Raw BM25 and dot-product scores are never mixed.
 * </ul>
 */
@Service
public class HybridRetrievalService {

    static final int VECTOR_TOP_K = 40;
    static final int RRF_K = 60;
    static final int MAX_RESULTS = 10;

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final RetrievalService lexical;
    private final RetrievalIndexRepository retrievalIndex;
    private final EmbeddingJobRepository jobs;
    private final EmbeddingProviderRegistry providers;
    /** Hard service-layer deadline for the whole vector branch (embed + scan). */
    private final long totalTimeoutMs;
    /** Bounded wait for a provider that reports unavailable (worker warming up). */
    private final long workerReadyTimeoutMs;

    private final ExecutorService vectorExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hybrid-vector-search");
        t.setDaemon(true);
        return t;
    });

    public HybridRetrievalService(
            RetrievalService lexical,
            RetrievalIndexRepository retrievalIndex,
            EmbeddingJobRepository jobs,
            EmbeddingProviderRegistry providers,
            @Value("${drill.retrieval.hybrid.timeout-ms:1500}") long totalTimeoutMs,
            @Value("${drill.retrieval.hybrid.worker-ready-ms:500}") long workerReadyTimeoutMs) {
        this.lexical = lexical;
        this.retrievalIndex = retrievalIndex;
        this.jobs = jobs;
        this.providers = providers;
        this.totalTimeoutMs = totalTimeoutMs;
        this.workerReadyTimeoutMs = workerReadyTimeoutMs;
    }

    /** Hybrid result: fused hits plus an optional degrade notice. */
    public record Result(List<RetrievalHit> hits, Map<String, Object> notice) {}

    public Result retrieve(RetrievalQuery query) {
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        List<RetrievalHit> bm25 = lexical.retrieve(query);

        String queryText = RetrievalService.truncateCodepoints(
                query.text(), RetrievalService.MAX_QUERY_CODEPOINTS);
        String normalized = RetrievalService.normalizeQuery(queryText);
        if (normalized.isEmpty()) return new Result(List.of(), null);

        VectorOutcome vector = tryVectorSearch(query, queryText, deadline);
        if (vector.ranks == null) {
            return new Result(bm25, vector.noticeOrNull());
        }
        return new Result(fuseAndMaterialize(bm25, vector.ranks, normalized), null);
    }

    // ── Vector branch ───────────────────────────────────────────────────────

    /** One vector hit ordered by descending dot product. */
    record VectorRanked(long chunkId, long sourceId, int chunkIndex, double score) {}

    private record VectorOutcome(List<VectorRanked> ranks, boolean degraded) {
        static final VectorOutcome NOT_CONFIGURED = new VectorOutcome(null, false);
        static final VectorOutcome DEGRADED = new VectorOutcome(null, true);

        Map<String, Object> noticeOrNull() {
            if (!degraded) return null;
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("code", "vector-index-unavailable");
            notice.put("message", "向量索引暂不可用，本次仅使用关键词检索");
            return notice;
        }
    }

    private VectorOutcome tryVectorSearch(
            RetrievalQuery query, String queryText, long deadline) {
        Map<String, Object> space = jobs.findSelectedSpaceAnyState();
        // No embedding space was ever selected: BM25 is the full contract.
        if (space == null) return VectorOutcome.NOT_CONFIGURED;

        String state = (String) space.get("state");
        double coverage = ((Number) space.get("coverage")).doubleValue();
        if (!"ACTIVE".equals(state) || coverage < 1.0) return VectorOutcome.DEGRADED;

        int dimensions = ((Number) space.get("dimensions")).intValue();
        String spaceId = (String) space.get("embedding_space_id");
        EmbeddingProvider provider = providers.active();
        if (provider == null || provider.dimensions() != dimensions) {
            return VectorOutcome.DEGRADED;
        }
        if (!awaitProviderReady(provider, deadline)) return VectorOutcome.DEGRADED;

        Long corpusId = query.scope() == RetrievalQuery.Scope.CURRENT
                ? query.notebookId() : null;
        String corpusType = query.corpus().name();
        Future<List<VectorRanked>> future = vectorExecutor.submit(() -> {
            List<Float> raw = provider.embedQuery(queryText);
            // encode() validates count/NaN/Inf/zero-norm and L2-normalizes.
            float[] queryVector = EmbeddingVectorCodec.decode(
                    EmbeddingVectorCodec.encode(raw, dimensions), dimensions);
            return scanTopK(corpusType, corpusId, spaceId, dimensions, queryVector);
        });
        long remaining = deadline - System.currentTimeMillis();
        try {
            return new VectorOutcome(
                    future.get(Math.max(1, remaining), TimeUnit.MILLISECONDS), false);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.warn("向量检索超过 {}ms deadline，本次降级为 BM25-only", totalTimeoutMs);
            return VectorOutcome.DEGRADED;
        } catch (Exception error) {
            // Never logs query text or note content.
            log.warn("向量检索失败，本次降级为 BM25-only：{}", error.getMessage());
            return VectorOutcome.DEGRADED;
        }
    }

    /** Poll a not-yet-available provider briefly instead of failing instantly. */
    private boolean awaitProviderReady(EmbeddingProvider provider, long deadline) {
        long waitUntil = Math.min(deadline, System.currentTimeMillis() + workerReadyTimeoutMs);
        while (!provider.isAvailable()) {
            if (System.currentTimeMillis() >= waitUntil) return false;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Full scan with a bounded min-heap: keeps the best {@code VECTOR_TOP_K}
     * rows by dot product, ties by {@code source_id}/{@code chunk_index}.
     */
    private List<VectorRanked> scanTopK(
            String corpusType, Long corpusId, String spaceId,
            int dimensions, float[] queryVector) {
        Comparator<VectorRanked> bestFirst = Comparator
                .comparingDouble(VectorRanked::score).reversed()
                .thenComparingLong(VectorRanked::sourceId)
                .thenComparingInt(VectorRanked::chunkIndex);
        // Heap keeps the current worst on top so it can be evicted in O(log k).
        PriorityQueue<VectorRanked> heap = new PriorityQueue<>(bestFirst.reversed());
        int expectedBytes = dimensions * 4;
        retrievalIndex.scanEmbeddings(corpusType, corpusId, spaceId, dimensions, rs -> {
            byte[] blob = rs.getBytes("vector_blob");
            if (blob == null || blob.length != expectedBytes) return; // corrupt row: skip
            double dot = 0.0;
            for (int i = 0; i < dimensions; i++) {
                int base = i * 4;
                int bits = (blob[base] & 0xFF)
                        | (blob[base + 1] & 0xFF) << 8
                        | (blob[base + 2] & 0xFF) << 16
                        | (blob[base + 3] & 0xFF) << 24;
                dot += queryVector[i] * Float.intBitsToFloat(bits);
            }
            VectorRanked candidate = new VectorRanked(
                    rs.getLong("chunk_id"), rs.getLong("source_id"),
                    rs.getInt("chunk_index"), dot);
            if (heap.size() < VECTOR_TOP_K) {
                heap.add(candidate);
            } else if (bestFirst.compare(candidate, heap.peek()) < 0) {
                heap.poll();
                heap.add(candidate);
            }
        });
        List<VectorRanked> ranked = new ArrayList<>(heap);
        ranked.sort(bestFirst);
        return ranked;
    }

    // ── RRF fusion ──────────────────────────────────────────────────────────

    /** Fused rank pair for one chunk; either rank may be null but not both. */
    record FusedEntry(long chunkId, long sourceId, int chunkIndex,
                      Integer ftsRank, Integer vectorRank, double rrfScore) {}

    /**
     * Pure RRF fusion over 1-based ranks: {@code 1/(60+ftsRank) + 1/(60+vectorRank)},
     * one-sided hits keep their single term, ties by source/chunk index.
     */
    static List<FusedEntry> fuse(List<RetrievalHit> bm25, List<VectorRanked> vector) {
        Map<Long, FusedEntry> byChunk = new LinkedHashMap<>();
        for (RetrievalHit hit : bm25) {
            Citation citation = hit.citation();
            byChunk.put(citation.chunkId(), new FusedEntry(
                    citation.chunkId(), hit.sourceId(), hit.chunkIndex(),
                    citation.ftsRank(), null, 0.0));
        }
        for (int i = 0; i < vector.size(); i++) {
            VectorRanked ranked = vector.get(i);
            int vectorRank = i + 1;
            FusedEntry existing = byChunk.get(ranked.chunkId());
            if (existing == null) {
                byChunk.put(ranked.chunkId(), new FusedEntry(
                        ranked.chunkId(), ranked.sourceId(), ranked.chunkIndex(),
                        null, vectorRank, 0.0));
            } else {
                byChunk.put(ranked.chunkId(), new FusedEntry(
                        existing.chunkId(), existing.sourceId(), existing.chunkIndex(),
                        existing.ftsRank(), vectorRank, 0.0));
            }
        }
        List<FusedEntry> fused = new ArrayList<>(byChunk.size());
        for (FusedEntry entry : byChunk.values()) {
            double score = 0.0;
            if (entry.ftsRank() != null) score += 1.0 / (RRF_K + entry.ftsRank());
            if (entry.vectorRank() != null) score += 1.0 / (RRF_K + entry.vectorRank());
            fused.add(new FusedEntry(entry.chunkId(), entry.sourceId(),
                    entry.chunkIndex(), entry.ftsRank(), entry.vectorRank(), score));
        }
        fused.sort(Comparator.comparingDouble(FusedEntry::rrfScore).reversed()
                .thenComparingLong(FusedEntry::sourceId)
                .thenComparingInt(FusedEntry::chunkIndex));
        return fused.size() <= MAX_RESULTS ? fused : fused.subList(0, MAX_RESULTS);
    }

    /** Turn fused entries back into hits with complete citations. */
    private List<RetrievalHit> fuseAndMaterialize(
            List<RetrievalHit> bm25, List<VectorRanked> vector, String normalizedQuery) {
        List<FusedEntry> fused = fuse(bm25, vector);

        Map<Long, RetrievalHit> bm25ByChunk = new LinkedHashMap<>();
        for (RetrievalHit hit : bm25) bm25ByChunk.put(hit.citation().chunkId(), hit);
        List<Long> vectorOnlyIds = fused.stream()
                .map(FusedEntry::chunkId)
                .filter(id -> !bm25ByChunk.containsKey(id))
                .toList();
        Map<Long, RetrievalIndexRepository.LexicalRow> rowsById = new LinkedHashMap<>();
        for (RetrievalIndexRepository.LexicalRow row
                : retrievalIndex.findChunksByIds(vectorOnlyIds)) {
            rowsById.put(row.chunkId(), row);
        }

        List<RetrievalHit> hits = new ArrayList<>(fused.size());
        for (FusedEntry entry : fused) {
            RetrievalHit lexicalHit = bm25ByChunk.get(entry.chunkId());
            List<String> matchTypes = entry.ftsRank() != null && entry.vectorRank() != null
                    ? List.of("bm25", "vector")
                    : entry.ftsRank() != null ? List.of("bm25") : List.of("vector");
            if (lexicalHit != null) {
                Citation base = lexicalHit.citation();
                Citation citation = new Citation(
                        base.corpusType(), base.notebookId(), base.pageId(),
                        base.chunkId(), base.title(), base.headingPath(), base.snippet(),
                        matchTypes, entry.ftsRank(), entry.vectorRank(), entry.rrfScore());
                hits.add(new RetrievalHit(citation, lexicalHit.text(),
                        lexicalHit.sourceId(), lexicalHit.chunkIndex()));
            } else {
                RetrievalIndexRepository.LexicalRow row = rowsById.get(entry.chunkId());
                if (row == null) continue; // chunk deleted between scan and fetch
                Citation citation = new Citation(
                        row.corpusType(), row.corpusId(), row.sourceId(),
                        row.chunkId(), row.title(), row.headingPath(),
                        RetrievalService.buildSnippet(row.text(), normalizedQuery),
                        matchTypes, null, entry.vectorRank(), entry.rrfScore());
                hits.add(new RetrievalHit(citation, row.text(),
                        row.sourceId(), row.chunkIndex()));
            }
        }
        return List.copyOf(hits);
    }
}
