package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiConnectionsStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class AiModelVerificationTest {
    final ObjectMapper mapper = new ObjectMapper();
    Path root;
    AiConfigRepository configs;
    AiConnectionsService service;
    HttpServer server;
    ExecutorService executor;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    String mode = "success";
    Consumer<JsonNode> beforeReply = ignored -> { };

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("model-verification-test-");
        var source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        configs = new AiConfigRepository(new JdbcTemplate(source));
        service = new AiConnectionsService(configs, new ApiKeyEncryptor(), mapper);
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody()); requests.add(request); beforeReply.accept(request);
                boolean anthropic = exchange.getRequestURI().getPath().endsWith("messages");
                JsonNode content = request.path("messages").get(0).path("content");
                boolean tool = request.has("tools"), vision = content.isArray() && content.size() > 1;
                if (mode.equals("text-error") || (mode.equals("optional-error") && (tool || vision))) {
                    byte[] bytes = "{\"error\":\"secret-response-must-not-be-stored\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, bytes.length); exchange.getResponseBody().write(bytes); return;
                }
                String text = mode.equals("empty") ? "" : "pong";
                if (vision && !mode.equals("ignored-capabilities")) {
                    JsonNode image = content.get(1);
                    String base64 = anthropic ? image.path("source").path("data").asText() : image.path("image_url").path("url").asText().split(",", 2)[1];
                    text = digits(base64);
                }
                Map<String, Object> response;
                String finish = mode.equals("truncated") ? "length" : "stop";
                if (tool && !mode.equals("ignored-capabilities")) {
                    JsonNode definition = anthropic ? request.path("tools").get(0) : request.path("tools").get(0).path("function");
                    String nonce = definition.path(anthropic ? "input_schema" : "parameters").path("properties").path("nonce").path("enum").get(0).asText();
                    Map<String, Object> args = Map.of("nonce", mode.equals("wrong-nonce") ? "incorrect" : nonce);
                    response = anthropic ? Map.of("content", List.of(Map.of("type", "tool_use", "id", "test-call", "name", "connection_check", "input", args)), "stop_reason", "tool_use")
                            : Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of("tool_calls", List.of(Map.of("id", "test-call", "type", "function", "function", Map.of("name", "connection_check", "arguments", mapper.writeValueAsString(args))))))));
                } else response = anthropic ? Map.of("content", List.of(Map.of("type", "text", "text", text)), "stop_reason", "end_turn")
                        : Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", text))));
                byte[] bytes = mapper.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void cleanup() throws Exception { server.stop(0); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); TestWorkspace.deleteRecursively(root); }
    Map<String, Object> body(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("revision", service.view().get("revision"));
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
    String provider(String format) {
        service.saveProvider(null, body("name", "Probe", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "apiFormat", format, "authMode", "none"));
        var providers = configs.connections().read().providers();
        return providers.get(providers.size() - 1).id();
    }
    Map<String, Object> verify(String provider, String id) { return service.verifyAndAdd(provider, body("requestId", id, "model", Map.of("modelId", "gpt-5.2"))); }
    List<AiConnectionsStore.Model> models(String provider) { return configs.connections().read().provider(provider).models(); }

    @ParameterizedTest @ValueSource(strings = {"chat_completions", "anthropic"})
    void actualImageAndToolResponseEnableCapabilitiesAndPresets(String format) {
        String p = provider(format);
        JsonNode result = mapper.valueToTree(verify(p, "happy"));
        assertTrue(result.path("added").asBoolean(), result.toString());
        for (String check : List.of("text", "image", "tools")) assertEquals("passed", result.path("verification").path(check).path("status").asText(), result.toString());
        assertEquals(3, requests.size());
        assertTrue((Boolean) models(p).get(0).params().get("tools"));
        assertTrue((Boolean) models(p).get(0).params().get("visionVerified"));
        if (format.equals("chat_completions")) assertEquals(400000, models(p).get(0).params().get("contextWindow"));
        assertEquals(1, requests.get(0).path("messages").size());
        assertFalse(requests.get(0).has("tools")); assertFalse(requests.get(1).has("tools"));
        assertEquals(1, requests.get(2).path("tools").size());
        assertFalse(requests.toString().contains("search_memories"));
        assertFalse(requests.toString().contains("tool_result"));
        assertNull(configs.connections().read().chatModelId());
        assertEquals("passed", mapper.valueToTree(service.view()).path("providers").get(0).path("models").get(0).path("verification").path("text").path("status").asText());
        service.saveProvider(p, body("name", "Probe", "endpoint", "http://127.0.0.1:1/v1", "apiFormat", format, "authMode", "none"));
        assertFalse(models(p).get(0).params().containsKey("verification"));
        assertFalse(models(p).get(0).params().containsKey("visionVerified"));
        assertEquals(true, models(p).get(0).params().get("tools"));
    }
    @ParameterizedTest @ValueSource(strings = {"text-error", "empty", "truncated"})
    void invalidTextDoesNotAddOrRunOptionalRequests(String failure) {
        mode = failure; String p = provider("chat_completions");
        JsonNode result = mapper.valueToTree(verify(p, "fail"));
        assertFalse(result.path("added").asBoolean()); assertTrue(models(p).isEmpty()); assertEquals(1, requests.size());
        assertEquals("skipped", result.path("verification").path("tools").path("status").asText());
        assertFalse(result.toString().contains("secret-response"));
    }
    @ParameterizedTest @ValueSource(strings = {"optional-error", "ignored-capabilities"})
    void textAloneAddsButDoesNotEnableUnsupportedCapabilities(String failure) {
        mode = failure; String p = provider("chat_completions");
        JsonNode result = mapper.valueToTree(verify(p, "optional"));
        assertTrue(result.path("added").asBoolean()); assertEquals(3, requests.size());
        assertEquals(false, models(p).get(0).params().get("tools"));
        assertEquals(false, models(p).get(0).params().get("visionVerified"));
        assertFalse(result.toString().contains("secret-response"));
    }
    @Test void wrongToolArgumentsDoNotEnableTools() {
        mode = "wrong-nonce"; String p = provider("chat_completions"); verify(p, "wrong");
        assertEquals(false, models(p).get(0).params().get("tools")); assertEquals(true, models(p).get(0).params().get("visionVerified"));
    }
    @Test void duplicateExistingModelDoesNotProbeOrOverwritePreferences() {
        String p = provider("chat_completions");
        service.saveModels(p, null, body("modelId", "gpt-5.2", "name", "Manual", "tools", false));
        var original = models(p).get(0);
        assertEquals(true, verify(p, "duplicate").get("existing"));
        assertTrue(requests.isEmpty()); assertEquals(List.of(original), models(p));
    }
    @Test void cancellationBeforeRegistrationSendsNothingAndLeavesSupplier() {
        String p = provider("chat_completions"); service.cancelTest("early");
        assertEquals(true, verify(p, "early").get("cancelled"));
        assertTrue(models(p).isEmpty()); assertTrue(requests.isEmpty()); assertFalse(AiConnectionUsage.providerActive(p));
    }
    @Test void cancellationStopsActiveRequestAndPreventsAdd() throws Exception {
        String p = provider("chat_completions"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeReply = ignored -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        var task = executor.submit(() -> verify(p, "cancel"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); service.cancelTest("cancel"); release.countDown();
            assertEquals(true, task.get(5, TimeUnit.SECONDS).get("cancelled"));
            assertTrue(models(p).isEmpty()); assertEquals(1, requests.size()); assertFalse(AiConnectionUsage.providerActive(p));
        } finally { release.countDown(); }
    }
    @Test void changedConfigurationRejectsLateResultWithoutOverwriting() throws Exception {
        String p = provider("chat_completions"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeReply = ignored -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        var task = executor.submit(() -> verify(p, "conflict"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.saveProvider(p, body("name", "Changed", "endpoint", "http://127.0.0.1:1/v1", "apiFormat", "chat_completions", "authMode", "none")); release.countDown();
            assertInstanceOf(ChatTurnRejectException.class, assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS)).getCause());
            assertTrue(models(p).isEmpty());
        } finally { release.countDown(); }
    }
    @Test void concurrentVerificationMergesEveryResultAndQueuedRequestsUseConnectionVersion() throws Exception {
        String p = provider("chat_completions");
        String version = mapper.valueToTree(service.view()).path("providers").get(0).path("connectionVersion").asText();
        var snapshot = body("connectionVersion", version);
        var entered = new CountDownLatch(3); var release = new CountDownLatch(1);
        beforeReply = ignored -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        List<Future<Map<String, Object>>> tasks = new ArrayList<>();
        for (String id : List.of("alpha", "beta", "gamma")) {
            var request = new LinkedHashMap<>(snapshot); request.put("requestId", id); request.put("model", TestModelLimits.config("modelId", id));
            tasks.add(executor.submit(() -> service.verifyAndAdd(p, request)));
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "all three text probes must overlap");
            service.saveModels(p, null, body("modelId", "manual", "contextWindow", 128000, "maxOutputTokens", 32000));
            String manual = models(p).get(0).id(); service.bind(body("purpose", "chat", "modelId", manual));
            service.saveProvider(p, body("name", "Renamed", "endpoint", configs.connections().read().provider(p).endpoint(), "apiFormat", "chat_completions", "authMode", "none"));
            release.countDown();
            for (var task : tasks) assertEquals(true, task.get(10, TimeUnit.SECONDS).get("added"));
            assertEquals(4, models(p).size());
            assertEquals(manual, configs.connections().read().chatModelId());
            assertEquals("Renamed", configs.connections().read().provider(p).name());
            // 排队请求即使持有旧全局 revision，只要实际连接没变也无需重复探测。
            snapshot.put("requestId", "queued"); snapshot.put("model", TestModelLimits.config("modelId", "delta"));
            assertEquals(true, service.verifyAndAdd(p, snapshot).get("added"));
            assertEquals(Set.of("manual", "alpha", "beta", "gamma", "delta"), new HashSet<>(models(p).stream().map(AiConnectionsStore.Model::modelId).toList()));
            assertEquals(12, requests.size());
            assertFalse(AiConnectionUsage.providerActive(p));
        } finally { release.countDown(); }
    }
    @Test void staleConnectionVersionRejectsBeforeAnyProbe() {
        String p = provider("chat_completions");
        String version = mapper.valueToTree(service.view()).path("providers").get(0).path("connectionVersion").asText();
        var request = body("connectionVersion", version, "requestId", "stale", "model", Map.of("modelId", "alpha"));
        service.saveProvider(p, body("name", "Probe", "endpoint", "http://127.0.0.1:1/v1", "apiFormat", "chat_completions", "authMode", "none"));
        assertThrows(ChatTurnRejectException.class, () -> service.verifyAndAdd(p, request));
        assertTrue(requests.isEmpty()); assertTrue(models(p).isEmpty());
    }
    @Test void modelAddedDuringProbeKeepsItsManualSettings() throws Exception {
        String p = provider("chat_completions"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeReply = ignored -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        var task = executor.submit(() -> verify(p, "manual-race"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.saveModels(p, null, body("modelId", "gpt-5.2", "name", "Manual", "tools", false));
            var original = models(p).get(0); release.countDown();
            assertEquals(true, task.get(10, TimeUnit.SECONDS).get("existing"));
            assertEquals(List.of(original), models(p));
        } finally { release.countDown(); }
    }
    @Test void changingVerifiedModelIdClearsOnlyProbeResultsAndPreservesSettingsAndBindings() {
        String p = provider("chat_completions"); verify(p, "original");
        var original = models(p).get(0);
        service.bind(body("purpose", "chat", "modelId", original.id()));
        service.bind(body("purpose", "import", "modelId", original.id()));
        var state = configs.connections().read(); var owner = state.provider(p);
        var params = new LinkedHashMap<>(original.params()); params.put("cacheBreakpoints", false);
        var cached = new AiConnectionsStore.Model(original.id(), original.modelId(), original.name(), "常用", params);
        configs.connections().save(new AiConnectionsStore.State(state.revision(), List.of(new AiConnectionsStore.Provider(owner.id(), owner.name(), owner.apiFormat(), owner.endpoint(), owner.authMode(), owner.encryptedKey(), owner.keyMeta(), List.of(cached))), original.id(), original.id()), state.revision());
        service.saveModels(null, original.id(), body("modelId", "custom-new"));
        var saved = models(p).get(0);
        assertEquals(original.id(), saved.id()); assertEquals("custom-new", configs.findChat().model());
        assertEquals("custom-new", configs.findImport().model()); assertEquals("常用", saved.group());
        for (String key : List.of("verification", "visionVerified", "cacheBreakpoints")) {
            assertFalse(saved.params().containsKey(key), key); params.remove(key);
        }
        assertEquals(params, saved.params());
        assertEquals(original.name(), saved.name());
    }
    static String digits(String base64) throws java.io.IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            var reader = ImageIO.getImageReaders(input).next();
            java.awt.image.BufferedImage image;
            try { reader.setInput(input); image = reader.read(0); } finally { reader.dispose(); }
            StringBuilder value = new StringBuilder();
            int[] masks = {0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
            int[][] points = {{20,6},{34,20},{34,50},{20,66},{6,50},{6,20},{20,36}};
            for (int digit = 0; digit < 6; digit++) {
                int mask = 0;
                for (int bit = 0; bit < 7; bit++) if ((image.getRGB(12 + digit * 52 + points[bit][0], 8 + points[bit][1]) & 0xffffff) == 0) mask |= 1 << bit;
                int decoded = -1; for (int i = 0; i < masks.length; i++) if (masks[i] == mask) decoded = i;
                assertTrue(decoded >= 0); value.append(decoded);
            }
            return value.toString();
        }
    }
}
