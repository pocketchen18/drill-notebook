package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryUpdateToolTest {
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
        appRoot = TestWorkspace.scratchDirectory("update-tool-");
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
    void definitionAcceptsTextPlusExactlyOneLocator() {
        AiUpstreamClient.ToolDefinition definition = stack.memoryUpdateTool().definition();
        assertEquals(MemoryUpdateTool.NAME, definition.name());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertEquals(List.of("text"), definition.parameters().get("required"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals(5, properties.size());
        assertTrue(properties.containsKey("memoryId"));
        assertTrue(properties.containsKey("expectedVersion"));
        assertTrue(properties.containsKey("currentText"));
        assertTrue(properties.containsKey("text"));
        assertTrue(properties.containsKey("expiry"));
        Map<?, ?> text = (Map<?, ?>) properties.get("text");
        assertEquals(1, text.get("minLength"));
        assertEquals(MemoryUpdateTool.MAX_TEXT_CODE_POINTS, text.get("maxLength"));
    }

    @Test
    void malformedArgumentsAreRejected() {
        String longText = "a".repeat(MemoryUpdateTool.MAX_TEXT_CODE_POINTS + 1);
        String longId = "a".repeat(MemoryUpdateTool.MAX_MEMORY_ID_CODE_POINTS + 1);
        for (String raw : List.of("", "   ", "not-json", "[]", "{}",
                "{\"text\":\"新正文\"}",
                "{\"memoryId\":\"m1\",\"text\":\"新正文\"}",
                "{\"expectedVersion\":1,\"text\":\"新正文\"}",
                "{\"memoryId\":\"m1\",\"expectedVersion\":1,\"currentText\":\"旧正文\",\"text\":\"新正文\"}",
                "{\"currentText\":\"旧正文\",\"expectedVersion\":1,\"text\":\"新正文\"}",
                "{\"memoryId\":\"m1\",\"expectedVersion\":0,\"text\":\"新正文\"}",
                "{\"memoryId\":\"m1\",\"expectedVersion\":\"1\",\"text\":\"新正文\"}",
                "{\"currentText\":\"旧正文\",\"text\":\" \"}",
                "{\"currentText\":\"旧正文\",\"text\":\"新正文\",\"extra\":1}",
                "{\"currentText\":\"旧正文\",\"text\":\"新正文\"} trailing",
                "{\"currentText\":\"旧正文\",\"text\":\"新正文\",\"text\":\"再改\"}",
                "{\"currentText\":\"" + longText + "\",\"text\":\"新正文\"}",
                "{\"currentText\":\"旧正文\",\"text\":\"" + longText + "\"}",
                "{\"memoryId\":\"" + longId + "\",\"expectedVersion\":1,\"text\":\"新正文\"}")) {
            assertEquals("INVALID_ARGUMENT", String.valueOf(
                    stack.memoryUpdateTool().execute(null, raw, true, "改一下").get("status")), raw);
        }
    }

    @Test
    void updatesByCurrentTextAndSupersedesTheOldVersion() throws Exception {
        unlockVault();
        enableUseConsent();
        String memoryId = add("GOAL", "GLOBAL", 0L, "study.goal", "考试安排");
        ChatTurnService.Lease lease = beginTurn("把考试安排改到周五");
        try {
            ChatContextPlan plan = plan(lease, "把考试安排改到周五");
            long before = vaultRevision();
            Map<String, Object> result = update(plan, "{\"currentText\":\"考试安排\",\"text\":\"考试安排改到周五\"}");
            assertEquals("UPDATED", result.get("status"), String.valueOf(result));
            assertEquals(memoryId, result.get("memoryId"));
            assertEquals(2, result.get("version"));
            assertTrue(vaultRevision() > before, "更新必须推进 vault revision");

            Map<String, Object> current = jdbc.queryForMap(
                    "SELECT status, origin, manual_protected FROM ai_memory_item WHERE memory_id = ? AND version = 2",
                    memoryId);
            assertEquals("ACTIVE", current.get("status"));
            assertEquals("MANUAL", current.get("origin"), "更新后来源必须转人工");
            assertEquals(1, ((Number) current.get("manual_protected")).intValue());
            assertEquals("SUPERSEDED", jdbc.queryForObject(
                    "SELECT status FROM ai_memory_item WHERE memory_id = ? AND version = 1", String.class, memoryId));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", Integer.class, memoryId));

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> listed = stack.memoryListTool().execute(refreshed, "{}", true,
                    "把考试安排改到周五");
            assertEquals("OK", listed.get("status"), String.valueOf(listed));
            assertTrue(listed.toString().contains("考试安排改到周五"));
            assertFalse(listed.toString().contains("考试安排\""), "列表不能再显示旧正文");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void updateByIdRequiresTheCurrentVersion() throws Exception {
        unlockVault();
        enableUseConsent();
        String memoryId = add("PREFERENCE", "GLOBAL", 0L, "answer.order", "我喜欢先看例子");
        ChatTurnService.Lease lease = beginTurn("改成先讲概念");
        try {
            ChatContextPlan plan = plan(lease, "改成先讲概念");
            Map<String, Object> first = update(plan,
                    "{\"memoryId\":\"" + memoryId + "\",\"expectedVersion\":1,\"text\":\"我喜欢先讲概念\"}");
            assertEquals("UPDATED", first.get("status"), String.valueOf(first));
            assertEquals(2, first.get("version"));

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> stale = update(refreshed,
                    "{\"memoryId\":\"" + memoryId + "\",\"expectedVersion\":1,\"text\":\"再改一次\"}");
            assertEquals("STALE", stale.get("status"), String.valueOf(stale));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", Integer.class, memoryId),
                    "STALE 不能产生新版本");

            Map<String, Object> missing = update(refreshed,
                    "{\"memoryId\":\"missing-memory\",\"expectedVersion\":1,\"text\":\"再改一次\"}");
            assertEquals("NOT_FOUND", missing.get("status"), String.valueOf(missing));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void normalizedNoChangeDoesNotCreateANewVersion() throws Exception {
        unlockVault();
        enableUseConsent();
        String memoryId = add("PREFERENCE", "GLOBAL", 0L, "answer.order", "Hello World");
        ChatTurnService.Lease lease = beginTurn("只是调整空格");
        try {
            ChatContextPlan plan = plan(lease, "只是调整空格");
            long before = vaultRevision();
            Map<String, Object> result = update(plan,
                    "{\"currentText\":\"Hello World\",\"text\":\"  Hello   World  \"}");
            assertEquals("NO_CHANGE", result.get("status"), String.valueOf(result));
            assertEquals(memoryId, result.get("memoryId"));
            assertEquals(1, result.get("version"));
            assertEquals(before, vaultRevision(), "NO_CHANGE 不能推进 revision");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?", Integer.class, memoryId));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void notebookSessionCanUpdateItsNotebookAndGlobalButNotOtherNotebooks() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (8, '数据库')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, 0L);
        String own = add("GOAL", "NOTEBOOK", 7L, "study.goal", "本册重点看进程");
        String global = add("PREFERENCE", "GLOBAL", 0L, "answer.order", "我喜欢先看例子");
        String other = add("GOAL", "NOTEBOOK", 8L, "study.goal", "另一本的内容");
        ChatTurnService.Lease lease = beginTurn("更新本册和全局记忆");
        try {
            ChatContextPlan plan = plan(lease, "更新本册和全局记忆");
            Map<String, Object> ownUpdate = update(plan,
                    "{\"currentText\":\"本册重点看进程\",\"text\":\"本册重点看线程\"}");
            assertEquals("UPDATED", ownUpdate.get("status"), String.valueOf(ownUpdate));
            assertEquals("NOTEBOOK", jdbc.queryForObject(
                    "SELECT scope_type FROM ai_memory_item WHERE memory_id = ? AND version = 2",
                    String.class, own));

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> globalUpdate = update(refreshed,
                    "{\"currentText\":\"我喜欢先看例子\",\"text\":\"我喜欢先讲概念\"}");
            assertEquals("UPDATED", globalUpdate.get("status"), String.valueOf(globalUpdate));
            assertEquals("GLOBAL", jdbc.queryForObject(
                    "SELECT scope_type FROM ai_memory_item WHERE memory_id = ? AND version = 2",
                    String.class, global));

            ChatContextPlan afterGlobal = stack.contexts().refreshAfterMemoryWrite(refreshed);
            Map<String, Object> otherByText = update(afterGlobal,
                    "{\"currentText\":\"另一本的内容\",\"text\":\"越权改另一本\"}");
            assertEquals("NOT_FOUND", otherByText.get("status"), String.valueOf(otherByText));
            Map<String, Object> otherById = update(afterGlobal,
                    "{\"memoryId\":\"" + other + "\",\"expectedVersion\":1,\"text\":\"越权改另一本\"}");
            assertEquals("NOT_FOUND", otherById.get("status"), String.valueOf(otherById));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?",
                    Integer.class, other), "越权更新不能产生新版本");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void globalSessionCannotUpdateNotebookMemory() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        String notebook = add("GOAL", "NOTEBOOK", 7L, "study.goal", "本册重点看进程");
        ChatTurnService.Lease lease = beginTurn("改一下本册记忆");
        try {
            ChatContextPlan plan = plan(lease, "改一下本册记忆");
            assertEquals("NOT_FOUND", update(plan,
                    "{\"currentText\":\"本册重点看进程\",\"text\":\"全局会话越权改\"}").get("status"));
            assertEquals("NOT_FOUND", update(plan,
                    "{\"memoryId\":\"" + notebook + "\",\"expectedVersion\":1,\"text\":\"全局会话越权改\"}")
                    .get("status"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void ambiguousCurrentTextIsRejectedInsteadOfGuessing() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "study.goal", "重复目标");
        add("TASK", "GLOBAL", 0L, "task.constraint", "重复目标");
        ChatTurnService.Lease lease = beginTurn("更新重复目标");
        try {
            ChatContextPlan plan = plan(lease, "更新重复目标");
            Map<String, Object> result = update(plan, "{\"currentText\":\"重复目标\",\"text\":\"新的目标\"}");
            assertEquals("AMBIGUOUS", result.get("status"), String.valueOf(result));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE version = 2",
                    Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void missingConsentOrLockedVaultReturnsUnavailable() throws Exception {
        unlockVault();
        enableUseConsent();
        add("GOAL", "GLOBAL", 0L, "study.goal", "考试安排");
        ChatTurnService.Lease lease = beginTurn("更新考试安排");
        try {
            stack.policy().revokeConsent(MemoryPolicyService.PURPOSE_USE);
            ChatContextPlan deniedPlan = plan(lease, "更新考试安排");
            Map<String, Object> denied = update(deniedPlan,
                    "{\"currentText\":\"考试安排\",\"text\":\"考试安排改到周五\"}");
            assertEquals("UNAVAILABLE", denied.get("status"), String.valueOf(denied));
            assertEquals("DENIED", denied.get("availability"));

            enableUseConsent();
            ChatContextPlan plan = plan(lease, "更新考试安排");
            stack.vault().lock();
            Map<String, Object> locked = update(plan,
                    "{\"currentText\":\"考试安排\",\"text\":\"考试安排改到周五\"}");
            assertEquals("UNAVAILABLE", locked.get("status"), String.valueOf(locked));
            assertEquals("LOCKED", locked.get("availability"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void sensitiveOrCredentialNewTextIsRejectedWithoutANewVersion() throws Exception {
        unlockVault();
        enableUseConsent();
        add("BACKGROUND", "GLOBAL", 0L, "study.background", "考试安排");
        ChatTurnService.Lease lease = beginTurn("改成邮箱");
        try {
            ChatContextPlan plan = plan(lease, "改成邮箱");
            long before = vaultRevision();
            Map<String, Object> sensitive = update(plan,
                    "{\"currentText\":\"考试安排\",\"text\":\"我的邮箱是 a@example.com\"}");
            assertEquals("REJECTED", sensitive.get("status"), String.valueOf(sensitive));
            assertEquals("SENSITIVE", sensitive.get("reason"));
            Map<String, Object> credential = update(plan,
                    "{\"currentText\":\"考试安排\",\"text\":\"密码: Abcdef123\"}");
            assertEquals("REJECTED", credential.get("status"), String.valueOf(credential));
            assertEquals("CREDENTIAL", credential.get("reason"));
            assertEquals(before, vaultRevision());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void stalePlanIsRejectedBeforeAnyUpdate() throws Exception {
        unlockVault();
        enableUseConsent();
        String memoryId = add("GOAL", "GLOBAL", 0L, "study.goal", "考试安排");
        ChatTurnService.Lease lease = beginTurn("更新考试安排");
        ChatContextPlan plan = plan(lease, "更新考试安排");
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of("好", null, "stop"), null);

        Map<String, Object> result = update(plan, "{\"currentText\":\"考试安排\",\"text\":\"考试安排改到周五\"}");
        assertEquals("STALE", result.get("status"), String.valueOf(result));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id = ?",
                Integer.class, memoryId));
    }

    @Test
    void updateInheritsEvidenceAndExpiryIsPreservedWhenOmitted() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：下个月复习");
        try {
            ChatContextPlan plan = plan(lease, "记住：下个月复习");
            Map<String, Object> created = stack.memoryCreateTool().execute(plan,
                    "{\"text\":\"下个月复习\",\"kind\":\"GOAL\",\"expiry\":\"2099-01-01T08:30:00+08:00\"}",
                    true, "记住：下个月复习", lease.turnId(), lease.userMessageId());
            assertEquals("CREATED", created.get("status"), String.valueOf(created));
            String memoryId = String.valueOf(created.get("memoryId"));

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> updated = update(refreshed,
                    "{\"memoryId\":\"" + memoryId + "\",\"expectedVersion\":1,\"text\":\"下个季度复习\"}");
            assertEquals("UPDATED", updated.get("status"), String.valueOf(updated));
            assertEquals(2, updated.get("version"));

            Map<String, Object> row = jdbc.queryForMap("SELECT row_id, origin, manual_protected, valid_until"
                    + " FROM ai_memory_item WHERE memory_id = ? AND version = 2", memoryId);
            assertEquals("MANUAL", row.get("origin"));
            assertEquals(1, ((Number) row.get("manual_protected")).intValue());
            assertEquals("2099-01-01T00:30:00.000Z", row.get("valid_until"), "省略 expiry 必须保留原到期时间");
            List<Map<String, Object>> evidence = jdbc.queryForList(
                    "SELECT e.session_id, e.turn_id, e.message_id, e.source_block_id FROM ai_memory_evidence e"
                            + " JOIN ai_memory_item_evidence ie ON ie.evidence_id = e.id WHERE ie.item_row_id = ?",
                    String.valueOf(row.get("row_id")));
            assertEquals(1, evidence.size(), "新版本必须继承原版本的证据");
            assertEquals(sessionId, ((Number) evidence.get(0).get("session_id")).longValue());
            assertEquals(lease.turnId(), ((Number) evidence.get(0).get("turn_id")).longValue());
            assertEquals(lease.userMessageId(), ((Number) evidence.get(0).get("message_id")).longValue());
            assertEquals("user-message", evidence.get(0).get("source_block_id"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void longToolCreatedValuesRemainEditable() throws Exception {
        unlockVault();
        enableUseConsent();
        String original = "长".repeat(300);
        String revised = "改".repeat(400);
        ChatTurnService.Lease lease = beginTurn("记住这段长内容");
        try {
            ChatContextPlan plan = plan(lease, "记住这段长内容");
            Map<String, Object> created = stack.memoryCreateTool().execute(plan,
                    "{\"text\":\"" + original + "\",\"kind\":\"BACKGROUND\"}", true,
                    "记住这段长内容", lease.turnId(), lease.userMessageId());
            assertEquals("CREATED", created.get("status"), String.valueOf(created));
            String memoryId = String.valueOf(created.get("memoryId"));

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> updated = update(refreshed, "{\"memoryId\":\"" + memoryId
                    + "\",\"expectedVersion\":1,\"text\":\"" + revised + "\"}");
            assertEquals("UPDATED", updated.get("status"), String.valueOf(updated));
            assertEquals(2, updated.get("version"));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void sameTextInNotebookAndGlobalIsReportedAsAmbiguous() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, 0L);
        add("GOAL", "NOTEBOOK", 7L, "study.goal", "重复正文");
        add("PREFERENCE", "GLOBAL", 0L, "answer.order", "重复正文");
        ChatTurnService.Lease lease = beginTurn("更新重复正文");
        try {
            ChatContextPlan plan = plan(lease, "更新重复正文");
            Map<String, Object> result = update(plan, "{\"currentText\":\"重复正文\",\"text\":\"新的正文\"}");
            assertEquals("AMBIGUOUS", result.get("status"), String.valueOf(result));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE version = 2",
                    Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    private Map<String, Object> update(ChatContextPlan plan, String arguments) {
        return stack.memoryUpdateTool().execute(plan, arguments, true, "更新已有记忆");
    }

    private String add(String kind, String scopeType, long scopeId, String predicate, String value) {
        MemoryApiResult added = items.add(new MemoryItemService.AddInput(kind,
                scopeType, scopeId, predicate, predicate + ':' + value, value, null, true, op("add")));
        assertEquals(201, added.status(), value + " 没有落成: " + added.body());
        return String.valueOf(added.body().get("memoryId"));
    }

    private long vaultRevision() throws Exception {
        return wiring.memory().readEnvelope().orElseThrow().revision();
    }

    private ChatContextPlan plan(ChatTurnService.Lease lease, String userText) throws Exception {
        return stack.contexts().plan(lease, userText, List.of(), Map.of(), Map.of("use", true), 32000, true);
    }

    private ChatTurnService.Lease beginTurn(String userText) {
        ChatTurnService.BeginResult begun = wiring.turnService().begin(new ChatTurnService.BeginInput(
                com.drillnotebook.app.model.UuidV7.generate(System.currentTimeMillis()), 2, sessionId, userText,
                PASSWORD, List.of(), Map.of()));
        return begun.lease();
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
        return "update-tool-" + action + '-' + (++sequence);
    }
}
