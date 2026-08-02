package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.AiChatSessionRepository;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * Task 11 contract tests: Java vector top-K, RRF fusion (k=60), and the full
 * hybrid degrade contract (BM25-only + vector-index-unavailable notice).
 */
class HybridRetrievalServiceTest {

    private static final String SPACE = "a".repeat(64);
    private static final int DIMS = 4;

    private JdbcTemplate jdbc;
    private NotebookRepository notebooks;
    private RetrievalIndexRepository retrievalIndex;
    private EmbeddingJobRepository jobs;
    private EmbeddingProviderRegistry registry;
    private RetrievalService lexical;
    private ObjectMapper mapper;
    private long nextChunkId;

    private HttpServer server;
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("hybrid-retrieval-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        notebooks = new NotebookRepository(jdbc, mapper);
        retrievalIndex = new RetrievalIndexRepository(jdbc);
        jobs = new EmbeddingJobRepository(jdbc);
        registry = new EmbeddingProviderRegistry();
        lexical = new RetrievalService(retrievalIndex, notebooks);
        nextChunkId = 1;
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ── Builders ───────────────────────────────────────────────────────────

    private HybridRetrievalService hybrid(long timeoutMs, long workerReadyMs) {
        return new HybridRetrievalService(
                lexical, retrievalIndex, jobs, registry, timeoutMs, workerReadyMs);
    }

    private void insertSpace(String spaceId, int dims, String state,
                             double coverage, boolean selected) {
        jdbc.update(
                "INSERT INTO embedding_space(embedding_space_id, canonical_contract_json,"
                        + " provider_type, model_identifier, dimensions, state, coverage, is_selected)"
                        + " VALUES (?, '{}', 'local-rust', 'test/model', ?, ?, ?, ?)",
                spaceId, dims, state, coverage, selected ? 1 : 0);
    }

    private void setSpaceState(String spaceId, String state, double coverage) {
        jdbc.update("UPDATE embedding_space SET state = ?, coverage = ? WHERE embedding_space_id = ?",
                state, coverage, spaceId);
    }

    private long insertChunk(long notebookId, long sourceId, int chunkIndex,
                             String title, String heading, String text) {
        long chunkId = nextChunkId++;
        jdbc.update(
                "INSERT INTO retrieval_chunk(id, corpus_type, corpus_id, source_id, chunk_index,"
                        + " title, heading_path, text, start_offset, end_offset, content_hash)"
                        + " VALUES (?, 'NOTEBOOK', ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                chunkId, notebookId, sourceId, chunkIndex, title, heading, text,
                text.length(), hashOf(sourceId, chunkIndex));
        jdbc.update(
                "INSERT INTO retrieval_chunk_fts(rowid, title, heading_path, text) VALUES (?, ?, ?, ?)",
                chunkId, title, heading, text);
        return chunkId;
    }

    private static String hashOf(long sourceId, int chunkIndex) {
        return "hash-" + sourceId + "-" + chunkIndex;
    }

    private void insertVector(long chunkId, String spaceId, int dims,
                              String contentHash, float[] raw) {
        List<Float> vector = new ArrayList<>(raw.length);
        for (float v : raw) vector.add(v);
        jdbc.update(
                "INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id,"
                        + " dimensions, content_hash, vector_blob) VALUES (?, 'NOTEBOOK', ?, ?, ?, ?)",
                chunkId, spaceId, dims, contentHash,
                EmbeddingVectorCodec.encode(vector, dims));
    }

    /** Configurable fake provider: fixed query vector, latency, failure. */
    static class FakeProvider implements EmbeddingProvider {
        private final int dims;
        private final float[] queryVector;
        volatile boolean available = true;
        volatile long sleepMs = 0;
        volatile boolean fail = false;

        FakeProvider(int dims, float[] queryVector) {
            this.dims = dims;
            this.queryVector = queryVector;
        }

        @Override public String providerType() { return "local-rust"; }
        @Override public String modelId() { return "test/model"; }
        @Override public int dimensions() { return dims; }
        @Override public boolean isAvailable() { return available; }

        @Override
        public List<List<Float>> embedDocuments(List<String> texts) {
            throw new UnsupportedOperationException("query-only fake");
        }

        @Override
        public List<Float> embedQuery(String text) throws EmbeddingProviderException {
            if (fail) throw new EmbeddingProviderException("WORKER_UNAVAILABLE", "boom", true);
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            List<Float> out = new ArrayList<>(dims);
            for (float v : queryVector) out.add(v);
            return out;
        }
    }

    private HybridRetrievalService.Result retrieveAll(HybridRetrievalService service, String text) {
        return service.retrieve(new RetrievalQuery(
                text, RetrievalQuery.Scope.ALL, null, RetrievalQuery.Corpus.NOTEBOOK));
    }

    private void writeEvidence(String name, Object payload) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        Files.writeString(Path.of(dir, name),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    // ── QA Scenario 1: 混合排名与预算 ───────────────────────────────────────

    @Test
    void rrfHandComputedOrderAndContextBudget() throws Exception {
        // Part 1: fixed 5 FTS ranks + 5 vector ranks with overlap (chunks 4,5).
        List<RetrievalHit> bm25 = new ArrayList<>();
        for (int chunk = 1; chunk <= 5; chunk++) {
            Citation citation = new Citation("NOTEBOOK", 1, chunk, chunk,
                    "t" + chunk, "", "s" + chunk, List.of("bm25"), chunk, null, null);
            bm25.add(new RetrievalHit(citation, "text" + chunk, chunk, 0));
        }
        List<HybridRetrievalService.VectorRanked> vector = List.of(
                new HybridRetrievalService.VectorRanked(4, 4, 0, 0.99),
                new HybridRetrievalService.VectorRanked(5, 5, 0, 0.98),
                new HybridRetrievalService.VectorRanked(6, 6, 0, 0.97),
                new HybridRetrievalService.VectorRanked(7, 7, 0, 0.96),
                new HybridRetrievalService.VectorRanked(8, 8, 0, 0.95));
        List<HybridRetrievalService.FusedEntry> fused =
                HybridRetrievalService.fuse(bm25, vector);
        // Hand-computed with k=60:
        // 4: 1/64+1/61  5: 1/65+1/62  1: 1/61  2: 1/62  3: 1/63  6: 1/63 (tie→source)
        // 7: 1/64  8: 1/65
        List<Long> fusedOrder = fused.stream().map(HybridRetrievalService.FusedEntry::chunkId).toList();
        assertEquals(List.of(4L, 5L, 1L, 2L, 3L, 6L, 7L, 8L), fusedOrder);
        HybridRetrievalService.FusedEntry top = fused.get(0);
        assertEquals(1.0 / 64 + 1.0 / 61, top.rrfScore(), 1e-12);
        assertEquals(4, top.ftsRank());
        assertEquals(1, top.vectorRank());

        // Part 2: 12k chars context budget via the chat path.
        long notebookId = notebooks.insert("预算");
        insertSpace(SPACE, DIMS, "ACTIVE", 1.0, true);
        FakeProvider provider = new FakeProvider(DIMS, new float[]{1, 0, 0, 0});
        registry.setActive(provider);
        for (int i = 0; i < 12; i++) {
            String text = "预算关键词" + i + " " + String.valueOf((char) ('a' + i)).repeat(2000);
            long chunkId = insertChunk(notebookId, 100 + i, 0, "页" + i, "", text);
            insertVector(chunkId, SPACE, DIMS, hashOf(100 + i, 0),
                    new float[]{1, i * 0.01f, 0, 0});
        }
        AiService ai = newAiService();
        ai.setHybridRetrieval(hybrid(5_000, 100));
        Map<String, Object> result = ai.chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "预算关键词")),
                "retrievalOptions", Map.of("enabled", true, "scope", "all")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertNotNull(citations);
        assertTrue(citations.size() <= 10, "at most 10 citations");
        JsonNode request = mapper.readTree(capturedBodies.get(capturedBodies.size() - 1));
        String context = request.path("messages").path(0).path("content").asText();
        assertTrue(request.path("messages").path(0).path("role").asText().equals("system"));
        // Fragments budget is 12,000 chars; the fixed preamble adds ~130 chars.
        assertTrue(context.length() <= 12_000 + 300,
                "context must respect the 12k budget, got " + context.length());
        for (Map<String, Object> citation : citations) {
            assertNotNull(citation.get("rrfScore"));
        }

        writeEvidence("task-11-rrf.json", Map.of(
                "scenario", "混合排名与预算",
                "rrfK", 60,
                "ftsRanks", Map.of("1", 1, "2", 2, "3", 3, "4", 4, "5", 5),
                "vectorRanks", Map.of("4", 1, "5", 2, "6", 3, "7", 4, "8", 5),
                "fusedOrder", fusedOrder,
                "handComputedOrder", List.of(4, 5, 1, 2, 3, 6, 7, 8),
                "topRrfScore", top.rrfScore(),
                "citations", citations.size(),
                "contextChars", context.length(),
                "budget", 12_000));
    }

    // ── Hybrid union & exclusion ───────────────────────────────────────────

    @Test
    void hybridUnionProducesBothRanksAndVectorOnlyHits() {
        long notebookId = notebooks.insert("联合");
        insertSpace(SPACE, DIMS, "ACTIVE", 1.0, true);
        registry.setActive(new FakeProvider(DIMS, new float[]{1, 0, 0, 0}));

        long chunkA = insertChunk(notebookId, 11, 0, "检索关键词甲", "", "正文甲");
        long chunkB = insertChunk(notebookId, 12, 0, "检索关键词乙", "", "正文乙");
        long chunkC = insertChunk(notebookId, 13, 0, "别的主题", "", "完全无关正文");
        insertVector(chunkA, SPACE, DIMS, hashOf(11, 0), new float[]{1, 0, 0, 0});
        insertVector(chunkB, SPACE, DIMS, hashOf(12, 0), new float[]{0, 1, 0, 0});
        insertVector(chunkC, SPACE, DIMS, hashOf(13, 0), new float[]{0.9f, 0.1f, 0, 0});

        HybridRetrievalService.Result result =
                retrieveAll(hybrid(5_000, 100), "检索关键词");
        assertNull(result.notice());
        Map<Long, Citation> byChunk = new LinkedHashMap<>();
        for (RetrievalHit hit : result.hits()) byChunk.put(hit.citation().chunkId(), hit.citation());

        Citation a = byChunk.get(chunkA);
        assertEquals(List.of("bm25", "vector"), a.matchTypes());
        assertEquals(1, a.vectorRank());
        assertNotNull(a.ftsRank());
        assertNotNull(a.rrfScore());

        Citation b = byChunk.get(chunkB);
        assertEquals(List.of("bm25", "vector"), b.matchTypes());
        assertEquals(3, b.vectorRank());

        // Vector-only hit enters the RRF union with a full citation.
        Citation c = byChunk.get(chunkC);
        assertNotNull(c, "vector-only hit must be included");
        assertEquals(List.of("vector"), c.matchTypes());
        assertNull(c.ftsRank());
        assertEquals(2, c.vectorRank());
        assertTrue(c.snippet().contains("完全无关正文"));

        // A fused before C: 1/61+1/61 > 1/62.
        List<Long> order = result.hits().stream().map(h -> h.citation().chunkId()).toList();
        assertTrue(order.indexOf(chunkA) < order.indexOf(chunkC));
    }

    @Test
    void mismatchedVectorsAreAllExcluded() {
        long notebookId = notebooks.insert("排除");
        insertSpace(SPACE, DIMS, "ACTIVE", 1.0, true);
        String otherSpace = "b".repeat(64);
        insertSpace(otherSpace, DIMS, "DISABLED", 0.0, false);
        String wrongDimSpace = "c".repeat(64);
        insertSpace(wrongDimSpace, 8, "DISABLED", 0.0, false);
        registry.setActive(new FakeProvider(DIMS, new float[]{1, 0, 0, 0}));

        long good = insertChunk(notebookId, 21, 0, "甲", "", "向量正文一");
        long stale = insertChunk(notebookId, 22, 0, "乙", "", "向量正文二");
        long otherSpaceChunk = insertChunk(notebookId, 23, 0, "丙", "", "向量正文三");
        long wrongDims = insertChunk(notebookId, 24, 0, "丁", "", "向量正文四");
        long wrongCorpus = insertChunk(notebookId, 25, 0, "戊", "", "向量正文五");

        insertVector(good, SPACE, DIMS, hashOf(21, 0), new float[]{1, 0, 0, 0});
        insertVector(stale, SPACE, DIMS, "stale-hash", new float[]{1, 0, 0, 0});
        insertVector(otherSpaceChunk, otherSpace, DIMS, hashOf(23, 0), new float[]{1, 0, 0, 0});
        insertVector(wrongDims, wrongDimSpace, 8, hashOf(24, 0),
                new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        jdbc.update(
                "INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id,"
                        + " dimensions, content_hash, vector_blob) VALUES (?, 'OTHER', ?, ?, ?, ?)",
                wrongCorpus, SPACE, DIMS, hashOf(25, 0),
                EmbeddingVectorCodec.encode(List.of(1f, 0f, 0f, 0f), DIMS));

        // Query without lexical matches: only scannable vectors can surface.
        HybridRetrievalService.Result result = retrieveAll(hybrid(5_000, 100), "zzzqqqvvv");
        assertNull(result.notice());
        assertEquals(List.of(good),
                result.hits().stream().map(h -> h.citation().chunkId()).toList());
        assertEquals(List.of("vector"), result.hits().get(0).citation().matchTypes());
    }

    // ── QA Scenario 2 + degrade contract ───────────────────────────────────

    @Test
    void degradeMatrixForcesBm25OnlyWithNotice() throws Exception {
        long notebookId = notebooks.insert("降级");
        long chunkId = insertChunk(notebookId, 31, 0, "降级关键词", "", "降级正文");
        insertSpace(SPACE, DIMS, "ACTIVE", 1.0, true);
        insertVector(chunkId, SPACE, DIMS, hashOf(31, 0), new float[]{1, 0, 0, 0});
        FakeProvider provider = new FakeProvider(DIMS, new float[]{1, 0, 0, 0});
        registry.setActive(provider);
        HybridRetrievalService service = hybrid(1_500, 100);

        List<Map<String, Object>> cases = new ArrayList<>();

        // Healthy baseline: vector rank present, no notice.
        HybridRetrievalService.Result healthy = retrieveAll(service, "降级关键词");
        assertNull(healthy.notice());
        assertEquals(1, healthy.hits().get(0).citation().vectorRank());

        // REBUILDING (model switch in progress, old vectors still stored).
        setSpaceState(SPACE, "REBUILDING", 0.5);
        cases.add(assertDegraded(service, "REBUILDING"));

        // UNINSTALLING.
        setSpaceState(SPACE, "UNINSTALLING", 1.0);
        cases.add(assertDegraded(service, "UNINSTALLING"));

        // ACTIVE but provider missing.
        setSpaceState(SPACE, "ACTIVE", 1.0);
        registry.clear();
        cases.add(assertDegraded(service, "no-provider"));

        // Provider registered but unhealthy (worker not ready within 100ms).
        provider.available = false;
        registry.setActive(provider);
        cases.add(assertDegraded(service, "provider-unavailable"));

        // Worker crash (embed throws).
        provider.available = true;
        provider.fail = true;
        cases.add(assertDegraded(service, "worker-crash"));
        provider.fail = false;

        // No selected space at all: BM25 is the full contract → no notice.
        jdbc.update("DELETE FROM retrieval_embedding");
        jdbc.update("DELETE FROM embedding_space");
        HybridRetrievalService.Result noSpace = retrieveAll(service, "降级关键词");
        assertNull(noSpace.notice(), "no notice when embedding was never enabled");
        assertEquals(List.of("bm25"), noSpace.hits().get(0).citation().matchTypes());

        // Chat must not fail during degrade: notice is surfaced, reply intact.
        insertSpace(SPACE, DIMS, "REBUILDING", 0.0, true);
        AiService ai = newAiService();
        ai.setHybridRetrieval(service);
        Map<String, Object> chat = ai.chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "降级关键词")),
                "retrievalOptions", Map.of("enabled", true, "scope", "all")));
        assertEquals("stub-reply", chat.get("reply"));
        @SuppressWarnings("unchecked")
        Map<String, Object> notice = (Map<String, Object>) chat.get("retrievalNotice");
        assertEquals("vector-index-unavailable", notice.get("code"));

        writeEvidence("task-11-hybrid-degrade.json", Map.of(
                "scenario", "模型切换中强制 BM25-only",
                "cases", cases,
                "noSpaceNotice", "none",
                "chatReply", chat.get("reply"),
                "chatNoticeCode", notice.get("code")));
    }

    private Map<String, Object> assertDegraded(HybridRetrievalService service, String label) {
        HybridRetrievalService.Result result = retrieveAll(service, "降级关键词");
        assertNotNull(result.notice(), label + ": notice expected");
        assertEquals("vector-index-unavailable", result.notice().get("code"), label);
        assertTrue(!result.hits().isEmpty(), label + ": bm25 hits must survive");
        for (RetrievalHit hit : result.hits()) {
            assertEquals(List.of("bm25"), hit.citation().matchTypes(), label);
            assertNull(hit.citation().vectorRank(), label + ": vectorRank must be null");
            assertNull(hit.citation().rrfScore(), label);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("case", label);
        entry.put("noticeCode", result.notice().get("code"));
        entry.put("matchTypes", "bm25");
        entry.put("vectorRank", null);
        return entry;
    }

    @Test
    void serviceDeadlineCancelsSlowVectorAndFallsBackToBm25() {
        long notebookId = notebooks.insert("超时");
        long chunkId = insertChunk(notebookId, 41, 0, "超时关键词", "", "超时正文");
        insertSpace(SPACE, DIMS, "ACTIVE", 1.0, true);
        insertVector(chunkId, SPACE, DIMS, hashOf(41, 0), new float[]{1, 0, 0, 0});
        FakeProvider slow = new FakeProvider(DIMS, new float[]{1, 0, 0, 0});
        slow.sleepMs = 60_000;
        registry.setActive(slow);

        long started = System.currentTimeMillis();
        HybridRetrievalService.Result result = retrieveAll(hybrid(200, 50), "超时关键词");
        long elapsed = System.currentTimeMillis() - started;

        assertNotNull(result.notice());
        assertEquals("vector-index-unavailable", result.notice().get("code"));
        assertEquals(List.of("bm25"), result.hits().get(0).citation().matchTypes());
        assertTrue(elapsed < 5_000, "deadline must cut the wait, took " + elapsed + "ms");
    }

    @Test
    void benchmark10kBy512IsRecordedNotGated() throws Exception {
        long notebookId = notebooks.insert("基准");
        String space = "d".repeat(64);
        insertSpace(space, 512, "ACTIVE", 1.0, true);
        float[] query = new float[512];
        query[0] = 1f;
        registry.setActive(new FakeProvider(512, query));

        java.util.Random random = new java.util.Random(42);
        List<Object[]> chunkBatch = new ArrayList<>();
        List<Object[]> vectorBatch = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            long chunkId = 1_000 + i;
            chunkBatch.add(new Object[]{chunkId, notebookId, 10_000 + i, 0,
                    "b" + i, "", "基准正文" + i, 10, "bench-" + i});
            List<Float> vector = new ArrayList<>(512);
            for (int d = 0; d < 512; d++) vector.add(random.nextFloat() - 0.5f);
            vectorBatch.add(new Object[]{chunkId, space, 512, "bench-" + i,
                    EmbeddingVectorCodec.encode(vector, 512)});
        }
        jdbc.batchUpdate(
                "INSERT INTO retrieval_chunk(id, corpus_type, corpus_id, source_id, chunk_index,"
                        + " title, heading_path, text, start_offset, end_offset, content_hash)"
                        + " VALUES (?, 'NOTEBOOK', ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                chunkBatch);
        jdbc.batchUpdate(
                "INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id,"
                        + " dimensions, content_hash, vector_blob) VALUES (?, 'NOTEBOOK', ?, ?, ?, ?)",
                vectorBatch);

        HybridRetrievalService service = hybrid(60_000, 100);
        long started = System.currentTimeMillis();
        HybridRetrievalService.Result result = retrieveAll(service, "不存在的词汇xyzw");
        long elapsed = System.currentTimeMillis() - started;

        assertNull(result.notice());
        assertEquals(10, result.hits().size(), "vector top-K fused down to 10");
        for (RetrievalHit hit : result.hits()) {
            assertEquals(List.of("vector"), hit.citation().matchTypes());
        }
        // Recorded, never asserted against a hardware threshold.
        writeEvidence("task-11-benchmark.txt", Map.of(
                "benchmark", "10k x 512 full scan + bounded min-heap",
                "elapsedMs", elapsed,
                "rows", 10_000,
                "dimensions", 512,
                "note", "recorded only; the enforced contract is the service-level deadline"));
    }

    @Test
    void emptyQueryAndEmptyCorpusContracts() {
        insertSpace(SPACE, DIMS, "ACTIVE", 1.0, true);
        registry.setActive(new FakeProvider(DIMS, new float[]{1, 0, 0, 0}));
        HybridRetrievalService service = hybrid(5_000, 100);

        assertTrue(retrieveAll(service, "   ").hits().isEmpty());
        assertNull(retrieveAll(service, "   ").notice());

        // No lexical matches and no vectors → empty (the only empty case).
        HybridRetrievalService.Result empty = retrieveAll(service, "无命中词");
        assertTrue(empty.hits().isEmpty());
        assertNull(empty.notice());
    }

    // ── Chat harness ───────────────────────────────────────────────────────

    private AiService newAiService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBodies.add(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response =
                    "{\"choices\":[{\"message\":{\"content\":\"stub-reply\"},\"finish_reason\":\"stop\"}]}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        capturedBodies.clear();
        AiService service = new AiService(
                new AiConfigRepository(jdbc),
                new AiChatSessionRepository(jdbc),
                new ApiKeyEncryptor(),
                mapper,
                lexical);
        service.saveConfig(Map.of(
                "provider", "custom",
                "endpoint", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model", "stub-model",
                "apiKey", "stub-key"));
        return service;
    }
}
