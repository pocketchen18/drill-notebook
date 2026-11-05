package com.drillnotebook.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P0a 第④组的入库版对照实验：容量账本的 CAS 为什么必须走 BEGIN IMMEDIATE。
 *
 * <p>DEFERRED（生产 DatabaseConfig 的默认模式）下"先读账本再写"的两个事务，
 * 后手在锁升级瞬间直接拿到 SQLITE_BUSY——busy_timeout 不覆盖锁升级，服务层只能报 500；
 * IMMEDIATE 让后手排队等锁，醒来后 CAS 影响 0 行，于是能干净返回 HISTORY_STORAGE_FULL。
 * 这条用例就是防止将来有人把账本事务改回普通事务而静默退化。</p>
 */
class ImmediateTransactionTest {
    private static final long REPLY_RESERVATION = 2_097_152L;
    private static final long NEW_TURN_BYTES = 1_000L;
    private static final int RACERS = 3;

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ImmediateTransaction immediate;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-immediate-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        immediate = new ImmediateTransaction(dataSource);
        // 只留"恰好一轮"的余量：三个并发开始请求最多只能有一个装得下
        jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = history_bytes + reserved_history_bytes + ? + ?,"
                + " attachment_limit_bytes = attachment_limit_bytes + ?", REPLY_RESERVATION, NEW_TURN_BYTES, 1_000L);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void deferredReadThenWriteDiesWithBusyInsteadOfRefusing() throws Exception {
        CyclicBarrier allRead = new CyclicBarrier(RACERS);
        AtomicInteger applied = new AtomicInteger();
        List<SQLException> busy = new CopyOnWriteArrayList<>();
        long started = System.nanoTime();

        List<Boolean> finished = race(index -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);          // DEFERRED：与生产 DatabaseConfig 一致
                long revision = readLedgerRevision(connection);
                allRead.await(5, TimeUnit.SECONDS);       // 三个事务都读完后才尝试写
                if (casFit(connection, revision) > 0) {
                    applied.incrementAndGet();
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                busy.add(error);
                return false;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });

        assertEquals(RACERS, finished.size());
        assertEquals(1, applied.get(), "DEFERRED 下也只能有一个写者成功");
        assertFalse(busy.isEmpty(), "DEFERRED 的先读后写必须出现 SQLITE_BUSY（P0a 实测；否则本用例失去意义）");
        assertTrue(busy.stream().allMatch(error -> error.getMessage().toLowerCase().contains("locked")
                        || error.getMessage().contains("SQLITE_BUSY")),
                "失败原因必须是锁冲突，实际：" + busy.get(0).getMessage());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMillis < 4_000L, "锁升级失败应当立即发生而不是等满 busy_timeout，实际 " + elapsedMillis + "ms");
    }

    @Test
    void immediateTransactionSerialisesWritersAndRefusesWithoutException() throws Exception {
        List<Boolean> finished = race(index -> {
            try {
                long updated = immediate.execute(connection -> casFit(connection, readLedgerRevision(connection)));
                return updated > 0;
            } catch (SQLException error) {
                throw new IllegalStateException("IMMEDIATE 事务不应再遇到锁升级失败：" + error.getMessage(), error);
            }
        });

        long accepted = finished.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, accepted, "三个并发开始请求里只能有一个装得下，其余必须走 CAS 拒绝");

        Ledger ledger = readLedger();
        assertEquals(NEW_TURN_BYTES + REPLY_RESERVATION, ledger.historyBytes() + ledger.reservedBytes(),
                "账本只加一轮正文 + 一份回复预留");
        assertTrue(ledger.historyBytes() + ledger.reservedBytes() <= ledger.historyLimit(), "不得超卖");
        assertEquals(2, ledger.revision(), "revision 只推进一次：被拒绝的两个写者没碰到账本行");
    }

    @Test
    void immediateTransactionRollsBackWholeWorkOnFailure() throws Exception {
        long revisionBefore = readLedger().revision();
        assertThrows(SQLException.class, () -> immediate.execute(connection -> {
            casFit(connection, readLedgerRevision(connection));
            throw new SQLException("模拟加密或落库失败");
        }));

        Ledger ledger = readLedger();
        assertEquals(revisionBefore, ledger.revision(), "事务内异常必须整体回滚，账本不留半笔");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn", Integer.class));

        // 回滚后连接归还池，后续事务照常
        immediate.execute(connection -> casFit(connection, readLedgerRevision(connection)));
        assertTrue(readLedger().revision() > revisionBefore);
    }

    @Test
    void workSeesCommittedStateInOrder() throws Exception {
        immediate.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,"
                            + " context_generation, source_scope_type, source_scope_id, source_timezone,"
                            + " runtime_generation, started_at) VALUES ('tx-visible', 1, 'RUNNING', 'm', '{}', 1,"
                            + " 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', '2026-10-11T00:00:00.000Z')")) {
                statement.executeUpdate();
            }
            return null;
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id = 'tx-visible'",
                Integer.class));

        // 同一事务内可见自己未提交的写入；回滚后外部读不到
        assertThrows(SQLException.class, () -> immediate.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,"
                            + " context_generation, source_scope_type, source_scope_id, source_timezone,"
                            + " runtime_generation, started_at) VALUES ('tx-rollback', 1, 'RUNNING', 'm', '{}', 1,"
                            + " 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', '2026-10-11T00:00:00.000Z')")) {
                assertEquals(1, statement.executeUpdate());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM ai_chat_turn WHERE request_id = 'tx-rollback'");
                 ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(1, result.getInt(1), "同一事务内必须看到自己的写入");
            }
            throw new SQLException("回滚");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id = 'tx-rollback'",
                Integer.class));
    }

    // ---- helpers ----

    /** 契约 §2.7 的开始事务额度判定：CAS 同时检查 revision 与剩余额度。 */
    private static long casFit(Connection connection, long expectedRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage
                   SET history_bytes = history_bytes + ?,
                       reserved_history_bytes = reserved_history_bytes + ?,
                       revision = revision + 1
                 WHERE id = 1 AND revision = ?
                   AND history_bytes + reserved_history_bytes + ? + ? <= history_limit_bytes
                """)) {
            statement.setLong(1, NEW_TURN_BYTES);
            statement.setLong(2, REPLY_RESERVATION);
            statement.setLong(3, expectedRevision);
            statement.setLong(4, NEW_TURN_BYTES);
            statement.setLong(5, REPLY_RESERVATION);
            return statement.executeUpdate();
        }
    }

    private static long readLedgerRevision(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM ai_chat_storage WHERE id = 1");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("缺少账本行");
            return result.getLong(1);
        }
    }

    private Ledger readLedger() {
        return jdbc.queryForObject("SELECT revision, history_bytes, reserved_history_bytes, history_limit_bytes "
                + "FROM ai_chat_storage WHERE id = 1", (result, row) -> new Ledger(
                result.getLong("revision"), result.getLong("history_bytes"),
                result.getLong("reserved_history_bytes"), result.getLong("history_limit_bytes")));
    }

    private record Ledger(long revision, long historyBytes, long reservedBytes, long historyLimit) {
    }

    /** 并发起跑，返回每个线程是否成功；线程池用完即关，不留悬挂线程。 */
    private List<Boolean> race(java.util.function.IntFunction<Boolean> body) throws Exception {
        var pool = Executors.newFixedThreadPool(RACERS);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < RACERS; index++) {
                final int slot = index;
                futures.add(pool.submit(() -> body.apply(slot)));
            }
            var results = new java.util.ArrayList<Boolean>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "测试线程池必须收干净");
        }
    }
}
