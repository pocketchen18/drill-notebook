package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ToolActivity;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.SessionLookupRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实数据库、加解密、工具循环及双协议上游；只有模型回复使用本机 HTTP 夹具。 */
class SessionToolChatPathTest {
    private static final String SOURCE = "历史计划：周五先练特征值，再复习对角化。";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private HttpServer server;
    private long caller;
    private String sourceLink;
    private boolean discovery;
    private boolean toolsEnabled = true;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("session-tool-chat-");
        var dataSource = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        long source = wiring.sessions().create("线性代数复习计划", "stub");
        var encrypted = wiring.cipher().encrypt(SOURCE, "");
        wiring.sessions().insertMessage(source, "assistant", "", encrypted.cipher(), encrypted.meta());
        sourceLink = (String) wiring.sessions().find(source).get("sessionLink");
        caller = wiring.sessions().create("本次提问", "stub");
        var lookup = new SessionLookupService(new SessionLookupRepository(dataSource), wiring.cipher(), stack.vault());
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(), json,
                null, wiring.turnService(), wiring.cipher(), stack.contexts());
        ai.setChatTools(List.of(stack.memoryListTool(), stack.memorySearchTool(), stack.memoryCreateTool(),
                stack.memoryUpdateTool(), new SessionListTool(lookup), new SessionReadTool(lookup)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::reply);
        server.start();
    }

    @AfterEach void cleanup() throws Exception {
        if (server != null) server.stop(0);
        if (stack != null) stack.indexes().shutdown();
        TestWorkspace.deleteRecursively(root);
    }

    @ParameterizedTest @CsvSource({
            "chat_completions,false,false,false,false", "chat_completions,true,true,true,false",
            "anthropic,false,false,true,false", "anthropic,true,true,false,false",
            "chat_completions,false,true,true,true", "anthropic,false,true,true,true"})
    void linksAndDiscoveryWorkWithMemoryDisabledOrUnavailable(String format, boolean upstreamStream, boolean uiStream,
                                                             boolean discover, boolean memoryUse) throws Exception {
        discovery = discover;
        configure(format, upstreamStream);
        List<Map<String, Object>> events = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        List<ToolActivity> activities;
        String requestId = UuidV7.generate(System.currentTimeMillis());
        Map<String, Object> body = Map.of("protocolVersion", 2, "requestId", requestId, "sessionId", caller,
                "memoryOptions", Map.of("use", memoryUse), "input", Map.of("userText",
                        discovery ? "找到以前的线性代数复习计划并接着讨论" : "读这段会话再继续：" + sourceLink));
        if (uiStream) {
            var result = ai.chatStream(body, (type, content) -> { deltas.add(content); return true; }, events::add);
            assertEquals("COMPLETED", result.status());
            assertTrue(result.text().contains("会话来源"));
            activities = wiring.turnService().toolActivities(requestId, "");
        } else {
            var result = ai.chat(body);
            assertEquals("COMPLETED", result.get("status"), result.toString());
            assertTrue(result.get("reply").toString().contains("会话来源"));
            activities = wiring.turnService().toolActivities(requestId, "");
        }
        assertEquals(discovery ? 3 : 2, requests.size());
        JsonNode definitions = requests.get(0).path("tools");
        assertTrue(definitions.toString().contains("list_sessions"));
        assertTrue(definitions.toString().contains("read_session"));
        assertEquals(memoryUse, definitions.toString().contains("search_memories"));
        assertTrue(requests.get(requests.size() - 1).toString().contains(SOURCE));
        if (discovery) assertTrue(requests.get(1).toString().contains("线性代数复习计划"));
        assertEquals(discovery ? 2 : 1, activities.size());
        assertTrue(activities.stream().allMatch(activity -> "OK".equals(activity.outcome())));
        assertEquals("read_session", activities.get(activities.size() - 1).toolName());
        assertEquals(1, activities.get(activities.size() - 1).count());
        if (uiStream) {
            assertTrue(events.stream().anyMatch(event -> "tools".equals(event.get("phase"))));
            assertFalse(json.writeValueAsString(events).contains(SOURCE));
            assertFalse(deltas.toString().contains(SOURCE));
        }
        var history = stack.history().page(caller, null, null, 20);
        assertFalse(history.toString().contains(SOURCE), "跨会话原始正文不复制到当前转录");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_vault", Integer.class));
    }

    @Test void disablingModelToolsKeepsThePlainChatPath() {
        toolsEnabled = false;
        configure("chat_completions", false);
        var result = ai.chat(Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", caller, "memoryOptions", Map.of("use", false), "input", Map.of("userText", sourceLink)));
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).has("tools"));
    }

    private void configure(String format, boolean stream) {
        ai.saveConfig(Map.of("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "apiFormat", format, "model", "stub", "apiKey", "fake-key", "tools", toolsEnabled, "streaming", stream));
    }

    private void reply(HttpExchange exchange) throws IOException {
        JsonNode request = json.readTree(exchange.getRequestBody());
        requests.add(request);
        int round = requests.size() - 1;
        boolean done = !toolsEnabled || round >= (discovery ? 2 : 1);
        String tool = discovery && round == 0 ? "list_sessions" : "read_session";
        Map<String, Object> args = "list_sessions".equals(tool) ? Map.of("query", "线性代数") : Map.of("session", sourceLink);
        boolean anthropic = exchange.getRequestURI().getPath().endsWith("/messages");
        boolean stream = request.path("stream").asBoolean(false);
        String finalText = "已查阅[会话来源](" + sourceLink + ")，可以继续复习。";
        String payload;
        if (anthropic) {
            Map<String, Object> block = done ? Map.of("type", "text", "text", finalText)
                    : Map.of("type", "tool_use", "id", "call_" + round, "name", tool, "input", args);
            if (!stream) {
                payload = json.writeValueAsString(Map.of("type", "message", "role", "assistant", "content", List.of(block),
                        "stop_reason", done ? "end_turn" : "tool_use", "usage", Map.of("input_tokens", 20, "output_tokens", 15)));
            } else {
                Map<String, Object> start = done ? Map.of("type", "text", "text", "")
                        : Map.of("type", "tool_use", "id", "call_" + round, "name", tool, "input", Map.of());
                payload = event(Map.of("type", "message_start", "message", Map.of("usage", Map.of("input_tokens", 20))))
                        + event(Map.of("type", "content_block_start", "index", 0, "content_block", start))
                        + event(Map.of("type", "content_block_delta", "index", 0, "delta", done
                                ? Map.of("type", "text_delta", "text", finalText)
                                : Map.of("type", "input_json_delta", "partial_json", json.writeValueAsString(args))))
                        + event(Map.of("type", "content_block_stop", "index", 0))
                        + event(Map.of("type", "message_delta", "delta", Map.of("stop_reason", done ? "end_turn" : "tool_use"),
                                "usage", Map.of("output_tokens", 15)))
                        + event(Map.of("type", "message_stop"));
            }
        } else {
            Map<String, Object> call = new java.util.LinkedHashMap<>(Map.of("id", "call_" + round, "type", "function",
                    "function", Map.of("name", tool, "arguments", json.writeValueAsString(args))));
            if (stream) call.put("index", 0);
            Map<String, Object> message = done ? Map.of("content", finalText)
                    : Map.of("content", "", "tool_calls", List.of(call));
            Map<String, Object> body = Map.of("choices", List.of(Map.of(stream ? "delta" : "message", message,
                    "finish_reason", done ? "stop" : "tool_calls")), "usage", Map.of("prompt_tokens", 20, "completion_tokens", 15));
            payload = stream ? "data: " + json.writeValueAsString(body) + "\n\ndata: [DONE]\n\n" : json.writeValueAsString(body);
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    private String event(Map<String, Object> data) throws IOException {
        return "event: " + data.get("type") + "\ndata: " + json.writeValueAsString(data) + "\n\n";
    }
}
