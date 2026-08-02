package com.drillnotebook.app.config;

import java.io.IOException;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource(PortablePathResolver paths) {
        // SQLite refuses to create the database file when the parent directory
        // is missing. Ensure the data directory exists so a fresh checkout (or
        // an @SpringBootTest context) can open the database without a manual
        // pre-created folder.
        try {
            Files.createDirectories(paths.database().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory: " + paths.database().getParent(), e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + paths.database());
        return dataSource;
    }
}
