package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retrieval index maintenance: on-demand reindex (missing/full), failed-job
 * retry and post-switch stale-vector cleanup (Canonical Contracts table:
 * {@code POST /api/ai/retrieval/reindex} and
 * {@code POST /api/ai/retrieval/retry-failed}).
 *
 * <p>All operations are asynchronous and idempotent. Heavy work happens on the
 * shared {@link EmbeddingJobExecutor} poller; this service only enqueues jobs
 * and wakes the poller. Contract guarantees:
 * <ul>
 *   <li>{@code reindex} returns {@code 202 {jobId,state}} on first request and
 *       {@code 200} with the same {@code jobId} while a reindex job for the
 *       same space+scope is still runnable (single active rebuild).</li>
 *   <li>{@code mode=missing} only queues pages lacking latest-hash vectors
 *       (completed work is never recomputed); {@code mode=full} re-queues every
 *       page, overwriting vectors on commit so hybrid stays available.</li>
 *   <li>{@code retry-failed} resets FAILED jobs to QUEUED and reports the
 *       count; successful jobs are untouched.</li>
 *   <li>Stale vectors of a space that became DISABLED during a model/provider
 *       switch are removed asynchronously; model files are never touched.</li>
 * </ul>
 */
@Service
public class RetrievalMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalMaintenanceService.class);
    private static final String CORPUS_NOTEBOOK = "NOTEBOOK";

    /** Result carrying the HTTP status the controller should emit. */
    public record ApiResult(int status, Map<String, Object> body) {}

    private final EmbeddingModelRepository models;
    private final EmbeddingJobRepository jobs;
    private final EmbeddingJobExecutor executor;

    /** Live reindex job ids keyed by {@code spaceId + ':' + scopeKey}. */
    private final Map<String, String> activeReindexJobs = new ConcurrentHashMap<>();

    private final ExecutorService cleanupTasks = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-space-cleanup");
        t.setDaemon(true);
        return t;
    });

    public RetrievalMaintenanceService(
            EmbeddingModelRepository models,
            EmbeddingJobRepository jobs,
            EmbeddingJobExecutor executor) {
        this.models = models;
        this.jobs = jobs;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        cleanupTasks.shutdownNow();
        try {
            cleanupTasks.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── reindex ─────────────────────────────────────────────────────────────

    /**
     * POST /api/ai/retrieval/reindex. Body: {@code {scope, notebookId?, mode}}.
     * First request queues jobs and returns {@code 202 {jobId,state}}; a
     * duplicate request while a reindex job is still runnable returns
     * {@code 200} with the same {@code jobId}.
     */
    public synchronized ApiResult reindex(Map<String, Object> body) {
        Scope scope = Scope.parse(body);
        String mode = string(body, "mode", "missing").trim().toLowerCase(Locale.ROOT);
        if (!"missing".equals(mode) && !"full".equals(mode)) {
            throw new IllegalArgumentException("mode 必须是 missing 或 full");
        }

        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        if (selected == null) {
            return conflict("NO_SELECTED_SPACE", "尚未启用任何向量索引，无法重建");
        }
        String state = (String) selected.get("state");
        if (!"ACTIVE".equals(state) && !"REBUILDING".equals(state)) {
            return conflict("SPACE_NOT_INDEXABLE", "向量索引当前状态不可重建：" + state);
        }
        String spaceId = (String) selected.get("embedding_space_id");

        String scopeKey = scope.corpusId() == null ? "all" : "nb" + scope.corpusId();
        String mapKey = spaceId + ":" + scopeKey;

        String existing = activeReindexJobs.get(mapKey);
        if (existing != null && models.countActiveReindexJobs(spaceId, scope.corpusId()) > 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", existing);
            result.put("state", state);
            return new ApiResult(200, result);
        }

        String reason = "full".equals(mode) ? "reindex-full" : "reindex-missing";
        int enqueued = "full".equals(mode)
                ? models.enqueueAllJobs(spaceId, reason, scope.corpusId())
                : models.enqueueMissingJobs(spaceId, reason, scope.corpusId());

        String jobId = UUID.randomUUID().toString();
        if (enqueued > 0) {
            activeReindexJobs.put(mapKey, jobId);
            executor.wake();
        }
        log.info("reindex mode={} scope={} space={} enqueued={}", mode, scopeKey, spaceId, enqueued);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("state", state);
        return new ApiResult(202, result);
    }

    // ── retry-failed ────────────────────────────────────────────────────────

    /**
     * POST /api/ai/retrieval/retry-failed. Body: {@code {scope, notebookId?}}.
     * Resets FAILED jobs of the selected space back to QUEUED and returns
     * {@code {requeued}}. Always {@code 200}; zero when no space is selected.
     */
    public Map<String, Object> retryFailed(Map<String, Object> body) {
        Scope scope = Scope.parse(body);
        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        if (selected == null) {
            return Map.of("requeued", 0);
        }
        String spaceId = (String) selected.get("embedding_space_id");
        int requeued = models.retryFailedJobs(spaceId, scope.corpusId());
        if (requeued > 0) {
            executor.wake();
        }
        log.info("retry-failed scope={} space={} requeued={}",
                scope.corpusId() == null ? "all" : "nb" + scope.corpusId(), spaceId, requeued);
        return Map.of("requeued", requeued);
    }

    // ── stale-vector cleanup ────────────────────────────────────────────────

    /**
     * Schedule asynchronous deletion of vectors belonging to a space that was
     * just deselected+DISABLED by a model/provider switch. The delete is
     * guarded so it becomes a no-op if the space is re-selected before the task
     * runs. Model files are never removed here.
     */
    public void scheduleDisabledSpaceCleanup(String embeddingSpaceId) {
        if (embeddingSpaceId == null) return;
        cleanupTasks.submit(() -> cleanupDisabledSpaceVectors(embeddingSpaceId));
    }

    /** Synchronous cleanup body (public for deterministic tests). */
    public int cleanupDisabledSpaceVectors(String embeddingSpaceId) {
        try {
            int deleted = models.deleteVectorsForDisabledSpace(embeddingSpaceId);
            if (deleted > 0) {
                log.info("removed {} stale vectors from disabled space {}",
                        deleted, embeddingSpaceId);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("disabled-space vector cleanup failed for {}", embeddingSpaceId, e);
            return 0;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Parsed scope: {@code current} requires a positive notebookId. */
    record Scope(String name, Long corpusId) {
        static Scope parse(Map<String, Object> body) {
            String scope = string(body, "scope", "all").trim().toLowerCase(Locale.ROOT);
            if ("current".equals(scope)) {
                Long notebookId = longOf(body == null ? null : body.get("notebookId"));
                if (notebookId == null) {
                    throw new IllegalArgumentException("scope=current 需要 notebookId");
                }
                return new Scope("current", notebookId);
            }
            return new Scope("all", null);
        }
    }

    private static String string(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ApiResult conflict(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("errorCode", code);
        body.put("message", message);
        return new ApiResult(409, body);
    }
}
