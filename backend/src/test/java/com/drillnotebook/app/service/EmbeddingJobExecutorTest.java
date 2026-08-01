package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * Tests for {@link EmbeddingJobExecutor} against a real SQLite database with
 * a controllable in-memory {@link EmbeddingProvider}.
 *
 * <p>The executor is constructed with {@code autoStart=false} and driven via
 * {@link EmbeddingJobExecutor#runOnce()} for deterministic single-poll
 * assertions (claim → out-of-transaction inference → commit-time
 * revalidation).
 */
class EmbeddingJobExecutorTest {

    private static final int DIMS = 512;
    private static final String SPACE_ID = "space-test-1";

    private Path tempDir;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private NoteIndexingService indexing;
    private EmbeddingJobRepository jobs;
    private EmbeddingProviderRegistry registry;
    private EmbeddingJobExecutor executor;
    private RetrievalStatusService statusService;
    private FakeProvider provider;
    private long notebookId;
    private long pageId;

    // ── Controllable provider ──────────────────────────────────────────────

    static class FakeProvider implements EmbeddingProvider {
        volatile boolean available = true;
        volatile EmbeddingProviderException nextFailure;
        volatile Runnable beforeReturnOnce;
        volatile int embedDocumentCalls = 0;

        @Override
        public String providerType() { return "local-rust"; }

        @Override
        public String modelId() { return "test-model"; }

        @Override
        public int dimensions() { return DIMS; }

        @Override
        public boolean isAvailable() { return available; }

        @Override
        public List<List<Float>> embedDocuments(List<String> texts)
                throws EmbeddingProviderException {
            embedDocumentCalls++;
            EmbeddingProviderException failure = nextFailure;
            if (failure != null) {
                throw failure;
            }
            Runnable hook = beforeReturnOnce;
            beforeReturnOnce = null;
            if (hook != null) hook.run();
            List<List<Float>> out = new ArrayList<>();
            for (String ignored : texts) {
                List<Float> vec = new ArrayList<>(DIMS);
                for (int i = 0; i < DIMS; i++) {
                    vec.add((i + 1) / (float) DIMS);
                }
                out.add(vec);
            }
            return out;
        }

        @Override
        public List<Float> embedQuery(String text) throws EmbeddingProviderException {
            return embedDocuments(List.of(text)).get(0);
        }
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("embedding-job-test");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("study.db") + "?foreign_keys=on");
        new DatabaseInitializer(ds).initialize();

        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper();
        NotebookRepository notebooks = new NotebookRepository(jdbc, mapper);
        RetrievalIndexRepository retrievalRepo = new RetrievalIndexRepository(jdbc);
        indexing = new NoteIndexingService(notebooks, retrievalRepo, jdbc, mapper);

        jobs = new EmbeddingJobRepository(jdbc);
        registry = new EmbeddingProviderRegistry();
        provider = new FakeProvider();
        registry.setActive(provider);
        executor = new EmbeddingJobExecutor(
                jobs, registry, new DataSourceTransactionManager(ds), false, 1000);
        statusService = new RetrievalStatusService(jobs);

        notebookId = notebooks.insert("Test Notebook");
        pageId = notebooks.insertPage(notebookId, "Test Page", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void insertSelectedSpace(String state) {
        jdbc.update(
                "INSERT INTO embedding_space(embedding_space_id,"
                        + " canonical_contract_json, provider_type, model_identifier,"
                        + " dimensions, state, coverage, is_selected)"
                        + " VALUES (?, '{}', 'local-rust', 'test-model', ?, ?, 0.0, 1)",
                SPACE_ID, DIMS, state);
    }

    /** TipTap doc with three ~2000-char paragraphs → exactly 3 chunks. */
    private static Map<String, Object> threeChunkContent(String marker) {
        List<Object> paragraphs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String text = (marker + "段落" + i + "：").repeat(1)
                    + String.valueOf((char) ('甲' + i)).repeat(1990);
            paragraphs.add(Map.of("type", "paragraph", "content",
                    List.of(Map.of("type", "text", "text", text))));
        }
        return Map.of("type", "doc", "content", paragraphs);
    }

    private String pageHash() {
        return jdbc.queryForObject(
                "SELECT content_hash FROM note_page WHERE id = ?", String.class, pageId);
    }

    private List<Map<String, Object>> jobRows() {
        return jdbc.query(
                "SELECT id, source_content_hash, status, attempts, next_run_at,"
                        + " claim_token, error FROM embedding_job ORDER BY id",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("hash", rs.getString("source_content_hash"));
                    m.put("status", rs.getString("status"));
                    m.put("attempts", rs.getInt("attempts"));
                    m.put("next_run_at", rs.getString("next_run_at"));
                    m.put("claim_token", rs.getString("claim_token"));
                    m.put("error", rs.getString("error"));
                    return m;
                });
    }

    private List<Map<String, Object>> embeddingRows() {
        return jdbc.query(
                "SELECT chunk_id, content_hash, dimensions,"
                        + " length(vector_blob) AS blob_len"
                        + " FROM retrieval_embedding ORDER BY chunk_id",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunk_id", rs.getLong("chunk_id"));
                    m.put("content_hash", rs.getString("content_hash"));
                    m.put("dimensions", rs.getInt("dimensions"));
                    m.put("blob_len", rs.getInt("blob_len"));
                    return m;
                });
    }

    private Map<String, Object> spaceRow() {
        return jdbc.queryForMap(
                "SELECT state, coverage FROM embedding_space WHERE embedding_space_id = ?",
                SPACE_ID);
    }

    private void writeEvidence(String name, List<String> lines) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        List<String> out = new ArrayList<>();
        out.add("generatedBy: EmbeddingJobExecutorTest");
        out.addAll(lines);
        Files.writeString(Path.of(dir, name),
                String.join(System.lineSeparator(), out) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    // ── QA Scenario 1: 最新内容后台批量向量化 ────────────────────────────────

    @Test
    void pollOnceEmbedsAllChunksCompletesJobAndActivatesSpace() throws Exception {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));

        long chunkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk", Long.class);
        assertEquals(3, chunkCount, "fixture must produce exactly 3 chunks");
        assertEquals("QUEUED", jobRows().get(0).get("status"));

        assertTrue(executor.runOnce(), "poll should process the job");

        List<Map<String, Object>> embeddings = embeddingRows();
        assertEquals(3, embeddings.size());
        for (Map<String, Object> row : embeddings) {
            assertEquals(2048, row.get("blob_len"), "512-dim float32 = 2048 bytes");
            assertEquals(pageHash(), row.get("content_hash"));
            assertEquals(DIMS, row.get("dimensions"));
        }
        Map<String, Object> job = jobRows().get(0);
        assertEquals("COMPLETED", job.get("status"));
        assertNull(job.get("claim_token"));

        Map<String, Object> space = spaceRow();
        assertEquals(1.0, ((Number) space.get("coverage")).doubleValue(), 1e-9);
        assertEquals("ACTIVE", space.get("state"), "100% coverage activates REBUILDING space");

        // Nothing left to do → idle.
        assertFalse(executor.runOnce());

        writeEvidence("task-9-embedding-job.txt", List.of(
                "scenario: 最新内容后台批量向量化（poll once）",
                "chunks: " + chunkCount,
                "embeddingRows: " + embeddings.size(),
                "blobBytes: " + embeddings.stream().map(r -> r.get("blob_len")).toList(),
                "jobStatus: " + job.get("status"),
                "coverage: " + space.get("coverage"),
                "spaceState: " + space.get("state")));
    }

    // ── QA Scenario 2: autosave 后旧结果被拒绝 ──────────────────────────────

    @Test
    void staleInferenceResultIsDiscardedAfterAutosave() throws Exception {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));
        String hashA = pageHash();

        // While job A's inference is "in flight", autosave replaces content (hash B).
        provider.beforeReturnOnce = () ->
                indexing.savePageAndIndex(pageId, null, threeChunkContent("B"));

        assertTrue(executor.runOnce(), "job A must be touched (discarded)");
        String hashB = pageHash();
        assertNotEquals(hashA, hashB);

        // A's result never reached the DB.
        assertEquals(0, embeddingRows().size(), "stale vectors must not be written");
        List<Map<String, Object>> afterFirst = jobRows();
        Map<String, Object> jobA = afterFirst.stream()
                .filter(j -> hashA.equals(j.get("hash"))).findFirst().orElseThrow();
        Map<String, Object> jobB = afterFirst.stream()
                .filter(j -> hashB.equals(j.get("hash"))).findFirst().orElseThrow();
        assertEquals("SUPERSEDED", jobA.get("status"));
        assertEquals("QUEUED", jobB.get("status"));

        // Second poll processes job B; only hash-B embeddings exist.
        assertTrue(executor.runOnce());
        List<Map<String, Object>> embeddings = embeddingRows();
        assertEquals(3, embeddings.size());
        for (Map<String, Object> row : embeddings) {
            assertEquals(hashB, row.get("content_hash"));
            assertEquals(2048, row.get("blob_len"));
        }
        Map<String, Object> jobBAfter = jobRows().stream()
                .filter(j -> hashB.equals(j.get("hash"))).findFirst().orElseThrow();
        assertEquals("COMPLETED", jobBAfter.get("status"));

        writeEvidence("task-9-stale-job.txt", List.of(
                "scenario: autosave 后旧结果被拒绝（stale hash discard）",
                "hashA: " + hashA,
                "hashB: " + hashB,
                "jobA: " + jobA.get("status"),
                "jobBAfterFirstPoll: " + jobB.get("status"),
                "embeddingsAfterFirstPoll: 0",
                "jobBFinal: " + jobBAfter.get("status"),
                "finalEmbeddingHashes: all=" + hashB,
                "finalEmbeddingRows: " + embeddings.size()));
    }

    // ── Restart recovery ───────────────────────────────────────────────────

    @Test
    void restartRecoveryReleasesClaimedJobsBackToQueued() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));
        // Simulate a previous process dying mid-claim.
        jdbc.update("UPDATE embedding_job SET status = 'CLAIMED',"
                + " claim_token = 'dead-process-token'");

        executor.start(); // autoStart=false → recovery only, no thread

        Map<String, Object> job = jobRows().get(0);
        assertEquals("QUEUED", job.get("status"));
        assertNull(job.get("claim_token"));

        // Recovered job is processable.
        assertTrue(executor.runOnce());
        assertEquals("COMPLETED", jobRows().get(0).get("status"));
    }

    // ── Provider down: no busy loop, no burned attempts ────────────────────

    @Test
    void unavailableProviderIdlesWithoutClaimingJobs() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));

        provider.available = false;
        assertFalse(executor.runOnce(), "must idle-wait instead of claiming");
        Map<String, Object> job = jobRows().get(0);
        assertEquals("QUEUED", job.get("status"));
        assertEquals(0, job.get("attempts"), "attempts must not burn while provider is down");
        assertEquals(0, provider.embedDocumentCalls);

        // Missing provider behaves the same.
        registry.clear();
        assertFalse(executor.runOnce());
        assertEquals("QUEUED", jobRows().get(0).get("status"));
    }

    @Test
    void dimensionMismatchedProviderDoesNotClaim() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));
        jdbc.update("UPDATE embedding_space SET dimensions = 384"
                + " WHERE embedding_space_id = ?", SPACE_ID);

        assertFalse(executor.runOnce());
        assertEquals("QUEUED", jobRows().get(0).get("status"));
        assertEquals(0, provider.embedDocumentCalls);
    }

    // ── Retry / failure paths ──────────────────────────────────────────────

    @Test
    void retryableFailureBacksOffAndDefersNextRun() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));

        provider.nextFailure = new EmbeddingProviderException(
                "WORKER_UNAVAILABLE", "boom", true);
        assertTrue(executor.runOnce());

        Map<String, Object> job = jobRows().get(0);
        assertEquals("RETRY", job.get("status"));
        assertEquals(1, job.get("attempts"));
        assertNotNull(job.get("next_run_at"), "backoff must set next_run_at");
        assertTrue(String.valueOf(job.get("error")).contains("WORKER_UNAVAILABLE"));

        // Backoff not yet elapsed → not claimable → idle (no busy loop).
        provider.nextFailure = null;
        assertFalse(executor.runOnce());
        assertEquals("RETRY", jobRows().get(0).get("status"));

        // Once due, the job recovers.
        jdbc.update("UPDATE embedding_job SET next_run_at = datetime('now', '-1 seconds')");
        assertTrue(executor.runOnce());
        assertEquals("COMPLETED", jobRows().get(0).get("status"));
    }

    @Test
    void nonRetryableFailureMarksJobFailed() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));

        provider.nextFailure = new EmbeddingProviderException(
                "INVALID_RESPONSE", "bad shape", false);
        assertTrue(executor.runOnce());

        Map<String, Object> job = jobRows().get(0);
        assertEquals("FAILED", job.get("status"));
        assertTrue(String.valueOf(job.get("error")).contains("INVALID_RESPONSE"));
        assertEquals(0, embeddingRows().size());
    }

    @Test
    void retryableFailureExhaustingMaxAttemptsFails() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));

        provider.nextFailure = new EmbeddingProviderException(
                "WORKER_UNAVAILABLE", "still down", true);
        for (int i = 0; i < EmbeddingJobExecutor.MAX_ATTEMPTS; i++) {
            jdbc.update("UPDATE embedding_job SET next_run_at = NULL");
            assertTrue(executor.runOnce(), "attempt " + (i + 1));
        }
        Map<String, Object> job = jobRows().get(0);
        assertEquals("FAILED", job.get("status"));
        assertEquals(EmbeddingJobExecutor.MAX_ATTEMPTS, job.get("attempts"));
    }

    // ── Deleted page ───────────────────────────────────────────────────────

    @Test
    void deletedPageJobIsSuperseded() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));
        // Delete the page but keep the job row (deletePage would remove it;
        // simulate the race where deletion happens between enqueue and claim).
        jdbc.update("DELETE FROM retrieval_chunk WHERE source_id = ?", pageId);
        jdbc.update("DELETE FROM note_page WHERE id = ?", pageId);

        assertTrue(executor.runOnce());
        assertEquals("SUPERSEDED", jobRows().get(0).get("status"));
        assertEquals(0, embeddingRows().size());
        assertEquals(0, provider.embedDocumentCalls, "no inference for a deleted source");
    }

    // ── Status service ─────────────────────────────────────────────────────

    @Test
    void statusReportsDisabledWithoutSelectedSpace() {
        Map<String, Object> status = statusService.status("all", null);
        assertEquals("DISABLED", status.get("indexState"));
        assertNull(status.get("embeddingSpaceId"));
        assertNull(status.get("provider"));
        assertEquals(0, status.get("queuedJobs"));
        assertEquals(0.0, ((Number) status.get("coverage")).doubleValue(), 1e-9);
    }

    @Test
    void statusReportsQueueCoverageAndScope() {
        insertSelectedSpace("REBUILDING");
        indexing.savePageAndIndex(pageId, null, threeChunkContent("A"));

        Map<String, Object> before = statusService.status("current", notebookId);
        assertEquals("current", before.get("scope"));
        assertEquals(notebookId, before.get("notebookId"));
        assertEquals(1, before.get("totalPages"));
        assertEquals(3, before.get("totalChunks"));
        assertEquals(0, before.get("indexedChunks"));
        assertEquals(1, before.get("queuedJobs"));
        assertEquals(0, before.get("failedJobs"));
        assertEquals("REBUILDING", before.get("indexState"));
        assertEquals(SPACE_ID, before.get("embeddingSpaceId"));
        assertEquals("local-rust", before.get("provider"));

        assertTrue(executor.runOnce());

        Map<String, Object> after = statusService.status("all", null);
        assertEquals(3, after.get("indexedChunks"));
        assertEquals(0, after.get("staleChunks"));
        assertEquals(0, after.get("queuedJobs"));
        assertEquals(1.0, ((Number) after.get("coverage")).doubleValue(), 1e-9);
        assertEquals("ACTIVE", after.get("indexState"));

        assertThrows(IllegalArgumentException.class,
                () -> statusService.status("current", null));
    }
}
