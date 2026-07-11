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
        assertEquals(1, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertTrue(Files.exists(root.resolve("data/study.db")));
    }
}
