package com.drillnotebook.app.service;

import java.util.List;

/**
 * Typed result of an embedding worker operation.
 * <p>
 * Never exposes model paths or internal config in error messages — only
 * structured codes the caller (e.g. chat service) can act on without
 * surfacing implementation detail to the user or log.
 */
public sealed interface WorkerResult {

    /** The operation succeeded. */
    record Success(List<List<Float>> embeddings) implements WorkerResult {}

    /**
     * The worker executable was not found at the configured path.
     * Returned during initial handshake — never retried automatically.
     */
    record WorkerNotBuilt(String detail) implements WorkerResult {}

    /**
     * The worker process is unavailable (crashed, failed restart, timeout,
     * or already shut down).  The caller should degrade gracefully (e.g.
     * BM25-only retrieval).
     */
    record WorkerUnavailable(String detail) implements WorkerResult {}
}
