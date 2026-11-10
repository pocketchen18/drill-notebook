package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.AiToolSettings;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 工具开关保存在现有便携数据库；无法解析的文档使用默认值，未知名称和无效值不予采纳。 */
@Repository
public class AiToolSettingsRepository {
    public record Snapshot(long revision, AiToolSettings values) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiToolSettingsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Snapshot read() {
        var rows = jdbc.query("SELECT revision, document FROM ai_tool_settings WHERE id = 1",
                (rs, index) -> new Snapshot(rs.getLong("revision"), parse(rs.getString("document"))));
        return rows.isEmpty() ? new Snapshot(0, AiToolSettings.DEFAULTS) : rows.get(0);
    }

    public Snapshot save(long revision, AiToolSettings values) {
        if (revision < 0 || values == null) throw new IllegalArgumentException("设置版本或内容无效");
        String json;
        try { json = mapper.writeValueAsString(Map.of("enabled", values.overrides())); }
        catch (Exception error) { throw new IllegalArgumentException("设置无法保存", error); }
        int changed = revision == 0
                ? jdbc.update("INSERT OR IGNORE INTO ai_tool_settings(id, revision, document) VALUES (1, 1, ?)", json)
                : jdbc.update("UPDATE ai_tool_settings SET revision = revision + 1, document = ? WHERE id = 1 AND revision = ?", json, revision);
        if (changed != 1) throw new ChatTurnRejectException(409, "AI_SETTINGS_CONFLICT",
                "设置已在其他窗口修改，请重新载入后保存", Map.of());
        return new Snapshot(revision + 1, values);
    }

    /** 只采纳当前目录认识的布尔项：损坏或来自其他版本的内容不能把删除工具意外打开。 */
    private AiToolSettings parse(String document) {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        try {
            JsonNode node = mapper.readTree(document).path("enabled");
            node.fields().forEachRemaining(entry -> {
                if (AiToolSettings.managed(entry.getKey()) && entry.getValue().isBoolean())
                    enabled.put(entry.getKey(), entry.getValue().booleanValue());
            });
        } catch (Exception ignored) { return AiToolSettings.DEFAULTS; }
        return new AiToolSettings(enabled);
    }
}
