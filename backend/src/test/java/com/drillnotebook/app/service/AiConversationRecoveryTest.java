package com.drillnotebook.app.service;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiRecovery;
import com.drillnotebook.app.model.AiRetrySettings;
import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiRuntimeSettingsRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class AiConversationRecoveryTest {
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private long session;
    private String format = "chat_completions", scenario = "http";
    private boolean toolsEnabled;
    private final List<AiRecovery> recovery = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("conversation-recovery-");
        var ds = Schema9Fixture.dataSource(root); new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); wiring = ChatLegacyWiring.wire(ds, jdbc); stack = ChatContextWiring.wire(ds, jdbc, wiring);
        session = wiring.sessions().create("恢复验收", "stub");
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(), json, null,
                wiring.turnService(), wiring.cipher(), stack.contexts());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody()); requests.add(request);
            int index = requests.size();
            String payload;
            int status = 200;
            if (scenario.equals("auth") || scenario.equals("http") && index == 1 || scenario.equals("exhaust")) {
                status = scenario.equals("auth") ? 401 : 503;
                payload = "{\"error\":{\"code\":\"" + (status == 401 ? "invalid_api_key" : "server_error") + "\",\"message\":\"upstream exact failure fake-fixture-key\"}}";
                exchange.getResponseHeaders().set("Retry-After", "0");
            } else if (scenario.equals("tool") && index == 1) {
                payload = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"saved-call\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"create_material\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n";
            } else if (scenario.equals("busy") && index == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                payload = "event: error\ndata: {\"error\":{\"type\":\"overloaded_error\",\"message\":\"fixture service overloaded\"}}\n\n";
            } else if (format.equals("anthropic")) {
                payload = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + (index == 1 && scenario.equals("partial") ? "保留前半句。" : "继续后半句。") + "\"}}\n\n";
                if (!(index == 1 && scenario.equals("partial"))) payload += "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
            } else {
                boolean interrupted = index == 1 && scenario.equals("partial") || index == 2 && scenario.equals("tool");
                String text = interrupted ? "保留前半句。" : "继续后半句。";
                payload = "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":"
                        + (interrupted ? "null" : "\"stop\"") + "}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n";
                if (!interrupted) payload += "data: [DONE]\n\n";
            }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
    }
    @AfterEach void cleanup() throws Exception { server.stop(0); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root); }
    private void config() {
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "apiKey", "fake-fixture-key", "model", "fixture", "apiFormat", format, "tools", toolsEnabled, "streaming", true));
    }
    private Map<String, Object> body() { return Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
            "sessionId", session, "input", Map.of("userText", "继续同一轮"), "memoryOptions", Map.of("use", false)); }
    private AiService.ChatResult run(Map<String, Object> body, java.util.function.Consumer<AiRecovery> action) {
        return ai.chatStream(body, (kind, text) -> true, event -> {
            if (event.get("recovery") instanceof AiRecovery value) { recovery.add(value); action.accept(value); }
        });
    }
    @Test void httpFailureRecoversWithinOneTurnAndKeepsRealAttemptCounts() {
        config(); var body = body(); var result = run(body, ignored -> {});
        assertEquals("COMPLETED", result.status()); assertEquals(2, requests.size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_chat_message WHERE role='user'", Integer.class));
        assertTrue(recovery.stream().anyMatch(value -> value.state().equals("waiting") && value.attempt() == 0));
        assertTrue(recovery.stream().anyMatch(value -> value.state().equals("connecting") && value.attempt() == 1 && value.limit() == 4));
        assertNull(result.resultView().get("recovery"));
        assertTrue(recovery.stream().noneMatch(value -> value.state().equals("failed")));
        assertFalse(recovery.toString().contains("fake-fixture-key"));
    }
    @ParameterizedTest @ValueSource(strings = { "chat_completions", "anthropic" })
    void partialStreamContinuesWithoutDroppingTextOrDuplicatingTheQuestion(String apiFormat) {
        scenario = "partial"; format = apiFormat; config(); var body = body(); var result = run(body, ignored -> {});
        assertEquals("COMPLETED", result.status()); assertEquals("保留前半句。继续后半句。", result.text());
        assertEquals(2, requests.size()); assertTrue(requests.get(1).toString().contains("保留前半句。"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_chat_message WHERE role='user'", Integer.class));
        assertTrue(recovery.stream().anyMatch(value -> value.kind().equals("stream") && value.attempt() == 1));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE settled_at IS NOT NULL AND purpose='CHAT'", Integer.class));
        if (apiFormat.equals("chat_completions")) {
            assertEquals(20, jdbc.queryForObject("SELECT sum(input_tokens) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
            assertEquals(10, jdbc.queryForObject("SELECT sum(output_tokens) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
        }
    }
    @Test void authFailureNeverRetriesAndPersistsExactRedactedError() throws Exception {
        scenario = "auth"; config(); var body = body();
        var failure = assertThrows(AiService.StreamFailure.class, () -> run(body, ignored -> {}));
        assertEquals(1, requests.size()); assertNotNull(failure.recovery()); assertEquals("failed", failure.recovery().state());
        assertEquals(0, failure.recovery().attempt()); assertTrue(failure.recovery().error().contains("HTTP 401"));
        assertFalse(failure.recovery().error().contains("fake-fixture-key"));
        assertEquals(failure.recovery(), wiring.turnService().recovery((String) body.get("requestId"), "").orElseThrow());
        assertTrue(stack.history().page(session, null, null, 50).toString().contains("upstream exact failure"));
        assertFalse(jdbc.queryForList("SELECT * FROM ai_chat_turn").toString().contains("upstream exact failure"));
    }
    @Test void zeroDisablesRetryAndExhaustionNeverMakesAnExtraRequest() {
        config(); var repository = new AiRuntimeSettingsRepository(jdbc); ai.setRuntimeSettings(repository);
        var base = AiRuntimeSettings.DEFAULTS;
        repository.save(0, new AiRuntimeSettings(base.maxToolStages(), base.maxToolExecutions(), base.turnTimeoutMinutes(), base.requestTimeoutMinutes(),
                base.maxOutputTokens(), base.requestOutputTokens(), base.finalOutputTokens(), base.repeatedToolLimit(), new AiRetrySettings(0, 0, false, 0)));
        assertThrows(AiService.StreamFailure.class, () -> run(body(), ignored -> {})); assertEquals(1, requests.size());
        repository.save(1, base); scenario = "exhaust"; requests.clear();
        var failure = assertThrows(AiService.StreamFailure.class, () -> run(body(), ignored -> {}));
        assertEquals(5, requests.size()); assertEquals(4, failure.recovery().attempt()); assertEquals(4, failure.recovery().limit());
    }
    @Test void stoppingDuringBackoffDoesNotSendThePendingRetryAndKeepsPartialOutput() {
        scenario = "partial"; config(); var body = body();
        var result = run(body, value -> { if (value.state().equals("waiting")) wiring.turnService().cancelActive((String) body.get("requestId")); });
        assertEquals(1, requests.size()); assertEquals("ABORTED", result.status()); assertEquals("保留前半句。", result.text());
        assertEquals("stopped", ((AiRecovery) result.resultView().get("recovery")).state());
        assertEquals(0, ((AiRecovery) result.resultView().get("recovery")).attempt());
    }

    @Test void nonStreamAuthFailurePersistsRecoveryWithoutLeavingAnActiveTurn() {
        scenario = "auth"; config(); var body = body();
        assertThrows(AiUpstreamException.class, () -> ai.chat(body));
        assertEquals(1, requests.size());
        var value = wiring.turnService().recovery((String) body.get("requestId"), "").orElseThrow();
        assertEquals("failed", value.state()); assertEquals(0, value.attempt());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn WHERE status='RUNNING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_chat_message WHERE role='assistant'", Integer.class));
    }

    @Test void busyStreamUsesValidResponseHeaderAdvice() {
        scenario = "busy"; format = "anthropic"; config();
        var result = run(body(), ignored -> {});
        assertEquals("COMPLETED", result.status()); assertEquals(2, requests.size());
        assertTrue(recovery.stream().anyMatch(value -> value.kind().equals("stream") && value.state().equals("waiting")));
        assertTrue(recovery.stream().noneMatch(value -> value.state().equals("failed")));
    }

    @Test void stopDuringPersistentNetworkWaitIsImmediateAndDoesNotCountAnUnsentRetry() {
        config(); server.stop(0); var body = body();
        var result = run(body, value -> {
            if (value.state().equals("waiting")) {
                assertEquals("network", value.kind()); assertNull(value.limit());
                assertEquals(0, value.attempt());
                assertTrue(value.nextRetryAt() >= System.currentTimeMillis() + 4000);
                wiring.turnService().cancelActive((String) body.get("requestId"));
            }
        });
        assertEquals("ABORTED", result.status()); assertEquals(0, requests.size());
        var value = (AiRecovery) result.resultView().get("recovery");
        assertEquals("stopped", value.state()); assertEquals(0, value.attempt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
    }

    @Test void recoveryWaitStopsAtTheOriginalTurnDeadlineAndReleasesTheConcurrencySlot() {
        config(); var clock = new java.util.concurrent.atomic.AtomicLong();
        var limits = new AiTurnBudget(AiRuntimeSettings.DEFAULTS, clock::get);
        int permits = SessionExecution.UPSTREAM.availablePermits();
        SessionExecution.CURRENT.set(new SessionExecution(limits, java.util.Set.of()));
        try {
            var result = run(body(), value -> {
                if (value.state().equals("waiting")) {
                    assertEquals(permits, SessionExecution.UPSTREAM.availablePermits());
                    clock.set(AiRuntimeSettings.DEFAULTS.turnTimeoutMinutes() * 60_000_000_000L + 1);
                }
            });
            assertEquals("FAILED", result.status()); assertEquals(1, requests.size());
            assertEquals("budget_time", result.resultView().get("finishReason"));
            assertEquals("failed", ((AiRecovery) result.resultView().get("recovery")).state());
            assertEquals(0, ((AiRecovery) result.resultView().get("recovery")).attempt());
            assertEquals(permits, SessionExecution.UPSTREAM.availablePermits());
        } finally { SessionExecution.CURRENT.remove(); }
    }

    @Test void confirmedWriteToolIsNotReexecutedAndEveryRecoveryRequestSettlesItsOwnUsage() {
        scenario = "tool"; toolsEnabled = true; config();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        ai.setChatTools(List.of(new AiService.ChatTool() {
            public AiUpstreamClient.ToolDefinition definition() {
                return new AiUpstreamClient.ToolDefinition("create_material", "fixture write", Map.of("type", "object", "properties", Map.of()));
            }
            public Map<String, Object> execute(ChatContextPlan plan, String arguments, boolean memoryUse, String userText) {
                writes.incrementAndGet(); return Map.of("status", "CREATED", "materialId", "fixture-saved", "count", 1);
            }
            public boolean writes() { return true; }
        }));
        var result = run(body(), ignored -> {});
        assertEquals("COMPLETED", result.status()); assertEquals(3, requests.size()); assertEquals(1, writes.get());
        assertEquals("保留前半句。继续后半句。", result.text());
        assertTrue(requests.get(2).toString().contains("fixture-saved"));
        assertTrue(requests.get(2).toString().contains("保留前半句。"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_chat_message WHERE role='user'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE state='SETTLED' AND purpose='CHAT'", Integer.class));
        assertEquals(15, jdbc.queryForObject("SELECT sum(output_tokens) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
    }
}
