package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.Chunk;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository for retrieval index operations: chunks, FTS, embedding spaces,
 * and embedding jobs.
 *
 * <p>All methods are non-transactional; callers are expected to provide a
 * transaction boundary. Every FTS {@code rowid} must equal the corresponding
 * {@code retrieval_chunk.id}.
 */
@Repository
public class RetrievalIndexRepository {

    private final JdbcTemplate jdbc;

    public RetrievalIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Chunks ──────────────────────────────────────────────────────────────

    public List<Long> findChunkIdsBySource(String corpusType, long sourceId) {
        return jdbc.query(
                "SELECT id FROM retrieval_chunk WHERE corpus_type = ? AND source_id = ? ORDER BY chunk_index",
                (rs, row) -> rs.getLong("id"),
                corpusType, sourceId);
    }

    public long insertChunk(String corpusType, long corpusId, long sourceId, Chunk chunk) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO retrieval_chunk(corpus_type, corpus_id, source_id, chunk_index,"
                            + " title, heading_path, text, start_offset, end_offset, content_hash)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, corpusType);
            ps.setLong(2, corpusId);
            ps.setLong(3, sourceId);
            ps.setInt(4, chunk.chunkIndex());
            ps.setString(5, chunk.title());
            ps.setString(6, String.join(" / ", chunk.headingPath()));
            ps.setString(7, chunk.text());
            ps.setInt(8, chunk.startOffset());
            ps.setInt(9, chunk.endOffset());
            ps.setString(10, chunk.contentHash());
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null
                ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class)
                : key.longValue();
    }

    public int deleteChunksBySource(String corpusType, long sourceId) {
        return jdbc.update(
                "DELETE FROM retrieval_chunk WHERE corpus_type = ? AND source_id = ?",
                corpusType, sourceId);
    }

    // ── FTS ─────────────────────────────────────────────────────────────────

    public int deleteFtsByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder(
                "DELETE FROM retrieval_chunk_fts WHERE rowid IN (");
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");
        return jdbc.update(sql.toString(), chunkIds.toArray());
    }

    /**
     * Update the title column of all chunks belonging to a source. Used to
     * keep chunk metadata in sync when only the page title changes.
     */
    public int updateChunkTitlesBySource(String corpusType, long sourceId, String title) {
        return jdbc.update(
                "UPDATE retrieval_chunk SET title = ?, updated_at = datetime('now')"
                        + " WHERE corpus_type = ? AND source_id = ?",
                title, corpusType, sourceId);
    }

    /**
     * Update the FTS title for the given chunk row ids. The FTS rowid equals
     * the chunk id, so we can target specific rows directly.
     */
    public int updateFtsTitleByChunkIds(List<Long> chunkIds, String title) {
        if (chunkIds == null || chunkIds.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder(
                "UPDATE retrieval_chunk_fts SET title = ? WHERE rowid IN (");
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");
        Object[] params = new Object[1 + chunkIds.size()];
        params[0] = title;
        for (int i = 0; i < chunkIds.size(); i++) {
            params[1 + i] = chunkIds.get(i);
        }
        return jdbc.update(sql.toString(), params);
    }

    public void insertFtsRow(long rowid, String title, String headingPath, String text) {
        jdbc.update(
                "INSERT INTO retrieval_chunk_fts(rowid, title, heading_path, text)"
                        + " VALUES (?, ?, ?, ?)",
                rowid, title, headingPath, text);
    }

    // ── Embedding space ─────────────────────────────────────────────────────

    public Map<String, Object> findSelectedSpace() {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT embedding_space_id, state, dimensions"
                        + " FROM embedding_space"
                        + " WHERE is_selected = 1 AND state IN ('ACTIVE', 'REBUILDING')",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("embedding_space_id", rs.getString("embedding_space_id"));
                    m.put("state", rs.getString("state"));
                    m.put("dimensions", rs.getInt("dimensions"));
                    return m;
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionActiveToRebuilding(String embeddingSpaceId) {
        return jdbc.update(
                "UPDATE embedding_space SET state = 'REBUILDING', updated_at = datetime('now')"
                        + " WHERE embedding_space_id = ? AND state = 'ACTIVE'",
                embeddingSpaceId);
    }

    // ── Embedding jobs ──────────────────────────────────────────────────────

    public int supersedeJobs(
            String corpusType, long sourceId, String currentHash, String spaceId) {
        return jdbc.update(
                "UPDATE embedding_job SET status = 'SUPERSEDED', updated_at = datetime('now')"
                        + " WHERE corpus_type = ? AND source_id = ? AND embedding_space_id = ?"
                        + " AND source_content_hash != ?"
                        + " AND status != 'SUPERSEDED'",
                corpusType, sourceId, spaceId, currentHash);
    }

    /**
     * Insert or upsert an embedding job. When a row with the same
     * (corpus_type, source_id, source_content_hash, embedding_space_id)
     * already exists, it is reset to QUEUED with fresh attempts, releasing
     * any CLAIMED state. This ensures that chunk-replacement saves always
     * produce an executable job even on content reversion.
     */
    public void upsertJob(
            String corpusType, long sourceId, String contentHash,
            String spaceId, String reason) {
        jdbc.update(
                "INSERT INTO embedding_job"
                        + "(corpus_type, source_id, source_content_hash,"
                        + " embedding_space_id, reason, status)"
                        + " VALUES (?, ?, ?, ?, ?, 'QUEUED')"
                        + " ON CONFLICT(corpus_type, source_id,"
                        + " source_content_hash, embedding_space_id)"
                        + " DO UPDATE SET"
                        + " status = 'QUEUED',"
                        + " reason = excluded.reason,"
                        + " attempts = 0,"
                        + " claim_token = NULL,"
                        + " error = NULL,"
                        + " next_run_at = NULL,"
                        + " updated_at = datetime('now')",
                corpusType, sourceId, contentHash, spaceId, reason);
    }

    public int deleteJobsBySource(String corpusType, long sourceId) {
        return jdbc.update(
                "DELETE FROM embedding_job WHERE corpus_type = ? AND source_id = ?",
                corpusType, sourceId);
    }

    // ── Startup backfill ────────────────────────────────────────────────────

    public List<Map<String, Object>> findPagesWithNullContentHash() {
        return jdbc.query(
                "SELECT np.id AS page_id, np.notebook_id, np.title, np.content"
                        + " FROM note_page np WHERE np.content_hash IS NULL"
                        + " ORDER BY np.id",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("page_id", rs.getLong("page_id"));
                    m.put("notebook_id", rs.getLong("notebook_id"));
                    m.put("title", rs.getString("title"));
                    m.put("content", rs.getString("content"));
                    return m;
                });
    }

    public int updatePageContentHash(long pageId, String contentHash) {
        return jdbc.update(
                "UPDATE note_page SET content_hash = ? WHERE id = ?",
                contentHash, pageId);
    }

    public long findNotebookIdByPageId(long pageId) {
        return jdbc.queryForObject(
                "SELECT notebook_id FROM note_page WHERE id = ?",
                Long.class, pageId);
    }

    public List<Long> findPageIdsByNotebook(long notebookId) {
        return jdbc.query(
                "SELECT id FROM note_page WHERE notebook_id = ? ORDER BY id",
                (rs, row) -> rs.getLong("id"),
                notebookId);
    }

    // ── BM25 retrieval reads ──────────────────────────────────────────────────

    public record LexicalRow(
            long chunkId,
            String corpusType,
            long corpusId,
            long sourceId,
            int chunkIndex,
            String title,
            String headingPath,
            String text,
            Double bm25Score
    ) {}

    /**
     * BM25 MATCH retrieval over {@code retrieval_chunk_fts} joined to
     * {@code retrieval_chunk}. The {@code matchExpr} is always a bound
     * parameter (never raw user input) and is constructed by
     * {@code RetrievalService} as quoted 3-codepoint shingles joined by
     * {@code OR}. Results are filtered to {@code corpus_type = 'NOTEBOOK'}
     * and optionally to a single {@code corpus_id} (notebook id) for the
     * {@code current} scope.
     *
     * <p>Ranking uses {@code bm25(retrieval_chunk_fts, 2.0, 1.5, 1.0)} so
     * title hits outweigh heading hits which outweigh body hits. The
     * returned list is ordered by ascending BM25 score (lower is better in
     * SQLite's {@code bm25()} convention), with stable tie-breaking by
     * {@code source_id} then {@code chunk_index}. The {@code ftsRank} field
     * is a 1-based integer assigned after the final stable sort.
     *
     * @param matchExpr   Bound FTS MATCH expression (quoted shingles joined
     *                    by {@code OR}); never null.
     * @param notebookId  Notebook id for the {@code current} scope, or
     *                    {@code null} for the {@code all} scope.
     * @param topK        Maximum number of rows to return (e.g. 40).
     */
    public List<LexicalRow> searchBm25(
            String corpusType, Long corpusId, String matchExpr, int topK) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.id AS chunk_id, c.source_id, c.corpus_id,"
                        + " c.title, c.heading_path, c.text, c.chunk_index,"
                        + " bm25(retrieval_chunk_fts, 2.0, 1.5, 1.0) AS score"
                        + " FROM retrieval_chunk_fts"
                        + " JOIN retrieval_chunk c"
                        + " ON c.id = retrieval_chunk_fts.rowid"
                        + " WHERE retrieval_chunk_fts MATCH ?"
                        + " AND c.corpus_type = ?");
        List<Object> params = new ArrayList<>();
        params.add(matchExpr);
        params.add(corpusType);
        if (corpusId != null) {
            sql.append(" AND c.corpus_id = ?");
            params.add(corpusId);
        }
        sql.append(" ORDER BY score ASC, c.source_id ASC, c.chunk_index ASC");
        if (topK > 0) {
            sql.append(" LIMIT ?");
            params.add(topK);
        }
        return jdbc.query(sql.toString(),
                (rs, row) -> new LexicalRow(
                        rs.getLong("chunk_id"),
                        corpusType,
                        rs.getLong("corpus_id"),
                        rs.getLong("source_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("title"),
                        rs.getString("heading_path"),
                        rs.getString("text"),
                        rs.getDouble("score")),
                params.toArray());
    }

    /**
     * Safe LIKE fallback for 1–2 codepoint queries. The {@code likePattern}
     * is a bound parameter with {@code %}, {@code _}, and {@code \}
     * pre-escaped by {@code RetrievalService} and wrapped in {@code %...%}.
     * The {@code ESCAPE '\'} clause is emitted literally (it is a fixed
     * syntactic token, not user input).
     *
     * <p>Ranking is deterministic: title hit > heading hit > text hit,
     * then {@code source_id} ascending, then {@code chunk_index} ascending.
     * The service assigns the final 1-based lexical rank after this stable
     * ordering, including for short-query LIKE results.
     *
     * @param likePattern  Bound LIKE pattern (already escaped, wrapped in
     *                     {@code %...%}); never null.
     * @param notebookId   Notebook id for the {@code current} scope, or
     *                    {@code null} for the {@code all} scope.
     * @param topK         Maximum number of rows to return.
     */
    public List<LexicalRow> searchLikeFallback(
            String corpusType, Long corpusId, String likePattern, int topK) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.id AS chunk_id, c.source_id, c.corpus_id,"
                        + " c.title, c.heading_path, c.text, c.chunk_index"
                        + " FROM retrieval_chunk c"
                        + " WHERE c.corpus_type = ?"
                        + " AND (COALESCE(c.title, '') LIKE ? ESCAPE '\\'"
                        + " OR COALESCE(c.heading_path, '') LIKE ? ESCAPE '\\'"
                        + " OR c.text LIKE ? ESCAPE '\\')");
        List<Object> params = new ArrayList<>();
        params.add(corpusType);
        params.add(likePattern);
        params.add(likePattern);
        params.add(likePattern);
        if (corpusId != null) {
            sql.append(" AND c.corpus_id = ?");
            params.add(corpusId);
        }
        sql.append(" ORDER BY")
                .append(" CASE")
                .append(" WHEN COALESCE(c.title, '') LIKE ? ESCAPE '\\' THEN 0")
                .append(" WHEN COALESCE(c.heading_path, '') LIKE ? ESCAPE '\\' THEN 1")
                .append(" WHEN c.text LIKE ? ESCAPE '\\' THEN 2")
                .append(" ELSE 3 END ASC,")
                .append(" c.source_id ASC, c.chunk_index ASC");
        params.add(likePattern);
        params.add(likePattern);
        params.add(likePattern);
        if (topK > 0) {
            sql.append(" LIMIT ?");
            params.add(topK);
        }
        return jdbc.query(sql.toString(),
                (rs, row) -> new LexicalRow(
                        rs.getLong("chunk_id"),
                        corpusType,
                        rs.getLong("corpus_id"),
                        rs.getLong("source_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("title"),
                        rs.getString("heading_path"),
                        rs.getString("text"),
                        null),
                params.toArray());
    }

    // ── Vector retrieval reads ──────────────────────────────────────────────

    /**
     * Stream all scannable embeddings for one embedding space. Rows are
     * filtered in SQL to the exact space/dimensions/corpus and to vectors
     * whose {@code content_hash} still matches the current chunk hash, so
     * stale vectors (page edited but not re-embedded) are never scored.
     * The callback receives {@code chunk_id, source_id, chunk_index,
     * vector_blob}; blobs of unexpected length must be skipped by callers.
     */
    public void scanEmbeddings(
            String corpusType, Long corpusId, String embeddingSpaceId,
            int dimensions, org.springframework.jdbc.core.RowCallbackHandler handler) {
        StringBuilder sql = new StringBuilder(
                "SELECT e.chunk_id, c.source_id, c.chunk_index, e.vector_blob"
                        + " FROM retrieval_embedding e"
                        + " JOIN retrieval_chunk c ON c.id = e.chunk_id"
                        + " WHERE e.embedding_space_id = ?"
                        + " AND e.dimensions = ?"
                        + " AND e.corpus_type = ?"
                        + " AND c.corpus_type = e.corpus_type"
                        + " AND e.content_hash = c.content_hash");
        List<Object> params = new ArrayList<>();
        params.add(embeddingSpaceId);
        params.add(dimensions);
        params.add(corpusType);
        if (corpusId != null) {
            sql.append(" AND c.corpus_id = ?");
            params.add(corpusId);
        }
        jdbc.query(sql.toString(), handler, params.toArray());
    }

    /** Chunk metadata for vector-only hits; keyed lookup after the scan. */
    public List<LexicalRow> findChunksByIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT id, corpus_type, corpus_id, source_id, chunk_index,"
                        + " title, heading_path, text FROM retrieval_chunk WHERE id IN (");
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");
        return jdbc.query(sql.toString(),
                (rs, row) -> new LexicalRow(
                        rs.getLong("id"),
                        rs.getString("corpus_type"),
                        rs.getLong("corpus_id"),
                        rs.getLong("source_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("title"),
                        rs.getString("heading_path"),
                        rs.getString("text"),
                        null),
                chunkIds.toArray());
    }
}
