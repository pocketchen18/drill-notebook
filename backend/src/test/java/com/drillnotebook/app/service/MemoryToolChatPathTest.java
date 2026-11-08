package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.drillnotebook.app.repository.MemoryRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        ai.setMemoryTools(List.of(stack.memorySearchTool(), stack.memoryListTool(),
                stack.memoryCreateTool(), stack.memoryUpdateTool()));
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE ai_chat_session SET memory_learn_enabled = 1 WHERE id = ?", sessionId);
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "考试安排", "goal", FACT);
        indexReady();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (server != null) server.close();
        if (stack != null) stack.indexes().shutdown();
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

    @Test
    void listMemoryToolPathReturnsOnlyTitles() throws Exception {
        ToolFixtureServer listServer = new ToolFixtureServer("list_memories", "{}");
        try {
            ai.saveConfig(Map.of("provider", "custom", "endpoint", listServer.baseUrl(), "model", "stub-model",
                    "apiKey", "stub-key", "tools", true));
            enableUseConsent();
            Map<String, Object> response = ai.chat(body("我记了哪些事"));
            assertEquals("COMPLETED", response.get("status"));
            List<String> bodies = listServer.bodies();
            assertEquals(2, bodies.size(), "工具调用后必须再发一次模型请求");
            assertTrue(bodies.get(0).contains(MemorySearchTool.NAME), "首请求必须带 search 工具定义");
            assertTrue(bodies.get(0).contains(MemoryListTool.NAME), "首请求必须带 list 工具定义");
            String listContent = toolMessageContent(bodies.get(1));
            assertTrue(listContent.contains(FACT), "列表结果只包含标题");
            assertFalse(listContent.contains("predicate"), "列表结果不能带内部谓词");

            List<String> deltas = new ArrayList<>();
            AiService.ChatResult streamed = ai.chatStream(body("我记了哪些事"), (type, text) -> {
                deltas.add(type + ":" + text);
                return true;
            });
            assertEquals("COMPLETED", streamed.status());
            assertEquals(4, listServer.bodies().size(), "SSE 同样要完成工具续写");
            assertTrue(listServer.bodies().get(3).contains(FACT));
            assertFalse(deltas.toString().contains(MemoryListTool.NAME), "工具调用不能作为正文发给前端");
            assertFalse(deltas.toString().contains("\"items\""), "工具结果不能作为正文发给前端");
            assertFalse(dbContains(MemoryListTool.NAME), "工具名不能落进持久化转录");
            assertFalse(dbContains("\"tool_call_id\""), "工具消息不能落进持久化转录");
        } finally {
            listServer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applyingAnotherModelKeepsMemoriesAvailableWithoutOpeningSettings(boolean changeSupplier) throws Exception {
        try (var listServer = new ToolFixtureServer("list_memories", "{\"scope\":\"ALL\",\"limit\":20}")) {
            if (!changeSupplier) {
                ai.saveConfig(Map.of("provider", "custom", "endpoint", listServer.baseUrl(), "model", "stub-model",
                        "apiKey", "stub-key", "tools", true));
                enableUseConsent();
            }
            var envelope = wiring.memory().readEnvelope().orElseThrow();
            var index = stack.indexes().ready(envelope.vaultId(), envelope.epoch(), envelope.revision()).orElseThrow();
            // 复现实际操作：自动记忆唯一来源会话被删，然后切模型立即查询。
            var fact = wiring.memory().findPage(envelope.vaultId(), "ACTIVE", null, null, null, null, 10).get(0);
            jdbc.update("UPDATE ai_memory_item SET origin = 'AUTO', manual_protected = 0 WHERE row_id = ?", fact.rowId());
            long sourceSession = wiring.sessions().create("已删除的来源会话", "stub-model");
            wiring.memory().attachEvidence(new MemoryRepository.EvidenceDraft("source-evidence", envelope.vaultId(),
                    sourceSession, null, null, "u0", "source-hmac", "fixture-quote", "{}", fact.recordedAt()), fact.rowId());
            wiring.sessions().delete(sourceSession);
            assertTrue(wiring.memory().findCurrent(envelope.vaultId(), fact.memoryId()).isPresent());
            assertEquals(List.of(), wiring.memory().evidenceIdsForItem(fact.rowId()));
            var configs = new AiConfigRepository(jdbc);
            var connections = new AiConnectionsService(configs, new ApiKeyEncryptor(), new ObjectMapper());
            connections.view();
            var state = configs.connections().read();
            String providerId = state.owner(state.chatModelId()).id();
            if (changeSupplier) {
                connections.saveProvider(null, Map.of("revision", state.revision(), "name", "另一供应商",
                        "endpoint", listServer.baseUrl(), "apiFormat", "chat_completions", "authMode", "none"));
                state = configs.connections().read();
                providerId = state.providers().get(state.providers().size() - 1).id();
            }
            connections.saveModels(providerId, null, Map.of("revision", state.revision(), "modelId", "new-model",
                    "tools", true, "streaming", true));
            state = configs.connections().read();
            var models = state.provider(providerId).models();
            connections.bind(Map.of("revision", state.revision(), "purpose", "chat", "modelId", models.get(models.size() - 1).id()));

            assertTrue(stack.policy().current().enabled());
            assertTrue(stack.policy().useEligibility().allowed(), "应用成功即能用记忆，不能依赖重新打开设置页");
            assertEquals(envelope, wiring.memory().readEnvelope().orElseThrow(), "切换聊天模型不改变记忆库版本");
            assertSame(index, stack.indexes().ready(envelope.vaultId(), envelope.epoch(), envelope.revision()).orElseThrow(),
                    "切换聊天模型直接复用现有索引");

            assertEquals("COMPLETED", ai.chat(body("看看我现在有哪些记忆")).get("status"));
            assertEquals("COMPLETED", ai.chatStream(body("再看看我现在有哪些记忆"), (type, text) -> true).status());
            assertEquals(4, listServer.bodies().size());
            for (int i : List.of(1, 3)) {
                var result = new ObjectMapper().readTree(toolMessageContent(listServer.bodies().get(i)));
                assertEquals("OK", result.path("status").asText());
                assertEquals("READY", result.path("availability").asText());
                assertEquals(FACT, result.path("items").get(0).path("title").asText());
            }
        }
    }

    @Test
    void createMemoryToolPersistsEvidenceAndSurvivesTheRevisionRefresh() throws Exception {
        ToolFixtureServer createServer = new ToolFixtureServer("create_memory",
                "{\"text\":\"我每周三晚上复习\",\"kind\":\"PREFERENCE\"}");
        try {
            ai.saveConfig(Map.of("provider", "custom", "endpoint", createServer.baseUrl(), "model", "stub-model",
                    "apiKey", "stub-key", "tools", true));
            enableUseConsent();
            Map<String, Object> response = ai.chat(body("记住：我每周三晚上复习"));
            assertEquals("COMPLETED", response.get("status"), String.valueOf(response));
            List<String> bodies = createServer.bodies();
            assertEquals(2, bodies.size(), "写入后仍要完成续写，不能因 vault revision 变化被误判 CONTEXT_CHANGED");
            String toolContent = toolMessageContent(bodies.get(1));
            assertTrue(toolContent.contains("CREATED"), "工具结果只返回定位状态");
            assertFalse(toolContent.contains("我每周三晚上复习"),
                    "写入结果不回显正文，模型只能依据用户原话继续回答");

            Map<String, Object> row = jdbc.queryForMap("SELECT memory_id, origin, scope_type FROM ai_memory_item"
                    + " WHERE status = 'ACTIVE' AND scope_type = 'GLOBAL' AND kind = 'PREFERENCE'");
            assertEquals("AUTO", row.get("origin"));
            List<Map<String, Object>> evidence = jdbc.queryForList(
                    "SELECT e.turn_id, e.message_id, e.source_block_id FROM ai_memory_evidence e"
                            + " JOIN ai_memory_item_evidence ie ON ie.evidence_id = e.id"
                            + " JOIN ai_memory_item i ON i.row_id = ie.item_row_id WHERE i.memory_id = ?",
                    String.valueOf(row.get("memory_id")));
            assertEquals(1, evidence.size());
            assertEquals("user-message", evidence.get(0).get("source_block_id"));
            assertNotNull(evidence.get(0).get("turn_id"));
            assertNotNull(evidence.get(0).get("message_id"));
            assertFalse(dbContains(MemoryCreateTool.NAME), "工具名不能落进持久化转录");
            assertFalse(dbContains("tool_call_id"), "工具消息不能落进持久化转录");
        } finally {
            createServer.close();
        }
    }

    @Test
    void updateMemoryToolPathLocatesByTextAndRefreshesTheVaultSnapshot() throws Exception {
        ToolFixtureServer updateServer = new ToolFixtureServer("update_memory",
                "{\"currentText\":\"" + FACT + "\",\"text\":\"考试改到周五\"}");
        try {
            ai.saveConfig(Map.of("provider", "custom", "endpoint", updateServer.baseUrl(), "model", "stub-model",
                    "apiKey", "stub-key", "tools", true));
            enableUseConsent();
            Map<String, Object> response = ai.chat(body("考试安排改到周五"));
            assertEquals("COMPLETED", response.get("status"), String.valueOf(response));
            List<String> bodies = updateServer.bodies();
            assertEquals(2, bodies.size(), "更新后仍要完成续写，不能因 vault revision 变化被误判 CONTEXT_CHANGED");
            String toolContent = toolMessageContent(bodies.get(1));
            assertTrue(toolContent.contains("UPDATED"), "工具结果只返回定位状态");
            assertTrue(toolContent.contains("\"version\":2"), toolContent);
            assertFalse(toolContent.contains("考试改到周五"), "更新结果不回显正文");

            Map<String, Object> oldRow = jdbc.queryForMap("SELECT status FROM ai_memory_item"
                    + " WHERE value_hmac IS NOT NULL AND status = 'SUPERSEDED'");
            assertEquals("SUPERSEDED", oldRow.get("status"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"
                    + " AND value_hmac IS NOT NULL AND kind = 'GOAL'", Integer.class));
            assertFalse(dbContains(MemoryUpdateTool.NAME), "工具名不能落进持久化转录");
            assertFalse(dbContains("\"tool_call_id\""), "工具消息不能落进持久化转录");
        } finally {
            updateServer.close();
        }
    }

    @Test
    void unknownToolNameIsRejectedWithoutBreakingTheTurn() throws Exception {
        ToolFixtureServer unknownServer = new ToolFixtureServer("unknown_memory_tool", "{}");
        try {
            ai.saveConfig(Map.of("provider", "custom", "endpoint", unknownServer.baseUrl(), "model", "stub-model",
                    "apiKey", "stub-key", "tools", true));
            Map<String, Object> response = ai.chat(body("我记了哪些事"));
            assertEquals("COMPLETED", response.get("status"));
            assertEquals(2, unknownServer.bodies().size());
            String toolContent = toolMessageContent(unknownServer.bodies().get(1));
            assertTrue(toolContent.contains("INVALID_ARGUMENT"));
            assertTrue(toolContent.contains("\"facts\":[]"), "未知工具按默认字段回空");
        } finally {
            unknownServer.close();
        }
    }

    private Map<String, Object> body(String userText) {
        return Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "memoryOptions", Map.of("use", true),
                "input", Map.of("userText", userText));
    }

    private static String toolMessageContent(String requestBody) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (var message : mapper.readTree(requestBody).path("messages")) {
            if ("tool".equals(message.path("role").asText())) {
                return message.path("content").asText("");
            }
        }
        return "";
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
        private static final String FINAL_JSON = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + FINAL + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}";
        private static final String FINAL_SSE = "data: {\"choices\":[{\"delta\":{\"content\":\"根据记忆，\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"先看内存管理。\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";

        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        private final String toolCall;
        private final String toolSse;

        ToolFixtureServer() throws IOException {
            this("search_memories", "{\"query\":\"复习计划\"}");
        }

        ToolFixtureServer(String toolName, String toolArguments) throws IOException {
            toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{"
                    + "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":" + jsonString(toolName)
                    + ",\"arguments\":" + jsonString(toolArguments) + "}}]},"
                    + "\"finish_reason\":\"tool_calls\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
            toolSse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                    + "\"type\":\"function\",\"function\":{\"name\":" + jsonString(toolName)
                    + ",\"arguments\":" + jsonString(toolArguments) + "}}]}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private static String jsonString(String raw) {
            return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
                byte[] payload = (continuation ? FINAL_SSE : toolSse).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } else {
                byte[] payload = (continuation ? FINAL_JSON : toolCall).getBytes(StandardCharsets.UTF_8);
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
