package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AiUpstreamExceptionTest {
    private final ObjectMapper json = new ObjectMapper();

    private AiUpstreamException event(String code, String message) {
        return AiUpstreamException.event(json.valueToTree(Map.of("error", Map.of("code", code, "message", message))));
    }

    @ParameterizedTest @ValueSource(strings = { "upstream_error", "Upstream_Error", "server_error", "api_error", "stream_disconnected", "rate_limit_error" })
    void transientStreamErrorsRemainRetryableAndKeepTheUpstreamReason(String code) {
        var failure = event(code, "fixture provider failure");
        assertEquals(AiUpstreamException.Kind.STREAM, failure.kind()); assertTrue(failure.retryable());
        assertEquals(code + "\nfixture provider failure", failure.getMessage());
    }

    @ParameterizedTest @ValueSource(strings = { "invalid_api_key", "authentication_error", "permission_error", "invalid_request_error",
            "context_length_exceeded", "insufficient_quota", "usage_limit_reached" })
    void aProxyUpstreamErrorWrappingAPermanentFailureDoesNotRetry(String reason) {
        assertFalse(event("upstream_error", reason).retryable());
    }

    @Test void unknownErrorsDoNotBecomeAutomaticallyRetryable() {
        assertFalse(event("provider_custom_error", "unknown failure").retryable());
    }

    @Test void overloadedStreamStillRequiresExplicitWaitAdvice() {
        assertFalse(event("overloaded_error", "server overloaded").retryable());
        var failure = AiUpstreamException.event(json.valueToTree(Map.of("error", Map.of("code", "overloaded_error",
                "message", "server overloaded", "retry_after_ms", 0))));
        assertTrue(failure.retryable()); assertNotNull(failure.retryAt());
    }

    @Test void waitAdviceDoesNotMakeAPermanentWrappedErrorRetryable() {
        var failure = event("upstream_error", "authentication_error; server overloaded").withResponseAdvice(
                java.net.http.HttpHeaders.of(Map.of("Retry-After", java.util.List.of("0")), (name, value) -> true), 1000);
        assertFalse(failure.retryable()); assertEquals(1000L, failure.retryAt());
    }
}
