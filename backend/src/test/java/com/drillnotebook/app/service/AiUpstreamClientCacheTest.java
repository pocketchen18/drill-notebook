package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiUpstreamClientCacheTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiUpstreamClient client = new AiUpstreamClient(java.net.http.HttpClient.newHttpClient(), mapper);

    private static AiUpstreamClient.Upstream anthropic(String baseUrl, boolean cacheBreakpoints) {
        return new AiUpstreamClient.Upstream(baseUrl, "k", "claude-sonnet-4-5",
                AiUpstreamClient.FORMAT_ANTHROPIC, 512, false, false, cacheBreakpoints);
    }

    private static List<Map<String, Object>> messages(String userText) {
        return List.of(
                Map.of("role", "system", "content", "稳定系统提示"),
                Map.of("role", "user", "content", userText));
    }

    @Test
    void cacheBreakpointMarksOnlyStableSystemAndLeavesDynamicUserAlone() {
        Map<String, Object> first = client.buildAnthropicRequest(
                anthropic("https://api.anthropic.com", true), messages("第一个问题"), false);
        Map<String, Object> second = client.buildAnthropicRequest(
                anthropic("https://api.anthropic.com", true), messages("第二个问题"), false);

        JsonNode firstSystem = mapper.valueToTree(first.get("system"));
        JsonNode secondSystem = mapper.valueToTree(second.get("system"));
        assertEquals(firstSystem, secondSystem);
        assertTrue(firstSystem.isArray());
        assertEquals("ephemeral", firstSystem.get(0).path("cache_control").path("type").asText());
        assertFalse(mapper.valueToTree(first.get("messages")).toString().contains("cache_control"));
    }

    @Test
    void customGatewayDoesNotPretendToSupportCacheControl() {
        Map<String, Object> request = client.buildAnthropicRequest(
                anthropic("https://gateway.example.com", false), messages("问题"), false);

        assertTrue(request.get("system") instanceof String);
        assertFalse(mapper.valueToTree(request).toString().contains("cache_control"));
    }

    @Test
    void cacheField400RetriesOnceWithoutBreakpoint() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> firstBody = new AtomicReference<>();
        AtomicReference<String> secondBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int call = requests.incrementAndGet();
            if (call == 1) {
                firstBody.set(body);
                respond(exchange, 400, "{\"error\":{\"message\":\"cache_control unsupported\"}}");
            } else {
                secondBody.set(body);
                respond(exchange, 200, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AiUpstreamClient.Result result = client.call(anthropic(baseUrl, true), messages("问题"), false, null, 10);

            assertEquals("ok", result.text());
            assertEquals(2, requests.get());
            assertTrue(firstBody.get().contains("cache_control"));
            assertFalse(secondBody.get().contains("cache_control"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void non400ErrorsAreNotRetriedByCacheFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.call(anthropic(baseUrl, true), messages("问题"), false, null, 10));
            assertTrue(error.getMessage().contains("HTTP 429"));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
