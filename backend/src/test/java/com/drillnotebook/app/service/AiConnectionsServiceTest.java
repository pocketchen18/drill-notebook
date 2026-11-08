package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiConnectionsStore;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class AiConnectionsServiceTest {
    private Path root;
    private JdbcTemplate jdbc;
    private AiConfigRepository configs;
    private AiConnectionsService service;
    private final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("connections-test-");
        var dataSource = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        configs = new AiConfigRepository(jdbc);
        service = new AiConnectionsService(configs, new ApiKeyEncryptor(), mapper);
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private Map<String, Object> body(Object... fields) {
        var body = new LinkedHashMap<String, Object>();
        body.put("revision", service.view().get("revision"));
        for (int i = 0; i < fields.length; i += 2) body.put((String) fields[i], fields[i + 1]);
        return body;
    }
    private AiConnectionsStore.State state() { return configs.connections().read(); }
    private String provider(String name, String endpoint) {
        service.saveProvider(null, body("name", name, "endpoint", endpoint, "apiFormat", "chat_completions", "authMode", "none"));
        return state().providers().get(state().providers().size() - 1).id();
    }
    private String model(String provider, String id, boolean tools) {
        service.saveModels(provider, null, body("modelId", id, "streaming", !tools, "tools", tools));
        var models = state().provider(provider).models();
        return models.get(models.size() - 1).id();
    }
    @Test void presetAdditionFillsOnceAndUserValuesRemainIndependent() {
        String p = provider("Gateway", "https://example.invalid/v1");
        service.saveModels(p, null, body("modelIds", List.of("gpt-5.2", "unknown-model")));
        var known = state().provider(p).models().get(0);
        assertEquals("GPT-5.2", known.name());
        assertEquals(400000, known.params().get("contextWindow"));
        assertEquals("effort", known.params().get("reasoningControl"));
        assertNull(known.params().get("defaultReasoningEffort"));
        assertEquals(false, known.params().get("tools"));
        assertEquals("effort", state().provider(p).models().get(1).params().get("reasoningControl"));
        service.saveModels(null, known.id(), body("modelId", "gpt-5.2", "contextWindow", 12000,
                "maxOutputTokens", 2000, "reasoningEfforts", List.of("low", "high"), "defaultReasoningEffort", "low"));
        service.saveModels(p, null, body("modelIds", List.of("gpt-5.2")));
        assertEquals(2, state().provider(p).models().size());
        assertEquals(12000, state().model(known.id()).params().get("contextWindow"));
        assertEquals("low", state().model(known.id()).params().get("defaultReasoningEffort"));
        var persisted = new AiConnectionsService(new AiConfigRepository(jdbc), new ApiKeyEncryptor(), mapper);
        assertTrue(mapper.valueToTree(persisted.view()).toString().contains("defaultReasoningEffort"));
    }
    @Test void legacyModelsAreNotAutofilledAndInvalidEffortWriteIsAtomic() {
        configs.upsert("chat", "custom", "https://example.invalid/v1", "gpt-5.2", null, null, "{}");
        service.view(); var original = state();
        assertNull(original.model(original.chatModelId()).params().get("contextWindow"));
        assertThrows(IllegalArgumentException.class, () -> service.saveModels(null, original.chatModelId(), body("modelId", "gpt-5.2", "reasoningEfforts", List.of("ultra"))));
        assertEquals(original, state());
        var unknown = service.view();
        assertEquals("effort", mapper.valueToTree(unknown).path("providers").get(0).path("models").get(0).path("reasoningControl").asText());
    }
    @Test void modelIdEditsPreserveSettingsAndIdentityAndRejectDuplicatesAtomically() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String original = model(p, "old", true), other = model(p, "duplicate", false);
        service.bind(body("purpose", "chat", "modelId", original));
        service.bind(body("purpose", "import", "modelId", original));
        service.saveModels(null, original, body("modelId", "old", "name", "自定义名称", "group", "常用", "contextWindow", 12000, "maxInputTokens", 800, "maxOutputTokens", 2000,
                "reasoningEfforts", List.of("high"), "defaultReasoningEffort", "high"));
        var settings = state().model(original).params();
        service.saveModels(null, original, body("modelId", " gpt-5.2 "));
        var saved = state().model(original);
        assertEquals("gpt-5.2", saved.modelId()); assertEquals("自定义名称", saved.name());
        assertEquals(settings, saved.params());
        assertEquals("常用", saved.group()); assertEquals(original, state().chatModelId()); assertEquals(original, state().importModelId());
        assertEquals("gpt-5.2", configs.findChat().model());
        var before = state();
        assertThrows(IllegalArgumentException.class, () -> service.saveModels(null, original, body("modelId", "duplicate")));
        assertEquals(before, state()); assertEquals(other, state().provider(p).models().get(1).id());
        service.saveModels(null, original, body("modelId", "custom-new", "tools", true, "contextWindow", 20000, "name", "自定义"));
        assertEquals(true, state().model(original).params().get("tools"));
        assertEquals(20000, state().model(original).params().get("contextWindow"));
        assertEquals("high", state().model(original).params().get("defaultReasoningEffort"));
        assertEquals(800, state().model(original).params().get("maxInputTokens"));
    }
    @Test void selectedPresetAndCustomIdPersistIndependentlyWithExplicitDraftValues() {
        String p = provider("Gateway", "https://example.invalid/v1");
        service.saveModels(p, null, body("modelId", "gateway/gpt-custom", "name", "草稿名称", "presetId", "gpt-5.2",
                "contextWindow", 500000, "maxInputTokens", null, "maxOutputTokens", 128000,
                "reasoningControl", "effort", "reasoningEfforts", List.of("low", "high"), "defaultReasoningEffort", "high", "tools", true));
        var saved = state().provider(p).models().get(0);
        assertEquals("gpt-5.2", saved.params().get("presetId"));
        assertEquals(500000, saved.params().get("contextWindow"));
        service.saveModels(null, saved.id(), body("modelId", "gpt-4.1"));
        assertEquals(saved.params(), state().model(saved.id()).params());
        assertEquals(saved.name(), state().model(saved.id()).name());
        var persisted = new AiConnectionsService(new AiConfigRepository(jdbc), new ApiKeyEncryptor(), mapper);
        var view = mapper.valueToTree(persisted.view()).path("providers").get(0).path("models").get(0);
        assertEquals("gpt-4.1", view.path("modelId").asText());
        assertEquals("gpt-5.2", view.path("preset").path("id").asText());
        assertEquals(500000, view.path("contextWindow").asInt());
        assertEquals("high", view.path("defaultReasoningEffort").asText());
        var before = state();
        assertThrows(IllegalArgumentException.class, () -> service.saveModels(null, saved.id(), body("modelId", "custom", "presetId", "missing")));
        assertEquals(before, state());
    }
    @Test void emptyDraftFieldsRemainEmptyEvenWhenTheTypedIdMatchesAPreset() {
        String p = provider("Gateway", "https://example.invalid/v1");
        service.saveModels(p, null, body("modelId", "gpt-5.2", "name", "", "group", "", "tools", false, "streaming", true,
                "contextWindow", null, "maxInputTokens", null, "maxOutputTokens", null,
                "reasoningControl", "effort", "reasoningEfforts", List.of(), "defaultReasoningEffort", "", "thinkingBudgetTokens", null));
        var saved = state().provider(p).models().get(0);
        assertEquals("", saved.name());
        for (String field : List.of("contextWindow", "maxInputTokens", "maxOutputTokens", "thinkingBudgetTokens", "defaultReasoningEffort")) assertNull(saved.params().get(field), field);
        assertEquals(List.of(), saved.params().get("reasoningEfforts"));
        service.saveModels(null, saved.id(), body("modelId", "gpt-4.1"));
        assertEquals(saved.params(), state().model(saved.id()).params());
    }
    @Test void migrationPreservesCiphertextSeparateAccountsAndExplicitPreferences() throws Exception {
        configs.upsert("chat", "custom", "https://api.openai.com/v1", "same", "cipher-a", "meta-a", "{\"tools\":false,\"streaming\":false}");
        configs.upsert("import", "custom", "https://api.openai.com/v1", "same", "cipher-b", "meta-b", "{\"streaming\":true}");
        configs.upsert("memory", "dedicated", "https://memory.example/v1", "memory", "cipher-c", "meta-c", "{}");
        var first = service.view();
        assertEquals(first, service.view());
        assertEquals(2, state().providers().size());
        assertNotEquals(state().chatModelId(), state().importModelId());
        assertEquals("cipher-a", configs.findChat().encryptedKey());
        assertEquals("cipher-b", configs.findImport().encryptedKey());
        assertEquals(false, AiModelLimits.params(configs.findChat()).get("tools"));
        assertEquals(false, AiModelLimits.params(configs.findChat()).get("streaming"));
        assertEquals(true, AiModelLimits.params(configs.findImport()).get("tools"));
        assertEquals("cipher-c", configs.findMemory().encryptedKey());
        String response = mapper.writeValueAsString(first);
        assertFalse(response.contains("cipher-a")); assertFalse(response.contains("meta-a"));
        assertEquals("cipher-a", configs.findLegacy("chat").encryptedKey());
    }
    @Test void incompleteImportDoesNotTurnIntoFollowAndDemoRemainsUsable() {
        configs.upsert("chat", "demo", "mock://local", "demo", null, null, "{}");
        configs.upsert("import", "custom", "https://incomplete.example", "", null, null, "{}");
        service.view();
        assertNotNull(state().importModelId());
        assertTrue(AiModelLimits.noKey(configs.findChat()));
        assertEquals("pong（本地演示）", service.test(state().chatModelId(), Map.of("prompt", "ping")).get("reply"));
        assertThrows(IllegalArgumentException.class, () -> service.bind(body("purpose", "import", "modelId", state().importModelId())));
    }
    @Test void saveApplyFollowAndPreferencesStayIndependentAcrossProviders() {
        String a = provider("A", "http://localhost:9999/v1"), b = provider("B", "http://localhost:9998/v1");
        String one = model(a, "same", true), two = model(b, "same", false);
        assertNull(configs.findChat());
        service.bind(body("purpose", "chat", "modelId", one));
        service.bind(body("purpose", "import", "modelId", two));
        var snapshot = configs.findChat();
        service.saveModels(null, two, body("modelId", "same", "name", "Second", "tools", false, "streaming", true));
        assertEquals(one, state().chatModelId());
        service.bind(body("purpose", "chat", "modelId", two));
        assertEquals("http://localhost:9999/v1", snapshot.endpoint());
        assertEquals(true, AiModelLimits.params(snapshot).get("tools"));
        assertEquals(false, AiModelLimits.params(configs.findChat()).get("tools"));
        service.bind(body("purpose", "import", "modelId", null));
        assertNull(configs.findImport());
        assertEquals(2, state().providers().size());
    }
    @Test void staleWriteAndReferencedOrRunningDeleteNeverChangeState() {
        String a = provider("A", "http://localhost:9999/v1");
        String one = model(a, "one", false), two = model(a, "two", false);
        service.bind(body("purpose", "chat", "modelId", one));
        var stale = body("purpose", "chat", "modelId", two);
        assertEquals(409, assertThrows(ChatTurnRejectException.class, () -> service.delete(one, false, body())).status());
        var snapshot = configs.findChat();
        try (var usage = AiConnectionUsage.acquire(snapshot)) {
            service.bind(body("purpose", "chat", "modelId", two));
            assertEquals("CONFIG_BUSY", assertThrows(ChatTurnRejectException.class, () -> service.delete(one, false, body())).code());
        }
        assertEquals("CONFIG_CHANGED", assertThrows(ChatTurnRejectException.class, () -> service.bind(stale)).code());
        assertEquals(two, state().chatModelId());
        service.delete(one, false, body());
        assertNull(state().model(one));
    }

    private void memorySettings(boolean enabled, String mode) {
        jdbc.update("INSERT INTO ai_memory_settings(id, enabled, policy_json, use_consent_fingerprint,"
                        + " maintenance_consent_fingerprint, updated_at) VALUES(1, ?, ?, 'old-use', 'old-maintenance', 'before')",
                enabled ? 1 : 0, "{\"maintenanceModelMode\":\"" + mode + "\"}");
    }

    private Map<String, Object> memorySettings() {
        return jdbc.queryForMap("SELECT * FROM ai_memory_settings WHERE id = 1");
    }

    private void assertCurrentMemoryConsent() {
        var target = configs.findChat();
        var saved = memorySettings();
        assertEquals(MemoryPolicyService.consentFingerprint(MemoryPolicyService.PURPOSE_USE,
                EmbeddingSpaceContracts.normalizeEndpoint(target.endpoint()), AiService.apiFormatOf(target), target.model()),
                saved.get("use_consent_fingerprint"));
        assertEquals(MemoryPolicyService.consentFingerprint(MemoryPolicyService.PURPOSE_MAINTENANCE,
                EmbeddingSpaceContracts.normalizeEndpoint(target.endpoint()), AiService.apiFormatOf(target), target.model()),
                saved.get("maintenance_consent_fingerprint"));
    }

    @Test void applyingChatPersistsBothConsentsAndReapplyingRepairsOldStaleState() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String one = model(p, "one", true), two = model(p, "two", true);
        service.bind(body("purpose", "chat", "modelId", one));
        memorySettings(true, "chat");
        service.bind(body("purpose", "chat", "modelId", two));
        assertCurrentMemoryConsent();
        var saved = memorySettings();
        assertEquals(2, ((Number) saved.get("revision")).intValue());
        assertEquals(1, ((Number) saved.get("enabled")).intValue());
        assertEquals(0, ((Number) saved.get("auto_extract")).intValue());
        assertEquals(0, ((Number) saved.get("compress_session")).intValue());
        // 重新建立服务实例后仍然有效，读取配置和重复应用不反复推进记忆版本。
        service = new AiConnectionsService(new AiConfigRepository(jdbc), new ApiKeyEncryptor(), mapper);
        service.view();
        service.bind(body("purpose", "chat", "modelId", two));
        assertEquals(saved, memorySettings());
        jdbc.update("UPDATE ai_memory_settings SET use_consent_fingerprint = 'stale'");
        service.bind(body("purpose", "chat", "modelId", two));
        assertCurrentMemoryConsent();
    }

    @Test void changingAppliedProviderEndpointAndProtocolRefreshesConsents() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String one = model(p, "one", true);
        service.bind(body("purpose", "chat", "modelId", one));
        memorySettings(true, "chat");
        service.saveProvider(p, body("name", "Gateway", "endpoint", "https://new.invalid/v1",
                "apiFormat", "anthropic", "authMode", "none"));
        assertCurrentMemoryConsent();
    }

    @Test void disabledMemoryAndUnrelatedConnectionChangesDoNotGrantConsent() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String one = model(p, "one", true), two = model(p, "two", true);
        service.bind(body("purpose", "chat", "modelId", one));
        memorySettings(false, "chat");
        var disabled = memorySettings();
        service.bind(body("purpose", "chat", "modelId", two));
        assertEquals(disabled, memorySettings());
        jdbc.update("UPDATE ai_memory_settings SET enabled = 1");
        var enabled = memorySettings();
        service.bind(body("purpose", "import", "modelId", one));
        service.saveModels(null, one, body("modelId", "one", "name", "New name"));
        provider("Other", "https://other.invalid/v1");
        assertEquals(enabled, memorySettings());
    }

    @Test void dedicatedMaintenanceConsentSurvivesChatSwitch() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String one = model(p, "one", true), two = model(p, "two", true);
        service.bind(body("purpose", "chat", "modelId", one));
        memorySettings(true, "dedicated");
        service.bind(body("purpose", "chat", "modelId", two));
        assertNotEquals("old-use", memorySettings().get("use_consent_fingerprint"));
        assertEquals("old-maintenance", memorySettings().get("maintenance_consent_fingerprint"));
    }

    @Test void failedConsentWriteRollsBackModelSwitchAndConnectionEdits() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String one = model(p, "one", true), two = model(p, "two", true);
        service.bind(body("purpose", "chat", "modelId", one));
        memorySettings(true, "chat");
        var before = state();
        var memoryBefore = memorySettings();
        jdbc.execute("CREATE TRIGGER fail_consent BEFORE UPDATE ON ai_memory_settings"
                + " BEGIN SELECT RAISE(ABORT, 'test consent write failure'); END");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> service.bind(body("purpose", "chat", "modelId", two)));
        assertEquals(before, state());
        assertEquals(memoryBefore, memorySettings());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> service.saveProvider(p, body("name", "Gateway", "endpoint", "https://new.invalid/v1",
                        "apiFormat", "chat_completions", "authMode", "none")));
        assertEquals(before, state());
        assertEquals(memoryBefore, memorySettings());
    }

    @Test void staleApplyDoesNotUpdateMemoryConsent() {
        String p = provider("Gateway", "https://example.invalid/v1");
        String one = model(p, "one", true), two = model(p, "two", true);
        service.bind(body("purpose", "chat", "modelId", one));
        memorySettings(true, "chat");
        var stale = body("purpose", "chat", "modelId", two);
        service.saveModels(null, two, body("modelId", "two", "name", "Changed"));
        var before = memorySettings();
        assertEquals("CONFIG_CHANGED", assertThrows(ChatTurnRejectException.class, () -> service.bind(stale)).code());
        assertEquals(before, memorySettings());
        assertEquals(one, state().chatModelId());
    }

    @Test void keyChangeIsExplicitEncryptedAndNoAuthClearsIt() throws Exception {
        service.saveProvider(null, body("name", "Private", "endpoint", "https://gateway.example/v1", "apiFormat", "chat_completions", "authMode", "api_key", "apiKey", "test-sensitive-key"));
        var first = state().providers().get(0);
        assertFalse(mapper.writeValueAsString(service.view()).contains("test-sensitive-key"));
        assertNotEquals("test-sensitive-key", first.encryptedKey());
        service.saveProvider(first.id(), body("name", "Renamed", "endpoint", first.endpoint(), "apiFormat", first.apiFormat(), "authMode", "api_key"));
        assertEquals(first.encryptedKey(), state().provider(first.id()).encryptedKey());
        service.saveProvider(first.id(), body("name", "Renamed", "endpoint", first.endpoint(), "apiFormat", first.apiFormat(), "authMode", "none"));
        assertNull(state().provider(first.id()).encryptedKey());
        assertNull(state().provider(first.id()).keyMeta());
    }
    @Test void validationRejectsDuplicateIdsBadUrlsAndTokenBounds() {
        assertThrows(IllegalArgumentException.class, () -> provider("bad", "https://user:pass@example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> provider("bad", "https://example.com/v1?key=private"));
        String p = provider("good", "http://localhost:9999/v1");
        model(p, "case", true);
        assertThrows(IllegalArgumentException.class, () -> model(p, "case", false));
        model(p, "Case", false);
        for (Object value : List.of(0, -1, 1.5, "200")) assertThrows(IllegalArgumentException.class,
                () -> service.saveModels(p, null, body("modelId", "bad", "maxOutputTokens", value)));
        assertThrows(IllegalArgumentException.class, () -> service.saveModels(p, null, body("modelId", "bad", "contextWindow", 100, "maxOutputTokens", 101)));
        service.saveModels(p, null, body("modelId", "valid", "contextWindow", 1000, "maxInputTokens", 900, "maxOutputTokens", 900));
    }
    @Test void discoveryAndTestUseSavedNoAuthConnectionWithoutUserDataOrApplication() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] json = "{\"data\":[{\"id\":\"manual\"},{\"id\":\"new\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length); exchange.getResponseBody().write(json); exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] json = "{\"choices\":[{\"message\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length); exchange.getResponseBody().write(json); exchange.close();
        });
        server.start();
        try {
            String p = provider("Local", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            String m = model(p, "manual", true);
            assertEquals(List.of("manual", "new"), service.discover(p, Map.of()));
            assertEquals(1, state().provider(p).models().size());
            service.saveModels(p, null, body("modelIds", List.of("manual", "new")));
            assertEquals(true, state().model(m).params().get("tools"));
            service.saveModels(null, m, body("modelId", "manual", "tools", true, "maxOutputTokens", 31));
            assertEquals("pong", service.test(m, Map.of("prompt", "hello")).get("reply"));
            var sent = mapper.readTree(request.get());
            assertEquals(31, sent.path("max_tokens").asInt());
            assertEquals(1, sent.path("messages").size());
            assertEquals("hello", sent.path("messages").get(0).path("content").asText());
            assertFalse(sent.has("tools")); assertNull(auth.get()); assertNull(state().chatModelId());
            service.saveModels(null, m, body("modelId", "manual", "maxOutputTokens", 8000,
                    "reasoningEfforts", List.of("low", "high"), "defaultReasoningEffort", "high"));
            assertEquals("pong", service.test(m, Map.of("prompt", "check effort")).get("reply"));
            sent = mapper.readTree(request.get());
            assertEquals("high", sent.path("reasoning_effort").asText());
            assertEquals(4096, sent.path("max_tokens").asInt());
            assertFalse(sent.has("tools")); assertNull(state().chatModelId());
        } finally { server.stop(0); }
    }
    @Test void modelLimitsRejectOversizeInputAndNeverExpandTaskLimit() {
        var config = new AiConfigRepository.ConfigRow("custom", "http://localhost", "m", null, null,
                "{\"maxInputTokens\":50,\"maxOutputTokens\":20,\"contextWindow\":100}");
        assertEquals(20, AiModelLimits.output(config, 4096));
        assertEquals(10, AiModelLimits.output(config, 10));
        assertDoesNotThrow(() -> AiModelLimits.validate(config, List.of(Map.of("content", "hi")), List.of(), 20));
        assertThrows(ChatTurnRejectException.class, () -> AiModelLimits.validate(config, List.of(Map.of("content", "长".repeat(100))), List.of(), 20));
        assertThrows(ChatTurnRejectException.class, () -> AiModelLimits.validate(config, List.of(Map.of("type", "image_url", "image_url", "data:tiny")), List.of(), 20));
    }

    @Test void cancelStopsASilentTestAndReleasesDeleteProtection() throws Exception {
        try (var server = new UpstreamFixtureServer()) {
            server.hangSilently();
            String p = provider("Silent", server.baseUrl());
            String m = model(p, "silent", false);
            var started = new java.util.concurrent.CompletableFuture<Void>();
            var testing = java.util.concurrent.CompletableFuture.runAsync(() -> {
                started.complete(null);
                assertThrows(RuntimeException.class, () -> service.test(m, Map.of("prompt", "ping", "requestId", "cancel-test")));
            });
            started.get(5, java.util.concurrent.TimeUnit.SECONDS);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!server.isHung() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(server.isHung());
            assertEquals("CONFIG_BUSY", assertThrows(ChatTurnRejectException.class, () -> service.delete(m, false, body())).code());
            assertEquals(true, service.cancelTest("cancel-test").get("cancelled"));
            testing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            // 取消幂等接受；未登记时保存短期标识，防止极早取消失效。
            assertEquals(true, service.cancelTest("cancel-test").get("cancelled"));
            service.delete(m, false, body());
            assertNull(state().model(m));
        }
    }

    @Test void anthropicTestHonorsSmallOutputLimitAndExplicitProtocolOverDisplayName() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] json = "{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"stop_reason\":\"end_turn\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length); exchange.getResponseBody().write(json); exchange.close();
        });
        server.start();
        try {
            service.saveProvider(null, body("name", "自定义名称", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "apiFormat", "anthropic", "authMode", "none"));
            String p = state().providers().get(0).id();
            String m = model(p, "messages-model", false);
            service.saveModels(null, m, body("modelId", "messages-model", "maxOutputTokens", 31));
            assertEquals("pong", service.test(m, Map.of("prompt", "ping")).get("reply"));
            assertEquals(31, mapper.readTree(request.get()).path("max_tokens").asInt());
            assertNull(auth.get());
            assertEquals("anthropic", AiService.apiFormatOf(new AiConfigRepository.ConfigRow("anthropic", "url", "model", null, null, "{}")));
            assertEquals("chat_completions", AiService.apiFormatOf(new AiConfigRepository.ConfigRow("anthropic 名称", "url", "model", null, null, "{\"apiFormat\":\"chat_completions\"}")));
        } finally { server.stop(0); }
    }
}
