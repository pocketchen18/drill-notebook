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
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 历史投影的读边界与摘要替代规则（执行方案 §9.4、§7.2.2 尾段，验收 T01/T02/T04/F02）。
 *
 * <p>轮次与消息用固定密文直接落库，只为了让一次建 60 轮不至于把 Argon2 跑满一分钟；
 * 解密路径、扫描分页、coverage 判定与中断态取的都是生产实现。</p>
 */
class ConversationProjectionTest {
    private static final String USER_TEXT = "这一轮的用户问题正文";
    private static final String REPLY_TEXT = "这一轮的助手回复正文，够长以便估算成若干 token。";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private MemoryCryptoService crypto;
    private MemoryVaultService vault;
    private ConversationProjection projection;
    private String userCipher;
    private String userMeta;
    private String replyCipher;
    private String replyMeta;
    private long sessionId;

    @BeforeEach
    void setUp() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p4-projection-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        ObjectMapper mapper = new ObjectMapper();
        TokenEstimator estimator = new TokenEstimator(mapper);
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        vault = new MemoryVaultService(crypto, wiring.memory(),
                new MemoryOperationRepository(dataSource, wiring.immediate()), wiring.metadata());
        projection = new ConversationProjection(new ChatHistoryRepository(dataSource),
                new SessionContextRepository(dataSource), wiring.metadata(), wiring.cipher(), wiring.memory(),
                crypto, vault, estimator, mapper);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        ChatMessageCipher.Encrypted user = wiring.cipher().encrypt(USER_TEXT, "");
        ChatMessageCipher.Encrypted reply = wiring.cipher().encrypt(REPLY_TEXT, "");
        userCipher = user.cipher();
        userMeta = user.meta();
        replyCipher = reply.cipher();
        replyMeta = reply.meta();
    }

    @Test
    void scanStaysBoundedNoMatterHowLongTheHistoryIs() throws Exception {
        seedTurns(60);
        long current = insertRunningTurn();

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-1", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertTrue(result.candidates().size() >= 2, "近期区至少要有两轮");
        assertTrue(result.candidates().size() <= ConversationProjection.MAX_SCANNED_TURNS);
        assertEquals(ConversationProjection.MAX_SCANNED_TURNS, result.scan().scannedTurns(),
                "一批 16、单请求最多扫 32：更早的一律不碰（T01）");
        assertTrue(result.scan().hasOlderUnscanned());
        // 没有有效摘要时范围外只能报"没扫"，数量必须留空，不能假造"都已总结"
        assertTrue(hasOmission(audit, result, ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY"));
        assertNull(omission(audit, result, ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY"));
        for (ConversationProjection.Item item : result.candidates()) {
            assertTrue(item.turnId() < current, "本轮正在跑的 RUNNING 轮次不能算自己的历史");
        }
    }

    @Test
    void coverageReplacesCoveredTurnsButNeverTheNewestTwo() throws Exception {
        seedTurns(20);
        long current = insertRunningTurn();
        createVault();
        // coverage 一路铺到"最近一条"之前：倒数第 1、2 轮仍要以原文保留（T02）
        checkpoint(1L, 20L, 1L, coverage(1, current - 1));

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-2", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertNotNull(result.summary());
        assertEquals(2, result.candidates().size(), "被摘要替掉的只到最近两轮之前");
        long newest = current - 1;
        assertEquals(newest, result.candidates().get(1).turnId());
        assertEquals(newest - 1, result.candidates().get(0).turnId());
        assertEquals(Integer.valueOf(18), omission(audit, result, ContextAudit.Omission.COVERED_BY_SUMMARY, "TURN"));
        assertFalse(result.summary().audit().hasGaps(), "连续且无非完成轮次时不该报缺口");
    }

    @Test
    void coverageGapKeepsTheUncoveredTurnAsOriginalText() throws Exception {
        seedTurns(10);
        long current = insertRunningTurn();
        createVault();
        // 中间留一个缺口：turn 5 不在任何 range 里，就不能按 through 整段排除
        checkpoint(1L, 10L, 1L, List.of(new long[]{1, 4}, new long[]{6, 10}));

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-3", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertNotNull(result.summary());
        assertTrue(result.summary().audit().hasGaps(), "coverage 不连续必须报缺口（§2.5）");
        // turn 5 不在任何 range 里：它必须作为原文回来，而不是按 through 整段排除
        assertEquals(3, result.candidates().size(), "缺口那轮 + 最近两轮原文保留");
        assertEquals(5, result.candidates().get(0).turnId());
        assertFalse(hasOmission(audit, result, ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY"),
                "10 轮全在扫描窗口内，没有范围外内容");
        // 被替掉的是 1–4 与 6–8：9、10 虽在 coverage 内，但最近两轮永远留原文（T02）
        assertEquals(Integer.valueOf(7), omission(audit, result, ContextAudit.Omission.COVERED_BY_SUMMARY, "TURN"));
    }

    @Test
    void lockedVaultUsesNoSummaryAndNeverFallsBack() throws Exception {
        seedTurns(4);
        long current = insertRunningTurn();
        createVault();
        checkpoint(1L, 4L, 1L, coverage(1, 4));
        MemoryApiResult locked = vault.lock();

        assertEquals(204, locked.status());
        ContextAudit.Builder audit = new ContextAudit.Builder("plan-4", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertNull(result.summary(), "未解锁就不得使用摘要正文，也不得按 through 排除历史");
        assertEquals(Integer.valueOf(1), omission(audit, result, ContextAudit.Omission.LOCKED, "SUMMARY"));
        assertTrue(result.notices().contains(ConversationProjection.NOTICE_SUMMARY_UNAVAILABLE));
        assertEquals(4, result.candidates().size(), "锁定只让摘要失效，不能顺手丢掉原文历史");
    }

    @Test
    void checkpointFromAnotherGenerationOrEpochIsIgnored() throws Exception {
        seedTurns(6);
        long current = insertRunningTurn();
        createVault();
        checkpoint(1L, 6L, 1L, coverage(1, 6));

        ContextAudit.Builder stale = new ContextAudit.Builder("plan-5", TokenEstimator.VERSION);
        // 会话把作用域切到 NOTEBOOK 之后 generation 变成 2：旧检查点必须整份作废（T04）
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '笔记本')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, current);

        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 2L, "", 2400), stale);
        assertNull(result.summary());
        assertEquals(Integer.valueOf(1), omission(stale, result, ContextAudit.Omission.WRONG_GENERATION, "SUMMARY"));

        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, current);
        jdbc.update("UPDATE ai_session_context SET context_generation = 2");
        String vaultId = jdbc.queryForObject("SELECT id FROM ai_memory_vault WHERE singleton = 1", String.class);
        wiring.memory().bumpEpoch(vaultId);

        ContextAudit.Builder afterClear = new ContextAudit.Builder("plan-6", TokenEstimator.VERSION);
        ConversationProjection.Projection epochResult = projection.project(
                new ConversationProjection.Request(sessionId, current, 2L, "", 2400), afterClear);
        assertNull(epochResult.summary(), "清空/重置库之后旧检查点不能恢复使用");
        assertEquals(Integer.valueOf(1), omission(afterClear, epochResult, ContextAudit.Omission.SOURCE_DELETED,
                "SUMMARY"));
    }

    @Test
    void resumeStateComesOnlyFromTheLatestTerminalTurn() throws Exception {
        seedTurns(3);
        long current = insertRunningTurn();
        jdbc.update("UPDATE ai_chat_turn SET status = 'ABORTED', finished_at = '2026-10-12T00:00:00.000Z'"
                + " WHERE id = (SELECT MAX(id) FROM ai_chat_turn WHERE id < ?)", current);

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-7", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertNotNull(result.resumeState());
        assertEquals("ABORTED", result.resumeState().status());
        assertEquals("2026-10-12T00:00:00.000Z", result.resumeState().finishedAt());

        // 最新一条终态是 COMPLETED 时不算中断：不往前翻历史失败（§4.5 尾段）
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED' WHERE id < ?", current);
        ContextAudit.Builder clean = new ContextAudit.Builder("plan-8", TokenEstimator.VERSION);
        assertNull(projection.project(new ConversationProjection.Request(sessionId, current, 1L, "", 2400),
                clean).resumeState());
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void legacyMessagesSitBeforeTurnRowsAndKeepTheirOwnOrder() throws Exception {
        // 迁移前的旧消息比任何轮次都旧：整体反转后必须落在最前面，且段内保持"旧→新"
        insertLegacyPair("旧问题一", "旧回答一", "2026-01-01 00:00:00", "2026-01-01 00:00:30");
        insertLegacyPair("旧问题二", "旧回答二", "2026-01-02 00:00:00", "2026-01-02 00:00:30");
        seedTurns(2);
        long current = insertRunningTurn();

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-9b", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertEquals(4, result.candidates().size(), "近期区没填满时才补迁移前消息（F02）");
        List<Boolean> legacyFlags = result.candidates().stream()
                .map(ConversationProjection.Item::legacy).toList();
        assertEquals(List.of(true, true, false, false), legacyFlags, "legacy 在前、轮次在后，不能被倒置");
        assertEquals("旧问题一", result.candidates().get(0).userText());
        assertEquals("旧问题二", result.candidates().get(1).userText());
        long oldestTurn = current - 2;
        assertEquals(oldestTurn, result.candidates().get(2).turnId(), "轮次也按时间升序接在后面");
        assertEquals(oldestTurn + 1, result.candidates().get(3).turnId());
    }

    private void insertLegacyPair(String question, String reply, String askedAt, String repliedAt) {
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content, created_at) VALUES (?, 'user', ?, ?)",
                sessionId, question, askedAt);
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content, created_at) VALUES (?, 'assistant', ?, ?)",
                sessionId, reply, repliedAt);
    }

    @Test
    void legacyMessagesJoinHistoryOnlyInsideTheInitialGeneration() throws Exception {
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content, created_at) VALUES (?, 'user', '旧明文提问', '2026-01-01 00:00:00')", sessionId);
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content, created_at) VALUES (?, 'assistant', '旧明文回答', '2026-01-01 00:00:01')", sessionId);
        long current = insertRunningTurn();

        ContextAudit.Builder first = new ContextAudit.Builder("plan-9", TokenEstimator.VERSION);
        ConversationProjection.Projection same = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), first);
        assertEquals(1, same.candidates().size(), "迁移前的旧消息按顺序配成临时轮次参与上下文（F02）");
        assertTrue(same.candidates().get(0).legacy());

        jdbc.update("INSERT INTO notebook(id, title) VALUES (8, '笔记本')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 8L, current);
        ContextAudit.Builder later = new ContextAudit.Builder("plan-10", TokenEstimator.VERSION);
        ConversationProjection.Projection switched = projection.project(
                new ConversationProjection.Request(sessionId, current, 2L, "", 2400), later);
        assertTrue(switched.candidates().isEmpty(), "切过作用域后旧正文不再自动带入新代次（§2.2）");
        assertEquals(null, omission(later, switched, ContextAudit.Omission.NON_COMPLETED, "LEGACY"));
    }

    @Test
    void attachmentIdentitiesRideAlongWithTheirTurn() throws Exception {
        seedTurns(2);
        long current = insertRunningTurn();
        seedAttachment(current - 1, "att-1", "a0", "IMAGE");
        seedAttachment(current - 1, "att-2", "a1", "TEXT");

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-11", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        ConversationProjection.Item newest = result.candidates().get(result.candidates().size() - 1);
        assertEquals(2, newest.attachments().size(), "投影只带身份，字节由组装器按图片策略再取");
        assertEquals("IMAGE", newest.attachments().get(0).kind());
        assertEquals("a1", newest.attachments().get(1).blockId());
    }

    @Test
    void unfinishedAndTombstonedTurnsAreReportedSeparately() throws Exception {
        seedTurns(5);
        long current = insertRunningTurn();
        jdbc.update("UPDATE ai_chat_turn SET status = 'FAILED' WHERE id = ?", current - 1);
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = '2026-10-12T00:00:00.000Z',"
                + " request_meta_cipher = NULL, request_meta_crypto = NULL WHERE id = ?", current - 2);
        jdbc.update("UPDATE ai_chat_turn SET context_generation = 9 WHERE id = ?", current - 3);

        ContextAudit.Builder audit = new ContextAudit.Builder("plan-12", TokenEstimator.VERSION);
        ConversationProjection.Projection result = projection.project(
                new ConversationProjection.Request(sessionId, current, 1L, "", 2400), audit);

        assertEquals(Integer.valueOf(1), omission(audit, result, ContextAudit.Omission.NON_COMPLETED, "TURN"));
        assertEquals(Integer.valueOf(1), omission(audit, result, ContextAudit.Omission.SOURCE_DELETED, "TURN"));
        assertEquals(Integer.valueOf(1), omission(audit, result, ContextAudit.Omission.WRONG_GENERATION, "TURN"));
        for (ConversationProjection.Item item : result.candidates()) {
            assertTrue(item.turnId() != current - 1 && item.turnId() != current - 2 && item.turnId() != current - 3);
        }
    }

    // ---------- 装配与夹具 ----------

    private void createVault() throws SQLException {
        MemoryApiResult created = vault.create("fingerprint", "", "01a109ca-1100-7000-8000-000000000001");
        assertTrue(created.status() == 201 || created.status() == 200, "vault 创建应成功: " + created.body());
    }

    private void seedTurns(int count) {
        for (int index = 1; index <= count; index++) {
            insertTurn(ChatTurnRepository.STATUS_COMPLETED, 1L, null, index);
        }
    }

    private void insertTurn(String status, long generation, String deletedAt, int sequence) {
        String requestId = String.format("01a109ca-1100-7000-8000-%012d", sequence);
        jdbc.update("INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher,"
                + " request_meta_crypto, context_generation, source_scope_type, source_scope_id, source_timezone,"
                + " runtime_generation, stored_history_bytes, reserved_history_bytes, persistence_status,"
                + " content_deleted_at, started_at, finished_at) VALUES (?, ?, 'RUNNING', 'meta', 'metaMeta',"
                + " ?, 'GLOBAL', 0, 'Asia/Shanghai', 'run-1', 0, 0, 'pending', ?, ?, ?)",
                requestId, sessionId, generation, deletedAt,
                "2026-10-1" + (sequence % 9) + "T00:00:00.000Z", "2026-10-12T00:00:00.000Z");
        Long turnId = jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE request_id = ?", Long.class, requestId);
        long userMessage = insertMessage("user", "USER", userCipher, userMeta);
        long assistantMessage = insertMessage("assistant", "ASSISTANT", replyCipher, replyMeta);
        jdbc.update("UPDATE ai_chat_message SET turn_id = ? WHERE id IN (?, ?)", turnId, userMessage, assistantMessage);
        jdbc.update("UPDATE ai_chat_turn SET user_message_id = ?, assistant_message_id = ?, status = ? WHERE id = ?",
                userMessage, assistantMessage, status, turnId);
    }

    private long insertRunningTurn() {
        insertTurn(ChatTurnRepository.STATUS_RUNNING, 1L, null, 900);
        return jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE status = 'RUNNING'", Long.class);
    }

    private long insertMessage(String role, String turnRole, String cipher, String meta) {
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta,"
                + " turn_role, created_at) VALUES (?, ?, '', ?, ?, ?, '2026-10-12 00:00:00')",
                sessionId, role, cipher, meta, turnRole);
        return jdbc.queryForObject("SELECT id FROM ai_chat_message WHERE session_id = ?"
                + " ORDER BY id DESC LIMIT 1", Long.class, sessionId);
    }

    private void seedAttachment(long turnId, String attachmentId, String blockId, String kind) {
        // byte_length 有 CHECK 约束：必须等于密文 BLOB 长度 + crypto_meta 的 UTF-8 长度
        byte[] payload = new byte[]{1, 2, 3};
        String meta = "{\"iv\":\"AAICAg==\"}";
        jdbc.update("INSERT INTO ai_chat_attachment(id, turn_id, block_id, kind, payload_cipher, crypto_meta,"
                + " byte_length, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, '2026-10-12T00:00:00.000Z')",
                attachmentId, turnId, blockId, kind, payload, meta,
                payload.length + meta.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private List<long[]> coverage(int first, long last) {
        List<long[]> ranges = new ArrayList<>();
        ranges.add(new long[]{first, last});
        return ranges;
    }

    /** 落一份合法检查点：payload 形状按 §2.5（modelSummary 与 serverCoverage 分开）。 */
    private void checkpoint(long generation, long throughTurnId, long revision, List<long[]> ranges) throws Exception {
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (long[] range : ranges) {
            coverage.add(Map.of("firstTurnId", range[0], "lastTurnId", range[1]));
        }
        String payload = new ObjectMapper().writeValueAsString(Map.of("schemaVersion", 2,
                "modelSummary", "用户在准备雅思，偏好先给例子再讲原理。", "serverCoverage", coverage));
        MemoryCryptoService.MemoryKey key = vault.requireKey();
        MemoryCryptoService.EncryptedValue encrypted = crypto.encrypt(key, "ai_session_context",
                String.valueOf(sessionId), revision, payload);
        long epoch = jdbc.queryForObject("SELECT epoch FROM ai_memory_vault WHERE singleton = 1", Long.class);
        jdbc.update("DELETE FROM ai_session_context WHERE session_id = ?", sessionId);
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, '2026-10-12T00:00:00.000Z')",
                sessionId, revision, throughTurnId, generation, epoch, encrypted.cipher(),
                encrypted.cryptoMeta(), 40);
    }

    private ContextAudit auditOf(ContextAudit.Builder builder, ConversationProjection.Projection result) {
        return builder.build(ContextAudit.DISPATCH_NOT_SENT, 0, ChatContextPlan.INPUT_BUDGET_TOKENS, result.scan(),
                result.summary() == null ? null : result.summary().audit(), result.resumeState());
    }

    private Integer omission(ContextAudit.Builder builder, ConversationProjection.Projection result,
                             ContextAudit.Omission reason, String kind) {
        for (ContextAudit.OmissionGroup group : auditOf(builder, result).omissions()) {
            if (group.reason() == reason && kind.equals(group.kind())) return group.count();
        }
        return null;
    }

    /** 区分"这条省略根本没记"与"记了但数量未知（null）"，两者在契约里含义不同。 */
    private boolean hasOmission(ContextAudit.Builder builder, ConversationProjection.Projection result,
                                ContextAudit.Omission reason, String kind) {
        for (ContextAudit.OmissionGroup group : auditOf(builder, result).omissions()) {
            if (group.reason() == reason && kind.equals(group.kind())) return true;
        }
        return false;
    }
}
