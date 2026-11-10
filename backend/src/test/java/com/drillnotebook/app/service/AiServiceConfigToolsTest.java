package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * GET /api/ai/config 的 chat 槽位必须暴露有效 tools 能力：
 * 显式 params.tools 优先，缺省时回落到 ProviderCapabilities 的已验证端点默认值。
 */
class AiServiceConfigToolsTest {
    private Path appRoot;
    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbc;
    private AiService service;

    @BeforeEach
    void setUp() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("ai-config-tools-");
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + appRoot.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ChatLegacyWiring.Wiring wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        service = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), null);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void customEndpointExposesToolsOffByDefault() {
        save("https://gateway.example.com/v1", "stub-model", null, null);

        Map<String, Object> chat = chatSlot(service.redactedConfig());
        assertTrue(chat.containsKey("tools"), "GET /api/ai/config 的 chat 槽位必须带 tools");
        assertEquals(false, chat.get("tools"));
    }

    @Test
    void explicitToolsTrueRoundTrips() {
        save("https://gateway.example.com/v1", "stub-model", null, true);

        assertEquals(true, chatSlot(service.redactedConfig()).get("tools"));
    }

    @Test
    void explicitToolsFalseRoundTrips() {
        save("https://gateway.example.com/v1", "stub-model", null, false);

        assertEquals(false, chatSlot(service.redactedConfig()).get("tools"));
    }

    @Test
    void explicitToolsSurvivesUnrelatedSave() {
        save("https://gateway.example.com/v1", "stub-model", null, true);
        save("https://gateway.example.com/v1", "stub-model-2", null, null);

        assertEquals(true, chatSlot(service.redactedConfig()).get("tools"));
    }

    @Test
    void verifiedAnthropicEndpointDefaultsToolsOn() {
        save("https://api.anthropic.com", "claude-3-5-sonnet-20241022", "anthropic", null);

        assertEquals(true, chatSlot(service.redactedConfig()).get("tools"));
    }

    @Test
    void explicitFalseOverridesVerifiedEndpointDefault() {
        save("https://api.anthropic.com", "claude-3-5-sonnet-20241022", "anthropic", false);

        assertEquals(false, chatSlot(service.redactedConfig()).get("tools"));
    }

    @Test void missingLimitsCannotBeSavedOrUsedByLegacyProbeAndChat() {
        var body = Map.<String, Object>of("provider", "custom", "endpoint", "mock://local", "model", "fixture");
        assertEquals("MODEL_LIMITS_REQUIRED", assertThrows(ChatTurnRejectException.class, () -> service.saveConfig(body)).code());
        new AiConfigRepository(jdbc).upsert("chat", "custom", "mock://local", "fixture", null, null, "{}");
        assertEquals("MODEL_LIMITS_REQUIRED", assertThrows(ChatTurnRejectException.class, () -> service.chat(Map.of())).code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn", Integer.class));
        assertEquals("MODEL_LIMITS_REQUIRED", assertThrows(ChatTurnRejectException.class, () -> service.testModel(Map.of())).code());
        var direct = Map.<String, Object>of("baseUrl", "mock://local", "model", "fixture", "apiKey", "fake", "apiFormat", "chat_completions");
        assertEquals("MODEL_LIMITS_REQUIRED", assertThrows(ChatTurnRejectException.class, () -> service.testModel(direct)).code());
        assertEquals(true, service.testModel(TestModelLimits.config(direct)).get("ok"));
    }

    private void save(String endpoint, String model, String apiFormat, Boolean tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("provider", "custom");
        body.put("endpoint", endpoint);
        body.put("model", model);
        if (apiFormat != null) body.put("apiFormat", apiFormat);
        if (tools != null) body.put("tools", tools);
        service.saveConfig(TestModelLimits.config(body));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> chatSlot(Map<String, Object> config) {
        return (Map<String, Object>) config.get("chat");
    }
}
