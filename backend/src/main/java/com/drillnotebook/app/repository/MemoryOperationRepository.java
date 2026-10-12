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
 * POST 动作的 operationId 去重（contracts §3 405 行）。
 *
 * <p>只存 operationId、动作类型、目标 ID、状态与响应元数据：<b>不存请求密码，也不存个人正文</b>。
 * 相同 operationId 配不同动作/目标算作冲突，防的是双击与乱序重放，不是幂等业务的万能钥匙。</p>
 */
@Repository
public class MemoryOperationRepository {
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * 登记结果。{@code REPLAY} 表示同一动作已完成、可直接回吐旧响应；
     * {@code IN_FLIGHT} 表示同键动作仍在进行（可重试），{@code CONFLICT} 表示 ID 被别的动作占用。
     */
    public enum StartOutcome { NEW, REPLAY, IN_FLIGHT, CONFLICT }

    public record OperationRow(String operationId, String action, String targetId, String status, String resultMeta,
                               String createdAt, String completedAt) {
        public boolean completed() {
            return STATUS_COMPLETED.equals(status);
        }
    }

    /** 登记结果：REPLAY 时带上已保存的响应元数据。 */
    public record StartResult(StartOutcome outcome, String resultMeta) {
    }

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemoryOperationRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    public StartResult start(String operationId, String action, String targetId, String createdAt)
            throws SQLException {
        return immediate.execute(connection -> {
            Optional<OperationRow> existing = find(connection, operationId);
            if (existing.isEmpty()) {
                execute(connection, "INSERT INTO ai_memory_operation(operation_id, action, target_id, status,"
                        + " created_at) VALUES (?, ?, ?, ?, ?)", operationId, action, targetId,
                        STATUS_IN_PROGRESS, createdAt);
                return new StartResult(StartOutcome.NEW, null);
            }
            OperationRow row = existing.get();
            if (!same(row.action(), action) || !same(row.targetId(), targetId)) {
                return new StartResult(StartOutcome.CONFLICT, null);
            }
            return new StartResult(row.completed() ? StartOutcome.REPLAY : StartOutcome.IN_FLIGHT, row.resultMeta());
        });
    }

    /** 动作成功：标记完成并保存可回吐的响应元数据。 */
    public void complete(String operationId, String resultMeta, String completedAt) throws SQLException {
        immediate.execute(connection -> {
            if (execute(connection, "UPDATE ai_memory_operation SET status = ?, result_meta = ?, completed_at = ?"
                    + " WHERE operation_id = ?", STATUS_COMPLETED, resultMeta, completedAt, operationId) != 1) {
                throw new SQLException("operationId 登记行不存在: " + operationId);
            }
            return null;
        });
    }

    /** 动作失败：删掉登记行，让客户端能用同一个 operationId 重试。 */
    public void abandon(String operationId) throws SQLException {
        immediate.execute(connection -> {
            execute(connection, "DELETE FROM ai_memory_operation WHERE operation_id = ? AND status = ?",
                    operationId, STATUS_IN_PROGRESS);
            return null;
        });
    }

    public Optional<OperationRow> find(String operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, operationId);
        }
    }

    private static Optional<OperationRow> find(Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT operation_id, action, target_id,"
                + " status, result_meta, created_at, completed_at FROM ai_memory_operation WHERE operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OperationRow(result.getString("operation_id"), result.getString("action"),
                        result.getString("target_id"), result.getString("status"), result.getString("result_meta"),
                        result.getString("created_at"), result.getString("completed_at")));
            }
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }
}
