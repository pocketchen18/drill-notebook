package com.drillnotebook.app.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiConfigRepository {
    public static final String PURPOSE_CHAT = "chat";
    public static final String PURPOSE_IMPORT = "import";
    public static final String PURPOSE_EMBEDDING = "embedding";
    /** 记忆维护专用槽位（§5.1 的 maintenanceModelMode=dedicated）。 */
    public static final String PURPOSE_MEMORY = "memory";

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

    /** Embedding：笔记本向量索引（独立于 chat/import 配置）。 */
    public ConfigRow findEmbedding() {
        return find(PURPOSE_EMBEDDING);
    }

    /** 记忆维护专用配置行；未配置时返回 null，dedicated 模式据此拒绝外发。 */
    public ConfigRow findMemory() {
        return find(PURPOSE_MEMORY);
    }

    public ConfigRow find(String purpose) {
        if (PURPOSE_CHAT.equals(purpose) || PURPOSE_IMPORT.equals(purpose)) {
            AiConnectionsStore store = new AiConnectionsStore(jdbc);
            AiConnectionsStore.State state = store.read();
            if (state != null) return store.resolve(state, purpose);
        }
        return findLegacy(purpose);
    }

    public AiConnectionsStore connections() { return new AiConnectionsStore(jdbc); }

    public ConfigRow findLegacy(String purpose) {
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
        if ((PURPOSE_CHAT.equals(key) || PURPOSE_IMPORT.equals(key)) && connections().read() != null) {
            throw new IllegalArgumentException("请在设置 → AI 连接的供应商和模型列表中修改配置");
        }
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

    public void updateParams(String purpose, String params) {
        String key = purpose == null || purpose.isBlank() ? PURPOSE_CHAT : purpose.trim();
        jdbc.update("UPDATE ai_config SET params = ? WHERE purpose = ?", params, key);
    }

    /** 旧请求的能力回写不能覆盖新应用项或新编辑值。 */
    public void updateParamsIfCurrent(ConfigRow snapshot, String params) {
        AiConnectionsStore store = connections();
        AiConnectionsStore.State state = store.read();
        if (state != null && (PURPOSE_CHAT.equals(snapshot.purpose()) || PURPOSE_IMPORT.equals(snapshot.purpose()))) {
            if (!snapshot.equals(store.resolve(state, snapshot.purpose()))) return;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var values = mapper.readValue(params, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
                String id = String.valueOf(values.get("modelConfigId"));
                var providers = state.providers().stream().map(p -> new AiConnectionsStore.Provider(p.id(), p.name(),
                        p.apiFormat(), p.endpoint(), p.authMode(), p.encryptedKey(), p.keyMeta(), p.models().stream().map(m -> {
                            if (!m.id().equals(id)) return m;
                            var updated = new java.util.LinkedHashMap<>(m.params());
                            updated.put("cacheBreakpoints", false);
                            return new AiConnectionsStore.Model(m.id(), m.modelId(), m.name(), m.group(), updated);
                        }).toList())).toList();
                store.save(new AiConnectionsStore.State(state.revision(), providers, state.chatModelId(), state.importModelId()), state.revision());
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalArgumentException("AI 配置无法读取", error);
            }
        } else {
            jdbc.update("UPDATE ai_config SET params = ? WHERE purpose = ? AND endpoint IS ? AND model IS ? AND params IS ? AND encrypted_key IS ?",
                    params, snapshot.purpose(), snapshot.endpoint(), snapshot.model(), snapshot.params(), snapshot.encryptedKey());
        }
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
