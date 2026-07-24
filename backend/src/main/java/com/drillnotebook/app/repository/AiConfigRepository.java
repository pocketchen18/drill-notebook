package com.drillnotebook.app.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiConfigRepository {
    public static final String PURPOSE_CHAT = "chat";
    public static final String PURPOSE_IMPORT = "import";

    private final JdbcTemplate jdbc;

    public AiConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 主模型：对话 / 总结 / 学习计划等。 */
    public ConfigRow findChat() {
        return find(PURPOSE_CHAT);
    }

    /** 导入兜底：PDF / Markdown / JSON / 知识点 AI 解析。 */
    public ConfigRow findImport() {
        return find(PURPOSE_IMPORT);
    }

    public ConfigRow find(String purpose) {
        String key = purpose == null || purpose.isBlank() ? PURPOSE_CHAT : purpose.trim();
        List<ConfigRow> rows = jdbc.query(
                "SELECT purpose, provider, endpoint, model, encrypted_key, key_meta, params FROM ai_config WHERE purpose = ?",
                (result, row) -> new ConfigRow(
                        result.getString("purpose"),
                        result.getString("provider"),
                        result.getString("endpoint"),
                        result.getString("model"),
                        result.getString("encrypted_key"),
                        result.getString("key_meta"),
                        result.getString("params")),
                key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsertChat(String provider, String endpoint, String model, String encryptedKey, String keyMeta, String params) {
        upsert(PURPOSE_CHAT, provider, endpoint, model, encryptedKey, keyMeta, params);
    }

    public void upsertImport(String provider, String endpoint, String model, String encryptedKey, String keyMeta, String params) {
        upsert(PURPOSE_IMPORT, provider, endpoint, model, encryptedKey, keyMeta, params);
    }

    public void upsert(String purpose, String provider, String endpoint, String model, String encryptedKey, String keyMeta, String params) {
        String key = purpose == null || purpose.isBlank() ? PURPOSE_CHAT : purpose.trim();
        jdbc.update("""
                INSERT INTO ai_config(purpose, provider, endpoint, model, encrypted_key, key_meta, params) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(purpose) DO UPDATE SET
                  provider = excluded.provider,
                  endpoint = excluded.endpoint,
                  model = excluded.model,
                  encrypted_key = COALESCE(excluded.encrypted_key, ai_config.encrypted_key),
                  key_meta = COALESCE(excluded.key_meta, ai_config.key_meta),
                  params = excluded.params
                """, key, provider, endpoint, model, encryptedKey, keyMeta, params);
    }

    public record ConfigRow(
            String purpose,
            String provider,
            String endpoint,
            String model,
            String encryptedKey,
            String keyMeta,
            String params) {
        /** 兼容旧 6 字段构造（无 purpose 时视为 chat）。 */
        public ConfigRow(String provider, String endpoint, String model, String encryptedKey, String keyMeta, String params) {
            this(PURPOSE_CHAT, provider, endpoint, model, encryptedKey, keyMeta, params);
        }
    }
}
