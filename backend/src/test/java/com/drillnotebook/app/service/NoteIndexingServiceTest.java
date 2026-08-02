package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.NoteIndexingStartupBackfill;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

/**
 * Tests for NoteIndexingService using a Spring AOP proxy to verify
 * @Transactional rollback semantics.
 *
 * <p>The TestConfig creates a minimal Spring context with
 * {@link EnableTransactionManagement} so that every call to
 * {@link NoteIndexingService} goes through a CGLIB proxy.
 * A {@link FaultyRetrievalIndexRepository} is used for
 * mock-based FTS failure injection (no DROP TABLE).
 */
class NoteIndexingServiceTest {

    private AnnotationConfigApplicationContext ctx;
    private NoteIndexingService svc;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private FaultyRetrievalIndexRepository retrievalRepo;
    private NoteIndexingStartupBackfill backfill;
    private long notebookId;
    private long pageId;

    // ── Spring test configuration ───────────────────────────────────────────

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private java.nio.file.Path tempDir;

        @Bean
        DataSource dataSource() throws Exception {
            tempDir = Files.createTempDirectory("note-indexing-test");
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + tempDir.resolve("study.db")
                    + "?foreign_keys=on");
            new DatabaseInitializer(ds).initialize();
            return ds;
        }

        @Bean
        DataSourceTransactionManager txManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper();
        }

        @Bean
        NotebookRepository notebooks(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new NotebookRepository(jdbc, mapper);
        }

        @Bean
        RetrievalIndexRepository retrievalRepo(JdbcTemplate jdbc) {
            return new FaultyRetrievalIndexRepository(jdbc);
        }

        @Bean
        NoteIndexingService indexingService(
                NotebookRepository notebooks,
                RetrievalIndexRepository retrievalRepo,
                JdbcTemplate jdbc,
                ObjectMapper mapper) {
            return new NoteIndexingService(
                    notebooks, retrievalRepo, jdbc, mapper);
        }
    }

    // ── Fault-injection repository ──────────────────────────────────────────

    /**
     * A RetrievalIndexRepository that can simulate FTS insert failures
     * for testing transactional rollback. The FTS table is never dropped,
     * so it remains queryable after a rollback.
     */
    static class FaultyRetrievalIndexRepository
            extends RetrievalIndexRepository {

        volatile boolean failOnNextFtsInsert = false;

        FaultyRetrievalIndexRepository(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public void insertFtsRow(long rowid, String title,
                                 String headingPath, String text) {
            if (failOnNextFtsInsert) {
                failOnNextFtsInsert = false;
                throw new RuntimeException("Simulated FTS insert failure");
            }
            super.insertFtsRow(rowid, title, headingPath, text);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> content(String text) {
        return Map.of("type", "doc", "content",
                List.of(Map.of("type", "paragraph", "content",
                        List.of(Map.of("type", "text", "text", text)))));
    }

    private static final Map<String, Object> SAMPLE_CONTENT =
            content("Hello world");
    private static final Map<String, Object> SAMPLE_CONTENT_2 =
            content("Second version");

    private long countChunks() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk", Long.class);
    }

    private long countFtsRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk_fts", Long.class);
    }

    private long countJobs() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job", Long.class);
    }

    private List<Map<String, Object>> selectJobs() {
        return jdbc.query("SELECT * FROM embedding_job ORDER BY id",
                (rs, row) -> Map.of(
                    "id", rs.getLong("id"),
                    "source_content_hash", rs.getString("source_content_hash"),
                    "status", rs.getString("status")));
    }

    private String getPageHash(long pid) {
        return jdbc.queryForObject(
                "SELECT content_hash FROM note_page WHERE id = ?",
                String.class, pid);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Test fixture is not JSON-serializable", e);
        }
    }

    private List<Map<String, Object>> selectChunksBySource(String corpusType, long sourceId) {
        return jdbc.query(
                "SELECT id, title, chunk_index FROM retrieval_chunk"
                + " WHERE corpus_type = ? AND source_id = ? ORDER BY chunk_index",
                (rs, row) -> Map.of(
                    "id", rs.getLong("id"),
                    "title", rs.getString("title"),
                    "chunk_index", rs.getInt("chunk_index")),
                corpusType, sourceId);
    }

    /** Create an ACTIVE+SELECTED embedding space for tests that need one. */
    private String createActiveSpace(String suffix) {
        String spaceId = "space-" + suffix;
        jdbc.update(
                "INSERT INTO embedding_space"
                + "(embedding_space_id, canonical_contract_json,"
                + " provider_type, model_identifier, dimensions,"
                + " state, is_selected)"
                + " VALUES (?, '{}', 'test', 'test-model', 4,"
                + " 'ACTIVE', 1)",
                spaceId);
        return spaceId;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        svc = ctx.getBean(NoteIndexingService.class);
        jdbc = ctx.getBean(JdbcTemplate.class);
        mapper = ctx.getBean(ObjectMapper.class);
        retrievalRepo = (FaultyRetrievalIndexRepository) ctx.getBean(
                RetrievalIndexRepository.class);
        backfill = new NoteIndexingStartupBackfill(
                retrievalRepo, svc);

        var notebooks = ctx.getBean(NotebookRepository.class);
        notebookId = notebooks.insert("Test Notebook");
        pageId = notebooks.insertPage(
                notebookId, "Test Page", SAMPLE_CONTENT);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    // ── 1. Create/save with no space (no embedding job) ─────────────────────

    @Test
    void saveWithNoSelectedSpaceDoesNotCreateJob() {
        // No embedding space exists → no job
        assertNull(retrievalRepo.findSelectedSpace(),
                "no selected space");

        // Save content through the @Transactional proxy
        Map<String, Object> result =
                svc.savePageAndIndex(pageId, "Updated", SAMPLE_CONTENT);
        assertNotNull(result);
        assertEquals("Updated", result.get("title"));

        // Should have chunks and FTS
        assertTrue(countChunks() > 0, "should have chunks");
        assertTrue(countFtsRows() > 0, "should have FTS rows");
        assertEquals(0, countJobs(), "no embedding jobs without a space");
        assertNotNull(getPageHash(pageId), "content_hash should be set");
    }

    // ── 2. Selected ACTIVE space queues job and becomes REBUILDING ───────────

    @Test
    void saveWithActiveSpaceQueuesJobAndTransitionsToRebuilding() {
        // Insert a selected ACTIVE embedding space
        String spaceId = "test-space-1";
        jdbc.update(
                "INSERT INTO embedding_space"
                + "(embedding_space_id, canonical_contract_json,"
                + " provider_type, model_identifier, dimensions,"
                + " state, is_selected)"
                + " VALUES (?, '{}', 'test', 'test-model', 4,"
                + " 'ACTIVE', 1)",
                spaceId);

        // Save content through the @Transactional proxy
        svc.savePageAndIndex(pageId, null, SAMPLE_CONTENT);

        // Space should now be REBUILDING
        String state = jdbc.queryForObject(
                "SELECT state FROM embedding_space"
                + " WHERE embedding_space_id = ?",
                String.class, spaceId);
        assertEquals("REBUILDING", state);

        // Job should exist
        assertEquals(1, countJobs());
        Map<String, Object> job = selectJobs().get(0);
        assertEquals("QUEUED", job.get("status"));
    }

    // ── 3. Title-only keeps hash/chunks, syncs chunk + FTS titles ───────────

    @Test
    void titleOnlyUpdatePreservesHashAndChunks() {
        // First: save with content to establish hash and chunks
        svc.savePageAndIndex(pageId, "Original Title", SAMPLE_CONTENT);
        String hashBefore = getPageHash(pageId);
        List<Map<String, Object>> chunksBefore = selectChunksBySource(
                "NOTEBOOK", pageId);
        long chunkCountBefore = chunksBefore.size();
        long jobsBefore = countJobs();
        assertTrue(chunkCountBefore > 0);

        // Title-only update
        svc.savePageAndIndex(pageId, "New Title", null);

        // Hash preserved, chunks preserved
        assertEquals(hashBefore, getPageHash(pageId));
        assertEquals(chunkCountBefore, countChunks());
        assertEquals(jobsBefore, countJobs(),
                "title-only update must not change embedding jobs");
        assertEquals("New Title", jdbc.queryForObject(
                "SELECT title FROM note_page WHERE id = ?",
                String.class, pageId));

        // Chunk titles must be in sync
        List<Map<String, Object>> chunks = selectChunksBySource(
                "NOTEBOOK", pageId);
        assertEquals(chunkCountBefore, chunks.size());
        assertEquals(
                chunksBefore.stream().map(chunk -> chunk.get("id")).toList(),
                chunks.stream().map(chunk -> chunk.get("id")).toList(),
                "title-only update must preserve chunk ids");
        for (Map<String, Object> chunk : chunks) {
            assertEquals("New Title", chunk.get("title"),
                    "chunk title should match page title");
        }

        // FTS titles must be in sync
        for (Map<String, Object> chunk : chunks) {
            long cid = (Long) chunk.get("id");
            String ftsTitle = jdbc.queryForObject(
                    "SELECT title FROM retrieval_chunk_fts WHERE rowid = ?",
                    String.class, cid);
            assertEquals("New Title", ftsTitle,
                    "FTS title should match page title");
        }
    }

    // ── 4. Invalid JSON rolls back old note/chunks/FTS ──────────────────────

    @Test
    void invalidJsonContentRollsBackEntireSave() {
        // First: establish valid content via the proxy
        svc.savePageAndIndex(pageId, "Good", SAMPLE_CONTENT);
        assertTrue(countChunks() > 0);
        String oldHash = getPageHash(pageId);

        // Try to save content the normalizer rejects (plain string, not TipTap)
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                svc.savePageAndIndex(pageId, "Bad",
                        "not valid json at all"));
        assertTrue(ex.getMessage().contains("NORMALIZATION_ERROR"),
                "exception should mention NORMALIZATION_ERROR");

        // Old content preserved
        assertEquals("Good", jdbc.queryForObject(
                "SELECT title FROM note_page WHERE id = ?",
                String.class, pageId));
        assertEquals(oldHash, getPageHash(pageId));
        assertTrue(countChunks() > 0, "old chunks preserved");
        assertTrue(countFtsRows() > 0, "old FTS preserved");
    }

    // ── 5. Injected FTS failure rollback (mock-based, no DROP TABLE) ────────

    @Test
    void ftsFailureRollsBackEntireTransaction() {
        // First: establish valid state
        svc.savePageAndIndex(pageId, "Stable", SAMPLE_CONTENT);
        long chunksBeforeFts = countChunks();
        long ftsBefore = countFtsRows();
        String hashBeforeFts = getPageHash(pageId);

        // Inject FTS insert failure (the FaultyRetrievalIndexRepository
        // will throw on the next insertFtsRow call)
        retrievalRepo.failOnNextFtsInsert = true;
        assertThrows(RuntimeException.class, () ->
                svc.savePageAndIndex(pageId, "Should fail",
                        SAMPLE_CONTENT));

        // Old state must be preserved
        assertEquals("Stable", jdbc.queryForObject(
                "SELECT title FROM note_page WHERE id = ?",
                String.class, pageId));
        assertEquals(hashBeforeFts, getPageHash(pageId));
        assertEquals(chunksBeforeFts, countChunks());

        // FTS must still be queryable (table was never dropped)
        assertEquals(ftsBefore, countFtsRows(),
                "FTS rows preserved after rollback");
        List<Map<String, Object>> ftsResults = jdbc.query(
                "SELECT rowid FROM retrieval_chunk_fts LIMIT 1",
                (rs, row) -> Map.of("rowid", rs.getLong("rowid")));
        assertFalse(ftsResults.isEmpty(),
                "FTS table is still queryable after rollback");
    }

    // ── 6. Three rapid saves leave only latest hash valid ───────────────────

    @Test
    void threeRapidSavesLeaveOnlyLatestJobValid() {
        // Create a selected REBUILDING space
        String spaceId = "test-space-rapid";
        jdbc.update(
                "INSERT INTO embedding_space"
                + "(embedding_space_id, canonical_contract_json,"
                + " provider_type, model_identifier, dimensions,"
                + " state, is_selected)"
                + " VALUES (?, '{}', 'test', 'test-model', 4,"
                + " 'REBUILDING', 1)",
                spaceId);

        // Three rapid saves with different contents
        var content1 = content("v1");
        var content2 = content("v2");
        var content3 = content("v3");

        svc.savePageAndIndex(pageId, null, content1);
        String hash1 = getPageHash(pageId);

        svc.savePageAndIndex(pageId, null, content2);
        String hash2 = getPageHash(pageId);

        svc.savePageAndIndex(pageId, null, content3);
        String hash3 = getPageHash(pageId);

        // All three hashes should be different
        assertNotEquals(hash1, hash2);
        assertNotEquals(hash2, hash3);

        // Only the latest job should be QUEUED; previous should be SUPERSEDED
        List<Map<String, Object>> jobs = selectJobs();
        assertEquals(3, jobs.size(), "three jobs total");

        int queued = 0, superseded = 0;
        String latestHash = null;
        for (Map<String, Object> job : jobs) {
            String st = (String) job.get("status");
            if ("QUEUED".equals(st)) {
                queued++;
                latestHash = (String) job.get("source_content_hash");
            } else if ("SUPERSEDED".equals(st)) {
                superseded++;
            }
        }
        assertEquals(1, queued, "exactly one QUEUED job");
        assertEquals(2, superseded, "two SUPERSEDED jobs");
        assertEquals(hash3, latestHash, "latest hash is QUEUED");
    }

    // ── 7. Same-hash resave upserts (no duplicate job) ───────────────────────

    @Test
    void sameHashResaveUpsertsJobInsteadOfDuplicate() {
        createActiveSpace("same-hash");
        String spaceStateBefore = jdbc.queryForObject(
                "SELECT state FROM embedding_space WHERE embedding_space_id = 'space-same-hash'",
                String.class);

        svc.savePageAndIndex(pageId, null, SAMPLE_CONTENT);
        String hash1 = getPageHash(pageId);
        assertEquals(1, countJobs(), "one job after first save");
        List<Map<String, Object>> jobs1 = selectJobs();
        assertEquals("QUEUED", jobs1.get(0).get("status"));

        jdbc.update(
                "UPDATE embedding_job SET status = 'CLAIMED', attempts = 3,"
                        + " claim_token = 'stale-claim', error = 'stale-error',"
                        + " next_run_at = '2099-01-01'"
                        + " WHERE source_content_hash = ?",
                hash1);

        // Save same content again — same normalized hash
        svc.savePageAndIndex(pageId, null, SAMPLE_CONTENT);
        String hash2 = getPageHash(pageId);
        assertEquals(hash1, hash2, "same content = same hash");

        // Must still be exactly one job, QUEUED (no duplicate)
        assertEquals(1, countJobs(), "still one job after same-hash resave");
        assertEquals("QUEUED", selectJobs().get(0).get("status"));

        // Upsert resets attempts/claim_token — verify the job row was touched
        int upserted = jdbc.queryForObject(
                "SELECT attempts FROM embedding_job WHERE status = 'QUEUED' AND source_content_hash = ?",
                Integer.class, hash1);
        assertEquals(0, upserted, "upsert resets attempts to 0");
        assertNull(jdbc.queryForObject(
                "SELECT claim_token FROM embedding_job WHERE source_content_hash = ?",
                String.class, hash1));
        assertNull(jdbc.queryForObject(
                "SELECT error FROM embedding_job WHERE source_content_hash = ?",
                String.class, hash1));
        assertNull(jdbc.queryForObject(
                "SELECT next_run_at FROM embedding_job WHERE source_content_hash = ?",
                String.class, hash1));
    }

    // ── 8. A→B→A requeues A and supersedes B ───────────────────────────────

    @Test
    void saveAThenBThenARequeuesAAndSupersedesB() {
        createActiveSpace("aba");

        var contentA = content("Version A");
        var contentB = content("Version B");

        svc.savePageAndIndex(pageId, null, contentA);
        String hashA = getPageHash(pageId);
        assertEquals(1, countJobs());
        jdbc.update(
                "UPDATE embedding_job SET status = 'COMPLETED'"
                        + " WHERE source_content_hash = ?",
                hashA);

        svc.savePageAndIndex(pageId, null, contentB);
        String hashB = getPageHash(pageId);
        assertNotEquals(hashA, hashB);
        assertEquals(2, countJobs());
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM embedding_job WHERE source_content_hash = ?",
                String.class, hashA),
                "completed old hash becomes superseded when B is current");

        // Revert to A — upserts the existing (superseded) hash-A row
        // back to QUEUED, so there are still only 2 rows total
        svc.savePageAndIndex(pageId, null, contentA);
        String hashA2 = getPageHash(pageId);
        assertEquals(hashA, hashA2, "back to A's content");
        assertEquals(2, countJobs(), "two job rows total (upsert, not insert)");

        List<Map<String, Object>> jobs = selectJobs();
        assertEquals(2, jobs.size());

        int queued = 0, superseded = 0;
        for (Map<String, Object> job : jobs) {
            String st = (String) job.get("status");
            if ("QUEUED".equals(st)) {
                queued++;
                assertEquals(hashA, job.get("source_content_hash"),
                        "QUEUED job must be for hash A");
            } else if ("SUPERSEDED".equals(st)) {
                superseded++;
            }
        }
        assertEquals(1, queued, "exactly one QUEUED job (for A)");
        assertEquals(1, superseded, "one SUPERSEDED job (hash B)");
    }

    // ── 9. Zero-chunk supersedes old jobs and creates no new job ────────────

    @Test
    void zeroChunkSaveSupersedesOldJobsAndCreatesNoNewJob() {
        createActiveSpace("zero-super");

        // First: non-empty content → creates chunks + job
        svc.savePageAndIndex(pageId, "Has chunks", SAMPLE_CONTENT);
        assertEquals(1, countJobs(), "one job after non-empty save");
        assertTrue(countChunks() > 0, "chunks exist after non-empty save");
        String oldHash = getPageHash(pageId);

        // Second: zero-chunk (questionBlock-only) content
        String questionOnly = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"questionBlock\",\"attrs\":{"
                + "\"questionId\":1,\"snapshot\":{}}}]}";
        Object parsed;
        try {
            parsed = mapper.readTree(questionOnly);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        svc.savePageAndIndex(pageId, "Zero only", parsed);

        // Chunks/FTS gone
        assertEquals(0, countChunks(), "no chunks after zero-chunk save");
        assertEquals(0, countFtsRows(), "no FTS after zero-chunk save");

        // Old job must be SUPERSEDED
        List<Map<String, Object>> jobs = selectJobs();
        assertEquals(1, jobs.size(), "one job row total");
        assertEquals("SUPERSEDED", jobs.get(0).get("status"),
                "old job is SUPERSEDED");
        assertNotEquals(oldHash, getPageHash(pageId),
                "content hash changed");

        // No new QUEUED job was created (zero chunks → no job)
        long queuedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job WHERE status = 'QUEUED'",
                Long.class);
        assertEquals(0, queuedCount, "no QUEUED jobs after zero-chunk save");
    }

    // ── 10. Page delete cleanup ─────────────────────────────────────────────

    @Test
    void pageDeleteRemovesChunksFtsAndJobs() {
        // Establish content with chunks
        svc.savePageAndIndex(pageId, "To Delete", SAMPLE_CONTENT);
        assertTrue(countChunks() > 0);

        // Delete through the @Transactional proxy
        svc.deletePage(pageId);

        // Everything gone
        assertEquals(0, countChunks());
        assertEquals(0, countFtsRows());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_page WHERE id = ?",
                Integer.class, pageId));
    }

    // ── 11. Notebook delete cleanup ────────────────────────────────────────

    @Test
    void notebookDeleteRemovesAllIndexData() {
        // Create another page in the same notebook
        var notebooks = ctx.getBean(NotebookRepository.class);
        long page2 = notebooks.insertPage(notebookId, "Page 2", null);
        svc.savePageAndIndex(pageId, "P1", SAMPLE_CONTENT);
        svc.savePageAndIndex(page2, "P2", SAMPLE_CONTENT);
        assertTrue(countChunks() >= 2);
        assertTrue(countFtsRows() >= 2);

        // Insert study_plan_item entries for both pages
        jdbc.update(
                "INSERT INTO study_plan_group"
                + "(id, plan_date, title, source)"
                + " VALUES (999, '2026-01-01', 'test-group', 'manual')");
        for (long pid : new long[]{pageId, page2}) {
            jdbc.update(
                    "INSERT INTO study_plan_item"
                    + "(group_id, plan_date, resource_type, resource_id, title)"
                    + " VALUES (999, '2026-01-01', 'note_page', ?, 'item')",
                    pid);
        }
        assertEquals(2, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_item"
                + " WHERE resource_type = 'note_page' AND resource_id IN (?, ?)",
                Long.class, pageId, page2));

        // Delete the notebook through the @Transactional proxy
        svc.deleteNotebook(notebookId);

        // Everything should be gone
        assertEquals(0, countChunks());
        assertEquals(0, countFtsRows());

        // Study plan items for these pages must be cleaned up
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_item"
                + " WHERE resource_type = 'note_page' AND resource_id IN (?, ?)",
                Long.class, pageId, page2),
                "study_plan_item entries removed");
        // Empty group should be deleted
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_group WHERE id = 999",
                Long.class),
                "empty study_plan_group deleted");

        // Pages gone
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_page WHERE notebook_id = ?",
                Integer.class, notebookId));
    }

    // ── 12. Zero-chunk hash/no job ─────────────────────────────────────────

    @Test
    void zeroChunkPageGetsHashButNoJob() {
        // questionBlock-only content produces zero chunks
        String questionOnly = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"questionBlock\",\"attrs\":{"
                + "\"questionId\":1,\"snapshot\":{}}}]}";
        Object parsed;
        try { parsed = mapper.readTree(questionOnly); }
        catch (Exception e) { throw new RuntimeException(e); }

        svc.savePageAndIndex(pageId, "Q only", parsed);

        // Should have a content_hash
        assertNotNull(getPageHash(pageId));
        assertFalse(getPageHash(pageId).isBlank());
        // No chunks
        assertEquals(0, countChunks());
        assertEquals(0, countFtsRows());
        // No embedding job (zero chunks)
        assertEquals(0, countJobs());
    }

    // ── 13. Backfill produces full chunks/FTS/jobs ─────────────────────────

    @Test
    void backfillIndexesOnlyNullPagesAndIsIdempotent() {
        // Index the setUp page so it has a content_hash and won't pollute
        // the NULL-hash scan.
        svc.savePageAndIndex(pageId, "Test Page", SAMPLE_CONTENT);

        // Insert a page with NULL content_hash (as if from v7 migration)
        long newPageId = jdbc.queryForObject(
                "INSERT INTO note_page(notebook_id, title, content) "
                + "VALUES (?, 'old', ?) RETURNING id",
                Long.class, notebookId, json(SAMPLE_CONTENT));

        // Verify content_hash is NULL
        assertNull(getPageHash(newPageId));
        String contentBefore = jdbc.queryForObject(
                "SELECT content FROM note_page WHERE id = ?",
                String.class, newPageId);

        backfill.backfillAll();

        // Now content_hash, chunks, and FTS should all exist
        assertNotNull(getPageHash(newPageId));
        assertFalse(getPageHash(newPageId).isBlank());
        assertEquals(contentBefore, jdbc.queryForObject(
                "SELECT content FROM note_page WHERE id = ?",
                String.class, newPageId),
                "backfill must not rewrite stored note content");
        assertTrue(countChunks() > 0, "backfill should create chunks");
        assertTrue(countFtsRows() > 0, "backfill should create FTS rows");

        // The trigram MATCH query should work on backfilled content
        List<Map<String, Object>> results = jdbc.query(
                "SELECT rowid FROM retrieval_chunk_fts"
                + " WHERE retrieval_chunk_fts MATCH ?",
                (rs, row) -> Map.of("rowid", rs.getLong("rowid")),
                "\"llo\"");
        assertFalse(results.isEmpty(),
                "backfilled content is FTS-MATCH-queryable");

        // No more pages with NULL hash
        assertTrue(
                retrievalRepo.findPagesWithNullContentHash().isEmpty(),
                "no more NULL content_hash pages after backfill");

        long chunksAfterFirstRun = countChunks();
        long ftsAfterFirstRun = countFtsRows();
        backfill.backfillAll();
        assertEquals(chunksAfterFirstRun, countChunks());
        assertEquals(ftsAfterFirstRun, countFtsRows());
    }

    @Test
    void backfillQueuesSelectedSpaceJobAndTransitionsToRebuilding() {
        svc.savePageAndIndex(pageId, "Test Page", SAMPLE_CONTENT);
        jdbc.update(
                "INSERT INTO embedding_space"
                + "(embedding_space_id, canonical_contract_json,"
                + " provider_type, model_identifier, dimensions, state, is_selected)"
                + " VALUES ('backfill-space', '{}', 'test', 'test-model', 4,"
                + " 'ACTIVE', 1)");
        long legacyPageId = jdbc.queryForObject(
                "INSERT INTO note_page(notebook_id, title, content)"
                + " VALUES (?, 'legacy', ?) RETURNING id",
                Long.class, notebookId, json(SAMPLE_CONTENT));

        backfill.backfillAll();

        assertNotNull(getPageHash(legacyPageId));
        assertEquals(1, countJobs());
        assertEquals("REBUILDING", jdbc.queryForObject(
                "SELECT state FROM embedding_space WHERE embedding_space_id = 'backfill-space'",
                String.class));
    }

    @Test
    void backfillRecordsValidZeroChunkPageWithoutJob() {
        svc.savePageAndIndex(pageId, "Test Page", SAMPLE_CONTENT);
        String questionOnly = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"questionBlock\",\"attrs\":{"
                + "\"questionId\":1,\"snapshot\":{}}}]}";
        long legacyPageId = jdbc.queryForObject(
                "INSERT INTO note_page(notebook_id, title, content)"
                + " VALUES (?, 'zero', ?) RETURNING id",
                Long.class, notebookId, questionOnly);

        backfill.backfillAll();

        assertNotNull(getPageHash(legacyPageId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk WHERE source_id = ?",
                Integer.class, legacyPageId));
        assertEquals(0, countJobs());
    }

    @Test
    void backfillLeavesMalformedPagePending() {
        svc.savePageAndIndex(pageId, "Test Page", SAMPLE_CONTENT);
        long malformedPageId = jdbc.queryForObject(
                "INSERT INTO note_page(notebook_id, title, content)"
                + " VALUES (?, 'bad', 'not-json') RETURNING id",
                Long.class, notebookId);

        backfill.backfillAll();

        assertNull(getPageHash(malformedPageId));
        assertTrue(retrievalRepo.findPagesWithNullContentHash().stream()
                .anyMatch(row -> ((Number) row.get("page_id")).longValue()
                        == malformedPageId));
    }

    // ── 17. Verify trigram FTS MATCH works ──────────────────────────────────

    @Test
    void trigramFtsMatchReturnsResults() {
        svc.savePageAndIndex(pageId, "FTS Test", SAMPLE_CONTENT);
        assertTrue(countFtsRows() > 0);

        // Query with trigram MATCH (3+ chars for trigram tokens)
        // "llo" is a 3-char substring that should match "Hello"
        List<Map<String, Object>> results = jdbc.query(
                "SELECT rowid, title FROM retrieval_chunk_fts"
                + " WHERE retrieval_chunk_fts MATCH ?",
                (rs, row) -> Map.of(
                    "rowid", rs.getLong("rowid"),
                    "title", rs.getString("title")),
                "\"llo\"");
        assertFalse(results.isEmpty(), "trigram MATCH should find results");
        assertTrue(results.stream().anyMatch(
                r -> "FTS Test".equals(r.get("title"))));
    }

    // ── 18. FTS rowid equals chunk id ──────────────────────────────────────

    @Test
    void ftsRowidEqualsChunkId() {
        svc.savePageAndIndex(pageId, "Rowid Test", SAMPLE_CONTENT);

        // For each chunk, the FTS rowid should match
        List<Long> chunkIds = jdbc.query(
                "SELECT id FROM retrieval_chunk ORDER BY chunk_index",
                (rs, row) -> rs.getLong("id"));
        assertFalse(chunkIds.isEmpty());

        for (long cid : chunkIds) {
            Long ftsRowid = jdbc.queryForObject(
                    "SELECT rowid FROM retrieval_chunk_fts WHERE rowid = ?",
                    Long.class, cid);
            assertEquals(cid, ftsRowid,
                    "FTS rowid must equal chunk id");
        }
    }

    // ── 19. Malformed create rolls back entire transaction ─────────────────

    @Test
    void invalidJsonCreatePageRollsBack() {
        long pageCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_page WHERE notebook_id = ?",
                Long.class, notebookId);

        // Attempt to create a page with invalid TipTap content
        assertThrows(IllegalArgumentException.class, () ->
                svc.createAndIndexPage(notebookId, "Bad",
                        "not valid json at all"));

        // No new page should exist — the insert was rolled back
        long pageCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM note_page WHERE notebook_id = ?",
                Long.class, notebookId);
        assertEquals(pageCountBefore, pageCountAfter,
                "no page created when content is malformed");

        // No chunks or FTS from the failed create
        assertEquals(0, countChunks());
        assertEquals(0, countFtsRows());
    }

    // ── 20. Create page happy path (createAndIndexPage) ────────────────────

    @Test
    void createAndIndexPageWorks() {
        Map<String, Object> page = svc.createAndIndexPage(
                notebookId, "New", SAMPLE_CONTENT);
        assertNotNull(page);
        assertNotNull(page.get("id"));

        long newId = ((Number) page.get("id")).longValue();

        // Verify content_hash, chunks, FTS
        assertNotNull(getPageHash(newId));
        assertTrue(countChunks() > 0);
        assertTrue(countFtsRows() > 0);
    }
}
