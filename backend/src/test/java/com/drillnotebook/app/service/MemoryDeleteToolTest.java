package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryDeleteToolTest {
    private static final String PASSWORD = "correct horse battery";

    private Path appRoot;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private MemoryDeleteTool tool;
    private long sessionId;
    private int sequence;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("delete-memory-tool-");
        DataSource dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        tool = new MemoryDeleteTool(stack.contexts());
        new AiConfigRepository(jdbc).upsertChat("custom", "https://example.invalid/v1", "stub-model", "enc", "{}",
                "{\"contextWindow\":32000}");
        sessionId = ChatLegacyWiring.ensureSession(jdbc);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        stack.indexes().shutdown();
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void malformedArgumentsAreRejectedBeforeAnyLookup() {
        for (String raw : List.of("", "not-json", "[]", "{}",
                "{\"memoryId\":\"m1\"}",
                "{\"expectedVersion\":1}",
                "{\"memoryId\":\"m1\",\"expectedVersion\":1,\"currentText\":\"旧正文\"}",
                "{\"currentText\":\"旧正文\",\"expectedVersion\":1}",
                "{\"memoryId\":\"m1\",\"expectedVersion\":\"1\"}",
                "{\"currentText\":\" \"}",
                "{\"currentText\":\"旧正文\",\"all\":true}",
                "{\"currentText\":\"旧正文\"} trailing",
                "{\"currentText\":\"旧正文\",\"currentText\":\"另一条\"}")) {
            assertEquals("INVALID_ARGUMENT", String.valueOf(tool.execute(null, raw, true, "删掉").get("status")), raw);
        }
    }

    @Test
    void ownDeletionDoesNotAuthorizeAnUnrelatedVaultChange() throws Exception {
        unlockVault(); enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "study.goal", "旧目标");
        var lease = beginTurn("删除旧目标");
        try {
            var before = plan(lease, "删除旧目标");
            assertEquals("DELETED", tool.execute(before, "{\"currentText\":\"旧目标\"}", true, "删除旧目标").get("status"));
            add("PREFERENCE", "GLOBAL", 0L, "study.other", "其他窗口新写的偏好");
            var refreshed = stack.contexts().refreshAfterMemoryForget(before);
            assertEquals(before, refreshed, "不能把其他窗口的变更算作本轮删除");
            assertFalse(stack.contexts().matches(refreshed));
        } finally { wiring.turnService().abandon(lease, "test"); }
    }

    @Test
    void deletesByCurrentTextSuppressesTheTopicAndLetsTheSameTurnContinue() throws Exception {
        unlockVault();
        enableUseConsent();
        String memoryId = add("GOAL", "GLOBAL", 0L, "study.goal", "考试安排在周五");
        String kept = add("PREFERENCE", "GLOBAL", 0L, "study.style", "喜欢先看结论");
        ChatTurnService.Lease lease = beginTurn("忘掉考试安排");
        try {
            ChatContextPlan plan = plan(lease, "忘掉考试安排");
            var before = wiring.memory().readEnvelope().orElseThrow();
            Map<String, Object> result = tool.execute(plan, "{\"currentText\":\"考试安排在周五\"}", true, "忘掉考试安排");
            assertEquals("DELETED", result.get("status"), String.valueOf(result));
            assertEquals(memoryId, result.get("memoryId"));
            assertEquals(Boolean.TRUE, result.get(AiService.VAULT_CHANGED));
            assertFalse(result.toString().contains("考试安排"), "结果不回显记忆正文");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", Integer.class, memoryId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", Integer.class, kept));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_suppression", Integer.class) > 0,
                    "删除后该主题不被自动重新记住");
            var after = wiring.memory().readEnvelope().orElseThrow();
            assertTrue(after.epoch() > before.epoch() && after.revision() > before.revision());

            assertFalse(stack.contexts().matches(plan), "未刷新的旧计划必须被视为已变化");
            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryForget(plan);
            assertTrue(stack.contexts().matches(refreshed), "本轮自己的删除不应打断本轮");
            assertEquals("NOT_FOUND", tool.execute(refreshed, "{\"currentText\":\"考试安排在周五\"}", true, "再删一次").get("status"));
        } finally {
            wiring.turnService().abandon(lease, "test");
        }
    }

    @Test
    void deleteByIdRequiresTheCurrentVersionAndStaysWithinTheVisibleScope() throws Exception {
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '本册'), (8, '别的笔记本')");
        unlockVault();
        enableUseConsent();
        String global = add("GOAL", "GLOBAL", 0L, "study.goal", "全局目标");
        String other = add("GOAL", "NOTEBOOK", 8L, "study.other", "别册事实");
        ChatTurnService.Lease lease = beginTurn("删除记忆");
        try {
            ChatContextPlan plan = plan(lease, "删除记忆");
            assertEquals("STALE", tool.execute(plan, "{\"memoryId\":\"" + global + "\",\"expectedVersion\":9}", true, "").get("status"));
            assertEquals("NOT_FOUND", tool.execute(plan, "{\"memoryId\":\"" + other + "\",\"expectedVersion\":1}", true, "").get("status"));
            assertEquals("NOT_FOUND", tool.execute(plan, "{\"currentText\":\"别册事实\"}", true, "").get("status"));
            assertEquals("NOT_FOUND", tool.execute(plan, "{\"currentText\":\"不存在的记忆\"}", true, "").get("status"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
            Map<String, Object> off = tool.execute(plan, "{\"currentText\":\"全局目标\"}", false, "");
            assertEquals("UNAVAILABLE", off.get("status"));
            assertEquals("NOT_REQUESTED", off.get("availability"));
            assertFalse(off.containsKey(AiService.VAULT_CHANGED));
            assertEquals("DELETED", tool.execute(plan, "{\"memoryId\":\"" + global + "\",\"expectedVersion\":1}", true, "").get("status"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test");
        }
    }

    @Test
    void sameTextInTwoVisibleScopesIsAmbiguousAndNothingIsDeleted() throws Exception {
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '本册')");
        jdbc.update("UPDATE ai_chat_session SET memory_scope_type = 'NOTEBOOK', memory_notebook_id = 7 WHERE id = ?", sessionId);
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "study.goal", "每天复习一小时");
        add("GOAL", "NOTEBOOK", 7L, "study.goal.book", "每天复习一小时");
        ChatTurnService.Lease lease = beginTurn("删除记忆");
        try {
            ChatContextPlan plan = plan(lease, "删除记忆");
            long epoch = wiring.memory().readEnvelope().orElseThrow().epoch();
            assertEquals("AMBIGUOUS", tool.execute(plan, "{\"currentText\":\"每天复习一小时\"}", true, "").get("status"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
            assertEquals(epoch, wiring.memory().readEnvelope().orElseThrow().epoch());
            assertNotEquals("DELETED", tool.execute(null, "{\"currentText\":\"每天复习一小时\"}", true, "").get("status"));
        } finally {
            wiring.turnService().abandon(lease, "test");
        }
    }

    private String add(String kind, String scopeType, long scopeId, String predicate, String value) {
        MemoryApiResult added = stack.items().add(new MemoryItemService.AddInput(kind,
                scopeType, scopeId, predicate, predicate + ':' + value, value, null, true, op("add")));
        assertEquals(201, added.status(), value + " 没有落成: " + added.body());
        return String.valueOf(added.body().get("memoryId"));
    }

    private ChatContextPlan plan(ChatTurnService.Lease lease, String userText) throws Exception {
        return stack.contexts().plan(lease, userText, List.of(), Map.of(), Map.of("use", true), 32000, true);
    }

    private ChatTurnService.Lease beginTurn(String userText) {
        return wiring.turnService().begin(new ChatTurnService.BeginInput(
                com.drillnotebook.app.model.UuidV7.generate(System.currentTimeMillis()), 2, sessionId, userText,
                PASSWORD, List.of(), Map.of())).lease();
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

    private String op(String action) {
        return "delete-tool-" + action + '-' + (++sequence);
    }
}
