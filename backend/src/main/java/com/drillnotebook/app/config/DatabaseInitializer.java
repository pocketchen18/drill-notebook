package com.drillnotebook.app.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer {
    private static final int SCHEMA_VERSION = 7;
    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        String schema;
        try (InputStream stream = new ClassPathResource("schema.sql").getInputStream()) {
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read database schema", error);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (String sql : schema.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) statement.execute(trimmed);
            }
            ensureColumn(connection, statement, "answer_record", "grading_status", "TEXT");
            ensureColumn(connection, statement, "answer_record", "grading_json", "TEXT");
            ensureColumn(connection, statement, "ai_chat_message", "session_id", "INTEGER");
            ensureColumn(connection, statement, "ai_chat_message", "content_cipher", "TEXT");
            ensureColumn(connection, statement, "ai_chat_message", "content_meta", "TEXT");
            ensureColumn(connection, statement, "knowledge_point", "heading_path", "TEXT");
            migrateAiChatSessions(statement);
            migrateAiConfigPurposes(connection, statement);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_chat_session_updated ON ai_chat_session(updated_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session ON ai_chat_message(session_id, id)");
            Integer current = null;
            try (var result = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
                if (result.next()) current = result.getInt(1);
            }
            if (current == null) statement.executeUpdate("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
            else if (current < SCHEMA_VERSION) statement.executeUpdate("UPDATE schema_version SET version = " + SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize SQLite schema", error);
        }
    }

    /**
     * 将旧版 ai_config(id=1 单行) 迁移为 purpose 主键（chat / import），便于主模型与导入兜底分轨。
     */
    private static void migrateAiConfigPurposes(Connection connection, Statement statement) throws SQLException {
        if (!tableExists(connection, "ai_config")) return;
        if (columnExists(connection, "ai_config", "purpose")) return;

        statement.execute("""
                CREATE TABLE IF NOT EXISTS ai_config_purpose (
                    purpose TEXT PRIMARY KEY,
                    provider TEXT,
                    endpoint TEXT,
                    model TEXT,
                    encrypted_key TEXT,
                    key_meta TEXT,
                    params TEXT
                )
                """);
        // 旧表 id=1 → chat；import 行可缺省，由用户在设置中单独配置
        try {
            statement.executeUpdate("""
                    INSERT OR IGNORE INTO ai_config_purpose(purpose, provider, endpoint, model, encrypted_key, key_meta, params)
                    SELECT 'chat', provider, endpoint, model, encrypted_key, key_meta, params FROM ai_config WHERE id = 1
                    """);
        } catch (SQLException ignored) {
            // 若旧表无 id 列或为空，忽略
        }
        statement.execute("DROP TABLE ai_config");
        statement.execute("ALTER TABLE ai_config_purpose RENAME TO ai_config");
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet result = connection.getMetaData().getTables(null, null, table, null)) {
            return result.next();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            return result.next();
        }
    }

    private static void migrateAiChatSessions(Statement statement) throws SQLException {
        long defaultSessionId;
        try (ResultSet existing = statement.executeQuery("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1")) {
            if (existing.next()) {
                defaultSessionId = existing.getLong(1);
            } else {
                statement.executeUpdate("INSERT INTO ai_chat_session(title) VALUES ('默认会话')");
                try (ResultSet created = statement.executeQuery("SELECT last_insert_rowid()")) {
                    created.next();
                    defaultSessionId = created.getLong(1);
                }
            }
        }
        statement.executeUpdate("UPDATE ai_chat_message SET session_id = " + defaultSessionId + " WHERE session_id IS NULL");
    }

    private static void ensureColumn(Connection connection, Statement statement, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            exists = result.next();
        }
        if (!exists) statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
