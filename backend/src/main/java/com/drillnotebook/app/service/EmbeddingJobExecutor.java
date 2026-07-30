package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingJobRepository.ClaimedJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single-threaded durable poller for the {@code embedding_job} queue.
 *
 * <p>Design constraints (Task 9):
 * <ul>
 *   <li>Short transactions only — inference runs strictly outside any DB
 *       transaction; the commit transaction re-validates source hash, chunk
 *       set and embedding-space state before writing vectors.</li>
 *   <li>Stale results (autosave changed the hash, page deleted, space
 *       switched/uninstalling) are discarded and the job marked SUPERSEDED.</li>
 *   <li>Retryable provider failures back off exponentially up to
 *       {@link #MAX_ATTEMPTS}; a missing/unhealthy provider idle-waits
 *       without claiming (no busy loop, no burned attempts).</li>
 *   <li>Startup releases CLAIMED jobs from a previous process back to
 *       QUEUED.</li>
 * </ul>
 */
@Service
public class EmbeddingJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingJobExecutor.class);

    static final int MAX_ATTEMPTS = 5;
    static final int BASE_BACKOFF_SECONDS = 2;
    static final int MAX_BACKOFF_SECONDS = 300;

    private final EmbeddingJobRepository jobs;
    private final EmbeddingProviderRegistry providers;
    private final TransactionTemplate tx;
    private final boolean autoStart;
    private final long idleWaitMs;

    private final Object monitor = new Object();
    private volatile boolean running = false;
    private Thread pollerThread;

    public EmbeddingJobExecutor(
            EmbeddingJobRepository jobs,
            EmbeddingProviderRegistry providers,
            PlatformTransactionManager txManager,
            @Value("${drill.embedding.poller.enabled:true}") boolean autoStart,
            @Value("${drill.embedding.poller.idle-wait-ms:15000}") long idleWaitMs) {
        this.jobs = jobs;
        this.providers = providers;
        this.tx = new TransactionTemplate(txManager);
        this.autoStart = autoStart;
        this.idleWaitMs = idleWaitMs;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @PostConstruct
    void start() {
        int recovered = tx.execute(status -> jobs.recoverClaimedJobs());
        if (recovered > 0) {
            log.info("Recovered {} claimed embedding jobs back to QUEUED", recovered);
        }
        if (!autoStart) return;
        running = true;
        pollerThread = new Thread(this::runLoop, "embedding-job-poller");
        pollerThread.setDaemon(true);
        pollerThread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        if (pollerThread != null) {
            try {
                pollerThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Wake the poller immediately (startup, config switch, model ready). */
    public void wake() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void runLoop() {
        while (running) {
            boolean didWork = false;
            try {
                didWork = runOnce();
            } catch (Exception e) {
                log.error("Embedding job poll failed", e);
            }
            if (!didWork && running) {
                synchronized (monitor) {
                    try {
                        monitor.wait(idleWaitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    // ── Single poll (public for deterministic tests) ───────────────────────

    /**
     * Process at most one job. Returns {@code true} when a job was touched
     * (completed/retried/failed/superseded) so the caller should poll again
     * immediately; {@code false} means idle-wait.
     */
    public boolean runOnce() {
        Map<String, Object> space = jobs.findSelectedSpaceAnyState();
        if (space == null) return false;
        String spaceId = (String) space.get("embedding_space_id");
        String state = (String) space.get("state");
        int dimensions = ((Number) space.get("dimensions")).intValue();
        if (!"REBUILDING".equals(state) && !"ACTIVE".equals(state)) return false;

        EmbeddingProvider provider = providers.active();
        if (provider == null || !provider.isAvailable()
                || provider.dimensions() != dimensions) {
            // Provider missing/unhealthy/mismatched: keep the queue intact and
            // idle-wait instead of claiming (avoids burning attempts).
            return false;
        }

        String claimToken = UUID.randomUUID().toString();
        ClaimedJob job = tx.execute(status -> jobs.claimNext(claimToken, spaceId));
        if (job == null) return false;

        // Cheap pre-checks outside any transaction.
        String pageHash = jobs.findPageContentHash(job.sourceId());
        if (pageHash == null || !pageHash.equals(job.sourceContentHash())) {
            tx.executeWithoutResult(status ->
                    jobs.markSuperseded(job.id(), claimToken));
            return true;
        }
        List<Map<String, Object>> chunks = jobs.findChunksForJob(
                job.corpusType(), job.sourceId(), job.sourceContentHash());
        if (chunks.isEmpty()) {
            tx.executeWithoutResult(status ->
                    jobs.markSuperseded(job.id(), claimToken));
            return true;
        }

        // Inference strictly outside the DB transaction.
        List<String> texts = new ArrayList<>(chunks.size());
        for (Map<String, Object> chunk : chunks) {
            texts.add((String) chunk.get("text"));
        }
        List<List<Float>> vectors;
        try {
            vectors = provider.embedDocuments(texts);
        } catch (EmbeddingProviderException e) {
            handleProviderFailure(job, claimToken, e);
            return true;
        }

        // Validate + normalize + encode before touching the DB.
        List<byte[]> blobs = new ArrayList<>(chunks.size());
        try {
            if (vectors == null || vectors.size() != chunks.size()) {
                throw new IllegalArgumentException(
                        "VECTOR_COUNT_MISMATCH: expected " + chunks.size()
                                + " vectors, got " + (vectors == null ? 0 : vectors.size()));
            }
            for (List<Float> vector : vectors) {
                blobs.add(EmbeddingVectorCodec.encode(vector, dimensions));
            }
        } catch (IllegalArgumentException e) {
            tx.executeWithoutResult(status ->
                    jobs.markFailed(job.id(), claimToken, e.getMessage()));
            log.warn("Embedding job {} failed: invalid vectors ({})",
                    job.id(), e.getMessage());
            return true;
        }

        // Commit transaction: re-validate everything, then write.
        Boolean committed = tx.execute(status -> {
            String currentHash = jobs.findPageContentHash(job.sourceId());
            if (currentHash == null || !currentHash.equals(job.sourceContentHash())) {
                jobs.markSuperseded(job.id(), claimToken);
                return false;
            }
            Map<String, Object> currentSpace = jobs.findSelectedSpaceAnyState();
            if (currentSpace == null
                    || !spaceId.equals(currentSpace.get("embedding_space_id"))) {
                jobs.markSuperseded(job.id(), claimToken);
                return false;
            }
            String currentState = (String) currentSpace.get("state");
            if (!"REBUILDING".equals(currentState) && !"ACTIVE".equals(currentState)) {
                jobs.markSuperseded(job.id(), claimToken);
                return false;
            }
            List<Map<String, Object>> currentChunks = jobs.findChunksForJob(
                    job.corpusType(), job.sourceId(), job.sourceContentHash());
            if (!sameChunkIds(chunks, currentChunks)) {
                jobs.markSuperseded(job.id(), claimToken);
                return false;
            }
            for (int i = 0; i < chunks.size(); i++) {
                long chunkId = ((Number) chunks.get(i).get("id")).longValue();
                jobs.upsertEmbedding(chunkId, job.corpusType(), spaceId,
                        dimensions, job.sourceContentHash(), blobs.get(i));
            }
            int marked = jobs.markCompleted(job.id(), claimToken);
            if (marked == 0) {
                // Claim was reset concurrently (e.g. same-hash upsertJob) —
                // roll everything back and let the fresh QUEUED job redo it.
                status.setRollbackOnly();
                return false;
            }
            double coverage = jobs.computeCoverage(job.corpusType(), spaceId);
            jobs.updateSpaceCoverage(spaceId, coverage);
            if (coverage >= 1.0) {
                jobs.activateSpaceIfComplete(spaceId);
            }
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            log.debug("Embedding job {} completed ({} chunks)", job.id(), chunks.size());
        }
        return true;
    }

    private void handleProviderFailure(
            ClaimedJob job, String claimToken, EmbeddingProviderException e) {
        if (e.retryable() && job.attempts() < MAX_ATTEMPTS) {
            int backoff = backoffSeconds(job.attempts());
            tx.executeWithoutResult(status -> jobs.markRetry(
                    job.id(), claimToken, e.code() + ": " + e.getMessage(), backoff));
            log.warn("Embedding job {} retry in {}s (attempt {}): {}",
                    job.id(), backoff, job.attempts(), e.code());
        } else {
            tx.executeWithoutResult(status -> jobs.markFailed(
                    job.id(), claimToken, e.code() + ": " + e.getMessage()));
            log.warn("Embedding job {} failed permanently after {} attempts: {}",
                    job.id(), job.attempts(), e.code());
        }
    }

    static int backoffSeconds(int attempts) {
        // attempts is already incremented by the claim: 1 → 2s, 2 → 4s, 3 → 8s …
        double raw = BASE_BACKOFF_SECONDS * Math.pow(2, Math.max(0, attempts - 1));
        return (int) Math.min(raw, MAX_BACKOFF_SECONDS);
    }

    private static boolean sameChunkIds(
            List<Map<String, Object>> before, List<Map<String, Object>> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            if (!Objects.equals(before.get(i).get("id"), after.get(i).get("id"))) {
                return false;
            }
        }
        return true;
    }
}
