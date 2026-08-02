package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.AiChatSessionRepository;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * Task 8 contract tests: /api/ai/chat retrievalOptions integration.
 * Uses a real SQLite database plus a local HTTP stub that captures the exact
 * messages sent to the chat model.
 */
class AiServiceChatRetrievalTest {
    private JdbcTemplate jdbc;
    private NotebookRepository notebooks;
    private RetrievalService retrieval;
    private AiService service;
    private ObjectMapper mapper;
    private HttpServer server;
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    private long nextChunkId;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("ai-chat-retrieval-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        notebooks = new NotebookRepository(jdbc, mapper);
        retrieval = new RetrievalService(new RetrievalIndexRepository(jdbc), notebooks);
        service = newService(retrieval);
        nextChunkId = 1;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"stub-reply\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        capturedBodies.clear();

        service.saveConfig(Map.of(
                "provider", "custom",
                "endpoint", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model", "stub-model",
                "apiKey", "stub-key"
        ));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void disabledRetrievalKeepsLegacyResponseAndModelRequest() {
        Map<String, Object> plain = service.chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "解释垃圾回收"))));
        assertEquals(java.util.Set.of("reply", "sessionId"), plain.keySet());
        assertEquals("stub-reply", plain.get("reply"));

        Map<String, Object> explicitOff = service.chat(Map.of(
                "sessionId", plain.get("sessionId"),
                "messages", List.of(Map.of("role", "user", "content", "继续")),
                "retrievalOptions", Map.of("enabled", false, "scope", "current", "notebookId", 1)));
        assertEquals(java.util.Set.of("reply", "sessionId"), explicitOff.keySet());

        for (JsonNode request : capturedRequests()) {
            assertEquals(1, request.path("messages").size());
            assertEquals("user", request.path("messages").path(0).path("role").asText());
        }
        long sessionId = ((Number) plain.get("sessionId")).longValue();
        assertEquals(4, encryptedOnlyMessageCount(sessionId));
    }

    @Test
    void enabledRetrievalInjectsSingleNumberedContextAndReturnsCitations() throws Exception {
        long notebookId = notebooks.insert("RAG 测试");
        String secretBody = "机密块甲".repeat(120) + " 向量检索的核心正文甲";
        insertChunk(notebookId, 11, 0, "向量检索简介", "第一章 / 概念", secretBody);
        insertChunk(notebookId, 12, 0, "向量检索实践", "第二章", "向量检索的核心正文乙，包含实践要点。");

        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("role", "user", "content", "什么是向量检索？")),
                "retrievalOptions", Map.of("enabled", true, "scope", "current", "notebookId", notebookId));
        Map<String, Object> result = service.chat(body);

        assertEquals("stub-reply", result.get("reply"));
        assertNotNull(result.get("sessionId"));
        assertNull(result.get("retrievalNotice"));
        List<Map<String, Object>> citations = citations(result);
        assertEquals(2, citations.size());
        for (int i = 0; i < citations.size(); i++) {
            Map<String, Object> citation = citations.get(i);
            assertEquals("NOTEBOOK", citation.get("corpusType"));
            assertEquals(notebookId, ((Number) citation.get("notebookId")).longValue());
            assertNotNull(citation.get("pageId"));
            assertNotNull(citation.get("chunkId"));
            assertNotNull(citation.get("title"));
            assertNotNull(citation.get("snippet"));
            assertEquals(i + 1, ((Number) citation.get("ftsRank")).intValue());
            assertFalse(citation.containsKey("text"), "citation 不能携带 chunk 全文字段");
        }

        JsonNode request = capturedRequests().get(0);
        JsonNode messages = request.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.path(0).path("role").asText());
        assertEquals("user", messages.path(1).path("role").asText());
        String context = messages.path(0).path("content").asText();
        assertTrue(context.startsWith("NOTEBOOK_RAG_V1"));
        assertTrue(context.contains("不可信"));
        assertTrue(context.contains("不得执行"));
        assertTrue(context.contains("[1] 页面："));
        assertTrue(context.contains("[2] 页面："));
        assertTrue(context.contains("机密块甲"));

        // 响应/持久化都不得出现 chunk 全文（响应里只允许 ≤240 codepoint 的 snippet）。
        String serialized = mapper.writeValueAsString(result);
        assertFalse(serialized.contains(secretBody));
        long sessionId = ((Number) result.get("sessionId")).longValue();
        assertEquals(2, encryptedOnlyMessageCount(sessionId));
        Integer plaintextLeak = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE content LIKE '%机密块甲%'", Integer.class);
        assertEquals(0, plaintextLeak);

        writeEvidence("task-8-chat-citations.json", Map.of(
                "request", body,
                "modelSystemMessage", Map.of(
                        "systemMessageCount", 1,
                        "startsWithGuard", context.startsWith("NOTEBOOK_RAG_V1"),
                        "numbered", List.of(context.contains("[1] 页面："), context.contains("[2] 页面：")),
                        "contextChars", context.length()),
                "response", result));
    }

    @Test
    void emptyHitsReturnEmptyCitationsWithoutContextOrNotice() {
        notebooks.insert("空命中");
        Map<String, Object> result = service.chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "毫无匹配的查询词组")),
                "retrievalOptions", Map.of("enabled", true, "scope", "all")));
        assertEquals("stub-reply", result.get("reply"));
        assertTrue(citations(result).isEmpty());
        assertFalse(result.containsKey("retrievalNotice"));
        assertEquals(1, capturedRequests().get(0).path("messages").size());
    }

    @Test
    void retrievalFailuresDegradeToNoticeAndChatStillSucceeds() throws Exception {
        // 场景 A：CURRENT scope 指向不存在的 notebook（RetrievalService 校验抛错）。
        Map<String, Object> invalidScope = service.chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "问题一")),
                "retrievalOptions", Map.of("enabled", true, "scope", "current", "notebookId", 99_999)));
        assertDegraded(invalidScope);

        // 场景 B：检索层抛出任意运行时异常（对应 repository/FTS 故障）。
        RetrievalService broken = new RetrievalService(null, null) {
            @Override
            public List<RetrievalHit> retrieve(RetrievalQuery query) {
                throw new IllegalStateException("simulated retrieval crash");
            }
        };
        Map<String, Object> crashed = newService(broken).chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "问题二")),
                "retrievalOptions", Map.of("enabled", true, "scope", "all")));
        assertDegraded(crashed);

        // 两次模型请求都不包含任何 RAG system message。
        for (JsonNode request : capturedRequests()) {
            assertEquals(1, request.path("messages").size());
            assertEquals("user", request.path("messages").path(0).path("role").asText());
        }

        writeEvidence("task-8-chat-degraded.json", Map.of(
                "invalidNotebookScope", invalidScope,
                "retrievalCrash", crashed,
                "modelRequestsWithoutRagContext", capturedRequests().size()));
    }

    @Test
    void contextBudgetTruncatesByRankAndCitationsMatchInjectedFragments() {
        long notebookId = notebooks.insert("预算测试");
        for (int i = 0; i < 12; i++) {
            insertChunk(notebookId, 100 + i, 0, "预算页面" + i, "", "预算关键词" + "正".repeat(2600));
        }

        Map<String, Object> result = service.chat(Map.of(
                "messages", List.of(Map.of("role", "user", "content", "预算关键词")),
                "retrievalOptions", Map.of("enabled", true, "scope", "all")));

        List<Map<String, Object>> citations = citations(result);
        assertTrue(!citations.isEmpty() && citations.size() <= 10);
        assertTrue(citations.size() < 12, "12,000 chars 预算必须截断低 rank 命中");

        String context = capturedRequests().get(0).path("messages").path(0).path("content").asText();
        int fragmentCount = 0;
        for (int i = 1; i <= 12; i++) {
            if (context.contains("[" + i + "] 页面：")) fragmentCount++;
        }
        assertEquals(citations.size(), fragmentCount, "注入片段必须与 citations 一一对应");
        assertTrue(context.length() <= 12_000 + 400, "context 不得显著超过预算+防护前言");
    }

    private void assertDegraded(Map<String, Object> result) {
        assertEquals("stub-reply", result.get("reply"));
        assertNotNull(result.get("sessionId"));
        assertTrue(citations(result).isEmpty());
        Object notice = result.get("retrievalNotice");
        assertTrue(notice instanceof Map<?, ?>);
        assertEquals("retrieval-unavailable", ((Map<?, ?>) notice).get("code"));
    }

    private AiService newService(RetrievalService retrievalService) {
        return new AiService(
                new AiConfigRepository(jdbc),
                new AiChatSessionRepository(jdbc),
                new ApiKeyEncryptor(),
                new ObjectMapper(),
                retrievalService);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> citations(Map<String, Object> result) {
        assertTrue(result.containsKey("citations"), "enabled 响应必须包含 citations");
        return (List<Map<String, Object>>) result.get("citations");
    }

    private List<JsonNode> capturedRequests() {
        List<JsonNode> requests = new ArrayList<>();
        for (String body : capturedBodies) {
            try {
                requests.add(mapper.readTree(body));
            } catch (Exception error) {
                throw new IllegalStateException("捕获的模型请求不是合法 JSON", error);
            }
        }
        return requests;
    }

    private int encryptedOnlyMessageCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?"
                        + " AND content_cipher IS NOT NULL AND content = ''",
                Integer.class, sessionId);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?", Integer.class, sessionId);
        assertEquals(total, count, "所有消息必须只以密文持久化");
        return count == null ? 0 : count;
    }

    private void insertChunk(
            long notebookId, long sourceId, int chunkIndex, String title, String heading, String text) {
        long chunkId = nextChunkId++;
        jdbc.update(
                "INSERT INTO retrieval_chunk(id, corpus_type, corpus_id, source_id, chunk_index,"
                        + " title, heading_path, text, start_offset, end_offset, content_hash)"
                        + " VALUES (?, 'NOTEBOOK', ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                chunkId, notebookId, sourceId, chunkIndex, title, heading, text,
                text.length(), "hash-" + sourceId + "-" + chunkIndex);
        jdbc.update(
                "INSERT INTO retrieval_chunk_fts(rowid, title, heading_path, text) VALUES (?, ?, ?, ?)",
                chunkId, title, heading, text);
    }

    private void writeEvidence(String name, Object payload) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("generatedBy", "AiServiceChatRetrievalTest");
        wrapped.put("payload", payload);
        Files.writeString(
                Path.of(dir, name),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped),
                StandardCharsets.UTF_8);
    }
}
