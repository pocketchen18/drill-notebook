package com.drillnotebook.app.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final int SCHEMA_VERSION = 9;
    private static final DateTimeFormatter SNAPSHOT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private final DataSource dataSource;
    private final PortablePathResolver paths;
    private final SnapshotService snapshots;

    /** 测试与旧调用点用：升级前快照落到当前 app.root 解析出的 backups 目录。 */
    public DatabaseInitializer(DataSource dataSource) {
        this(dataSource, new PortablePathResolver());
    }

    public DatabaseInitializer(DataSource dataSource, PortablePathResolver paths) {
        this(dataSource, paths, new SnapshotService(dataSource));
    }

    @Autowired
    public DatabaseInitializer(DataSource dataSource, PortablePathResolver paths, SnapshotService snapshots) {
        this.dataSource = dataSource;
        this.paths = paths;
        this.snapshots = snapshots;
    }

    @PostConstruct
    public void initialize() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        Integer current = readCurrentVersion();
        // 版本前置检查必须在任何 DDL 之前：高版本库不能偷偷降级到 9。
        if (current != null && current > SCHEMA_VERSION) {
            throw new IllegalStateException("数据库结构版本 " + current + " 高于本程序支持的 "
                    + SCHEMA_VERSION + "，请升级程序后再打开该数据目录。");
        }
        if (current != null && current < SCHEMA_VERSION) {
            snapshotBeforeMigration(current);
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
            // 错题本：手动「移出错题本」标记；答错时由 recordAnswer 重置为 0，使其重新计入错题本
            ensureColumn(connection, "question", "wrong_excluded", "INTEGER NOT NULL DEFAULT 0");
            // v9 轮次映射：legacy 消息保持 turn_id IS NULL，不自动补造轮次
            ensureColumn(connection, "ai_chat_message", "turn_id", "INTEGER REFERENCES ai_chat_turn(id) ON DELETE SET NULL");
            ensureColumn(connection, "ai_chat_message", "turn_role", "TEXT");
            // v9 会话元数据：作用域/学习开关/水位/摘要调度/归档时间
            ensureColumn(connection, "ai_chat_session", "memory_scope_type", "TEXT NOT NULL DEFAULT 'GLOBAL'");
            ensureColumn(connection, "ai_chat_session", "memory_notebook_id", "INTEGER");
            ensureColumn(connection, "ai_chat_session", "memory_learn_enabled", "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(connection, "ai_chat_session", "context_generation", "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(connection, "ai_chat_session", "history_revision", "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(connection, "ai_chat_session", "memory_scan_after_turn", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "ai_chat_session", "summary_scan_after_turn", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "ai_chat_session", "summary_last_attempt_at", "TEXT");
            ensureColumn(connection, "ai_chat_session", "summary_last_attempt_through", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "ai_chat_session", "summary_last_input_revision", "TEXT");
            ensureColumn(connection, "ai_chat_session", "archived_at", "TEXT");
            ensureColumn(connection, "ai_chat_session", "maintenance_wake_pending", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "ai_chat_session", "memory_skipped_count", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "ai_chat_session", "summary_skipped_count", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "ai_chat_session", "last_scan_notice", "TEXT");
            migrateAiChatSessions(connection);
            migrateAiConfigPurposes(connection);
            exec(connection, "CREATE INDEX IF NOT EXISTS idx_ai_chat_session_updated ON ai_chat_session(updated_at DESC, id DESC)");
            exec(connection, "CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session ON ai_chat_message(session_id, id)");
            // 手动排序索引依赖 sort_index 列，须在 ensureColumn 补齐该列之后再创建（老库升级场景）
            exec(connection, "CREATE INDEX IF NOT EXISTS idx_knowledge_point_sort ON knowledge_point(bank_id, sort_index, id)");
            try (var stmt = connection.createStatement()) {
                if (current == null) stmt.executeUpdate("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
                else if (current < SCHEMA_VERSION) stmt.executeUpdate("UPDATE schema_version SET version = " + SCHEMA_VERSION);
            }
            connection.commit();
        } catch (SQLException | ScriptStatementFailedException error) {
            // 建表脚本失败时事务未提交，旧库原样保留；调用方只会看到一类启动异常。
            throw new IllegalStateException("Unable to initialize SQLite schema", error);
        }
    }

    /**
     * 读取当前库的 schema 版本；表不存在表示全新库（返回 null）。
     */
    private Integer readCurrentVersion() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "schema_version")) return null;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
                return result.next() ? result.getInt(1) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read schema version", error);
        }
    }

    /**
     * 跨版本升级前留一份一致性 v(n) 快照（§11.4：独立 autocommit 连接 + VACUUM INTO，
     * 不直接 copy 活跃 WAL 库）。失败即停升级，避免在无可回退副本的情况下改动用户数据。
     *
     * <p>这里不删文件：目标已存在时 SnapshotService 会拒绝覆盖，此时那个文件是上一次的唯一回退证据；
     * 半成品清理由创建它的 SnapshotService 负责。</p>
     */
    private void snapshotBeforeMigration(int currentVersion) {
        Path target = paths.root().resolve("backups").resolve("pre-schema" + SCHEMA_VERSION + "-from-v"
                + currentVersion + "-" + SNAPSHOT_STAMP.format(ZonedDateTime.now(ZoneOffset.UTC)) + "-study.db");
        try {
            snapshots.vacuumInto(target);
            log.info("schema {} -> {} 升级前快照: {}", currentVersion, SCHEMA_VERSION, target);
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("升级前快照失败，已停止 schema 迁移：" + error.getMessage(), error);
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
