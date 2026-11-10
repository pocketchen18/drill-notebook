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
            assertEquals(!row.get("group").equals("delete"), row.get("defaultEnabled"));
            result.put((String) row.get("name"), (Boolean) row.get("enabled"));
        }
        return result;
    }

    @Test void allTwentyToolsAreGroupedWithSixteenOnAndFourDeletionsOff() {
        var view = controller.get();
        assertEquals(0L, view.get("revision"));
        assertEquals(20, enabled(view).size());
        assertEquals(16L, enabled(view).values().stream().filter(Boolean::booleanValue).count());
        for (String name : AiToolSettings.DELETE_TOOLS) assertFalse(enabled(view).get(name), name);
        assertTrue(AiToolSettings.managed("update_material"));
        @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) view.get("tools");
        assertEquals(Map.of("material", 8L, "memory", 4L, "session", 4L, "delete", 4L), rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(row -> (String) row.get("group"), java.util.stream.Collectors.counting())));
    }
    @Test void partialUpdatesKeepOtherSwitchesAndStoreOnlyExplicitChoices() throws Exception {
        var first = put("{\"revision\":0,\"enabled\":{\"delete_material\":true,\"read_notebook\":false}}");
        assertEquals(1L, first.get("revision")); assertEquals(true, enabled(first).get("delete_material")); assertEquals(false, enabled(first).get("delete_session"));
        var second = put("{\"revision\":1,\"enabled\":{\"delete_memory\":true,\"send_message\":false,\"create_memory\":false}}");
        assertEquals(true, enabled(second).get("delete_material")); assertEquals(true, enabled(second).get("delete_memory"));
        assertEquals(false, enabled(second).get("read_notebook")); assertEquals(false, enabled(second).get("send_message"));
        var reopened = new AiToolSettingsRepository(jdbc).read().values();
        assertFalse(reopened.enabled("read_notebook")); assertFalse(reopened.enabled("create_memory")); assertTrue(reopened.enabled("delete_material"));
        assertFalse(jdbc.queryForObject("SELECT document FROM ai_tool_settings", String.class).contains("delete_session"));
        var defaults = new java.util.LinkedHashMap<String,Boolean>();
        @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) second.get("tools");
        for (var row : rows) defaults.put((String) row.get("name"), (Boolean) row.get("defaultEnabled"));
        var reset = controller.put(json.valueToTree(Map.of("revision", 2, "enabled", defaults)));
        assertEquals(defaults, enabled(reset));
        assertEquals("{\"enabled\":{}}", jdbc.queryForObject("SELECT document FROM ai_tool_settings", String.class));
    }
    @Test void staleRevisionsAndInvalidBodiesChangeNothing() throws Exception {
        put("{\"revision\":0,\"enabled\":{\"delete_material\":true}}");
        assertEquals("AI_SETTINGS_CONFLICT", assertThrows(ChatTurnRejectException.class,
                () -> put("{\"revision\":0,\"enabled\":{\"delete_session\":true}}")).code());
        for (String body : List.of("{\"enabled\":{\"delete_session\":true}}", "{\"revision\":1}", "{\"revision\":1,\"enabled\":{}}",
                "{\"revision\":1,\"enabled\":{\"delete_session\":\"true\"}}", "{\"revision\":1,\"enabled\":{\"update_material\":null}}",
                "{\"revision\":1,\"enabled\":{\"delete_everything\":true}}"))
            assertThrows(IllegalArgumentException.class, () -> put(body), body);
        assertEquals(Map.of("delete_material", true), repository.read().values().overrides());
    }
    @Test void olderDeletionChoicesSurviveAndNewOrdinaryToolsUseDefaults() {
        jdbc.update("INSERT INTO ai_tool_settings(id,revision,document) VALUES(1,3,?)",
                "{\"enabled\":{\"delete_material\":true,\"delete_memory\":false,\"future_tool\":true}}");
        var values = repository.read().values();
        assertEquals(Map.of("delete_material", true), values.overrides());
        assertTrue(values.enabled("read_notebook")); assertTrue(values.enabled("send_message"));
        assertFalse(values.enabled("delete_memory"));
        assertEquals(3, repository.read().revision());
    }

    @Test void damagedDocumentsAreReportedInsteadOfReenablingOrdinaryTools() {
        jdbc.update("INSERT INTO ai_tool_settings(id,revision,document) VALUES(1,3,'not-json')");
        for (String invalid : List.of("not-json", "null", "{}", "{\"enabled\":[]}",
                "{\"enabled\":{\"read_notebook\":\"false\"}}", "{\"enabled\":{\"delete_session\":null}}")) {
            jdbc.update("UPDATE ai_tool_settings SET document=?", invalid);
            assertThrows(IllegalStateException.class, controller::get, invalid);
            assertEquals(invalid, jdbc.queryForObject("SELECT document FROM ai_tool_settings", String.class));
        }
    }
}
