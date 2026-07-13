package com.drillnotebook.app;

import com.drillnotebook.app.config.DatabaseInitializer;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitializerTest {
    @Test
    void createsSchemaInConfiguredDatabase() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-test");
        Files.createDirectories(root.resolve("data"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("data/study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(3, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'knowledge_point'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('answer_record') WHERE name = 'grading_status'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('answer_record') WHERE name = 'grading_json'", Integer.class));
        assertTrue(Files.exists(root.resolve("data/study.db")));
    }

    @Test
    void upgradesVersionTwoAnswerRecordsIdempotently() throws Exception {
        var root = Files.createTempDirectory("drill-notebook-migration-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");
        jdbc.update("INSERT INTO schema_version(version) VALUES (2)");
        jdbc.execute("CREATE TABLE answer_record(id INTEGER PRIMARY KEY, question_id INTEGER NOT NULL, user_answer TEXT, is_correct INTEGER, time_spent INTEGER, session_id TEXT, answered_at TEXT)");
        jdbc.update("INSERT INTO answer_record(id, question_id, user_answer, is_correct) VALUES (1, 9, 'A', 1)");
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);
        initializer.initialize();
        initializer.initialize();
        assertEquals(3, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals("A", jdbc.queryForObject("SELECT user_answer FROM answer_record WHERE id = 1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info('answer_record') WHERE name = 'grading_status'", Integer.class));
    }
}
