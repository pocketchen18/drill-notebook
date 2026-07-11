package com.drillnotebook.app.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiConfigRepository {
    private final JdbcTemplate jdbc;

    public AiConfigRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ConfigRow find() {
        List<ConfigRow> rows = jdbc.query("SELECT id, provider, endpoint, model, encrypted_key, key_meta, params FROM ai_config WHERE id = 1", (result, row) -> new ConfigRow(
                result.getString("provider"), result.getString("endpoint"), result.getString("model"), result.getString("encrypted_key"), result.getString("key_meta"), result.getString("params")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsert(String provider, String endpoint, String model, String encryptedKey, String keyMeta, String params) {
        jdbc.update("""
                INSERT INTO ai_config(id, provider, endpoint, model, encrypted_key, key_meta, params) VALUES (1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET provider = excluded.provider, endpoint = excluded.endpoint, model = excluded.model,
                encrypted_key = COALESCE(excluded.encrypted_key, ai_config.encrypted_key), key_meta = COALESCE(excluded.key_meta, ai_config.key_meta), params = excluded.params
                """, provider, endpoint, model, encryptedKey, keyMeta, params);
    }

    public record ConfigRow(String provider, String endpoint, String model, String encryptedKey, String keyMeta, String params) {}
}
