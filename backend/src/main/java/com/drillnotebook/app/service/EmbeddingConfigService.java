package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent embedding configuration slot ({@code purpose=embedding}).
 *
 * <p>Frozen consent contract (Task 12): the local provider needs no key or
 * consent; OpenAI/Ollama are always remote (even on localhost). Consent
 * authorizes uploading all current and future NOTEBOOK chunks to exactly
 * {@code provider + normalizedEndpoint + model}
 * ({@code consentFingerprint = SHA-256(provider\nendpoint\nmodel)}); changing
 * any of these invalidates consent, the config is saved {@code enabled=false}
 * with code {@code CONSENT_REQUIRED}, and no probe/index request is ever sent.
 * A consented save creates the selected REBUILDING space and queues existing
 * chunks; the space only becomes ACTIVE at 100% coverage.
 */
@Service
public class EmbeddingConfigService {

    static final String PROBE_TEXT = "drill-notebook embedding connectivity probe";
    private static final List<String> PROVIDERS = List.of("disabled", "local", "openai", "ollama");
    private static final int DEFAULT_BATCH_SIZE = 16;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfigService.class);

    private final AiConfigRepository configs;
    private final ApiKeyEncryptor encryptor;
    private final ObjectMapper mapper;
    private final EmbeddingProviderRegistry providers;
    private final EmbeddingModelRepository models;
    private final EmbeddingJobRepository jobs;
    private final EmbeddingJobExecutor executor;
    private final RetrievalMaintenanceService maintenance;
    private final TransactionTemplate tx;

    public EmbeddingConfigService(
            AiConfigRepository configs,
            ApiKeyEncryptor encryptor,
            ObjectMapper mapper,
            EmbeddingProviderRegistry providers,
            EmbeddingModelRepository models,
            EmbeddingJobRepository jobs,
            EmbeddingJobExecutor executor,
            RetrievalMaintenanceService maintenance,
            PlatformTransactionManager txManager) {
        this.configs = configs;
        this.encryptor = encryptor;
        this.mapper = mapper;
        this.providers = providers;
        this.models = models;
        this.jobs = jobs;
        this.executor = executor;
        this.maintenance = maintenance;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Result carrying the HTTP status the controller should emit. */
    public record ApiResult(int status, Map<String, Object> body) {}

    // ── Startup ─────────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        try {
            restoreRemoteProvider();
        } catch (Exception e) {
            log.warn("恢复远程 embedding provider 失败：{}", e.getMessage());
        }
    }

    /** Re-register a consented remote provider after restart (fingerprint-mode key only). */
    void restoreRemoteProvider() {
        AiConfigRepository.ConfigRow row = configs.findEmbedding();
        if (row == null || !isRemote(row.provider())) return;
        Map<String, Object> params = parseParams(row.params());
        if (!Boolean.TRUE.equals(params.get("enabled")) || !Boolean.TRUE.equals(params.get("consent"))) return;
        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        if (selected == null || !row.provider().equals(selected.get("provider_type"))) return;
        String apiKey;
        try {
            apiKey = decryptKey(row, "");
        } catch (IllegalArgumentException e) {
            // Password-mode keys cannot be restored silently; user must re-save.
            log.info("embedding API key 需要主密码，重启后暂不注册远程 provider");
            return;
        }
        providers.setActive(buildProvider(row.provider(), row.endpoint(), row.model(),
                intParam(params, "dimensions", 0), apiKey, params));
        executor.wake();
    }

    // ── Redacted config ─────────────────────────────────────────────────────

    /** Embedding slot for GET /api/ai/config: never contains the plain key. */
    public Map<String, Object> redactedEmbedding() {
        AiConfigRepository.ConfigRow row = configs.findEmbedding();
        Map<String, Object> params = row == null ? Map.of() : parseParams(row.params());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", row == null || row.provider() == null || row.provider().isBlank()
                ? "disabled" : row.provider());
        result.put("endpoint", row == null || row.endpoint() == null ? "" : row.endpoint());
        result.put("model", row == null || row.model() == null ? "" : row.model());
        result.put("dimensions", intParam(params, "dimensions", 0));
        result.put("hasKey", row != null && row.encryptedKey() != null && !row.encryptedKey().isBlank());
        result.put("enabled", Boolean.TRUE.equals(params.get("enabled")));
        result.put("consent", Boolean.TRUE.equals(params.get("consent")));
        return result;
    }

    // ── Save ────────────────────────────────────────────────────────────────

    /**
     * Save the embedding slot. Remote saves without valid consent are stored
     * {@code enabled=false} and answered with code {@code CONSENT_REQUIRED};
     * consented remote saves select a REBUILDING space and queue backfill.
     */
    public Map<String, Object> saveConfig(Map<String, Object> body) {
        String provider = string(body, "provider", "disabled").trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("embedding provider 必须是 disabled、local、openai 或 ollama");
        }
        if (!isRemote(provider)) {
            return saveNonRemote(provider, body);
        }
        String normalizedEndpoint = EmbeddingSpaceContracts.normalizeEndpoint(string(body, "endpoint", ""));
        String model = string(body, "model", "").trim();
        if (model.isEmpty()) throw new IllegalArgumentException("embedding model 不能为空");
        int dimensions = intOf(body.get("dimensions"));
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions 必须是正整数");

        String fingerprint = EmbeddingSpaceContracts.consentFingerprint(provider, normalizedEndpoint, model);
        AiConfigRepository.ConfigRow previous = configs.findEmbedding();
        Map<String, Object> previousParams = previous == null ? Map.of() : parseParams(previous.params());
        boolean requestConsent = Boolean.TRUE.equals(body.get("remoteContentConsent"));
        boolean storedConsent = previous != null && provider.equals(previous.provider())
                && Boolean.TRUE.equals(previousParams.get("consent"))
                && fingerprint.equals(previousParams.get("consentFingerprint"));
        boolean consent = requestConsent || storedConsent;

        int batchSize = clamp(intOf(body.getOrDefault("batchSize",
                previousParams.getOrDefault("batchSize", DEFAULT_BATCH_SIZE))), 1, 256);
        int timeoutSeconds = clamp(intOf(body.getOrDefault("timeoutSeconds",
                previousParams.getOrDefault("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS))), 5, 300);

        EncryptedKey key = encryptKey(body);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("enabled", consent);
        params.put("consent", consent);
        params.put("consentFingerprint", consent ? fingerprint : "");
        params.put("dimensions", dimensions);
        params.put("batchSize", batchSize);
        params.put("timeoutSeconds", timeoutSeconds);
        configs.upsert(AiConfigRepository.PURPOSE_EMBEDDING, provider, normalizedEndpoint,
                model, key.encrypted(), key.metadata(), writeParams(params));

        if (!consent) {
            // Unconsented target: stop any embedding traffic immediately.
            stopRemoteEmbedding();
            Map<String, Object> result = redactedEmbedding();
            result.put("code", "CONSENT_REQUIRED");
            return result;
        }

        String apiKey = resolveApiKey(body, key);
        String canonicalJson = EmbeddingSpaceContracts.remoteCanonicalJson(
                provider, normalizedEndpoint, model, dimensions);
        String spaceId = EmbeddingSpaceContracts.spaceId(canonicalJson);
        Map<String, Object> previousSelected = jobs.findSelectedSpaceAnyState();
        String oldSpaceId = previousSelected != null
                && !spaceId.equals(previousSelected.get("embedding_space_id"))
                ? (String) previousSelected.get("embedding_space_id") : null;
        providers.withSpaceLock(() -> {
            tx.executeWithoutResult(status -> {
                models.deselectCurrentSpace();
                models.upsertSelectedRebuildingSpace(spaceId, canonicalJson, provider, model, dimensions);
                models.enqueueMissingJobs(spaceId, "remote-config");
                double coverage = jobs.computeCoverage("NOTEBOOK", spaceId);
                jobs.updateSpaceCoverage(spaceId, coverage);
                jobs.activateSpaceIfComplete(spaceId);
            });
            providers.setActive(buildProvider(provider, normalizedEndpoint, model, dimensions, apiKey, params));
        });
        executor.wake();
        if (oldSpaceId != null) {
            maintenance.scheduleDisabledSpaceCleanup(oldSpaceId);
        }
        return redactedEmbedding();
    }

    private Map<String, Object> saveNonRemote(String provider, Map<String, Object> body) {
        EncryptedKey key = encryptKey(body);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("enabled", "local".equals(provider));
        params.put("consent", false);
        params.put("consentFingerprint", "");
        configs.upsert(AiConfigRepository.PURPOSE_EMBEDDING, provider, "", "",
                key.encrypted(), key.metadata(), writeParams(params));
        if ("disabled".equals(provider)) {
            stopRemoteEmbedding();
            EmbeddingProvider active = providers.active();
            if (active != null && LocalEmbeddingProvider.PROVIDER_TYPE.equals(active.providerType())) {
                providers.clear();
                models.deselectCurrentSpace();
            }
        } else {
            // provider=local: activation runs through the model catalog flow;
            // only make sure a remote provider is no longer serving.
            stopRemoteEmbedding();
        }
        return redactedEmbedding();
    }

    /** Clear a remote active provider and deselect a remote selected space. */
    private void stopRemoteEmbedding() {
        EmbeddingProvider active = providers.active();
        if (active != null && isRemote(active.providerType())) {
            providers.clear();
        }
        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        if (selected != null && isRemote((String) selected.get("provider_type"))) {
            models.deselectCurrentSpace();
        }
    }

    // ── Test endpoint ───────────────────────────────────────────────────────

    /**
     * POST /api/ai/embeddings/test: probes the saved config with a fixed text.
     * Consent/config problems → 409, upstream failure → 502.
     */
    public ApiResult testEndpoint(Map<String, Object> body) {
        AiConfigRepository.ConfigRow row = configs.findEmbedding();
        String provider = row == null || row.provider() == null ? "disabled" : row.provider();
        if ("disabled".equals(provider) || row == null) {
            return conflict("NOT_CONFIGURED", "请先在设置中配置 Embedding provider");
        }
        EmbeddingProvider probe;
        if ("local".equals(provider)) {
            probe = providers.active();
            if (probe == null || !LocalEmbeddingProvider.PROVIDER_TYPE.equals(probe.providerType())) {
                return conflict("LOCAL_MODEL_INACTIVE", "本地模型尚未启用");
            }
        } else {
            Map<String, Object> params = parseParams(row.params());
            if (!Boolean.TRUE.equals(params.get("consent"))) {
                return conflict("CONSENT_REQUIRED", "远程 embedding 未授权，不能发送探针请求");
            }
            String apiKey;
            try {
                apiKey = decryptKey(row, string(body, "masterPassword", ""));
            } catch (IllegalArgumentException e) {
                return conflict("MASTER_PASSWORD_REQUIRED", e.getMessage());
            }
            probe = buildProvider(provider, row.endpoint(), row.model(),
                    intParam(params, "dimensions", 0), apiKey, params);
        }
        long start = System.currentTimeMillis();
        try {
            List<Float> vector = probe.embedQuery(PROBE_TEXT);
            long latency = System.currentTimeMillis() - start;
            return new ApiResult(200, Map.of(
                    "ok", true, "dimensions", vector.size(), "latencyMs", latency));
        } catch (EmbeddingProviderException e) {
            return new ApiResult(502, Map.of(
                    "error", e.code(), "errorCode", e.code(), "message", e.getMessage()));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static boolean isRemote(String provider) {
        return OpenAiEmbeddingProvider.PROVIDER_TYPE.equals(provider)
                || OllamaEmbeddingProvider.PROVIDER_TYPE.equals(provider);
    }

    private EmbeddingProvider buildProvider(String provider, String endpoint, String model,
                                            int dimensions, String apiKey, Map<String, Object> params) {
        int batchSize = clamp(intParam(params, "batchSize", DEFAULT_BATCH_SIZE), 1, 256);
        int timeoutSeconds = clamp(intParam(params, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS), 5, 300);
        if (OpenAiEmbeddingProvider.PROVIDER_TYPE.equals(provider)) {
            return new OpenAiEmbeddingProvider(mapper, endpoint, model, dimensions,
                    apiKey, batchSize, timeoutSeconds);
        }
        return new OllamaEmbeddingProvider(mapper, endpoint, model, dimensions,
                apiKey, batchSize, timeoutSeconds);
    }

    private record EncryptedKey(String encrypted, String metadata, String plaintext) {}

    /** Encrypt the request key like the chat/import slots do (or keep the stored one). */
    private EncryptedKey encryptKey(Map<String, Object> body) {
        String apiKey = string(body, "apiKey", "");
        if (apiKey.isBlank()) return new EncryptedKey(null, null, null);
        String masterPassword = string(body, "masterPassword", "");
        try {
            String mode = masterPassword.isBlank() ? "fingerprint" : "password";
            String material = masterPassword.isBlank() ? encryptor.fingerprintMaterial() : masterPassword;
            ApiKeyEncryptor.EncryptedValue value = encryptor.encrypt(apiKey, material, mode);
            String metadata = mapper.writeValueAsString(Map.of(
                    "salt", value.salt(), "iv", value.iv(),
                    "kdf", "Argon2id", "algorithm", "AES-256-GCM", "mode", value.mode()));
            return new EncryptedKey(value.encrypted(), metadata, apiKey);
        } catch (Exception error) {
            throw new IllegalArgumentException("API Key 加密失败");
        }
    }

    /** Plain key for the provider instance: request key, stored key, or empty. */
    private String resolveApiKey(Map<String, Object> body, EncryptedKey saved) {
        if (saved.plaintext() != null) return saved.plaintext();
        AiConfigRepository.ConfigRow row = configs.findEmbedding();
        if (row == null || row.encryptedKey() == null || row.encryptedKey().isBlank()) return "";
        return decryptKey(row, string(body, "masterPassword", ""));
    }

    private String decryptKey(AiConfigRepository.ConfigRow row, String masterPassword) {
        if (row.encryptedKey() == null || row.encryptedKey().isBlank()) return "";
        try {
            Map<String, Object> metadata = mapper.readValue(row.keyMeta(), new TypeReference<>() {});
            String mode = String.valueOf(metadata.getOrDefault("mode", "fingerprint"));
            String material = "password".equals(mode) ? masterPassword : encryptor.fingerprintMaterial();
            if (material == null || material.isBlank()) {
                throw new IllegalArgumentException("该 embedding 配置需要主密码");
            }
            return encryptor.decrypt(row.encryptedKey(),
                    String.valueOf(metadata.get("salt")), String.valueOf(metadata.get("iv")), material);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("embedding API Key 解密失败");
        }
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeParams(Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("params 序列化失败");
        }
    }

    private static ApiResult conflict(String code, String message) {
        return new ApiResult(409, Map.of(
                "error", code, "errorCode", code, "message", message));
    }

    private static String string(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value == null ? fallback : intOf(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
