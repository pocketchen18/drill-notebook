package com.drillnotebook.app.memory.prototype;

import com.drillnotebook.app.config.DatabaseInitializer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * P0a 第④组原型（执行方案 §14 P0a、验收 §10.1.1）：把 contracts §2.7 容量账本与
 * contracts §7 beginTurn/finalizeTurn 伪代码写成可执行形状，先验证三条地基假设——
 * 并发预留不超卖、开始事务回滚不留残留、预留只释放一次。
 *
 * <p>只存在于测试作用域，生产路径不引用；P1 会把计数与 CAS 形状迁入 ChatStorageSizer /
 * ChatTurnRepository。账本表照 contracts §2.7 蓝图，轮次表用 {@code p0a_turn} 复刻
 * 账本所需的最小列（P1 才建正式 ai_chat_turn）。</p>
 */
public final class StorageLedgerPrototype {

    /** contracts §2.7：正文逻辑存储与附件密文各 256 MiB。 */
    public static final long HISTORY_LIMIT_DEFAULT = 268_435_456L;
    public static final long ATTACHMENT_LIMIT_DEFAULT = 268_435_456L;
    /** 开始轮次为回复预留的 2 MiB。 */
    public static final long REPLY_RESERVATION_BYTES = 2_097_152L;
    /** 正文 + reasoning 的原始 UTF-8 捕获上限（两者合计）。 */
    public static final long OUTPUT_CAPTURE_RAW_BYTES = 1_048_576L;
    /** result_meta 明文上限。 */
    public static final long RESULT_META_PLAIN_MAX = 16_384L;
    /** 每份 crypto_meta 上限。 */
    public static final long CRYPTO_META_MAX = 4_096L;
    /** AES-GCM 认证标签。 */
    public static final long GCM_TAG_BYTES = 16L;

    private StorageLedgerPrototype() {
    }

    public record Ledger(long revision, long historyBytes, long reservedHistoryBytes, long historyLimit) {
    }

    public record BeginResult(boolean accepted, String code, Ledger ledger) {
    }

    public record TerminalResult(String status, boolean stateDurable, boolean alreadyTerminal) {
    }

    public record TurnSnapshot(String status, long reserved, boolean reservationReleased) {
    }

    /**
     * 测试临时目录放在仓库内 {@code tmp/}（已 gitignore），不写系统 %TEMP%：执行方案 §14.3
     * 要求新 fixture 显式定位 APP_ROOT 内的测试临时区。
     */
    public static java.nio.file.Path scratchDirectory(String prefix) throws java.io.IOException {
        return com.drillnotebook.app.memory.TestWorkspace.scratchDirectory(prefix);
    }

    public static void deleteRecursively(java.nio.file.Path root) throws java.io.IOException {
        com.drillnotebook.app.memory.TestWorkspace.deleteRecursively(root);
    }

    /**
     * pragma 组合与 DatabaseConfig 一致，让并发写行为和生产相同.
     *
     * <p>P0a 实测结论：项目现在的 {@code DatabaseConfig} 没有设置事务模式，默认 DEFERRED——
     * 两个连接先读账本再写时，后手在锁升级瞬间直接拿到 SQLITE_BUSY（database is locked），
     * busy_timeout 对这种升级不生效。容量账本要靠 CAS 判定胜负，就必须用 IMMEDIATE 事务，
     * 让第二个写者排队等锁、醒来后 CAS 影响 0 行并干净地返回 HISTORY_STORAGE_FULL。</p>
     */
    public static DataSource createDatabase(java.nio.file.Path file) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5_000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file);
        new DatabaseInitializer(dataSource).initialize();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ai_chat_storage (
                      id INTEGER PRIMARY KEY CHECK(id = 1),
                      revision INTEGER NOT NULL DEFAULT 1,
                      history_limit_bytes INTEGER NOT NULL DEFAULT 268435456 CHECK(history_limit_bytes > 0),
                      attachment_limit_bytes INTEGER NOT NULL DEFAULT 268435456 CHECK(attachment_limit_bytes > 0),
                      history_bytes INTEGER NOT NULL DEFAULT 0 CHECK(history_bytes >= 0),
                      reserved_history_bytes INTEGER NOT NULL DEFAULT 0 CHECK(reserved_history_bytes >= 0),
                      attachment_bytes INTEGER NOT NULL DEFAULT 0 CHECK(attachment_bytes >= 0)
                    )
                    """);
            statement.execute("INSERT OR IGNORE INTO ai_chat_storage(id) VALUES (1)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS p0a_turn (
                      request_id TEXT PRIMARY KEY,
                      session_id INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      persistence_status TEXT,
                      memory_eligible INTEGER NOT NULL DEFAULT 1,
                      reserved_history_bytes INTEGER NOT NULL,
                      settled_bytes INTEGER NOT NULL DEFAULT 0,
                      reservation_released INTEGER NOT NULL DEFAULT 0,
                      created_at TEXT NOT NULL,
                      finished_at TEXT
                    )
                    """);
            // §4 不变量 1：一个会话最多一个运行中轮次。
            statement.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS p0a_turn_running ON p0a_turn(session_id) WHERE status = 'RUNNING'");
        }
        return dataSource;
    }

    public static Ledger readLedger(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return readLedger(connection);
        }
    }

    private static Ledger readLedger(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT revision, history_bytes, reserved_history_bytes, history_limit_bytes
                       FROM ai_chat_storage WHERE id = 1
                     """)) {
            if (!rows.next()) {
                throw new IllegalStateException("容量账本不存在");
            }
            return new Ledger(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
        }
    }

    /**
     * contracts §2.7 的计数口径：按实际落库字段的 UTF-8 字节长度统计，NULL 记 0，
     * 不解密也不按 Token 估算。这里覆盖消息五列（正式实现再加轮次请求/结果元数据列）。
     */
    public static long measureStoredBytes(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT COALESCE(SUM(
                         length(CAST(COALESCE(content, '') AS BLOB))
                       + length(CAST(COALESCE(content_cipher, '') AS BLOB))
                       + length(CAST(COALESCE(content_meta, '') AS BLOB))
                       + length(CAST(COALESCE(reasoning_cipher, '') AS BLOB))
                       + length(CAST(COALESCE(reasoning_meta, '') AS BLOB))
                     ), 0) FROM ai_chat_message
                     """)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /** 单条记录的确切落库字节；与 measureStoredBytes 对同一行应得的结果一致。 */
    public static long storedBytes(String... columns) {
        long total = 0;
        for (String column : columns) {
            total += column == null ? 0 : column.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    public static long base64CipherBytes(long plainBytes) {
        long withTag = plainBytes + GCM_TAG_BYTES;
        return 4L * ((withTag + 2L) / 3L);
    }

    /** 封装上界：Base64(明文 + GCM tag) + crypto_meta。 */
    public static long encapsulatedBytes(long plainBytes) {
        return base64CipherBytes(plainBytes) + CRYPTO_META_MAX;
    }

    /**
     * 回复预留的可验证上界（contracts §2.7）：正文与 reasoning 合计 1MiB 原文按最坏对半分配
     * 各自封装，再加 result_meta 与三份 crypto_meta。
     */
    public static long worstCaseReplyBytes() {
        long half = OUTPUT_CAPTURE_RAW_BYTES / 2;
        return encapsulatedBytes(half) + encapsulatedBytes(OUTPUT_CAPTURE_RAW_BYTES - half)
                + encapsulatedBytes(RESULT_META_PLAIN_MAX);
    }

    /**
     * contracts §7 beginTurn 的账本部分：短事务内 CAS 检查额度，命中才原子写 turn/user。
     * 额度不足回滚并返回 HISTORY_STORAGE_FULL，不留 RUNNING。
     */
    public static BeginResult beginTurn(DataSource dataSource, long sessionId, String requestId, String userCipher,
            String userMeta, long attachmentBytes) throws SQLException {
        long newBytes = storedBytes(userCipher, userMeta);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long revision = readLedger(connection).revision();
                int updated;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ai_chat_storage
                           SET history_bytes = history_bytes + ?,
                               reserved_history_bytes = reserved_history_bytes + ?,
                               attachment_bytes = attachment_bytes + ?,
                               revision = revision + 1
                         WHERE id = 1 AND revision = ?
                           AND history_bytes + reserved_history_bytes + ? + ? <= history_limit_bytes
                           AND attachment_bytes + ? <= attachment_limit_bytes
                        """)) {
                    statement.setLong(1, newBytes);
                    statement.setLong(2, REPLY_RESERVATION_BYTES);
                    statement.setLong(3, attachmentBytes);
                    statement.setLong(4, revision);
                    statement.setLong(5, newBytes);
                    statement.setLong(6, REPLY_RESERVATION_BYTES);
                    statement.setLong(7, attachmentBytes);
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    connection.rollback();
                    return new BeginResult(false, "HISTORY_STORAGE_FULL", readLedger(dataSource));
                }
                try (PreparedStatement turn = connection.prepareStatement("""
                        INSERT INTO p0a_turn(request_id, session_id, status, reserved_history_bytes, created_at)
                        VALUES (?, ?, 'RUNNING', ?, datetime('now'))
                        """)) {
                    turn.setString(1, requestId);
                    turn.setLong(2, sessionId);
                    turn.setLong(3, REPLY_RESERVATION_BYTES);
                    turn.executeUpdate();
                }
                insertMessage(connection, sessionId, "user", userCipher, userMeta);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return new BeginResult(true, "OK", readLedger(dataSource));
    }

    /** 注入点：在写入 turn 之后、提交之前抛错，用来验证开始事务回滚不留残留。 */
    public static BeginResult beginTurnFailingBeforeCommit(DataSource dataSource, long sessionId, String requestId,
            String userCipher, String userMeta) throws SQLException {
        long newBytes = storedBytes(userCipher, userMeta);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long revision = readLedger(connection).revision();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ai_chat_storage
                           SET history_bytes = history_bytes + ?,
                               reserved_history_bytes = reserved_history_bytes + ?,
                               revision = revision + 1
                         WHERE id = 1 AND revision = ?
                           AND history_bytes + reserved_history_bytes + ? + ? <= history_limit_bytes
                        """)) {
                    statement.setLong(1, newBytes);
                    statement.setLong(2, REPLY_RESERVATION_BYTES);
                    statement.setLong(3, revision);
                    statement.setLong(4, newBytes);
                    statement.setLong(5, REPLY_RESERVATION_BYTES);
                    statement.executeUpdate();
                }
                try (PreparedStatement turn = connection.prepareStatement("""
                        INSERT INTO p0a_turn(request_id, session_id, status, reserved_history_bytes, created_at)
                        VALUES (?, ?, 'RUNNING', ?, datetime('now'))
                        """)) {
                    turn.setString(1, requestId);
                    turn.setLong(2, sessionId);
                    turn.setLong(3, REPLY_RESERVATION_BYTES);
                    turn.executeUpdate();
                }
                throw new SQLException("模拟开始事务提交前故障");
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * contracts §7 finalizeTurn 的账本部分：结算实际字节、断言增量不超过本轮预留、
     * 在同一事务里释放预留并 CAS 改写轮次终态。
     */
    public static Ledger finalizeTurn(DataSource dataSource, String requestId, String assistantCipher,
            String assistantMeta, String resultMetaCipher, String resultMetaMeta) throws SQLException {
        long actualBytes = storedBytes(assistantCipher, assistantMeta, resultMetaCipher, resultMetaMeta);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TurnSnapshot turn = readTurn(connection, requestId);
                if (turn == null) {
                    throw new IllegalStateException("轮次不存在: " + requestId);
                }
                if (!"RUNNING".equals(turn.status())) {
                    connection.rollback();
                    return readLedger(connection);
                }
                if (actualBytes > turn.reserved()) {
                    throw new IllegalStateException(
                            "回复实际字节 " + actualBytes + " 超出本轮预留 " + turn.reserved());
                }
                long sessionId = readSessionId(connection, requestId);
                Ledger ledger = readLedger(connection);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ai_chat_storage
                           SET history_bytes = history_bytes + ?,
                               reserved_history_bytes = reserved_history_bytes - ?,
                               revision = revision + 1
                         WHERE id = 1 AND revision = ?
                        """)) {
                    statement.setLong(1, actualBytes);
                    statement.setLong(2, turn.reserved());
                    statement.setLong(3, ledger.revision());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("账本 CAS 失败");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE p0a_turn
                           SET status = 'COMPLETED', persistence_status = 'saved', settled_bytes = ?,
                               reservation_released = 1, finished_at = datetime('now')
                         WHERE request_id = ? AND status = 'RUNNING'
                        """)) {
                    statement.setLong(1, actualBytes);
                    statement.setString(2, requestId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("轮次 CAS 失败");
                    }
                }
                insertMessage(connection, sessionId, "assistant", assistantCipher, assistantMeta);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return readLedger(dataSource);
    }

    /**
     * §7.2.1 完成失败收尾：先按 turn ID 重读，已终态就复用既有结果；仍 RUNNING 才另开短事务
     * CAS 成 FAILED、persistence_status=failed、memory_eligible=0，并只释放本轮预留一次。
     * 连失败收尾都写不进去时返回 stateDurable=false。
     */
    public static TerminalResult finalizeFailure(DataSource dataSource, String requestId) throws SQLException {
        TurnSnapshot existing = readTurn(dataSource, requestId);
        if (existing == null) {
            return new TerminalResult("MISSING", false, false);
        }
        if (!"RUNNING".equals(existing.status())) {
            return new TerminalResult(existing.status(), true, true);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int cas;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE p0a_turn
                           SET status = 'FAILED', persistence_status = 'failed', memory_eligible = 0,
                               finished_at = datetime('now')
                         WHERE request_id = ? AND status = 'RUNNING'
                        """)) {
                    statement.setString(1, requestId);
                    cas = statement.executeUpdate();
                }
                if (cas != 1) {
                    connection.rollback();
                    TurnSnapshot reread = readTurn(dataSource, requestId);
                    return new TerminalResult(reread == null ? "MISSING" : reread.status(), true, true);
                }
                releaseReservationOnce(connection, requestId, existing.reserved());
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                return new TerminalResult("FAILED", false, false);
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return new TerminalResult("FAILED", true, false);
    }

    /** 孤儿恢复/失败收尾共用：重复调用不会二次扣减预留。 */
    public static boolean releaseReservation(DataSource dataSource, String requestId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TurnSnapshot turn = readTurn(connection, requestId);
                if (turn == null) {
                    connection.rollback();
                    return false;
                }
                boolean released = releaseReservationOnce(connection, requestId, turn.reserved());
                connection.commit();
                return released;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static boolean releaseReservationOnce(Connection connection, String requestId, long reserved)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE p0a_turn SET reservation_released = 1
                 WHERE request_id = ? AND reservation_released = 0
                """)) {
            statement.setString(1, requestId);
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        Ledger ledger = readLedger(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage
                   SET reserved_history_bytes = reserved_history_bytes - ?, revision = revision + 1
                 WHERE id = 1 AND revision = ? AND reserved_history_bytes >= ?
                """)) {
            statement.setLong(1, reserved);
            statement.setLong(2, ledger.revision());
            statement.setLong(3, reserved);
            return statement.executeUpdate() == 1;
        }
    }

    public static TurnSnapshot readTurn(DataSource dataSource, String requestId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return readTurn(connection, requestId);
        }
    }

    private static TurnSnapshot readTurn(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, reserved_history_bytes, reservation_released FROM p0a_turn WHERE request_id = ?
                """)) {
            statement.setString(1, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new TurnSnapshot(rows.getString(1), rows.getLong(2), rows.getInt(3) == 1);
            }
        }
    }

    /** 容量策略更新：不允许降到 used + reserved 以下（contracts §2.7）。 */
    public static boolean updateHistoryLimit(DataSource dataSource, long expectedRevision, long newLimit)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE ai_chat_storage
                        SET history_limit_bytes = ?, revision = revision + 1
                      WHERE id = 1 AND revision = ? AND ? >= history_bytes + reserved_history_bytes
                     """)) {
            statement.setLong(1, newLimit);
            statement.setLong(2, expectedRevision);
            statement.setLong(3, newLimit);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 输出捕获上限（H06）：按码点累加正文/reasoning 的原始 UTF-8 字节，装不下的码点起整体停止，
     * 绝不切坏 UTF-8；返回值为可安全保存的完整码点前缀。
     */
    public static String appendWithinCaptureLimit(String accumulated, String chunk) {
        String base = accumulated == null ? "" : accumulated;
        if (chunk == null || chunk.isEmpty()) {
            return base;
        }
        long used = base.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder kept = new StringBuilder(base);
        for (int offset = 0; offset < chunk.length();) {
            int codePoint = chunk.codePointAt(offset);
            int units = Character.charCount(codePoint);
            long codePointBytes = longBytesOf(codePoint);
            if (used + codePointBytes > OUTPUT_CAPTURE_RAW_BYTES) {
                break;
            }
            used += codePointBytes;
            kept.appendCodePoint(codePoint);
            offset += units;
        }
        return kept.toString();
    }

    private static long longBytesOf(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private static void insertMessage(Connection connection, long sessionId, String role, String cipher, String meta)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta)
                VALUES (?, ?, '', ?, ?)
                """)) {
            statement.setLong(1, sessionId);
            statement.setString(2, role);
            statement.setString(3, cipher);
            statement.setString(4, meta);
            statement.executeUpdate();
        }
    }

    private static long readSessionId(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT session_id FROM p0a_turn WHERE request_id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("轮次不存在: " + requestId);
                }
                return rows.getLong(1);
            }
        }
    }
}
