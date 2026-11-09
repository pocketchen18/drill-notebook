package com.drillnotebook.app.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 会话工具专用只读投影；不装载思考、请求元数据、工具载荷或附件密文。 */
@Repository
public class SessionLookupRepository {
    public record Session(long id, String ref, String title, String updatedAt, boolean archived,
                          String scope, Long notebookId, long revision, long generation) { }
    public record Message(long id, String role, String content, String cipher, String crypto,
                          String createdAt, String turnStatus, boolean legacy) { }
    public record Range(long throughMessageId, long storedMessages, long availableMessages) { }

    private static final String SESSION_COLUMNS = "SELECT id, session_ref, title, updated_at, archived,"
            + " memory_scope_type, memory_notebook_id, history_revision, context_generation FROM ai_chat_session";
    private static final String MESSAGE_FROM = " FROM ai_chat_message m"
            + " JOIN ai_chat_session s ON s.id = m.session_id LEFT JOIN ai_chat_turn t ON t.id = m.turn_id"
            + " WHERE m.session_id = ? AND m.role IN ('user', 'assistant')";
    private static final String AVAILABLE = " AND ((m.turn_id IS NULL AND s.context_generation = 1) OR"
            + " (t.session_id = s.id AND t.context_generation = s.context_generation"
            + " AND t.status <> 'RUNNING' AND t.persistence_status = 'saved' AND t.content_deleted_at IS NULL))"
            + " AND (m.content IS NOT NULL OR m.content_cipher IS NOT NULL)";
    private final DataSource dataSource;

    public SessionLookupRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @FunctionalInterface
    public interface Reader<T> { T read(Snapshot snapshot) throws SQLException; }

    /** 一个调用中的标题、版本、边界与消息来自同一 SQLite 读快照。 */
    public <T> T read(Reader<T> reader) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = reader.read(new Snapshot(connection));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public static final class Snapshot {
        private final Connection connection;
        private Snapshot(Connection connection) { this.connection = connection; }

        public Session byId(long id) throws SQLException { return find("id", id); }
        public Session byRef(String ref) throws SQLException { return find("session_ref", ref); }

        private Session find(String column, Object value) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(SESSION_COLUMNS + " WHERE " + column + " = ?")) {
                statement.setObject(1, value);
                try (ResultSet rows = statement.executeQuery()) { return rows.next() ? session(rows) : null; }
            }
        }

        public List<Session> list(String query, long beforeId, long excludedId, boolean archived, int limit)
                throws SQLException {
            String sql = SESSION_COLUMNS + " WHERE id < ? AND id <> ?"
                    + (archived ? "" : " AND archived = 0")
                    + " AND instr(lower(title), lower(?)) > 0 ORDER BY id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, beforeId);
                statement.setLong(2, excludedId);
                statement.setString(3, query);
                statement.setInt(4, limit);
                List<Session> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(session(rows));
                }
                return result;
            }
        }

        public Range range(long sessionId) throws SQLException {
            long through = scalar("SELECT COALESCE(MAX(m.id), 0)" + MESSAGE_FROM, sessionId);
            long stored = scalar("SELECT COUNT(*)" + MESSAGE_FROM, sessionId);
            long available = scalar("SELECT COUNT(*)" + MESSAGE_FROM + AVAILABLE, sessionId);
            return new Range(through, stored, available);
        }

        private long scalar(String sql, long sessionId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
            }
        }

        public List<Message> messages(long sessionId, long nextId, long throughId, int limit) throws SQLException {
            String sql = "SELECT m.id, m.role, m.content, m.content_cipher, m.content_meta, m.created_at,"
                    + " t.status, m.turn_id" + MESSAGE_FROM + AVAILABLE
                    + " AND m.id >= ? AND m.id <= ? ORDER BY m.id ASC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, sessionId);
                statement.setLong(2, nextId);
                statement.setLong(3, throughId);
                statement.setInt(4, limit);
                List<Message> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new Message(rows.getLong("id"), rows.getString("role"),
                            rows.getString("content"), rows.getString("content_cipher"), rows.getString("content_meta"),
                            rows.getString("created_at"), rows.getString("status"), rows.getObject("turn_id") == null));
                }
                return result;
            }
        }

        private static Session session(ResultSet row) throws SQLException {
            long notebook = row.getLong("memory_notebook_id");
            Long notebookId = row.wasNull() ? null : notebook;
            return new Session(row.getLong("id"), row.getString("session_ref"), row.getString("title"),
                    row.getString("updated_at"), row.getInt("archived") == 1, row.getString("memory_scope_type"),
                    notebookId, row.getLong("history_revision"), row.getLong("context_generation"));
        }
    }
}
