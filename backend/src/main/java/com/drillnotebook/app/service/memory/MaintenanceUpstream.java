package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.AiModelLimits;
import com.drillnotebook.app.service.AiConnectionUsage;
import com.drillnotebook.app.service.AiService;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.EmbeddingSpaceContracts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 维护链唯一的外发口子（执行方案 §8.2 第 1-3 条；contracts §5.1 的目标复验）。
 *
 * <p>提取与摘要是两个端口，但<b>只能有一个</b>发往"用户当场授权过的那个目标"的出口：目标指纹在这
 * 一处复验，中途换模型就停下而不是拿着旧授权去打新模型；Key 也只有 fingerprint 模式能自解。
 * 两个端口各自拼提示词与输入，其余全走这里。</p>
 */
@Service
class MaintenanceUpstream {
    private final AiUpstreamClient client;
    private final MemoryPolicyService policy;
    private final AiConfigRepository configs;
    private final ApiKeyEncryptor keys;
    private final ObjectMapper mapper;

    /**
     * {@code AiUpstreamClient} 从来不是 Spring bean（{@code AiService} 自己懒建一个），维护侧照同一口径自建：
     * 连接池由 JDK 的 HttpClient 自己管，多一个实例不额外占端口，也不引入新的 bean 依赖边。
     */
    @Autowired
    MaintenanceUpstream(MemoryPolicyService policy, AiConfigRepository configs, ApiKeyEncryptor keys,
                        ObjectMapper mapper) {
        this(new AiUpstreamClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), mapper),
                policy, configs, keys, mapper);
    }

    MaintenanceUpstream(AiUpstreamClient client, MemoryPolicyService policy, AiConfigRepository configs,
                        ApiKeyEncryptor keys, ObjectMapper mapper) {
        this.client = client;
        this.policy = policy;
        this.configs = configs;
        this.keys = keys;
        this.mapper = mapper;
    }

    /** 一次带固定系统提示词的维护调用；返回原文与实际用量，结构判定归调用方。 */
    AiUpstreamClient.Result call(String systemPrompt, String input) throws MaintenancePorts.AttemptFailure {
        AiConfigRepository.ConfigRow row;
        AiConnectionUsage usage;
        synchronized (AiConnectionUsage.LOCK) {
            row = targetRow();
            usage = AiConnectionUsage.acquire(row);
        }
        try (usage) { return callSnapshot(systemPrompt, input, row); }
    }

    private AiUpstreamClient.Result callSnapshot(String systemPrompt, String input, AiConfigRepository.ConfigRow row) throws MaintenancePorts.AttemptFailure {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", input));
        String endpoint = EmbeddingSpaceContracts.normalizeEndpoint(row.endpoint());
        String apiFormat = AiService.apiFormatOf(row);
        int output = AiModelLimits.output(row, MaintenanceRules.OUTPUT_TOKEN_BUDGET);
        AiUpstreamClient.Upstream target = new AiUpstreamClient.Upstream(endpoint, headlessKey(row), row.model(),
                apiFormat, output, true, true);
        int timeoutSeconds = (int) Math.max(1, MaintenanceRules.CALL_TIMEOUT_MILLIS / 1_000L);
        try {
            AiModelLimits.validate(row, messages, List.of(), output);
            return client.call(target, messages, false, null, timeoutSeconds);
        } catch (Exception error) {
            if (!target.anthropic() && responseFormatRejected(error)) {
                // 兼容网关可能不认 response_format：只对这一种明确拒绝再发一次纯提示词请求
                AiUpstreamClient.Upstream plain = new AiUpstreamClient.Upstream(endpoint, target.apiKey(),
                        target.model(), apiFormat, output, true, false);
                try {
                    return client.call(plain, messages, false, null, timeoutSeconds);
                } catch (Exception retryError) {
                    throw new MaintenancePorts.AttemptFailure("MAINTENANCE_UPSTREAM_ERROR", "维护模型调用失败", true,
                            retryError);
                }
            }
            // 网络/网关的失败形态太多：统一按可重试交给 job 的三次上限，绝不在这里自己循环
            throw new MaintenancePorts.AttemptFailure("MAINTENANCE_UPSTREAM_ERROR", "维护模型调用失败", true, error);
        }
    }

    private static boolean responseFormatRejected(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String lowered = message.toLowerCase(Locale.ROOT);
        return lowered.contains("response_format") || lowered.contains("json_object") || lowered.contains("json mode");
    }

    /** 目标解析 + 指纹复验：授权之后换过模型、端点或 Key，都在这一步判成"目标变了"。 */
    private AiConfigRepository.ConfigRow targetRow() throws MaintenancePorts.AttemptFailure {
        MemoryPolicyService.Target target = policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE);
        if (!target.configured()) {
            throw new MaintenancePorts.AttemptFailure("TARGET_MISSING", "维护模型未配置", false);
        }
        AiConfigRepository.ConfigRow row = configs.find(target.slot());
        if (row == null || (!AiModelLimits.noKey(row) && (row.encryptedKey() == null || row.encryptedKey().isBlank()))) {
            throw new MaintenancePorts.AttemptFailure("TARGET_KEY_MISSING", "维护模型缺少 API Key", false);
        }
        String endpoint = EmbeddingSpaceContracts.normalizeEndpoint(row.endpoint());
        String apiFormat = AiService.apiFormatOf(row);
        if (!target.fingerprint().equals(policy.consentFingerprint(MemoryPolicyService.PURPOSE_MAINTENANCE, endpoint,
                apiFormat, row.model()))) {
            throw new MaintenancePorts.AttemptFailure("TARGET_CHANGED", "维护目标在本次运行中被更换", false);
        }
        return row;
    }

    /**
     * 后台没有用户输入，只有机器指纹模式的 Key 能自解（§5.1：拿不到就停，不回退到别的模型）。
     * password 模式由 worker 的门禁提前挡成等待态，走到这里说明配置中途被换过。
     */
    private String headlessKey(AiConfigRepository.ConfigRow row) throws MaintenancePorts.AttemptFailure {
        if (AiModelLimits.noKey(row)) return "";
        try {
            Map<String, Object> meta = mapper.readValue(row.keyMeta(), new TypeReference<>() {
            });
            if ("password".equals(String.valueOf(meta.getOrDefault("mode", "fingerprint")))) {
                throw new MaintenancePorts.AttemptFailure("TARGET_KEY_NEEDS_PASSWORD", "维护模型 Key 需要主密码，后台不代解",
                        false);
            }
            return keys.decrypt(row.encryptedKey(), String.valueOf(meta.get("salt")), String.valueOf(meta.get("iv")),
                    keys.fingerprintMaterial());
        } catch (MaintenancePorts.AttemptFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new MaintenancePorts.AttemptFailure("TARGET_KEY_UNREADABLE", "维护模型 Key 无法解密", false, error);
        }
    }

}
