package com.drillnotebook.app.service;

/**
 * Typed failure from an {@link EmbeddingProvider}.
 *
 * <p>{@code retryable=true} means the job executor should back off and retry
 * later (worker down, transient upstream error). {@code retryable=false}
 * means retrying the same input cannot succeed (invalid response shape,
 * dimension mismatch) and the job should be marked FAILED.
 */
public class EmbeddingProviderException extends Exception {

    private final String code;
    private final boolean retryable;

    public EmbeddingProviderException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }

    public boolean retryable() { return retryable; }
}
