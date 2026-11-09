package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryListToolTest {
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
        appRoot = TestWorkspace.scratchDirectory("list-tool-");
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
        sessionId = com.drillnotebook.app.repository.ChatLegacyWiring.ensureSession(jdbc);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void definitionExposesOnlyOptionalLimitAndScope() {
        AiUpstreamClient.ToolDefinition definition = stack.memoryListTool().definition();
        assertEquals(MemoryListTool.NAME, definition.name());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertFalse(definition.parameters().containsKey("required"), "两个参数都可省略");
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertNotNull(properties);
        assertEquals(2, properties.size());
        Map<?, ?> limit = (Map<?, ?>) properties.get("limit");
        assertEquals("integer", limit.get("type"));
        assertEquals(MemoryListTool.MIN_LIMIT, limit.get("minimum"));
        assertEquals(MemoryListTool.MAX_LIMIT, limit.get("maximum"));
        Map<?, ?> scope = (Map<?, ?>) properties.get("scope");
        assertEquals("string", scope.get("type"));
        assertEquals(List.of("GLOBAL", "NOTEBOOK", "ALL"), scope.get("enum"));
    }

    @Test
    void malformedArgumentsAreRejected() {
        for (String raw : List.of("", "   ", "not-json", "[]", "{\"limit\":0}", "{\"limit\":21}",
                "{\"limit\":\"10\"}", "{\"limit\":10.5}", "{\"limit\":1,\"limit\":2}",
                "{\"scope\":\"OTHER\"}", "{\"scope\":1}", "{\"limit\":10,\"extra\":1}",
                "{\"limit\":10} trailing")) {
            assertInvalid(raw);
        }
        assertInvalid(null);
        assertInvalid("{\"limit\":10}" + " ".repeat(MemoryListTool.MAX_ARGUMENT_BYTES));
    }

    @Test
    void emptyVaultReturnsNoMatchWithReadyAvailability() throws Exception {
        unlockVault();
        enableUseConsent();
        indexReady();
        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            Map<String, Object> result = list(plan(lease, "我记了哪些事"), "{}");
            assertEquals("NO_MATCH", result.get("status"));
            assertEquals("READY", result.get("availability"));
            assertTrue(items(result).isEmpty());
            assertEquals(false, result.get("incomplete"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void listsOnlyTheCurrentNotebookAndGlobalScope() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (8, '数据库')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, 0L);
        add("GOAL", "GLOBAL", 0L, "全局目标", "global-goal", "全局记忆：复习计划先看内存管理");
        add("GOAL", "NOTEBOOK", 7L, "本册目标", "book-goal", "本册记忆：复习计划先看进程调度");
        add("GOAL", "NOTEBOOK", 8L, "别册目标", "other-goal", "别册记忆：复习计划先看事务隔离");
        indexReady();

        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            ChatContextPlan plan = plan(lease, "我记了哪些事");
            Map<String, Object> all = list(plan, "{\"scope\":\"ALL\"}");
            assertEquals("OK", all.get("status"));
            assertEquals(2, items(all).size(), "只允许当前笔记本与全局记忆");
            assertEquals(Set.of("GLOBAL:0", "NOTEBOOK:7"), scopes(all));
            assertFalse(items(all).toString().contains("事务隔离"));
            for (Map<String, Object> row : items(all)) {
                assertEquals(Set.of("title", "scope", "updatedAt"), row.keySet());
                assertNotNull(row.get("updatedAt"));
                assertFalse(row.keySet().contains("memoryId"));
                assertFalse(row.keySet().contains("predicate"));
                assertFalse(row.keySet().contains("version"));
            }
            assertTrue(items(all).stream()
                    .anyMatch(row -> "全局记忆：复习计划先看内存管理".equals(row.get("title"))));

            Map<String, Object> global = list(plan, "{\"scope\":\"GLOBAL\"}");
            assertEquals(1, items(global).size());
            assertEquals("GLOBAL:0", items(global).get(0).get("scope"));

            Map<String, Object> notebook = list(plan, "{\"scope\":\"NOTEBOOK\"}");
            assertEquals(1, items(notebook).size());
            assertEquals("NOTEBOOK:7", items(notebook).get(0).get("scope"));

            wiring.metadata().changeScope(sessionId, "GLOBAL", null, 0L);
            assertEquals("STALE", list(plan, "{}").get("status"), "作用域变化后旧计划必须作废");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }

        ChatTurnService.Lease globalLease = beginTurn("我记了哪些事");
        try {
            ChatContextPlan globalPlan = plan(globalLease, "我记了哪些事");
            assertEquals("INVALID_ARGUMENT", list(globalPlan, "{\"scope\":\"NOTEBOOK\"}").get("status"),
                    "全局会话不能按笔记本过滤");
            Map<String, Object> all = list(globalPlan, "{}");
            assertEquals(1, items(all).size());
            assertEquals("GLOBAL:0", items(all).get(0).get("scope"));
        } finally {
            wiring.turnService().abandon(globalLease, "test-cleanup");
        }
    }

    @Test
    void defaultLimitAndMoreResultsReportLimitReached() throws Exception {
        unlockVault();
        enableUseConsent();
        for (int index = 0; index < 12; index++) {
            add("GOAL", "GLOBAL", 0L, "目标" + index, "goal-" + index, "记忆条目" + index);
        }
        indexReady();

        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            ChatContextPlan plan = plan(lease, "我记了哪些事");
            Map<String, Object> defaults = list(plan, "{}");
            assertEquals("LIMIT_REACHED", defaults.get("status"));
            assertEquals(MemoryListTool.DEFAULT_LIMIT, items(defaults).size());
            assertEquals(true, defaults.get("incomplete"));

            Map<String, Object> twenty = list(plan, "{\"limit\":20}");
            assertEquals("OK", twenty.get("status"));
            assertEquals(12, items(twenty).size());
            assertEquals(false, twenty.get("incomplete"));

            Map<String, Object> one = list(plan, "{\"limit\":1}");
            assertEquals("LIMIT_REACHED", one.get("status"));
            assertEquals(1, items(one).size());
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void expiredDisabledAndSuppressedMemoriesAreHidden() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "保留", "keep", "保留的记忆");
        MemoryApiResult expired = add("GOAL", "GLOBAL", 0L, "过期", "expired", "已过期的记忆");
        MemoryApiResult disabled = add("GOAL", "GLOBAL", 0L, "停用", "disabled", "已停用的记忆");
        MemoryApiResult suppressed = add("GOAL", "GLOBAL", 0L, "抑制", "suppressed", "被抑制的记忆");
        jdbc.update("UPDATE ai_memory_item SET valid_until = ? WHERE memory_id = ?",
                "2000-01-01T00:00:00Z", String.valueOf(expired.body().get("memoryId")));
        jdbc.update("UPDATE ai_memory_item SET status = 'DISABLED' WHERE memory_id = ?",
                String.valueOf(disabled.body().get("memoryId")));
        String familyHmac = jdbc.queryForObject(
                "SELECT family_key_hmac FROM ai_memory_item WHERE memory_id = ?", String.class,
                String.valueOf(suppressed.body().get("memoryId")));
        indexReady();
        String vaultId = wiring.memory().readEnvelope().orElseThrow().vaultId();
        new MemorySuppressionRepository(dataSource, wiring.immediate()).record(vaultId,
                new MemorySuppressionRepository.Suppression("GLOBAL", 0L, MemorySuppressionRepository.KIND_FAMILY,
                        familyHmac, null, Instant.now().toString()));

        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            Map<String, Object> result = list(plan(lease, "我记了哪些事"), "{}");
            assertEquals("OK", result.get("status"));
            assertEquals(1, items(result).size());
            assertEquals("保留的记忆", items(result).get(0).get("title"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void corruptRowsAreMarkedIncompleteButSafeTitlesStillReturn() throws Exception {
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

        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            Map<String, Object> result = list(plan(lease, "我记了哪些事"), "{}");
            assertEquals("OK", result.get("status"));
            assertEquals(true, result.get("incomplete"));
            assertEquals(1, items(result).size());
            assertFalse(items(result).toString().contains("磁盘调度"), "损坏行绝不能被当成标题输出");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void switchedOffAndDeniedRequestsReturnUnavailable() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "目标", "goal", "复习计划先看内存管理");
        indexReady();

        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            ChatContextPlan plan = plan(lease, "我记了哪些事");
            Map<String, Object> switchedOff = stack.memoryListTool().execute(plan, "{}", false, "我记了哪些事");
            assertEquals("UNAVAILABLE", switchedOff.get("status"));
            assertEquals("NOT_REQUESTED", switchedOff.get("availability"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }

        stack.policy().revokeConsent(MemoryPolicyService.PURPOSE_USE);
        ChatTurnService.Lease deniedLease = beginTurn("我记了哪些事");
        try {
            ChatContextPlan denied = plan(deniedLease, "我记了哪些事");
            Map<String, Object> result = stack.memoryListTool().execute(denied, "{}", true, "我记了哪些事");
            assertEquals("UNAVAILABLE", result.get("status"));
            assertEquals("DENIED", result.get("availability"));
            assertTrue(items(result).isEmpty());
        } finally {
            wiring.turnService().abandon(deniedLease, "test-cleanup");
        }
    }

    @Test
    void staleAndLockedVaultsRefuseToRead() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "目标", "goal", "复习计划先看内存管理");
        indexReady();
        ChatContextPlan stalePlan = plan("我记了哪些事");
        completeTurn("补充一轮对话", "回答");
        assertEquals("STALE", list(stalePlan, "{}").get("status"),
                "历史版本变化后旧计划不能继续读记忆");

        ChatTurnService.Lease lease = beginTurn("我记了哪些事");
        try {
            ChatContextPlan plan = plan(lease, "我记了哪些事");
            stack.vault().lock();
            Map<String, Object> result = list(plan, "{}");
            assertEquals("UNAVAILABLE", result.get("status"));
            assertEquals("LOCKED", result.get("availability"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    private void assertInvalid(String raw) {
        assertEquals("INVALID_ARGUMENT",
                String.valueOf(stack.memoryListTool().execute(null, raw, true, "我记了哪些事").get("status")));
    }

    private Map<String, Object> list(ChatContextPlan plan, String raw) {
        return stack.memoryListTool().execute(plan, raw, true, "我记了哪些事");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private static Set<String> scopes(Map<String, Object> result) {
        return items(result).stream().map(row -> String.valueOf(row.get("scope"))).collect(Collectors.toSet());
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
        return "list-tool-" + action + '-' + (++sequence);
    }
}
