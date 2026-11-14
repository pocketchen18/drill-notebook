package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.model.AiToolSettings;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.*;
import com.drillnotebook.app.service.web.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Real chat/tool/storage loop; remote search and native account responses are bounded protocol fixtures. */
class WebToolChatPathTest {
    private static final String QUERY = "仅工具参数中的公开查询";
    private static final String URL = "https://docs.example/a";
    private static final String SEARCH_TEXT = "仅搜索返回的真实来源片段";
    private static final String FETCH_TEXT = "仅网页工具返回的正文";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<AiUpstreamClient.Upstream> mainTargets = new ArrayList<>();
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private AiToolSettingsRepository toolSettings;
    private WebSearchSettingsService webSettings;
    private PublicWebPolicy policy;
    private WebProviders providers;
    private LocalWebFetcher local;
    private HttpServer server;
    private long session;
    private boolean closeSearchBeforeExecute;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("web-chat-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); wiring = ChatLegacyWiring.wire(ds, jdbc); stack = ChatContextWiring.wire(ds, jdbc, wiring);
        session = wiring.sessions().create("联网验收", "stub");
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(), json, null,
                wiring.turnService(), wiring.cipher(), stack.contexts());
        toolSettings = new AiToolSettingsRepository(jdbc); ai.setToolSettings(toolSettings);
        webSettings = new WebSearchSettingsService(new WebSearchSettingsRepository(jdbc), new ApiKeyEncryptor());
        policy = new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        providers = mock(WebProviders.class); local = mock(LocalWebFetcher.class);
        var web = new WebSearchService(webSettings, providers, local, policy);
        ai.setChatTools(List.of(new SearchWebTool(web), new FetchWebTool(web)));
        when(providers.search(eq(WebResult.Provider.exa), eq(QUERY), eq(3), isNull(), any()))
                .thenReturn(WebResult.success(List.of(new WebResult.Page(URL, "真实标题", SEARCH_TEXT, null)), WebResult.Provider.exa));
        when(local.fetch(eq(List.of(URL)), any()))
                .thenReturn(WebResult.success(List.of(new WebResult.Page(URL, "网页标题", FETCH_TEXT, null)), WebResult.Provider.local));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody()); requests.add(request);
            int index = requests.size();
            if (index == 1 && closeSearchBeforeExecute) permission("search_web", false);
            boolean done = index >= 3, stream = request.path("stream").asBoolean();
            boolean anthropic = exchange.getRequestURI().getPath().endsWith("/messages");
            String name = index == 1 ? "search_web" : "fetch_web", id = "web-" + index;
            var args = index == 1 ? Map.of("query", QUERY, "count", 3) : Map.of("urls", List.of(URL));
            String payload;
            if (anthropic) {
                var block = done ? Map.of("type", "text", "text", "依据实际工具结果回答。")
                        : Map.of("type", "tool_use", "id", id, "name", name, "input", args);
                payload = json.writeValueAsString(Map.of("type", "message", "role", "assistant", "content", List.of(block),
                        "stop_reason", done ? "end_turn" : "tool_use", "usage", Map.of("input_tokens", 10, "output_tokens", 5)));
            } else {
                var message = done ? Map.of("role", "assistant", "content", "依据实际工具结果回答。")
                        : Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", id, "type", "function",
                        "function", Map.of("name", name, "arguments", json.writeValueAsString(args)), "index", 0)));
                payload = json.writeValueAsString(Map.of("choices", List.of(Map.of(stream ? "delta" : "message", message,
                        "finish_reason", done ? "stop" : "tool_calls")), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
                if (stream) payload = "data: " + payload + "\n\ndata: [DONE]\n\n";
            }
            var bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void cleanup() throws Exception {
        SessionExecution.CURRENT.remove(); server.stop(0); policy.close(); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root);
    }
    private void config(String format, boolean stream) {
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "apiKey", "fake-fixture-key", "model", "fixture", "apiFormat", format, "tools", true, "streaming", stream));
    }
    private Map<String, Object> body() {
        return Map.of("protocolVersion", 2, "sessionId", session, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "memoryOptions", Map.of("use", false), "input", Map.of("userText", "检查实时资料"),
                "pageSnapshot", Map.of("kind", "manual", "title", "本地笔记", "capturedAt", "2026-11-14T10:00:00Z",
                        "markdown", "本地私人草稿不发往联网服务", "isDraft", true));
    }
    private void permission(String name, boolean enabled) {
        var saved = toolSettings.read();
        var next = new LinkedHashMap<>(saved.values().overrides()); next.put(name, enabled);
        toolSettings.save(saved.revision(), new AiToolSettings(next));
    }
    private JsonNode readResult(JsonNode request, String id) throws Exception {
        for (var message : request.path("messages")) {
            if (id.equals(message.path("tool_call_id").asText())) return json.readTree(message.path("content").asText());
            if (message.path("content").isArray()) for (var block : message.path("content"))
                if ("tool_result".equals(block.path("type").asText()) && id.equals(block.path("tool_use_id").asText()))
                    return json.readTree(block.path("content").asText());
        }
        return json.createObjectNode();
    }
    private void assertPrivateToolDataNotPersisted(Map<String, Object> body, List<Map<String, Object>> events) throws Exception {
        var activities = wiring.turnService().toolActivities((String) body.get("requestId"), "");
        for (String text : List.of(QUERY, SEARCH_TEXT, FETCH_TEXT)) {
            assertFalse(json.writeValueAsString(activities).contains(text));
            assertFalse(json.writeValueAsString(events).contains(text));
            assertFalse(stack.history().page(session, null, null, 50).toString().contains(text));
            assertFalse(jdbc.queryForList("SELECT * FROM ai_chat_turn").toString().contains(text));
            assertFalse(jdbc.queryForList("SELECT * FROM ai_usage_attempt").toString().contains(text));
        }
    }

    @ParameterizedTest @CsvSource({"chat_completions,false,false", "chat_completions,true,true", "anthropic,false,true"})
    void searchThenFetchContinuesAcrossAllExistingChatProtocolsWithoutPersistingToolBodies(String format, boolean upstreamStream, boolean uiStream) throws Exception {
        config(format, upstreamStream); var body = body(); var events = new ArrayList<Map<String, Object>>();
        if (uiStream) assertEquals("COMPLETED", ai.chatStream(body, (kind, text) -> true, events::add).status());
        else assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(3, requests.size());
        assertTrue(requests.get(0).path("tools").toString().contains("search_web"));
        assertTrue(requests.get(0).path("tools").toString().contains("fetch_web"));
        assertFalse(requests.get(0).toString().contains(SEARCH_TEXT));
        assertFalse(requests.get(0).toString().contains("本地私人草稿不发往联网服务"));
        assertEquals("exa", readResult(requests.get(1), "web-1").path("webProvider").asText());
        assertTrue(readResult(requests.get(1), "web-1").toString().contains(SEARCH_TEXT));
        assertTrue(readResult(requests.get(2), "web-2").toString().contains(FETCH_TEXT));
        assertTrue(readResult(requests.get(2), "web-2").path("items").path(0).path("untrusted").asBoolean());
        var activities = wiring.turnService().toolActivities((String) body.get("requestId"), "");
        assertEquals(List.of("search_web", "fetch_web"), activities.stream().map(com.drillnotebook.app.model.ToolActivity::toolName).toList());
        assertEquals(List.of("OK", "OK"), activities.stream().map(com.drillnotebook.app.model.ToolActivity::outcome).toList());
        assertPrivateToolDataNotPersisted(body, events);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE settled_at IS NOT NULL AND purpose='CHAT'", Integer.class));
    }

    @Test void disabledBeforeTurnIsNotAdvertisedOrExecuted() throws Exception {
        permission("search_web", false); config("chat_completions", false);
        ai.chat(body());
        assertFalse(requests.get(0).path("tools").toString().contains("search_web"));
        assertTrue(requests.get(0).path("tools").toString().contains("fetch_web"));
        verifyNoInteractions(providers);
    }

    @Test void permissionClosedAfterTheModelResponseBlocksThePendingTool() throws Exception {
        closeSearchBeforeExecute = true; config("chat_completions", false);
        ai.chat(body());
        assertTrue(requests.get(0).path("tools").toString().contains("search_web"));
        assertEquals("DISABLED", readResult(requests.get(1), "web-1").path("status").asText());
        verifyNoInteractions(providers);
    }

    @Test void permissionClosedBetweenToolsBlocksTheNextExecutionWithoutRemovingPriorResults() throws Exception {
        when(providers.search(eq(WebResult.Provider.exa), eq(QUERY), eq(3), isNull(), any())).thenAnswer(call -> {
            permission("fetch_web", false);
            return WebResult.success(List.of(new WebResult.Page(URL, "标题", SEARCH_TEXT, null)), WebResult.Provider.exa);
        });
        config("chat_completions", true); var body = body();
        assertEquals("COMPLETED", ai.chatStream(body, (kind, text) -> true).status());
        assertEquals("OK", readResult(requests.get(1), "web-1").path("status").asText());
        assertEquals("DISABLED", readResult(requests.get(2), "web-2").path("status").asText());
        verifyNoInteractions(local);
    }

    private WebHttpClient nativeFixture(boolean anthropic) throws Exception {
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", anthropic ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1",
                "apiKey", "fake-native-key", "model", anthropic ? "claude-sonnet-4-6" : "gpt-5.2", "apiFormat", anthropic ? "anthropic" : "chat_completions",
                "tools", true, "streaming", false));
        webSettings.save(json.valueToTree(Map.of("revision", 0, "preferNative", true)));
        var main = mock(AiUpstreamClient.class); ReflectionTestUtils.setField(ai, "upstreamClient", main);
        when(main.callWithTools(any(), anyList(), anyList(), anyBoolean(), any(), anyInt(), any())).thenAnswer(call -> {
            mainTargets.add(call.getArgument(0));
            requests.add(json.valueToTree(Map.of("messages", call.getArgument(1), "tools", call.getArgument(2))));
            var usage = new AiUpstreamClient.Usage("provider", 10, 5, null, null);
            return requests.size() == 1
                    ? new AiUpstreamClient.Result("", "", "tool_calls", true, false, false, usage,
                    List.of(new AiUpstreamClient.ToolCall("web-1", "search_web", json.writeValueAsString(Map.of("query", QUERY, "count", 3)))))
                    : new AiUpstreamClient.Result("依据原生搜索返回资料回答。", "", "stop", true, false, false, usage);
        });
        WebHttpClient nativeHttp = mock(WebHttpClient.class);
        Object response = anthropic ? Map.of("content", List.of(
                Map.of("type", "web_search_tool_result", "content", List.of(Map.of("type", "web_search_result", "url", URL, "title", "真实标题"))),
                Map.of("type", "text", "text", "回答", "citations", List.of(Map.of("url", URL, "cited_text", SEARCH_TEXT)))), "usage", Map.of("input_tokens", 50, "output_tokens", 20))
                : Map.of("status", "completed", "output", List.of(
                Map.of("type", "web_search_call", "status", "completed", "action", Map.of("type", "search", "sources", List.of(Map.of("url", URL, "title", "真实标题")))),
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", SEARCH_TEXT,
                        "annotations", List.of(Map.of("type", "url_citation", "url", URL, "title", "真实标题")))))), "usage", Map.of("input_tokens", 50, "output_tokens", 20));
        when(nativeHttp.request(anyString(), any(), anyMap(), anyString(), anyInt(), any())).thenReturn(
                new WebHttpClient.Reply(200, "application/json", json.writeValueAsBytes(response), null, null, null));
        ai.setNativeWebClient(new NativeWebClient(nativeHttp, policy));
        return nativeHttp;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeRetrievalUsesTheSharedBudgetAndRealUsageLedgerWithoutSendingPrivateContext(boolean anthropic) throws Exception {
        var nativeHttp = nativeFixture(anthropic); var body = body();
        var settings = AiRuntimeSettings.DEFAULTS; var budget = new AiTurnBudget(settings);
        SessionExecution.CURRENT.set(new SessionExecution(budget, Set.of(session)));
        int permits = SessionExecution.UPSTREAM.availablePermits();
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(settings.maxOutputTokens() - 30, budget.allocation(Integer.MAX_VALUE, 0));
        assertEquals(permits, SessionExecution.UPSTREAM.availablePermits());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE settled_at IS NOT NULL AND purpose='CHAT'", Integer.class));
        assertEquals(70, jdbc.queryForObject("SELECT sum(input_tokens) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
        assertEquals(30, jdbc.queryForObject("SELECT sum(output_tokens) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
        String fingerprint = UsageRepository.targetFingerprint(anthropic ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1",
                anthropic ? "anthropic_messages" : "openai_responses", anthropic ? "claude-sonnet-4-6" : "gpt-5.2");
        assertEquals(20, jdbc.queryForObject("SELECT sum(output_tokens) FROM ai_usage_attempt WHERE target_fingerprint=?", Integer.class, fingerprint));
        verify(nativeHttp).request(eq("POST"), eq(URI.create(anthropic ? "https://api.anthropic.com/v1/messages" : "https://api.openai.com/v1/responses")),
                anyMap(), argThat(request -> request.contains(QUERY) && !request.contains("本地私人草稿") && !request.contains("检查实时资料")), anyInt(), any());
        assertTrue(readResult(requests.get(1), "web-1").toString().contains(SEARCH_TEXT));
        assertPrivateToolDataNotPersisted(body, List.of()); verifyNoInteractions(providers, local);
    }

    @Test void nativeRetrievalCannotBypassTheSharedStageLimit() throws Exception {
        var nativeHttp = nativeFixture(false);
        var base = AiRuntimeSettings.DEFAULTS;
        var settings = new AiRuntimeSettings(1, base.maxToolExecutions(), base.turnTimeoutMinutes(), base.requestTimeoutMinutes(),
                base.maxOutputTokens(), base.requestOutputTokens(), base.finalOutputTokens(), base.repeatedToolLimit());
        var repository = new AiRuntimeSettingsRepository(jdbc); repository.save(0, settings); ai.setRuntimeSettings(repository);
        SessionExecution.CURRENT.set(new SessionExecution(new AiTurnBudget(settings), Set.of(session)));
        ai.chat(body());
        assertEquals("LIMIT_REACHED", readResult(requests.get(1), "web-1").path("status").asText());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE purpose='CHAT'", Integer.class));
        verifyNoInteractions(nativeHttp, providers, local);
    }

    @Test void changingTheConfiguredModelDoesNotChangeTheAcceptedTurnOrItsNativeRetrieval() throws Exception {
        var nativeHttp = nativeFixture(false);
        var response = Map.of("status", "completed", "output", List.of(
                Map.of("type", "web_search_call", "status", "completed", "action", Map.of("type", "search", "sources", List.of(Map.of("url", URL, "title", "来源")))),
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", SEARCH_TEXT)))));
        var reply = new WebHttpClient.Reply(200, "application/json", json.writeValueAsBytes(response), null, null, null);
        when(nativeHttp.request(anyString(), any(), anyMap(), anyString(), anyInt(), any())).thenAnswer(call -> {
            ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "https://api.openai.com/v1", "apiKey", "fake-native-key",
                    "model", "gpt-5.4", "apiFormat", "chat_completions", "tools", true, "streaming", false));
            return reply;
        });
        assertEquals("COMPLETED", ai.chat(body()).get("status"));
        assertEquals(List.of("gpt-5.2", "gpt-5.2"), mainTargets.stream().map(AiUpstreamClient.Upstream::model).toList());
        verify(nativeHttp).request(anyString(), any(), anyMap(), argThat(value -> value.contains("\"model\":\"gpt-5.2\"")), anyInt(), any());
    }

    @Test void stoppingDuringNativeRetrievalSettlesUsageAndDoesNotRunFallbackOrAnotherModelRequest() throws Exception {
        var nativeHttp = nativeFixture(false); var body = body();
        when(nativeHttp.request(anyString(), any(), anyMap(), anyString(), anyInt(), any())).thenAnswer(call -> {
            WebRequestContext context = call.getArgument(5); context.handle().requestCancel(); context.check();
            throw new AssertionError("cancel should have stopped this attempt");
        });
        assertEquals("ABORTED", ai.chat(body).get("status"));
        assertEquals(1, requests.size());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai_usage_attempt WHERE settled_at IS NOT NULL AND purpose='CHAT'", Integer.class));
        verifyNoInteractions(providers, local);
    }
}
