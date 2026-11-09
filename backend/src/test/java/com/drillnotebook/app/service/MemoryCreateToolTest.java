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
import com.drillnotebook.app.repository.MemoryRepository;
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

class MemoryCreateToolTest {
    private static final String PASSWORD = "correct horse battery";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private long sessionId;
    private int sequence;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("create-tool-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        new AiConfigRepository(jdbc).upsertChat("custom", "https://example.invalid/v1", "stub-model", "enc", "{}",
                "{\"contextWindow\":32000}");
        sessionId = com.drillnotebook.app.repository.ChatLegacyWiring.ensureSession(jdbc);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void definitionRequiresTextAndKindWithOptionalScopeExpiry() {
        AiUpstreamClient.ToolDefinition definition = stack.memoryCreateTool().definition();
        assertEquals(MemoryCreateTool.NAME, definition.name());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertEquals(List.of("text", "kind"), definition.parameters().get("required"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals(4, properties.size());
        Map<?, ?> text = (Map<?, ?>) properties.get("text");
        assertEquals("string", text.get("type"));
        assertEquals(1, text.get("minLength"));
        assertEquals(MemoryCreateTool.MAX_TEXT_CODE_POINTS, text.get("maxLength"));
        Map<?, ?> kind = (Map<?, ?>) properties.get("kind");
        assertEquals(List.of("PREFERENCE", "GOAL", "BACKGROUND", "TASK"), kind.get("enum"));
    }

    @Test
    void malformedArgumentsAreRejected() {
        for (String raw : List.of("", "   ", "not-json", "[]", "{}", "{\"text\":\"x\"}", "{\"kind\":\"GOAL\"}",
                "{\"text\":\" \",\"kind\":\"GOAL\"}", "{\"text\":\"x\",\"kind\":\"OTHER\"}",
                "{\"text\":\"x\",\"kind\":\"GOAL\",\"scope\":\"ALL\"}", "{\"text\":\"x\",\"kind\":\"GOAL\",\"extra\":1}",
                "{\"text\":\"x\",\"kind\":\"GOAL\"} trailing",
                "{\"text\":\"x\",\"kind\":\"GOAL\",\"text\":\"y\"}",
                "{\"text\":\"" + "a".repeat(MemoryCreateTool.MAX_TEXT_CODE_POINTS + 1) + "\",\"kind\":\"GOAL\"}")) {
            assertEquals("INVALID_ARGUMENT", String.valueOf(
                    stack.memoryCreateTool().execute(null, raw, true, "记住").get("status")), raw);
        }
        assertEquals("INVALID_ARGUMENT", String.valueOf(stack.memoryCreateTool()
                .execute(null, "{\"text\":\"x\",\"kind\":\"GOAL\"}" + " ".repeat(4096), true, "记住").get("status")));
    }

    @Test
    void createsGlobalMemoryWithEvidenceAndRefreshesIndex() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：我每周三晚上复习");
        try {
            ChatContextPlan plan = plan(lease, "记住：我每周三晚上复习");
            long before = vaultRevision();
            Map<String, Object> result = create(plan, lease, "记住：我每周三晚上复习",
                    "{\"text\":\"我每周三晚上复习\",\"kind\":\"PREFERENCE\"}");
            assertEquals("CREATED", result.get("status"), String.valueOf(result));
            String memoryId = String.valueOf(result.get("memoryId"));
            assertEquals(1, result.get("version"));
            assertTrue(vaultRevision() > before, "写入必须推进 vault revision");

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_memory_item WHERE memory_id = ?", memoryId);
            assertEquals("ACTIVE", row.get("status"));
            assertEquals("GLOBAL", row.get("scope_type"));
            assertEquals(0L, ((Number) row.get("scope_id")).longValue());
            assertEquals("PREFERENCE", row.get("kind"));
            assertEquals("AUTO", row.get("origin"));
            assertEquals(1, ((Number) row.get("manual_protected")).intValue(),
                    "用户明确要求保存的记忆必须在来源会话删除后保留");

            List<Map<String, Object>> evidence = jdbc.queryForList(
                    "SELECT e.* FROM ai_memory_evidence e JOIN ai_memory_item_evidence ie ON ie.evidence_id = e.id"
                            + " JOIN ai_memory_item i ON i.row_id = ie.item_row_id WHERE i.memory_id = ?", memoryId);
            assertEquals(1, evidence.size());
            assertEquals(sessionId, ((Number) evidence.get(0).get("session_id")).longValue());
            assertEquals(lease.turnId(), ((Number) evidence.get(0).get("turn_id")).longValue());
            assertEquals(lease.userMessageId(), ((Number) evidence.get(0).get("message_id")).longValue());
            assertEquals("user-message", evidence.get(0).get("source_block_id"));

            MemoryVaultService vault = stack.vault();
            MemoryRepository.FactRow fact = wiring.memory().findCurrent(vault.currentKey().orElseThrow().vaultId(),
                    memoryId).orElseThrow();
            String payloadJson = stack.crypto().decrypt(vault.currentKey().orElseThrow(), "ai_memory_item",
                    fact.rowId(), fact.version(), fact.payloadCipher(), fact.cryptoMeta());
            Map<?, ?> payload = new ObjectMapper().readValue(payloadJson, Map.class);
            assertEquals("我每周三晚上复习", payload.get("value"));
            assertEquals("AI_TOOL", payload.get("createdBy"));
            assertEquals("other", payload.get("predicate"));
            assertFalse(payloadJson.contains("weekly"), "载荷不能夹带模型参数");

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> listed = stack.memoryListTool().execute(refreshed, "{}", true,
                    "记住：我每周三晚上复习");
            assertEquals("OK", listed.get("status"), String.valueOf(listed));
            assertTrue(listed.toString().contains("我每周三晚上复习"), "写入后同轮列表必须能看到新版本");
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void deletingTheSourceSessionKeepsTheUserRequestedMemory() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：我是男的");
        ChatContextPlan plan = plan(lease, "记住：我是男的");
        Map<String, Object> created = create(plan, lease, "记住：我是男的",
                "{\"text\":\"我是男的\",\"kind\":\"BACKGROUND\"}");
        assertEquals("CREATED", created.get("status"), String.valueOf(created));
        String memoryId = String.valueOf(created.get("memoryId"));
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of("好", null, "stop"), null);

        wiring.sessions().delete(sessionId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, manual_protected FROM ai_memory_item WHERE memory_id = ?", memoryId);
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(1, ((Number) row.get("manual_protected")).intValue());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_memory_evidence WHERE session_id = ?", Integer.class, sessionId),
                "来源会话删除后只断证据");
    }

    @Test
    void normalizedDuplicateDoesNotCreateASecondRow() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：Hello World");
        try {
            ChatContextPlan plan = plan(lease, "记住：Hello World");
            Map<String, Object> first = create(plan, lease, "记住：Hello World",
                    "{\"text\":\"Hello   World\",\"kind\":\"PREFERENCE\"}");
            assertEquals("CREATED", first.get("status"), String.valueOf(first));
            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> second = create(refreshed, lease, "记住：hello world",
                    "{\"text\":\"hello world\",\"kind\":\"PREFERENCE\"}");
            assertEquals("DUPLICATE", second.get("status"), String.valueOf(second));
            assertEquals(first.get("memoryId"), second.get("memoryId"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'",
                    Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void explicitRememberRequestRevivesAFamilyThatWasDeletedBefore() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease first = beginTurn("请你记住，我是男的");
        String memoryId;
        try {
            ChatContextPlan plan = plan(first, "请你记住，我是男的");
            Map<String, Object> created = create(plan, first, "请你记住，我是男的",
                    "{\"text\":\"用户是男性。\",\"kind\":\"BACKGROUND\"}");
            assertEquals("CREATED", created.get("status"), String.valueOf(created));
            memoryId = String.valueOf(created.get("memoryId"));
        } finally {
            wiring.turnService().abandon(first, "test-cleanup");
        }
        assertEquals(204, stack.items().delete(memoryId, 1, "delete-before-recreate").status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_suppression"
                + " WHERE suppression_kind = 'FAMILY'", Integer.class), "删除应写下家族抑制");

        ChatTurnService.Lease second = beginTurn("请你记住，我是男的");
        try {
            ChatContextPlan plan = plan(second, "请你记住，我是男的");
            Map<String, Object> recreated = create(plan, second, "请你记住，我是男的",
                    "{\"text\":\"用户是男性。\",\"kind\":\"BACKGROUND\"}");
            assertEquals("CREATED", recreated.get("status"), String.valueOf(recreated));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_suppression"
                    + " WHERE suppression_kind = 'FAMILY'", Integer.class),
                    "用户再次明确要求记住时，必须解除这一族的抑制，不能写出一条看不见的记忆");

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> listed = stack.memoryListTool().execute(refreshed, "{}", true,
                    "请你记住，我是男的");
            assertEquals("OK", listed.get("status"), String.valueOf(listed));
            assertTrue(listed.toString().contains("用户是男性。"), "重新明确要求保存的记忆必须能被列表看到");
        } finally {
            wiring.turnService().abandon(second, "test-cleanup");
        }
    }

    @Test
    void explicitRememberRequestRevivesAnAlreadyExistingHiddenDuplicate() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease first = beginTurn("请你记住，我是男的");
        String memoryId;
        try {
            ChatContextPlan plan = plan(first, "请你记住，我是男的");
            Map<String, Object> created = create(plan, first, "请你记住，我是男的",
                    "{\"text\":\"用户是男性。\",\"kind\":\"BACKGROUND\"}");
            memoryId = String.valueOf(created.get("memoryId"));
        } finally {
            wiring.turnService().abandon(first, "test-cleanup");
        }
        assertEquals(204, stack.items().delete(memoryId, 1, "delete-before-manual-ghost").status());
        MemoryApiResult ghost = stack.items().add(new MemoryItemService.AddInput("BACKGROUND", "GLOBAL", 0L,
                "other", null, "用户是男性。", null, false, "manual-ghost"));
        assertEquals(201, ghost.status(), String.valueOf(ghost.body()));
        String ghostId = String.valueOf(ghost.body().get("memoryId"));

        ChatTurnService.Lease second = beginTurn("请你记住，我是男的");
        try {
            ChatContextPlan plan = plan(second, "请你记住，我是男的");
            Map<String, Object> result = create(plan, second, "请你记住，我是男的",
                    "{\"text\":\"用户是男性。\",\"kind\":\"BACKGROUND\"}");
            assertEquals("DUPLICATE", result.get("status"), String.valueOf(result));
            assertEquals(ghostId, result.get("memoryId"));

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> listed = stack.memoryListTool().execute(refreshed, "{}", true,
                    "请你记住，我是男的");
            assertTrue(listed.toString().contains("用户是男性。"),
                    "明确要求记住时，已经存在的隐藏重复行也要重新可见");
        } finally {
            wiring.turnService().abandon(second, "test-cleanup");
        }
    }

    @Test
    void suppressedFamilyWithoutAnExplicitRememberRequestDoesNotCreateAGhostRow() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease first = beginTurn("请你记住，我是男的");
        String memoryId;
        try {
            ChatContextPlan plan = plan(first, "请你记住，我是男的");
            Map<String, Object> created = create(plan, first, "请你记住，我是男的",
                    "{\"text\":\"用户是男性。\",\"kind\":\"BACKGROUND\"}");
            memoryId = String.valueOf(created.get("memoryId"));
        } finally {
            wiring.turnService().abandon(first, "test-cleanup");
        }
        assertEquals(204, stack.items().delete(memoryId, 1, "delete-before-implicit-create").status());

        ChatTurnService.Lease second = beginTurn("我是男的");
        try {
            ChatContextPlan plan = plan(second, "我是男的");
            Map<String, Object> result = create(plan, second, "我是男的",
                    "{\"text\":\"用户是男性。\",\"kind\":\"BACKGROUND\"}");
            assertEquals("SUPPRESSED", result.get("status"), String.valueOf(result));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class),
                    "没有明确要求记住时，不能写出一条被抑制、读路径看不见的行");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_suppression"
                    + " WHERE suppression_kind = 'FAMILY'", Integer.class), "抑制不能被模型自行解除");
        } finally {
            wiring.turnService().abandon(second, "test-cleanup");
        }
    }

    @Test
    void notebookScopeDefaultsToCurrentAndExplicitGlobalIsAllowed() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, 0L);
        ChatTurnService.Lease lease = beginTurn("记住：本册重点看进程");
        try {
            ChatContextPlan plan = plan(lease, "记住：本册重点看进程");
            Map<String, Object> notebook = create(plan, lease, "记住：本册重点看进程",
                    "{\"text\":\"本册重点看进程\",\"kind\":\"GOAL\"}");
            assertEquals("CREATED", notebook.get("status"), String.valueOf(notebook));
            Map<String, Object> notebookRow = jdbc.queryForMap("SELECT scope_type, scope_id FROM ai_memory_item"
                    + " WHERE memory_id = ?", String.valueOf(notebook.get("memoryId")));
            assertEquals("NOTEBOOK", notebookRow.get("scope_type"));
            assertEquals(7L, ((Number) notebookRow.get("scope_id")).longValue());

            ChatContextPlan refreshed = stack.contexts().refreshAfterMemoryWrite(plan);
            Map<String, Object> global = create(refreshed, lease, "记住：我喜欢先看例子",
                    "{\"text\":\"我喜欢先看例子\",\"kind\":\"PREFERENCE\",\"scope\":\"GLOBAL\"}");
            assertEquals("CREATED", global.get("status"), String.valueOf(global));
            Map<String, Object> globalRow = jdbc.queryForMap("SELECT scope_type, scope_id FROM ai_memory_item"
                    + " WHERE memory_id = ?", String.valueOf(global.get("memoryId")));
            assertEquals("GLOBAL", globalRow.get("scope_type"));
            assertEquals(0L, ((Number) globalRow.get("scope_id")).longValue());
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }

        wiring.metadata().changeScope(sessionId, "GLOBAL", null, 0L);
        ChatTurnService.Lease globalLease = beginTurn("记住：全局偏好");
        try {
            ChatContextPlan globalPlan = plan(globalLease, "记住：全局偏好");
            Map<String, Object> rejected = create(globalPlan, globalLease, "记住：全局偏好",
                    "{\"text\":\"全局偏好\",\"kind\":\"PREFERENCE\",\"scope\":\"NOTEBOOK\"}");
            assertEquals("INVALID_ARGUMENT", rejected.get("status"), "全局会话不能把记忆写进笔记本");
        } finally {
            wiring.turnService().abandon(globalLease, "test-cleanup");
        }
    }

    @Test
    void stalePlanIsRejectedBeforeAnyWrite() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：我喜欢先看例子");
        ChatContextPlan plan = plan(lease, "记住：我喜欢先看例子");
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of("好", null, "stop"), null);

        assertEquals("STALE", create(plan, lease, "记住：我喜欢先看例子",
                "{\"text\":\"我喜欢先看例子\",\"kind\":\"PREFERENCE\"}").get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
    }

    @Test
    void lockedVaultReturnsUnavailable() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：我喜欢先看例子");
        try {
            ChatContextPlan plan = plan(lease, "记住：我喜欢先看例子");
            stack.vault().lock();
            Map<String, Object> locked = create(plan, lease, "记住：我喜欢先看例子",
                    "{\"text\":\"我喜欢先看例子\",\"kind\":\"PREFERENCE\"}");
            assertEquals("UNAVAILABLE", locked.get("status"));
            assertEquals("LOCKED", locked.get("availability"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void missingUseConsentReturnsDenied() throws Exception {
        unlockVault();
        ChatTurnService.Lease lease = beginTurn("记住：我喜欢先看例子");
        try {
            stack.policy().revokeConsent(MemoryPolicyService.PURPOSE_USE);
            ChatContextPlan plan = plan(lease, "记住：我喜欢先看例子");
            Map<String, Object> denied = create(plan, lease, "记住：我喜欢先看例子",
                    "{\"text\":\"我喜欢先看例子\",\"kind\":\"PREFERENCE\"}");
            assertEquals("UNAVAILABLE", denied.get("status"));
            assertEquals("DENIED", denied.get("availability"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void sensitiveCredentialsAndPastExpiryAreRejectedWithoutRows() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：我的邮箱是 a@example.com");
        try {
            ChatContextPlan plan = plan(lease, "记住：我的邮箱是 a@example.com");
            Map<String, Object> sensitive = create(plan, lease, "记住：我的邮箱是 a@example.com",
                    "{\"text\":\"我的邮箱是 a@example.com\",\"kind\":\"BACKGROUND\"}");
            assertEquals("REJECTED", sensitive.get("status"));
            assertEquals("SENSITIVE", sensitive.get("reason"));

            Map<String, Object> credential = create(plan, lease, "记住：密码: Abcdef123",
                    "{\"text\":\"密码: Abcdef123\",\"kind\":\"TASK\"}");
            assertEquals("REJECTED", credential.get("status"));
            assertEquals("CREDENTIAL", credential.get("reason"));

            Map<String, Object> expiry = create(plan, lease, "记住：下周复习",
                    "{\"text\":\"下周复习\",\"kind\":\"GOAL\",\"expiry\":\"2000-01-01T00:00:00Z\"}");
            assertEquals("INVALID_ARGUMENT", expiry.get("status"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item", Integer.class));
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    @Test
    void futureExpiryIsStoredInFixedWidthUtc() throws Exception {
        unlockVault();
        enableUseConsent();
        ChatTurnService.Lease lease = beginTurn("记住：下个月复习");
        try {
            ChatContextPlan plan = plan(lease, "记住：下个月复习");
            Map<String, Object> created = create(plan, lease, "记住：下个月复习",
                    "{\"text\":\"下个月复习\",\"kind\":\"GOAL\",\"expiry\":\"2099-01-01T08:30:00+08:00\"}");
            assertEquals("CREATED", created.get("status"), String.valueOf(created));
            String validUntil = jdbc.queryForObject("SELECT valid_until FROM ai_memory_item WHERE memory_id = ?",
                    String.class, String.valueOf(created.get("memoryId")));
            assertEquals("2099-01-01T00:30:00.000Z", validUntil);
        } finally {
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    private Map<String, Object> create(ChatContextPlan plan, ChatTurnService.Lease lease, String userText,
                                       String arguments) {
        return stack.memoryCreateTool().execute(plan, arguments, true, userText, lease.turnId(),
                lease.userMessageId());
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
        return "create-tool-" + action + '-' + (++sequence);
    }
}
