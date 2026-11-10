package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.AiToolSettings;
import com.drillnotebook.app.repository.AiToolSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/tool-settings")
public class AiToolSettingsController {
    private final AiToolSettingsRepository settings;
    public AiToolSettingsController(AiToolSettingsRepository settings) { this.settings = settings; }

    @GetMapping
    public Map<String, Object> get() { return view(settings.read()); }

    /** 只更新请求给出的开关，其余保持；恢复默认由前端显式提交各项默认值。 */
    @PutMapping
    public Map<String, Object> put(@RequestBody JsonNode body) {
        if (!body.path("revision").isIntegralNumber() || !body.path("revision").canConvertToLong()) {
            throw new IllegalArgumentException("请提供设置版本");
        }
        JsonNode enabled = body.path("enabled");
        if (!enabled.isObject() || enabled.isEmpty()) throw new IllegalArgumentException("请提供要修改的工具开关");
        Map<String, Boolean> changes = new LinkedHashMap<>();
        enabled.fields().forEachRemaining(entry -> {
            if (!AiToolSettings.managed(entry.getKey()) || !entry.getValue().isBoolean()) {
                throw new IllegalArgumentException("工具开关 " + entry.getKey() + " 无效");
            }
            changes.put(entry.getKey(), entry.getValue().booleanValue());
        });
        var current = settings.read();
        return view(settings.save(body.path("revision").longValue(), current.values().with(changes)));
    }

    private static Map<String, Object> view(AiToolSettingsRepository.Snapshot snapshot) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (AiToolSettings.Tool tool : AiToolSettings.TOOLS) {
            String name = tool.name();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("group", tool.group());
            row.put("enabled", snapshot.values().enabled(name));
            row.put("defaultEnabled", AiToolSettings.defaultEnabled(name));
            tools.add(row);
        }
        return Map.of("revision", snapshot.revision(), "tools", tools);
    }
}
