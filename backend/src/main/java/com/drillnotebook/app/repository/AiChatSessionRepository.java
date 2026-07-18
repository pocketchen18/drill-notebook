package com.drillnotebook.app.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AiChatSessionRepository {
    private final JdbcTemplate jdbc;

    public AiChatSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    public void touch(long id) {
        jdbc.update("UPDATE ai_chat_session SET updated_at = datetime('now') WHERE id = ?", id);
    }

    @Transactional
    public void delete(long id) {
        jdbc.update("DELETE FROM ai_chat_message WHERE session_id = ?", id);
        jdbc.update("DELETE FROM ai_chat_session WHERE id = ?", id);
    }

    public List<MessageRow> messages(long sessionId) {
        return jdbc.query("""
                SELECT id, session_id, role, content, content_cipher, content_meta, created_at
                FROM ai_chat_message WHERE session_id = ? ORDER BY id
                """, (result, row) -> new MessageRow(
                result.getLong("id"),
                result.getObject("session_id") == null ? null : result.getLong("session_id"),
                result.getString("role"),
                result.getString("content"),
                result.getString("content_cipher"),
                result.getString("content_meta"),
                result.getString("created_at")
        ), sessionId);
    }

    public void insertMessage(long sessionId, String role, String content, String contentCipher, String contentMeta) {
        jdbc.update("""
                INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, role, content == null ? "" : content, contentCipher, contentMeta);
        touch(sessionId);
    }

    public void pruneToNewest(long sessionId, int keep) {
        if (keep < 1) return;
        jdbc.update("""
                DELETE FROM ai_chat_message WHERE session_id = ? AND id NOT IN (
                    SELECT id FROM ai_chat_message WHERE session_id = ? ORDER BY id DESC LIMIT ?
                )
                """, sessionId, sessionId, keep);
    }

    public long ensureDefaultSession() {
        List<Long> ids = jdbc.query("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", (result, row) -> result.getLong(1));
        if (!ids.isEmpty()) return ids.get(0);
        return create("默认会话", null);
    }

    public record MessageRow(long id, Long sessionId, String role, String content, String contentCipher, String contentMeta, String createdAt) {}
}
