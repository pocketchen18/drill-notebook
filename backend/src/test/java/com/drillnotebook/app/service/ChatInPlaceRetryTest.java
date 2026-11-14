package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 原地重试同时核对外发上下文、历史位置、加密附件、幂等回执和真实用量。 */
class ChatInPlaceRetryTest {
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack contexts;
    private AiService ai;
    private long sessionId;
    private HttpServer upstream;
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("in-place-retry-");
        var source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        contexts = ChatContextWiring.wire(source, jdbc, wiring);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(), json,
                null, wiring.turnService(), wiring.cipher(), contexts.contexts());
        sessionId = wiring.sessions().create("重试验收", null);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message",
                    Map.of("role", "assistant", "content", "reply#" + requests.size()), "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 7, "completion_tokens", 2)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        upstream.start();
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint",
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "model", "retry-test",
                "apiKey", "local-test-only", "streaming", false));
    }

    @AfterEach void cleanup() throws Exception {
        if (upstream != null) upstream.stop(0);
        TestWorkspace.deleteRecursively(root);
    }

    private Map<String, Object> request(String text) {
        return new LinkedHashMap<>(Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "input", Map.of("userText", text)));
    }
    private Map<String, Object> retry(Map<String, Object> original, String previous) {
        var result = new LinkedHashMap<>(original);
        result.put("requestId", UuidV7.generate(System.currentTimeMillis())); result.put("retryOf", previous);
        return result;
    }
    private ChatTurnInputService inputReader() {
        var vault = mock(MemoryVaultService.class);
        when(vault.sessionPassword(sessionId)).thenReturn(Optional.empty());
        return new ChatTurnInputService(wiring.turns(), wiring.cipher(),
                new ChatAttachmentReader(wiring.turns(), wiring.cipher(), wiring.attachmentCipher(), json), vault, json);
    }
    private List<Map<String, Object>> messageIdentities() {
        return jdbc.queryForList("SELECT id, role, turn_id, created_at FROM ai_chat_message ORDER BY id");
    }

    @Test void olderAnswerIsReplacedInPlaceWithoutSendingLaterTurnsAsContext() throws Exception {
        ai.chat(request("前一轮已确认的条件"));
        var original = request("本轮唯一的提问"); ai.chat(original);
        var turn = wiring.turns().findByRequestId((String) original.get("requestId")).orElseThrow();
        ai.chat(request("后续轮次的独立问题"));
        var identities = messageIdentities();

        var retried = retry(original, turn.requestId());
        assertEquals("reply#4", ai.chat(retried).get("reply"));
        var current = wiring.turns().findByRequestId((String) retried.get("requestId")).orElseThrow();
        assertEquals(turn.id(), current.id()); assertEquals(turn.userMessageId(), current.userMessageId());
        assertEquals(turn.assistantMessageId(), current.assistantMessageId());
        assertEquals(turn.startedAt(), current.startedAt()); assertEquals(identities, messageIdentities());
        String sent = requests.get(3).path("messages").toString();
        assertTrue(sent.contains("前一轮已确认的条件"));
        assertEquals(1, sent.split("本轮唯一的提问", -1).length - 1);
        assertFalse(sent.contains("后续轮次的独立问题")); assertFalse(sent.contains("reply#2"));
        assertEquals(3L, contexts.history().navigation(sessionId, 2).get("totalRounds"));
        assertEquals("reply#4", contexts.history().navigation(sessionId, 2).get("answer"));
        assertEquals("reply#3", contexts.history().navigation(sessionId, 3).get("answer"));
        assertTrue(wiring.ledger().reconcile().consistent());
    }

    @Test void repeatedRetriesKeepAttachmentsReadableAndAccumulateUsageWithoutDuplicateDispatches() throws Exception {
        var original = request("保留原附件");
        original.put("input", Map.of("userText", "保留原附件", "attachments", List.of(Map.of(
                "clientId", "first-client", "kind", "text", "name", "原文.md", "mimeType", "text/markdown", "text", "唯一附件全文"))));
        ai.chat(original); var identities = messageIdentities();
        String firstId = (String) original.get("requestId");
        var second = retry(original, firstId); ai.chat(second);
        String secondId = (String) second.get("requestId");
        var third = retry(original, secondId); ai.chat(third);
        String currentId = (String) third.get("requestId");
        assertEquals(identities, messageIdentities());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_attachment", Integer.class));
        var input = (Map<?, ?>) inputReader().input(currentId).get("input");
        assertEquals("保留原附件", input.get("userText"));
        var attachments = (List<?>) input.get("attachments");
        assertEquals("唯一附件全文", ((Map<?, ?>) attachments.get(0)).get("text"));
        assertEquals("原文.md", ((Map<?, ?>) attachments.get(0)).get("name"));
        assertEquals(true, ai.chat(third).get("replayed")); assertEquals(3, requests.size());
        for (var stale : List.of(original, second)) {
            var rejection = assertThrows(ChatTurnRejectException.class, () -> ai.chat(stale));
            assertEquals("TURN_SUPERSEDED", rejection.code());
            assertEquals(currentId, rejection.details().get("replacementRequestId"));
        }
        assertEquals("TURN_SUPERSEDED", assertThrows(ChatTurnRejectException.class, () -> inputReader().input(firstId)).code());
        var facts = new ChatHistoryRepository(jdbc.getDataSource()).turnFacts(sessionId, Set.of(currentId));
        var usage = (Map<?, ?>) facts.get(currentId).get("usage");
        assertEquals(3L, ((Number) usage.get("requests")).longValue());
        assertEquals(21L, ((Number) usage.get("inputTokens")).longValue());
        assertEquals(6L, ((Number) usage.get("outputTokens")).longValue());
        assertTrue(wiring.ledger().reconcile().consistent());
    }

    @Test void capacityRefusalAndChangedInputPreserveTheOriginalAnswerAndLedger() throws Exception {
        var original = request("不能丢掉的原回答"); ai.chat(original);
        String firstId = (String) original.get("requestId");
        var turn = wiring.turns().findByRequestId(firstId).orElseThrow();
        var changed = retry(original, firstId); changed.put("input", Map.of("userText", "不同的问题"));
        assertEquals("RETRY_INPUT_CHANGED", assertThrows(ChatTurnRejectException.class, () -> ai.chat(changed)).code());
        jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = history_bytes WHERE id = 1");
        var before = wiring.ledger().read(); var identities = messageIdentities();
        assertEquals("HISTORY_STORAGE_FULL", assertThrows(ChatTurnRejectException.class,
                () -> ai.chat(retry(original, firstId))).code());
        assertEquals(before, wiring.ledger().read()); assertEquals(identities, messageIdentities());
        assertEquals(turn, wiring.turns().findByRequestId(firstId).orElseThrow());
        assertEquals("reply#1", contexts.history().navigation(sessionId, 1).get("answer"));
        assertEquals(1, requests.size()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_retry_receipt", Integer.class));
        assertTrue(wiring.ledger().reconcile().consistent());
    }

    @Test void aFailedTurnWithoutAnAnswerCanRetryAtTheEndButCannotBeMovedPastLaterQuestions() throws Exception {
        var original = request("尚无输出的提问"); String firstId = (String) original.get("requestId");
        var started = wiring.turnService().begin(new ChatTurnService.BeginInput(firstId, 2, sessionId,
                "尚无输出的提问", "", List.of(), Map.of()));
        wiring.turnService().abandon(started.lease(), "upstream_failed");
        assertNull(wiring.turns().findByRequestId(firstId).orElseThrow().assistantMessageId());
        var replacement = retry(original, firstId);
        assertEquals("reply#1", ai.chat(replacement).get("reply"));
        assertEquals(1L, contexts.history().navigation(sessionId, 1).get("totalRounds"));

        var missing = request("第二次尚无输出"); String missingId = (String) missing.get("requestId");
        var failed = wiring.turnService().begin(new ChatTurnService.BeginInput(missingId, 2, sessionId,
                "第二次尚无输出", "", List.of(), Map.of()));
        wiring.turnService().abandon(failed.lease(), "upstream_failed");
        ai.chat(request("之后的提问"));
        var identities = messageIdentities(); var before = wiring.ledger().read();
        assertEquals("RETRY_TARGET_INCOMPLETE", assertThrows(ChatTurnRejectException.class,
                () -> ai.chat(retry(missing, missingId))).code());
        assertEquals(identities, messageIdentities()); assertEquals(before, wiring.ledger().read());
        assertEquals("reply#2", contexts.history().navigation(sessionId, 3).get("answer"));
    }
}
