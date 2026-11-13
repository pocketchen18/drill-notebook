package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.model.AiRetrySettings;
import com.drillnotebook.app.repository.AiRuntimeSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/runtime-settings")
public class AiRuntimeSettingsController {
    private final AiRuntimeSettingsRepository settings;
    public AiRuntimeSettingsController(AiRuntimeSettingsRepository settings) { this.settings = settings; }

    @GetMapping
    public Map<String, Object> get() { return view(settings.read()); }

    @PutMapping
    public Map<String, Object> put(@RequestBody JsonNode body) {
        if (!body.path("revision").isIntegralNumber() || !body.path("revision").canConvertToLong()) {
            throw new IllegalArgumentException("请提供设置版本");
        }
        JsonNode values = body.path("values");
        var next = new AiRuntimeSettings(integer(values, "maxToolStages"), integer(values, "maxToolExecutions"),
                integer(values, "turnTimeoutMinutes"), integer(values, "requestTimeoutMinutes"),
                integer(values, "maxOutputTokens"), integer(values, "requestOutputTokens"),
                integer(values, "finalOutputTokens"), integer(values, "repeatedToolLimit"), retry(values));
        return view(settings.save(body.path("revision").longValue(), next));
    }

    private static int integer(JsonNode values, String key) {
        if (!values.path(key).isIntegralNumber() || !values.path(key).canConvertToInt()) {
            throw new IllegalArgumentException("设置项 " + key + " 须为整数");
        }
        return values.path(key).intValue();
    }

    private AiRetrySettings retry(JsonNode values) {
        if (!values.has("retry")) return settings.read().values().retry();
        JsonNode retry = values.path("retry");
        if (!retry.path("networkPersistent").isBoolean()) throw new IllegalArgumentException("请选择断网等待方式");
        return new AiRetrySettings(integer(retry, "httpRetries"), integer(retry, "streamRetries"),
                retry.path("networkPersistent").booleanValue(), integer(retry, "networkRetries"));
    }

    private static Map<String, Object> view(AiRuntimeSettingsRepository.Snapshot snapshot) {
        return Map.of("revision", snapshot.revision(), "values", snapshot.values(), "defaults", AiRuntimeSettings.DEFAULTS);
    }
}
