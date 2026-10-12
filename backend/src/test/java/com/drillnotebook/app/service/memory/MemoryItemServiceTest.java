package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryItemService.AddInput;
import com.drillnotebook.app.service.memory.MemoryItemService.EditInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 手动管理一条本地加密记忆的完整链路（执行方案 P2 退出条件）：
 * 明文只回到刚提交它的用户手里，落库全是密文与派生键；版本 CAS、停用/启用、分页与本地检索、
 * 删除的遗忘屏障与抑制都由服务层把关，不靠前端禁用按钮。
 */
class MemoryItemServiceTest {
    private static final String VALUE = "以后解释算法先给例子";
    private static final String PREDICATE = "answer.style";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryCryptoService crypto;
    private MemoryRepository memory;
    private MemoryVaultService vault;
    private MemoryPolicyService policy;
    private MemoryItemService items;
    private String vaultId;
    private long sessionId;
    private long nextTurnId = 700;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-items-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        memory = new MemoryRepository(dataSource, immediate);
        MemoryOperationRepository operations = new MemoryOperationRepository(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        vault = new MemoryVaultService(crypto, memory, operations, metadata);
        policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate), memory,
                new MemoryJobRepository(dataSource, immediate), new AiConfigRepository(jdbc),
                new NotebookRepository(jdbc, new ObjectMapper()), vault);
        items = new MemoryItemService(crypto, memory, vault, policy, operations,
                new MemoryProposalRepository(dataSource, immediate),
                new MemorySuppressionRepository(dataSource, immediate),
                new MemoryJobRepository(dataSource, immediate), metadata);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    // ---- 新增 ----

    @Test
    void manualAddKeepsOnlyCipherAndKeysInTheDatabase() throws Exception {
        createFingerprintVault();
        MemoryApiResult created = items.add(add(PREDICATE, VALUE));
        assertEquals(201, created.status());
        String memoryId = (String) created.body().get("memoryId");
        assertEquals(VALUE, created.body().get("value"), "刚提交的人当然看得见自己写的正文");
        assertEquals(1, created.body().get("version"));
        assertEquals("ACTIVE", created.body().get("status"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_memory_item WHERE memory_id = ?", memoryId);
        String stored = String.valueOf(row.values());
        assertFalse(stored.contains(VALUE), "表里任何一列都不该有明文");
        assertFalse(stored.contains(PREDICATE));
        assertNotNull(row.get("predicate_key_hmac"));
        assertNotNull(row.get("family_key_hmac"));
        assertEquals("MANUAL", row.get("origin"));
        assertEquals(1, ((Number) row.get("manual_protected")).intValue());

        jdbc.execute("PRAGMA wal_checkpoint(FULL)");
        byte[] db = Files.readAllBytes(appRoot.resolve("data/study.db"));
        Path walPath = appRoot.resolve("data/study.db-wal");
        byte[] wal = Files.exists(walPath) ? Files.readAllBytes(walPath) : new byte[0];
        assertFalse(contains(db, VALUE), "明文字节不得出现在主库文件里");
        assertFalse(contains(wal, VALUE), "明文字节不得出现在 WAL 里");

        MemoryApiResult read = items.get(memoryId);
        assertEquals(200, read.status());
        assertEquals(VALUE, read.body().get("value"));
        assertEquals(List.of(), read.body().get("sources"));
    }

    @Test
    void sameFamilyAddIsRefusedWithMetadataOnlyCandidates() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        MemoryApiResult conflict = items.add(add(PREDICATE, "改成先给结论"));
        assertEquals(409, conflict.status());
        assertEquals("MEMORY_FAMILY_CONFLICT", conflict.body().get("code"));
        List<?> candidates = (List<?>) ((Map<?, ?>) conflict.body().get("details")).get("candidates");
        assertEquals(1, candidates.size());
        Map<?, ?> candidate = (Map<?, ?>) candidates.get(0);
        assertEquals(memoryId, candidate.get("memoryId"));
        assertEquals(1, candidate.get("version"));
        assertFalse(String.valueOf(candidates).contains(VALUE), "冲突响应不回别人的正文");
    }

    @Test
    void credentialsAreRefusedEvenWhenTheUserInsists() throws Exception {
        createFingerprintVault();

        MemoryApiResult key = items.add(add("contact.key", "我的 api key 是 sk-abcdefghijklmnopqrstuvwxyz"));
        assertEquals(422, key.status());
        assertEquals("MEMORY_SENSITIVE_CREDENTIAL", key.body().get("code"));
        assertEquals(422, items.add(add("contact.key", "密码：hunter2hunter2")).status());
        assertEquals(422, items.add(add("contact.card", "6222021234567890123")).status());

        MemoryApiResult unconfirmed = items.add(add("contact.email", "我的邮箱是 me@example.com"));
        assertEquals(409, unconfirmed.status());
        assertEquals("MEMORY_SENSITIVE_CONFIRMATION_REQUIRED", unconfirmed.body().get("code"));
        assertEquals(0L, count("ai_memory_item"));

        MemoryApiResult confirmed = items.add(new AddInput("BACKGROUND", "GLOBAL", 0L, "contact.email", null,
                "我的邮箱是 me@example.com", null, true, null));
        assertEquals(201, confirmed.status(), "敏感但用户明确确认的人工条目可以存（§5.3）");
    }

    @Test
    void malformedRequestsAreRejectedBeforeAnyWrite() throws Exception {
        createFingerprintVault();

        assertEquals("MEMORY_KIND_INVALID", items.add(new AddInput("NOTE", "GLOBAL", 0L, "p", null, "v",
                null, null, null)).body().get("code"));
        assertEquals("MEMORY_SCOPE_INVALID", items.add(new AddInput("GOAL", "SPACE", 0L, "p", null, "v",
                null, null, null)).body().get("code"));
        assertEquals("MEMORY_PREDICATE_INVALID", items.add(add("  ", VALUE)).body().get("code"));
        assertEquals("MEMORY_VALUE_INVALID", items.add(add(PREDICATE, "雅".repeat(1001))).body().get("code"));
        assertEquals("MEMORY_EXPIRY_INVALID", items.add(new AddInput("TASK", "GLOBAL", 0L, "p", null, "v",
                "昨天", null, null)).body().get("code"));
        assertEquals("MEMORY_EXPIRY_IN_PAST", items.add(new AddInput("TASK", "GLOBAL", 0L, "p", null, "v",
                Instant.now().minus(1, ChronoUnit.DAYS).toString(), null, null)).body().get("code"));
        assertEquals("MEMORY_SCOPE_INVALID", items.add(new AddInput("GOAL", "NOTEBOOK", 424242L, "p", null, "v",
                null, null, null)).body().get("code"));
        assertEquals(0L, count("ai_memory_item"), "被拒的请求一条都不写");
    }

    @Test
    void lockedVaultRefusesEveryRouteWith423() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        vault.lock();

        MemoryApiResult refusedAdd = items.add(add("answer.length", "短一点"));
        assertEquals(423, refusedAdd.status());
        assertEquals("MEMORY_LOCKED", refusedAdd.body().get("code"));
        assertEquals(423, items.get(memoryId).status());
        assertEquals(423, items.list(null, null, null, null, null, null).status());
        assertEquals(423, items.edit(memoryId, new EditInput(1, null, null, "长一点", null, null)).status());
        assertEquals(423, items.delete(memoryId, 1, false, null).status());
    }

    // ---- 改与停用 ----

    @Test
    void editNeedsTheVersionItSawAndReturnsOnlyMetadataOnConflict() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        MemoryApiResult missingVersion = items.edit(memoryId, new EditInput(null, null, null, "长一点", null, null));
        assertEquals(400, missingVersion.status());
        assertEquals("MEMORY_VERSION_REQUIRED", missingVersion.body().get("code"));

        MemoryApiResult conflict = items.edit(memoryId, new EditInput(9, null, null, "长一点", null, null));
        assertEquals(409, conflict.status());
        assertEquals("MEMORY_VERSION_CONFLICT", conflict.body().get("code"));
        assertEquals(1, ((Map<?, ?>) conflict.body().get("details")).get("version"));
        assertFalse(String.valueOf(conflict.body()).contains(VALUE), "冲突响应不回正文");
        assertEquals(400, items.edit(memoryId, new EditInput(1, null, null, null, null, null)).status());
    }

    @Test
    void contentEditCreatesTheNextVersionAndKeepsFamilyAndObservation() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        MemoryApiResult edited = items.edit(memoryId, new EditInput(1, null, null, "先给反例再给正例", null, null));
        assertEquals(200, edited.status());
        assertEquals(2, edited.body().get("version"));
        assertEquals("先给反例再给正例", edited.body().get("value"));
        assertEquals(PREDICATE, edited.body().get("predicate"), "没改谓词就沿用原来的");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, status, family_key_hmac,"
                + " predicate_key_hmac, observed_at FROM ai_memory_item WHERE memory_id = ? ORDER BY version",
                memoryId);
        assertEquals(2, rows.size());
        assertEquals("SUPERSEDED", rows.get(0).get("status"));
        assertEquals("ACTIVE", rows.get(1).get("status"));
        assertEquals(rows.get(0).get("family_key_hmac"), rows.get(1).get("family_key_hmac"));
        assertEquals(rows.get(0).get("predicate_key_hmac"), rows.get(1).get("predicate_key_hmac"));
        assertEquals(rows.get(0).get("observed_at"), rows.get(1).get("observed_at"), "来源时间跟着事实走，不改成现在");

        assertEquals("MEMORY_FAMILY_CONFLICT", items.add(add(PREDICATE, "又一条同族")).body().get("code"));
    }

    @Test
    void disableAndReEnableShareOneVersionCas() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        assertEquals("DISABLED", items.edit(memoryId, new EditInput(1, null, null, null, null, "DISABLED"))
                .body().get("status"));
        assertEquals(404, items.get(memoryId).status(), "停用后没有生效版本");
        assertEquals(200, items.edit(memoryId, new EditInput(1, null, null, null, null, "disabled")).status(),
                "模式大小写不影响判定");
        assertEquals(false, items.edit(memoryId, new EditInput(1, null, null, null, null, "DISABLED"))
                .body().get("changed"), "重复停用是幂等无变化");

        assertEquals("ACTIVE", items.edit(memoryId, new EditInput(1, null, null, null, null, "ACTIVE"))
                .body().get("status"));
        assertEquals(200, items.get(memoryId).status());
        assertEquals(400, items.edit(memoryId, new EditInput(1, null, null, null, null, "EXPIRED")).status());
        assertEquals(1L, count("ai_memory_item"), "停用与启用都不造新版");
    }

    @Test
    void corruptedRowIsIsolatedInsteadOfBreakingTheList() throws Exception {
        createFingerprintVault();
        String good = (String) items.add(add("answer.length", "回答短一点")).body().get("memoryId");
        String broken = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        String rowId = currentRowId(broken);
        jdbc.update("UPDATE ai_memory_item SET payload_cipher = 'AAAA' WHERE row_id = ?", rowId);

        MemoryApiResult read = items.get(broken);
        assertEquals(200, read.status(), "坏一行不该让整个读取失败");
        assertEquals(true, read.body().get("corrupt"));
        assertNull(read.body().get("value"));

        List<?> listed = (List<?>) items.list(null, null, null, null, null, null).body().get("items");
        assertEquals(2, listed.size());
        assertTrue(listed.stream().anyMatch(entry -> Boolean.TRUE.equals(((Map<?, ?>) entry).get("corrupt"))));
        assertEquals("回答短一点", items.get(good).body().get("value"));
    }

    // ---- 列表与检索 ----

    @Test
    void listPagesByKeysetAndSearchesDecryptedText() throws Exception {
        createFingerprintVault();
        String first = (String) items.add(add("answer.style", VALUE)).body().get("memoryId");
        items.add(add("goal.language", "我在准备雅思"));
        items.add(add("background.course", "每周两次课"));

        MemoryApiResult all = items.list(null, null, null, null, null, null);
        assertEquals(200, all.status());
        assertEquals(3, ((List<?>) all.body().get("items")).size());
        assertEquals(false, all.body().get("hasMore"));
        assertNotNull(all.body().get("nextCursor"));

        MemoryApiResult page = items.list(null, null, null, null, 2, null);
        assertEquals(2, ((List<?>) page.body().get("items")).size());
        assertEquals(true, page.body().get("hasMore"));
        MemoryApiResult next = items.list(null, null, null, null, 2, (String) page.body().get("nextCursor"));
        assertEquals(1, ((List<?>) next.body().get("items")).size(), "第二页拿到剩下的那条，不重复也不丢");
        assertEquals(false, next.body().get("hasMore"));

        List<?> matched = (List<?>) items.list(null, null, null, "雅思", 10, null).body().get("items");
        assertEquals(1, matched.size());
        assertEquals("我在准备雅思", ((Map<?, ?>) matched.get(0)).get("value"));
        assertEquals(1, ((List<?>) items.list("GLOBAL", 0L, null, "雅思", 10, null).body().get("items")).size());
        assertEquals(0, ((List<?>) items.list("NOTEBOOK", 424242L, "ACTIVE", null, 10, null)
                .body().get("items")).size(), "没有这条作用域就不返回别人的内容");
        assertEquals(50, items.list(null, null, null, null, 500, null).body().get("limit"), "每页最多 50");

        assertEquals(400, items.list("SPACE", null, null, null, null, null).status());
        assertEquals(400, items.list(null, null, "DONE", null, null, null).status());
        assertEquals(400, items.list(null, null, null, null, null, "不是游标").status());
        assertEquals(400, items.list("NOTEBOOK", null, "ACTIVE", null, 10, null).status());

        items.edit(first, new EditInput(1, null, null, null, null, "DISABLED"));
        List<?> disabled = (List<?>) items.list(null, null, "DISABLED", null, 10, null).body().get("items");
        assertEquals(1, disabled.size());
        assertEquals(first, ((Map<?, ?>) disabled.get(0)).get("memoryId"));
        List<?> active = (List<?>) items.list(null, null, null, null, 10, null).body().get("items");
        assertEquals(2, active.size());
    }

    // ---- 删除 ----

    @Test
    void forgetfulDeleteWipesTheChainAndRaisesTheBarriers() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        long turn = insertTurn();
        memory.attachEvidence(new MemoryRepository.EvidenceDraft("ev-del", vaultId, sessionId, turn, null, "b1",
                "src-del", "密文原话", "{}", MemoryTimes.now()), currentRowId(memoryId));
        insertProposal("prop-del", memoryId);
        MemoryJobRepository jobs = new MemoryJobRepository(dataSource, new ImmediateTransaction(dataSource));
        String jobId = jobs.enqueue(new MemoryJobRepository.JobDraft(MemoryJobRepository.Kind.EXTRACT, sessionId,
                1, turn, 1, "GLOBAL", 0, "UTC", 1, 1, "fp", "extract-v1", "input-1", MemoryTimes.now())).jobId();
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                        + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                        + " VALUES (?, 1, ?, 1, 1, 'ctx', '{}', 20, ?)", sessionId, turn, MemoryTimes.now());
        long epochBefore = memory.readVault(vaultId).orElseThrow().epoch();

        MemoryApiResult deleted = items.delete(memoryId, 1, true, "op-del-1");
        assertEquals(204, deleted.status());
        assertTrue(deleted.body().isEmpty(), "204 不带正文");

        assertEquals(0L, count("ai_memory_item"), "整条版本链都删掉");
        assertEquals(0L, count("ai_memory_evidence"), "失去引用的证据一起删");
        assertEquals(0L, count("ai_memory_item_evidence"));
        assertEquals("STALE", jdbc.queryForObject("SELECT status FROM ai_memory_proposal", String.class),
                "指向被删记忆的建议置 STALE，不留在 PENDING");
        assertEquals(3L, count("ai_memory_suppression"), "family + predicate + 来源");
        assertEquals(epochBefore + 1, memory.readVault(vaultId).orElseThrow().epoch(), "遗忘推 epoch");
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM ai_memory_job WHERE id = ?",
                String.class, jobId));
        assertEquals(0L, count("ai_session_context"), "旧 epoch 的摘要检查点作废");
        assertEquals(turn, scanWatermark("memory_scan_after_turn"), "会话水位推到当前边界");
        assertEquals(turn, scanWatermark("summary_scan_after_turn"));
        assertTrue(policy.policyOf(policy.current()).memoryHistoryFloor() >= turn, "全局遗忘边界单调推进");

        assertEquals(200, items.list(null, null, null, null, null, null).status(), "删空后列表仍可用");
    }

    @Test
    void plainDeleteLeavesScanBoundariesAlone() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");
        insertTurn();
        jdbc.update("UPDATE ai_chat_session SET memory_scan_after_turn = 5, summary_scan_after_turn = 5"
                + " WHERE id = ?", sessionId);

        assertEquals(204, items.delete(memoryId, 1, false, null).status());

        assertEquals(0L, count("ai_memory_item"));
        assertEquals(5L, scanWatermark("memory_scan_after_turn"), "不带 forget 的删除不动扫描水位");
        assertEquals(0L, policy.policyOf(policy.current()).memoryHistoryFloor());
        assertEquals(2L, memory.readVault(vaultId).orElseThrow().epoch());
    }

    @Test
    void deleteIsIdempotentAndReplaysItsOperationId() throws Exception {
        createFingerprintVault();
        String memoryId = (String) items.add(add(PREDICATE, VALUE)).body().get("memoryId");

        assertEquals(204, items.delete("mem-从未存在", 1, false, null).status(), "不存在按幂等 204");

        assertEquals(204, items.delete(memoryId, 1, false, "op-del-2").status());
        long epochAfterDelete = memory.readVault(vaultId).orElseThrow().epoch();
        assertEquals(204, items.delete(memoryId, 1, false, "op-del-2").status());
        assertEquals(epochAfterDelete, memory.readVault(vaultId).orElseThrow().epoch(), "重放不再推一次 epoch");

        String second = (String) items.add(add("goal.language", "我在准备雅思")).body().get("memoryId");
        assertEquals(409, items.delete(second, 99, false, null).status());
        assertEquals(1L, count("ai_memory_item"), "版本冲突时一条都不删");
        assertEquals(400, items.delete(second, null, false, null).status());
    }

    // ---- 状态计数 ----

    @Test
    void statusReportsCountsAndPauseReasonWithoutText() throws Exception {
        createFingerprintVault();
        items.add(add(PREDICATE, VALUE));

        MemoryApiResult status = items.status();
        assertEquals(200, status.status());
        assertEquals(1L, status.body().get("activeFacts"));
        assertEquals(0L, status.body().get("pendingFacts"));
        assertEquals(2000, ((Map<?, ?>) status.body().get("limits")).get("activeFacts"));
        assertEquals(Boolean.TRUE, status.body().get("unlocked"));
        MemoryPolicyService.Eligibility extraction = (MemoryPolicyService.Eligibility) status.body().get("extraction");
        assertEquals(MemoryPolicyService.Gate.MEMORY_DISABLED, extraction.gate(), "总开关先拦，暂停原因要看得见");
        assertFalse(String.valueOf(status.body()).contains(VALUE), "状态响应不含正文");
    }

    // ---- helpers ----

    private void createFingerprintVault() throws SQLException {
        assertEquals(201, vault.create("fingerprint", null, null).status());
        vaultId = memory.readEnvelope().orElseThrow().vaultId();
    }

    private static AddInput add(String predicate, String value) {
        return new AddInput("PREFERENCE", "GLOBAL", 0L, predicate, null, value, null, null, null);
    }

    private String currentRowId(String memoryId) {
        return jdbc.queryForObject("SELECT row_id FROM ai_memory_item WHERE memory_id = ? AND status = 'ACTIVE'",
                String.class, memoryId);
    }

    private long insertTurn() {
        long id = nextTurnId++;
        jdbc.update("INSERT INTO ai_chat_turn(id, request_id, session_id, status, request_meta_cipher,"
                        + " request_meta_crypto, context_generation, source_scope_type, source_scope_id,"
                        + " source_timezone, runtime_generation, started_at)"
                        + " VALUES (?, ?, ?, 'COMPLETED', 'm', '{}', 1, 'GLOBAL', 0, 'UTC', 'gen-test', ?)",
                id, "req-" + id, sessionId, MemoryTimes.now());
        return id;
    }

    private void insertProposal(String id, String targetMemoryId) {
        jdbc.update("INSERT INTO ai_memory_proposal(id, vault_id, session_id, target_memory_id, target_version,"
                        + " operation, status, payload_cipher, crypto_meta, dedup_hmac, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, 1, 'REPLACE_PROPOSAL', 'PENDING', 'p', '{}', ?, ?, ?)",
                id, vaultId, sessionId, targetMemoryId, "dedup-" + id, MemoryTimes.now(), MemoryTimes.now());
    }

    private long scanWatermark(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ai_chat_session WHERE id = ?", Long.class, sessionId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int start = 0; start + target.length <= haystack.length; start++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (haystack[start + offset] != target[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
