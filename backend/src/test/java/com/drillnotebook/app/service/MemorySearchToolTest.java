package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ChatContextPlan;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemorySearchToolTest {
    private static final String PASSWORD = "correct horse battery";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private MemoryItemService items;
    private long sessionId;
    private int sequence;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("search-tool-");
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
        new AiConfigRepository(jdbc).upsertChat("custom", "https://example.invalid/v1", "stub-model", "enc", "{}",
                "{\"contextWindow\":32000}");
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void definitionExposesOnlyTheBoundedQueryArgument() {
        AiUpstreamClient.ToolDefinition definition = stack.memorySearchTool().definition();
        assertEquals(MemorySearchTool.NAME, definition.name());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertEquals(List.of("query"), definition.parameters().get("required"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertNotNull(properties);
        assertEquals(1, properties.size());
        Map<?, ?> query = (Map<?, ?>) properties.get("query");
        assertEquals("string", query.get("type"));
        assertEquals(MemorySearchTool.MAX_QUERY_CODE_POINTS, query.get("maxLength"));
    }

    @Test
    void malformedExtraOversizedAndNonTextArgumentsAreRejected() {
        MemorySearchTool tool = stack.memorySearchTool();
        assertInvalid(tool, null);
        for (String raw : List.of("", "   ", "not-json", "[]", "{}", "{\"query\":\"\"}",
                "{\"query\":\"   \"}", "{\"query\":1}", "{\"query\":\"x\",\"extra\":1}",
                "{\"query\":\"a\",\"query\":\"b\"}", "{\"query\":\"a\"} trailing",
                "{\"query\":\"" + "复".repeat(MemorySearchTool.MAX_QUERY_CODE_POINTS + 1) + "\"}")) {
            assertInvalid(tool, raw);
        }
        String oversized = "{\"query\":\"复习计划\"}" + " ".repeat(MemorySearchTool.MAX_ARGUMENT_BYTES);
        assertInvalid(tool, oversized);

        String boundary = "{\"query\":\"" + "复".repeat(MemorySearchTool.MAX_QUERY_CODE_POINTS) + "\"}";
        assertEquals("STALE", status(tool, null, boundary), "200 个码点必须通过参数校验");
    }

    @Test
    void deniedConsentAndStalePlansRefuseToReadMemories() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "考试安排", "goal", "本周准备内存管理考试");
        indexReady();
        ChatContextPlan plan = plan("内存管理考试怎么复习");

        completeTurn("补充一轮对话", "回答");
        assertEquals("STALE", status(stack.memorySearchTool(), plan, arguments("内存管理考试")),
                "历史版本变化后旧计划不能继续读记忆");

        stack.policy().revokeConsent(MemoryPolicyService.PURPOSE_USE);
        ChatTurnService.Lease deniedLease = beginTurn("内存管理考试怎么复习");
        try {
            ChatContextPlan denied = plan(deniedLease, "内存管理考试怎么复习");
            Map<String, Object> deniedResult = stack.memorySearchTool()
                    .execute(denied, arguments("内存管理考试"), true, "内存管理考试怎么复习");
            assertEquals("UNAVAILABLE", deniedResult.get("status"));
            assertEquals("DENIED", deniedResult.get("availability"));
            assertTrue(facts(deniedResult).isEmpty());

            Map<String, Object> switchedOff = stack.memorySearchTool()
                    .execute(denied, arguments("内存管理考试"), false, "内存管理考试怎么复习");
            assertEquals("UNAVAILABLE", switchedOff.get("status"));
            assertEquals("NOT_REQUESTED", switchedOff.get("availability"));
        } finally {
            wiring.turnService().abandon(deniedLease, "test-cleanup");
        }
    }

    @Test
    void noRelevantMatchReturnsNoMatchWithReadyAvailability() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "考试安排", "goal", "本周准备内存管理考试");
        indexReady();
        ChatTurnService.Lease lease = beginTurn("今天有什么好看的纪录片");
        try {
            Map<String, Object> result = stack.memorySearchTool().execute(plan(lease, "今天有什么好看的纪录片"),
                    arguments("今天有什么好看的纪录片"), true, "今天有什么好看的纪录片");
            assertEquals("NO_MATCH", result.get("status"));
            assertEquals("READY", result.get("availability"));
            assertTrue(facts(result).isEmpty());
            assertEquals(false, result.get("incomplete"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void resultIsLimitedToTheCurrentScopeAndRejectsStaleScopeChanges() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (8, '数据库')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, 0L);
        add("GOAL", "GLOBAL", 0L, "全局目标", "global-goal", "复习计划先看内存管理");
        add("GOAL", "NOTEBOOK", 7L, "本册目标", "book-goal", "复习计划先看进程调度");
        add("GOAL", "NOTEBOOK", 8L, "别册目标", "other-goal", "复习计划先看事务隔离");
        indexReady();
        ChatTurnService.Lease lease = beginTurn("复习计划怎么安排");
        try {
            ChatContextPlan plan = plan(lease, "复习计划怎么安排");
            Map<String, Object> result = stack.memorySearchTool()
                    .execute(plan, arguments("复习计划"), true, "复习计划怎么安排");
            assertEquals("OK", result.get("status"));
            List<Map<String, Object>> rows = facts(result);
            assertEquals(2, rows.size(), "只允许当前笔记本与全局事实");
            assertEquals(java.util.Set.of("GLOBAL:0", "NOTEBOOK:7"),
                    new java.util.HashSet<>(rows.stream().map(row -> row.get("scope")).toList()),
                    "只允许当前笔记本与全局事实，检索顺序按相关性而非作用域");
            assertFalse(rows.toString().contains("事务隔离"));

            wiring.metadata().changeScope(sessionId, "GLOBAL", null, 0L);
            assertEquals("STALE", status(stack.memorySearchTool(), plan, arguments("复习计划")),
                    "作用域变化后旧计划必须作废");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void moreThanFiveSafeFactsReportLimitReached() throws Exception {
        unlockVault();
        enableUseConsent();
        for (int index = 0; index < 6; index++) {
            add("GOAL", "GLOBAL", 0L, "目标" + index, "goal-" + index, "复习计划要点" + index);
        }
        add("PREFERENCE", "GLOBAL", 0L, "answer.detail", "answer-detail", "复习计划要详细");
        add("PREFERENCE", "GLOBAL", 0L, "answer.order", "answer-order", "复习计划按顺序");
        indexReady();

        ChatTurnService.Lease lease = beginTurn("复习计划怎么安排");
        try {
            Map<String, Object> result = stack.memorySearchTool().execute(plan(lease, "复习计划怎么安排"),
                    arguments("复习计划"), true, "复习计划怎么安排");
            assertEquals("LIMIT_REACHED", result.get("status"));
            assertEquals("READY", result.get("availability"));
            List<Map<String, Object>> rows = facts(result);
            assertEquals(ChatContextService.MEMORY_TOOL_LIMIT, rows.size());
            for (Map<String, Object> row : rows) {
                assertEquals(java.util.Set.of("text", "kind", "scope", "expiry"), row.keySet());
                assertFalse(row.containsKey("predicate"));
                assertFalse(row.containsKey("family"));
                assertFalse(row.containsKey("memoryId"));
                assertFalse(row.containsKey("version"));
                assertNull(row.get("expiry"));
            }
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void corruptIndexDataIsMarkedIncompleteButSafeFactsStillReturn() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "好目标", "good-goal", "复习计划先看内存管理");
        MemoryApiResult broken = add("GOAL", "GLOBAL", 0L, "坏目标", "bad-goal", "复习计划先看磁盘调度");
        String brokenId = String.valueOf(broken.body().get("memoryId"));
        String cipher = jdbc.queryForObject("SELECT payload_cipher FROM ai_memory_item WHERE memory_id = ?",
                String.class, brokenId);
        jdbc.update("UPDATE ai_memory_item SET payload_cipher = ? WHERE memory_id = ?",
                "M" + cipher.substring(1), brokenId);
        indexReady();

        ChatTurnService.Lease lease = beginTurn("复习计划怎么安排");
        try {
            Map<String, Object> result = stack.memorySearchTool()
                    .execute(plan(lease, "复习计划怎么安排"), arguments("复习计划"), true, "复习计划怎么安排");
            assertEquals("OK", result.get("status"));
            assertEquals(true, result.get("incomplete"));
            List<Map<String, Object>> rows = facts(result);
            assertEquals(1, rows.size());
            assertEquals("复习计划先看内存管理", rows.get(0).get("text"));
            assertFalse(rows.toString().contains("磁盘调度"), "损坏行绝不能被当成事实输出");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    private static void assertInvalid(MemorySearchTool tool, String raw) {
        assertEquals("INVALID_ARGUMENT", status(tool, null, raw));
    }

    private static String status(MemorySearchTool tool, ChatContextPlan plan, String raw) {
        return String.valueOf(tool.execute(plan, raw, true, "query").get("status"));
    }

    private static String arguments(String query) {
        return "{\"query\":\"" + query + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> facts(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("facts");
    }

    private Map<String, Object> execute(ChatContextPlan plan, String query) {
        return stack.memorySearchTool().execute(plan, arguments(query), true, query);
    }

    private ChatContextPlan plan(String userText) throws Exception {
        ChatTurnService.Lease lease = beginTurn(userText);
        try {
            return plan(lease, userText);
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    private ChatContextPlan plan(ChatTurnService.Lease lease, String userText) throws Exception {
        return stack.contexts().plan(lease, userText, List.of(), Map.of(), Map.of("use", true), 32000, true);
    }

    private void completeTurn(String userText, String reply) {
        ChatTurnService.Lease lease = beginTurn(userText);
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of(reply, null, "stop"), null);
    }

    private ChatTurnService.Lease beginTurn(String userText) {
        ChatTurnService.BeginResult begun = wiring.turnService().begin(new ChatTurnService.BeginInput(
                requestId(), 2, sessionId, userText, PASSWORD, List.of(), Map.of()));
        return begun.lease();
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

    private String requestId() {
        return com.drillnotebook.app.model.UuidV7.generate(System.currentTimeMillis());
    }

    private String op(String action) {
        return "search-tool-" + action + '-' + (++sequence);
    }
}
