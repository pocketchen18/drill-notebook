package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.ChatTurnRepository.AttachmentPayload;
import com.drillnotebook.app.repository.ChatTurnRepository.Draft;
import com.drillnotebook.app.repository.ChatTurnRepository.StartOutcome;
import com.drillnotebook.app.repository.ChatTurnRepository.StartResult;
import com.drillnotebook.app.repository.ChatTurnRepository.Terminal;
import com.drillnotebook.app.repository.ChatTurnRepository.TerminalOutcome;
import com.drillnotebook.app.repository.ChatTurnRepository.TurnRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轮次生命周期 + 容量账本（contracts §2.2、§2.7）：开始不留残轮、终态只结算一次、
 * 清理留墓碑并把字节扣准，且计数与写入永远在同一事务里。
 */
class ChatTurnRepositoryTest {
    private static final String NOW = "2026-10-11T00:00:00.000Z";
    private static final String LATER = "2026-10-11T00:01:00.000Z";
    private static final String USER_CIPHER = "dXNlci1jaXBoZXI=";
    private static final String USER_META = "{\"salt\":\"s\",\"iv\":\"aAA=\"}";
    private static final String REQUEST_META = "{\"schemaVersion\":2}";
    private static final String REQUEST_META_CRYPTO = "{\"version\":1}";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatStorageLedger ledger;
    private ChatTurnRepository turns;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-turns-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        ledger = new ChatStorageLedger(dataSource, immediate);
        turns = new ChatTurnRepository(dataSource, immediate, ledger,
                new ChatSessionMetadataRepository(dataSource, immediate),
                new MemoryRepository(dataSource, immediate));
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void beginWritesTurnUserAndAttachmentsInOneTransaction() throws Exception {
        StartResult started = turns.begin(draft("req-1", List.of(attachment("att-1"))));

        assertEquals(StartOutcome.STARTED, started.outcome());
        TurnRow turn = turns.findByRequestId("req-1").orElseThrow();
        assertEquals(ChatTurnRepository.STATUS_RUNNING, turn.status());
        assertEquals(started.userMessageId(), (long) turn.userMessageId());
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, turn.reservedHistoryBytes(), "该轮记下自己的回复预留");
        assertNull(turn.assistantMessageId());
        assertEquals("pending", turn.persistenceStatus());
        assertEquals(1, turn.sourceRevision());

        long expectedUserBytes = "中文问题".getBytes(StandardCharsets.UTF_8).length
                + USER_CIPHER.length() + USER_META.length()
                + REQUEST_META.length() + REQUEST_META_CRYPTO.length();
        assertEquals(expectedUserBytes, turn.storedHistoryBytes(), "轮次小计 = 自身元数据 + user 消息");

        ChatStorageLedger.Snapshot snapshot = ledger.read();
        assertEquals(expectedUserBytes, snapshot.historyBytes());
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, snapshot.reservedHistoryBytes());
        assertEquals(attachment("att-1").storedBytes(), snapshot.attachmentBytes());
        assertTrue(ledger.reconcile().consistent(), "开始事务的写入必须与账本同批");
        assertEquals(2, revision(), "消息新增推进 history_revision");
        assertEquals("USER", jdbc.queryForObject("SELECT turn_role FROM ai_chat_message WHERE id = ?",
                String.class, started.userMessageId()));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_attachment WHERE turn_id = " + turn.id()));
    }

    @Test
    void duplicateRequestIdReplaysInsteadOfStartingAgain() throws Exception {
        turns.begin(draft("req-dup", List.of()));
        ChatStorageLedger.Snapshot afterFirst = ledger.read();

        StartResult replay = turns.begin(draft("req-dup", List.of()));
        assertEquals(StartOutcome.DUPLICATE_REQUEST, replay.outcome());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message"));
        assertEquals(afterFirst, ledger.read(), "重放不得二次预留");
    }

    @Test
    void onlyOneRunningTurnPerSessionButOtherSessionsProceed() throws Exception {
        turns.begin(draft("req-a", List.of()));
        assertSame(StartOutcome.RUNNING_EXISTS, turns.begin(draft("req-b", List.of())).outcome());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"));

        long secondSession = newSession("另一个会话");
        assertSame(StartOutcome.STARTED, turns.begin(draftOn(secondSession, "req-c")).outcome());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"));
    }

    @Test
    void historyFullLeavesNoRunningResidue() throws Exception {
        StartResult first = turns.begin(draft("req-1", List.of()));
        assertEquals(StartOutcome.STARTED, first.outcome());
        // 先结算第一轮，让该会话不再有 RUNNING 占用（否则先撞上"同会话仅一轮"的门）
        turns.finalizeTurn("req-1", new Terminal(ChatTurnRepository.STATUS_ABORTED, null, null, null, null, null,
                null, null, "user_cancel", "saved", LATER));
        ChatStorageLedger.Snapshot afterFirst = ledger.read();
        // 额度收成"一个字节都不剩"：第二轮必须被拒且一行都不写
        jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = history_bytes + reserved_history_bytes");

        StartResult second = turns.begin(draft("req-2", List.of()));
        assertSame(StartOutcome.HISTORY_STORAGE_FULL, second.outcome());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"), "被拒的请求不留 RUNNING 残留");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message"));
        // 本用例自己改过额度，所以只比对"拒绝时不许动"的四项计数
        ChatStorageLedger.Snapshot afterRefusal = ledger.read();
        assertEquals(afterFirst.revision(), afterRefusal.revision());
        assertEquals(afterFirst.historyBytes(), afterRefusal.historyBytes());
        assertEquals(afterFirst.reservedHistoryBytes(), afterRefusal.reservedHistoryBytes());
        assertEquals(afterFirst.attachmentBytes(), afterRefusal.attachmentBytes());
    }

    @Test
    void attachmentFullLeavesNoResidue() throws Exception {
        jdbc.update("UPDATE ai_chat_storage SET attachment_limit_bytes = attachment_bytes + 10");

        StartResult result = turns.begin(draft("req-att", List.of(attachment("att-too-big"))));
        assertSame(StartOutcome.ATTACHMENT_STORAGE_FULL, result.outcome());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_attachment"));
        assertEquals(0, ledger.read().attachmentBytes());
    }

    @Test
    void finalizeSettlesActualBytesAndKeepsLedgerConsistent() throws Exception {
        turns.begin(draft("req-1", List.of()));
        ChatStorageLedger.Snapshot afterBegin = ledger.read();

        TerminalOutcome terminal = turns.finalizeTurn("req-1",
                new Terminal(ChatTurnRepository.STATUS_COMPLETED, null, "cmVwbHk=", "{\"mime\":\"v1\"}",
                        "cmVhc29uaW5n", "{\"chain\":1}", REQUEST_META, "{\"audit\":1}", "stop", "saved", LATER));

        assertTrue(terminal.applied());
        assertFalse(terminal.alreadyTerminal());
        TurnRow turn = turns.findByRequestId("req-1").orElseThrow();
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, turn.status());
        assertEquals(0, turn.reservedHistoryBytes(), "终态把该轮预留清零");
        assertEquals(2, turn.sourceRevision(), "终态保存算一次源变更");
        assertNotNull(turn.assistantMessageId());
        assertEquals("stop", turn.finishReason());

        ChatStorageLedger.Snapshot afterFinalize = ledger.read();
        assertEquals(0, afterFinalize.reservedHistoryBytes(), "全局预留同批归零");
        assertTrue(afterFinalize.historyBytes() > afterBegin.historyBytes());
        assertEquals(turn.storedHistoryBytes(), afterFinalize.historyBytes(), "全局存量恰等于该轮小计");
        assertTrue(ledger.reconcile().consistent(), "计数与写入同事务：结算后实测必须等于账本");
        assertEquals(3, revision(), "begin + 终态各推进一次");
        assertEquals(turn.assistantMessageId(), jdbc.queryForObject(
                "SELECT id FROM ai_chat_message WHERE turn_id = ? AND turn_role = 'ASSISTANT'", Long.class, turn.id()),
                "两个视图必须一致，否则按消息 ID 统计会漏");
    }

    @Test
    void duplicateFinalizeChangesNothing() throws Exception {
        turns.begin(draft("req-1", List.of()));
        turns.finalizeTurn("req-1", terminal(ChatTurnRepository.STATUS_COMPLETED, "cmVwbHk="));
        ChatStorageLedger.Snapshot afterFirst = ledger.read();
        long storedAfterFirst = turns.findByRequestId("req-1").orElseThrow().storedHistoryBytes();

        TerminalOutcome replay = turns.finalizeTurn("req-1", terminal(ChatTurnRepository.STATUS_FAILED, "xyz"));

        assertFalse(replay.applied());
        assertTrue(replay.alreadyTerminal());
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, replay.status(), "重复完成不得把 COMPLETED 改成 FAILED");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_role = 'ASSISTANT'"), "不重插消息");
        assertEquals(afterFirst, ledger.read(), "不二次结算、不二次计费");
        assertEquals(storedAfterFirst, replay.storedHistoryBytes());
    }

    @Test
    void overReservationKeepsTurnRunningAndRollsBackAssistant() throws Exception {
        turns.begin(draft("req-1", List.of()));
        ChatStorageLedger.Snapshot before = ledger.read();

        // 3 MiB 密文超过 2 MiB 预留：新适配器改变封装必须重新核验上界，这里拒绝结算而不是临时无界放大
        String oversized = "A".repeat(3_000_000);
        assertThrows(ChatTurnRepository.ReservationExceededException.class, () ->
                turns.finalizeTurn("req-1", terminal(ChatTurnRepository.STATUS_COMPLETED, oversized)));

        TurnRow turn = turns.findByRequestId("req-1").orElseThrow();
        assertEquals(ChatTurnRepository.STATUS_RUNNING, turn.status(), "拒绝结算时轮次保持 RUNNING");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_role = 'ASSISTANT'"), "回滚不留半个回复");
        assertEquals(before, ledger.read(), "账本不变");
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, turn.reservedHistoryBytes());
    }

    @Test
    void emptyTerminalReleasesReservationExactlyOnce() throws Exception {
        turns.begin(draft("req-stop", List.of()));
        long userBytes = ledger.read().historyBytes();

        TerminalOutcome aborted = turns.finalizeTurn("req-stop",
                new Terminal(ChatTurnRepository.STATUS_ABORTED, null, null, null, null, null, null, null,
                        "user_cancel", "saved", LATER));

        assertTrue(aborted.applied());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message"), "只保留用户那句话");
        assertEquals(0, ledger.read().reservedHistoryBytes(), "一个字没落库也要释放预留");
        assertEquals(userBytes, ledger.read().historyBytes(), "没有回复就不增加存量");
        assertTrue(ledger.reconcile().consistent());

        long secondSession = newSession("第二个会话");
        turns.begin(draftOn(secondSession, "req-stop-2"));
        turns.finalizeTurn("req-stop-2", new Terminal(ChatTurnRepository.STATUS_FAILED, null, null, null, null, null,
                null, null, "upstream_error", "saved", LATER));
        assertEquals(0, ledger.read().reservedHistoryBytes(), "两次取消各释放一次，不重复扣");
    }

    @Test
    void clearContentTombstonesAndReleasesExactBytes() throws Exception {
        turns.begin(draft("req-1", List.of(attachment("att-1"))));
        turns.finalizeTurn("req-1", terminal(ChatTurnRepository.STATUS_COMPLETED, "cmVwbHk="));
        long turnId = turns.findByRequestId("req-1").orElseThrow().id();
        seedEvidenceOwnedBy(turnId);

        turns.clearContent("req-1", LATER);

        TurnRow tombstone = turns.findByRequestId("req-1").orElseThrow();
        assertEquals(LATER, tombstone.contentDeletedAt());
        assertEquals(0, tombstone.storedHistoryBytes());
        assertNull(tombstone.userMessageId());
        assertNull(tombstone.assistantMessageId());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_attachment"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence WHERE id = 'ev-1'"), "原话不得以快照名义留存");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item_evidence"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE row_id = 'row-1'"),
                "原话是该自动事实唯一来源时，同一事务把它作为孤儿移除（P1c 接线）");
        assertEquals(2, jdbc.queryForObject("SELECT revision FROM ai_memory_vault WHERE id = 'vault-1'",
                Integer.class).intValue(), "证据/事实变更推进 vault revision，读路径据此失效缓存");

        ChatStorageLedger.Snapshot after = ledger.read();
        assertEquals(0, after.historyBytes(), "清理后本轮存量归零");
        assertEquals(0, after.attachmentBytes());
        assertTrue(ledger.reconcile().consistent(), "删除与账本扣减必须同事务");
        assertEquals(3, tombstone.sourceRevision(), "清理原文算一次源变更");
        assertEquals(4, revision());
    }

    @Test
    void clearContentRefusedWhileRunning() throws Exception {
        turns.begin(draft("req-1", List.of()));
        assertThrows(SQLException.class, () -> turns.clearContent("req-1", LATER));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message"), "RUNNING 期间清理被拒且不留痕");
        assertNull(turns.findByRequestId("req-1").orElseThrow().contentDeletedAt());
    }

    @Test
    void concurrentBeginsSellOnlyOneSlot() throws Exception {
        // 额度只够"一轮正文 + 一份回复预留"，多给 400 字节但不足第二轮的 2 MiB
        jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = history_bytes + reserved_history_bytes + ?"
                + " + 400", ChatStorageSizer.REPLY_RESERVATION_BYTES);
        List<Long> sessionIds = List.of(sessionId, newSession("并发会话 0"), newSession("并发会话 1"));
        ChatTurnRepository shared = turns;
        var pool = Executors.newFixedThreadPool(sessionIds.size());
        List<StartOutcome> outcomes = new CopyOnWriteArrayList<>();
        try {
            for (Long session : sessionIds) {
                pool.submit(() -> {
                    try {
                        outcomes.add(shared.begin(draftOn(session, "req-race-" + session)).outcome());
                    } catch (SQLException error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发开始必须有界结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, outcomes.stream().filter(StartOutcome.STARTED::equals).count(), "只有一个装得下");
        assertEquals(sessionIds.size() - 1,
                outcomes.stream().filter(outcome -> outcome == StartOutcome.HISTORY_STORAGE_FULL).count());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        ChatStorageLedger.Snapshot after = ledger.read();
        assertTrue(after.historyBytes() + after.reservedHistoryBytes() <= after.historyLimitBytes(), "无超卖");
        assertTrue(ledger.reconcile().consistent(), "并发下计数仍与实测一致");
    }

    @Test
    void statusLookupNeverRegeneratesAnything() throws Exception {
        turns.begin(draft("req-1", List.of()));
        turns.finalizeTurn("req-1", terminal(ChatTurnRepository.STATUS_TRUNCATED, "cGFydGlhbA=="));

        TurnRow row = turns.findByRequestId("req-1").orElseThrow();
        assertEquals(ChatTurnRepository.STATUS_TRUNCATED, row.status());
        assertEquals("length", row.finishReason());
        assertTrue(turns.findRunning(sessionId).isEmpty(), "终态之后该会话没有 RUNNING 轮次");
        turns.findByRequestId("req-1");
        turns.findRunning(sessionId);
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "查询不产生新行");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
    }

    // ---- helpers ----

    private Draft draft(String requestId, List<AttachmentPayload> attachments) {
        return draftOn(sessionId, requestId, attachments);
    }

    private Draft draftOn(long session, String requestId) {
        return draftOn(session, requestId, List.of());
    }

    private Draft draftOn(long session, String requestId, List<AttachmentPayload> attachments) {
        return new Draft(requestId, session, 1, "GLOBAL", 0, "Asia/Shanghai", "gen-test", true, 1L,
                REQUEST_META, REQUEST_META_CRYPTO, "中文问题", USER_CIPHER, USER_META, attachments, NOW);
    }

    private Terminal terminal(String status, String replyCipher) {
        return new Terminal(status, null, replyCipher, "{\"mime\":\"v1\"}", null, null,
                REQUEST_META, "{\"audit\":1}", "length", "saved", LATER);
    }

    private AttachmentPayload attachment(String id) {
        return new AttachmentPayload(id, "a0", "IMAGE", new byte[64], "{\"aad\":\"x\"}");
    }

    private long newSession(String title) {
        jdbc.update("INSERT INTO ai_chat_session(title) VALUES (?)", title);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session", Long.class);
    }

    private int revision() {
        return jdbc.queryForObject("SELECT history_revision FROM ai_chat_session WHERE id = ?",
                Integer.class, sessionId).intValue();
    }

    /** 该轮拥有的证据与事实关联：清理原文时必须一起删掉原话密文。 */
    private void seedEvidenceOwnedBy(long turnId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                    + " VALUES ('vault-1', 'dek', '{}', 'fingerprint', ?)", NOW);
            execute(connection, """
                    INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id, source_block_id,
                        source_hmac, quote_cipher, crypto_meta, source_created_at)
                    SELECT 'ev-1', 'vault-1', ?, ?, MIN(id), 'u0', 'hmac-1', '原始话语', '{}', ?
                    FROM ai_chat_message WHERE turn_id = ?
                    """, sessionId, turnId, NOW, turnId);
            execute(connection, """
                    INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,
                        predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,
                        observed_at, recorded_at, last_confirmed_at)
                    VALUES ('row-1', 'mem-1', 'vault-1', 1, 'GLOBAL', 0, 'PREFERENCE', 'p', 'f', 'v', 'ACTIVE',
                        'x', '{}', 'AUTO', ?, ?, ?)
                    """, NOW, NOW, NOW);
            execute(connection, "INSERT INTO ai_memory_item_evidence(item_row_id, evidence_id) VALUES ('row-1', 'ev-1')");
            connection.commit();
        }
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            statement.executeUpdate();
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
