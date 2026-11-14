package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.WebSearchService;
import com.drillnotebook.app.service.WebSearchSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/web-settings")
public class WebSearchSettingsController {
    private final WebSearchSettingsService settings;
    private final WebSearchService web;
    public WebSearchSettingsController(WebSearchSettingsService settings, WebSearchService web) { this.settings = settings; this.web = web; }
    @GetMapping public Map<String, Object> get() { return settings.view(settings.read()); }
    @PutMapping public Map<String, Object> put(@RequestBody JsonNode body) { return settings.save(body); }
    @PostMapping("/test") public Map<String, Object> test(@RequestBody JsonNode body) {
        var draft = settings.draft(settings.read().values(), body);
        String capability = body.path("capability").asText("both");
        if (!List.of("search", "fetch", "both").contains(capability)) throw new IllegalArgumentException("测试能力无效");
        // The test is explicit and uses public fixture input; it does not save settings or change tool permissions.
        return Map.of("checks", capability.equals("both") ? List.of(web.test(draft, false), web.test(draft, true))
                : List.of(web.test(draft, capability.equals("fetch"))));
    }
}
