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
 * 后台额度判定用 {@link #maintenanceSpend}（跨所有 target 与三个维护 purpose 求和），
 * 这里只提供「先占额度 → 发送 → 按实际结算」的落库形态。</p>
 */
@Repository
public class UsageRepository {
    public static final String PURPOSE_CHAT = "CHAT";
    /** 三个占后台每日额度的维护用途（§2.6：CHAT 计量但不占）。 */
    public static final String PURPOSE_MEMORY_EXTRACT = "MEMORY_EXTRACT";
    public static final String PURPOSE_SESSION_SUMMARY = "SESSION_SUMMARY";
    public static final String PURPOSE_FORMAT_REPAIR = "FORMAT_REPAIR";
    private static final List<String> OPEN_STATES = List.of("RESERVED", "SENT");

    /**
     * 一次外发的身份与预留；{@code reservedTokens=0} 表示只计量、不占额度（聊天就是这种）。
     * {@code contextPlanId} 把 attempt 绑到实际发出的那份上下文计划（contracts §4.5）。
     */
    public record Attempt(String id, String operationId, String purpose, String targetFingerprint,
                          String budgetDate, String budgetTimezone, int reservedTokens, String createdAt,
                          String contextPlanId) {
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
                        budget_timezone, reserved_tokens, usage_source, state, created_at, context_plan_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'unknown', 'RESERVED', ?, ?)
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
                statement.setString(9, attempt.contextPlanId());
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

    /**
     * 卡在 SENT 的全部 attempt：恢复流程把它们按 UNKNOWN 消费（§7.2.1）。
     */
    public List<AttemptRow> findUnsettled() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, operation_id, purpose, target_fingerprint, budget_date, budget_timezone, state,"
                             + " reserved_tokens, usage_source, input_tokens, output_tokens, settled_at"
                             + " FROM ai_usage_attempt WHERE state IN ('RESERVED','SENT') ORDER BY created_at")) {
            try (ResultSet result = statement.executeQuery()) {
                List<AttemptRow> rows = new java.util.ArrayList<>();
                while (result.next()) rows.add(row(result));
                return rows;
            }
        }
    }

    /** 某个操作（聊天用 requestId、维护用 jobId）仍未结算的 attempt，按创建顺序。 */
    public List<AttemptRow> findUnsettledByOperation(String operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, operation_id, purpose, target_fingerprint, budget_date, budget_timezone, state,"
                             + " reserved_tokens, usage_source, input_tokens, output_tokens, settled_at"
                             + " FROM ai_usage_attempt WHERE operation_id = ? AND state IN ('RESERVED','SENT')"
                             + " ORDER BY created_at, id")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                List<AttemptRow> rows = new java.util.ArrayList<>();
                while (result.next()) rows.add(row(result));
                return rows;
            }
        }
    }

    /**
     * 当天后台已花的额度：{@code ai_usage_daily} 是可重建聚合，所以这里跨<b>所有 target</b> 与三个维护
     * purpose 求和——切模型换 target 不能重置每日额度（§2.6）。
     *
     * <p>{@code reservedTokens} 是仍未结算的外发预留，判定新任务时必须连同它一起算，
     * 否则并发中的预留会被当成没花过的钱。</p>
     */
    public MaintenanceSpend maintenanceSpend(String budgetDate, String budgetTimezone) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(SUM(attempts), 0) AS attempts, COALESCE(SUM(charged_tokens), 0) AS charged,
                           COALESCE(SUM(reserved_tokens), 0) AS reserved
                      FROM ai_usage_daily
                     WHERE budget_date = ? AND budget_timezone = ? AND purpose IN (?, ?, ?)
                    """)) {
            statement.setString(1, budgetDate);
            statement.setString(2, budgetTimezone);
            statement.setString(3, PURPOSE_MEMORY_EXTRACT);
            statement.setString(4, PURPOSE_SESSION_SUMMARY);
            statement.setString(5, PURPOSE_FORMAT_REPAIR);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new MaintenanceSpend(0, 0, 0);
                return new MaintenanceSpend(result.getInt("attempts"), result.getLong("charged"),
                        result.getLong("reserved"));
            }
        }
    }

    /** 当天后台额度快照。{@code spentTokens()} 才是"已经花掉 + 仍在飞行"的合计。 */
    public record MaintenanceSpend(int attempts, long chargedTokens, long reservedTokens) {
        public long spentTokens() {
            return chargedTokens + reservedTokens;
        }
    }

    /**
     * target 指纹：endpoint 归一化（小写、去尾斜杠）+ apiFormat + model 的摘要。
     * 它与授权指纹分开——切模型会换 target，但跨 target 的每日维护总额不因此重置（§2.6）。
     */
    public static String targetFingerprint(String endpoint, String apiFormat, String model) {
        String canonical = String.join("|",
                endpoint == null ? "" : endpoint.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("/+$", ""),
                apiFormat == null ? "" : apiFormat.trim().toLowerCase(java.util.Locale.ROOT),
                model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
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
                return result.next() ? Optional.of(row(result)) : Optional.empty();
            }
        }
    }

    private static AttemptRow row(ResultSet result) throws SQLException {
        return new AttemptRow(result.getString("id"), result.getString("operation_id"),
                result.getString("purpose"), result.getString("target_fingerprint"),
                result.getString("budget_date"), result.getString("budget_timezone"),
                result.getString("state"), result.getInt("reserved_tokens"),
                result.getString("usage_source"), nullableInt(result, "input_tokens"),
                nullableInt(result, "output_tokens"), result.getString("settled_at"));
    }

    /**
     * daily 是可重建聚合，缺行时按 {@code ON CONFLICT DO UPDATE} 直接累加。
     *
     * <p>{@code excluded.*} 取的是<b>被 MAX(0,?) 夹过</b>的插入值，而结算要能把之前加进去的预留与
     * attempt 数<b>减回</b>去（释放要退回额度、结算要把预留清零，§2.6），所以那三列改用
     * {@code ai_usage_daily.x + 原始增量} 再夹 0：既不会写出负数，也不会把减法当成加法丢掉。
     * 只增不减的四列（input/output/cache/unknown）继续用 {@code excluded}。</p>
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
                    attempts = MAX(0, ai_usage_daily.attempts + ?),
                    charged_tokens = MAX(0, ai_usage_daily.charged_tokens + ?),
                    reserved_tokens = MAX(0, ai_usage_daily.reserved_tokens + ?),
                    input_tokens = ai_usage_daily.input_tokens + excluded.input_tokens,
                    output_tokens = ai_usage_daily.output_tokens + excluded.output_tokens,
                    cache_read_tokens = ai_usage_daily.cache_read_tokens + excluded.cache_read_tokens,
                    cache_write_tokens = ai_usage_daily.cache_write_tokens + excluded.cache_write_tokens,
                    unknown_attempts = ai_usage_daily.unknown_attempts + excluded.unknown_attempts
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
            statement.setLong(13, attempts);
            statement.setLong(14, charged);
            statement.setLong(15, reservedDelta);
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
