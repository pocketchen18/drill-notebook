package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.AiRuntimeSettings;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 全局预算只保存到现有便携数据库，不使用模型 params 或用户目录文件。 */
@Repository
public class AiRuntimeSettingsRepository {
    public record Snapshot(long revision, AiRuntimeSettings values) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiRuntimeSettingsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Snapshot read() {
        var rows = jdbc.query("SELECT revision, document FROM ai_runtime_settings WHERE id = 1", (rs, index) -> {
            try { return new Snapshot(rs.getLong("revision"), mapper.readValue(rs.getString("document"), AiRuntimeSettings.class)); }
            catch (Exception error) { throw new IllegalStateException("AI 对话设置无法读取，原数据已保留", error); }
        });
        return rows.isEmpty() ? new Snapshot(0, AiRuntimeSettings.DEFAULTS) : rows.get(0);
    }

    public Snapshot save(long revision, AiRuntimeSettings values) {
        if (revision < 0 || values == null) throw new IllegalArgumentException("设置版本或内容无效");
        String json;
        try { json = mapper.writeValueAsString(values); }
        catch (Exception error) { throw new IllegalArgumentException("设置无法保存", error); }
        int changed = revision == 0
                ? jdbc.update("INSERT OR IGNORE INTO ai_runtime_settings(id, revision, document) VALUES (1, 1, ?)", json)
                : jdbc.update("UPDATE ai_runtime_settings SET revision = revision + 1, document = ? WHERE id = 1 AND revision = ?", json, revision);
        if (changed != 1) throw new ChatTurnRejectException(409, "AI_SETTINGS_CONFLICT",
                "设置已在其他窗口修改，请重新载入后保存", Map.of());
        return new Snapshot(revision + 1, values);
    }
}
