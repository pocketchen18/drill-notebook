package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.controller.AiRuntimeSettingsController;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiRetrySettings;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeSettingsRepositoryTest {
    @Test void retrySettingsPersistInThePortableDatabaseAndOldClientsPreserveThem() throws Exception {
        var root = TestWorkspace.scratchDirectory("portable-retry-settings-");
        try {
            var source = Schema9Fixture.dataSource(root);
            new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
            var jdbc = new JdbcTemplate(source);
            var first = new AiRuntimeSettingsRepository(jdbc);
            var base = AiRuntimeSettings.DEFAULTS;
            var custom = new AiRuntimeSettings(base.maxToolStages(), base.maxToolExecutions(), base.turnTimeoutMinutes(),
                    base.requestTimeoutMinutes(), base.maxOutputTokens(), base.requestOutputTokens(), base.finalOutputTokens(),
                    base.repeatedToolLimit(), new AiRetrySettings(7, 8, false, 9));
            first.save(0, custom);
            var reopened = new AiRuntimeSettingsRepository(new JdbcTemplate(Schema9Fixture.dataSource(root)));
            assertEquals(custom, reopened.read().values());
            var mapper = new ObjectMapper();
            var values = mapper.valueToTree(custom);
            ((com.fasterxml.jackson.databind.node.ObjectNode) values).remove("retry");
            ((com.fasterxml.jackson.databind.node.ObjectNode) values).put("maxToolStages", 80);
            var body = mapper.createObjectNode().put("revision", 1).set("values", values);
            new AiRuntimeSettingsController(reopened).put(body);
            assertEquals(80, first.read().values().maxToolStages());
            assertEquals(custom.retry(), first.read().values().retry());
            assertTrue(java.nio.file.Files.exists(Schema9Fixture.resolver(root).database()));
        } finally { TestWorkspace.deleteRecursively(root); }
    }

    @Test void invalidRetryLimitsAndModesNeverReplaceTheSavedConfiguration() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE ai_runtime_settings(id INTEGER PRIMARY KEY, revision INTEGER, document TEXT)");
            var repository = new AiRuntimeSettingsRepository(jdbc);
            var controller = new AiRuntimeSettingsController(repository);
            var mapper = new ObjectMapper();
            for (var invalid : java.util.List.of("{\"httpRetries\":-1,\"streamRetries\":5,\"networkPersistent\":true,\"networkRetries\":5}",
                    "{\"httpRetries\":4,\"streamRetries\":101,\"networkPersistent\":true,\"networkRetries\":5}",
                    "{\"httpRetries\":4,\"streamRetries\":5,\"networkPersistent\":\"persistent\",\"networkRetries\":5}")) {
                var values = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(AiRuntimeSettings.DEFAULTS);
                values.set("retry", mapper.readTree(invalid));
                var body = mapper.createObjectNode().put("revision", 0).set("values", values);
                assertThrows(IllegalArgumentException.class, () -> controller.put(body));
                assertEquals(0, repository.read().revision());
                assertEquals(AiRuntimeSettings.DEFAULTS, repository.read().values());
            }
        }
    }

    @Test void oldDocumentsReceiveRetryDefaultsWithoutChangingSavedBudgets() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE ai_runtime_settings(id INTEGER PRIMARY KEY, revision INTEGER, document TEXT)");
            var values = new ObjectMapper().valueToTree(new AiRuntimeSettings(80, 200, 60, 20, 256000, 32000, 16000, 0));
            ((com.fasterxml.jackson.databind.node.ObjectNode) values).remove("retry");
            jdbc.update("INSERT INTO ai_runtime_settings VALUES(1,7,?)", values.toString());
            var saved = new AiRuntimeSettingsRepository(jdbc).read();
            assertEquals(7, saved.revision()); assertEquals(80, saved.values().maxToolStages());
            assertEquals(com.drillnotebook.app.model.AiRetrySettings.DEFAULTS, saved.values().retry());
        }
    }
    @Test void globalSettingsPersistAcrossInstancesAndResetWithoutTouchingModels() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE ai_runtime_settings(id INTEGER PRIMARY KEY, revision INTEGER, document TEXT)");
            var first = new AiRuntimeSettingsRepository(jdbc);
            assertEquals(AiRuntimeSettings.DEFAULTS, first.read().values());
            var custom = new AiRuntimeSettings(80, 200, 60, 20, 256000, 32000, 16000, 0);
            first.save(0, custom);
            var reopened = new AiRuntimeSettingsRepository(jdbc);
            assertEquals(custom, reopened.read().values());
            assertThrows(ChatTurnRejectException.class, () -> first.save(0, AiRuntimeSettings.DEFAULTS));
            assertThrows(ChatTurnRejectException.class, () -> first.save(9, AiRuntimeSettings.DEFAULTS));
            reopened.save(1, AiRuntimeSettings.DEFAULTS);
            assertEquals(AiRuntimeSettings.DEFAULTS, first.read().values());
            assertEquals(2, first.read().revision());
        }
    }

    @Test void malformedNumbersAndCrossFieldConstraintsDoNotWrite() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE ai_runtime_settings(id INTEGER PRIMARY KEY, revision INTEGER, document TEXT)");
            var repository = new AiRuntimeSettingsRepository(jdbc);
            var controller = new AiRuntimeSettingsController(repository);
            ObjectMapper mapper = new ObjectMapper();
            var body = mapper.createObjectNode().put("revision", 0);
            var values = mapper.valueToTree(AiRuntimeSettings.DEFAULTS);
            body.set("values", values);
            ((com.fasterxml.jackson.databind.node.ObjectNode) values).put("maxToolStages", 1.2);
            assertThrows(IllegalArgumentException.class, () -> controller.put(body));
            assertEquals(0, repository.read().revision());
            assertThrows(IllegalArgumentException.class, () -> new AiRuntimeSettings(50, 100, 10, 15, 128000, 16000, 8192, 3));
            assertThrows(IllegalArgumentException.class, () -> new AiRuntimeSettings(50, 100, 30, 15, 1024, 1024, 1024, 3));
        }
    }
}
