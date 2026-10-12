package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 只读历史分页与上下文检查器（contracts §3、§4.5，执行方案 §7.4）。
 *
 * <p>三条底线：查一次不能写出任何东西；展示资格不等于注入资格；解不开的内容只给占位，
 * 绝不把密文或猜测的正文端出去。</p>
 */
class ChatHistoryReadTest {
    private static final Map<String, Object> MOCK_CONFIG = Map.of(
            "provider", "mock", "endpoint", "mock://local", "model", "local-demo", "apiKey", "demo-key");

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p4-read-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), stack.contexts());
        ai.saveConfig(MOCK_CONFIG);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void newestPageIsOldestFirstAndCursorsNeverOverlap() throws Exception {
        chat("第一问");
        chat("第二问");
        chat("第三问");

        Map<String, Object> newest = stack.history().page(sessionId, null, null, 2);
        assertEquals(2, rows(newest).size(), "每页只给 limit 条，多取的那条只用来判 hasMore");
        assertTrue((Boolean) newest.get("hasMore"));
        assertEquals(2, newest.get("limit"));
        assertEquals(true, messageIds(newest).get(0) < messageIds(newest).get(1), "页内按时间升序返回");
        assertEquals("第三问", content(newest, 0), "最新一页必须含刚刚那一问");
        assertTrue(content(newest, 1).startsWith("本地演示"), "页尾是它的回复，不能被哨兵挤掉");

        long oldestInPage = messageIds(newest).get(0);
        Map<String, Object> previous = stack.history().page(sessionId, oldestInPage, null, 2);
        assertFalse(containsAny(messageIds(previous), messageIds(newest)), "游标必须严格排他，不能重复给同一条");
        assertEquals("第二问", content(previous, 0), "上一页正好接上更早的一问");
        Map<String, Object> forward = stack.history().page(sessionId, null, oldestInPage, 10);
        assertEquals(List.of(messageIds(newest).get(1)), messageIds(forward), "往后翻只拿到更新的尾巴");

        int attemptsBeforePaging = count("SELECT COUNT(*) FROM ai_usage_attempt");
        assertEquals(50, stack.history().page(sessionId, null, null, 500).get("limit"), "每页最多 50 条（§7.4）");
        stack.history().page(sessionId, oldestInPage, null, 2);
        assertEquals(attemptsBeforePaging, count("SELECT COUNT(*) FROM ai_usage_attempt"), "翻页不外发，也不计量");
    }

    @Test
    void bothCursorsAtOnceIsAContractViolation() throws Exception {
        chat("只要一个游标");
        long messageId = messageIds(stack.history().page(sessionId, null, null, 1)).get(0);

        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class,
                () -> stack.history().page(sessionId, messageId, messageId, 10));
        assertEquals(400, error.status());
        assertEquals("HISTORY_CURSOR_CONFLICT", error.code());
    }

    @Test
    void failedAndTombstonedTurnsShowTheirRealState() throws Exception {
        chat("正常的一问");
        // 失败轮次：正文照样能看见，但必须带着真实终态，不能被伪装成完成
        ChatTurnService.Lease broken = wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "失败的一问", "", List.of(),
                Map.of())).lease();
        wiring.turnService().abandon(broken, "upstream_failed");

        Map<String, Object> page = stack.history().page(sessionId, null, null, 10);
        Map<String, Object> failed = rows(page).get(rows(page).size() - 1);
        assertEquals("失败的一问", failed.get("content"));
        assertEquals("FAILED", failed.get("turnStatus"));
        assertEquals(true, failed.get("readable"));

        // 用户明确清理该轮原文：走生产删除路径（删消息、清两组密文元数据、立墓碑）
        wiring.turns().clearContent(broken.requestId(), "2026-10-12T00:00:00.000Z");
        Map<String, Object> tombstone = stack.history().page(sessionId, null, null, 10);
        assertNotEqualsPlain(tombstone, "失败的一问");
        assertFalse(rows(tombstone).isEmpty());

        ChatTurnRejectException expired = assertThrows(ChatTurnRejectException.class,
                () -> stack.inspector().audit(broken.requestId()));
        assertEquals(410, expired.status());
        assertEquals("RESULT_EXPIRED", expired.code(), "墓碑轮次的上下文记录不再提供（§4.5）");
    }

    @Test
    void lockedHistoryIsMarkedUnreadableInsteadOfLeakingCiphertext() throws Exception {
        String password = "主密码-用于历史";
        assertEquals(201, stack.vault().create("password", password, "op-history-vault").status());
        ai.chat(Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "masterPassword", password,
                "input", Map.of("userText", "需要主密码才能读的问题")));

        Map<String, Object> locked = stack.history().page(sessionId, null, null, 10);
        Map<String, Object> user = rows(locked).get(rows(locked).size() - 1);
        assertEquals(false, user.get("readable"), "没解锁就是读不到，不能假装是空消息");
        assertFalse(String.valueOf(user.get("content")).contains("需要主密码才能读的问题"));
        assertFalse(serialized(locked).contains("需要主密码才能读的问题"), "锁定状态下也不得漏明文");

        stack.vault().unlock(password, List.of(new com.drillnotebook.app.service.memory.MemoryVaultService
                .SessionUnlock(sessionId, password)));
        Map<String, Object> unlocked = stack.history().page(sessionId, null, null, 10);
        assertEquals("需要主密码才能读的问题", content(unlocked, 0), "解锁后同一条消息才给得出正文");
    }

    @Test
    void inspectorReadsOnlyTheSavedAuditAndNeverStartsWork() throws Exception {
        Map<String, Object> v2 = chat("检查器要读的这轮");
        String requestId = (String) v2.get("requestId");
        String planId = String.valueOf(((Map<?, ?>) v2.get("contextAudit")).get("planId"));
        int turns = count("SELECT COUNT(*) FROM ai_chat_turn");
        int attempts = count("SELECT COUNT(*) FROM ai_usage_attempt");

        Map<String, Object> audit = stack.inspector().audit(requestId);
        assertEquals(true, audit.get("available"));
        assertEquals(planId, audit.get("planId"));
        assertEquals("sent", audit.get("dispatchState"));
        Map<?, ?> body = (Map<?, ?>) audit.get("audit");
        assertEquals("context-estimate-v1", body.get("estimatorVersion"));
        assertNotNull(body.get("regions"));
        assertEquals(turns, count("SELECT COUNT(*) FROM ai_chat_turn"), "查一次审计不得新建轮次");
        assertEquals(attempts, count("SELECT COUNT(*) FROM ai_usage_attempt"), "查一次审计不得触发外发或计量");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal"));

        ai.chat(Map.of("sessionId", sessionId, "messages",
                List.of(Map.of("role", "user", "content", "旧客户端的一问"))));
        String v1RequestId = jdbc.queryForObject("SELECT request_id FROM ai_chat_turn ORDER BY id DESC LIMIT 1",
                String.class);
        Map<String, Object> legacy = stack.inspector().audit(v1RequestId);
        assertEquals(false, legacy.get("available"), "v1 没有统一计划，检查器要直说（§7.1）");
        assertEquals("LEGACY_TURN_WITHOUT_CONTEXT_PLAN", legacy.get("reason"));

        // 没有这个轮次就是 404：检查器不能凭空"造一轮"来回答查询
        ChatTurnRejectException missing = assertThrows(ChatTurnRejectException.class,
                () -> stack.inspector().audit(UuidV7.generate(System.currentTimeMillis())));
        assertEquals(404, missing.status());
        assertEquals("TURN_NOT_FOUND", missing.code());
    }

    @Test
    void previewOnlyShowsSourcesThatWereActuallySent() throws Exception {
        chat("被引用为历史的一问");
        Map<String, Object> v2 = chat("带页面与检索的一问");
        String requestId = (String) v2.get("requestId");
        // 响应只给摘要，来源清单要从检查器读回来（§4.2：明细走 GET /turns/{id}/context）
        String turnKey = keyOf(selectedOf(requestId), "TURN");
        String madeUp = "sk" + "0".repeat(16);
        int turns = count("SELECT COUNT(*) FROM ai_chat_turn");
        int messages = count("SELECT COUNT(*) FROM ai_chat_message");

        List<Map<String, Object>> rows = stack.inspector().preview(requestId, List.of(turnKey));
        assertEquals(1, rows.size());
        assertEquals("TURN", rows.get(0).get("kind"));
        assertEquals(true, rows.get(0).get("available"));
        assertTrue(String.valueOf(rows.get(0).get("text")).contains("被引用为历史的一问"));
        assertEquals(turns, count("SELECT COUNT(*) FROM ai_chat_turn"), "预览同样是纯读，不新建轮次");
        assertEquals(messages, count("SELECT COUNT(*) FROM ai_chat_message"), "预览不写消息");

        ChatTurnRejectException unknown = assertThrows(ChatTurnRejectException.class,
                () -> stack.inspector().preview(requestId, List.of(madeUp)));
        assertEquals(400, unknown.status());
        assertEquals("CONTEXT_SOURCE_UNKNOWN", unknown.code(), "不能借检查器读任意内容（§4.5）");

        ChatTurnRejectException tooMany = assertThrows(ChatTurnRejectException.class,
                () -> stack.inspector().preview(requestId, List.of(turnKey, turnKey, turnKey, turnKey, turnKey,
                        turnKey)));
        assertEquals(400, tooMany.status());
        assertEquals("CONTEXT_PREVIEW_TOO_MANY", tooMany.code());
    }

    @Test
    void previewOfADeletedSourceFallsBackToAPlaceholder() throws Exception {
        ChatTurnService.Lease older = chatLease("先落一轮历史");
        finish(older, "旧回答");
        Map<String, Object> v2 = chat("后面这一问");
        String requestId = (String) v2.get("requestId");
        String turnKey = keyOf(selectedOf(requestId), "TURN");

        jdbc.update("DELETE FROM ai_chat_message WHERE turn_id = ?", older.turnId());
        jdbc.update("DELETE FROM ai_chat_turn WHERE id = ?", older.turnId());

        Map<String, Object> row = stack.inspector().preview(requestId, List.of(turnKey)).get(0);
        assertEquals(false, row.get("available"), "来源已经没了就只能给占位，不能从缓存里恢复正文");
        assertEquals("SOURCE_DELETED", row.get("reason"));
    }

    // ---------- 夹具 ----------

    private Map<String, Object> chat(String userText) {
        return ai.chat(Map.of("protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", userText)));
    }

    private ChatTurnService.Lease chatLease(String userText) {
        return wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, userText, "", List.of(),
                Map.of())).lease();
    }

    private void finish(ChatTurnService.Lease lease, String reply) {
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of(reply, null, "stop"), null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> messageIds(Map<String, Object> page) {
        return rows(page).stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    private static String content(Map<String, Object> page, int index) {
        return String.valueOf(rows(page).get(index).get("content"));
    }

    private static boolean containsAny(List<Long> candidate, List<Long> reference) {
        return candidate.stream().anyMatch(reference::contains);
    }

    private List<?> selectedOf(String requestId) throws Exception {
        Map<?, ?> audit = (Map<?, ?>) stack.inspector().audit(requestId).get("audit");
        return (List<?>) audit.get("selected");
    }

    private static String keyOf(List<?> selected, String kind) {
        for (Object row : selected) {
            Map<?, ?> map = (Map<?, ?>) row;
            if (kind.equals(map.get("kind"))) return String.valueOf(map.get("sourceKey"));
        }
        throw new AssertionError("本轮没有 " + kind + " 来源：" + selected);
    }

    private static String serialized(Map<String, Object> page) {
        return String.valueOf(page);
    }

    private static void assertNotEqualsPlain(Map<String, Object> page, String plain) {
        assertFalse(serialized(page).contains(plain), "清理过的内容不能继续在历史分页里给出正文：" + plain);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
