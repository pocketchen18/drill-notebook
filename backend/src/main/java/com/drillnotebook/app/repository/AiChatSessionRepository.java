package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 旧（v1）聊天会话与消息的读写入口。
 *
 * <p>消息新增、裁剪和会话删除都会改动历史存量，因此一律走 {@link ImmediateTransaction}，
 * 在同一次事务里连同 {@link ChatStorageLedger} 记账一起提交：账本与实测脱节会让后续额度判定失真。
 * 额度上限的准入判定属于轮次路径（P3 的开始预留），这里只保证「写多少记多少、删多少扣多少」。</p>
 */
@Repository
public class AiChatSessionRepository {
    private final JdbcTemplate jdbc;
    private final ImmediateTransaction immediate;
    private final ChatStorageLedger ledger;
    private final ChatSessionMetadataRepository metadata;
    private final MemoryRepository memory;

    public AiChatSessionRepository(JdbcTemplate jdbc, ImmediateTransaction immediate,
                                   ChatStorageLedger ledger, ChatSessionMetadataRepository metadata,
                                   MemoryRepository memory) {
        this.jdbc = jdbc;
        this.immediate = immediate;
        this.ledger = ledger;
        this.metadata = metadata;
        this.memory = memory;
    }

    public List<Map<String, Object>> list(boolean includeArchived) {
        String sql = includeArchived
                ? "SELECT s.id, s.title, s.archived, s.model, s.tags, s.created_at, s.updated_at, COUNT(m.id) AS message_count FROM ai_chat_session s LEFT JOIN ai_chat_message m ON m.session_id = s.id GROUP BY s.id ORDER BY s.updated_at DESC, s.id DESC"
                : "SELECT s.id, s.title, s.archived, s.model, s.tags, s.created_at, s.updated_at, COUNT(m.id) AS message_count FROM ai_chat_session s LEFT JOIN ai_chat_message m ON m.session_id = s.id WHERE s.archived = 0 GROUP BY s.id ORDER BY s.updated_at DESC, s.id DESC";
        return jdbc.query(sql, (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id"));
            item.put("title", result.getString("title"));
            item.put("archived", result.getInt("archived") == 1);
            item.put("model", result.getString("model"));
            item.put("tags", result.getString("tags"));
            item.put("createdAt", result.getString("created_at"));
            item.put("updatedAt", result.getString("updated_at"));
            item.put("messageCount", result.getLong("message_count"));
            return item;
        });
    }

    public Map<String, Object> find(long id) {
        return jdbc.queryForObject("""
                SELECT s.id, s.title, s.archived, s.model, s.tags, s.created_at, s.updated_at, COUNT(m.id) AS message_count
                FROM ai_chat_session s LEFT JOIN ai_chat_message m ON m.session_id = s.id
                WHERE s.id = ? GROUP BY s.id
                """, (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id"));
            item.put("title", result.getString("title"));
            item.put("archived", result.getInt("archived") == 1);
            item.put("model", result.getString("model"));
            item.put("tags", result.getString("tags"));
            item.put("createdAt", result.getString("created_at"));
            item.put("updatedAt", result.getString("updated_at"));
            item.put("messageCount", result.getLong("message_count"));
            return item;
        }, id);
    }

    public long create(String title, String model) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_chat_session(title, model) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title == null || title.isBlank() ? "新会话" : title.trim());
            statement.setString(2, model);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void update(long id, String title, Boolean archived, String model) {
        jdbc.update("""
                UPDATE ai_chat_session SET
                    title = COALESCE(?, title),
                    archived = COALESCE(?, archived),
                    model = COALESCE(?, model),
                    updated_at = datetime('now')
                WHERE id = ?
                """, title, archived == null ? null : (archived ? 1 : 0), model, id);
    }

    /**
     * 删除会话：先在同一事务里重算该会话来源的记忆证据（孤儿自动事实移除、引用被删原话的建议删除），
     * 再按实测把历史与附件字节、以及仍未结算的回复预留一并扣回账本，最后删行（§2.3）。
     */
    public void delete(long id) {
        try {
            ledger.ensureRebuiltInProcess();
            immediate.execute(connection -> {
                memory.purgeEvidence(connection, id, List.of(), List.of());
                long historyBytes = ChatStorageSizer.sessionHistoryBytes(connection, id);
                long attachmentBytes = ChatStorageSizer.sessionAttachmentBytes(connection, id);
                long liveReservation = ChatStorageSizer.liveReservationBytes(connection, id);
                // 附件经轮次归属，必须先于轮次删除；消息与轮次互相引用但都是 SET NULL，顺序无关
                execute(connection, "DELETE FROM ai_chat_attachment WHERE turn_id IN"
                        + " (SELECT id FROM ai_chat_turn WHERE session_id = ?)", id);
                execute(connection, "DELETE FROM ai_chat_message WHERE session_id = ?", id);
                execute(connection, "DELETE FROM ai_chat_turn WHERE session_id = ?", id);
                execute(connection, "DELETE FROM ai_chat_session WHERE id = ?", id);
                ledger.releaseStorage(connection, historyBytes, attachmentBytes);
                ledger.releaseReservation(connection, liveReservation);
                return null;
            });
        } catch (SQLException error) {
            throw new UncategorizedSQLException("删除聊天会话", null, error);
        }
    }

    public List<MessageRow> messages(long sessionId) {
        return jdbc.query("""
                SELECT id, session_id, role, content, content_cipher, content_meta, reasoning_cipher, reasoning_meta, created_at
                FROM ai_chat_message WHERE session_id = ? ORDER BY id
                """, (result, row) -> new MessageRow(
                result.getLong("id"),
                result.getObject("session_id") == null ? null : result.getLong("session_id"),
                result.getString("role"),
                result.getString("content"),
                result.getString("content_cipher"),
                result.getString("content_meta"),
                result.getString("reasoning_cipher"),
                result.getString("reasoning_meta"),
                result.getString("created_at")
        ), sessionId);
    }

    /** 返回真实消息 ID：轮次回填、导出与来源绑定都要用，不能再靠"最后一条"猜。 */
    public long insertMessage(long sessionId, String role, String content, String contentCipher, String contentMeta) {
        return insertMessage(sessionId, role, content, contentCipher, contentMeta, null, null);
    }

    public long insertMessage(long sessionId, String role, String content, String contentCipher, String contentMeta,
                              String reasoningCipher, String reasoningMeta) {
        String storedContent = content == null ? "" : content;
        long newBytes = ChatStorageSizer.storedBytes(storedContent, contentCipher, contentMeta,
                reasoningCipher, reasoningMeta);
        try {
            ledger.ensureRebuiltInProcess();
            return immediate.execute(connection -> {
                long messageId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta,
                            reasoning_cipher, reasoning_meta)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, sessionId);
                    statement.setString(2, role);
                    statement.setString(3, storedContent);
                    statement.setString(4, contentCipher);
                    statement.setString(5, contentMeta);
                    statement.setString(6, reasoningCipher);
                    statement.setString(7, reasoningMeta);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("旧聊天消息写入未命中单行");
                    }
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("消息写入后没有取回自增主键");
                        messageId = keys.getLong(1);
                    }
                }
                ledger.addStoredHistory(connection, newBytes);
                metadata.bumpHistoryRevision(connection, sessionId);
                return messageId;
            });
        } catch (SQLException error) {
            throw new UncategorizedSQLException("写入聊天消息", null, error);
        }
    }

    public long ensureDefaultSession() {
        List<Long> ids = jdbc.query("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", (result, row) -> result.getLong(1));
        if (!ids.isEmpty()) return ids.get(0);
        return create("默认会话", null);
    }

    public record MessageRow(long id, Long sessionId, String role, String content, String contentCipher, String contentMeta,
                             String reasoningCipher, String reasoningMeta, String createdAt) {}

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static long scalar(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("容量统计没有返回行: " + sql);
                return result.getLong(1);
            }
        }
    }
}
