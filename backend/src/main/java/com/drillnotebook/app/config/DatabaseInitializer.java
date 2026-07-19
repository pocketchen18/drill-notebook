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
    private static final int SCHEMA_VERSION = 5;
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
            migrateAiChatSessions(statement);
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
