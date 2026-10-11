package com.drillnotebook.app.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * schema9 的约束门禁：唯一键、CHECK、部分索引与级联删除都要有失败测试撑着，
 * 后续包（P2/P3/P6）才不能靠服务层自觉。
 */
class Schema9ConstraintTest {
    private static final String NOW = "2026-10-11T00:00:00.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-constraints-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    // ---- ai_chat_turn ----

    @Test
    void onlyOneRunningTurnPerSession() {
        long session = session("会话一");
        insertTurn(session, "req-running-1", "RUNNING", "GLOBAL", 0);
        assertRejected("同一会话不能有第二个 RUNNING", () ->
                insertTurn(session, "req-running-2", "RUNNING", "GLOBAL", 0));

        // 不同会话可以并行 RUNNING；上一轮进入终态后同会话也能再来一轮
        long other = session("会话二");
        insertTurn(other, "req-running-3", "RUNNING", "GLOBAL", 0);
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED' WHERE request_id = 'req-running-1'");
        insertTurn(session, "req-running-4", "RUNNING", "GLOBAL", 0);
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"));
    }

    @Test
    void requestIdIsTheIdempotencyKey() {
        long session = session("会话一");
        insertTurn(session, "req-dup", "RUNNING", "GLOBAL", 0);
        long second = session("会话二");
        assertRejected("重复 requestId 必须被唯一键挡住", () ->
                insertTurn(second, "req-dup", "RUNNING", "GLOBAL", 0));
    }

    @Test
    void sourceScopeIdIsTiedToScopeType() {
        long session = session("会话一");
        assertRejected("NOTEBOOK 作用域必须有正数 scope_id", () ->
                insertTurn(session, "req-scope-bad-1", "RUNNING", "NOTEBOOK", 0));
        assertRejected("GLOBAL 作用域的 scope_id 必须是 0", () ->
                insertTurn(session, "req-scope-bad-2", "RUNNING", "GLOBAL", 7));
        insertTurn(session, "req-scope-notebook", "RUNNING", "NOTEBOOK", 3);
        insertTurn(session, "req-scope-global", "COMPLETED", "GLOBAL", 0);
    }

    @Test
    void unknownStatusIsRejectedButTombstoneNeedsDeletionTime() {
        long session = session("会话一");
        assertRejected("status 必须在枚举内", () -> insertTurn(session, "req-bad-status", "CANCELLED", "GLOBAL", 0));

        // 未清理的轮次必须带 request_meta；清理后允许为空（墓碑）
        assertRejected("非墓碑轮次不能没有 request_meta", () -> jdbc.update("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, context_generation,
                    source_scope_type, source_scope_id, source_timezone, runtime_generation, started_at)
                VALUES ('req-no-meta', ?, 'RUNNING', 1, 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', ?)
                """, session, NOW));
        jdbc.update("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, context_generation,
                    source_scope_type, source_scope_id, source_timezone, runtime_generation,
                    started_at, content_deleted_at)
                VALUES ('req-tombstone', ?, 'COMPLETED', 1, 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', ?, ?)
                """, session, NOW, NOW);
        assertNull(jdbc.queryForObject("SELECT request_meta_cipher FROM ai_chat_turn WHERE request_id = 'req-tombstone'",
                String.class));
    }

    @Test
    void deletingSessionCascadesTurnsAndNullsMessagePointers() {
        long session = session("待删会话");
        long turn = insertTurn(session, "req-cascade", "COMPLETED", "GLOBAL", 0);
        long userMessage = message(session, "user", turn, "USER");
        jdbc.update("INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id, source_block_id, "
                + "source_hmac, quote_cipher, crypto_meta, source_created_at) VALUES ('ev-1', ?, ?, ?, ?, 'u0', 'hmac-1', 'q', '{}', ?)",
                vault(), session, turn, userMessage, NOW);
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation, vault_epoch, "
                + "payload_cipher, crypto_meta, estimated_tokens, updated_at) VALUES (?, 1, ?, 1, 1, 'p', '{}', 10, ?)",
                session, turn, NOW);

        jdbc.update("DELETE FROM ai_chat_session WHERE id = ?", session);

        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE session_id = " + session));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = " + session));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence WHERE id = 'ev-1'"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE id = " + userMessage));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_job WHERE session_id = " + session));
    }

    // ---- vault / item / evidence / proposal ----

    @Test
    void vaultRowIsSingleton() {
        vault();
        assertRejected("v0.7 单用户库：singleton 唯一约束不得放第二份库", () -> jdbc.update(
                "INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at) VALUES ('v-2', 'd', '{}', 'password', ?)", NOW));
        assertRejected("singleton 只能是 1", () -> jdbc.update(
                "INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, singleton, created_at) VALUES ('v-3', 'd', '{}', 'password', 2, ?)", NOW));
        assertRejected("mode 只允许 fingerprint/password", () -> jdbc.update(
                "INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at) VALUES ('v-4', 'd', '{}', 'auto', ?)", NOW));
    }

    @Test
    void onlyOneActiveFactPerFamily() {
        String vaultId = vault();
        insertItem("row-1", "mem-1", vaultId, 1, "ACTIVE", "fam-pref", "pred-pref", "val-1", "MANUAL");
        assertRejected("同 family 只能有一条 ACTIVE", () ->
                insertItem("row-2", "mem-2", vaultId, 1, "ACTIVE", "fam-pref", "pred-other", "val-2", "AUTO"));

        // 同 family 的 PENDING 可以与 ACTIVE 并存（替换建议待审阅）
        insertItem("row-3", "mem-2", vaultId, 1, "PENDING", "fam-pref", "pred-other", "val-2", "AUTO");
        // 不同 family、或同 family 的其它状态都不冲突
        insertItem("row-4", "mem-3", vaultId, 1, "ACTIVE", "fam-goal", "pred-goal", "val-3", "AUTO");
        insertItem("row-5", "mem-1", vaultId, 2, "SUPERSEDED", "fam-pref", "pred-pref", "val-0", "MANUAL");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = '" + vaultId + "' AND status = 'ACTIVE' AND family_key_hmac = 'fam-pref'"));
    }

    @Test
    void itemVersionScopeAndKindAreChecked() {
        String vaultId = vault();
        assertRejected("version 必须为正", () ->
                insertItem("row-bad-version", "mem-x", vaultId, 0, "ACTIVE", "fam-x", "pred-x", "val-x", "AUTO"));
        assertRejected("kind 必须在四类之内", () -> jdbc.update("""
                INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,
                    predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,
                    observed_at, recorded_at, last_confirmed_at)
                VALUES ('row-bad-kind', 'mem-y', ?, 1, 'GLOBAL', 0, 'SECRET', 'p', 'f', 'v', 'ACTIVE', 'x', '{}', 'AUTO', ?, ?, ?)
                """, vaultId, NOW, NOW, NOW));
        assertRejected("NOTEBOOK 作用域必须带正数 scope_id", () -> jdbc.update("""
                INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,
                    predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,
                    observed_at, recorded_at, last_confirmed_at)
                VALUES ('row-bad-scope', 'mem-z', ?, 1, 'NOTEBOOK', 0, 'GOAL', 'p', 'f', 'v', 'ACTIVE', 'x', '{}', 'AUTO', ?, ?, ?)
                """, vaultId, NOW, NOW, NOW));
    }

    @Test
    void evidenceIsDeduplicatedPerVaultAndLinkedRowsFollowCascade() {
        String vaultId = vault();
        long session = session("证据会话");
        long turn = insertTurn(session, "req-evidence", "COMPLETED", "GLOBAL", 0);
        long message = message(session, "user", turn, "USER");
        jdbc.update("INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id, source_block_id, "
                        + "source_hmac, quote_cipher, crypto_meta, source_created_at) VALUES ('ev-a', ?, ?, ?, ?, 'u0', 'hmac-a', 'q', '{}', ?)",
                vaultId, session, turn, message, NOW);
        assertRejected("同一来源在同库内只能有一条证据", () -> jdbc.update(
                "INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id, source_block_id, "
                        + "source_hmac, quote_cipher, crypto_meta, source_created_at) VALUES ('ev-b', ?, ?, ?, ?, 'u0', 'hmac-a', 'q', '{}', ?)",
                vaultId, session, turn, message, NOW));

        insertItem("row-linked", "mem-linked", vaultId, 1, "ACTIVE", "fam-linked", "pred-linked", "val-linked", "AUTO");
        jdbc.update("INSERT INTO ai_memory_item_evidence(item_row_id, evidence_id) VALUES ('row-linked', 'ev-a')");

        // 删原轮次只把 turn_id 置空，证据仍在（§2.3：不能靠 SET NULL 把 quote 丢掉）
        jdbc.update("DELETE FROM ai_chat_turn WHERE id = ?", turn);
        assertNotNull(jdbc.queryForObject("SELECT quote_cipher FROM ai_memory_evidence WHERE id = 'ev-a'", String.class));
        assertNull(jdbc.queryForObject("SELECT turn_id FROM ai_memory_evidence WHERE id = 'ev-a'", Long.class));

        // 删事实行则联表关系级联消失
        jdbc.update("DELETE FROM ai_memory_item WHERE row_id = 'row-linked'");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item_evidence WHERE item_row_id = 'row-linked'"));
    }

    @Test
    void proposalKeysAndEnums() {
        String vaultId = vault();
        long session = session("建议会话");
        insertProposal("prop-1", vaultId, session, "REPLACE_PROPOSAL", "PENDING", "dedup-1");
        assertRejected("同库内 dedup_hmac 唯一", () ->
                insertProposal("prop-2", vaultId, session, "REPLACE_PROPOSAL", "PENDING", "dedup-1"));
        assertRejected("operation 只有替换/撤销两种", () ->
                insertProposal("prop-3", vaultId, session, "ADD_PROPOSAL", "PENDING", "dedup-3"));
        assertRejected("status 必须在枚举内", () ->
                insertProposal("prop-4", vaultId, session, "RETRACT_PROPOSAL", "ABDATED", "dedup-4"));
    }

    @Test
    void suppressionKeyIsComposite() {
        String vaultId = vault();
        jdbc.update("INSERT INTO ai_memory_suppression(vault_id, scope_type, scope_id, suppression_kind, key_hmac, created_at) "
                + "VALUES (?, 'GLOBAL', 0, 'FAMILY', 'k-1', ?)", vaultId, NOW);
        assertRejected("同键重复抑制应失败", () -> jdbc.update(
                "INSERT INTO ai_memory_suppression(vault_id, scope_type, scope_id, suppression_kind, key_hmac, created_at) "
                        + "VALUES (?, 'GLOBAL', 0, 'FAMILY', 'k-1', ?)", vaultId, NOW));
        // 作用域或种类不同即为另一条抑制
        jdbc.update("INSERT INTO ai_memory_suppression(vault_id, scope_type, scope_id, suppression_kind, key_hmac, created_at) "
                + "VALUES (?, 'NOTEBOOK', 3, 'EVIDENCE', 'k-1', ?)", vaultId, NOW);
        assertRejected("suppression_kind 只有三种", () -> jdbc.update(
                "INSERT INTO ai_memory_suppression(vault_id, scope_type, scope_id, suppression_kind, key_hmac, created_at) "
                        + "VALUES (?, 'GLOBAL', 0, 'MEMORY', 'k-2', ?)", vaultId, NOW));
    }

    // ---- job / summary / storage / usage ----

    @Test
    void jobIdentityCoversInputRevisionAndPolicy() {
        long session = session("任务会话");
        insertJob("job-1", "EXTRACT", session, 1, 4, "rev-1", 1, "QUEUED", null);
        assertRejected("相同输入终态不能被定时器重跑", () ->
                insertJob("job-2", "EXTRACT", session, 1, 4, "rev-1", 1, "QUEUED", null));
        // 批次范围、输入指纹或策略版本不同即是另一个任务
        insertJob("job-3", "EXTRACT", session, 1, 5, "rev-1", 1, "QUEUED", null);
        insertJob("job-4", "SUMMARY", session, 1, 4, "rev-1", 1, "QUEUED", null);
        insertJob("job-5", "EXTRACT", session, 1, 4, "rev-2", 1, "QUEUED", null);
        insertJob("job-6", "EXTRACT", session, 1, 4, "rev-3", 2, "CLAIMED", null);
        assertRejected("未知 job kind", () -> insertJob("job-7", "PRUNE", session, 1, 4, "rev-9", 1, "QUEUED", null));
        assertRejected("outcome_code 必须是 JobOutcome 之一", () ->
                insertJob("job-8", "EXTRACT", session, 1, 9, "rev-10", 1, "COMPLETED", "APPLIED_ALL"));
        insertJob("job-9", "EXTRACT", session, 1, 10, "rev-11", 1, "COMPLETED", "NO_NEW_INFORMATION");
    }

    @Test
    void sessionContextMustBeVersionTwo() {
        long session = session("摘要会话");
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation, vault_epoch, "
                + "payload_cipher, crypto_meta, estimated_tokens, updated_at) VALUES (?, 1, 1, 1, 1, 'p', '{}', 600, ?)", session, NOW);
        assertRejected("只接受 schema_version=2 的摘要载荷", () -> jdbc.update(
                "INSERT INTO ai_session_context(session_id, revision, schema_version, through_turn_id, context_generation, vault_epoch, "
                        + "payload_cipher, crypto_meta, estimated_tokens, updated_at) VALUES (?, 1, 1, 1, 1, 1, 'p', '{}', 600, ?)", session, NOW));
        assertRejected("一个会话只有一条摘要检查点", () -> jdbc.update(
                "INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation, vault_epoch, "
                        + "payload_cipher, crypto_meta, estimated_tokens, updated_at) VALUES (?, 2, 2, 1, 1, 'p', '{}', 600, ?)", session, NOW));
    }

    @Test
    void storageLedgerKeepsPositiveLimitsAndNonNegativeCounters() {
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_storage WHERE id = 1"));
        assertRejected("容量额度必须为正", () -> jdbc.update(
                "UPDATE ai_chat_storage SET history_limit_bytes = 0 WHERE id = 1"));
        assertRejected("账本不能为负", () -> jdbc.update(
                "UPDATE ai_chat_storage SET reserved_history_bytes = -1 WHERE id = 1"));
        assertRejected("账本只允许一行", () -> jdbc.update(
                "INSERT INTO ai_chat_storage(id) VALUES (2)"));
    }

    @Test
    void usageAttemptStateAndDailyKey() {
        jdbc.update("INSERT INTO ai_usage_attempt(id, operation_id, purpose, target_fingerprint, budget_date, "
                + "budget_timezone, reserved_tokens, usage_source, state, created_at) VALUES ('at-1', 'op-1', "
                + "'MEMORY_EXTRACT', 'fp', '2026-10-11', 'Asia/Shanghai', 4096, 'estimate', 'RESERVED', ?)", NOW);
        assertRejected("purpose 必须在四个通道内", () -> jdbc.update(
                "INSERT INTO ai_usage_attempt(id, operation_id, purpose, target_fingerprint, budget_date, "
                        + "budget_timezone, reserved_tokens, usage_source, state, created_at) VALUES ('at-2', 'op-2', "
                        + "'EMBEDDING', 'fp', '2026-10-11', 'Asia/Shanghai', 1, 'estimate', 'RESERVED', ?)", NOW));
        assertRejected("未知 state 一律拒绝", () -> jdbc.update(
                "INSERT INTO ai_usage_attempt(id, operation_id, purpose, target_fingerprint, budget_date, "
                        + "budget_timezone, reserved_tokens, usage_source, state, created_at) VALUES ('at-3', 'op-3', "
                        + "'CHAT', 'fp', '2026-10-11', 'Asia/Shanghai', 1, 'provider', 'PENDING', ?)", NOW));

        jdbc.update("INSERT INTO ai_usage_daily(budget_date, budget_timezone, target_fingerprint, purpose) "
                + "VALUES ('2026-10-11', 'Asia/Shanghai', 'fp', 'MEMORY_EXTRACT')");
        assertRejected("日聚合主键是日期+时区+目标+用途", () -> jdbc.update(
                "INSERT INTO ai_usage_daily(budget_date, budget_timezone, target_fingerprint, purpose) "
                        + "VALUES ('2026-10-11', 'Asia/Shanghai', 'fp', 'MEMORY_EXTRACT')"));
        jdbc.update("INSERT INTO ai_usage_daily(budget_date, budget_timezone, target_fingerprint, purpose) "
                + "VALUES ('2026-10-11', 'Asia/Shanghai', 'fp', 'CHAT')");
        // 换模型不能重置每日额度：另一 target 的行独立存在，总限额由服务层跨 target 求和
        assertEquals(2, count("SELECT COUNT(*) FROM ai_usage_daily"));
    }

    @Test
    void attachmentByteLengthMustMatchStoredPayload() {
        long session = session("附件会话");
        long turn = insertTurn(session, "req-attachment", "RUNNING", "GLOBAL", 0);
        assertRejected("byte_length 必须由服务端按密文实际长度计算", () -> jdbc.update(
                "INSERT INTO ai_chat_attachment(id, turn_id, block_id, kind, payload_cipher, crypto_meta, byte_length, created_at) "
                        + "VALUES ('att-1', ?, 'a0', 'IMAGE', ?, '{}', 999, ?)", turn, new byte[]{1, 2, 3}, NOW));
        // 密文 3 字节 + crypto_meta '{}' 2 字节 = 5，由服务端算好后写入
        jdbc.update("INSERT INTO ai_chat_attachment(id, turn_id, block_id, kind, payload_cipher, crypto_meta, byte_length, created_at) "
                + "VALUES ('att-2', ?, 'a0', 'IMAGE', ?, '{}', 5, ?)", turn, new byte[]{1, 2, 3}, NOW);
        assertEquals(5, jdbc.queryForObject("SELECT byte_length FROM ai_chat_attachment WHERE id = 'att-2'", Integer.class).intValue());
        assertRejected("kind 只有 TEXT/IMAGE", () -> jdbc.update(
                "INSERT INTO ai_chat_attachment(id, turn_id, block_id, kind, payload_cipher, crypto_meta, byte_length, created_at) "
                        + "VALUES ('att-3', ?, 'a1', 'PDF', ?, '{}', 3, ?)", turn, new byte[]{1}, NOW));
    }

    @Test
    void memorySettingsRowIsSingletonAndSwitchesAreBoolean() {
        assertRejected("拒绝高于支持的 id：设置表只允许一行", () -> jdbc.update(
                "INSERT INTO ai_memory_settings(id, policy_json, updated_at) VALUES (2, '{}', ?)", NOW));
        jdbc.update("INSERT INTO ai_memory_settings(id, policy_json, updated_at) VALUES (1, '{}', ?)", NOW);
        assertRejected("开关列只能是 0/1", () -> jdbc.update(
                "UPDATE ai_memory_settings SET enabled = 2 WHERE id = 1"));
        assertRejected("policy_json 不能为空", () -> jdbc.update(
                "UPDATE ai_memory_settings SET policy_json = NULL WHERE id = 1"));
        // 默认关闭：迁移不会擅自启用记忆或压缩
        assertEquals(0, jdbc.queryForObject("SELECT enabled FROM ai_memory_settings WHERE id = 1", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT auto_extract FROM ai_memory_settings WHERE id = 1", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT compress_session FROM ai_memory_settings WHERE id = 1", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject("SELECT auto_apply_low_risk FROM ai_memory_settings WHERE id = 1", Integer.class).intValue());
    }

    // ---- helpers ----

    /** last_insert_rowid() 是连接级的，测试里每次写入都新开连接，必须用 KeyHolder 取回主键。 */
    private long insertAndKey(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, holder);
        Number key = holder.getKey();
        assertNotNull(key, "自增主键必须可取回: " + sql);
        return key.longValue();
    }

    private long session(String title) {
        return insertAndKey("INSERT INTO ai_chat_session(title) VALUES (?)", title);
    }

    private long message(long session, String role, Long turnId, String turnRole) {
        return insertAndKey("INSERT INTO ai_chat_message(session_id, role, content, turn_id, turn_role) VALUES (?, ?, '', ?, ?)",
                session, role, turnId, turnRole);
    }

    private long insertTurn(long session, String requestId, String status, String scopeType, long scopeId) {
        return insertAndKey("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,
                    context_generation, source_scope_type, source_scope_id, source_timezone, runtime_generation,
                    started_at)
                VALUES (?, ?, ?, 'meta', '{"v":1}', 1, ?, ?, 'Asia/Shanghai', 'gen-1', ?)
                """, requestId, session, status, scopeType, scopeId, NOW);
    }

    private String vault() {
        List<String> existing = jdbc.queryForList("SELECT id FROM ai_memory_vault LIMIT 1", String.class);
        if (!existing.isEmpty()) return existing.get(0);
        jdbc.update("INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at) VALUES ('vault-1', 'dek', '{}', 'fingerprint', ?)", NOW);
        return "vault-1";
    }

    private void insertItem(String rowId, String memoryId, String vaultId, int version, String status,
                            String family, String predicate, String value, String origin) {
        jdbc.update("""
                INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,
                    predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,
                    observed_at, recorded_at, last_confirmed_at)
                VALUES (?, ?, ?, ?, 'GLOBAL', 0, 'PREFERENCE', ?, ?, ?, ?, 'payload', '{}', ?, ?, ?, ?)
                """, rowId, memoryId, vaultId, version, predicate, family, value, status, origin, NOW, NOW, NOW);
    }

    private void insertProposal(String id, String vaultId, long session, String operation, String status, String dedup) {
        jdbc.update("INSERT INTO ai_memory_proposal(id, vault_id, session_id, target_memory_id, target_version, operation, "
                + "status, payload_cipher, crypto_meta, dedup_hmac, created_at, expires_at) "
                + "VALUES (?, ?, ?, 'mem-target', 1, ?, ?, 'payload', '{}', ?, ?, ?)",
                id, vaultId, session, operation, status, dedup, NOW, NOW);
    }

    private void insertJob(String id, String kind, long session, long from, long through, String inputRevision,
                           long policyRevision, String status, String outcome) {
        jdbc.update("INSERT INTO ai_memory_job(id, kind, session_id, from_turn_id, through_turn_id, context_generation, "
                + "source_scope_type, source_scope_id, source_timezone, vault_epoch, policy_revision, target_fingerprint, "
                + "prompt_version, input_revision, status, outcome_code, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 1, 'GLOBAL', 0, 'Asia/Shanghai', 1, ?, 'fp', 'v1', ?, ?, ?, ?, ?)",
                id, kind, session, from, through, policyRevision, inputRevision, status, outcome, NOW, NOW);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static void assertRejected(String message, Runnable action) {
        DataAccessException error = assertThrows(DataAccessException.class, action::run, message);
        assertTrue(error.getMessage() != null, "约束失败必须带 SQLite 错误信息");
    }
}
