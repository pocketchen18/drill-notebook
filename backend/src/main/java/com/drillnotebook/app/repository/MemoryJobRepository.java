package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 持久任务行与租约（contracts §2.4、§6.3、§7）：提取与摘要任务的入队、认领、结算、取消。
 *
 * <p>口径：{@code QUEUED}/{@code RETRY} 可被认领，{@code CLAIMED} 之后批次内容不可变；
 * 结算必须同时匹配 {@code lease_token} 且租约仍未过期，否则一律拒绝——取消后的旧租约即使进程
 * 重新开启也不能再提交（§2.4）。相同输入的终态不会被定时器或 flush 重跑（§2.4），
 * 幂等键就是 {@code UNIQUE(kind, session_id, from_turn_id, through_turn_id, input_revision, policy_revision)}。</p>
 *
 * <p>范围合并（QUEUED 可合并相邻范围）、预算与冷却判定属 P6 调度器，这里只提供原子行操作。
 * 任务 ID 是服务端生成的字符串 UUID（§2 建表蓝图：{@code id TEXT PRIMARY KEY}，非自增）。</p>
 */
@Repository
public class MemoryJobRepository {
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_RETRY = "RETRY";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_FAILED = "FAILED";

    public enum Kind {
        EXTRACT, SUMMARY
    }

    /** 入队材料：{@code inputRevision} 是本批源与版本指纹的 HMAC，不是整个会话的 history_revision（§2.4）。 */
    public record JobDraft(Kind kind, long sessionId, long fromTurnId, long throughTurnId, long contextGeneration,
                           String sourceScopeType, long sourceScopeId, String sourceTimezone, long vaultEpoch,
                           long policyRevision, String targetFingerprint, String promptVersion, String inputRevision,
                           String createdAt) {
    }

    public record JobRow(String id, Kind kind, long sessionId, long fromTurnId, long throughTurnId,
                         long contextGeneration, String sourceScopeType, long sourceScopeId, String sourceTimezone,
                         long vaultEpoch, long policyRevision, String status, String leaseToken, String leaseUntil,
                         int attempts, String outcomeCode, String nextRunAt, String errorCode) {
    }

    /** 入队结果：{@code replayed} 表示同幂等键已有行，没有制造第二个任务。 */
    public record EnqueueResult(String jobId, Kind kind, String status, boolean replayed) {
    }

    /** 认领/结算的 CAS 结果。 */
    public enum LeaseOutcome { APPLIED, REJECTED }

    private static final String SELECT = "SELECT id, kind, session_id, from_turn_id, through_turn_id,"
            + " context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,"
            + " policy_revision, status, lease_token, lease_until, attempts, outcome_code, next_run_at, error_code"
            + " FROM ai_memory_job";

    private static final String IDEMPOTENCY_KEY = "kind = ? AND session_id = ? AND from_turn_id = ?"
            + " AND through_turn_id = ? AND input_revision = ? AND policy_revision = ?";

    private static final String ACTIVE_STATUSES = "status IN ('" + STATUS_QUEUED + "','" + STATUS_CLAIMED + "','"
            + STATUS_RETRY + "')";

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemoryJobRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    public EnqueueResult enqueue(JobDraft draft) throws SQLException {
        return immediate.execute(connection -> {
            Optional<JobRow> existing = byIdempotencyKey(connection, draft);
            if (existing.isPresent()) {
                JobRow row = existing.get();
                return new EnqueueResult(row.id(), row.kind(), row.status(), true);
            }
            String jobId = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO ai_memory_job(id, kind, session_id, from_turn_id, through_turn_id,"
                            + " context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,"
                            + " policy_revision, target_fingerprint, prompt_version, input_revision, status,"
                            + " attempts, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,'"
                            + STATUS_QUEUED + "', 0, ?, ?)",
                    jobId, draft.kind().name(), draft.sessionId(), draft.fromTurnId(), draft.throughTurnId(),
                    draft.contextGeneration(), draft.sourceScopeType(), draft.sourceScopeId(), draft.sourceTimezone(),
                    draft.vaultEpoch(), draft.policyRevision(), draft.targetFingerprint(), draft.promptVersion(),
                    draft.inputRevision(), draft.createdAt(), draft.createdAt());
            return new EnqueueResult(jobId, draft.kind(), STATUS_QUEUED, false);
        });
    }

    /** 认领：只有 QUEUED/RETRY 且已到点（{@code next_run_at} 为空或已到期）才能被认领，命中即写入本次租约。 */
    public LeaseOutcome claim(String jobId, String leaseToken, String leaseUntil, String asOf) throws SQLException {
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_job SET status = ?, lease_token = ?,"
                    + " lease_until = ?, next_run_at = NULL, updated_at = ? WHERE id = ? AND status IN ('"
                    + STATUS_QUEUED + "','" + STATUS_RETRY + "') AND (next_run_at IS NULL OR next_run_at <= ?)",
                    STATUS_CLAIMED, leaseToken, leaseUntil, asOf, jobId, asOf);
            return updated == 1 ? LeaseOutcome.APPLIED : LeaseOutcome.REJECTED;
        });
    }

    /**
     * 结算：租约令牌必须匹配、状态仍是 CLAIMED、且租约在 {@code asOf} 时仍未过期。
     * 任一不符都拒绝写入——取消或过期的旧租约永远提交不了（§7「verify lease token is still claimed and not expired」）。
     */
    public LeaseOutcome settle(String jobId, String leaseToken, String asOf, String status, String outcomeCode)
            throws SQLException {
        if (!List.of(STATUS_COMPLETED, STATUS_SUPERSEDED, STATUS_FAILED).contains(status)) {
            throw new SQLException("任务终态非法: " + status);
        }
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_job SET status = ?, outcome_code = ?,"
                    + " lease_token = NULL, lease_until = NULL, updated_at = ? WHERE id = ? AND status = ?"
                    + " AND lease_token = ? AND lease_until > ?",
                    status, outcomeCode, asOf, jobId, STATUS_CLAIMED, leaseToken, asOf);
            return updated == 1 ? LeaseOutcome.APPLIED : LeaseOutcome.REJECTED;
        });
    }

    /** 网络/格式失败的回队：租约作废、尝试次数加一，仍在原任务上限内（每次占预算由 P6 判定）。 */
    public LeaseOutcome markRetry(String jobId, String leaseToken, String nextRunAt, String errorCode, String asOf)
            throws SQLException {
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_job SET status = ?, lease_token = NULL,"
                    + " lease_until = NULL, attempts = attempts + 1, next_run_at = ?, error_code = ?, updated_at = ?"
                    + " WHERE id = ? AND status = ? AND lease_token = ? AND lease_until > ?",
                    STATUS_RETRY, nextRunAt, errorCode, asOf, jobId, STATUS_CLAIMED, leaseToken, asOf);
            return updated == 1 ? LeaseOutcome.APPLIED : LeaseOutcome.REJECTED;
        });
    }

    /**
     * 取消：把未终态的任务打成 SUPERSEDED 并抹掉租约，即使用户随后重新开启，旧租约也不能再提交（§2.4）。
     * 返回真正被取消的条数；已终态的任务不动（不伪造第二次取消）。
     */
    public int cancel(List<String> jobIds, String asOf) throws SQLException {
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }
        return immediate.execute(connection -> execute(connection,
                "UPDATE ai_memory_job SET status = '" + STATUS_SUPERSEDED + "', lease_token = NULL,"
                        + " lease_until = NULL, updated_at = ? WHERE id IN (" + placeholders(jobIds.size()) + ")"
                        + " AND " + ACTIVE_STATUSES, withFirst(asOf, jobIds).toArray()));
    }

    /** epoch 推进（遗忘/清空）后旧 epoch 的任务全部作废：不允许重跑旧 epoch（§3）。 */
    public int supersedeBeforeEpoch(long vaultEpoch) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "UPDATE ai_memory_job SET status = '" + STATUS_SUPERSEDED + "', lease_token = NULL,"
                        + " lease_until = NULL WHERE vault_epoch < ? AND " + ACTIVE_STATUSES, vaultEpoch));
    }

    /** 会话归档或删除：该会话未终态的任务一律作废（归档取消 job/lease 但保留水位，§2.1）。 */
    public int supersedeForSession(long sessionId) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "UPDATE ai_memory_job SET status = '" + STATUS_SUPERSEDED + "', lease_token = NULL,"
                        + " lease_until = NULL WHERE session_id = ? AND " + ACTIVE_STATUSES, sessionId));
    }

    /** 到期的可认领任务（P6 轮询）：按 next_run_at、id 稳定排序，只读不改状态。 */
    public List<JobRow> findClaimable(String asOf, int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " WHERE status IN ('" + STATUS_QUEUED + "','" + STATUS_RETRY
                             + "') AND (next_run_at IS NULL OR next_run_at <= ?) ORDER BY next_run_at IS NOT NULL,"
                             + " next_run_at, id LIMIT ?")) {
            statement.setString(1, asOf);
            statement.setInt(2, Math.max(1, limit));
            return collect(statement.executeQuery());
        }
    }

    public Optional<JobRow> find(String jobId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE id = ?")) {
            statement.setString(1, jobId);
            return first(collect(statement.executeQuery()));
        }
    }

    public long countByStatus(String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_memory_job WHERE status = ?")) {
            statement.setString(1, status);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    // ---- 事务内原语 ----

    private static Optional<JobRow> byIdempotencyKey(Connection connection, JobDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE " + IDEMPOTENCY_KEY + " ORDER BY id LIMIT 1")) {
            statement.setString(1, draft.kind().name());
            statement.setLong(2, draft.sessionId());
            statement.setLong(3, draft.fromTurnId());
            statement.setLong(4, draft.throughTurnId());
            statement.setString(5, draft.inputRevision());
            statement.setLong(6, draft.policyRevision());
            return first(collect(statement.executeQuery()));
        }
    }

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static List<Object> withFirst(String firstValue, List<String> rest) {
        List<Object> args = new ArrayList<>();
        args.add(firstValue);
        args.addAll(rest);
        return args;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Optional<JobRow> first(List<JobRow> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static List<JobRow> collect(ResultSet result) throws SQLException {
        List<JobRow> rows = new ArrayList<>();
        while (result.next()) {
            rows.add(new JobRow(result.getString("id"), Kind.valueOf(result.getString("kind")),
                    result.getLong("session_id"), result.getLong("from_turn_id"), result.getLong("through_turn_id"),
                    result.getLong("context_generation"), result.getString("source_scope_type"),
                    result.getLong("source_scope_id"), result.getString("source_timezone"),
                    result.getLong("vault_epoch"), result.getLong("policy_revision"), result.getString("status"),
                    result.getString("lease_token"), result.getString("lease_until"), result.getInt("attempts"),
                    result.getString("outcome_code"), result.getString("next_run_at"),
                    result.getString("error_code")));
        }
        return rows;
    }
}
