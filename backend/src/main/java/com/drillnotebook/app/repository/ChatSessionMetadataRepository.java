package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 会话的记忆/调度元数据（contracts §2.1 尾段、§2.5）：作用域、上下文代、历史版本、两个扫描水位、
 * 摘要尝试记录、跳过计数、归档与维护唤醒。
 *
 * <p>口径固定：水位表示「已检查至此」，不表示「这些轮次都整理成功」；只前进不后退，
 * 关闭再开启或清空后重新启用都把水位前移到新的起始位置，绝不扫回已跳过的源。
 * 归档不释放空间、也不遗忘记忆；取消归档与只读打开都不置唤醒。</p>
 */
@Repository
public class ChatSessionMetadataRepository {
    /** 扫描水位种类：事实提取与任务续接摘要各自独立。 */
    public enum ScanKind {
        MEMORY("memory_scan_after_turn"),
        SUMMARY("summary_scan_after_turn");

        final String column;

        ScanKind(String column) {
            this.column = column;
        }
    }

    /** 归档结果；RUNNING 期间不允许归档。 */
    public enum ArchiveResult { ARCHIVED, UNARCHIVED, REFUSED_RUNNING, NOT_FOUND }

    public record State(long sessionId, String memoryScopeType, Long memoryNotebookId, boolean learnEnabled,
                        long contextGeneration, long historyRevision, long memoryScanAfterTurn,
                        long summaryScanAfterTurn, String summaryLastAttemptAt, long summaryLastAttemptThrough,
                        String summaryLastInputRevision, String archivedAt, boolean maintenanceWakePending,
                        long memorySkippedCount, long summarySkippedCount, String lastScanNotice) {
    }

    private static final String SELECT = "SELECT id, memory_scope_type, memory_notebook_id, memory_learn_enabled,"
            + " context_generation, history_revision, memory_scan_after_turn, summary_scan_after_turn,"
            + " summary_last_attempt_at, summary_last_attempt_through, summary_last_input_revision, archived_at,"
            + " maintenance_wake_pending, memory_skipped_count, summary_skipped_count, last_scan_notice"
            + " FROM ai_chat_session WHERE id = ?";

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public ChatSessionMetadataRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    public Optional<State> read(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return read(connection, sessionId);
        }
    }

    public static Optional<State> read(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new State(result.getLong("id"), result.getString("memory_scope_type"),
                        nullableLong(result, "memory_notebook_id"), result.getInt("memory_learn_enabled") == 1,
                        result.getLong("context_generation"), result.getLong("history_revision"),
                        result.getLong("memory_scan_after_turn"), result.getLong("summary_scan_after_turn"),
                        result.getString("summary_last_attempt_at"),
                        result.getLong("summary_last_attempt_through"),
                        result.getString("summary_last_input_revision"), result.getString("archived_at"),
                        result.getInt("maintenance_wake_pending") == 1, result.getLong("memory_skipped_count"),
                        result.getLong("summary_skipped_count"), result.getString("last_scan_notice")));
            }
        }
    }

    /**
     * 消息新增、终态保存、来源编辑/删除都推进 history_revision；
     * 阅读、usage 统计与摘要改写不得调用这里（§2.1）。
     */
    public long bumpHistoryRevision(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_chat_session SET history_revision = history_revision + 1,"
                        + " updated_at = datetime('now') WHERE id = ?")) {
            statement.setLong(1, sessionId);
            if (statement.executeUpdate() != 1) throw new SQLException("会话历史版本推进未命中单行: " + sessionId);
        }
        return read(connection, sessionId).orElseThrow().historyRevision();
    }

    public long bumpHistoryRevision(long sessionId) throws SQLException {
        return immediate.execute(connection -> bumpHistoryRevision(connection, sessionId));
    }

    /** 合格的新轮次或显式 flush 才置唤醒；等待预算/等待解锁都不清除它（§2.1）。 */
    public void markMaintenanceWake(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_chat_session SET maintenance_wake_pending = 1 WHERE id = ?")) {
            statement.setLong(1, sessionId);
            if (statement.executeUpdate() != 1) throw new SQLException("维护唤醒置位未命中单行: " + sessionId);
        }
    }

    /** 全部积压处理完成后才清唤醒。 */
    public void clearMaintenanceWake(Connection connection, long sessionId) throws SQLException {
        update(connection, "UPDATE ai_chat_session SET maintenance_wake_pending = 0 WHERE id = ?", sessionId);
    }

    /**
     * 水位推进：只前进。{@code throughTurnId} 是"已检查至此"的上界，允许跳过某些轮次，
     * 因此调用方必须只在来源被真正检查过（或被明确判定为永久跳过）后推进。
     */
    public long advanceScanWatermark(Connection connection, long sessionId, ScanKind kind, long throughTurnId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_chat_session SET " + kind.column + " = ? WHERE id = ? AND " + kind.column + " < ?")) {
            statement.setLong(1, throughTurnId);
            statement.setLong(2, sessionId);
            statement.setLong(3, throughTurnId);
            statement.executeUpdate();   // 已经是更大的水位时静默保持，不回退
        }
        State state = read(connection, sessionId).orElseThrow();
        return kind == ScanKind.MEMORY ? state.memoryScanAfterTurn() : state.summaryScanAfterTurn();
    }

    /**
     * 全库最大轮次 ID：全局遗忘边界（{@code memory_history_floor}）要推到的位置。
     * 没有轮次时返回 0。
     */
    public long globalMaxTurnId() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM ai_chat_turn");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    /** 全库遗忘边界（§4.4 deleteMemory 的"advance all session floors to current boundaries"）：
     * 把每个会话的扫描水位推到它当前的最大轮次，旧来源从此不再被自动补记。仍只前进，不回退。
     */
    public int advanceAllScanWatermarks(ScanKind kind) throws SQLException {
        String boundary = "(SELECT COALESCE(MAX(t.id), 0) FROM ai_chat_turn t WHERE t.session_id = s.id)";
        return immediate.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE ai_chat_session AS s SET "
                    + kind.column + " = " + boundary + " WHERE " + kind.column + " < " + boundary)) {
                return statement.executeUpdate();
            }
        });
    }

    /**
     * 摘要尝试记录：在实际发送 SESSION_SUMMARY 之前写，失败同样计入冷却（§2.4）。
     * 属于原 SUMMARY 尝试链的格式修复也更新这里，不伪造新的来源触发。
     */
    public void recordSummaryAttempt(Connection connection, long sessionId, String attemptedAt, long throughTurnId,
                                     String inputRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_chat_session SET summary_last_attempt_at = ?, summary_last_attempt_through = ?,"
                        + " summary_last_input_revision = ? WHERE id = ?")) {
            statement.setString(1, attemptedAt);
            statement.setLong(2, throughTurnId);
            statement.setString(3, inputRevision);
            statement.setLong(4, sessionId);
            if (statement.executeUpdate() != 1) throw new SQLException("摘要尝试记录未命中单行: " + sessionId);
        }
    }

    /** 扫描跳过的数值统计与最近提示；只存计数和短代码，不存正文（§2.5）。 */
    public void recordSkips(Connection connection, long sessionId, ScanKind kind, long additional, String notice)
            throws SQLException {
        String column = kind == ScanKind.MEMORY ? "memory_skipped_count" : "summary_skipped_count";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_chat_session SET " + column + " = " + column + " + ?, last_scan_notice = ? WHERE id = ?")) {
            statement.setLong(1, Math.max(0, additional));
            statement.setString(2, notice);
            statement.setLong(3, sessionId);
            if (statement.executeUpdate() != 1) throw new SQLException("跳过统计未命中单行: " + sessionId);
        }
    }

    /**
     * 学习开关：关闭后再开启时把两个水位前移到 {@code restartThroughTurnId}（通常是当前最大轮次 ID），
     * 保证被跳过的旧源不会被回扫（§2.1）。
     */
    public void setLearnEnabled(long sessionId, boolean enabled, long restartThroughTurnId) throws SQLException {
        immediate.execute(connection -> {
            update(connection, "UPDATE ai_chat_session SET memory_learn_enabled = ? WHERE id = ?",
                    enabled ? 1 : 0, sessionId);
            if (enabled) {
                advanceScanWatermark(connection, sessionId, ScanKind.MEMORY, restartThroughTurnId);
                advanceScanWatermark(connection, sessionId, ScanKind.SUMMARY, restartThroughTurnId);
            }
            return null;
        });
    }

    /**
     * 作用域切换：切 {@code context_generation}、双水位前移到切换点，并保留历史作用域记录。
     * NOTEBOOK 作用域必须带正数 notebookId（服务层负责校验它仍存在，§2.1）。
     */
    public long changeScope(long sessionId, String scopeType, Long notebookId, long restartThroughTurnId)
            throws SQLException {
        if (!"GLOBAL".equals(scopeType) && !"NOTEBOOK".equals(scopeType)) {
            throw new SQLException("非法的记忆作用域: " + scopeType);
        }
        if ("NOTEBOOK".equals(scopeType) && (notebookId == null || notebookId <= 0)) {
            throw new SQLException("NOTEBOOK 作用域必须带正数 notebookId");
        }
        if ("GLOBAL".equals(scopeType) && notebookId != null) {
            throw new SQLException("GLOBAL 作用域不得携带 notebookId");
        }
        return immediate.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_chat_session SET memory_scope_type = ?, memory_notebook_id = ?,"
                            + " context_generation = context_generation + 1 WHERE id = ?")) {
                statement.setString(1, scopeType);
                statement.setObject(2, notebookId);
                statement.setLong(3, sessionId);
                if (statement.executeUpdate() != 1) throw new SQLException("作用域切换未命中单行: " + sessionId);
            }
            advanceScanWatermark(connection, sessionId, ScanKind.MEMORY, restartThroughTurnId);
            advanceScanWatermark(connection, sessionId, ScanKind.SUMMARY, restartThroughTurnId);
            // 历史作用域不能被悄悄改回 GLOBAL：切走后仍记录原 notebook id 的归属由服务层的删除钩子负责
            return read(connection, sessionId).orElseThrow().contextGeneration();
        });
    }

    /**
     * 归档只隐藏列表并暂停维护：该会话仍有 RUNNING 轮次时拒绝，归档时清唤醒；
     * 取消归档不置唤醒（要等新完成的轮次或显式 flush 才恢复调度，§2.1）。
     */
    public ArchiveResult setArchived(long sessionId, boolean archived, String archivedAt) throws SQLException {
        return immediate.execute(connection -> {
            if (!read(connection, sessionId).isPresent()) {
                return ArchiveResult.NOT_FOUND;
            }
            if (archived && hasRunningTurn(connection, sessionId)) {
                return ArchiveResult.REFUSED_RUNNING;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_chat_session SET archived = ?, archived_at = ?, maintenance_wake_pending = 0,"
                            + " updated_at = datetime('now') WHERE id = ?")) {
                statement.setInt(1, archived ? 1 : 0);
                statement.setString(2, archived ? archivedAt : null);
                statement.setLong(3, sessionId);
                if (statement.executeUpdate() != 1) throw new SQLException("归档状态未命中单行: " + sessionId);
            }
            return archived ? ArchiveResult.ARCHIVED : ArchiveResult.UNARCHIVED;
        });
    }

    /** 安全重置（遗忘边界、清空记忆库等）：推进上下文代，让旧 generation 的轮次不再被读回。 */
    public long resetContextGeneration(long sessionId) throws SQLException {
        return immediate.execute(connection -> {
            update(connection, "UPDATE ai_chat_session SET context_generation = context_generation + 1 WHERE id = ?",
                    sessionId);
            return read(connection, sessionId).orElseThrow().contextGeneration();
        });
    }

    private static boolean hasRunningTurn(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ai_chat_turn WHERE session_id = ? AND status = 'RUNNING'")) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }

    private static void update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            if (statement.executeUpdate() != 1) throw new SQLException("会话元数据更新未命中单行");
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
