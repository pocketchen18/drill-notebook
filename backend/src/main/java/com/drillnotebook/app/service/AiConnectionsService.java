package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiConnectionsStore;
import com.drillnotebook.app.repository.AiConnectionsStore.Model;
import com.drillnotebook.app.repository.AiConnectionsStore.Provider;
import com.drillnotebook.app.repository.AiConnectionsStore.State;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

@Service
public class AiConnectionsService {
    private final AiConfigRepository configs;
    private final AiConnectionsStore store;
    private final ApiKeyEncryptor keys;
    private final ObjectMapper mapper;
    private final AiUpstreamClient upstream;
    private final java.util.concurrent.ConcurrentHashMap<String, ActiveTurnRegistry.Handle> tests = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> cancelledTests = new LinkedHashMap<>();
    private final java.util.Set<String> verifyingModels = new java.util.HashSet<>();

    public AiConnectionsService(AiConfigRepository configs, ApiKeyEncryptor keys, ObjectMapper mapper) {
        this.configs = configs;
        this.store = configs.connections();
        this.keys = keys;
        this.mapper = mapper;
        this.upstream = new AiUpstreamClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), mapper);
    }

    private State state() {
        State state = store.initialize(this::migrate);
        store.initializeSessionChoices(state);
        return state;
    }
    public Map<String, Object> view() { synchronized (AiConnectionUsage.LOCK) { return view(state()); } }
    public List<AiModelPresets.Preset> presets(String providerId) {
        synchronized (AiConnectionUsage.LOCK) { return AiModelPresets.forProvider(provider(state(), providerId)); }
    }

    private State migrate() {
        List<Provider> providers = new ArrayList<>();
        String chat = null;
        String imported = null;
        for (String purpose : List.of("chat", "import")) {
            var row = configs.findLegacy(purpose);
            if (row == null || (blank(row.endpoint()) && blank(row.model()) && blank(row.encryptedKey()))) continue;
            Map<String, Object> params = new LinkedHashMap<>(AiModelLimits.params(row));
            String format = AiService.apiFormatOf(row);
            params.putIfAbsent("streaming", true);
            params.putIfAbsent("tools", ProviderCapabilities.of(row.endpoint(), format, row.model()).tools());
            String modelId = uuid();
            Model model = new Model(modelId, row.model() == null ? "" : row.model().trim(), "", "", params);
            providers.add(new Provider(uuid(), "chat".equals(purpose) ? "原主模型" : "原导入兜底", format,
                    row.endpoint() == null ? "" : row.endpoint(), "mock://local".equals(row.endpoint()) ? "none" : "api_key",
                    row.encryptedKey(), row.keyMeta(), List.of(model)));
            if ("chat".equals(purpose)) chat = modelId; else imported = modelId;
        }
        return new State(1, providers, chat, imported);
    }

    public Map<String, Object> saveProvider(String id, Map<String, Object> body) {
        return mutate(body, state -> {
            Provider old = id == null ? null : provider(state, id);
            String name = text(body, "name", 60, true);
            if (state.providers().stream().anyMatch(p -> p.name().equals(name) && !p.id().equals(id))) invalid("供应商名称已存在");
            String endpoint = text(body, "endpoint", 2048, true);
            if (!(old != null && "mock://local".equals(old.endpoint()) && endpoint.equals(old.endpoint()))) {
                try {
                    URI uri = URI.create(endpoint);
                    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                            || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                        invalid("Base URL 须为不含账号密码、查询参数或片段的 HTTP(S) 地址");
                    }
                } catch (IllegalArgumentException error) { invalid("Base URL 须为不含账号密码、查询参数或片段的 HTTP(S) 地址"); }
            }
            String format = AiService.normalizeApiFormat(text(body, "apiFormat", 40, true));
            String auth = text(body, "authMode", 20, true);
            if (!List.of("api_key", "none").contains(auth)) invalid("请选择认证方式");
            String encrypted = old == null ? null : old.encryptedKey();
            String metadata = old == null ? null : old.keyMeta();
            if ("none".equals(auth)) { encrypted = null; metadata = null; }
            else if (body.containsKey("apiKey")) {
                String raw = text(body, "apiKey", 8192, true);
                String password = text(body, "masterPassword", 8192, false);
                try {
                    var value = keys.encrypt(raw, password.isBlank() ? keys.fingerprintMaterial() : password,
                            password.isBlank() ? "fingerprint" : "password");
                    encrypted = value.encrypted();
                    metadata = json(Map.of("salt", value.salt(), "iv", value.iv(), "kdf", "Argon2id", "algorithm", "AES-256-GCM", "mode", value.mode()));
                } catch (Exception error) { throw new IllegalArgumentException("密钥加密失败，请重试"); }
            }
            if ("api_key".equals(auth) && blank(encrypted)) invalid("请输入 API Key，或选择无需密钥");
            List<Model> models = old == null ? List.of() : old.models();
            if (old != null && (!old.endpoint().equals(endpoint) || !old.apiFormat().equals(format)
                    || !old.authMode().equals(auth) || body.containsKey("apiKey"))) {
                models = models.stream().map(model -> {
                    Map<String, Object> params = new LinkedHashMap<>(model.params());
                    if (!old.endpoint().equals(endpoint) || !old.apiFormat().equals(format)) params.remove("cacheBreakpoints");
                    params.remove("verification"); params.remove("visionVerified");
                    return new Model(model.id(), model.modelId(), model.name(), model.group(), params);
                }).toList();
            }
            Provider saved = new Provider(old == null ? uuid() : id, name, format, endpoint, auth, encrypted, metadata, models);
            List<Provider> providers = new ArrayList<>(state.providers());
            if (old == null) providers.add(saved); else providers.set(providers.indexOf(old), saved);
            return new State(state.revision(), providers, state.chatModelId(), state.importModelId(), state.chatReasoningEffort());
        });
    }

    public Map<String, Object> saveModels(String providerId, String modelId, Map<String, Object> body) {
        return mutate(body, state -> {
            Provider owner = modelId == null ? provider(state, providerId) : owner(state, modelId);
            List<Model> models = new ArrayList<>(owner.models());
            if (body.get("modelIds") instanceof List<?> ids && modelId == null) {
                if (ids.isEmpty() || ids.size() > 200) invalid("一次请选择 1–200 个模型");
                for (Object raw : ids) {
                    String upstreamId = text(Map.of("modelId", raw), "modelId", 240, true);
                    if (models.stream().noneMatch(m -> m.modelId().equals(upstreamId))) {
                        models.add(model(owner, null, Map.of("modelId", upstreamId)));
                    }
                }
            } else {
                Model old = modelId == null ? null : state.model(modelId);
                Model saved = model(owner, old, body);
                if (models.stream().anyMatch(m -> m.modelId().equals(saved.modelId()) && !m.id().equals(modelId))) invalid("此供应商已添加相同模型 ID");
                if (old == null) models.add(saved); else models.set(models.indexOf(old), saved);
            }
            if (models.size() > 500) invalid("单个供应商最多保存 500 个模型");
            return replace(state, owner, new Provider(owner.id(), owner.name(), owner.apiFormat(), owner.endpoint(),
                    owner.authMode(), owner.encryptedKey(), owner.keyMeta(), models));
        });
    }

    public Map<String, Object> verifyAndAdd(String providerId, Map<String, Object> body) {
        String requestId = text(body, "requestId", 80, true);
        if (!(body.get("model") instanceof Map<?, ?>)) invalid("请提供待验证模型");
        Map<String, Object> draft = mapper.convertValue(body.get("model"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        Provider provider;
        Model candidate;
        String key;
        AiConnectionUsage usage;
        ActiveTurnRegistry.Handle handle = new ActiveTurnRegistry.Handle(requestId, 0);
        synchronized (AiConnectionUsage.LOCK) {
            State state = state(); provider = provider(state, providerId);
            if (body.containsKey("connectionVersion")) {
                if (!connectionVersion(provider).equals(text(body, "connectionVersion", 64, true))) throw AiConnectionsStore.conflict();
            } else if (!(body.get("revision") instanceof Number expected) || expected.longValue() != state.revision()) {
                throw AiConnectionsStore.conflict();
            }
            String modelId = text(draft, "modelId", 240, true);
            Model existing = provider.models().stream().filter(m -> m.modelId().equals(modelId)).findFirst().orElse(null);
            if (existing != null) return Map.of("added", true, "existing", true, "modelId", existing.id(), "connections", view(state));
            if (provider.models().size() >= 500) invalid("单个供应商最多保存 500 个模型");
            candidate = model(provider, null, draft); ready(provider, candidate);
            if ("mock://local".equals(provider.endpoint())) invalid("本地演示连接不执行联网验证");
            key = provider.id() + "/" + candidate.modelId();
            if (verifyingModels.contains(key) || tests.size() >= 20) invalid("此模型正在验证，或验证任务过多，请稍后重试");
            if (tests.putIfAbsent(requestId, handle) != null) invalid("此验证请求仍在进行");
            pruneCancelledTests();
            if (cancelledTests.remove(requestId) != null) handle.requestCancel();
            verifyingModels.add(key); usage = AiConnectionUsage.acquireProvider(providerId);
        }
        try (usage) {
            var reasoning = AiReasoningSettings.read(candidate.params(), provider.apiFormat());
            int output = Math.min(((Number) candidate.params().get("maxOutputTokens")).intValue(), 4096);
            var target = new AiUpstreamClient.Upstream(provider.endpoint(), decrypt(provider, body), candidate.modelId(), provider.apiFormat(), output, false).withReasoning(reasoning);
            var verification = new AiModelVerifier(upstream, mapper).verify(target, handle);
            synchronized (AiConnectionUsage.LOCK) {
                if (handle.cancelRequested()) return Map.of("added", false, "cancelled", true, "verification", verification);
                if (!verification.text().passed()) return Map.of("added", false, "verification", verification);
                State latest = state();
                Provider current = provider(latest, providerId);
                if (!connectionVersion(current).equals(connectionVersion(provider))) throw AiConnectionsStore.conflict();
                Model existing = current.models().stream().filter(m -> m.modelId().equals(candidate.modelId())).findFirst().orElse(null);
                if (existing != null) return Map.of("added", true, "existing", true, "modelId", existing.id(), "connections", view(latest));
                if (current.models().size() >= 500) invalid("单个供应商最多保存 500 个模型");
                Map<String, Object> params = new LinkedHashMap<>(candidate.params());
                params.put("tools", verification.tools().passed());
                params.put("visionVerified", verification.image().passed());
                params.put("verification", mapper.convertValue(verification, Map.class));
                Model saved = new Model(candidate.id(), candidate.modelId(), candidate.name(), candidate.group(), params);
                List<Model> models = new ArrayList<>(current.models()); models.add(saved);
                var next = replace(latest, current, new Provider(current.id(), current.name(), current.apiFormat(), current.endpoint(),
                        current.authMode(), current.encryptedKey(), current.keyMeta(), models));
                return Map.of("added", true, "modelId", saved.id(), "verification", verification, "connections", view(store.save(next, latest.revision())));
            }
        } finally {
            synchronized (AiConnectionUsage.LOCK) { tests.remove(requestId, handle); verifyingModels.remove(key); }
        }
    }

    private Model model(Provider provider, Model old, Map<String, Object> body) {
        String id = text(body, "modelId", 240, true);
        boolean changedId = old != null && !old.modelId().equals(id);
        // ID 编辑不重置用户设置；仅旧上游身份的探测记录失效。
        Map<String, Object> params = new LinkedHashMap<>(old == null ? Map.of() : old.params());
        if (changedId) {
            params.remove("verification"); params.remove("visionVerified"); params.remove("cacheBreakpoints");
        }
        var preset = AiModelPresets.match(provider, id);
        if (old == null && preset != null) {
            params.put("presetId", preset.id()); params.put("presetVersion", AiModelPresets.version());
            if (preset.contextWindow() != null) params.put("contextWindow", preset.contextWindow());
            if (preset.maxOutputTokens() != null) params.put("maxOutputTokens", preset.maxOutputTokens());
            params.put("reasoningControl", preset.reasoningControl()); params.put("reasoningEfforts", preset.reasoningEfforts());
        }
        if (body.containsKey("presetId")) {
            var reference = AiModelPresets.match(provider, text(body, "presetId", 240, true));
            if (reference == null) invalid("所选预设不可用，请重新选择");
            params.put("presetId", reference.id()); params.put("presetVersion", AiModelPresets.version());
        }
        params.put("streaming", bool(body, "streaming", !Boolean.FALSE.equals(params.get("streaming"))));
        params.put("tools", bool(body, "tools", params.get("tools") instanceof Boolean flag ? flag : ProviderCapabilities.of(provider.endpoint(), provider.apiFormat(), id).tools()));
        params.remove("maxInputTokens");
        for (String field : List.of("contextWindow", "maxOutputTokens")) {
            if (!body.containsKey(field)) continue;
            Object value = body.get(field);
            if (value == null || "".equals(value)) params.remove(field);
            else {
                if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                        || number.longValue() < 1 || number.longValue() > Integer.MAX_VALUE) invalid("Token 上限须为 1–2147483647 的整数");
                params.put(field, ((Number) value).intValue());
            }
        }
        AiModelLimits.requireConfigured(params);
        for (String field : AiReasoningSettings.FIELDS) if (body.containsKey(field)) params.put(field, body.get(field));
        AiReasoningSettings reasoning = AiReasoningSettings.read(params, provider.apiFormat());
        reasoning.putInto(params);
        if (reasoning.budget() != null && params.get("maxOutputTokens") instanceof Number limit && reasoning.budget() >= limit.intValue()) invalid("思考预算须小于最大输出 Token");
        String name = body.containsKey("name") ? text(body, "name", 80, false) : old != null ? old.name() : preset == null ? "" : preset.name();
        String group = body.containsKey("group") ? text(body, "group", 40, false) : old == null ? "" : old.group();
        return new Model(old == null ? uuid() : old.id(), id, name, group, params);
    }

    public Map<String, Object> bind(Map<String, Object> body) {
        return mutate(body, state -> {
            String purpose = text(body, "purpose", 10, true);
            if (!"import".equals(purpose)) invalid("设置中仅可选择导入模型，对话模型请在 AI 助手中选择");
            String id = body.get("modelId") == null ? null : text(body, "modelId", 80, true);
            if (id != null) ready(owner(state, id), state.model(id));
            return new State(state.revision(), state.providers(), state.chatModelId(), id, state.chatReasoningEffort());
        });
    }

    /** 助手输入区改选只写当前会话，并更新新会话的继承默认；已受理任务保留配置快照。 */
    public Map<String, Object> selectChatModel(Map<String, Object> body) {
        return mutate(body, state -> {
            String id = body.containsKey("modelConfigId") ? text(body, "modelConfigId", 80, true) : state.chatModelId();
            if (id == null) invalid("请在 AI 助手中选择模型");
            ready(owner(state, id), state.model(id));
            String effort = body.containsKey("reasoningEffort") ? text(body, "reasoningEffort", 20, false) : state.chatReasoningEffort();
            if (effort != null && effort.isBlank()) effort = null;
            if (effort != null && !supportsEffort(state, id, effort)) {
                if (body.containsKey("reasoningEffort")) invalid("该模型不支持所选思考档位");
                effort = null;
            }
            return new State(state.revision(), state.providers(), id, state.importModelId(), effort);
        }, true);
    }

    private static boolean supportsEffort(State state, String id, String effort) {
        if (id == null || state.model(id) == null) return false;
        try {
            var reasoning = AiReasoningSettings.read(state.model(id).params(), state.owner(id).apiFormat());
            return "effort".equals(reasoning.control()) && reasoning.efforts().contains(effort);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public Map<String, Object> delete(String id, boolean isProvider, Map<String, Object> body) {
        return mutate(body, state -> {
            Provider owner = isProvider ? provider(state, id) : owner(state, id);
            if (isProvider && AiConnectionUsage.providerActive(id)) throw new ChatTurnRejectException(409, "CONFIG_BUSY", "仍有任务使用此配置，完成后再删除", Map.of());
            List<Model> removed = isProvider ? owner.models() : List.of(state.model(id));
            for (Model model : removed) {
                if (model.id().equals(state.chatModelId()) || model.id().equals(state.importModelId())) {
                    throw new ChatTurnRejectException(409, "CONFIG_IN_USE", "此配置仍被使用，请在 AI 助手中切换对话模型，或更换导入模型", Map.of());
                }
                if (AiConnectionUsage.active(model.id())) {
                    throw new ChatTurnRejectException(409, "CONFIG_BUSY", "仍有任务使用此配置，完成后再删除", Map.of());
                }
            }
            if (isProvider) return new State(state.revision(), state.providers().stream().filter(p -> !p.id().equals(id)).toList(), state.chatModelId(), state.importModelId(), state.chatReasoningEffort());
            return replace(state, owner, new Provider(owner.id(), owner.name(), owner.apiFormat(), owner.endpoint(), owner.authMode(),
                    owner.encryptedKey(), owner.keyMeta(), owner.models().stream().filter(m -> !m.id().equals(id)).toList()));
        });
    }

    public List<String> discover(String providerId, Map<String, Object> body) {
        Provider provider;
        AiConnectionUsage usage;
        synchronized (AiConnectionUsage.LOCK) {
            provider = provider(state(), providerId);
            usage = AiConnectionUsage.acquireProvider(providerId);
        }
        try (usage) {
            ready(provider, null);
            if ("mock://local".equals(provider.endpoint())) invalid("本地演示连接请手动添加模型");
            return upstream.listModels(new AiUpstreamClient.Upstream(provider.endpoint(), decrypt(provider, body), "", provider.apiFormat(), 256, true));
        }
    }

    public Map<String, Object> test(String modelId, Map<String, Object> body) {
        AiConfigRepository.ConfigRow config;
        Provider provider;
        AiConnectionUsage usage;
        synchronized (AiConnectionUsage.LOCK) {
            State state = state();
            provider = owner(state, modelId);
            ready(provider, state.model(modelId));
            config = store.resolve(new State(state.revision(), state.providers(), modelId, null), "chat");
            usage = AiConnectionUsage.acquire(config);
        }
        try (usage) {
            String requestId = body.get("requestId") == null ? uuid() : text(body, "requestId", 80, true);
            ActiveTurnRegistry.Handle handle = new ActiveTurnRegistry.Handle(requestId, 0);
            synchronized (AiConnectionUsage.LOCK) {
                if (tests.putIfAbsent(requestId, handle) != null) throw new ChatTurnRejectException(409, "TEST_BUSY", "此测试仍在运行", Map.of());
                pruneCancelledTests();
                if (cancelledTests.remove(requestId) != null) handle.requestCancel();
            }
            try {
                String prompt = text(body, "prompt", 2000, true);
                List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));
                AiReasoningSettings reasoning = AiReasoningSettings.from(config);
                int output = AiModelLimits.output(config, reasoning.specified() ? 4096 : 256);
                AiModelLimits.validate(config, messages, List.of(), output);
                long start = System.currentTimeMillis();
                if ("mock://local".equals(provider.endpoint())) return Map.of("reply", "pong（本地演示）", "elapsedMs", 0);
                var target = new AiUpstreamClient.Upstream(provider.endpoint(), decrypt(provider, body), config.model(), provider.apiFormat(), output, false).withReasoning(reasoning);
                var result = upstream.call(target, messages, false, null, 30, handle);
                if (handle.cancelRequested()) invalid("测试已取消");
                if (blank(result.text())) invalid("服务未返回正文，请检查模型 ID 或重试");
                return Map.of("reply", result.text(), "elapsedMs", System.currentTimeMillis() - start);
            } finally { tests.remove(requestId, handle); }
        }
    }

    public Map<String, Object> cancelTest(String id) {
        if (id == null || id.length() > 80) invalid("无效的验证请求 ID");
        synchronized (AiConnectionUsage.LOCK) {
            ActiveTurnRegistry.Handle handle = tests.get(id);
            if (handle != null) handle.requestCancel();
            else { pruneCancelledTests(); cancelledTests.put(id, System.currentTimeMillis()); }
            return Map.of("cancelled", true);
        }
    }

    private void pruneCancelledTests() {
        long cutoff = System.currentTimeMillis() - 120_000;
        cancelledTests.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        while (cancelledTests.size() >= 128) cancelledTests.remove(cancelledTests.keySet().iterator().next());
    }

    private String decrypt(Provider provider, Map<String, Object> body) {
        if ("none".equals(provider.authMode())) return "";
        try {
            var meta = mapper.readTree(provider.keyMeta());
            String material = "password".equals(meta.path("mode").asText())
                    ? text(body, "masterPassword", 8192, true) : keys.fingerprintMaterial();
            return keys.decrypt(provider.encryptedKey(), meta.path("salt").asText(), meta.path("iv").asText(), material);
        } catch (Exception error) { throw new IllegalArgumentException("密钥无法解密，请提供主密码或更换此连接密钥"); }
    }

    private Map<String, Object> mutate(Map<String, Object> body, UnaryOperator<State> update) {
        return mutate(body, update, false);
    }

    private Map<String, Object> mutate(Map<String, Object> body, UnaryOperator<State> update, boolean selectChat) {
        synchronized (AiConnectionUsage.LOCK) {
            State state = state();
            if (!(body.get("revision") instanceof Number revision) || revision.longValue() != state.revision()) throw AiConnectionsStore.conflict();
            State next = update.apply(state);
            if (next.chatReasoningEffort() != null && !supportsEffort(next, next.chatModelId(), next.chatReasoningEffort())) {
                next = new State(next.revision(), next.providers(), next.chatModelId(), next.importModelId(), null);
            }
            var target = store.resolve(next, "chat");
            String fingerprint = memoryFingerprint(target, MemoryPolicyService.PURPOSE_USE);
            // 显式选择可修复旧版本留下的过期授权；修改当前连接的地址/协议也要同步。
            if (fingerprint != null && (selectChat || !fingerprint.equals(
                    memoryFingerprint(store.resolve(state, "chat"), MemoryPolicyService.PURPOSE_USE)))) {
                return saveSelection(next, state, body, selectChat, fingerprint,
                        memoryFingerprint(target, MemoryPolicyService.PURPOSE_MAINTENANCE));
            }
            if (selectChat) return saveSelection(next, state, body, true, fingerprint,
                    memoryFingerprint(target, MemoryPolicyService.PURPOSE_MAINTENANCE));
            return view(store.save(next, state.revision()));
        }
    }

    private Map<String, Object> saveSelection(State next, State previous, Map<String, Object> body,
                                               boolean selectChat, String useFingerprint, String maintenanceFingerprint) {
        Long sessionId = null;
        if (selectChat && body.get("sessionId") != null) {
            if (!(body.get("sessionId") instanceof Number id) || id.longValue() <= 0 || id.doubleValue() != id.longValue()) {
                invalid("会话标识无效");
            }
            sessionId = ((Number) body.get("sessionId")).longValue();
        }
        var result = view(store.saveWithMemoryConsent(next, previous.revision(), useFingerprint, maintenanceFingerprint, sessionId));
        if (sessionId != null) result.put("sessionChoice", store.sessionChoice(sessionId));
        return result;
    }

    private static String memoryFingerprint(AiConfigRepository.ConfigRow config, String purpose) {
        if (config == null || blank(config.endpoint()) || blank(config.model())
                || (!AiModelLimits.noKey(config) && blank(config.encryptedKey()))) return null;
        try {
            return MemoryPolicyService.consentFingerprint(purpose, EmbeddingSpaceContracts.normalizeEndpoint(config.endpoint()),
                    AiService.apiFormatOf(config), config.model());
        } catch (IllegalArgumentException error) {
            // 本地演示和未完成的旧配置没有实际外发目标。
            return null;
        }
    }

    private String connectionVersion(Provider provider) {
        // 密文自带随机盐；摘要仅用作配置一致性标记，不承担授权，不暴露密钥或密文。
        String snapshot = json(java.util.Arrays.asList(provider.id(), provider.endpoint(), provider.apiFormat(),
                provider.authMode(), provider.encryptedKey(), provider.keyMeta()));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private Map<String, Object> view(State state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", state.revision());
        result.put("chatModelId", state.chatModelId());
        result.put("chatReasoningEffort", state.chatReasoningEffort());
        result.put("importModelId", state.importModelId());
        result.put("providers", state.providers().stream().map(p -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", p.id()); view.put("name", p.name()); view.put("endpoint", p.endpoint());
            view.put("apiFormat", p.apiFormat()); view.put("authMode", p.authMode());
            view.put("connectionVersion", connectionVersion(p));
            view.put("hasKey", !blank(p.encryptedKey()));
            view.put("needsPassword", p.keyMeta() != null && p.keyMeta().contains("\"password\""));
            view.put("models", p.models().stream().map(m -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", m.id()); item.put("modelId", m.modelId()); item.put("name", m.name()); item.put("group", m.group());
                for (String key : List.of("streaming", "tools", "contextWindow", "maxOutputTokens")) item.put(key, m.params().get(key));
                item.put("nativeWeb", com.drillnotebook.app.service.web.NativeWebCapabilities.of(p.endpoint(), p.apiFormat(), m.modelId()));
                item.put("reasoningControl", m.params().getOrDefault("reasoningControl", "effort"));
                item.put("reasoningEfforts", m.params().getOrDefault("reasoningEfforts", List.of()));
                item.put("defaultReasoningEffort", m.params().get("defaultReasoningEffort"));
                item.put("thinkingBudgetTokens", m.params().get("thinkingBudgetTokens"));
                item.put("preset", AiModelPresets.match(p, m.params().get("presetId") instanceof String reference ? reference : m.modelId()));
                item.put("presetVersion", m.params().get("presetVersion"));
                item.put("visionVerified", m.params().get("visionVerified"));
                item.put("verification", m.params().get("verification"));
                return item;
            }).toList());
            return view;
        }).toList());
        return result;
    }
    private static State replace(State state, Provider old, Provider value) {
        return new State(state.revision(), state.providers().stream().map(p -> p.id().equals(old.id()) ? value : p).toList(), state.chatModelId(), state.importModelId(), state.chatReasoningEffort());
    }
    private static Provider provider(State state, String id) {
        Provider provider = state.provider(id);
        if (provider == null) throw new ChatTurnRejectException(404, "CONFIG_NOT_FOUND", "供应商已不存在，请重载", Map.of());
        return provider;
    }
    private static Provider owner(State state, String id) {
        Provider provider = state.owner(id);
        if (provider == null) throw new ChatTurnRejectException(404, "CONFIG_NOT_FOUND", "模型已不存在，请重载", Map.of());
        return provider;
    }
    private static void ready(Provider provider, Model model) {
        if (blank(provider.endpoint()) || ("api_key".equals(provider.authMode()) && blank(provider.encryptedKey())) || (model != null && blank(model.modelId()))) {
            invalid("此配置待补全，请检查连接地址、密钥和模型 ID");
        }
        if (model != null) AiModelLimits.requireConfigured(model.params());
    }
    private static String text(Map<String, Object> body, String key, int limit, boolean required) {
        String value = body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
        if (value.length() > limit || (required && value.isBlank())) invalid(key + " 必填且不能超过 " + limit + " 字");
        return value;
    }
    private static boolean bool(Map<String, Object> body, String key, boolean fallback) {
        if (!body.containsKey(key)) return fallback;
        if (!(body.get(key) instanceof Boolean)) invalid(key + " 必须为开或关");
        return Boolean.TRUE.equals(body.get(key));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("配置序列化失败", error); }
    }
}
