package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 遗忘与拒绝抑制（contracts §2.4）：{@code (vault, scope, kind, key)} 主键天然幂等，
 * 只存 HMAC 与"抑制至此轮次"的边界，不存任何正文。
 *
 * <p>三档语义：{@code FAMILY} 覆盖整个 family（如"此偏好别再自动记"）、
 * {@code PREDICATE} 覆盖一个谓词类别、{@code EVIDENCE} 让拒绝过的同一来源不再重提。
 * 只有用户显式解除（{@link #release}）才会失效，模型不能自己撤销。</p>
 */
@Repository
public class MemorySuppressionRepository {
    public static final String KIND_FAMILY = "FAMILY";
    public static final String KIND_PREDICATE = "PREDICATE";
    public static final String KIND_EVIDENCE = "EVIDENCE";

    public enum RecordOutcome { APPLIED, ALREADY_PRESENT }

    public record Suppression(String scopeType, long scopeId, String kind, String keyHmac, Long sourceThroughTurnId,
                              String createdAt) {
    }

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemorySuppressionRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    public RecordOutcome record(String vaultId, Suppression suppression) throws SQLException {
        requireKind(suppression.kind());
        return immediate.execute(connection -> {
            int inserted = execute(connection, "INSERT OR IGNORE INTO ai_memory_suppression(vault_id, scope_type,"
                            + " scope_id, suppression_kind, key_hmac, source_through_turn_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    vaultId, suppression.scopeType(), suppression.scopeId(), suppression.kind(),
                    suppression.keyHmac(), suppression.sourceThroughTurnId(), suppression.createdAt());
            return inserted == 1 ? RecordOutcome.APPLIED : RecordOutcome.ALREADY_PRESENT;
        });
    }

    /** 显式解除：返回真正删掉的条数，0 表示本来就没有（调用方据此给"无变化"响应）。 */
    public int release(String vaultId, String scopeType, long scopeId, String kind, String keyHmac)
            throws SQLException {
        requireKind(kind);
        return immediate.execute(connection -> execute(connection,
                "DELETE FROM ai_memory_suppression WHERE vault_id = ? AND scope_type = ? AND scope_id = ?"
                        + " AND suppression_kind = ? AND key_hmac = ?",
                vaultId, scopeType, scopeId, kind, keyHmac));
    }

    public boolean isSuppressed(String vaultId, String scopeType, long scopeId, String kind, String keyHmac)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return count(connection, "SELECT COUNT(*) FROM ai_memory_suppression WHERE vault_id = ?"
                    + " AND scope_type = ? AND scope_id = ? AND suppression_kind = ? AND key_hmac = ?",
                    vaultId, scopeType, scopeId, kind, keyHmac) > 0;
        }
    }

    /** 某作用域下该档的全部抑制键（P5 的冲突抑制与 P7 的写入资格判定按这批键过滤）。 */
    public List<String> activeKeys(String vaultId, String scopeType, long scopeId, String kind) throws SQLException {
        requireKind(kind);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT key_hmac FROM ai_memory_suppression WHERE vault_id = ? AND scope_type = ?"
                             + " AND scope_id = ? AND suppression_kind = ? ORDER BY created_at, key_hmac")) {
            statement.setString(1, vaultId);
            statement.setString(2, scopeType);
            statement.setLong(3, scopeId);
            statement.setString(4, kind);
            try (ResultSet result = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (result.next()) {
                    keys.add(result.getString(1));
                }
                return keys;
            }
        }
    }

    public int countAll(String vaultId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return (int) count(connection,
                    "SELECT COUNT(*) FROM ai_memory_suppression WHERE vault_id = ?", vaultId);
        }
    }

    private static void requireKind(String kind) throws SQLException {
        if (!List.of(KIND_FAMILY, KIND_PREDICATE, KIND_EVIDENCE).contains(kind)) {
            throw new SQLException("非法的抑制类型: " + kind);
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

    private static long count(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }
}
