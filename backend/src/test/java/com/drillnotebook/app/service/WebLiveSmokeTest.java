package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.WebSearchSettingsRepository;
import com.drillnotebook.app.service.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit opt-in: only public inputs and anonymous endpoints; no configured AI account or private application data. */
@EnabledIfSystemProperty(named = "drill.web.smoke", matches = "true")
class WebLiveSmokeTest {
    private Path root;
    private PublicWebPolicy policy;
    @AfterEach void cleanup() throws Exception {
        if (policy != null) policy.close();
        TestWorkspace.deleteRecursively(root);
    }
    @Test void defaultProductionAdaptersSearchAndFetchPublicContentWithoutConfiguration() throws Exception {
        root = TestWorkspace.scratchDirectory("web-live-smoke-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        var settings = new WebSearchSettingsService(new WebSearchSettingsRepository(new JdbcTemplate(ds)), new ApiKeyEncryptor());
        settings.save(new ObjectMapper().valueToTree(Map.of("revision", 0, "preferNative", false)));
        policy = new PublicWebPolicy(); var http = new WebHttpClient(policy);
        var web = new WebSearchService(settings, new WebProviders(http, policy), new LocalWebFetcher(http, policy), policy);
        var search = web.execute(false, "Exa MCP official documentation search fetch", List.of(), 3, 1200, 0, null);
        System.out.println("WEB_LIVE_SEARCH status=" + search.get("status") + " route=" + search.get("webRoute"));
        assertEquals("OK", search.get("status")); assertFalse(((List<?>) search.get("items")).isEmpty());
        var fetch = web.execute(true, "", List.of("https://exa.ai/docs/get-started/exa-mcp"), 1, 6000, 0, null);
        System.out.println("WEB_LIVE_FETCH status=" + fetch.get("status") + " route=" + fetch.get("webRoute"));
        assertEquals("OK", fetch.get("status")); assertEquals(1, ((List<?>) fetch.get("items")).size());
        @SuppressWarnings("unchecked") var page = ((List<Map<String, Object>>) fetch.get("items")).get(0);
        assertTrue(((String) page.get("content")).length() >= 80); assertEquals(true, page.get("untrusted"));
        assertEquals(false, settings.read().values().preferNative()); assertTrue(settings.read().values().keys().isEmpty());
    }
}
