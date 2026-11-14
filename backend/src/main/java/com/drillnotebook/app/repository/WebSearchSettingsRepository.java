package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.WebSearchSettings;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebSearchSettingsRepository {
    public record Snapshot(long revision, WebSearchSettings values) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public WebSearchSettingsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Snapshot read() {
        var rows = jdbc.query("SELECT revision, document FROM ai_web_settings WHERE id = 1",
                (rs, index) -> new Snapshot(rs.getLong("revision"), parse(rs.getString("document"))));
        return rows.isEmpty() ? new Snapshot(0, WebSearchSettings.DEFAULTS) : rows.get(0);
    }

    public Snapshot save(long revision, WebSearchSettings values) {
        if (revision < 0 || values == null) throw new IllegalArgumentException("联网设置版本或内容无效");
        String document;
        try { document = json.writeValueAsString(values); }
        catch (Exception error) { throw new IllegalStateException("联网设置无法保存"); }
        int changed = revision == 0
                ? jdbc.update("INSERT OR IGNORE INTO ai_web_settings(id, revision, document) VALUES(1, 1, ?)", document)
                : jdbc.update("UPDATE ai_web_settings SET revision = revision + 1, document = ? WHERE id = 1 AND revision = ?", document, revision);
        if (changed != 1) throw new ChatTurnRejectException(409, "AI_SETTINGS_CONFLICT",
                "联网设置已在其他窗口修改，请重新载入后保存", Map.of());
        return new Snapshot(revision + 1, values);
    }

    private WebSearchSettings parse(String document) {
        try {
            var root = json.readTree(document);
            if (!root.isObject() || !root.path("preferNative").isBoolean() || !root.path("keys").isObject()) {
                throw new IllegalArgumentException();
            }
            WebSearchSettings values = json.treeToValue(root, WebSearchSettings.class);
            for (var key : values.keys().values()) {
                if (key.encrypted() == null || key.salt() == null || key.iv() == null || !"fingerprint".equals(key.mode())) {
                    throw new IllegalArgumentException();
                }
            }
            return values;
        } catch (Exception error) { throw new IllegalStateException("联网设置无法读取，请检查本地数据"); }
    }
}
