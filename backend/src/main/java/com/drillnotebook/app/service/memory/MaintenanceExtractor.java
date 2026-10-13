package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.EmbeddingSpaceContracts;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.AttemptFailure;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.ExtractionResult;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 真实提取端口（执行方案 §14 P7、§8.2 第 4-7 条；contracts §5 的输入结构与提示词）。
 *
 * <p>本类只做三件事：把<b>本批用户亲述块</b>与<b>最多 8 条相关既有事实</b>编成 §5 的输入 JSON、
 * 用固定提示词打一次维护模型、把返回交给 {@link MemoryValidator}。它<b>不写库</b>，
 * 也不判定冲突——那是 worker 与 {@link MemoryConflictResolver} 在同一笔事务里做的事（§7）。</p>
 *
 * <p>外发授权已在 worker 门禁里查过（含"后台只能自解 fingerprint 模式的 Key"，§5.1）；这里的任务行指纹与
 * 实际解析出的目标再核一次，中途换模型就当场停下，不"偷偷回退到另一家"。</p>
 */
@Service
public class MaintenanceExtractor implements MaintenancePorts.Extractor {
    /** 既有事实的有界扫描规模：只取相关的前几条，绝不把全库交给模型（§8.2 第 5 条）。 */
    static final int KNOWN_FACT_SCAN_ROWS = 200;
    static final int MAX_KNOWN_FACTS = 8;
    /** 展示给模型的事实值上限：超长只截断展示，落库用的仍是校验过的原值。 */
    private static final int MAX_SHOWN_VALUE_CODE_POINTS = 160;

    private static final Logger log = LoggerFactory.getLogger(MaintenanceExtractor.class);

    private final AiUpstreamClient upstream;
    private final MemoryPolicyService policy;
    private final AiConfigRepository configs;
    private final ApiKeyEncryptor keys;
    private final MemoryRepository memory;
    private final MemoryCryptoService crypto;
    private final TokenEstimator estimator;
    private final ObjectMapper mapper;

    /**
     * {@code AiUpstreamClient} 不是 Spring bean（{@code AiService} 自己懒建一个），维护侧照同一个口径自建：
     * 共享连接池由 JDK 的 HttpClient 自己管，多一个实例不额外占端口，也不引入新的 bean 依赖边。
     */
    @Autowired
    public MaintenanceExtractor(MemoryPolicyService policy, AiConfigRepository configs, ApiKeyEncryptor keys,
                                MemoryRepository memory, MemoryCryptoService crypto, TokenEstimator estimator,
                                ObjectMapper mapper) {
        this(new AiUpstreamClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), mapper),
                policy, configs, keys, memory, crypto, estimator, mapper);
    }

    MaintenanceExtractor(AiUpstreamClient upstream, MemoryPolicyService policy, AiConfigRepository configs,
                        ApiKeyEncryptor keys, MemoryRepository memory, MemoryCryptoService crypto,
                        TokenEstimator estimator, ObjectMapper mapper) {
        this.upstream = upstream;
        this.policy = policy;
        this.configs = configs;
        this.keys = keys;
        this.memory = memory;
        this.crypto = crypto;
        this.estimator = estimator;
        this.mapper = mapper;
    }

    @Override
    public String promptVersion() {
        return MemoryValidator.PROMPT_VERSION;
    }

    @Override
    public int framingTokens() {
        return estimator.estimateText(MemoryValidator.SYSTEM_PROMPT)
                + estimator.estimateText(MemoryValidator.OUTPUT_SHAPE);
    }

    @Override
    public ExtractionResult extract(Batch batch, MemoryCryptoService.MemoryKey key, String repairOf)
            throws AttemptFailure {
        int template = framingTokens();
        if (template >= MaintenanceRules.INPUT_TOKEN_BUDGET) {
            // §5：固定模板单独就把预算吃光了，这是配置问题，让模型"压缩提示词"没有意义
            throw new AttemptFailure("MAINTENANCE_TEMPLATE_TOO_LARGE", "维护提示词模板超出输入预算", false);
        }
        // §5 的打包顺序：先保提示词与至少一条完整来源，再用剩余空间装既有事实；装不下就丢事实不丢来源
        List<Map<String, Object>> facts = new ArrayList<>(knownFacts(batch, key));
        String input = encodeInput(batch, facts, repairOf);
        while (!facts.isEmpty() && estimator.estimateText(input) > MaintenanceRules.INPUT_TOKEN_BUDGET) {
            facts.remove(facts.size() - 1);
            input = encodeInput(batch, facts, repairOf);
        }
        AiUpstreamClient.Result result = dispatch(input);
        Integer inputTokens = result.usage() == null ? null : result.usage().inputTokens();
        Integer outputTokens = result.usage() == null ? null : result.usage().outputTokens();
        MaintenancePorts.Usage usage = new MaintenancePorts.Usage(
                inputTokens == null ? null : inputTokens.longValue(),
                outputTokens == null ? null : outputTokens.longValue());
        Map<String, Integer> shownTargets = targetsOf(facts);
        MemoryValidator.Outcome outcome = MemoryValidator.validate(result.text(), batch, shownTargets, mapper);
        if (!outcome.formatOk()) {
            return ExtractionResult.formatFailure(outcome.format(), usage, shownTargets, result.text());
        }
        return new ExtractionResult(outcome.format(), outcome.candidates(), outcome.rejections(), usage,
                shownTargets, result.text());
    }

    /** 交给模型的既有事实集合直接反推出"合法目标表"：伪造 ID 与陈旧版本都在这份集合外。 */
    private static Map<String, Integer> targetsOf(List<Map<String, Object>> facts) {
        Map<String, Integer> targets = new LinkedHashMap<>();
        for (Map<String, Object> fact : facts) {
            targets.put(String.valueOf(fact.get("memoryId")), (Integer) fact.get("version"));
        }
        return Map.copyOf(targets);
    }

    // ---- 输入编码 ----

    /** §5 的输入结构：scope、时区、谓词词表、用户亲述块与既有事实；一次修复只多带上一次的坏返回。 */
    private String encodeInput(Batch batch, List<Map<String, Object>> knownFacts, String repairOf) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", batch.scopeType());
        scope.put("id", batch.scopeId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("scope", scope);
        payload.put("sourceTimezone", batch.timezone());
        payload.put("allowedPredicates", MemoryValidator.ALLOWED_PREDICATES);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SourceTurn turn : batch.turns()) {
            for (MaintenancePorts.SourceBlock block : turn.blocks()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", turn.userMessageId());
                entry.put("turnId", turn.turnId());
                entry.put("blockId", block.blockId());
                entry.put("origin", "USER_AUTHORED");
                entry.put("createdAt", blockCreatedAt(turn));
                entry.put("text", block.text());
                messages.add(entry);
            }
        }
        payload.put("userMessages", messages);
        payload.put("existingFacts", knownFacts);
        try {
            String json = mapper.writeValueAsString(payload);
            return repairOf == null ? json : json + "\n\n上一次返回不是合法结构。只把它改写成指定结构，"
                    + "不得新增、不得改写内容，无法确定的字段一律 needsReview=true：\n<untrusted>\n" + repairOf
                    + "\n</untrusted>";
        } catch (Exception error) {
            throw new IllegalStateException("提取输入无法编码", error);
        }
    }

    private static String blockCreatedAt(SourceTurn turn) {
        return turn.sourceCreatedAt() == null ? MemoryTimes.formatMillis(turn.finishedMillis())
                : turn.sourceCreatedAt();
    }

    /** 既有相关事实：按词法重合度取前若干条，只给 ID/版本/kind/predicate/value/manualProtected（§8.2 第 5 条）。 */
    private List<Map<String, Object>> knownFacts(Batch batch, MemoryCryptoService.MemoryKey key) {
        Set<String> query = Set.copyOf(MemoryLexicon.tokens(batchText(batch)));
        List<Candidate> scored = new ArrayList<>();
        for (MemoryRepository.FactRow row : scanActive(key, batch)) {
            Map<String, Object> payload = payloadOf(key, row);
            if (payload == null) {
                continue;
            }
            String predicate = text(payload.get("predicate"));
            String value = text(payload.get("value"));
            int overlap = 0;
            for (String token : MemoryLexicon.tokens(predicate + " " + value)) {
                if (query.contains(token)) {
                    overlap++;
                }
            }
            if (overlap == 0) {
                continue;
            }
            scored.add(new Candidate(overlap, row, predicate, value, text(payload.get("kind"))));
        }
        // 重合度高的先看，同分时新记录先看；上限就是 MAX_KNOWN_FACTS，不发全库
        scored.sort((left, right) -> left.overlap != right.overlap
                ? Integer.compare(right.overlap, left.overlap)
                : right.row.recordedAt().compareTo(left.row.recordedAt()));
        List<Map<String, Object>> facts = new ArrayList<>();
        Set<String> seenMemoryIds = new HashSet<>();
        for (Candidate item : scored) {
            if (facts.size() >= MAX_KNOWN_FACTS || !seenMemoryIds.add(item.row.memoryId())) {
                continue;
            }
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("memoryId", item.row.memoryId());
            fact.put("version", item.row.version());
            fact.put("kind", item.kind.isBlank() ? item.row.kind() : item.kind);
            fact.put("predicate", item.predicate);
            fact.put("value", clip(item.value));
            fact.put("manualProtected", item.row.manualProtected());
            facts.add(fact);
        }
        return facts;
    }

    /** GLOBAL 与笔记本作用域都要看：注入侧一直是两者合并，消歧侧也必须一致（§5.2）。 */
    private List<MemoryRepository.FactRow> scanActive(MemoryCryptoService.MemoryKey key, Batch batch) {
        List<MemoryRepository.FactRow> rows = new ArrayList<>();
        try {
            rows.addAll(memory.findPage(key.vaultId(), MemoryRepository.STATUS_ACTIVE, "GLOBAL", 0L, null, null,
                    KNOWN_FACT_SCAN_ROWS));
            if (!"GLOBAL".equals(batch.scopeType()) && batch.scopeId() > 0) {
                rows.addAll(memory.findPage(key.vaultId(), MemoryRepository.STATUS_ACTIVE, batch.scopeType(),
                        batch.scopeId(), null, null, KNOWN_FACT_SCAN_ROWS));
            }
        } catch (Exception error) {
            log.warn("维护提取读既有事实失败，按无既有事实继续: {}", error.toString());
        }
        return rows;
    }

    private Map<String, Object> payloadOf(MemoryCryptoService.MemoryKey key, MemoryRepository.FactRow row) {
        try {
            return mapper.readValue(crypto.decrypt(key, "ai_memory_item", row.rowId(), row.version(),
                    row.payloadCipher(), row.cryptoMeta()), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            // 单行载荷坏了就当它不在场：不能把乱码送进模型，也不能因为一行坏了放弃整批消歧
            return null;
        }
    }

    private static String batchText(Batch batch) {
        StringBuilder text = new StringBuilder();
        for (SourceTurn turn : batch.turns()) {
            for (MaintenancePorts.SourceBlock block : turn.blocks()) {
                text.append(block.text()).append(' ');
            }
        }
        return text.toString();
    }

    /** 一次带外发目标的调用：目标在中途被换掉就停在这里，不会拿着旧指纹去打新模型。 */
    private AiUpstreamClient.Result dispatch(String input) throws AttemptFailure {
        MemoryPolicyService.Target target = policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE);
        if (!target.configured()) {
            throw new AttemptFailure("TARGET_MISSING", "维护模型未配置", false);
        }
        AiConfigRepository.ConfigRow row = configs.find(target.slot());
        if (row == null || row.encryptedKey() == null || row.encryptedKey().isBlank()) {
            throw new AttemptFailure("TARGET_KEY_MISSING", "维护模型缺少 API Key", false);
        }
        String endpoint = EmbeddingSpaceContracts.normalizeEndpoint(row.endpoint());
        String apiFormat = row.provider() == null ? AiUpstreamClient.FORMAT_CHAT_COMPLETIONS
                : mapFormat(row.provider());
        if (!target.fingerprint().equals(policy.consentFingerprint(MemoryPolicyService.PURPOSE_MAINTENANCE, endpoint,
                apiFormat, row.model()))) {
            throw new AttemptFailure("TARGET_CHANGED", "维护目标在本次运行中被更换", false);
        }
        String apiKey = headlessKey(row);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", MemoryValidator.SYSTEM_PROMPT),
                Map.of("role", "user", "content", input));
        AiUpstreamClient.Upstream upstreamTarget = new AiUpstreamClient.Upstream(endpoint, apiKey, row.model(),
                apiFormat, MaintenanceRules.OUTPUT_TOKEN_BUDGET, true);
        try {
            return upstream.call(upstreamTarget, messages, false, null,
                    (int) Math.max(1, MaintenanceRules.CALL_TIMEOUT_MILLIS / 1_000L));
        } catch (Exception error) {
            // 网络/网关的失败形态太多：统一按可重试交给 job 的三次上限，绝不在这里自己循环
            throw new AttemptFailure("MAINTENANCE_UPSTREAM_ERROR", "维护模型调用失败", true, error);
        }
    }

    /**
     * 后台没有用户输入，只有机器指纹模式的 Key 能自解（§5.1：拿不到就停，不回退到别的模型）。
     * password 模式由 worker 的门禁提前挡成等待态，走到这里说明配置中途被换过。
     */
    private String headlessKey(AiConfigRepository.ConfigRow row) throws AttemptFailure {
        try {
            Map<String, Object> meta = mapper.readValue(row.keyMeta(), new TypeReference<>() {
            });
            if ("password".equals(String.valueOf(meta.getOrDefault("mode", "fingerprint")))) {
                throw new AttemptFailure("TARGET_KEY_NEEDS_PASSWORD", "维护模型 Key 需要主密码，后台不代解", false);
            }
            return keys.decrypt(row.encryptedKey(), String.valueOf(meta.get("salt")), String.valueOf(meta.get("iv")),
                    keys.fingerprintMaterial());
        } catch (AttemptFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new AttemptFailure("TARGET_KEY_UNREADABLE", "维护模型 Key 无法解密", false, error);
        }
    }

    private static String mapFormat(String provider) {
        String lowered = provider.toLowerCase(Locale.ROOT);
        return lowered.contains("anthropic") || lowered.contains("claude") ? AiUpstreamClient.FORMAT_ANTHROPIC
                : AiUpstreamClient.FORMAT_CHAT_COMPLETIONS;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String clip(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_SHOWN_VALUE_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_SHOWN_VALUE_CODE_POINTS));
    }

    /** 打分用的中间结构：overlap 只用于排序，不参与"能不能写"的判断。 */
    private record Candidate(int overlap, MemoryRepository.FactRow row, String predicate, String value, String kind) {
    }
}
