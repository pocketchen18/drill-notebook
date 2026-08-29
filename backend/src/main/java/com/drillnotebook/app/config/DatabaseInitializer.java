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
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer {
    private static final int SCHEMA_VERSION = 8;
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
        try (Connection connection = dataSource.getConnection();
             Statement batch = connection.createStatement()) {
            connection.setAutoCommit(false);
            batch.execute("PRAGMA foreign_keys = ON");
            // Use ScriptUtils which properly splits on ; and handles
            // multi-statement execution without the "prepared statement
            // finalized" GC-race seen when Statements are created/closed
            // in a tight loop.
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            // Dimension-enforcement trigger executed separately because its
            // body contains ; which would confuse the ;-splitting parser.
            exec(connection, """
                    CREATE TRIGGER IF NOT EXISTS trg_retrieval_embedding_check_dimensions
                    BEFORE INSERT ON retrieval_embedding
                    WHEN (SELECT dimensions FROM embedding_space WHERE embedding_space_id = NEW.embedding_space_id) IS NOT NULL
                    BEGIN
                        SELECT RAISE(ABORT, 'retrieval_embedding.dimensions must match embedding_space.dimensions')
                        WHERE NEW.dimensions != (SELECT dimensions FROM embedding_space WHERE embedding_space_id = NEW.embedding_space_id);
                    END
                    """);
            ensureColumn(connection, "answer_record", "grading_status", "TEXT");
            ensureColumn(connection, "answer_record", "grading_json", "TEXT");
            ensureColumn(connection, "ai_chat_message", "session_id", "INTEGER");
            ensureColumn(connection, "ai_chat_message", "content_cipher", "TEXT");
            ensureColumn(connection, "ai_chat_message", "content_meta", "TEXT");
            // AI 思考链（CoT）持久化：加密存储，随消息接口返回
            ensureColumn(connection, "ai_chat_message", "reasoning_cipher", "TEXT");
            ensureColumn(connection, "ai_chat_message", "reasoning_meta", "TEXT");
            ensureColumn(connection, "knowledge_point", "heading_path", "TEXT");
            ensureColumn(connection, "knowledge_point", "sort_index", "INTEGER");
            ensureColumn(connection, "note_page", "content_hash", "TEXT");
            migrateAiChatSessions(connection);
            migrateAiConfigPurposes(connection);
            exec(connection, "CREATE INDEX IF NOT EXISTS idx_ai_chat_session_updated ON ai_chat_session(updated_at DESC, id DESC)");
            exec(connection, "CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session ON ai_chat_message(session_id, id)");
            // 手动排序索引依赖 sort_index 列，须在 ensureColumn 补齐该列之后再创建（老库升级场景）
            exec(connection, "CREATE INDEX IF NOT EXISTS idx_knowledge_point_sort ON knowledge_point(bank_id, sort_index, id)");
            Integer current = null;
            try (var stmt = connection.createStatement(); var result = stmt.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
                if (result.next()) current = result.getInt(1);
            }
            try (var stmt = connection.createStatement()) {
                if (current == null) stmt.executeUpdate("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
                else if (current < SCHEMA_VERSION) stmt.executeUpdate("UPDATE schema_version SET version = " + SCHEMA_VERSION);
            }
            connection.commit();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize SQLite schema", error);
        }
    }

    /**
     * Execute a SQL statement on a fresh Statement handle.
     * Closing each statement individually avoids "prepared statement finalized"
     * errors from certain DDL (CREATE VIRTUAL TABLE, CREATE TRIGGER) that
     * corrupt the internal JDBC handle when the same Statement is reused.
     */
    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 将旧版 ai_config(id=1 单行) 迁移为 purpose 主键（chat / import），便于主模型与导入兜底分轨。
     */
    private static void migrateAiConfigPurposes(Connection connection) throws SQLException {
        if (!tableExists(connection, "ai_config")) return;
        if (columnExists(connection, "ai_config", "purpose")) return;

        try (Statement statement = connection.createStatement()) {
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

    private static void migrateAiChatSessions(Connection connection) throws SQLException {
        long defaultSessionId;
        try (Statement statement = connection.createStatement();
             ResultSet existing = statement.executeQuery("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1")) {
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
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ai_chat_message SET session_id = " + defaultSessionId + " WHERE session_id IS NULL");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            exists = result.next();
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }
}
