package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 替换/撤销建议（contracts §2.3.1、§4.4）：建议只进 {@code ai_memory_proposal}，
 * 不给撤销制造假的 value/item，也不冒充事实。
 *
 * <p>口径：状态只有 PENDING/ACCEPTED/REJECTED/STALE；审阅走 version CAS，
 * 目标更新、来源删除、作用域切换、epoch 变化都把旧建议打成 STALE（删除范围内的建议直接删，
 * 由 {@link MemoryRepository#purgeEvidenceForDeletedSources} 负责）。
 * 建议的 scope 在加密 payload 里，因此 SQL 只能按 vault/session 过滤，scope 过滤由解密后的服务层做（P7/P9）。</p>
 */
@Repository
public class MemoryProposalRepository {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_STALE = "STALE";

    /** 终态建议的保留天数上限（§2.3.1）；去重抑制元数据由 suppression 表继续留存。 */
    public static final int TERMINAL_RETENTION_DAYS = 30;

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemoryProposalRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    /**
     * 建议的落库材料；payload 已由调用方加密，{@code dedupHmac} 含源 block/quote、operation、目标与新值。
     * {@code proposalId} 由调用方先生成——payload 的 AAD 绑定 {@code vault|table|rowId|version}，
     * 仓储内部造 ID 会让持有钥匙的上层无法预加密（§6.1.1）。
     */
    public record ProposalDraft(String proposalId, String vaultId, long sessionId, Long sourceTurnId,
                                String evidenceId, String targetMemoryId, int targetVersion, String operation,
                                String payloadCipher, String cryptoMeta, String dedupHmac,
                                String createdAt, String expiresAt) {
    }

    public record ProposalRow(String id, String vaultId, long sessionId, Long sourceTurnId, String evidenceId,
                              String targetMemoryId, int targetVersion, String operation, String status, int version,
                              String payloadCipher, String cryptoMeta, String createdAt, String expiresAt) {
    }

    /** 提交结果：DUPLICATE 是同 dedup_hmac 的重放（带回既有 id），QUOTA_FULL 一条都不进。 */
    public enum SubmitOutcome { SUBMITTED, DUPLICATE, PENDING_QUOTA_FULL }

    /** {@code id} 在 QUOTA_FULL 时为 null：没有任何行可回给调用方。 */
    public record SubmitResult(SubmitOutcome outcome, String id) {
        static SubmitResult refused(SubmitOutcome outcome) {
            return new SubmitResult(outcome, null);
        }
    }

    /** 审阅结果：ALREADY_DECIDED 是同一建议的重复点击，回既有状态而不重复生效。 */
    public enum ReviewOutcome { APPLIED, VERSION_CONFLICT, ALREADY_DECIDED, NOT_FOUND }

    private static final String SELECT = "SELECT id, vault_id, session_id, source_turn_id, evidence_id,"
            + " target_memory_id, target_version, operation, status, version, payload_cipher, crypto_meta,"
            + " created_at, expires_at FROM ai_memory_proposal";

    public SubmitResult submit(ProposalDraft draft) throws SQLException {
        if (!List.of("REPLACE_PROPOSAL", "RETRACT_PROPOSAL").contains(draft.operation())) {
            throw new SQLException("非法的建议动作: " + draft.operation());
        }
        if (draft.proposalId() == null || draft.proposalId().isBlank()) {
            throw new SQLException("proposalId 必须由调用方先生成：建议 payload 的 AAD 绑定它");
        }
        return immediate.execute(connection -> {
            Optional<ProposalRow> existing = byDedupKey(connection, draft.vaultId(), draft.dedupHmac());
            if (existing.isPresent()) {
                return new SubmitResult(SubmitOutcome.DUPLICATE, existing.get().id());
            }
            long pending = MemoryRepository.pendingCount(connection, draft.vaultId())
                    + count(connection, "SELECT COUNT(*) FROM ai_memory_proposal WHERE vault_id = ? AND status = ?",
                    draft.vaultId(), STATUS_PENDING);
            if (pending >= MemoryRepository.PENDING_QUOTA) {
                return SubmitResult.refused(SubmitOutcome.PENDING_QUOTA_FULL);
            }
            String id = draft.proposalId();
            execute(connection, "INSERT INTO ai_memory_proposal(id, vault_id, session_id, source_turn_id, evidence_id,"
                            + " target_memory_id, target_version, operation, status, version, payload_cipher,"
                            + " crypto_meta, dedup_hmac, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                            + " '" + STATUS_PENDING + "', 1, ?, ?, ?, ?, ?)",
                    id, draft.vaultId(), draft.sessionId(), draft.sourceTurnId(), draft.evidenceId(),
                    draft.targetMemoryId(), draft.targetVersion(), draft.operation(),
                    draft.payloadCipher(), draft.cryptoMeta(), draft.dedupHmac(), draft.createdAt(),
                    draft.expiresAt());
            return new SubmitResult(SubmitOutcome.SUBMITTED, id);
        });
    }

    /** 待确认建议：过期即刻从读路径排除（§2.3.1「读路径即时排除过期抑制」），不做后台清理才生效。 */
    public List<ProposalRow> findPending(String vaultId, String asOf) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " WHERE vault_id = ? AND status = ? AND expires_at > ? ORDER BY created_at, id")) {
            statement.setString(1, vaultId);
            statement.setString(2, STATUS_PENDING);
            statement.setString(3, asOf);
            return collect(statement.executeQuery());
        }
    }

    public Optional<ProposalRow> find(String id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, id);
        }
    }

    /**
     * 审阅建议：一次事务 CAS（同 id、同 version、仍是 PENDING）写终态并把版本推进。
     * 版本不符就什么都不改，调用方据最新 metadata 回 409（§4.4）。
     */
    public ReviewOutcome review(String id, int expectedVersion, String decision) throws SQLException {
        if (!List.of(STATUS_ACCEPTED, STATUS_REJECTED).contains(decision)) {
            throw new SQLException("非法的审阅决定: " + decision);
        }
        return immediate.execute(connection -> {
            Optional<ProposalRow> current = find(connection, id);
            if (current.isEmpty()) {
                return ReviewOutcome.NOT_FOUND;
            }
            if (!STATUS_PENDING.equals(current.get().status())) {
                return ReviewOutcome.ALREADY_DECIDED;
            }
            int updated = execute(connection, "UPDATE ai_memory_proposal SET status = ?, version = version + 1"
                    + " WHERE id = ? AND version = ? AND status = ?", decision, id, expectedVersion, STATUS_PENDING);
            return updated == 1 ? ReviewOutcome.APPLIED : ReviewOutcome.VERSION_CONFLICT;
        });
    }

    /** 目标事实被更新/移除：PENDING 建议变 STALE，不静默重放生效。 */
    public int markStaleForTarget(String vaultId, String targetMemoryId) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "UPDATE ai_memory_proposal SET status = ? WHERE vault_id = ? AND target_memory_id = ? AND status = ?",
                STATUS_STALE, vaultId, targetMemoryId, STATUS_PENDING));
    }

    /** 作用域切换或会话归档：该会话的 PENDING 建议全部 STALE（源不再在当前 generation 里）。 */
    public int markStaleForSession(long sessionId) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "UPDATE ai_memory_proposal SET status = ? WHERE session_id = ? AND status = ?",
                STATUS_STALE, sessionId, STATUS_PENDING));
    }

    /**
     * 清掉创建时间早于 {@code cutoff} 的终态建议：整行删除（含密文 payload）。
     * 去重抑制不靠这些行继续生效，而靠 {@link MemorySuppressionRepository} 的长期元数据。
     */
    public int purgeTerminalBefore(String cutoff) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "DELETE FROM ai_memory_proposal WHERE status IN ('" + STATUS_ACCEPTED + "','" + STATUS_REJECTED
                        + "','" + STATUS_STALE + "') AND created_at < ?", cutoff));
    }

    public long pendingCount(String vaultId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return count(connection, "SELECT COUNT(*) FROM ai_memory_proposal WHERE vault_id = ? AND status = ?",
                    vaultId, STATUS_PENDING);
        }
    }

    // ---- 事务内原语 ----

    private static Optional<ProposalRow> byDedupKey(Connection connection, String vaultId, String dedupHmac)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE vault_id = ? AND dedup_hmac = ?")) {
            statement.setString(1, vaultId);
            statement.setString(2, dedupHmac);
            return first(collect(statement.executeQuery()));
        }
    }

    private static Optional<ProposalRow> find(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE id = ?")) {
            statement.setString(1, id);
            return first(collect(statement.executeQuery()));
        }
    }

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int index = 0; index < args.length; index++) {
            statement.setObject(index + 1, args[index]);
        }
    }

    private static Optional<ProposalRow> first(List<ProposalRow> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static List<ProposalRow> collect(ResultSet result) throws SQLException {
        List<ProposalRow> rows = new ArrayList<>();
        while (result.next()) {
            long session = result.getLong("session_id");
            rows.add(new ProposalRow(result.getString("id"), result.getString("vault_id"),
                    result.wasNull() ? 0 : session, nullableId(result, "source_turn_id"),
                    result.getString("evidence_id"), result.getString("target_memory_id"),
                    result.getInt("target_version"), result.getString("operation"), result.getString("status"),
                    result.getInt("version"), result.getString("payload_cipher"), result.getString("crypto_meta"),
                    result.getString("created_at"), result.getString("expires_at")));
        }
        return rows;
    }

    private static Long nullableId(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
