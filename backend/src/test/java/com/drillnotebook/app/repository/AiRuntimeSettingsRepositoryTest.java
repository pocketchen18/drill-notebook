package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.controller.AiRuntimeSettingsController;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeSettingsRepositoryTest {
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
