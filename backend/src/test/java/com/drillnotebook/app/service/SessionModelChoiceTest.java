package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 会话级模型与思考档位：选择只影响该会话之后的消息，历史保留每轮实际使用的模型。 */
class SessionModelChoiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private AiConfigRepository configs;
    private HttpServer server;
    private long session;
    private String secondModel;
    /** 模拟供应商回报的用量；个别用例改成带缓存命中的形态。 */
    private volatile Map<String,Object> upstreamUsage = Map.of("prompt_tokens", 10, "completion_tokens", 3);

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("session-model-");
        var ds = Schema9Fixture.dataSource(root); new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); wiring = ChatLegacyWiring.wire(ds, jdbc); stack = ChatContextWiring.wire(ds, jdbc, wiring);
        configs = new AiConfigRepository(jdbc);
        ai = new AiService(configs, wiring.sessions(), new ApiKeyEncryptor(), json, null, wiring.turnService(), wiring.cipher(), stack.contexts());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "好的。"),
                    "finish_reason", "stop")), "usage", upstreamUsage));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "apiKey", "fake-key", "model", "main-model", "apiFormat", "chat_completions", "tools", false, "streaming", false));
        var connections = new AiConnectionsService(configs, new ApiKeyEncryptor(), json);
        connections.view();
        var state = configs.connections().read();
        String provider = state.owner(state.chatModelId()).id();
        connections.saveModels(provider, null, Map.of("revision", state.revision(), "modelId", "deep-model", "tools", false, "streaming", false,
                "contextWindow", 64000, "maxOutputTokens", 16000, "reasoningControl", "effort", "reasoningEfforts", List.of("low", "high")));
        state = configs.connections().read();
        secondModel = state.provider(provider).models().stream().filter(m -> m.modelId().equals("deep-model")).findFirst().orElseThrow().id();
        assertEquals("main-model", state.model(state.chatModelId()).modelId(), "新增模型不改变主模型");
        session = wiring.sessions().create("会话甲", "main-model");
    }
    @AfterEach void cleanup() throws Exception { server.stop(0); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root); }

    private Map<String,Object> body(long id, String text) {
        return Map.of("protocolVersion", 2, "sessionId", id, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "memoryOptions", Map.of("use", false), "input", Map.of("userText", text));
    }
    private JsonNode send(long id, String text) {
        assertEquals("COMPLETED", ai.chat(body(id, text)).get("status"));
        return requests.get(requests.size() - 1);
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> assistantRows(long id) throws Exception {
        return ((List<Map<String,Object>>) stack.history().page(id, null, null, 50).get("messages")).stream()
                .filter(row -> "assistant".equals(row.get("role"))).toList();
    }

    @Test void aSessionFollowsTheMainModelUntilItChoosesItsOwn() throws Exception {
        var first = send(session, "第一问");
        assertEquals("main-model", first.path("model").asText()); assertFalse(first.has("reasoning_effort"));

        var updated = ai.updateSession(session, Map.of("modelConfigId", secondModel, "reasoningEffort", "high"));
        assertEquals(secondModel, updated.get("modelConfigId")); assertEquals("high", updated.get("reasoningEffort"));
        var second = send(session, "第二问");
        assertEquals("deep-model", second.path("model").asText()); assertEquals("high", second.path("reasoning_effort").asText());

        long other = wiring.sessions().create("会话乙", "main-model");
        assertEquals("main-model", send(other, "另一个会话").path("model").asText(), "改选只影响这个会话");
        assertEquals("main-model", configs.connections().read().model(configs.connections().read().chatModelId()).modelId(), "主模型不变");

        var rows = assistantRows(session);
        assertTrue(String.valueOf(rows.get(0).get("model")).endsWith("main-model")); assertFalse(rows.get(0).containsKey("reasoningEffort"));
        assertTrue(String.valueOf(rows.get(1).get("model")).endsWith("deep-model")); assertEquals("high", rows.get(1).get("reasoningEffort"));

        ai.updateSession(session, Map.of("reasoningEffort", ""));
        assertFalse(send(session, "第三问").has("reasoning_effort"), "回到模型默认时不额外指定");
        assertEquals("high", assistantRows(session).get(1).get("reasoningEffort"), "历史不随之后的改选变化");
    }

    @Test void contextUsageReportsTheLatestMeasuredRequestAgainstTheChosenModelsWindow() throws Exception {
        var empty = ai.sessionContextUsage(session);
        assertNull(empty.get("latest"), "没有请求时不给数字");
        assertEquals(128000, empty.get("contextWindow"));
        send(session, "第一问");
        @SuppressWarnings("unchecked") var latest = (Map<String,Object>) ai.sessionContextUsage(session).get("latest");
        assertEquals(10, latest.get("inputTokens")); assertEquals(128000, latest.get("contextWindow"));
        assertTrue(String.valueOf(latest.get("model")).endsWith("main-model"));

        ai.updateSession(session, Map.of("modelConfigId", secondModel));
        var switched = ai.sessionContextUsage(session);
        assertEquals(64000, switched.get("contextWindow"), "改选后按新模型的窗口解释");
        assertEquals(128000, ((Map<?,?>) switched.get("latest")).get("contextWindow"), "已发生的请求保留当时的窗口");
        assertTrue(String.valueOf(switched.get("model")).endsWith("deep-model"));
        assertTrue(((Number) switched.get("outputReserve")).intValue() > 0);

        @SuppressWarnings("unchecked") var usage = (Map<String,Object>) assistantRows(session).get(0).get("usage");
        assertEquals(1, usage.get("requests")); assertEquals(10L, usage.get("inputTokens")); assertEquals(3L, usage.get("outputTokens"));

        jdbc.update("UPDATE ai_chat_session SET model_config_id = 'removed-model' WHERE id = ?", session);
        var broken = ai.sessionContextUsage(session);
        assertNull(broken.get("contextWindow")); assertNotNull(broken.get("latest"), "模型不可用时仍如实给出已有实测值");
        jdbc.update("UPDATE ai_chat_session SET model_config_id = NULL, context_generation = context_generation + 1 WHERE id = ?", session);
        assertNull(ai.sessionContextUsage(session).get("latest"), "上下文换代后旧请求不再代表当前占用");
    }

    @Test void contextUsageSplitsInputIntoCacheHitAndKeepsTheOutputOfTheLatestRequest() {
        send(session, "第一问");
        @SuppressWarnings("unchecked") var plain = (Map<String,Object>) ai.sessionContextUsage(session).get("latest");
        assertEquals(10, plain.get("inputTokens")); assertEquals(3, plain.get("outputTokens"));
        assertTrue(plain.containsKey("cacheReadTokens")); assertNull(plain.get("cacheReadTokens"), "供应商没回报缓存时不写 0");

        upstreamUsage = Map.of("prompt_tokens", 900, "completion_tokens", 40, "prompt_tokens_details", Map.of("cached_tokens", 640));
        send(session, "第二问");
        @SuppressWarnings("unchecked") var cached = (Map<String,Object>) ai.sessionContextUsage(session).get("latest");
        assertEquals(900, cached.get("inputTokens"), "OpenAI 口径的输入已含缓存命中，不重复相加");
        assertEquals(640, cached.get("cacheReadTokens")); assertEquals(40, cached.get("outputTokens"));

        upstreamUsage = Map.of("prompt_tokens", 500, "completion_tokens", 7, "prompt_cache_hit_tokens", 320, "prompt_cache_miss_tokens", 180);
        send(session, "第三问");
        @SuppressWarnings("unchecked") var topLevel = (Map<String,Object>) ai.sessionContextUsage(session).get("latest");
        assertEquals(500, topLevel.get("inputTokens")); assertEquals(320, topLevel.get("cacheReadTokens"), "usage 顶层的命中数同样识别");
        assertEquals(7, topLevel.get("outputTokens"));
    }

    @Test void theBoundPageIsShownUnderTheQuestionAndOnlyWhenThereIsOne() throws Exception {
        var withPage = new LinkedHashMap<>(body(session, "讲解当前这页"));
        withPage.put("pageSnapshot", Map.of("kind", "note", "title", "导数的定义", "capturedAt", "2026-11-11T08:00:00Z",
                "markdown", "页面正文不进历史", "pageId", 7, "notebookId", 2));
        assertEquals("COMPLETED", ai.chat(withPage).get("status"));
        send(session, "没有页面的提问");
        @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) stack.history().page(session, null, null, 50).get("messages");
        var questions = rows.stream().filter(row -> "user".equals(row.get("role"))).toList();
        assertEquals(Map.of("title", "导数的定义", "link", "drill-notebook://material/note/7"), questions.get(0).get("page"));
        assertFalse(questions.get(1).containsKey("page"));
        var answers = rows.stream().filter(row -> "assistant".equals(row.get("role"))).toList();
        assertFalse(answers.get(0).containsKey("page")); assertTrue(answers.get(0).containsKey("model"));
        assertFalse(questions.get(0).containsKey("model"));
        assertFalse(rows.toString().contains("页面正文不进历史"));
    }

    @Test void pinningKeepsTheFirstPinTimeAndNeverTouchesRecency() throws Exception {
        long other = wiring.sessions().create("会话乙", "main-model");
        String updatedAt = String.valueOf(wiring.sessions().find(session).get("updatedAt"));
        assertNull(wiring.sessions().find(session).get("pinnedAt"));
        String first = String.valueOf(ai.updateSession(other, Map.of("pinned", true)).get("pinnedAt"));
        Thread.sleep(5);
        String second = String.valueOf(ai.updateSession(session, Map.of("pinned", true)).get("pinnedAt"));
        assertTrue(first.compareTo(second) < 0, "先置顶的时刻更早");
        assertEquals(second, ai.updateSession(session, Map.of("pinned", true)).get("pinnedAt"), "重复置顶不改变顺序");
        assertEquals(second, ai.updateSession(session, Map.of("title", "改名不影响置顶")).get("pinnedAt"));
        assertEquals(updatedAt, wiring.sessions().find(session).get("updatedAt"), "置顶不算会话活动");
        assertNull(ai.updateSession(session, Map.of("pinned", false)).get("pinnedAt"));
        assertEquals(first, wiring.sessions().find(other).get("pinnedAt"));
        assertThrows(IllegalArgumentException.class, () -> ai.updateSession(session, Map.of("pinned", "yes")));
        assertTrue(ai.listSessions(false).stream().anyMatch(row -> first.equals(row.get("pinnedAt"))));
    }

    @Test void effortMustBeSupportedAndFallsBackWhenTheModelChanges() {
        ai.updateSession(session, Map.of("modelConfigId", secondModel, "reasoningEffort", "low"));
        assertThrows(IllegalArgumentException.class, () -> ai.updateSession(session, Map.of("reasoningEffort", "max")));
        assertThrows(IllegalArgumentException.class, () -> ai.updateSession(session, Map.of("modelConfigId", "missing-model")));
        assertEquals("low", ai.updateSession(session, Map.of("title", "只改标题")).get("reasoningEffort"), "无关修改保留选择");
        var back = ai.updateSession(session, Map.of("modelConfigId", ""));
        assertNull(back.get("modelConfigId")); assertNull(back.get("reasoningEffort"), "主模型不支持该档位时回到模型默认");
        assertThrows(IllegalArgumentException.class, () -> ai.updateSession(session, Map.of("reasoningEffort", "low")));
        assertEquals("main-model", send(session, "跟随主模型").path("model").asText());
    }

    @Test void newSessionsCanStartWithAChoiceAndInvalidChoicesCreateNothing() {
        var created = ai.createSession(Map.of("title", "新会话", "modelConfigId", secondModel, "reasoningEffort", "low"));
        long id = ((Number) created.get("id")).longValue();
        assertEquals(secondModel, created.get("modelConfigId"));
        var request = send(id, "首条消息");
        assertEquals("deep-model", request.path("model").asText()); assertEquals("low", request.path("reasoning_effort").asText());
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session", Integer.class);
        assertThrows(IllegalArgumentException.class, () -> ai.createSession(Map.of("modelConfigId", "missing-model")));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session", Integer.class));
    }

    @Test void aDeletedChoiceIsReportedInsteadOfSilentlyUsingAnotherModel() {
        ai.updateSession(session, Map.of("modelConfigId", secondModel));
        jdbc.update("UPDATE ai_chat_session SET model_config_id = 'removed-model' WHERE id = ?", session);
        int sent = requests.size();
        var error = assertThrows(IllegalArgumentException.class, () -> ai.chat(body(session, "还能发吗")));
        assertTrue(error.getMessage().contains("重新选择模型"), error.getMessage());
        assertEquals(sent, requests.size(), "不向其他模型发送");
    }

    @Test void upgradeFrom17KeepsSessionsFollowingTheMainModel() throws Exception {
        jdbc.execute("ALTER TABLE ai_chat_session DROP COLUMN model_config_id");
        jdbc.execute("ALTER TABLE ai_chat_session DROP COLUMN reasoning_effort");
        // 旧库没有「命中 / 输出」两列：升级时补上，已有轮次保持未回报。
        jdbc.execute("ALTER TABLE ai_chat_turn DROP COLUMN cache_read_tokens");
        jdbc.execute("ALTER TABLE ai_chat_turn DROP COLUMN output_tokens");
        jdbc.update("UPDATE schema_version SET version=17");
        new DatabaseInitializer(jdbc.getDataSource(), Schema9Fixture.resolver(root)).initialize();
        assertEquals(DatabaseInitializer.currentSchemaVersion(), jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        var view = wiring.sessions().find(session);
        assertEquals("会话甲", view.get("title")); assertNull(view.get("modelConfigId")); assertNull(view.get("reasoningEffort"));
        assertEquals("main-model", send(session, "升级后").path("model").asText());
        assertEquals(3, ((Map<?,?>) ai.sessionContextUsage(session).get("latest")).get("outputTokens"), "升级后新请求照常记录输出");
    }
}
