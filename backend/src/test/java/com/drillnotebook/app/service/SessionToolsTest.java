package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.SessionLookupRepository;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionToolsTest {
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private SessionLookupRepository repository;
    private SessionLookupService service;
    private SessionListTool list;
    private SessionReadTool read;
    private MemoryVaultService vault;
    private ChatContextPlan plan;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("session-tools-");
        var dataSource = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        repository = new SessionLookupRepository(dataSource);
        vault = mock(MemoryVaultService.class);
        when(vault.sessionPassword(anyLong())).thenReturn(Optional.empty());
        service = new SessionLookupService(repository, wiring.cipher(), vault);
        list = new SessionListTool(service);
        read = new SessionReadTool(service);
        long caller = wiring.sessions().create("当前会话", "test");
        plan = new ChatContextPlan("plan", caller, 1, 0, null, null, null, 0,
                List.of(), List.of(), List.of(), null);
    }

    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }

    @Test void listUsesStableSeekPaginationLiteralFilteringAndDoesNotReadBodies() throws Exception {
        long first = wiring.sessions().create("数学 100% _", "test");
        long second = wiring.sessions().create("数学复习", "test");
        long archived = wiring.sessions().create("数学归档", "test");
        wiring.sessions().update(archived, null, true, null);
        var page = list.execute(plan, "{\"query\":\"数学\",\"limit\":1}", false, "");
        assertEquals(second, rows(page, "items").get(0).get("sessionId"));
        assertEquals(true, page.get("hasMore"));
        wiring.sessions().create("数学新到会话", "test");
        var next = list.execute(plan, json.writeValueAsString(Map.of("query", "数学", "limit", 1,
                "beforeSessionId", page.get("nextBeforeSessionId"))), false, "");
        assertEquals(first, rows(next, "items").get(0).get("sessionId"));
        assertEquals(false, next.get("hasMore"));
        assertEquals(1, rows(list.execute(plan, "{\"query\":\"% _\"}", false, ""), "items").size());
        var all = list.execute(plan, "{\"includeArchived\":true}", false, "");
        assertEquals(4, rows(all, "items").size());
        assertFalse(all.toString().contains("当前会话"));
        verifyNoInteractions(vault);
    }

    @Test void linksSurviveRenameAndResolveNotebookAndArchivedSessionsWithoutMemoryConsent() throws Exception {
        long target = wiring.sessions().create("笔记本专题", "test");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (42, '本册')");
        jdbc.update("UPDATE ai_chat_session SET memory_scope_type = 'NOTEBOOK', memory_notebook_id = 42 WHERE id = ?", target);
        String link = link(target);
        legacy(target, "user", "旧会话正文", "");
        wiring.sessions().update(target, "改名后的标题", true, null);
        Map<String, Object> result = read(target, link, null, 10);
        assertEquals("OK", result.get("status"));
        assertEquals("旧会话正文", rows(result, "messages").get(0).get("content"));
        assertEquals("改名后的标题", ((Map<?, ?>) result.get("session")).get("title"));
        assertEquals("NOTEBOOK", ((Map<?, ?>) result.get("session")).get("scope"));
        assertEquals(link, ((Map<?, ?>) result.get("session")).get("sessionLink"));
        assertEquals("OK", read(target, String.valueOf(target), null, 10).get("status"));
        assertEquals("OK", read(target, link.substring(SessionLookupService.LINK_PREFIX.length()), null, 10).get("status"));
        assertFalse(read.requiresMemory());
        assertFalse(list.requiresMemory());
    }

    @Test void titleAmbiguityNeverReadsAnArbitraryCandidateAndDeletionNeverFallsBack() throws Exception {
        long a = wiring.sessions().create("微积分专题", "test");
        long b = wiring.sessions().create("微积分专题", "test");
        legacy(a, "assistant", "不应泄漏的正文", "");
        var ambiguous = read.execute(plan, "{\"session\":\"微积分\"}", false, "");
        assertEquals("AMBIGUOUS", ambiguous.get("status"));
        assertEquals(2, rows(ambiguous, "candidates").size());
        assertTrue(rows(ambiguous, "messages").isEmpty());
        assertFalse(ambiguous.toString().contains("不应泄漏"));
        String link = link(a);
        wiring.sessions().delete(a);
        assertEquals("NOT_FOUND", read(a, link, null, 10).get("status"));
        assertEquals("NO_MATCH", read.execute(plan, "{\"session\":\"不存在的主题\"}", false, "").get("status"));
        assertEquals("OK", read(b, link(b), null, 10).get("status"));
    }

    @Test void longUnicodeMessagesRoundTripAcrossPagesWithoutLossOrDuplication() throws Exception {
        long target = wiring.sessions().create("长历史", "test");
        String text = "甲😀\\\"\n".repeat(3100);
        legacy(target, "assistant", text, "");
        legacy(target, "user", "接下来", "");
        StringBuilder joined = new StringBuilder();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> result = read(target, link(target), cursor, 1);
            assertEquals("OK", result.get("status"));
            assertTrue(json.writeValueAsBytes(result).length < 64 * 1024);
            for (var message : rows(result, "messages")) joined.append(message.get("content"));
            cursor = (String) result.get("nextCursor");
            assertEquals(cursor != null, result.get("hasMore"));
            assertTrue(++pages < 10);
        } while (cursor != null);
        assertEquals(text + "接下来", joined.toString());
        assertTrue(pages > 2);
    }

    @Test void messagePaginationIsChronologicalAndDoesNotSkipTheLookahead() throws Exception {
        long target = wiring.sessions().create("分页", "test");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) { expected.add("消息" + i); legacy(target, i % 2 == 0 ? "user" : "assistant", "消息" + i, ""); }
        List<String> actual = new ArrayList<>();
        String cursor = null;
        do {
            var result = read(target, link(target), cursor, 2);
            rows(result, "messages").forEach(message -> actual.add((String) message.get("content")));
            cursor = (String) result.get("nextCursor");
        } while (cursor != null);
        assertEquals(expected, actual);
    }

    @Test void cursorIsBoundToSessionRevisionAndRejectsBadOffsets() throws Exception {
        long a = wiring.sessions().create("A", "test");
        long b = wiring.sessions().create("B", "test");
        legacy(a, "assistant", "x".repeat(7000), "");
        var first = read(a, link(a), null, 1);
        String cursor = (String) first.get("nextCursor");
        assertEquals("INVALID_ARGUMENT", read(b, link(b), cursor, 1).get("status"));
        assertEquals("INVALID_ARGUMENT", read(a, link(a), cursor.replace(":6000", ":99999"), 1).get("status"));
        wiring.sessions().update(a, "重新命名", null, null);
        assertEquals("OK", read(a, link(a), cursor, 1).get("status"));
        legacy(a, "user", "新增使历史变更", "");
        assertEquals("STALE", read(a, link(a), cursor, 1).get("status"));
    }

    @Test void passwordAndCorruptMessagesReportGapsWithoutSentinelAsFact() throws Exception {
        long target = wiring.sessions().create("加密历史", "test");
        legacy(target, "assistant", "受密码保护", "password");
        var locked = read(target, link(target), null, 10);
        assertEquals("UNAVAILABLE", locked.get("status"));
        assertEquals("LOCKED", locked.get("availability"));
        assertFalse(locked.toString().contains("受密码保护"));
        assertFalse(rows(locked, "messages").get(0).containsKey("content"));
        when(vault.sessionPassword(target)).thenReturn(Optional.of("password"));
        assertEquals("受密码保护", rows(read(target, link(target), null, 10), "messages").get(0).get("content"));
        jdbc.update("UPDATE ai_chat_message SET content_cipher = 'broken' WHERE session_id = ?", target);
        var broken = read(target, link(target), null, 10);
        assertEquals("UNREADABLE", broken.get("availability"));
        assertEquals(true, broken.get("incomplete"));
    }

    @Test void excludesRunningDeletedAndPreviousGenerationsButMarksFailedTurns() throws Exception {
        long target = wiring.sessions().create("轮次状态", "test");
        var complete = begin(target, "完整问题");
        wiring.turnService().finalizeTurn(complete, ChatTurnService.UpstreamReply.of("完整回答", "思考不返回", "stop"), null);
        var failed = begin(target, "失败的问题");
        wiring.turnService().finalizeTurn(failed, new ChatTurnService.UpstreamReply("部分回答", null, "stream_error",
                false, false, false, AiUpstreamClient.Usage.unknown()), null);
        var running = begin(target, "运行中不外发");
        var result = read(target, link(target), null, 20);
        assertEquals("OK", result.get("status"));
        assertTrue(result.toString().contains("完整回答"));
        assertFalse(result.toString().contains("思考不返回"));
        assertFalse(result.toString().contains("运行中不外发"));
        assertTrue(rows(result, "messages").stream().anyMatch(row -> !"COMPLETED".equals(row.get("turnStatus"))));
        wiring.turnService().abandon(running, "test");
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = '2026-11-09' WHERE id = ?", complete.turnId());
        assertFalse(read(target, link(target), null, 20).toString().contains("完整回答"));
        wiring.metadata().resetContextGeneration(target);
        var reset = read(target, link(target), null, 20);
        assertTrue(rows(reset, "messages").isEmpty());
        assertEquals(true, reset.get("incomplete"));
    }

    @Test void changesDuringDecryptionDiscardTheWholePage() throws Exception {
        long target = wiring.sessions().create("并发清理", "test");
        legacy(target, "assistant", "清理前的正文", "");
        ChatMessageCipher changing = spy(wiring.cipher());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            jdbc.update("UPDATE ai_chat_session SET history_revision = history_revision + 1 WHERE id = ?", target);
            return result;
        }).when(changing).readLegacy(any(), any(), any(), any());
        var result = new SessionLookupService(repository, changing, vault).read(plan, link(target), null, 10);
        assertEquals("STALE", result.get("status"));
        assertFalse(result.toString().contains("清理前的正文"));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "{\"session\":1}", "{\"session\":\"x\",\"limit\":0}",
            "{\"session\":\"x\",\"limit\":21}", "{\"session\":\"x\",\"limit\":1.5}",
            "{\"session\":\"x\",\"extra\":true}", "{\"session\":\"x\",\"session\":\"y\"}",
            "{\"session\":\"x\"} {}", "{\"session\":\"x\",\"cursor\":null}",
            "{\"session\":\"https://example.com/chat\"}", "{\"session\":\"drill-notebook://session/invalid\"}"})
    void rejectsInvalidReadArguments(String raw) { assertEquals("INVALID_ARGUMENT", read.execute(plan, raw, false, "").get("status")); }

    @ParameterizedTest @ValueSource(strings = {"null", "{} {}", "{\"limit\":0}", "{\"limit\":21}", "{\"query\":null}",
            "{\"includeArchived\":\"true\"}", "{\"limit\":1,\"limit\":2}", "{\"beforeSessionId\":-1}", "{\"unknown\":1}"})
    void rejectsInvalidListArguments(String raw) { assertEquals("INVALID_ARGUMENT", list.execute(plan, raw, false, "").get("status")); }

    @Test void invalidCallerCannotReadAnySession() {
        assertEquals("STALE", list.execute(null, "{}", true, "").get("status"));
        jdbc.update("UPDATE ai_chat_session SET context_generation = 2 WHERE id = ?", plan.sessionId());
        assertEquals("STALE", read.execute(plan, "{\"session\":\"1\"}", true, "").get("status"));
    }

    private ChatTurnService.Lease begin(long target, String text) {
        return wiring.turnService().begin(new ChatTurnService.BeginInput(UuidV7.generate(System.currentTimeMillis()),
                2, target, text, "", List.of(), Map.of())).lease();
    }
    private void legacy(long target, String role, String text, String password) {
        var encrypted = wiring.cipher().encrypt(text, password);
        wiring.sessions().insertMessage(target, role, "", encrypted.cipher(), encrypted.meta());
    }
    private String link(long id) { return (String) wiring.sessions().find(id).get("sessionLink"); }
    private Map<String, Object> read(long id, String selector, String cursor, int limit) throws Exception {
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("session", selector); args.put("limit", limit);
        if (cursor != null) args.put("cursor", cursor);
        return read.execute(plan, json.writeValueAsString(args), false, "");
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
}
