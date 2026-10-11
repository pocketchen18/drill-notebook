package com.drillnotebook.app.memory.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.prototype.StorageLedgerPrototype.BeginResult;
import com.drillnotebook.app.memory.prototype.StorageLedgerPrototype.Ledger;
import com.drillnotebook.app.memory.prototype.StorageLedgerPrototype.TerminalResult;
import com.drillnotebook.app.memory.prototype.StorageLedgerPrototype.TurnSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0a 第④组：SQLite 容量账本与轮次预留的高风险假设（验收 H05/H06/H07/H08 的最小 fixture）。
 * 验证四条地基：并发预留不超卖、开始事务回滚不留残留、预留恰释放一次、超限库仍可读可统计。
 * 生产实现属于 P1（ChatStorageSizer/账本）与 P3（begin/finalize），此处不接任何产品代码。
 */
class StorageLedgerPrototypeTest {

    private static final String USER_CIPHER = "d" + "A".repeat(2_048);
    private static final String USER_META = "{\"salt\":\"s\",\"iv\":\"i\"}";
    private static final long TURN_BYTES = StorageLedgerPrototype.storedBytes(USER_CIPHER, USER_META);
    private static final long REPLY_RESERVE = StorageLedgerPrototype.REPLY_RESERVATION_BYTES;

    private Path workdir;

    private DataSource dataSource;

    @BeforeEach
    void createDatabase() throws Exception {
        workdir = StorageLedgerPrototype.scratchDirectory("p0a-storage-");
        dataSource = StorageLedgerPrototype.createDatabase(workdir.resolve("study.db"));
    }

    @AfterEach
    void removeScratch() throws Exception {
        StorageLedgerPrototype.deleteRecursively(workdir);
    }

    @Test
    void replyReservationBoundFitsInsideTwoMiB() {
        long worst = StorageLedgerPrototype.worstCaseReplyBytes();
        // contracts §2.7 要求：最坏封装 <1,433,000 字节，且必须低于 2,097,152 的预留
        assertTrue(worst < 1_433_000L, "回复封装上界应低于契约给出的 1,433,000 字节，实际 " + worst);
        assertTrue(worst < REPLY_RESERVE, "回复封装上界必须装进本轮预留");

        long fullCapture = StorageLedgerPrototype.OUTPUT_CAPTURE_RAW_BYTES;
        // 不能把 1MiB 原文当 1MiB 密文：Base64 + 标签 + crypto_meta 必然膨胀
        assertTrue(StorageLedgerPrototype.encapsulatedBytes(fullCapture) > fullCapture);
        assertEquals(4L * ((fullCapture + 16L + 2L) / 3L) + 4_096L, StorageLedgerPrototype.encapsulatedBytes(fullCapture));

        // 预留按最坏对半分配计算；把 1MiB 原文直接当密文会明显低估
        long half = fullCapture / 2;
        long splitWorst = StorageLedgerPrototype.encapsulatedBytes(half)
                + StorageLedgerPrototype.encapsulatedBytes(fullCapture - half)
                + StorageLedgerPrototype.encapsulatedBytes(StorageLedgerPrototype.RESULT_META_PLAIN_MAX);
        assertEquals(worst, splitWorst);
        assertTrue(fullCapture + StorageLedgerPrototype.RESULT_META_PLAIN_MAX < worst,
                "忽略 Base64 膨胀与 crypto_meta 的算法会低估回复占用");
    }

    @Test
    void captureLimitStopsOnCompleteCodepoints() {
        long limit = StorageLedgerPrototype.OUTPUT_CAPTURE_RAW_BYTES;

        String emoji = StorageLedgerPrototype.appendWithinCaptureLimit(null, "🙂".repeat(600_000));
        long emojiBytes = emoji.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(emojiBytes <= limit, "捕获上限不得被越过");
        assertTrue(limit - emojiBytes < 4, "截断余量应不足一个 emoji 码点，实际剩余 " + (limit - emojiBytes));
        assertEquals(emoji, new String(emoji.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals(emoji, StorageLedgerPrototype.appendWithinCaptureLimit(emoji, "🙂"), "到限后追加被拒绝且长度不变");

        String chinese = StorageLedgerPrototype.appendWithinCaptureLimit(null, "汉".repeat(400_000));
        long chineseBytes = chinese.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(chineseBytes <= limit);
        assertTrue(limit - chineseBytes < 3, "截断余量应不足一个中文字，实际剩余 " + (limit - chineseBytes));

        // 混合：只剩 1 字节时，4 字节 emoji 必须整体停止，而 1 字节 ASCII 恰好填满
        String seed = "x".repeat((int) limit - 1);
        assertEquals(seed, StorageLedgerPrototype.appendWithinCaptureLimit(seed, "🙂"));
        assertEquals(limit,
                StorageLedgerPrototype.appendWithinCaptureLimit(seed, "y").getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void concurrentBeginReservesOnlyOneTurnWithoutOversell() throws Exception {
        Ledger start = ledger();
        // 容量恰够一轮：已用 + 已预留 + 本轮新字节 + 回复预留
        setLimit(start.historyBytes() + start.reservedHistoryBytes() + TURN_BYTES + REPLY_RESERVE);

        long[] sessions = { createSession("并发A"), createSession("并发B"), createSession("并发C") };
        CountDownLatch ready = new CountDownLatch(sessions.length);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(sessions.length);
        List<Future<BeginResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < sessions.length; index++) {
                long sessionId = sessions[index];
                String requestId = requestId(900_000L + index);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId, USER_CIPHER, USER_META, 0);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<BeginResult> results = new ArrayList<>();
            for (Future<BeginResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter(BeginResult::accepted).count(), "只有一个请求能拿到额度：" + results);
            assertTrue(results.stream().filter(result -> !result.accepted())
                    .allMatch(result -> "HISTORY_STORAGE_FULL".equals(result.code())));

            Ledger after = ledger();
            assertEquals(start.historyBytes() + TURN_BYTES, after.historyBytes(), "只有胜出者计入账本");
            assertEquals(REPLY_RESERVE, after.reservedHistoryBytes() - start.reservedHistoryBytes(), "预留不得超卖");
            assertTrue(after.reservedHistoryBytes() >= 0);
            assertTrue(after.revision() > start.revision());
            assertEquals(1, messageCount(sessions[0]) + messageCount(sessions[1]) + messageCount(sessions[2]));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void beginRollbackLeavesNoLedgerOrTurnResidue() throws Exception {
        Ledger before = ledger();
        long sessionId = createSession("回滚");
        String requestId = requestId(101L);

        assertThrows(Exception.class, () -> StorageLedgerPrototype.beginTurnFailingBeforeCommit(
                dataSource, sessionId, requestId, USER_CIPHER, USER_META));

        Ledger after = ledger();
        assertEquals(before.historyBytes(), after.historyBytes(), "回滚后账本字节数必须复原");
        assertEquals(before.reservedHistoryBytes(), after.reservedHistoryBytes(), "回滚后不得留下预留");
        assertEquals(before.revision(), after.revision(), "回滚后 revision 不变");
        assertNull(StorageLedgerPrototype.readTurn(dataSource, requestId));
        assertEquals(0, messageCount(sessionId));

        // 回滚后同一会话仍可正常开始新轮次（不会被残留 RUNNING 卡住）
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId(102L), USER_CIPHER, USER_META, 0)
                .accepted());
    }

    @Test
    void finalizeSettlesActualBytesAndReleasesReservationOnce() throws Exception {
        Ledger before = ledger();
        long sessionId = createSession("完成");
        String requestId = requestId(103L);
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId, USER_CIPHER, USER_META, 0)
                .accepted());

        String replyCipher = "u" + "B".repeat(4_096);
        String replyMeta = "{\"salt\":\"s\",\"iv\":\"i\"}";
        String resultMetaCipher = "v" + "C".repeat(1_024);
        String resultMetaMeta = "{\"salt\":\"s\",\"iv\":\"i\"}";
        long replyBytes = StorageLedgerPrototype.storedBytes(replyCipher, replyMeta, resultMetaCipher, resultMetaMeta);

        Ledger settled = StorageLedgerPrototype.finalizeTurn(dataSource, requestId,
                replyCipher, replyMeta, resultMetaCipher, resultMetaMeta);
        assertEquals(before.historyBytes() + TURN_BYTES + replyBytes, settled.historyBytes(), "按实际落库字节结算");
        assertEquals(before.reservedHistoryBytes(), settled.reservedHistoryBytes(), "预留应全部释放");

        TurnSnapshot turn = StorageLedgerPrototype.readTurn(dataSource, requestId);
        assertEquals("COMPLETED", turn.status());
        assertTrue(turn.reservationReleased());

        // 孤儿恢复/失败收尾重复释放：既不二次扣账，也不产生负数
        assertFalse(StorageLedgerPrototype.releaseReservation(dataSource, requestId), "已结算轮次不得再次释放");
        Ledger repeated = ledger();
        assertEquals(settled.historyBytes(), repeated.historyBytes());
        assertEquals(settled.reservedHistoryBytes(), repeated.reservedHistoryBytes());

        // 实际回复超出预留时拒绝结算，不静默扩大额度
        String oversize = "w" + "D".repeat((int) REPLY_RESERVE);
        String lateRequest = requestId(104L);
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, createSession("超限回复"), lateRequest,
                USER_CIPHER, USER_META, 0).accepted());
        assertThrows(IllegalStateException.class, () -> StorageLedgerPrototype.finalizeTurn(
                dataSource, lateRequest, oversize, replyMeta, oversize, replyMeta));
        TurnSnapshot stillRunning = StorageLedgerPrototype.readTurn(dataSource, lateRequest);
        assertEquals("RUNNING", stillRunning.status(), "结算失败后不得伪装成 COMPLETED");
    }

    @Test
    void failureFinalizeAfterCommittedTurnKeepsExistingTerminal() throws Exception {
        long sessionId = createSession("已提交后抛错");
        String requestId = requestId(105L);
        StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId, USER_CIPHER, USER_META, 0);
        String replyCipher = "x" + "E".repeat(64);
        String replyMeta = "{\"salt\":\"s\",\"iv\":\"i\"}";
        StorageLedgerPrototype.finalizeTurn(dataSource, requestId, replyCipher, replyMeta, replyCipher, replyMeta);

        Ledger before = ledger();
        // commit 已成功、应用层却收到异常，此时走 §7.2.1 失败收尾路径
        TerminalResult result = StorageLedgerPrototype.finalizeFailure(dataSource, requestId);
        assertTrue(result.alreadyTerminal(), "必须复用既有终态而不是改判失败");
        assertEquals("COMPLETED", result.status());
        assertTrue(result.stateDurable());

        Ledger after = ledger();
        assertEquals(before.historyBytes(), after.historyBytes(), "已提交轮次不得被改成失败");
        assertEquals(before.reservedHistoryBytes(), after.reservedHistoryBytes());
        assertEquals(0, after.reservedHistoryBytes());
        assertEquals("COMPLETED", statusOf(requestId));
    }

    @Test
    void failureFinalizeMarksFailedAndKeepsUserInputUnlearnable() throws Exception {
        Ledger before = ledger();
        long sessionId = createSession("收尾失败");
        String requestId = requestId(106L);
        StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId, USER_CIPHER, USER_META, 0);

        TerminalResult result = StorageLedgerPrototype.finalizeFailure(dataSource, requestId);
        assertEquals("FAILED", result.status());
        assertTrue(result.stateDurable());
        assertFalse(result.alreadyTerminal());

        Ledger after = ledger();
        assertEquals(before.reservedHistoryBytes(), after.reservedHistoryBytes(), "失败收尾释放本轮预留");
        assertEquals(before.historyBytes() + TURN_BYTES, after.historyBytes(), "用户输入必须保留");
        assertEquals(0, memoryEligible(requestId), "失败轮次不得参与学习");
        assertEquals("failed", persistenceStatus(requestId));

        // 重复收尾不再二次扣账
        TerminalResult again = StorageLedgerPrototype.finalizeFailure(dataSource, requestId);
        assertEquals("FAILED", again.status());
        assertTrue(again.alreadyTerminal());
        assertEquals(after.historyBytes(), ledger().historyBytes());

        // 该会话仍可继续新轮次
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId(107L), USER_CIPHER, USER_META, 0)
                .accepted());
    }

    @Test
    void overLimitLedgerStaysReadableAndBlocksNewTurnsUntilRaised() throws Exception {
        long sessionId = createSession("超限");
        String first = requestId(108L);
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, first, USER_CIPHER, USER_META, 0).accepted());
        StorageLedgerPrototype.finalizeFailure(dataSource, first);

        // 统计口径按 UTF-8 字节且不解密：明文中文行按字节计入测量值
        Ledger settled = ledger();
        long measuredBefore = StorageLedgerPrototype.measureStoredBytes(dataSource);
        long chineseBytes = insertPlaintextChineseMessage(sessionId, "质能方程");
        assertEquals(measuredBefore + chineseBytes, StorageLedgerPrototype.measureStoredBytes(dataSource),
                "计数必须按落库字节而不是字符数");
        assertEquals(chineseBytes, "质能方程".getBytes(StandardCharsets.UTF_8).length);
        // 绕过 begin/finalize 的写入不会更新账本：P1 必须让计数与写入同事务，否则账本失真
        assertEquals(settled.historyBytes(), ledger().historyBytes(), "绕过账本的写入会让账本与实测脱节（P1 需同事务）");

        setLimit(ledger().historyBytes());
        BeginResult rejected = StorageLedgerPrototype.beginTurn(dataSource, createSession("超限2"), requestId(109L),
                USER_CIPHER, USER_META, 0);
        assertFalse(rejected.accepted());
        assertEquals("HISTORY_STORAGE_FULL", rejected.code());
        // 历史仍可读：不删任何数据
        assertEquals(settled.historyBytes(), ledger().historyBytes());
        assertEquals(2, messageCount(sessionId), "超限只挡新请求，不删旧消息");

        // 不允许把额度降到 used + reserved 以下
        Ledger current = ledger();
        assertFalse(StorageLedgerPrototype.updateHistoryLimit(dataSource, current.revision(), current.historyBytes() - 1));
        assertEquals(current.historyBytes(), ledger().historyBytes(), "降额失败不得改账本");

        // 显式调高额度后可以继续发送
        assertTrue(StorageLedgerPrototype.updateHistoryLimit(dataSource, ledger().revision(),
                current.historyBytes() + 4_194_304L));
        String raised = requestId(110L);
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, createSession("超限3"), raised,
                USER_CIPHER, USER_META, 0).accepted());
        assertEquals(current.historyBytes() + TURN_BYTES, ledger().historyBytes());
    }

    @Test
    void onlyOneRunningTurnPerSession() throws Exception {
        long sessionId = createSession("同会话双请求");
        String first = requestId(111L);
        String second = requestId(112L);
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, first, USER_CIPHER, USER_META, 0).accepted());

        // 会话级部分唯一索引挡住第二个 RUNNING（P3 映射为 409 SESSION_BUSY）
        assertThrows(Exception.class, () -> StorageLedgerPrototype.beginTurn(
                dataSource, sessionId, second, USER_CIPHER, USER_META, 0));
        assertEquals("RUNNING", statusOf(first));
        assertNull(StorageLedgerPrototype.readTurn(dataSource, second), "被拒的第二请求不得留下轮次行");

        // 上一轮进入终态后，同会话可以再来
        StorageLedgerPrototype.finalizeFailure(dataSource, first);
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, second, USER_CIPHER, USER_META, 0).accepted());
    }

    @Test
    void sameRequestIdReplayDoesNotReserveTwice() throws Exception {
        long sessionId = createSession("幂等");
        String requestId = requestId(113L);
        Ledger before = ledger();
        assertTrue(StorageLedgerPrototype.beginTurn(dataSource, sessionId, requestId, USER_CIPHER, USER_META, 0)
                .accepted());
        // 同 requestId 重发时 P3 先查既有轮次；账本层必须因主键冲突而拒绝第二次预留
        assertThrows(Exception.class, () -> StorageLedgerPrototype.beginTurn(
                dataSource, sessionId, requestId, USER_CIPHER, USER_META, 0));
        Ledger after = ledger();
        assertEquals(before.historyBytes() + TURN_BYTES, after.historyBytes(), "重发不得重复计入字节");
        assertEquals(before.reservedHistoryBytes() + REPLY_RESERVE, after.reservedHistoryBytes(), "重发不得重复预留");
    }

    private String requestId(long sequence) {
        return String.format("018f0000-0000-7000-8000-%012d", sequence);
    }

    private Ledger ledger() throws Exception {
        return StorageLedgerPrototype.readLedger(dataSource);
    }

    private String statusOf(String requestId) throws Exception {
        TurnSnapshot turn = StorageLedgerPrototype.readTurn(dataSource, requestId);
        return turn == null ? null : turn.status();
    }

    private void setLimit(long historyLimit) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var update = connection.prepareStatement("UPDATE ai_chat_storage SET history_limit_bytes = ? WHERE id = 1")) {
            update.setLong(1, historyLimit);
            update.executeUpdate();
        }
    }

    private long createSession(String title) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("INSERT INTO ai_chat_session(title) VALUES (?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, title);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next(), "会话插入后必须取得 ID");
                return keys.getLong(1);
            }
        }
    }

    private int messageCount(long sessionId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private long insertPlaintextChineseMessage(long sessionId, String text) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'assistant', ?)")) {
            statement.setLong(1, sessionId);
            statement.setString(2, text);
            statement.executeUpdate();
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private int memoryEligible(String requestId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT memory_eligible FROM p0a_turn WHERE request_id = ?")) {
            statement.setString(1, requestId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    private String persistenceStatus(String requestId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT persistence_status FROM p0a_turn WHERE request_id = ?")) {
            statement.setString(1, requestId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
