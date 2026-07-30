package com.drillnotebook.app.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for the durable {@code embedding_job} queue, the
 * {@code retrieval_embedding} vector store and embedding-space coverage.
 *
 * <p>All methods are non-transactional; the job executor wraps them in short
 * transactions via {@code TransactionTemplate}. Claims are single-row atomic
 * updates guarded by a unique {@code claim_token}, so a restarted executor
 * can never complete a job claimed by a previous generation.
 */
@Repository
public class EmbeddingJobRepository {

    private final JdbcTemplate jdbc;

    public EmbeddingJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ClaimedJob(
            long id,
            String corpusType,
            long sourceId,
            String sourceContentHash,
            String embeddingSpaceId,
            int attempts
    ) {}

    // ── Claiming ────────────────────────────────────────────────────────────

    /**
     * Atomically claim the oldest runnable job for a space (QUEUED/RETRY with
     * due {@code next_run_at}), incrementing attempts. Returns {@code null}
     * when no job is runnable.
     */
    public ClaimedJob claimNext(String claimToken, String embeddingSpaceId) {
        int updated = jdbc.update(
                "UPDATE embedding_job SET status = 'CLAIMED', claim_token = ?,"
                        + " attempts = attempts + 1, updated_at = datetime('now')"
                        + " WHERE id = (SELECT id FROM embedding_job"
                        + "   WHERE status IN ('QUEUED', 'RETRY')"
                        + "   AND embedding_space_id = ?"
                        + "   AND (next_run_at IS NULL OR next_run_at <= datetime('now'))"
                        + "   ORDER BY id LIMIT 1)",
                claimToken, embeddingSpaceId);
        if (updated == 0) return null;
        List<ClaimedJob> rows = jdbc.query(
                "SELECT id, corpus_type, source_id, source_content_hash,"
                        + " embedding_space_id, attempts"
                        + " FROM embedding_job WHERE claim_token = ? AND status = 'CLAIMED'",
                (rs, row) -> new ClaimedJob(
                        rs.getLong("id"),
                        rs.getString("corpus_type"),
                        rs.getLong("source_id"),
                        rs.getString("source_content_hash"),
                        rs.getString("embedding_space_id"),
                        rs.getInt("attempts")),
                claimToken);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int markCompleted(long id, String claimToken) {
        return jdbc.update(
                "UPDATE embedding_job SET status = 'COMPLETED', claim_token = NULL,"
                        + " error = NULL, next_run_at = NULL, updated_at = datetime('now')"
                        + " WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'",
                id, claimToken);
    }

    public int markRetry(long id, String claimToken, String error, int backoffSeconds) {
        return jdbc.update(
                "UPDATE embedding_job SET status = 'RETRY', claim_token = NULL,"
                        + " error = ?, next_run_at = datetime('now', '+' || ? || ' seconds'),"
                        + " updated_at = datetime('now')"
                        + " WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'",
                error, backoffSeconds, id, claimToken);
    }

    public int markFailed(long id, String claimToken, String error) {
        return jdbc.update(
                "UPDATE embedding_job SET status = 'FAILED', claim_token = NULL,"
                        + " error = ?, next_run_at = NULL, updated_at = datetime('now')"
                        + " WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'",
                error, id, claimToken);
    }

    public int markSuperseded(long id, String claimToken) {
        return jdbc.update(
                "UPDATE embedding_job SET status = 'SUPERSEDED', claim_token = NULL,"
                        + " next_run_at = NULL, updated_at = datetime('now')"
                        + " WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'",
                id, claimToken);
    }

    /**
     * Startup recovery: release jobs that were CLAIMED by a previous process
     * generation back to QUEUED (attempts are preserved).
     */
    public int recoverClaimedJobs() {
        return jdbc.update(
                "UPDATE embedding_job SET status = 'QUEUED', claim_token = NULL,"
                        + " updated_at = datetime('now') WHERE status = 'CLAIMED'");
    }

    // ── Job payload reads ──────────────────────────────────────────────────

    /** Latest chunks of a source matching the job's content hash, ordered by chunk_index. */
    public List<Map<String, Object>> findChunksForJob(
            String corpusType, long sourceId, String contentHash) {
        return jdbc.query(
                "SELECT id, chunk_index, text FROM retrieval_chunk"
                        + " WHERE corpus_type = ? AND source_id = ? AND content_hash = ?"
                        + " ORDER BY chunk_index",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("chunk_index", rs.getInt("chunk_index"));
                    m.put("text", rs.getString("text"));
                    return m;
                },
                corpusType, sourceId, contentHash);
    }

    /** Current content hash of a note page, or {@code null} when the page is gone. */
    public String findPageContentHash(long pageId) {
        List<String> rows = jdbc.query(
                "SELECT content_hash FROM note_page WHERE id = ?",
                (rs, row) -> rs.getString("content_hash"),
                pageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Selected space regardless of state (for status/commit-time revalidation). */
    public Map<String, Object> findSelectedSpaceAnyState() {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT embedding_space_id, provider_type, model_identifier,"
                        + " dimensions, state, coverage"
                        + " FROM embedding_space WHERE is_selected = 1",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("embedding_space_id", rs.getString("embedding_space_id"));
                    m.put("provider_type", rs.getString("provider_type"));
                    m.put("model_identifier", rs.getString("model_identifier"));
                    m.put("dimensions", rs.getInt("dimensions"));
                    m.put("state", rs.getString("state"));
                    m.put("coverage", rs.getDouble("coverage"));
                    return m;
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Vector writes ──────────────────────────────────────────────────────

    public void upsertEmbedding(
            long chunkId, String corpusType, String embeddingSpaceId,
            int dimensions, String contentHash, byte[] vectorBlob) {
        jdbc.update(
                "INSERT INTO retrieval_embedding"
                        + "(chunk_id, corpus_type, embedding_space_id,"
                        + " dimensions, content_hash, vector_blob)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(chunk_id, embedding_space_id) DO UPDATE SET"
                        + " dimensions = excluded.dimensions,"
                        + " content_hash = excluded.content_hash,"
                        + " vector_blob = excluded.vector_blob",
                chunkId, corpusType, embeddingSpaceId,
                dimensions, contentHash, vectorBlob);
    }

    // ── Coverage & activation ──────────────────────────────────────────────

    public int countChunks(String corpusType, Long corpusId) {
        String sql = "SELECT COUNT(*) FROM retrieval_chunk WHERE corpus_type = ?";
        if (corpusId != null) {
            return jdbc.queryForObject(sql + " AND corpus_id = ?",
                    Integer.class, corpusType, corpusId);
        }
        return jdbc.queryForObject(sql, Integer.class, corpusType);
    }

    /** Chunks whose latest-hash vector exists for the space. */
    public int countIndexedChunks(String corpusType, String embeddingSpaceId, Long corpusId) {
        String sql = "SELECT COUNT(*) FROM retrieval_chunk c"
                + " JOIN retrieval_embedding e ON e.chunk_id = c.id"
                + " AND e.embedding_space_id = ?"
                + " AND e.content_hash = c.content_hash"
                + " WHERE c.corpus_type = ?";
        if (corpusId != null) {
            return jdbc.queryForObject(sql + " AND c.corpus_id = ?",
                    Integer.class, embeddingSpaceId, corpusType, corpusId);
        }
        return jdbc.queryForObject(sql, Integer.class, embeddingSpaceId, corpusType);
    }

    /** Chunks that only have a stale-hash vector for the space. */
    public int countStaleChunks(String corpusType, String embeddingSpaceId, Long corpusId) {
        String sql = "SELECT COUNT(*) FROM retrieval_chunk c"
                + " JOIN retrieval_embedding e ON e.chunk_id = c.id"
                + " AND e.embedding_space_id = ?"
                + " AND e.content_hash != c.content_hash"
                + " WHERE c.corpus_type = ?";
        if (corpusId != null) {
            return jdbc.queryForObject(sql + " AND c.corpus_id = ?",
                    Integer.class, embeddingSpaceId, corpusType, corpusId);
        }
        return jdbc.queryForObject(sql, Integer.class, embeddingSpaceId, corpusType);
    }

    /** Latest-hash coverage across the whole corpus (1.0 for an empty corpus). */
    public double computeCoverage(String corpusType, String embeddingSpaceId) {
        int total = countChunks(corpusType, null);
        if (total == 0) return 1.0;
        return countIndexedChunks(corpusType, embeddingSpaceId, null) / (double) total;
    }

    public int updateSpaceCoverage(String embeddingSpaceId, double coverage) {
        return jdbc.update(
                "UPDATE embedding_space SET coverage = ?, updated_at = datetime('now')"
                        + " WHERE embedding_space_id = ?",
                coverage, embeddingSpaceId);
    }

    /** REBUILDING → ACTIVE, only for the selected space at 100% coverage. */
    public int activateSpaceIfComplete(String embeddingSpaceId) {
        return jdbc.update(
                "UPDATE embedding_space SET state = 'ACTIVE', updated_at = datetime('now')"
                        + " WHERE embedding_space_id = ? AND is_selected = 1"
                        + " AND state = 'REBUILDING' AND coverage >= 1.0",
                embeddingSpaceId);
    }

    // ── Status counts (scope: all notebooks or one notebook) ──────────────

    public int countPages(Long notebookId) {
        if (notebookId != null) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM note_page WHERE notebook_id = ?",
                    Integer.class, notebookId);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM note_page", Integer.class);
    }

    public int countJobsByStatuses(
            String embeddingSpaceId, List<String> statuses, Long notebookId) {
        if (statuses == null || statuses.isEmpty()) return 0;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM embedding_job j"
                        + " WHERE j.embedding_space_id = ?"
                        + " AND j.status IN (" + in + ")");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(embeddingSpaceId);
        params.addAll(statuses);
        if (notebookId != null) {
            sql.append(" AND j.corpus_type = 'NOTEBOOK' AND EXISTS"
                    + " (SELECT 1 FROM note_page p WHERE p.id = j.source_id"
                    + " AND p.notebook_id = ?)");
            params.add(notebookId);
        }
        return jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
    }
}
