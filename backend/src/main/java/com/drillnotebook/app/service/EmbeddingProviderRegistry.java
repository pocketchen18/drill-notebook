package com.drillnotebook.app.service;

import org.springframework.stereotype.Component;

/**
 * Holds the currently active {@link EmbeddingProvider}, if any.
 *
 * <p>Task 9 baseline: no provider is registered by default (jobs stay queued
 * and the executor idle-waits). Model activation (Task 10) and embedding
 * config (Task 12) install/replace the active provider and call
 * {@link EmbeddingJobExecutor#wake()} afterwards.
 */
@Component
public class EmbeddingProviderRegistry {

    private volatile EmbeddingProvider active;

    /**
     * Serializes the combined "selected embedding space + active provider"
     * mutation performed by model activate/disable (Task 10) and remote
     * embedding config (Task 12). Both live in different services but must
     * stay consistent: without a shared lock a concurrent {@code activate}
     * and {@code saveConfig} could leave the DB-selected space and the
     * in-memory provider disagreeing. Callers wrap their deselect/upsert/
     * {@link #setActive} block in {@link #withSpaceLock(Runnable)}.
     */
    private final Object spaceLock = new Object();

    /** The active provider, or {@code null} when none is configured. */
    public EmbeddingProvider active() {
        return active;
    }

    public void setActive(EmbeddingProvider provider) {
        this.active = provider;
    }

    public void clear() {
        this.active = null;
    }

    /** Run a space-selection + provider mutation under the shared lock. */
    public void withSpaceLock(Runnable action) {
        synchronized (spaceLock) {
            action.run();
        }
    }
}
