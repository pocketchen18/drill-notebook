package com.drillnotebook.app.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 摘要检查点的只读入口（contracts §2.4、§2.5）：P4 的上下文投影只用它判断"哪些轮次已被有效 coverage
 * 替代"，并把模型可见的摘要文本放进数据块；写入方（P8 的摘要维护）另行实现。
 *
 * <p>本类不解密：payload 沿记忆库密钥（{@code memory/summary 沿 vault}，§4.5），
 * 由服务层带 vault 钥匙来解。</p>
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
}
