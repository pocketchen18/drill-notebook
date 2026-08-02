package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.NoteIndexingStartupBackfill;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Task 15 QA evidence journeys (service-layer equivalents of the planned Bash
 * scenarios). The assertions always run; evidence files are only written when
 * {@code DRILL_EVIDENCE_DIR} is set.
 *
 * <ul>
 *   <li>{@code task-15-backfill-switch.json} — v8 first-start async backfill,
 *       BM25-only during a model/space switch, hybrid restored at 100%
 *       coverage, stale vectors of the disabled space removed.</li>
 *   <li>{@code task-15-reindex-race.txt} — a full rebuild job claimed for an
 *       old hash is superseded when autosave lands a new hash; only the newest
 *       content_hash survives in chunks/FTS/vectors.</li>
 * </ul>
 */
class RetrievalMaintenanceEvidenceTest {

    private static final int DIMS_A = 512;
    private static final int DIMS_B = 384;
    private static final String SPACE_A = "space-evidence-a";
    private static final String SPACE_B = "space-evidence-b";
    private static final String SPACE_RACE = "space-evidence-race";

    private Path tempDir;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private NotebookRepository notebooks;
    private RetrievalIndexRepository retrievalRepo;
    private NoteIndexingService indexing;
    private EmbeddingJobRepository jobs;
    private EmbeddingModelRepository models;
    private EmbeddingProviderRegistry registry;
    private EmbeddingJobExecutor executor;
    private RetrievalMaintenanceService maintenance;
    private RetrievalStatusService statusService;
    private HybridRetrievalService hybrid;

    /** Fixed-vector provider with parametrizable dimensions. */
    static class FakeProvider implements EmbeddingProvider {
        private final int dims;
        FakeProvider(int dims) { this.dims = dims; }
        @Override public String providerType() { return "local-rust"; }
        @Override public String modelId() { return "test-model"; }
        @Override public int dimensions() { return dims; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<List<Float>> embedDocuments(List<String> texts) {
            List<List<Float>> out = new ArrayList<>();
            for (String ignored : texts) out.add(vector());
            return out;
        }
        @Override public List<Float> embedQuery(String text) { return vector(); }
        private List<Float> vector() {
            List<Float> vec = new ArrayList<>(dims);
            for (int i = 0; i < dims; i++) vec.add((i + 1) / (float) dims);
            return vec;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("retrieval-maint-evidence");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("study.db") + "?foreign_keys=on");
        new DatabaseInitializer(ds).initialize();

        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper();
        notebooks = new NotebookRepository(jdbc, mapper);
        retrievalRepo = new RetrievalIndexRepository(jdbc);
        indexing = new NoteIndexingService(notebooks, retrievalRepo, jdbc, mapper);

        jobs = new EmbeddingJobRepository(jdbc);
        models = new EmbeddingModelRepository(jdbc);
        registry = new EmbeddingProviderRegistry();
        executor = new EmbeddingJobExecutor(
                jobs, registry, new DataSourceTransactionManager(ds), false, 1000);
        maintenance = new RetrievalMaintenanceService(models, jobs, executor);
        statusService = new RetrievalStatusService(jobs);
        hybrid = new HybridRetrievalService(
                new RetrievalService(retrievalRepo, notebooks),
                retrievalRepo, jobs, registry, 1500, 500);
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

    private void insertSelectedSpace(String spaceId, int dims, String state) {
        jdbc.update(
                "INSERT INTO embedding_space(embedding_space_id,"
                        + " canonical_contract_json, provider_type, model_identifier,"
                        + " dimensions, state, coverage, is_selected)"
                        + " VALUES (?, '{}', 'local-rust', 'test-model', ?, ?, 0.0, 1)",
                spaceId, dims, state);
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

    private void runExecutorToIdle() {
        int guard = 0;
        while (executor.runOnce() && guard++ < 500) { /* drain */ }
    }

    private String spaceState(String spaceId) {
        return jdbc.queryForObject(
                "SELECT state FROM embedding_space WHERE embedding_space_id = ?",
                String.class, spaceId);
    }

    private List<RetrievalHit> searchAll(String text) {
        return hybrid.retrieve(new RetrievalQuery(
                text, RetrievalQuery.Scope.ALL, null, RetrievalQuery.Corpus.NOTEBOOK)).hits();
    }

    private void writeEvidence(String name, Object payload) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        Files.createDirectories(Path.of(dir));
        Files.writeString(Path.of(dir, name),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private void writeEvidenceLines(String name, List<String> lines) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        Files.createDirectories(Path.of(dir));
        List<String> out = new ArrayList<>();
        out.add("generatedBy: RetrievalMaintenanceEvidenceTest");
        out.addAll(lines);
        Files.writeString(Path.of(dir, name),
                String.join(System.lineSeparator(), out) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    // ── Scenario 1: v8 首启后台回填与模型切换 ──────────────────────────────

    @Test
    void backfillAndModelSwitchJourney() throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();

        // 1. Ten legacy v7 pages: content present, content_hash still NULL.
        long notebookId = notebooks.insert("Legacy Notebook");
        for (int i = 0; i < 10; i++) {
            notebooks.insertPage(notebookId, "Legacy Page " + i, threeChunkContent("甲"));
        }
        int nullHashPages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_page WHERE content_hash IS NULL", Integer.class);
        assertEquals(10, nullHashPages);
        steps.add(step("legacy-pages", Map.of("pagesWithNullContentHash", nullHashPages)));

        // 2. Startup health/status succeeds immediately, before any backfill.
        Map<String, Object> statusBefore = statusService.status("all", null);
        assertEquals("DISABLED", statusBefore.get("indexState"));
        steps.add(step("health-before-backfill", statusBefore));

        // 3. Async startup backfill builds text/chunk/FTS (no provider yet).
        new NoteIndexingStartupBackfill(retrievalRepo, indexing).backfillAll();
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_page WHERE content_hash IS NULL", Integer.class));
        Map<String, Object> statusAfterBackfill = statusService.status("all", null);
        assertEquals(10, ((Number) statusAfterBackfill.get("totalPages")).intValue());
        assertEquals(30, ((Number) statusAfterBackfill.get("totalChunks")).intValue());
        assertEquals("DISABLED", statusAfterBackfill.get("indexState"));
        List<RetrievalHit> ftsHits = searchAll("段落0");
        assertFalse(ftsHits.isEmpty(), "FTS must be searchable right after backfill");
        Map<String, Object> backfillStep = new LinkedHashMap<>(statusAfterBackfill);
        backfillStep.put("bm25Hits", ftsHits.size());
        steps.add(step("after-startup-backfill", backfillStep));

        // 4. Activate space A (512-dim) and build vectors → hybrid available.
        insertSelectedSpace(SPACE_A, DIMS_A, "REBUILDING");
        registry.setActive(new FakeProvider(DIMS_A));
        models.enqueueMissingJobs(SPACE_A, "activate-backfill");
        executor.wake();
        runExecutorToIdle();
        assertEquals("ACTIVE", spaceState(SPACE_A));
        HybridRetrievalService.Result hybridA = hybrid.retrieve(new RetrievalQuery(
                "段落0", RetrievalQuery.Scope.ALL, null, RetrievalQuery.Corpus.NOTEBOOK));
        assertNull(hybridA.notice(), "space A active → full hybrid, no degrade notice");
        assertFalse(hybridA.hits().isEmpty());
        Map<String, Object> statusA = statusService.status("all", null);
        assertEquals("ACTIVE", statusA.get("indexState"));
        Map<String, Object> aStep = new LinkedHashMap<>(statusA);
        aStep.put("hybridNotice", null);
        aStep.put("hits", hybridA.hits().size());
        steps.add(step("space-a-active-hybrid", aStep));

        // 5. Switch to fake space B (384-dim): A deselected+DISABLED, B REBUILDING.
        models.deselectCurrentSpace();
        models.upsertSelectedRebuildingSpace(
                SPACE_B, "{}", "local-rust", "fake-384", DIMS_B);
        models.enqueueMissingJobs(SPACE_B, "activate-backfill");
        registry.setActive(new FakeProvider(DIMS_B));
        assertEquals("DISABLED", spaceState(SPACE_A));
        Map<String, Object> statusSwitch = statusService.status("all", null);
        assertEquals("REBUILDING", statusSwitch.get("indexState"));
        HybridRetrievalService.Result hybridSwitch = hybrid.retrieve(new RetrievalQuery(
                "段落0", RetrievalQuery.Scope.ALL, null, RetrievalQuery.Corpus.NOTEBOOK));
        assertNotNull(hybridSwitch.notice(), "switch period must degrade to BM25-only");
        assertEquals("vector-index-unavailable", hybridSwitch.notice().get("code"));
        assertFalse(hybridSwitch.hits().isEmpty(), "BM25 still answers during the switch");
        Map<String, Object> switchStep = new LinkedHashMap<>(statusSwitch);
        switchStep.put("hybridNotice", hybridSwitch.notice().get("code"));
        switchStep.put("bm25Hits", hybridSwitch.hits().size());
        steps.add(step("switch-to-space-b-bm25-only", switchStep));

        // 6. Space B reaches 100% coverage → hybrid restored automatically.
        runExecutorToIdle();
        assertEquals("ACTIVE", spaceState(SPACE_B));
        Map<String, Object> statusB = statusService.status("all", null);
        assertEquals("ACTIVE", statusB.get("indexState"));
        assertEquals(1.0, ((Number) statusB.get("coverage")).doubleValue(), 1e-9);
        HybridRetrievalService.Result hybridB = hybrid.retrieve(new RetrievalQuery(
                "段落0", RetrievalQuery.Scope.ALL, null, RetrievalQuery.Corpus.NOTEBOOK));
        assertNull(hybridB.notice(), "100% coverage → hybrid restored");
        Map<String, Object> bStep = new LinkedHashMap<>(statusB);
        bStep.put("hybridNotice", null);
        bStep.put("hits", hybridB.hits().size());
        steps.add(step("space-b-active-hybrid-restored", bStep));

        // 7. Stale vectors of the disabled space A are removed asynchronously.
        int vectorsABefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_embedding WHERE embedding_space_id = ?",
                Integer.class, SPACE_A);
        assertTrue(vectorsABefore > 0);
        int deleted = maintenance.cleanupDisabledSpaceVectors(SPACE_A);
        assertEquals(vectorsABefore, deleted);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_embedding WHERE embedding_space_id = ?",
                Integer.class, SPACE_A));
        steps.add(step("cleanup-disabled-space-a", Map.of(
                "deletedVectors", deleted, "remainingVectors", 0)));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("generatedBy", "RetrievalMaintenanceEvidenceTest");
        evidence.put("scenario", "v8 首启后台回填与模型切换");
        evidence.put("expected", List.of(
                "health 先成功", "FTS 逐步完成", "切换期间 BM25-only",
                "fake space coverage=100% 后 hybrid", "旧空间 vectors 异步删除"));
        evidence.put("steps", steps);
        writeEvidence("task-15-backfill-switch.json", evidence);
    }

    private static Map<String, Object> step(String name, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", name);
        m.putAll(data);
        return m;
    }

    // ── Scenario 2: 重建期间 autosave 获胜 ─────────────────────────────────

    @Test
    void reindexRaceAutosaveWins() throws Exception {
        insertSelectedSpace(SPACE_RACE, DIMS_A, "REBUILDING");
        registry.setActive(new FakeProvider(DIMS_A));
        long notebookId = notebooks.insert("Race Notebook");
        long pageId = notebooks.insertPage(notebookId, "Race Page", null);

        // Baseline content v1 → vectors built, space ACTIVE.
        indexing.savePageAndIndex(pageId, "Race Page", threeChunkContent("甲"));
        runExecutorToIdle();
        assertEquals("ACTIVE", spaceState(SPACE_RACE));
        String hashV1 = jdbc.queryForObject(
                "SELECT content_hash FROM note_page WHERE id = ?", String.class, pageId);

        // Full rebuild enqueues a job for the current (old) hash.
        RetrievalMaintenanceService.ApiResult reindex =
                maintenance.reindex(Map.of("scope", "all", "mode", "full"));
        assertEquals(202, reindex.status());
        int activeAfterReindex = models.countActiveReindexJobs(SPACE_RACE, null);
        assertTrue(activeAfterReindex > 0, "reindex-full job must be active");

        // Autosave lands new content before the rebuild job is committed.
        indexing.savePageAndIndex(pageId, "Race Page", threeChunkContent("乙"));
        String hashV2 = jdbc.queryForObject(
                "SELECT content_hash FROM note_page WHERE id = ?", String.class, pageId);
        assertNotEquals(hashV1, hashV2);

        // Drain the queue: the stale rebuild job is superseded, v2 completes.
        runExecutorToIdle();
        assertEquals("ACTIVE", spaceState(SPACE_RACE));

        List<String> chunkHashes = jdbc.queryForList(
                "SELECT DISTINCT content_hash FROM retrieval_chunk WHERE source_id = ?",
                String.class, pageId);
        List<String> vectorHashes = jdbc.queryForList(
                "SELECT DISTINCT e.content_hash FROM retrieval_embedding e"
                        + " JOIN retrieval_chunk c ON c.id = e.chunk_id"
                        + " WHERE c.source_id = ?",
                String.class, pageId);
        int supersededReindexJobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job WHERE reason LIKE 'reindex%'"
                        + " AND status = 'SUPERSEDED' AND source_content_hash = ?",
                Integer.class, hashV1);
        int completedV2Jobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job WHERE status = 'COMPLETED'"
                        + " AND source_content_hash = ?",
                Integer.class, hashV2);
        int activeReindexAfter = models.countActiveReindexJobs(SPACE_RACE, null);

        assertEquals(List.of(hashV2), chunkHashes, "chunks keep only the newest hash");
        assertEquals(List.of(hashV2), vectorHashes, "vectors keep only the newest hash");
        assertTrue(supersededReindexJobs >= 1, "stale rebuild job must be SUPERSEDED");
        assertTrue(completedV2Jobs >= 1, "autosave job must complete");
        assertEquals(0, activeReindexAfter, "no duplicate active rebuild remains");

        List<String> lines = new ArrayList<>();
        lines.add("scenario: 重建期间 autosave 获胜");
        lines.add("hashV1=" + hashV1);
        lines.add("hashV2=" + hashV2);
        lines.add("reindexFirstStatus=" + reindex.status());
        lines.add("activeReindexJobsAfterReindex=" + activeAfterReindex);
        lines.add("chunkContentHashes=" + chunkHashes);
        lines.add("vectorContentHashes=" + vectorHashes);
        lines.add("supersededReindexJobsForV1=" + supersededReindexJobs);
        lines.add("completedJobsForV2=" + completedV2Jobs);
        lines.add("activeReindexJobsAfterDrain=" + activeReindexAfter);
        lines.add("expected: DB/FTS/vector 仅含新 hash; 旧任务 SUPERSEDED; 无重复 active rebuild");
        writeEvidenceLines("task-15-reindex-race.txt", lines);
    }
}
