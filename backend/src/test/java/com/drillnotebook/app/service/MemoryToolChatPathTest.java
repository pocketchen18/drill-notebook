package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实聊天路径的工具闭环：模型先发 {@code search_memories}，服务端执行后必须带着结果再请求一次，
 * 普通与 SSE 两条路径都只把最终正文交给用户，工具消息不落转录、不进 SSE。
 */
class MemoryToolChatPathTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String FACT = "复习计划先看内存管理";
    private static final String FINAL = "根据记忆，先看内存管理。";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private MemoryItemService items;
    private ToolFixtureServer server;
    private AiService ai;
    private long sessionId;
    private int sequence;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("memory-tool-chat-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        items = new MemoryItemService(stack.crypto(), wiring.memory(), stack.vault(), stack.policy(),
                new MemoryOperationRepository(dataSource, wiring.immediate()),
                new MemoryProposalRepository(dataSource, wiring.immediate()),
                new MemorySuppressionRepository(dataSource, wiring.immediate()),
                new MemoryJobRepository(dataSource, wiring.immediate()), wiring.metadata());
        server = new ToolFixtureServer();
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), stack.contexts());
        ai.saveConfig(Map.of("provider", "custom", "endpoint", server.baseUrl(), "model", "stub-model",
                "apiKey", "stub-key", "tools", true));
        ai.setMemoryTool(stack.memorySearchTool());
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE ai_chat_session SET memory_learn_enabled = 1 WHERE id = ?", sessionId);
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "考试安排", "goal", FACT);
        indexReady();
    }

    @AfterEach
    void cleanup() throws Exception {
        server.close();
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void ordinaryChatExecutesTheToolAndContinuesWithTheModel() throws Exception {
        Map<String, Object> response = ai.chat(body("复习计划怎么安排"));

        assertEquals("COMPLETED", response.get("status"));
        assertEquals(FINAL, response.get("reply"));
        List<String> bodies = server.bodies();
        assertEquals(2, bodies.size(), "工具调用后必须再发一次模型请求");
        assertTrue(bodies.get(0).contains("\"tools\""), "启用工具时首请求必须带工具定义");
        assertTrue(bodies.get(0).contains(MemorySearchTool.NAME));
        assertTrue(bodies.get(1).contains("\"role\":\"tool\""), "续写请求必须带工具结果消息");
        assertTrue(bodies.get(1).contains(FACT), "续写请求必须带上工具返回的事实");
        assertTokenBudgets(bodies);
        assertFalse(dbContains("\"tool_call_id\""), "工具消息不能落进持久化转录");
        assertFalse(dbContains(MemorySearchTool.NAME), "工具名不能落进持久化转录");
    }

    @Test
    void streamChatExecutesTheToolWithoutLeakingToolContentToSse() throws Exception {
        List<String> deltas = new ArrayList<>();
        AiService.ChatResult result = ai.chatStream(body("复习计划怎么安排"), (type, text) -> {
            deltas.add(type + ":" + text);
            return true;
        });

        assertEquals("COMPLETED", result.status());
        assertEquals(FINAL, result.text());
        assertEquals(2, server.bodies().size(), "SSE 同样要完成工具续写");
        assertTrue(server.bodies().get(1).contains(FACT));
        assertTokenBudgets(server.bodies());
        assertFalse(deltas.toString().contains(MemorySearchTool.NAME), "工具调用不能作为正文发给前端");
        assertFalse(deltas.toString().contains("\"facts\""), "工具结果不能作为正文发给前端");
        assertFalse(dbContains("\"tool_call_id\""), "工具消息不能落进持久化转录");
    }

    private Map<String, Object> body(String userText) {
        return Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "memoryOptions", Map.of("use", true),
                "input", Map.of("userText", userText));
    }

    private static void assertTokenBudgets(List<String> requests) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String request : requests) {
            assertTrue(mapper.readTree(request).path("max_tokens").asInt() <= 1365,
                    "三次请求最坏情况合计不能超过 4095 个输出 token");
        }
    }

    private MemoryApiResult add(String kind, String scopeType, long scopeId, String predicate, String family,
                                String value) {
        MemoryApiResult result = items.add(new MemoryItemService.AddInput(kind, scopeType, scopeId, predicate,
                family, value, null, false, op("add")));
        assertEquals(201, result.status(), value + " 没有落成: " + result.body());
        return result;
    }

    private void unlockVault() {
        MemoryApiResult created = stack.vault().create("password", PASSWORD, op("vault"));
        assertEquals(201, created.status(), String.valueOf(created.body()));
        assertEquals(200, stack.vault().unlock(PASSWORD, List.of(new MemoryVaultService.SessionUnlock(sessionId,
                PASSWORD))).status(), String.valueOf(stack.vault().status().body()));
    }

    private void enableUseConsent() throws Exception {
        long revision = stack.policy().current().revision();
        assertEquals(200, stack.policy().update(revision, true, false, false, false, null).status());
        MemoryPolicyService.Target target = stack.policy().targetOf(MemoryPolicyService.PURPOSE_USE);
        assertEquals(200, stack.policy().grantConsent(MemoryPolicyService.PURPOSE_USE, target.fingerprint(),
                MemoryPolicyService.POLICY_VERSION).status(), target.toString());
    }

    private void indexReady() throws Exception {
        var envelope = wiring.memory().readEnvelope().orElseThrow();
        stack.indexes().buildNow(envelope.vaultId(), envelope.epoch(), envelope.revision());
    }

    private String op(String action) {
        return "memory-tool-chat-" + action + '-' + (++sequence);
    }

    /** 扫库文件与 WAL：工具消息一旦被当成普通消息落盘，这里立刻暴露。 */
    private boolean dbContains(String text) throws Exception {
        StringBuilder raw = new StringBuilder();
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path file = appRoot.resolve("data/study.db" + suffix);
            if (Files.exists(file)) {
                raw.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return raw.toString().contains(
                new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }

    /**
     * 回环夹具：第一次请求回工具调用，第二次（带 role=tool）回最终正文；按请求体里的 stream 开关
     * 分别回 JSON 或 SSE，不落盘、不外呼。
     */
    private static final class ToolFixtureServer implements Closeable {
        private static final String TOOL_CALL = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"search_memories\",\"arguments\":\"{\\\"query\\\":\\\"复习计划\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        private static final String FINAL_JSON = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + FINAL + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}";
        private static final String TOOL_SSE = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search_memories\","
                + "\"arguments\":\"{\\\"query\\\":\\\"复习计划\\\"}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
        private static final String FINAL_SSE = "data: {\"choices\":[{\"delta\":{\"content\":\"根据记忆，\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"先看内存管理。\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";

        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

        ToolFixtureServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        List<String> bodies() {
            return List.copyOf(bodies);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            bodies.add(body);
            boolean continuation = body.contains("\"role\":\"tool\"");
            boolean stream = body.contains("\"stream\":true");
            if (stream) {
                byte[] payload = (continuation ? FINAL_SSE : TOOL_SSE).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } else {
                byte[] payload = (continuation ? FINAL_JSON : TOOL_CALL).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
