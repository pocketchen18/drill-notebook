package com.drillnotebook.app.service;

import com.drillnotebook.app.model.AiRecovery;
import com.drillnotebook.app.model.AiRetrySettings;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** 每个模型步骤的恢复计数；成功取得完整响应后重置，不触碰整轮预算。 */
final class AiRetryPolicy {
    private final AiRetrySettings settings;
    private final DoubleSupplier random;
    private final int[] attempts = new int[3];
    private AiUpstreamException pending;

    AiRetryPolicy(AiRetrySettings settings) { this(settings, () -> ThreadLocalRandom.current().nextDouble()); }
    AiRetryPolicy(AiRetrySettings settings, DoubleSupplier random) { this.settings = settings; this.random = random; }
    int attempt(AiUpstreamException.Kind kind) { return attempts[kind.ordinal()]; }
    Integer limit(AiUpstreamException.Kind kind) {
        return switch (kind) {
            case REQUEST -> settings.httpRetries();
            case STREAM -> settings.streamRetries();
            case NETWORK -> settings.networkPersistent() ? null : settings.networkRetries();
        };
    }
    boolean canRetry(AiUpstreamException error) {
        Integer limit = limit(error.kind());
        return error.retryable() && (limit == null || attempt(error.kind()) < limit);
    }
    long delay(AiUpstreamException error, long now) {
        if (error.retryAt() != null && error.kind() != AiUpstreamException.Kind.NETWORK) return Math.max(0, error.retryAt() - now);
        int attempt = attempt(error.kind());
        if (error.kind() == AiUpstreamException.Kind.NETWORK) return attempt >= 4 ? 60_000 : 5_000L << attempt;
        return (long) Math.min(Long.MAX_VALUE / 4.0, Math.scalb(400.0, attempt) * (0.9 + random.getAsDouble() * 0.2));
    }
    AiRecovery view(AiUpstreamException error, String state, Long at) {
        return new AiRecovery(error.kind().name().toLowerCase(java.util.Locale.ROOT), state,
                attempt(error.kind()), limit(error.kind()), error.getMessage(), at);
    }
    void pending(AiUpstreamException error) { pending = error; }
    AiRecovery starting() {
        if (pending == null) return null;
        attempts[pending.kind().ordinal()]++;
        AiRecovery result = view(pending, "connecting", null);
        pending = null;
        return result;
    }
    void complete() { java.util.Arrays.fill(attempts, 0); pending = null; }
}
