package com.drillnotebook.app.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@code embedding_model} installation rows and the
 * embedding-space transitions driven by the model lifecycle
 * (activate/disable/uninstall).
 */
@Repository
public class EmbeddingModelRepository {

    private final JdbcTemplate jdbc;

    public EmbeddingModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── embedding_model rows ────────────────────────────────────────────────

    /** Ensure a row exists for a catalog model (state AVAILABLE). */
    public void ensureRow(String catalogId, String providerModelId,
                          String artifactRevision, int dimensions) {
        jdbc.update(
                "INSERT OR IGNORE INTO embedding_model"
                        + "(catalog_id, provider_model_id, artifact_revision, dimensions)"
                        + " VALUES (?, ?, ?, ?)",
                catalogId, providerModelId, artifactRevision, dimensions);
    }

    public Map<String, Object> find(String catalogId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, catalog_id, provider_model_id, artifact_revision,"
                        + " dimensions, installation_state, manifest_json,"
                        + " download_progress_json, download_error"
                        + " FROM embedding_model WHERE catalog_id = ?",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("catalog_id", rs.getString("catalog_id"));
                    m.put("provider_model_id", rs.getString("provider_model_id"));
                    m.put("artifact_revision", rs.getString("artifact_revision"));
                    m.put("dimensions", rs.getInt("dimensions"));
                    m.put("installation_state", rs.getString("installation_state"));
                    m.put("manifest_json", rs.getString("manifest_json"));
                    m.put("download_progress_json", rs.getString("download_progress_json"));
                    m.put("download_error", rs.getString("download_error"));
                    return m;
                },
                catalogId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> findAllStates() {
        return jdbc.query(
                "SELECT catalog_id, installation_state FROM embedding_model",
                (rs, row) -> rs.getString("catalog_id") + "=" + rs.getString("installation_state"));
    }

    public void updateState(String catalogId, String state) {
        jdbc.update(
                "UPDATE embedding_model SET installation_state = ?,"
                        + " updated_at = datetime('now') WHERE catalog_id = ?",
                state, catalogId);
    }

    public void updateStateAndError(String catalogId, String state, String error) {
        jdbc.update(
                "UPDATE embedding_model SET installation_state = ?, download_error = ?,"
                        + " updated_at = datetime('now') WHERE catalog_id = ?",
                state, error, catalogId);
    }

    public void updateProgress(String catalogId, String progressJson) {
        jdbc.update(
                "UPDATE embedding_model SET download_progress_json = ?,"
                        + " updated_at = datetime('now') WHERE catalog_id = ?",
                progressJson, catalogId);
    }

    public void markReady(String catalogId, String manifestJson) {
        jdbc.update(
                "UPDATE embedding_model SET installation_state = 'READY',"
                        + " manifest_json = ?, download_progress_json = NULL,"
                        + " download_error = NULL, updated_at = datetime('now')"
                        + " WHERE catalog_id = ?",
                manifestJson, catalogId);
    }

    public void resetToAvailable(String catalogId) {
        jdbc.update(
                "UPDATE embedding_model SET installation_state = 'AVAILABLE',"
                        + " manifest_json = NULL, download_progress_json = NULL,"
                        + " download_error = NULL, updated_at = datetime('now')"
                        + " WHERE catalog_id = ?",
                catalogId);
    }

    // ── embedding_space transitions ─────────────────────────────────────────

    public Map<String, Object> findSpace(String embeddingSpaceId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT embedding_space_id, state, coverage, is_selected"
                        + " FROM embedding_space WHERE embedding_space_id = ?",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("embedding_space_id", rs.getString("embedding_space_id"));
                    m.put("state", rs.getString("state"));
                    m.put("coverage", rs.getDouble("coverage"));
                    m.put("is_selected", rs.getInt("is_selected"));
                    return m;
                },
                embeddingSpaceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Deselect the previously selected space; a previously ACTIVE space is
     * immediately DISABLED so no space is ACTIVE during switching.
     */
    public void deselectCurrentSpace() {
        jdbc.update(
                "UPDATE embedding_space SET is_selected = 0,"
                        + " state = CASE WHEN state = 'ACTIVE' THEN 'DISABLED' ELSE state END,"
                        + " updated_at = datetime('now')"
                        + " WHERE is_selected = 1");
    }

    /** Upsert a space as the selected REBUILDING space (coverage reset happens via recompute). */
    public void upsertSelectedRebuildingSpace(
            String embeddingSpaceId, String canonicalJson, String providerType,
            String modelIdentifier, int dimensions) {
        jdbc.update(
                "INSERT INTO embedding_space(embedding_space_id, canonical_contract_json,"
                        + " provider_type, model_identifier, dimensions, state, coverage, is_selected)"
                        + " VALUES (?, ?, ?, ?, ?, 'REBUILDING', 0.0, 1)"
                        + " ON CONFLICT(embedding_space_id) DO UPDATE SET"
                        + " state = 'REBUILDING', is_selected = 1, updated_at = datetime('now')",
                embeddingSpaceId, canonicalJson, providerType, modelIdentifier, dimensions);
    }

    /** Spaces belonging to a provider model (any state). */
    public List<String> findSpaceIdsForModel(String providerType, String modelIdentifier) {
        return jdbc.query(
                "SELECT embedding_space_id FROM embedding_space"
                        + " WHERE provider_type = ? AND model_identifier = ?",
                (rs, row) -> rs.getString(1),
                providerType, modelIdentifier);
    }

    /** Disable model spaces (files/vectors kept); selection is preserved. */
    public void disableSpacesForModel(String providerType, String modelIdentifier) {
        jdbc.update(
                "UPDATE embedding_space SET state = 'DISABLED', updated_at = datetime('now')"
                        + " WHERE provider_type = ? AND model_identifier = ?"
                        + " AND state IN ('ACTIVE', 'REBUILDING')",
                providerType, modelIdentifier);
    }

    /** Mark model spaces UNINSTALLING and clear their selection (stops reads/jobs). */
    public void markSpacesUninstalling(String providerType, String modelIdentifier) {
        jdbc.update(
                "UPDATE embedding_space SET state = 'UNINSTALLING', is_selected = 0,"
                        + " updated_at = datetime('now')"
                        + " WHERE provider_type = ? AND model_identifier = ?",
                providerType, modelIdentifier);
    }

    /** Thorough uninstall: vectors, jobs and the space rows themselves. */
    public void deleteSpacesForModel(String providerType, String modelIdentifier) {
        List<String> spaceIds = findSpaceIdsForModel(providerType, modelIdentifier);
        for (String spaceId : spaceIds) {
            jdbc.update("DELETE FROM retrieval_embedding WHERE embedding_space_id = ?", spaceId);
            jdbc.update("DELETE FROM embedding_job WHERE embedding_space_id = ?", spaceId);
            jdbc.update("DELETE FROM embedding_space WHERE embedding_space_id = ?", spaceId);
        }
    }

    /** Backfill: queue one job per (page, latest hash) missing latest-hash vectors. */
    public int enqueueMissingJobs(String embeddingSpaceId, String reason) {
        return enqueueMissingJobs(embeddingSpaceId, reason, null);
    }

    /**
     * Scope-aware missing-vector backfill. When {@code notebookId} is non-null
     * only chunks of that notebook are considered ({@code corpus_id} equals the
     * notebook id). Existing job rows are left untouched ({@code INSERT OR
     * IGNORE}) so completed/failed work is never recomputed by a missing pass.
     */
    public int enqueueMissingJobs(String embeddingSpaceId, String reason, Long notebookId) {
        StringBuilder sql = new StringBuilder(
                "INSERT OR IGNORE INTO embedding_job"
                        + "(corpus_type, source_id, source_content_hash, embedding_space_id,"
                        + " reason, status)"
                        + " SELECT DISTINCT c.corpus_type, c.source_id, c.content_hash, ?, ?, 'QUEUED'"
                        + " FROM retrieval_chunk c"
                        + " WHERE c.content_hash IS NOT NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM retrieval_embedding e"
                        + "   WHERE e.chunk_id = c.id AND e.embedding_space_id = ?"
                        + "   AND e.content_hash = c.content_hash)");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(embeddingSpaceId);
        params.add(reason);
        params.add(embeddingSpaceId);
        if (notebookId != null) {
            sql.append(" AND c.corpus_id = ?");
            params.add(notebookId);
        }
        return jdbc.update(sql.toString(), params.toArray());
    }

    /**
     * Full rebuild: (re)queue one job per (page, latest hash) for every chunk
     * regardless of existing vectors. Unlike {@link #enqueueMissingJobs} this
     * resets existing job rows to {@code QUEUED} (fresh attempts) so already
     * completed pages are re-embedded; vectors are overwritten on commit, so
     * hybrid retrieval stays available during the rebuild.
     */
    public int enqueueAllJobs(String embeddingSpaceId, String reason, Long notebookId) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO embedding_job"
                        + "(corpus_type, source_id, source_content_hash, embedding_space_id,"
                        + " reason, status)"
                        + " SELECT DISTINCT c.corpus_type, c.source_id, c.content_hash, ?, ?, 'QUEUED'"
                        + " FROM retrieval_chunk c"
                        + " WHERE c.content_hash IS NOT NULL");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(embeddingSpaceId);
        params.add(reason);
        if (notebookId != null) {
            sql.append(" AND c.corpus_id = ?");
            params.add(notebookId);
        }
        sql.append(" ON CONFLICT(corpus_type, source_id, source_content_hash, embedding_space_id)"
                + " DO UPDATE SET status = 'QUEUED', reason = excluded.reason,"
                + " attempts = 0, claim_token = NULL, error = NULL, next_run_at = NULL,"
                + " updated_at = datetime('now')");
        return jdbc.update(sql.toString(), params.toArray());
    }

    /**
     * Re-queue FAILED jobs of a space back to QUEUED with fresh attempts so the
     * poller retries them. Returns the number of rows re-queued. Scope is
     * restricted to one notebook when {@code notebookId} is non-null.
     */
    public int retryFailedJobs(String embeddingSpaceId, Long notebookId) {
        StringBuilder sql = new StringBuilder(
                "UPDATE embedding_job SET status = 'QUEUED', attempts = 0,"
                        + " claim_token = NULL, error = NULL, next_run_at = NULL,"
                        + " updated_at = datetime('now')"
                        + " WHERE status = 'FAILED' AND embedding_space_id = ?");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(embeddingSpaceId);
        if (notebookId != null) {
            sql.append(" AND corpus_type = 'NOTEBOOK' AND EXISTS"
                    + " (SELECT 1 FROM note_page p WHERE p.id = source_id"
                    + " AND p.notebook_id = ?)");
            params.add(notebookId);
        }
        return jdbc.update(sql.toString(), params.toArray());
    }

    /**
     * Count runnable reindex jobs ({@code reason LIKE 'reindex%'} in
     * QUEUED/CLAIMED/RETRY) for a space, used to honour the single-active
     * rebuild idempotency contract. Scope is restricted to one notebook when
     * {@code notebookId} is non-null.
     */
    public int countActiveReindexJobs(String embeddingSpaceId, Long notebookId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM embedding_job"
                        + " WHERE embedding_space_id = ? AND reason LIKE 'reindex%'"
                        + " AND status IN ('QUEUED', 'CLAIMED', 'RETRY')");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(embeddingSpaceId);
        if (notebookId != null) {
            sql.append(" AND corpus_type = 'NOTEBOOK' AND EXISTS"
                    + " (SELECT 1 FROM note_page p WHERE p.id = source_id"
                    + " AND p.notebook_id = ?)");
            params.add(notebookId);
        }
        return jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
    }

    /**
     * Asynchronous post-switch cleanup: delete vectors of a space that is no
     * longer selected and DISABLED. The guard sub-select makes the delete a
     * no-op if the space was re-selected (user switched back) before the
     * cleanup task ran. Returns the number of vectors removed.
     */
    public int deleteVectorsForDisabledSpace(String embeddingSpaceId) {
        return jdbc.update(
                "DELETE FROM retrieval_embedding"
                        + " WHERE embedding_space_id = ?"
                        + " AND EXISTS (SELECT 1 FROM embedding_space"
                        + "   WHERE embedding_space_id = ?"
                        + "   AND is_selected = 0 AND state = 'DISABLED')",
                embeddingSpaceId, embeddingSpaceId);
    }
}
