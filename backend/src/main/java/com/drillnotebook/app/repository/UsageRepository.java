package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 用量与可恢复预算（contracts §2.6）：{@code ai_usage_attempt} 的唯一写入口。
 *
 * <p>{@code ai_usage_daily} 只是可重建的聚合，attempt 才是结算依据：同一个 attempt 只允许第一次
 * 把数字加进 daily，所以每个写方法都先按状态做 CAS，失手即视为重复结算并返回 false。
 * 本类不做后台额度判定（P6 负责），只提供「先占额度 → 发送 → 按实际结算」的落库形态。</p>
 */
@Repository
public class UsageRepository {
    public static final String PURPOSE_CHAT = "CHAT";
    private static final List<String> OPEN_STATES = List.of("RESERVED", "SENT");

    /** 一次外发的身份与预留；{@code reservedTokens=0} 表示只计量、不占额度（聊天就是这种）。 */
    public record Attempt(String id, String operationId, String purpose, String targetFingerprint,
                          String budgetDate, String budgetTimezone, int reservedTokens, String createdAt) {
    }

    /** 厂商回的实际用量；没有数字就是 unknown，绝不拿估算冒充当过的钱。 */
    public record ActualUsage(String usageSource, Integer inputTokens, Integer outputTokens,
                              Integer cacheReadTokens, Integer cacheWriteTokens) {
        public static ActualUsage unknown() {
            return new ActualUsage("unknown", null, null, null, null);
        }

        /** 计费口径：四类 token 都算已花出去的量，缓存读写分开只是为 P10 的厂商差异留位。 */
        public long chargedTokens() {
            return nonNegative(inputTokens) + nonNegative(outputTokens)
                    + nonNegative(cacheReadTokens) + nonNegative(cacheWriteTokens);
        }

        public boolean reported() {
            return "provider".equals(usageSource)
                    && (inputTokens != null || outputTokens != null || cacheReadTokens != null
                        || cacheWriteTokens != null);
        }

        private static long nonNegative(Integer value) {
            return value == null || value < 0 ? 0 : value;
        }
    }

    /** attempt 行快照（结算幂等判定与状态查询用）。 */
    public record AttemptRow(String id, String operationId, String purpose, String targetFingerprint,
                             String budgetDate, String budgetTimezone, String state, int reservedTokens,
                             String usageSource, Integer inputTokens, Integer outputTokens, String settledAt) {
    }

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public UsageRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    /**
     * 登记 attempt 并把预留计入 daily。同 ID 重复调用不再累加——attempt 主键就是幂等凭据。
     *
     * @return true 表示这次真的新建了行
     */
    public boolean reserve(Attempt attempt) throws SQLException {
        return immediate.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_usage_attempt(id, operation_id, purpose, target_fingerprint, budget_date,
                        budget_timezone, reserved_tokens, usage_source, state, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'unknown', 'RESERVED', ?)
                    ON CONFLICT(id) DO NOTHING
                    """)) {
                statement.setString(1, attempt.id());
                statement.setString(2, attempt.operationId());
                statement.setString(3, attempt.purpose());
                statement.setString(4, attempt.targetFingerprint());
                statement.setString(5, attempt.budgetDate());
                statement.setString(6, attempt.budgetTimezone());
                statement.setInt(7, attempt.reservedTokens());
                statement.setString(8, attempt.createdAt());
                if (statement.executeUpdate() == 0) return false;
            }
            bumpDaily(connection, attempt.budgetDate(), attempt.budgetTimezone(), attempt.targetFingerprint(),
                    attempt.purpose(), 1, 0, attempt.reservedTokens(), 0, 0, 0, 0, 0);
            return true;
        });
    }

    /** 发请求之前置 SENT：崩溃后仍停在 SENT 的行由恢复流程按 UNKNOWN 消费预留。 */
    public boolean markSent(String attemptId) throws SQLException {
        return immediate.execute(connection -> {
            Optional<AttemptRow> row = find(connection, attemptId);
            if (row.isEmpty() || !"RESERVED".equals(row.get().state())) return false;
            return update(connection, "UPDATE ai_usage_attempt SET state = 'SENT' WHERE id = ?"
                    + " AND state = 'RESERVED'", attemptId) == 1;
        });
    }

    /**
     * 按实际用量结算：仍处在 RESERVED/SENT 的那一次才会成功，成功后释放预留并写进 daily。
     *
     * @return false 表示这行早已是终态（重复结算不再记账，也不再扣预留）
     */
    public boolean settle(String attemptId, ActualUsage actual, String settledAt) throws SQLException {
        return immediate.execute(connection -> {
            Optional<AttemptRow> row = find(connection, attemptId);
            if (row.isEmpty() || !OPEN_STATES.contains(row.get().state())) return false;
            AttemptRow attempt = row.get();
            if (update(connection, """
                    UPDATE ai_usage_attempt
                       SET state = 'SETTLED', usage_source = ?, input_tokens = ?, output_tokens = ?,
                           cache_read_tokens = ?, cache_write_tokens = ?, reserved_tokens = 0, settled_at = ?
                     WHERE id = ? AND state IN ('RESERVED','SENT')
                    """, actual.reported() ? "provider" : actual.usageSource(), actual.inputTokens(),
                    actual.outputTokens(), actual.cacheReadTokens(), actual.cacheWriteTokens(), settledAt,
                    attemptId) != 1) {
                return false;
            }
            bumpDaily(connection, attempt.budgetDate(), attempt.budgetTimezone(), attempt.targetFingerprint(),
                    attempt.purpose(), 0, actual.chargedTokens(), -attempt.reservedTokens(),
                    orZero(actual.inputTokens()), orZero(actual.outputTokens()), orZero(actual.cacheReadTokens()),
                    orZero(actual.cacheWriteTokens()), 0);
            return true;
        });
    }

    /**
     *  SENT 未结算（进程被杀、流断在结果之前）按 UNKNOWN 消费：预留照扣、次数照记，
     *  但绝不谎称知道实际 token（§2.6）。
     */
    public boolean markUnknown(String attemptId, String settledAt) throws SQLException {
        return immediate.execute(connection -> {
            Optional<AttemptRow> row = find(connection, attemptId);
            if (row.isEmpty() || !"SENT".equals(row.get().state())) return false;
            AttemptRow attempt = row.get();
            if (update(connection, "UPDATE ai_usage_attempt SET state = 'UNKNOWN', usage_source = 'unknown',"
                    + " reserved_tokens = 0, settled_at = ? WHERE id = ? AND state = 'SENT'",
                    settledAt, attemptId) != 1) return false;
            bumpDaily(connection, attempt.budgetDate(), attempt.budgetTimezone(), attempt.targetFingerprint(),
                    attempt.purpose(), 0, attempt.reservedTokens(), -attempt.reservedTokens(), 0, 0, 0, 0, 1);
            return true;
        });
    }

    /** 可证明没发出去的 RESERVED 才允许释放：预留退回，当天 attempt 数也撤回。 */
    public boolean releaseUnsent(String attemptId, String settledAt) throws SQLException {
        return immediate.execute(connection -> {
            Optional<AttemptRow> row = find(connection, attemptId);
            if (row.isEmpty() || !"RESERVED".equals(row.get().state())) return false;
            AttemptRow attempt = row.get();
            if (update(connection, "UPDATE ai_usage_attempt SET state = 'RELEASED', reserved_tokens = 0,"
                    + " settled_at = ? WHERE id = ? AND state = 'RESERVED'", settledAt, attemptId) != 1) {
                return false;
            }
            bumpDaily(connection, attempt.budgetDate(), attempt.budgetTimezone(), attempt.targetFingerprint(),
                    attempt.purpose(), -1, 0, -attempt.reservedTokens(), 0, 0, 0, 0, 0);
            return true;
        });
    }

    public Optional<AttemptRow> find(String attemptId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, attemptId);
        }
    }

    /** 卡在 SENT 的全部 attempt：恢复流程把它们按 UNKNOWN 消费（§7.2.1）。 */
    public List<String> findSentAttemptIds() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM ai_usage_attempt WHERE state = 'SENT' ORDER BY created_at")) {
            try (ResultSet result = statement.executeQuery()) {
                List<String> ids = new java.util.ArrayList<>();
                while (result.next()) ids.add(result.getString(1));
                return ids;
            }
        }
    }

    // ---- 事务内原语 ----

    private static Optional<AttemptRow> find(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, operation_id, purpose, target_fingerprint, budget_date, budget_timezone, state,
                    reserved_tokens, usage_source, input_tokens, output_tokens, settled_at
                FROM ai_usage_attempt WHERE id = ?
                """)) {
            statement.setString(1, attemptId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new AttemptRow(result.getString("id"), result.getString("operation_id"),
                        result.getString("purpose"), result.getString("target_fingerprint"),
                        result.getString("budget_date"), result.getString("budget_timezone"),
                        result.getString("state"), result.getInt("reserved_tokens"),
                        result.getString("usage_source"), nullableInt(result, "input_tokens"),
                        nullableInt(result, "output_tokens"), result.getString("settled_at")));
            }
        }
    }

    /**
     * daily 是可重建聚合，缺行时按 {@code ON CONFLICT DO UPDATE} 直接累加。
     * {@code reserved_tokens} 允许被减到 0 以下吗？不允许：那说明同一笔预留被释放了两次，
     * 于是这里用 {@code MAX(0, ...)} 兜住并让 CHECK 仍成立。
     */
    private void bumpDaily(Connection connection, String budgetDate, String budgetTimezone, String target,
                           String purpose, long attempts, long charged, long reservedDelta, long input, long output,
                           long cacheRead, long cacheWrite, long unknown) throws SQLException {
        if (budgetDate == null || budgetTimezone == null || target == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_usage_daily(budget_date, budget_timezone, target_fingerprint, purpose, attempts,
                    charged_tokens, reserved_tokens, input_tokens, output_tokens, cache_read_tokens,
                    cache_write_tokens, unknown_attempts)
                VALUES (?, ?, ?, ?, MAX(0, ?), MAX(0, ?), MAX(0, ?), ?, ?, ?, ?, ?)
                ON CONFLICT(budget_date, budget_timezone, target_fingerprint, purpose) DO UPDATE SET
                    attempts = MAX(0, attempts + excluded.attempts),
                    charged_tokens = MAX(0, charged_tokens + excluded.charged_tokens),
                    reserved_tokens = MAX(0, reserved_tokens + excluded.reserved_tokens),
                    input_tokens = input_tokens + excluded.input_tokens,
                    output_tokens = output_tokens + excluded.output_tokens,
                    cache_read_tokens = cache_read_tokens + excluded.cache_read_tokens,
                    cache_write_tokens = cache_write_tokens + excluded.cache_write_tokens,
                    unknown_attempts = unknown_attempts + excluded.unknown_attempts
                """)) {
            statement.setString(1, budgetDate);
            statement.setString(2, budgetTimezone);
            statement.setString(3, target);
            statement.setString(4, purpose);
            statement.setLong(5, attempts);
            statement.setLong(6, charged);
            statement.setLong(7, reservedDelta);
            statement.setLong(8, input);
            statement.setLong(9, output);
            statement.setLong(10, cacheRead);
            statement.setLong(11, cacheWrite);
            statement.setLong(12, unknown);
            if (statement.executeUpdate() != 1) throw new SQLException("用量聚合写入未命中单行");
        }
    }

    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static long orZero(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
