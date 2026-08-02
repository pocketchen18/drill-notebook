package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * Tests for {@link RetrievalMaintenanceService} (Task 15) against a real
 * SQLite database: reindex (missing/full) idempotency and scope, retry-failed
 * re-queue, and post-switch stale-vector cleanup guards.
 */
class RetrievalMaintenanceServiceTest {

    private static final int DIMS = 512;
    private static final String SPACE_ID = "space-maint-1";

    private Path tempDir;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private NotebookRepository notebooks;
    private NoteIndexingService indexing;
    private EmbeddingJobRepository jobs;
    private EmbeddingModelRepository models;
    private EmbeddingProviderRegistry registry;
    private EmbeddingJobExecutor executor;
    private RetrievalMaintenanceService maintenance;
    private long notebookId;
    private long pageId;

    static class FakeProvider implements EmbeddingProvider {
        @Override public String providerType() { return "local-rust"; }
        @Override public String modelId() { return "test-model"; }
        @Override public int dimensions() { return DIMS; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<List<Float>> embedDocuments(List<String> texts) {
            List<List<Float>> out = new ArrayList<>();
            for (String ignored : texts) {
                List<Float> vec = new ArrayList<>(DIMS);
                for (int i = 0; i < DIMS; i++) vec.add((i + 1) / (float) DIMS);
                out.add(vec);
            }
            return out;
        }
        @Override public List<Float> embedQuery(String text) {
            return embedDocuments(List.of(text)).get(0);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("retrieval-maint-test");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("study.db") + "?foreign_keys=on");
        new DatabaseInitializer(ds).initialize();

        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper();
        notebooks = new NotebookRepository(jdbc, mapper);
        RetrievalIndexRepository retrievalRepo = new RetrievalIndexRepository(jdbc);
        indexing = new NoteIndexingService(notebooks, retrievalRepo, jdbc, mapper);

        jobs = new EmbeddingJobRepository(jdbc);
        models = new EmbeddingModelRepository(jdbc);
        registry = new EmbeddingProviderRegistry();
        registry.setActive(new FakeProvider());
        executor = new EmbeddingJobExecutor(
                jobs, registry, new DataSourceTransactionManager(ds), false, 1000);
        maintenance = new RetrievalMaintenanceService(models, jobs, executor);

        notebookId = notebooks.insert("Test Notebook");
        pageId = notebooks.insertPage(notebookId, "Test Page", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        maintenance.shutdown();
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
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

    private void insertSpace(String spaceId, String state, double coverage, int selected) {
        jdbc.update(
                "INSERT INTO embedding_space(embedding_space_id,"
                        + " canonical_contract_json, provider_type, model_identifier,"
                        + " dimensions, state, coverage, is_selected)"
                        + " VALUES (?, '{}', 'local-rust', 'test-model', ?, ?, ?, ?)",
                spaceId, DIMS, state, coverage, selected);
    }

    /** TipTap doc with three ~2000-char paragraphs → exactly 3 chunks. */
    private static Map<String, Object> threeChunkContent(String marker) {
        List<Object> paragraphs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String text = (marker + "段落" + i + "：")
                    + String.valueOf((char) ('甲' + i)).repeat(1990);
            paragraphs.add(Map.of("type", "paragraph", "content",
                    List.of(Map.of("type", "text", "text", text))));
        }
        return Map.of("type", "doc", "content", paragraphs);
    }

    private void saveContent() {
        indexing.savePageAndIndex(pageId, "Test Page", threeChunkContent("甲"));
    }

    private void runExecutorToIdle() {
        int guard = 0;
        while (executor.runOnce() && guard++ < 200) { /* drain */ }
    }

    private String spaceState(String spaceId) {
        return jdbc.queryForObject(
                "SELECT state FROM embedding_space WHERE embedding_space_id = ?",
                String.class, spaceId);
    }

    private int countJobs(String status, String reasonPrefix) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job WHERE status = ? AND reason LIKE ?",
                Integer.class, status, reasonPrefix + "%");
    }

    private int countJobsForNotebook(long nb, String reasonPrefix) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job j WHERE j.reason LIKE ?"
                        + " AND EXISTS (SELECT 1 FROM note_page p WHERE p.id = j.source_id"
                        + " AND p.notebook_id = ?)",
                Integer.class, reasonPrefix + "%", nb);
    }

    private long firstChunkId() {
        return jdbc.queryForObject(
                "SELECT id FROM retrieval_chunk ORDER BY id LIMIT 1", Long.class);
    }

    private void insertVector(String spaceId, long chunkId) {
        String hash = jdbc.queryForObject(
                "SELECT content_hash FROM retrieval_chunk WHERE id = ?", String.class, chunkId);
        jdbc.update(
                "INSERT INTO retrieval_embedding(chunk_id, corpus_type, embedding_space_id,"
                        + " dimensions, content_hash, vector_blob)"
                        + " VALUES (?, 'NOTEBOOK', ?, ?, ?, ?)",
                chunkId, spaceId, DIMS, hash, new byte[DIMS * 4]);
    }

    // ── reindex validation ─────────────────────────────────────────────────

    @Test
    void reindexCurrentWithoutNotebookIdRejected() {
        insertSelectedSpace("REBUILDING");
        assertThrows(IllegalArgumentException.class,
                () -> maintenance.reindex(Map.of("scope", "current", "mode", "missing")));
    }

    @Test
    void reindexInvalidModeRejected() {
        insertSelectedSpace("REBUILDING");
        assertThrows(IllegalArgumentException.class,
                () -> maintenance.reindex(Map.of("scope", "all", "mode", "bogus")));
    }

    @Test
    void reindexWithoutSelectedSpaceConflicts() {
        RetrievalMaintenanceService.ApiResult result =
                maintenance.reindex(Map.of("scope", "all", "mode", "missing"));
        assertEquals(409, result.status());
        assertEquals("NO_SELECTED_SPACE", result.body().get("errorCode"));
    }

    // ── reindex missing ────────────────────────────────────────────────────

    @Test
    void reindexMissingIsIdempotentWhileActive() {
        insertSelectedSpace("REBUILDING");
        saveContent();
        // Chunks exist but no job/vectors: drop the PAGE_UPDATE job.
        jdbc.update("DELETE FROM embedding_job");

        RetrievalMaintenanceService.ApiResult first =
                maintenance.reindex(Map.of("scope", "all", "mode", "missing"));
        assertEquals(202, first.status());
        String jobId = (String) first.body().get("jobId");
        assertNotNull(jobId);
        assertTrue(countJobs("QUEUED", "reindex") > 0);

        // Duplicate request while the reindex job is still runnable → same job.
        RetrievalMaintenanceService.ApiResult second =
                maintenance.reindex(Map.of("scope", "all", "mode", "missing"));
        assertEquals(200, second.status());
        assertEquals(jobId, second.body().get("jobId"));
        // No duplicate queueing.
        assertEquals(countJobs("QUEUED", "reindex"),
                countJobs("QUEUED", "reindex"));
    }

    @Test
    void reindexMissingDoesNotRecomputeCompletedVectors() {
        insertSelectedSpace("REBUILDING");
        saveContent();
        runExecutorToIdle(); // vectors built → coverage 100% → ACTIVE
        assertEquals("ACTIVE", spaceState(SPACE_ID));
        jdbc.update("DELETE FROM embedding_job");

        RetrievalMaintenanceService.ApiResult result =
                maintenance.reindex(Map.of("scope", "all", "mode", "missing"));
        assertEquals(202, result.status());
        // Nothing missing: successful work is not recomputed.
        assertEquals(0, countJobs("QUEUED", "reindex"));
    }

    // ── reindex full ───────────────────────────────────────────────────────

    @Test
    void reindexFullRequeuesCompletedPages() {
        insertSelectedSpace("REBUILDING");
        saveContent();
        runExecutorToIdle();
        assertEquals("ACTIVE", spaceState(SPACE_ID));

        RetrievalMaintenanceService.ApiResult result =
                maintenance.reindex(Map.of("scope", "all", "mode", "full"));
        assertEquals(202, result.status());
        assertTrue(countJobs("QUEUED", "reindex-full") > 0);
        // Attempts reset on the re-queued row.
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM embedding_job WHERE reason LIKE 'reindex-full%'",
                Integer.class);
        assertEquals(0, attempts);
    }

    @Test
    void reindexCurrentScopeOnlyQueuesThatNotebook() {
        insertSelectedSpace("REBUILDING");
        saveContent();
        long otherNotebook = notebooks.insert("Other");
        long otherPage = notebooks.insertPage(otherNotebook, "Other Page", null);
        indexing.savePageAndIndex(otherPage, "Other Page", threeChunkContent("乙"));
        jdbc.update("DELETE FROM embedding_job");

        maintenance.reindex(Map.of(
                "scope", "current", "notebookId", notebookId, "mode", "missing"));

        assertTrue(countJobsForNotebook(notebookId, "reindex") > 0);
        assertEquals(0, countJobsForNotebook(otherNotebook, "reindex"));
    }

    // ── retry-failed ───────────────────────────────────────────────────────

    @Test
    void retryFailedRequeuesFailedJobs() {
        insertSelectedSpace("REBUILDING");
        saveContent();
        jdbc.update("UPDATE embedding_job SET status = 'FAILED', attempts = 5,"
                + " error = 'boom', next_run_at = NULL");

        Map<String, Object> result = maintenance.retryFailed(Map.of("scope", "all"));
        assertEquals(1, ((Number) result.get("requeued")).intValue());

        Integer queued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job WHERE status = 'QUEUED'", Integer.class);
        assertEquals(1, queued);
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM embedding_job", Integer.class);
        assertEquals(0, attempts);
    }

    @Test
    void retryFailedWithoutSpaceReturnsZero() {
        Map<String, Object> result = maintenance.retryFailed(Map.of("scope", "all"));
        assertEquals(0, ((Number) result.get("requeued")).intValue());
    }

    // ── stale-vector cleanup ───────────────────────────────────────────────

    @Test
    void cleanupRemovesDisabledDeselectedVectors() {
        insertSpace("spaceA", "DISABLED", 0.0, 0);
        saveContent();
        insertVector("spaceA", firstChunkId());

        int deleted = maintenance.cleanupDisabledSpaceVectors("spaceA");
        assertEquals(1, deleted);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_embedding WHERE embedding_space_id = 'spaceA'",
                Integer.class));
    }

    @Test
    void cleanupSkipsReselectedSpace() {
        insertSpace("spaceB", "ACTIVE", 1.0, 1);
        saveContent();
        insertVector("spaceB", firstChunkId());

        // Guard: an active selected space must never lose vectors here.
        int deleted = maintenance.cleanupDisabledSpaceVectors("spaceB");
        assertEquals(0, deleted);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_embedding WHERE embedding_space_id = 'spaceB'",
                Integer.class));
    }

    @Test
    void scheduleCleanupRunsAsynchronously() throws Exception {
        insertSpace("spaceC", "DISABLED", 0.0, 0);
        saveContent();
        insertVector("spaceC", firstChunkId());

        maintenance.scheduleDisabledSpaceCleanup("spaceC");

        long deadline = System.currentTimeMillis() + 3000;
        int remaining = 1;
        while (System.currentTimeMillis() < deadline) {
            remaining = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM retrieval_embedding WHERE embedding_space_id = 'spaceC'",
                    Integer.class);
            if (remaining == 0) break;
            Thread.sleep(20);
        }
        assertEquals(0, remaining);
    }
}
