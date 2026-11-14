package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.controller.WebSearchSettingsController;
import com.drillnotebook.app.memory.*;
import com.drillnotebook.app.model.WebSearchSettings;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class WebSearchSettingsTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private WebSearchSettingsRepository repository;
    private WebSearchSettingsService service;
    private WebSearchSettingsController controller;
    private WebSearchService web;
    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("web-settings-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); repository = new WebSearchSettingsRepository(jdbc);
        service = new WebSearchSettingsService(repository, new ApiKeyEncryptor()); web = mock(WebSearchService.class);
        controller = new WebSearchSettingsController(service, web);
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private Map<String, Object> save(String body) throws Exception { return controller.put(json.readTree(body)); }

    @Test void defaultsAndPermissionsArePortableAndSeparate() throws Exception {
        assertEquals(0L, controller.get().get("revision"));
        assertEquals("auto", controller.get().get("searchProvider")); assertEquals(false, controller.get().get("preferNative"));
        var permissions = new AiToolSettingsRepository(jdbc);
        assertTrue(permissions.read().values().enabled("search_web")); assertTrue(permissions.read().values().enabled("fetch_web"));
        save("{\"revision\":0,\"preferNative\":true}");
        permissions.save(0, permissions.read().values().with(Map.of("search_web", false, "fetch_web", false)));
        assertTrue(new WebSearchSettingsRepository(jdbc).read().values().preferNative());
        assertFalse(permissions.read().values().enabled("search_web"));
        assertEquals(19, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
    }

    @Test void bothCapabilitiesShareOneEncryptedKeyAndViewsNeverReturnIt() throws Exception {
        String plaintext = "tavily-fixture-not-a-real-key";
        var saved = save("{\"revision\":0,\"searchProvider\":\"tavily\",\"fetchProvider\":\"tavily\",\"keys\":{\"tavily\":{\"apiKey\":\""+plaintext+"\"}}}");
        var values = repository.read().values();
        assertEquals(1, values.keys().size()); assertEquals(plaintext, service.key(values, WebSearchSettings.Provider.tavily));
        String disk = jdbc.queryForObject("SELECT document FROM ai_web_settings", String.class);
        assertFalse(disk.contains(plaintext)); assertTrue(disk.contains("encrypted"));
        String view = json.writeValueAsString(saved);
        assertFalse(view.contains(plaintext)); assertFalse(view.contains("encrypted")); assertFalse(view.contains("salt"));
        assertTrue(view.contains("hasKey\":true"));
        var immediate = save("{\"revision\":1,\"preferNative\":true}");
        assertEquals("tavily", immediate.get("searchProvider"));
        assertEquals(values.keys(), repository.read().values().keys());
        assertEquals(plaintext, new WebSearchSettingsService(new WebSearchSettingsRepository(jdbc), new ApiKeyEncryptor())
                .key(repository.read().values(), WebSearchSettings.Provider.tavily));
    }

    @Test void blankKeepsKeyAndClearIsExplicitAndAtomic() throws Exception {
        save("{\"revision\":0,\"searchProvider\":\"tinyfish\",\"keys\":{\"tinyfish\":{\"apiKey\":\"fixture-first\"}}}");
        var previous = repository.read().values().keys();
        save("{\"revision\":1,\"keys\":{\"tinyfish\":{\"apiKey\":\"   \"}}}");
        assertEquals(previous, repository.read().values().keys());
        assertThrows(IllegalArgumentException.class, () -> save("{\"revision\":2,\"keys\":{\"tinyfish\":{\"clear\":true}}}"));
        assertEquals(2, repository.read().revision()); assertEquals(previous, repository.read().values().keys());
        save("{\"revision\":2,\"keys\":{\"tinyfish\":{\"apiKey\":\"fixture-new\"}}}");
        assertEquals("fixture-new", service.key(repository.read().values(), WebSearchSettings.Provider.tinyfish));
        save("{\"revision\":3,\"searchProvider\":\"auto\",\"keys\":{\"tinyfish\":{\"clear\":true}}}");
        assertFalse(repository.read().values().hasKey(WebSearchSettings.Provider.tinyfish));
    }

    @Test void conflictAndInvalidInputCannotOverwriteOtherWindows() throws Exception {
        save("{\"revision\":0,\"preferNative\":true}");
        assertEquals("AI_SETTINGS_CONFLICT", assertThrows(ChatTurnRejectException.class,
                () -> save("{\"revision\":0,\"preferNative\":false}")).code());
        for (String body : List.of("{}", "{\"revision\":1,\"preferNative\":\"true\"}", "{\"revision\":1,\"searchProvider\":\"random\"}",
                "{\"revision\":1,\"searchProvider\":\"tavily\"}", "{\"revision\":1,\"keys\":{\"auto\":{\"apiKey\":\"bad\"}}}",
                "{\"revision\":1,\"keys\":{\"tinyfish\":{\"apiKey\":false}}}", "{\"revision\":1,\"keys\":{\"tavily\":{\"apiKey\":\"new\",\"clear\":true}}}",
                "{\"revision\":1,\"endpoint\":\"http://localhost\"}")) assertThrows(IllegalArgumentException.class, () -> save(body));
        assertEquals(1, repository.read().revision()); assertTrue(repository.read().values().preferNative());
    }

    @Test void connectionTestUsesDraftAndChecksResultsWithoutSavingOrChangingPermissions() throws Exception {
        when(web.test(any(), anyBoolean())).thenAnswer(invocation -> Map.of("capability", invocation.getArgument(1, Boolean.class) ? "fetch" : "search",
                "status", "QUOTA_EXHAUSTED", "count", 0, "webRoute", List.of()));
        var result = controller.test(json.readTree("{\"searchProvider\":\"tinyfish\",\"keys\":{\"tinyfish\":{\"apiKey\":\"fixture-key\"}}}"));
        assertEquals(2, ((List<?>)result.get("checks")).size());
        verify(web).test(argThat(value -> value.searchProvider() == WebSearchSettings.Provider.tinyfish && value.hasKey(WebSearchSettings.Provider.tinyfish)), eq(false));
        verify(web).test(any(), eq(true));
        assertEquals(0, repository.read().revision());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_tool_settings", Integer.class));
        assertFalse(json.writeValueAsString(result).contains("fixture-key"));
    }

    @Test void damagedSettingsFailClosedInsteadOfReenablingAnonymousRequests() {
        jdbc.update("INSERT INTO ai_web_settings(id,revision,document) VALUES(1,5,'broken')");
        for (String value : List.of("broken", "null", "{}", "{\"searchProvider\":\"auto\",\"fetchProvider\":\"auto\",\"keys\":{}}")) {
            jdbc.update("UPDATE ai_web_settings SET document=?", value);
            assertThrows(IllegalStateException.class, controller::get);
        }
        verifyNoInteractions(web);
    }
}
