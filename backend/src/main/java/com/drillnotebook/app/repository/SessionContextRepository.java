package com.drillnotebook.app.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 摘要检查点的读写入口（contracts §2.4、§2.5）：读侧判断"哪些轮次已被有效 coverage 替代"，
 * 写侧按 §8.4 第 5 条做 CAS 保存。payload 由上层加密后传入，本类不解密、不持钥匙。
 *
 * <p>摘要沿记忆库密钥（{@code memory/summary 沿 vault}，§4.5），加密身份是
 * {@code (ai_session_context, sessionId, revision)}，所以<b>必须先定 revision 再加密</b>。</p>
 */
@Repository
public class SessionContextRepository {

    /** 一行检查点：revision 用于发送前复核，throughTurnId 只是"已检查至此"的上界，不代表全部已总结。 */
    public record Checkpoint(long sessionId, long revision, long throughTurnId, long contextGeneration,
                             long vaultEpoch, String payloadCipher, String cryptoMeta, long estimatedTokens,
                             String updatedAt) {
    }

    private final DataSource dataSource;

    public SessionContextRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Checkpoint> read(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    SELECT session_id, revision, through_turn_id, context_generation, vault_epoch,
                           payload_cipher, crypto_meta, estimated_tokens, updated_at
                      FROM ai_session_context WHERE session_id = ?
                    """)) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new Checkpoint(result.getLong("session_id"), result.getLong("revision"),
                        result.getLong("through_turn_id"), result.getLong("context_generation"),
                        result.getLong("vault_epoch"), result.getString("payload_cipher"),
                        result.getString("crypto_meta"), result.getLong("estimated_tokens"),
                        result.getString("updated_at")));
            }
        }
    }

    /** 检查点 CAS 结果。{@code CONFLICT} 表示这份结果已经过时，必须整份放弃而不是覆盖新值。 */
    public enum SaveOutcome { APPLIED, CONFLICT }

    /**
     * 摘要检查点的 CAS 保存（§8.4 第 5 条）。五个键里 {@code inputRevision} 存在任务行上，
     * 由同一事务里的租约复验保证（令牌匹配 + 未过期 + 状态仍是 CLAIMED），因此这里比剩下的四个：
     * 期望的上一 revision、上下文代、库 epoch。
     *
     * <p>没有任何一行时走 INSERT，插入冲突即 {@code CONFLICT}——不会把别人刚写的检查点盖掉。</p>
     */
    public static SaveOutcome saveIn(Connection connection, long sessionId, long expectedRevision,
                                     long expectedGeneration, long expectedEpoch, long newRevision,
                                     long throughTurnId, long contextGeneration, long vaultEpoch,
                                     String payloadCipher, String cryptoMeta, long estimatedTokens, String updatedAt)
            throws SQLException {
        if (expectedRevision <= 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_session_context(session_id, revision, schema_version, through_turn_id,
                        context_generation, vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)
                    VALUES (?, ?, 2, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(session_id) DO NOTHING
                    """)) {
                statement.setLong(1, sessionId);
                statement.setLong(2, newRevision);
                statement.setLong(3, throughTurnId);
                statement.setLong(4, contextGeneration);
                statement.setLong(5, vaultEpoch);
                statement.setString(6, payloadCipher);
                statement.setString(7, cryptoMeta);
                statement.setLong(8, estimatedTokens);
                statement.setString(9, updatedAt);
                return statement.executeUpdate() == 1 ? SaveOutcome.APPLIED : SaveOutcome.CONFLICT;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_session_context
                   SET revision = ?, through_turn_id = ?, context_generation = ?, vault_epoch = ?,
                       payload_cipher = ?, crypto_meta = ?, estimated_tokens = ?, updated_at = ?
                 WHERE session_id = ? AND revision = ? AND context_generation = ? AND vault_epoch = ?
                """)) {
            statement.setLong(1, newRevision);
            statement.setLong(2, throughTurnId);
            statement.setLong(3, contextGeneration);
            statement.setLong(4, vaultEpoch);
            statement.setString(5, payloadCipher);
            statement.setString(6, cryptoMeta);
            statement.setLong(7, estimatedTokens);
            statement.setString(8, updatedAt);
            statement.setLong(9, sessionId);
            statement.setLong(10, expectedRevision);
            statement.setLong(11, expectedGeneration);
            statement.setLong(12, expectedEpoch);
            return statement.executeUpdate() == 1 ? SaveOutcome.APPLIED : SaveOutcome.CONFLICT;
        }
    }

    /**
     * 连接内失效该会话的摘要检查点（contracts §3「先断开相关读/任务…再更新账本/墓碑/摘要与索引」、
     * execution §7.4.5「失效相关摘要/任务」）。
     *
     * <p>判定只看一个数：检查点当时的<b>已检查上界</b>有没有落在被清掉的轮次里。检查点的 {@code through_turn_id}
     * 是"这份摘要整理到哪儿为止"，它可能<b>落后于</b>本次清理的批次（例如只清了最近两轮，而摘要还停在最早那轮）。
     * 那种情况下摘要引用不到任何一条已消失的原话，删它就是无故让一段有效结论失效——所以只删
     * {@code through_turn_id >= 本批最早的被清轮次}的那一行：一旦它覆盖过任何一条被删的轮次，
     * 里面那句总结就还在替已消失的原话说话，而检查点只会往前推，没有"改一半"的余地，只能整行作废。</p>
     *
     * @param lowestCleanedTurnId 本次清理里 id 最小的那个轮次；传非正数表示本批没有轮次（只清 legacy 行），不动检查点
     * @return 删掉的行数（0 或 1：一个会话最多一行检查点）
     */
    public static int invalidateIn(Connection connection, long sessionId, long lowestCleanedTurnId)
            throws SQLException {
        if (lowestCleanedTurnId <= 0) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM ai_session_context WHERE session_id = ? AND through_turn_id >= ?")) {
            statement.setLong(1, sessionId);
            statement.setLong(2, lowestCleanedTurnId);
            return statement.executeUpdate();
        }
    }
}
