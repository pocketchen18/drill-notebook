package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.controller.AiToolSettingsController;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.AiToolSettings;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class AiToolSettingsRepositoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private AiToolSettingsRepository repository;
    private AiToolSettingsController controller;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("tool-settings-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); repository = new AiToolSettingsRepository(jdbc); controller = new AiToolSettingsController(repository);
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private Map<String,Object> put(String body) throws Exception { return controller.put(json.readTree(body)); }
    @SuppressWarnings("unchecked") private static Map<String,Boolean> enabled(Map<String,Object> view) {
        var result = new java.util.LinkedHashMap<String,Boolean>();
        for (var row : (List<Map<String,Object>>) view.get("tools")) {
            assertEquals("delete", row.get("group")); assertEquals(false, row.get("defaultEnabled"));
            result.put((String) row.get("name"), (Boolean) row.get("enabled"));
        }
        return result;
    }

    @Test void everyDeleteToolStartsOffAndOtherToolsAreNotManaged() {
        var view = controller.get();
        assertEquals(0L, view.get("revision"));
        assertEquals(AiToolSettings.DELETE_TOOLS, List.copyOf(enabled(view).keySet()));
        assertFalse(enabled(view).containsValue(true));
        assertTrue(AiToolSettings.DEFAULTS.enabled("update_material"), "未纳入开关的工具不受影响");
        assertFalse(AiToolSettings.DEFAULTS.enabled("delete_memory"));
    }
    @Test void partialUpdatesKeepOtherSwitchesAndStoreOnlyExplicitChoices() throws Exception {
        var first = put("{\"revision\":0,\"enabled\":{\"delete_material\":true}}");
        assertEquals(1L, first.get("revision")); assertEquals(true, enabled(first).get("delete_material")); assertEquals(false, enabled(first).get("delete_session"));
        var second = put("{\"revision\":1,\"enabled\":{\"delete_memory\":true}}");
        assertEquals(true, enabled(second).get("delete_material")); assertEquals(true, enabled(second).get("delete_memory"));
        assertFalse(jdbc.queryForObject("SELECT document FROM ai_tool_settings", String.class).contains("delete_session"));
        var reset = put("{\"revision\":2,\"enabled\":{\"delete_memory\":false,\"delete_session\":false,\"delete_material\":false,\"remove_wrong_question\":false}}");
        assertFalse(enabled(reset).containsValue(true));
        assertEquals("{\"enabled\":{}}", jdbc.queryForObject("SELECT document FROM ai_tool_settings", String.class));
    }
    @Test void staleRevisionsAndInvalidBodiesChangeNothing() throws Exception {
        put("{\"revision\":0,\"enabled\":{\"delete_material\":true}}");
        assertEquals("AI_SETTINGS_CONFLICT", assertThrows(ChatTurnRejectException.class,
                () -> put("{\"revision\":0,\"enabled\":{\"delete_session\":true}}")).code());
        for (String body : List.of("{\"enabled\":{\"delete_session\":true}}", "{\"revision\":1}", "{\"revision\":1,\"enabled\":{}}",
                "{\"revision\":1,\"enabled\":{\"delete_session\":\"true\"}}", "{\"revision\":1,\"enabled\":{\"update_material\":false}}",
                "{\"revision\":1,\"enabled\":{\"delete_everything\":true}}"))
            assertThrows(IllegalArgumentException.class, () -> put(body), body);
        assertEquals(Map.of("delete_material", true), repository.read().values().overrides());
    }
    @Test void unreadableOrForeignDocumentsNeverSwitchDeletionOn() {
        jdbc.update("INSERT INTO ai_tool_settings(id,revision,document) VALUES(1,3,'not-json')");
        assertEquals(AiToolSettings.DEFAULTS, repository.read().values());
        jdbc.update("UPDATE ai_tool_settings SET document=?", "{\"enabled\":{\"delete_everything\":true,\"delete_session\":\"yes\",\"remove_wrong_question\":true}}");
        var values = repository.read().values();
        assertEquals(Map.of("remove_wrong_question", true), values.overrides());
        assertEquals(3, repository.read().revision());
    }
}
