package com.drillnotebook.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.drillnotebook.app.memory.TestWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingRestoreApplierTest {
    private Path root;
    private Path data;
    private Path database;

    @BeforeEach
    void setUp() throws Exception {
        root = TestWorkspace.scratchDirectory("pending-restore-test-");
        data = Files.createDirectories(root.resolve("data"));
        database = data.resolve("study.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ai_memory_settings(id INTEGER PRIMARY KEY, revision INTEGER NOT NULL,"
                    + " enabled INTEGER NOT NULL, auto_extract INTEGER NOT NULL, use_consent_fingerprint TEXT,"
                    + " maintenance_consent_fingerprint TEXT, consented_at TEXT, updated_at TEXT NOT NULL)");
            statement.execute("INSERT INTO ai_memory_settings VALUES (1, 1, 1, 1, 'fingerprint-a', 'fingerprint-b',"
                    + " '2026-01-01', '2026-01-01')");
            statement.execute("CREATE TABLE ai_memory_vault(id TEXT PRIMARY KEY, epoch INTEGER NOT NULL,"
                    + " revision INTEGER NOT NULL)");
            statement.execute("INSERT INTO ai_memory_vault VALUES ('vault-1', 1, 1)");
            statement.execute("CREATE TABLE ai_chat_session(id INTEGER PRIMARY KEY, context_generation INTEGER NOT NULL,"
                    + " memory_scan_after_turn INTEGER NOT NULL, summary_scan_after_turn INTEGER NOT NULL)");
            statement.execute("INSERT INTO ai_chat_session VALUES (1, 1, 0, 0)");
            statement.execute("CREATE TABLE ai_chat_turn(id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL,"
                    + " status TEXT NOT NULL, finished_at TEXT, persistence_status TEXT, finish_reason TEXT)");
            statement.execute("INSERT INTO ai_chat_turn VALUES (1, 1, 'RUNNING', NULL, 'pending', NULL)");
            statement.execute("CREATE TABLE ai_memory_job(id TEXT PRIMARY KEY, status TEXT NOT NULL,"
                    + " lease_token TEXT, lease_until TEXT)");
            statement.execute("INSERT INTO ai_memory_job VALUES ('job-1', 'QUEUED', 'lease-1', '2026-01-01')");
            statement.execute("CREATE TABLE ai_usage_attempt(id TEXT PRIMARY KEY, state TEXT NOT NULL,"
                    + " settled_at TEXT)");
            statement.execute("INSERT INTO ai_usage_attempt VALUES ('attempt-1', 'SENT', NULL)");
        }
        Files.writeString(data.resolve("restore-manifest.json"),
                "{\"from\":\"test-backup.zip\",\"at\":\"2026-01-01T00:00:00Z\","
                        + "\"schemaVersion\":9,\"stage\":\"DB_REPLACED\"}");
    }

    @AfterEach
    void tearDown() throws Exception {
        TestWorkspace.deleteRecursively(root);
    }

    @Test
    void resumesSafetyAfterDatabaseWasAlreadyReplaced() throws Exception {
        assertThat(PendingRestoreApplier.applyIfPending(data)).isTrue();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT enabled, auto_extract,"
                    + " use_consent_fingerprint, maintenance_consent_fingerprint, consented_at"
                    + " FROM ai_memory_settings WHERE id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("enabled")).isZero();
                assertThat(result.getInt("auto_extract")).isZero();
                assertThat(result.getString("use_consent_fingerprint")).isNull();
                assertThat(result.getString("maintenance_consent_fingerprint")).isNull();
                assertThat(result.getString("consented_at")).isNull();
            }
            try (ResultSet result = statement.executeQuery("SELECT epoch FROM ai_memory_vault")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("epoch")).isEqualTo(2);
            }
            try (ResultSet result = statement.executeQuery("SELECT context_generation,"
                    + " memory_scan_after_turn, summary_scan_after_turn FROM ai_chat_session")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("context_generation")).isEqualTo(2);
                assertThat(result.getInt("memory_scan_after_turn")).isEqualTo(1);
                assertThat(result.getInt("summary_scan_after_turn")).isEqualTo(1);
            }
            try (ResultSet result = statement.executeQuery("SELECT status, persistence_status"
                    + " FROM ai_chat_turn WHERE id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("INTERRUPTED");
                assertThat(result.getString("persistence_status")).isEqualTo("failed");
            }
            try (ResultSet result = statement.executeQuery("SELECT status, lease_token"
                    + " FROM ai_memory_job WHERE id = 'job-1'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("SUPERSEDED");
                assertThat(result.getString("lease_token")).isNull();
            }
            try (ResultSet result = statement.executeQuery("SELECT state FROM ai_usage_attempt"
                    + " WHERE id = 'attempt-1'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("state")).isEqualTo("UNKNOWN");
            }
        }
        assertThat(data.resolve("restore-manifest.json")).doesNotExist();
        assertThat(data.resolve("study.db.pending-restore")).doesNotExist();
    }

    @Test
    void resumesSafetyAfterMoveFinishedBeforeStageWasWritten() throws Exception {
        Files.writeString(data.resolve("restore-manifest.json"),
                "{\"from\":\"test-backup.zip\",\"at\":\"2026-01-01T00:00:00Z\","
                        + "\"schemaVersion\":9,\"stage\":\"PREPARED\"}");

        assertThat(PendingRestoreApplier.applyIfPending(data)).isTrue();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT enabled, use_consent_fingerprint"
                     + " FROM ai_memory_settings WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("enabled")).isZero();
            assertThat(result.getString("use_consent_fingerprint")).isNull();
        }
        assertThat(data.resolve("restore-manifest.json")).doesNotExist();
        assertThat(data.resolve("study.db.pending-restore")).doesNotExist();
    }
}
