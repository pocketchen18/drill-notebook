package com.drillnotebook.app.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * P1 schema9 迁移门禁：新建库 / v8 升级 / 重复启动 / 迁移失败回滚 / 高版本拒绝启动。
 *
 * <p>口径来自执行方案 §14 P1 与验收 §6.1–6.2：旧数据一条不少、一字不改；升级前留一致性快照；
 * 迁移中途失败必须整体回滚；高于本程序支持的版本一律拒绝，而不是偷偷降到 9。</p>
 */
class Schema9MigrationTest {
    private Path appRoot;
    private DataSource dataSource;

    @BeforeEach
    void prepare() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-schema9-");
        dataSource = Schema9Fixture.dataSource(appRoot);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void freshDatabaseBuildsSchema9AndSkipsSnapshot() throws Exception {
        new DatabaseInitializer(dataSource, paths()).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(9, version(jdbc));
        for (String table : new String[]{"ai_chat_turn", "ai_chat_attachment", "ai_memory_vault",
                "ai_memory_settings", "ai_memory_item", "ai_memory_evidence", "ai_memory_item_evidence",
                "ai_memory_proposal", "ai_session_context", "ai_memory_job", "ai_memory_suppression",
                "ai_memory_operation", "ai_usage_attempt", "ai_usage_daily", "ai_chat_storage"}) {
            assertEquals(1, Schema9Fixture.objectCount(jdbc, "table", table), "缺少表 " + table);
        }
        for (String index : new String[]{"idx_ai_turn_running_session", "idx_ai_turn_session_id",
                "idx_memory_one_active_family", "idx_memory_scope_status", "idx_memory_job_poll"}) {
            assertEquals(1, Schema9Fixture.objectCount(jdbc, "index", index), "缺少索引 " + index);
        }
        // 会话元数据列齐备，默认值 = 学习开、作用域 GLOBAL、水位 0、不唤醒维护
        for (String column : new String[]{"memory_scope_type", "memory_notebook_id", "memory_learn_enabled",
                "context_generation", "history_revision", "memory_scan_after_turn", "summary_scan_after_turn",
                "summary_last_attempt_at", "summary_last_attempt_through", "summary_last_input_revision",
                "archived_at", "maintenance_wake_pending", "memory_skipped_count", "summary_skipped_count",
                "last_scan_notice"}) {
            assertEquals(1, columnCount(jdbc, "ai_chat_session", column), "ai_chat_session 缺少列 " + column);
        }
        assertEquals(1, columnCount(jdbc, "ai_chat_message", "turn_id"));
        assertEquals(1, columnCount(jdbc, "ai_chat_message", "turn_role"));

        // 账本行有默认额度；记忆库与设置一律不自动创建/开启（迁移不暗示启用记忆）
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_storage", Integer.class));
        assertEquals(268435456L, jdbc.queryForObject("SELECT history_limit_bytes FROM ai_chat_storage WHERE id = 1", Long.class));
        assertEquals(268435456L, jdbc.queryForObject("SELECT attachment_limit_bytes FROM ai_chat_storage WHERE id = 1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_vault", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_settings", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn", Integer.class));

        // 全新库没有旧数据要保护，不该产生升级前快照
        assertFalse(existsSnapshot(), "全新库不应建快照");
        assertForeignKeysClean();
    }

    @Test
    void v8UpgradePreservesLegacyDataAndLeavesItTurnless() throws Exception {
        JdbcTemplate legacy = Schema9Fixture.createV8Database(dataSource);
        Schema9Fixture.seedV8Data(legacy);
        String pageOne = legacy.queryForObject("SELECT content FROM note_page WHERE id = 1", String.class);
        String cipher = legacy.queryForObject("SELECT content_cipher FROM ai_chat_message WHERE id = 2", String.class);

        new DatabaseInitializer(dataSource, paths()).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(9, version(jdbc));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notebook", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM note_page", Integer.class));
        assertEquals(pageOne, jdbc.queryForObject("SELECT content FROM note_page WHERE id = 1", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class));
        assertEquals("什么是二分查找", jdbc.queryForObject("SELECT stem FROM question WHERE id = 1", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(cipher, jdbc.queryForObject("SELECT content_cipher FROM ai_chat_message WHERE id = 2", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM retrieval_chunk", Integer.class));
        assertEquals(1.0, jdbc.queryForObject("SELECT coverage FROM embedding_space", Double.class).doubleValue());

        // 旧消息不自动回填成轮次：没有 turn 行，turn_id 仍为空
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id IS NULL", Integer.class));
        // 旧库的会话元数据由默认值补齐，学习开、作用域 GLOBAL
        assertEquals(1, jdbc.queryForObject(
                "SELECT history_revision FROM ai_chat_session WHERE id = 1", Integer.class));
        assertEquals("GLOBAL", jdbc.queryForObject(
                "SELECT memory_scope_type FROM ai_chat_session WHERE id = 1", String.class));

        assertTrue(existsSnapshot(), "跨版本升级必须先留一致性快照");
        assertForeignKeysClean();
    }

    @Test
    void repeatedStartupKeepsOneCopyOfEveryObject() throws Exception {
        JdbcTemplate legacy = Schema9Fixture.createV8Database(dataSource);
        Schema9Fixture.seedV8Data(legacy);
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource, paths());
        initializer.initialize();
        long snapshots = snapshotCount();
        initializer.initialize();
        initializer.initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(9, version(jdbc));
        assertEquals(1, Schema9Fixture.objectCount(jdbc, "table", "ai_chat_turn"));
        assertEquals(1, Schema9Fixture.objectCount(jdbc, "index", "idx_ai_turn_running_session"));
        assertEquals(1, Schema9Fixture.objectCount(jdbc, "index", "idx_memory_one_active_family"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_storage", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(snapshots, snapshotCount(), "重复启动不应再生成升级前快照");
        assertForeignKeysClean();
    }

    @Test
    void failedMigrationRollsBackAndKeepsUsableV8Copy() throws Exception {
        JdbcTemplate legacy = Schema9Fixture.createV8Database(dataSource);
        Schema9Fixture.seedV8Data(legacy);
        // 用一个缺列的同名表卡住 schema9 的 family 唯一索引，模拟迁移中途 SQL 失败
        legacy.execute("CREATE TABLE ai_memory_item (row_id TEXT PRIMARY KEY, memory_id TEXT NOT NULL)");

        assertThrows(IllegalStateException.class,
                () -> new DatabaseInitializer(dataSource, paths()).initialize());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(8, version(jdbc));
        assertEquals(0, Schema9Fixture.objectCount(jdbc, "table", "ai_chat_turn"));
        assertEquals(0, Schema9Fixture.objectCount(jdbc, "table", "ai_chat_storage"));
        assertEquals(0, columnCount(jdbc, "ai_chat_message", "turn_id"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM note_page", Integer.class));

        // 快照本身必须是一份可用的 v8 库：版本、行数与旧表形状（尚无 turn_id）都对得上
        Path snapshot = firstSnapshot();
        SQLiteDataSource copy = new SQLiteDataSource();
        copy.setUrl("jdbc:sqlite:" + snapshot);
        JdbcTemplate snapshotJdbc = new JdbcTemplate(copy);
        assertEquals(8, version(snapshotJdbc));
        assertEquals(3, snapshotJdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(0, columnCount(snapshotJdbc, "ai_chat_message", "turn_id"));
    }

    @Test
    void refusesDatabaseFromNewerProgramBeforeAnyDdl() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");
        jdbc.update("INSERT INTO schema_version(version) VALUES (10)");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseInitializer(dataSource, paths()).initialize());
        assertTrue(error.getMessage().contains("高于本程序支持"), error.getMessage());

        assertEquals(10, version(jdbc));
        // 拒绝发生在建表之前：连 v8 就有的表都没被创建
        assertEquals(0, Schema9Fixture.objectCount(jdbc, "table", "question"));
        assertEquals(0, Schema9Fixture.objectCount(jdbc, "table", "ai_chat_turn"));
        assertFalse(existsSnapshot(), "拒绝高版本时不该顺手改数据目录");
    }

    // ---- helpers ----

    private com.drillnotebook.app.config.PortablePathResolver paths() {
        return Schema9Fixture.resolver(appRoot);
    }

    private static int version(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT version FROM schema_version", Integer.class);
    }

    private static int columnCount(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?",
                Integer.class, table, column);
        return count == null ? 0 : count;
    }

    private Path firstSnapshot() throws Exception {
        Path dir = appRoot.resolve("backups");
        if (!Files.isDirectory(dir)) return null;
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("pre-schema9-")).findFirst().orElse(null);
        }
    }

    private boolean existsSnapshot() throws Exception {
        return firstSnapshot() != null;
    }

    private long snapshotCount() throws Exception {
        Path dir = appRoot.resolve("backups");
        if (!Files.isDirectory(dir)) return 0;
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("pre-schema9-")).count();
        }
    }

    private void assertForeignKeysClean() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            try (ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(result.next(), "foreign_key_check 应无异常");
            }
        }
    }
}
