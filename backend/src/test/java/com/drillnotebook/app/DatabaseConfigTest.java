package com.drillnotebook.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drillnotebook.app.config.DatabaseConfig;
import com.drillnotebook.app.config.PortablePathResolver;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseConfigTest {
    @Test
    void enablesForeignKeysOnEveryConnection() throws Exception {
        var root = Files.createTempDirectory("database-config-test");
        Files.createDirectories(root.resolve("data"));
        PortablePathResolver paths = mock(PortablePathResolver.class);
        when(paths.database()).thenReturn(root.resolve("data/study.db"));
        DataSource dataSource = new DatabaseConfig().dataSource(paths);

        assertForeignKeysEnabled(dataSource);
        assertForeignKeysEnabled(dataSource);
    }

    private static void assertForeignKeysEnabled(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            assertEquals(1, result.getInt(1));
        }
    }
}
