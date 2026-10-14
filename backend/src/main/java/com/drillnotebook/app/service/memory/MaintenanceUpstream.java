package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.service.AiUpstreamClient;
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
        AiConfigRepository.ConfigRow row = targetRow();
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", input));
        AiUpstreamClient.Upstream target = new AiUpstreamClient.Upstream(
                EmbeddingSpaceContracts.normalizeEndpoint(row.endpoint()), headlessKey(row), row.model(),
                mapFormat(row.provider()), MaintenanceRules.OUTPUT_TOKEN_BUDGET, true);
        try {
            return client.call(target, messages, false, null,
                    (int) Math.max(1, MaintenanceRules.CALL_TIMEOUT_MILLIS / 1_000L));
        } catch (Exception error) {
            // 网络/网关的失败形态太多：统一按可重试交给 job 的三次上限，绝不在这里自己循环
            throw new MaintenancePorts.AttemptFailure("MAINTENANCE_UPSTREAM_ERROR", "维护模型调用失败", true, error);
        }
    }

    /** 目标解析 + 指纹复验：授权之后换过模型、端点或 Key，都在这一步判成"目标变了"。 */
    private AiConfigRepository.ConfigRow targetRow() throws MaintenancePorts.AttemptFailure {
        MemoryPolicyService.Target target = policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE);
        if (!target.configured()) {
            throw new MaintenancePorts.AttemptFailure("TARGET_MISSING", "维护模型未配置", false);
        }
        AiConfigRepository.ConfigRow row = configs.find(target.slot());
        if (row == null || row.encryptedKey() == null || row.encryptedKey().isBlank()) {
            throw new MaintenancePorts.AttemptFailure("TARGET_KEY_MISSING", "维护模型缺少 API Key", false);
        }
        String endpoint = EmbeddingSpaceContracts.normalizeEndpoint(row.endpoint());
        String apiFormat = mapFormat(row.provider());
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

    private static String mapFormat(String provider) {
        String lowered = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        return lowered.contains("anthropic") || lowered.contains("claude") ? AiUpstreamClient.FORMAT_ANTHROPIC
                : AiUpstreamClient.FORMAT_CHAT_COMPLETIONS;
    }
}
