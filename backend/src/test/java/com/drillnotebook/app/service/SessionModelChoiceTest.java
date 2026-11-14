package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionModelChoiceTest {
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private AiConnectionsService connections;
    private AiConfigRepository configs;
    private AiService ai;
    private HttpServer upstream;
    private final ObjectMapper json = new ObjectMapper();
    private String first, second, other;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("session-model-choice-");
        var source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        var context = ChatContextWiring.wire(source, jdbc, wiring);
        configs = new AiConfigRepository(jdbc);
        connections = new AiConnectionsService(configs, new ApiKeyEncryptor(), json);
        ai = new AiService(configs, wiring.sessions(), new ApiKeyEncryptor(), json, null,
                wiring.turnService(), wiring.cipher(), context.contexts());
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String model = json.readTree(exchange.getRequestBody().readAllBytes()).path("model").asText();
            byte[] reply = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message",
                    Map.of("role", "assistant", "content", "来自 " + model), "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 12, "completion_tokens", 3)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply); exchange.close();
        });
        upstream.start();
        String a = provider("供应商 A", "/a/v1"), b = provider("供应商 B", "/b/v1");
        first = model(a, "first"); second = model(a, "second"); other = model(b, "other");
        connections.selectChatModel(body("modelConfigId", first, "reasoningEffort", "high"));
    }

    @AfterEach void cleanup() throws Exception {
        if (upstream != null) upstream.stop(0);
        TestWorkspace.deleteRecursively(root);
    }

    private Map<String, Object> body(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("revision", connections.view().get("revision"));
        for (int at = 0; at < fields.length; at += 2) result.put((String) fields[at], fields[at + 1]);
        return result;
    }
    private String provider(String name, String path) {
        connections.saveProvider(null, body("name", name, "endpoint", "http://127.0.0.1:" + upstream.getAddress().getPort() + path,
                "apiFormat", "chat_completions", "authMode", "none"));
        var providers = configs.connections().read().providers(); return providers.get(providers.size() - 1).id();
    }
    private String model(String provider, String name) {
        connections.saveModels(provider, null, body("modelId", name, "streaming", false, "tools", false,
                "contextWindow", 32000, "maxOutputTokens", 4000, "reasoningEfforts", List.of("low", "high"), "defaultReasoningEffort", "low"));
        var models = configs.connections().read().provider(provider).models(); return models.get(models.size() - 1).id();
    }
    private long session() { return ((Number) ai.createSession(Map.of()).get("id")).longValue(); }
    private Map<String, Object> request(long session, String text) {
        return Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", session, "input", Map.of("userText", text));
    }

    @Test void selectingElsewhereDoesNotChangeRunningOrQueuedSnapshotsAndNewSessionsInherit() throws Exception {
        long a = session(), b = session();
        try (var runningA = ai.captureConfig(a, ""); var queuedB = ai.captureConfig(b, "")) {
            var saved = connections.selectChatModel(body("sessionId", b, "modelConfigId", other, "reasoningEffort", "low"));
            assertEquals(other, ((Map<?, ?>) saved.get("sessionChoice")).get("modelConfigId"));
            assertEquals(first, wiring.sessions().modelChoice(a).modelConfigId());
            assertEquals("high", wiring.sessions().modelChoice(a).effort());
            assertEquals(other, wiring.sessions().modelChoice(b).modelConfigId());
            assertEquals("first", runningA.row().model());
            assertEquals("first", queuedB.row().model());
            var aResult = ai.runAccepted(request(a, "A 的任务"), runningA, null, null);
            var bResult = ai.runAccepted(request(b, "已经排队的任务"), queuedB, null, null);
            assertEquals("来自 first", aResult.text()); assertEquals("来自 first", bResult.text());
        }
        assertEquals("来自 other", ai.chat(request(b, "B 的新任务")).get("reply"));
        long next = session();
        assertEquals(other, wiring.sessions().modelChoice(next).modelConfigId());
        assertEquals("low", wiring.sessions().modelChoice(next).effort());
        connections.selectChatModel(body("modelConfigId", second, "reasoningEffort", null));
        assertEquals(first, wiring.sessions().modelChoice(a).modelConfigId());
        assertEquals(other, wiring.sessions().modelChoice(b).modelConfigId());
        assertEquals(other, wiring.sessions().modelChoice(next).modelConfigId());
        assertEquals("first", ai.sessionContextUsage(a).get("model"));
        assertEquals("other", ai.sessionContextUsage(b).get("model"));
        new DatabaseInitializer(jdbc.getDataSource(), Schema9Fixture.resolver(root)).initialize();
        connections = new AiConnectionsService(new AiConfigRepository(jdbc), new ApiKeyEncryptor(), json);
        assertEquals(second, connections.view().get("chatModelId"));
        assertEquals(first, wiring.sessions().modelChoice(a).modelConfigId());
        assertEquals(other, wiring.sessions().modelChoice(b).modelConfigId());
    }

    @Test void aDraftCreatesItsCapturedChoiceEvenIfTheInheritanceDefaultChanges() {
        connections.selectChatModel(body("modelConfigId", other, "reasoningEffort", "low"));
        var draft = ai.createSession(Map.of("modelConfigId", first, "reasoningEffort", "high"));
        assertEquals(first, draft.get("modelConfigId")); assertEquals("high", draft.get("reasoningEffort"));
        assertEquals(other, connections.view().get("chatModelId"));
        long current = ((Number) draft.get("id")).longValue();
        ai.updateSession(current, Map.of("modelConfigId", second, "reasoningEffort", "low"));
        assertEquals(second, wiring.sessions().modelChoice(current).modelConfigId());
        assertEquals(other, connections.view().get("chatModelId"));
    }

    @Test void legacyNullChoicesRecoverTheLastActualProviderAndAreThenFixed() {
        long a = session();
        connections.selectChatModel(body("sessionId", a, "modelConfigId", other, "reasoningEffort", "low"));
        ai.chat(request(a, "旧会话实际使用另一供应商"));
        jdbc.update("UPDATE ai_chat_session SET model_config_id = NULL, reasoning_effort = NULL WHERE id = ?", a);
        connections.selectChatModel(body("modelConfigId", second, "reasoningEffort", "high"));
        assertEquals(other, wiring.sessions().modelChoice(a).modelConfigId());
        assertEquals("low", wiring.sessions().modelChoice(a).effort());
        assertEquals("other", ai.sessionContextUsage(a).get("model"));
    }

    @Test void invalidSessionSelectionNeverChangesTheDefaultOrAnyExistingSession() {
        long current = session(); var before = connections.view();
        assertThrows(ChatTurnRejectException.class, () -> connections.selectChatModel(body("sessionId", 99999L,
                "modelConfigId", other, "reasoningEffort", "low")));
        assertEquals(before, connections.view());
        assertEquals(first, wiring.sessions().modelChoice(current).modelConfigId());
    }

    @Test void manualTitleUsesTheTargetSessionsModelEvenAfterAnotherSessionSelectsADifferentProvider() throws Exception {
        long current = session(), elsewhere = session();
        ai.chat(request(current, "原会话的提问"));
        connections.selectChatModel(body("sessionId", elsewhere, "modelConfigId", other, "reasoningEffort", "low"));
        var vault = org.mockito.Mockito.mock(MemoryVaultService.class);
        org.mockito.Mockito.when(vault.sessionPassword(current)).thenReturn(Optional.empty());
        var titles = new AiSessionTitleService(wiring.sessions(), configs, wiring.cipher(), new ApiKeyEncryptor(),
                json, vault, wiring.usage(), wiring.handles());
        try {
            var before = wiring.sessions().find(current);
            titles.generate(current, Map.of("expectedRevision", before.get("titleRevision"), "overwriteManual", true));
            for (int attempt = 0; attempt < 120 && "generating".equals(wiring.sessions().find(current).get("titleStatus")); attempt++) {
                Thread.sleep(25);
            }
            assertEquals("ready", wiring.sessions().find(current).get("titleStatus"));
            assertEquals("来自 first", wiring.sessions().find(current).get("title"));
        } finally { titles.close(); }
    }
}
