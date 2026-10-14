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
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 持久任务行与租约（contracts §2.4、§6.3、§7）：提取与摘要任务的入队、认领、结算、取消。
 *
 * <p>口径：{@code QUEUED}/{@code RETRY}/{@code WAITING_*}/{@code PAUSED_BUDGET} 都算"待处理范围"，
 * 到点即可被认领（等待态的到点只是<b>重新探门禁</b>，不构成一次远程尝试）；{@code CLAIMED} 之后批次内容不可变。
 * 结算必须同时匹配 {@code lease_token} 且租约仍未过期，否则一律拒绝——取消后的旧租约即使进程
 * 重新开启也不能再提交（§2.4）。相同输入的终态不会被定时器或 flush 重跑（§2.4），
 * 幂等键就是 {@code UNIQUE(kind, session_id, from_turn_id, through_turn_id, input_revision, policy_revision)}。</p>
 *
 * <p>队列形态（§8.1「每会话每类型至多 1 个待处理范围」）：入队只存水位与范围，不存正文。
 * 同一 {@code (kind, session)} 已有 QUEUED 行时按新范围<b>就地合并</b>；已有 CLAIMED/RETRY/等待态行时
 * 不再插入第二行（当前批结束才生成下一批），超过队列容量也只回"没排下"，唤醒水位留在会话元数据上。</p>
 *
 * <p>任务 ID 是服务端生成的字符串 UUID（§2 建表蓝图：{@code id TEXT PRIMARY KEY}，非自增）。
 * {@link #settleIn} 是连接内原语，供上层把"租约复验 + 结果落库 + 结算"压在同一事务里。</p>
 */
@Repository
public class MemoryJobRepository {
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_RETRY = "RETRY";
    public static final String STATUS_WAITING_UNLOCK = "WAITING_UNLOCK";
    public static final String STATUS_WAITING_CONSENT = "WAITING_CONSENT";
    public static final String STATUS_PAUSED_BUDGET = "PAUSED_BUDGET";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_FAILED = "FAILED";

    /** 三个终态：进入后不再被任何取消/作废语句触碰。 */
    public static final List<String> TERMINAL_STATUSES = List.of(STATUS_COMPLETED, STATUS_SUPERSEDED,
            STATUS_FAILED);

    /** 可以承载"等待原因"的非认领态；{@code QUEUED} 也在内，表示"稍后再排一次"。 */
    private static final Set<String> PENDING_STATUS_NAMES = Set.of(STATUS_QUEUED, STATUS_RETRY,
            STATUS_WAITING_UNLOCK, STATUS_WAITING_CONSENT, STATUS_PAUSED_BUDGET);

    /** §8.1 队列容量：最多 100 个活跃会话；排不下时只在会话上留待处理水位，不复制正文。 */
    public static final int QUEUE_CAPACITY = 100;

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
                         long vaultEpoch, long policyRevision, String targetFingerprint, String promptVersion,
                         String inputRevision, String status, String leaseToken, String leaseUntil, int attempts,
                         String outcomeCode, String nextRunAt, String errorCode) {
    }

    /** 入队结果。{@code MERGED} 表示扩写了已有 QUEUED 行，没有制造第二个任务。 */
    public enum EnqueueOutcome { INSERTED, MERGED, REPLAYED, DEFERRED, CAPACITY_FULL }

    /**
     * 入队结果：{@code REPLAYED} 是同幂等键已有行；{@code DEFERRED} 是该会话该类型已有未终态行，
     * 当前批结束后才会生成下一批；{@code CAPACITY_FULL} 只说明没排下，唤醒水位仍留在会话上。
     */
    public record EnqueueResult(String jobId, Kind kind, String status, boolean replayed, EnqueueOutcome outcome) {
        public EnqueueResult(String jobId, Kind kind, String status, boolean replayed) {
            this(jobId, kind, status, replayed, replayed ? EnqueueOutcome.REPLAYED : EnqueueOutcome.INSERTED);
        }
    }

    /** 认领/结算的 CAS 结果。 */
    public enum LeaseOutcome { APPLIED, REJECTED }

    private static final String SELECT = "SELECT id, kind, session_id, from_turn_id, through_turn_id,"
            + " context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,"
            + " policy_revision, target_fingerprint, prompt_version, input_revision, status, lease_token,"
            + " lease_until, attempts, outcome_code, next_run_at, error_code FROM ai_memory_job";

    private static final String IDEMPOTENCY_KEY = "kind = ? AND session_id = ? AND from_turn_id = ?"
            + " AND through_turn_id = ? AND input_revision = ? AND policy_revision = ?";

    /**
     * 待处理范围：占一个"每会话每类型"的名额，到点可被认领。
     * 等待态与 {@code PAUSED_BUDGET} 的到点只代表重新探门禁，不代表一次远程尝试。
     */
    private static final String PENDING_STATUSES = "status IN ('" + STATUS_QUEUED + "','" + STATUS_RETRY + "','"
            + STATUS_WAITING_UNLOCK + "','" + STATUS_WAITING_CONSENT + "','" + STATUS_PAUSED_BUDGET + "')";

    private static final String ACTIVE_STATUSES = "status IN ('" + STATUS_QUEUED + "','" + STATUS_CLAIMED + "','"
            + STATUS_RETRY + "','" + STATUS_WAITING_UNLOCK + "','" + STATUS_WAITING_CONSENT + "','"
            + STATUS_PAUSED_BUDGET + "')";

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemoryJobRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    public EnqueueResult enqueue(JobDraft draft) throws SQLException {
        return enqueue(draft, null);
    }

    /**
     * 入队（只存水位与范围）：同幂等键已有行一律回吐既有行，绝不因为"范围变宽"再造一条同输入任务。
     *
     * @param nextRunAt 最早可认领时刻；QUEUED 行合并时一并刷新（合并等于重新计一次静默窗口）
     */
    public EnqueueResult enqueue(JobDraft draft, String nextRunAt) throws SQLException {
        return immediate.execute(connection -> {
            Optional<JobRow> existing = byIdempotencyKey(connection, draft);
            if (existing.isPresent()) {
                JobRow row = existing.get();
                return new EnqueueResult(row.id(), row.kind(), row.status(), true);
            }
            List<JobRow> occupants = collectQuery(connection, SELECT + " WHERE kind = ? AND session_id = ? AND "
                    + ACTIVE_STATUSES + " ORDER BY id LIMIT 2", draft.kind().name(), draft.sessionId());
            if (!occupants.isEmpty()) {
                JobRow first = occupants.get(0);
                if (!STATUS_QUEUED.equals(first.status())) {
                    // CLAIMED/RETRY/等待态各占一个名额：当前批结束前不生成第二条同类型任务（§2.4）
                    return new EnqueueResult(first.id(), first.kind(), first.status(), false,
                            EnqueueOutcome.DEFERRED);
                }
                int updated = execute(connection, "UPDATE ai_memory_job SET from_turn_id = ?, through_turn_id = ?,"
                                + " context_generation = ?, source_scope_type = ?, source_scope_id = ?,"
                                + " source_timezone = ?, vault_epoch = ?, policy_revision = ?, target_fingerprint = ?,"
                                + " prompt_version = ?, input_revision = ?, next_run_at = ?, updated_at = ?"
                                + " WHERE id = ? AND status = ?",
                        draft.fromTurnId(), draft.throughTurnId(), draft.contextGeneration(), draft.sourceScopeType(),
                        draft.sourceScopeId(), draft.sourceTimezone(), draft.vaultEpoch(), draft.policyRevision(),
                        draft.targetFingerprint(), draft.promptVersion(), draft.inputRevision(), nextRunAt,
                        draft.createdAt(), first.id(), STATUS_QUEUED);
                return updated == 1
                        ? new EnqueueResult(first.id(), first.kind(), STATUS_QUEUED, false, EnqueueOutcome.MERGED)
                        : new EnqueueResult(first.id(), first.kind(), first.status(), false,
                                EnqueueOutcome.DEFERRED);
            }
            if (activeSessionCount(connection) >= QUEUE_CAPACITY) {
                // 队列容量只按"活跃会话数"计：排不下时不复制正文，会话上的待处理边界仍在
                return new EnqueueResult(null, draft.kind(), null, false, EnqueueOutcome.CAPACITY_FULL);
            }
            String jobId = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO ai_memory_job(id, kind, session_id, from_turn_id, through_turn_id,"
                            + " context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,"
                            + " policy_revision, target_fingerprint, prompt_version, input_revision, status,"
                            + " attempts, next_run_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, '" + STATUS_QUEUED + "', 0, ?, ?, ?)",
                    jobId, draft.kind().name(), draft.sessionId(), draft.fromTurnId(), draft.throughTurnId(),
                    draft.contextGeneration(), draft.sourceScopeType(), draft.sourceScopeId(), draft.sourceTimezone(),
                    draft.vaultEpoch(), draft.policyRevision(), draft.targetFingerprint(), draft.promptVersion(),
                    draft.inputRevision(), nextRunAt, draft.createdAt(), draft.createdAt());
            return new EnqueueResult(jobId, draft.kind(), STATUS_QUEUED, false);
        });
    }

    /** 认领：待处理范围内且已到点才会被认领，命中即写入本次租约。 */
    public LeaseOutcome claim(String jobId, String leaseToken, String leaseUntil, String asOf) throws SQLException {
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_job SET status = ?, lease_token = ?,"
                    + " lease_until = ?, next_run_at = NULL, updated_at = ? WHERE id = ? AND " + PENDING_STATUSES
                    + " AND (next_run_at IS NULL OR next_run_at <= ?)",
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
        assertTerminal(status);
        return immediate.execute(connection ->
                settleIn(connection, jobId, leaseToken, asOf, status, outcomeCode) == 1
                        ? LeaseOutcome.APPLIED : LeaseOutcome.REJECTED);
    }

    /**
     * 连接内结算原语：让上层把「租约复验 + 结果落库 + 任务终态」压进同一笔事务。
     * 返回受影响行数；0 表示租约已被取消或已过期，调用方必须放弃本次结果而不是重写。
     */
    public static int settleIn(Connection connection, String jobId, String leaseToken, String asOf, String status,
                               String outcomeCode) throws SQLException {
        assertTerminal(status);
        return execute(connection, "UPDATE ai_memory_job SET status = ?, outcome_code = ?, lease_token = NULL,"
                + " lease_until = NULL, updated_at = ? WHERE id = ? AND status = ? AND lease_token = ?"
                + " AND lease_until > ?", status, outcomeCode, asOf, jobId, STATUS_CLAIMED, leaseToken, asOf);
    }

    /**
     * 门禁没过：作废租约并把任务放进等待态，<b>不增加尝试次数</b>（§8.2 第 2 条「不算一次远程尝试，不忙循环」）。
     * {@code probeAt} 是下次重新探门禁的时刻；用 {@code QUEUED} 表示"再排一次"。
     */
    public LeaseOutcome markWaiting(String jobId, String leaseToken, String status, String probeAt, String errorCode,
                                    String asOf) throws SQLException {
        if (!PENDING_STATUS_NAMES.contains(status)) {
            throw new SQLException("等待态非法: " + status);
        }
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_job SET status = ?, lease_token = NULL,"
                    + " lease_until = NULL, next_run_at = ?, error_code = ?, updated_at = ? WHERE id = ?"
                    + " AND status = ? AND lease_token = ? AND lease_until > ?",
                    status, probeAt, errorCode, asOf, jobId, STATUS_CLAIMED, leaseToken, asOf);
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

    /**
     * 授权或设置变更后作废旧任务：只打 {@code policy_revision < } 新版本的未终态行（§5.1 撤销要取消相关任务）。
     * 已按新版本重新入队的任务不受影响；被取消的范围不会被记为"已扫描"，恢复授权后由水位重新入队。
     */
    public int supersedeBelowPolicyRevision(long policyRevision, String asOf) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "UPDATE ai_memory_job SET status = '" + STATUS_SUPERSEDED + "', lease_token = NULL,"
                        + " lease_until = NULL, updated_at = ? WHERE policy_revision < ? AND " + ACTIVE_STATUSES,
                asOf, policyRevision));
    }

    /** 会话归档或删除：该会话未终态的任务一律作废（归档取消 job/lease 但保留水位，§2.1）。 */
    public int supersedeForSession(long sessionId) throws SQLException {
        return immediate.execute(connection -> supersedeForSessionIn(connection, sessionId));
    }

    /** 连接内原语：让"归档 + 取消任务"落在同一笔事务里，避免归档后还有旧租约在提交。 */
    public static int supersedeForSessionIn(Connection connection, long sessionId) throws SQLException {
        return execute(connection, "UPDATE ai_memory_job SET status = '" + STATUS_SUPERSEDED + "', lease_token = NULL,"
                + " lease_until = NULL WHERE session_id = ? AND " + ACTIVE_STATUSES, sessionId);
    }

    /** 到期的可认领任务：待处理范围且已到点，按 next_run_at、id 稳定排序，只读不改状态。 */
    public List<JobRow> findClaimable(String asOf, int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " WHERE " + PENDING_STATUSES + " AND (next_run_at IS NULL OR next_run_at <= ?)"
                             + " ORDER BY next_run_at IS NOT NULL, next_run_at, id LIMIT ?")) {
            statement.setString(1, asOf);
            statement.setInt(2, Math.max(1, limit));
            return collect(statement.executeQuery());
        }
    }

    /**
     * 租约已过期的 CLAIMED 行：进程被杀、上游悬挂都会留在这里。
     * 恢复流程按行处理（结算未发出的用量、再决定回队或终态），所以这里只读不改。
     */
    public List<JobRow> findExpiredClaims(String asOf, int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " WHERE status = ? AND lease_until IS NOT NULL AND lease_until <= ? ORDER BY"
                             + " lease_until, id LIMIT ?")) {
            statement.setString(1, STATUS_CLAIMED);
            statement.setString(2, asOf);
            statement.setInt(3, Math.max(1, limit));
            return collect(statement.executeQuery());
        }
    }

    /**
     * 陈旧租约的接管：只碰 {@code lease_until} 已经到点的 CLAIMED 行，原令牌随之作废。
     * 与 {@link #markRetry} 的区别就是这里<b>不要求租约仍有效</b>——它正是用来收尾过期租约的。
     */
    public LeaseOutcome expireClaim(String jobId, String leaseToken, String status, String nextRunAt,
                                     String errorCode, String asOf) throws SQLException {
        if (!PENDING_STATUS_NAMES.contains(status) && !STATUS_FAILED.equals(status)) {
            throw new SQLException("过期租约的归宿非法: " + status);
        }
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_job SET status = ?, attempts = attempts + 1,"
                    + " lease_token = NULL, lease_until = NULL, next_run_at = ?, error_code = ?, updated_at = ?"
                    + " WHERE id = ? AND status = ? AND lease_token = ? AND lease_until <= ?",
                    status, nextRunAt, errorCode, asOf, jobId, STATUS_CLAIMED, leaseToken, asOf);
            return updated == 1 ? LeaseOutcome.APPLIED : LeaseOutcome.REJECTED;
        });
    }

    /** 某会话当前的未终态任务（排队/等待/认领都算）：用于判断"这个会话还有没有积压"。 */
    public List<JobRow> findActiveForSession(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " WHERE session_id = ? AND " + ACTIVE_STATUSES + " ORDER BY kind, id")) {
            statement.setLong(1, sessionId);
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

    /** 队列容量口径：按"有未终态任务的会话数"计，不是按行数（§8.1）。 */
    public int activeSessionCount() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return activeSessionCount(connection);
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

    private static int activeSessionCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT session_id) FROM ai_memory_job WHERE " + ACTIVE_STATUSES);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void assertTerminal(String status) throws SQLException {
        if (!TERMINAL_STATUSES.contains(status)) {
            throw new SQLException("任务终态非法: " + status);
        }
    }

    private static List<JobRow> collectQuery(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return collect(statement.executeQuery());
        }
    }

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
                    result.getLong("vault_epoch"), result.getLong("policy_revision"),
                    result.getString("target_fingerprint"), result.getString("prompt_version"),
                    result.getString("input_revision"), result.getString("status"),
                    result.getString("lease_token"), result.getString("lease_until"),
                    result.getInt("attempts"), result.getString("outcome_code"), result.getString("next_run_at"),
                    result.getString("error_code")));
        }
        return rows;
    }
}
