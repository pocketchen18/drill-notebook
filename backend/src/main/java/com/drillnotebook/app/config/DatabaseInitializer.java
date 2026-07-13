package com.drillnotebook.app.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer {
    private static final int SCHEMA_VERSION = 3;
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

    private static void ensureColumn(Connection connection, Statement statement, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            exists = result.next();
        }
        if (!exists) statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
