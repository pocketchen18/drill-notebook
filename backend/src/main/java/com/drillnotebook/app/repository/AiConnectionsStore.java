package com.drillnotebook.app.repository;

import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 小型配置集合一次提交；旧槽位只作迁移源，不维护第二份生效配置。 */
public class AiConnectionsStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Model(String id, String modelId, String name, String group, Map<String, Object> params) { }
    public record Provider(String id, String name, String apiFormat, String endpoint, String authMode,
                           String encryptedKey, String keyMeta, List<Model> models) { }
    public record State(long revision, List<Provider> providers, String chatModelId, String importModelId,
                        String chatReasoningEffort) {
        public State(long revision, List<Provider> providers, String chatModelId, String importModelId) {
            this(revision, providers, chatModelId, importModelId, null);
        }
        public Model model(String id) {
            return providers.stream().flatMap(p -> p.models().stream())
                    .filter(m -> m.id().equals(id)).findFirst().orElse(null);
        }
        public Provider owner(String id) {
            return providers.stream().filter(p -> p.models().stream().anyMatch(m -> m.id().equals(id)))
                    .findFirst().orElse(null);
        }
        public Provider provider(String id) {
            return providers.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        }
    }

    public AiConnectionsStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public State read() {
        List<State> rows = jdbc.query("SELECT revision, document FROM ai_connections WHERE id = 1", (rs, index) -> {
            try {
                State state = mapper.readValue(rs.getString("document"), State.class);
                return new State(rs.getLong("revision"), state.providers(), state.chatModelId(), state.importModelId(), state.chatReasoningEffort());
            } catch (Exception error) { throw new IllegalStateException("AI 连接配置无法读取，原数据已保留", error); }
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    public State initialize(Supplier<State> migrate) {
        State existing = read();
        if (existing != null) return existing;
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            State again = read();
            if (again != null) return again;
            State state = migrate.get();
            jdbc.update("INSERT OR IGNORE INTO ai_connections(id, revision, document) VALUES(1, 1, ?)", json(state));
            return read();
        });
    }

    /** 旧会话优先恢复最近实际使用的供应商/模型；其余空选择只继承一次当前默认。 */
    public void initializeSessionChoices(State state) {
        if (state == null || state.chatModelId() == null) return;
        var missing = jdbc.queryForList("""
                SELECT s.id, s.model,
                  (SELECT model_label FROM ai_chat_turn t WHERE t.session_id = s.id AND model_label IS NOT NULL ORDER BY id DESC LIMIT 1) AS last_model,
                  (SELECT reasoning_effort FROM ai_chat_turn t WHERE t.session_id = s.id AND model_label IS NOT NULL ORDER BY id DESC LIMIT 1) AS last_effort
                FROM ai_chat_session s WHERE s.model_config_id IS NULL
                """);
        for (var session : missing) {
            String id = state.chatModelId();
            String effort = state.chatReasoningEffort();
            Object label = session.get("last_model") == null ? session.get("model") : session.get("last_model");
            var matches = state.providers().stream().flatMap(provider -> provider.models().stream()
                    .filter(model -> (provider.name() + " · " + model.modelId()).equals(label)
                            || model.modelId().equals(label)).map(Model::id)).toList();
            if (matches.size() == 1) {
                id = matches.get(0);
                effort = session.get("last_effort") instanceof String value ? value : null;
            }
            jdbc.update("""
                    UPDATE ai_chat_session SET model_config_id = ?, reasoning_effort = ?,
                      model_choice_revision = model_choice_revision + 1 WHERE id = ? AND model_config_id IS NULL
                    """, id, effort, session.get("id"));
        }
    }

    public Map<String, Object> sessionChoice(long sessionId) {
        var rows = jdbc.queryForList("SELECT id, model_config_id, reasoning_effort, model_choice_revision FROM ai_chat_session WHERE id = ?", sessionId);
        if (rows.isEmpty()) throw new ChatTurnRejectException(404, "SESSION_NOT_FOUND", "会话不存在", Map.of());
        var row = rows.get(0);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("sessionId", sessionId);
        choice.put("modelConfigId", row.get("model_config_id"));
        choice.put("reasoningEffort", row.get("reasoning_effort"));
        choice.put("revision", row.get("model_choice_revision"));
        return choice;
    }

    public State save(State state, long expected) {
        int updated = jdbc.update("UPDATE ai_connections SET document = ?, revision = revision + 1 WHERE id = 1 AND revision = ?",
                json(state), expected);
        if (updated != 1) throw conflict();
        return new State(expected + 1, state.providers(), state.chatModelId(), state.importModelId(), state.chatReasoningEffort());
    }

    /** 保存助手模型选择与同步已开启的记忆授权一起提交；授权写入失败时配置也回滚。 */
    public State saveWithMemoryConsent(State state, long expected, String useFingerprint, String maintenanceFingerprint) {
        return saveWithMemoryConsent(state, expected, useFingerprint, maintenanceFingerprint, null);
    }

    /** 当前会话和下一新会话的默认选择原子保存，不改其它会话。 */
    public State saveWithMemoryConsent(State state, long expected, String useFingerprint, String maintenanceFingerprint, Long sessionId) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            if (sessionId != null) sessionChoice(sessionId);
            State saved = save(state, expected);
            if (sessionId != null) jdbc.update("""
                    UPDATE ai_chat_session SET model_config_id = ?, reasoning_effort = ?,
                      model_choice_revision = model_choice_revision + 1 WHERE id = ?
                    """, saved.chatModelId(), saved.chatReasoningEffort(), sessionId);
            // 首次配置模型前建立的空会话也固定自己的初始选择。
            initializeSessionChoices(saved);
            String now = Instant.now().toString();
            // 专用维护模型保留自己的授权；未启用记忆时不创建设置、不改开关或授权。
            if (useFingerprint != null) jdbc.update("""
                    UPDATE ai_memory_settings SET
                      use_consent_fingerprint = ?,
                      maintenance_consent_fingerprint = CASE
                        WHEN COALESCE(json_extract(policy_json, '$.maintenanceModelMode'), 'chat') = 'dedicated'
                        THEN maintenance_consent_fingerprint ELSE ? END,
                      revision = revision + 1, consented_at = ?, updated_at = ?
                    WHERE id = 1 AND enabled = 1 AND (
                      use_consent_fingerprint IS NOT ? OR (
                        COALESCE(json_extract(policy_json, '$.maintenanceModelMode'), 'chat') <> 'dedicated'
                        AND maintenance_consent_fingerprint IS NOT ?))
                    """, useFingerprint, maintenanceFingerprint, now, now, useFingerprint, maintenanceFingerprint);
            return saved;
        });
    }

    public AiConfigRepository.ConfigRow resolve(State state, String purpose) {
        String id = "chat".equals(purpose) ? state.chatModelId() : state.importModelId();
        return resolve(state, purpose, id, "chat".equals(purpose) ? state.chatReasoningEffort() : null);
    }

    public AiConfigRepository.ConfigRow resolveChat(State state, String modelConfigId, String effort) {
        return resolve(state, "chat", modelConfigId, effort);
    }

    private AiConfigRepository.ConfigRow resolve(State state, String purpose, String id, String effort) {
        if (id == null) return null;
        Provider provider = state.owner(id);
        Model model = state.model(id);
        if (provider == null || model == null) throw new IllegalArgumentException("所选模型已不存在，请重新选择模型");
        Map<String, Object> params = new LinkedHashMap<>(model.params());
        if ("chat".equals(purpose) && effort != null
                && "effort".equals(params.getOrDefault("reasoningControl", "effort"))
                && params.get("reasoningEfforts") instanceof List<?> efforts && efforts.contains(effort)) {
            params.put("defaultReasoningEffort", effort);
        }
        params.put("apiFormat", provider.apiFormat());
        params.put("authMode", provider.authMode());
        params.put("connectionId", provider.id());
        params.put("modelConfigId", model.id());
        params.put("connectionRevision", state.revision());
        return new AiConfigRepository.ConfigRow(purpose, provider.name(), provider.endpoint(), model.modelId(),
                provider.encryptedKey(), provider.keyMeta(), json(params));
    }

    public static ChatTurnRejectException conflict() {
        return new ChatTurnRejectException(409, "CONFIG_CHANGED", "配置已变更，请重载后核对；本地输入已保留", Map.of());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("AI 配置序列化失败", error); }
    }
}
