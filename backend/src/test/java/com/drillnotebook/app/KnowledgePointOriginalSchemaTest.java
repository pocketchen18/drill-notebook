package com.drillnotebook.app;

import com.drillnotebook.app.config.DatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePointOriginalSchemaTest {

    @Test
    void newTableExistsAfterMigration() throws Exception {
        Path tmp = java.nio.file.Files.createTempFile("study", ".db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_point (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, bank_id INTEGER, title TEXT NOT NULL, " +
                "content TEXT NOT NULL, category TEXT, tags TEXT, heading_path TEXT, " +
                "created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now')))");
        new DatabaseInitializer(ds).initialize();
        List<Map<String, Object>> tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name='knowledge_point_original'");
        assertTrue(tables.size() == 1, "knowledge_point_original 表应存在");
    }
}
