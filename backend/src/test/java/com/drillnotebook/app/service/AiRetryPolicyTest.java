package com.drillnotebook.app.service;

import com.drillnotebook.app.model.AiRetrySettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiRetryPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private AiUpstreamException http(int status, String code, String message) {
        return AiUpstreamException.http(status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}",
                HttpHeaders.of(Map.of(), (a,b) -> true), 1000, "fake-key");
    }
    @Test void countersAdvanceOnlyWhenActuallyStartingAndLayersRemainIndependent() {
        var retry = new AiRetryPolicy(AiRetrySettings.DEFAULTS, () -> .5);
        var request = http(503, "server_error", "Service Unavailable");
        for (long delay : new long[] {400, 800, 1600, 3200}) {
            assertTrue(retry.canRetry(request)); assertEquals(delay, retry.delay(request, 1000));
            int before = retry.attempt(request.kind());
            retry.pending(request); assertEquals(before, retry.view(request, "waiting", 1000L).attempt());
            assertEquals(before + 1, retry.starting().attempt());
        }
        assertFalse(retry.canRetry(request));
        var stream = AiUpstreamException.disconnected(null, null);
        assertTrue(retry.canRetry(stream)); assertEquals(0, retry.attempt(stream.kind()));
        for (long delay : new long[] {400, 800, 1600, 3200, 6400}) {
            assertTrue(retry.canRetry(stream)); assertEquals(delay, retry.delay(stream, 1000));
            retry.pending(stream); retry.starting();
        }
        assertFalse(retry.canRetry(stream));
        retry.complete(); assertEquals(0, retry.attempt(request.kind())); assertTrue(retry.canRetry(stream));
        assertEquals(400, retry.delay(request, 1000)); assertEquals(400, retry.delay(stream, 1000));
    }
    @Test void networkWaitsFollowFiveTenTwentyFortySixtyAndZeroDisablesOnlyItsLayer() {
        var network = AiUpstreamException.transport(new ConnectException("Connection refused"));
        var retry = new AiRetryPolicy(AiRetrySettings.DEFAULTS, () -> .5);
        for (long delay : new long[] {5000, 10000, 20000, 40000, 60000, 60000}) {
            assertNull(retry.limit(network.kind())); assertTrue(retry.canRetry(network));
            assertEquals(delay, retry.delay(network, 0)); retry.pending(network); retry.starting();
        }
        var off = new AiRetryPolicy(new AiRetrySettings(0, 0, false, 0));
        assertFalse(off.canRetry(network)); assertFalse(off.canRetry(http(503, "server_error", "busy")));
        assertFalse(off.canRetry(AiUpstreamException.disconnected(null, null)));
    }
    @Test void serverDeadlineWinsAndInvalidWaitSuggestionsFallBackToJitteredBackoff() throws Exception {
        var headers = HttpHeaders.of(Map.of("Retry-After", List.of("2")), (a,b) -> true);
        var failure = AiUpstreamException.http(503, "temporary", headers, 1000, null);
        var retry = new AiRetryPolicy(AiRetrySettings.DEFAULTS, () -> 0);
        assertEquals(1500, retry.delay(failure, 1500)); assertEquals(0, retry.delay(failure, 3100));
        var invalid = AiUpstreamException.http(503, "temporary", HttpHeaders.of(Map.of("retry-after", List.of("-1")), (a,b) -> true), 1000, null);
        assertEquals(360, retry.delay(invalid, 1000));
        assertEquals(440 - 1, new AiRetryPolicy(AiRetrySettings.DEFAULTS, () -> .999).delay(invalid, 1000));
        assertEquals(1250L, AiUpstreamException.retryAt(null, json.readTree("{\"retry_after_ms\":250}"), 1000));
        assertTrue(new AiRetryPolicy(new AiRetrySettings(100, 100, true, 100)).canRetry(failure));
    }
    @Test void authQuotaAndInvalidRequestsNeverRetryAndKeysNeverEnterVisibleErrors() {
        var retry = new AiRetryPolicy(AiRetrySettings.DEFAULTS);
        for (var failure : List.of(http(401, "invalid_api_key", "fake-key"), http(403, "forbidden", "no access"),
                http(404, "not_found", "unknown route"), http(400, "invalid_request", "bad arguments"),
                http(429, "insufficient_quota", "quota exceeded"), http(429, "usage_limit_reached", "limit reached"))) assertFalse(retry.canRetry(failure));
        assertTrue(retry.canRetry(http(429, "rate_limit_error", "try again")));
        assertFalse(http(401, "invalid_api_key", "fake-key").getMessage().contains("fake-key"));
        assertFalse(AiUpstreamException.safe("Authorization: Bearer sample-key api_key=another-key", null).contains("sample-key"));
    }
}
