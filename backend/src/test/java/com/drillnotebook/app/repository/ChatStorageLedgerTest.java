package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 容量账本（contracts §2.7）：预留/结算/释放的算术、额度调整规则，以及"对账只读不覆盖差异"。
 *
 * <p>并发抢额度的用例放在 {@link ChatTurnRepositoryTest}，因为只有 begin 才是生产入口。</p>
 */
class ChatStorageLedgerTest {
    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ImmediateTransaction immediate;
    private ChatStorageLedger ledger;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-ledger-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        immediate = new ImmediateTransaction(dataSource);
        ledger = new ChatStorageLedger(dataSource, immediate);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void freshLedgerStartsAtZeroWithTwoHundredFiftySixMibLimits() throws Exception {
        ChatStorageLedger.Snapshot snapshot = ledger.read();
        assertEquals(268_435_456L, snapshot.historyLimitBytes());
        assertEquals(268_435_456L, snapshot.attachmentLimitBytes());
        assertEquals(0, snapshot.historyBytes());
        assertEquals(0, snapshot.reservedHistoryBytes());
        assertFalse(snapshot.historyWarning());
    }

    @Test
    void reserveAddsBothCountersAndBumpsRevision() throws Exception {
        ChatStorageLedger.Snapshot before = ledger.read();
        assertSame(ChatStorageLedger.Fit.ACCEPTED,
                immediate.execute(connection -> ledger.reserve(connection, 500, 100)));

        ChatStorageLedger.Snapshot after = ledger.read();
        assertEquals(500, after.historyBytes(), "本轮正文即时计入存量");
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, after.reservedHistoryBytes(), "回复预留是另一本账");
        assertEquals(100, after.attachmentBytes());
        assertEquals(before.revision() + 1, after.revision());
    }

    @Test
    void settleMovesReservationIntoStoredExactlyOnce() throws Exception {
        immediate.execute(connection -> {
            ledger.reserve(connection, 400, 0);
            return null;
        });
        immediate.execute(connection -> {
            ledger.settle(connection, 1_000, ChatStorageSizer.REPLY_RESERVATION_BYTES);
            return null;
        });
        ChatStorageLedger.Snapshot settled = ledger.read();
        assertEquals(1_400, settled.historyBytes());
        assertEquals(0, settled.reservedHistoryBytes());

        // 同一笔预留只能释放一次：第二次必须失败，而不是用 MAX(0,…) 把重复扣减掩盖掉
        assertThrows(SQLException.class, () -> immediate.execute(connection -> {
            ledger.settle(connection, 0, ChatStorageSizer.REPLY_RESERVATION_BYTES);
            return null;
        }));
        assertEquals(1_400, ledger.read().historyBytes(), "失败的结算不得改动账本");
    }

    @Test
    void releaseReservationOnlyShrinksWhatIsActuallyReserved() throws Exception {
        assertThrows(SQLException.class, () -> immediate.execute(connection -> {
            ledger.releaseReservation(connection, 1_024);
            return null;
        }), "没有预留时释放必须失败，不能把账本写成负数");

        immediate.execute(connection -> {
            ledger.reserve(connection, 10, 0);
            return null;
        });
        long revisionAfterReserve = ledger.read().revision();
        immediate.execute(connection -> {
            ledger.releaseReservation(connection, ChatStorageSizer.REPLY_RESERVATION_BYTES);
            return null;
        });
        assertEquals(0, ledger.read().reservedHistoryBytes());
        assertEquals(10, ledger.read().historyBytes(), "只释放预留不得动存量");
        assertTrue(ledger.read().revision() > revisionAfterReserve);
    }

    @Test
    void releaseStorageSubtractsBothCountersAndRefusesUnderflow() throws Exception {
        immediate.execute(connection -> {
            ledger.reserve(connection, 300, 50);
            return null;
        });
        immediate.execute(connection -> {
            ledger.releaseStorage(connection, 300, 50);
            return null;
        });
        assertEquals(0, ledger.read().historyBytes());
        assertEquals(0, ledger.read().attachmentBytes());

        assertThrows(SQLException.class, () -> immediate.execute(connection -> {
            ledger.releaseStorage(connection, 1, 0);
            return null;
        }));
    }

    @Test
    void rebuildRecomputesFromActualRowsWithoutDecrypting() throws Exception {
        // 迁移前的 legacy 路径直接写行、不记账；rebuild 必须把实测搬进账本
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content, content_cipher, reasoning_cipher)"
                + " VALUES (1, ?, 'user', '旧明文', 'cipher-text', 'reasoning-text')", sessionId);
        insertTurn(7, ChatTurnRepository.STATUS_RUNNING, 0);
        assertEquals(0, ledger.read().historyBytes(), "绕过账本的写入不会自动记账");

        ChatStorageLedger.Snapshot rebuilt = ledger.rebuild();
        long expected = "旧明文".getBytes(StandardCharsets.UTF_8).length
                + "cipher-text".length() + "reasoning-text".length()
                + "m".length() + "{}".length();
        assertEquals(expected, rebuilt.historyBytes());
        assertTrue(ledger.reconcile().consistent(), "重建后实测与账本必须一致");
    }

    @Test
    void rebuildKeepsLiveReservationsFromRunningTurns() throws Exception {
        // 预留按仍在 RUNNING 的轮次实测保留：孤儿恢复（P3）之后才逐轮释放，rebuild 不能先把它抹掉
        insertTurn(8, ChatTurnRepository.STATUS_RUNNING, ChatStorageSizer.REPLY_RESERVATION_BYTES);
        long history = ledger.rebuild().historyBytes();
        ChatStorageLedger.Snapshot rebuilt = ledger.rebuild();
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, rebuilt.reservedHistoryBytes());
        assertEquals(history, rebuilt.historyBytes());
    }

    @Test
    void reconcileReportsDriftWithoutOverwriting() throws Exception {
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content) VALUES (5, ?, 'user', '未记账的旧消息')",
                sessionId);
        ChatStorageLedger.ReconcileReport report = ledger.reconcile();
        assertFalse(report.consistent());
        assertEquals(0, report.ledgerHistoryBytes(), "对账只读：不许顺手把差异改平");
        assertTrue(report.measuredHistoryBytes() > 0);
        assertEquals(0, ledger.read().historyBytes());
    }

    @Test
    void limitUpdateRequiresRevisionAndRoom() throws Exception {
        immediate.execute(connection -> {
            ledger.reserve(connection, 1_000, 200);
            return null;
        });
        ChatStorageLedger.Snapshot used = ledger.read();

        assertSame(ChatStorageLedger.LimitResult.REVISION_CONFLICT,
                ledger.updateLimits(used.revision() - 1, 1_000_000, 1_000_000));
        assertSame(ChatStorageLedger.LimitResult.BELOW_USED,
                ledger.updateLimits(used.revision(), 500, 1_000_000), "历史额度不得降到 used+预留 之下");
        assertSame(ChatStorageLedger.LimitResult.BELOW_USED,
                ledger.updateLimits(used.revision(), 1_000_000, 100), "附件额度不得低于已用");
        assertSame(ChatStorageLedger.LimitResult.INVALID_VALUE,
                ledger.updateLimits(used.revision(), 0, 1_000_000));

        assertSame(ChatStorageLedger.LimitResult.APPLIED,
                ledger.updateLimits(used.revision(), 4_000_000, 3_000_000));
        ChatStorageLedger.Snapshot updated = ledger.read();
        assertEquals(4_000_000, updated.historyLimitBytes());
        assertEquals(1_000, updated.historyBytes(), "调额度不清任何记录");
        assertEquals(used.revision() + 1, updated.revision());
        assertEquals(200, updated.attachmentBytes(), "调整额度不得清掉任何已记字节");
    }

    @Test
    void warningTurnsOnAtEightyPercent() throws Exception {
        // 用整数额度，避免整除截断把边界测试变成算术测试
        jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = 1000");
        jdbc.update("UPDATE ai_chat_storage SET history_bytes = 799");
        assertFalse(ledger.read().historyWarning());
        jdbc.update("UPDATE ai_chat_storage SET history_bytes = 800");
        assertTrue(ledger.read().historyWarning());
        // 预留也算进预警口径：正在生成回复时不能显示"还有余量"
        jdbc.update("UPDATE ai_chat_storage SET history_bytes = 0, reserved_history_bytes = 800");
        assertTrue(ledger.read().historyWarning());
        jdbc.update("UPDATE ai_chat_storage SET attachment_limit_bytes = 100, attachment_bytes = 79");
        assertFalse(ledger.read().attachmentWarning());
        jdbc.update("UPDATE ai_chat_storage SET attachment_bytes = 80");
        assertTrue(ledger.read().attachmentWarning());
    }

    // ---- helpers ----

    private void insertTurn(long id, String status, long reserved) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                INSERT INTO ai_chat_turn(id, request_id, session_id, status, request_meta_cipher,
                    request_meta_crypto, context_generation, source_scope_type, source_scope_id, source_timezone,
                    runtime_generation, stored_history_bytes, reserved_history_bytes, started_at)
                VALUES (?, ?, ?, ?, 'm', '{}', 1, 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', 0, ?,
                    '2026-10-11T00:00:00.000Z')
                """)) {
            statement.setLong(1, id);
            statement.setString(2, "req-" + id + "-" + status);
            statement.setLong(3, sessionId);
            statement.setString(4, status);
            statement.setLong(5, reserved);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }
}
