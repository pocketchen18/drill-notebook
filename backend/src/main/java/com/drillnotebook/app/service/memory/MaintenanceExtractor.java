package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.AttemptFailure;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.ExtractionResult;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceBlock;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    /**
     * 用户明确要求记住时，不再把“写不写”交给模型碰运气：命中这些词就兜底落一条。
     * 顺序从长到短，避免“请记住”先被“记住”拆掉后留下一个孤零零的“请”。
     */
    private static final List<String> MEMORY_INTENT_MARKERS = List.of(
            "请记住", "帮我记住", "让你记住", "加入记忆", "记到记忆", "存到记忆",
            "记住", "记一下", "记下来", "别忘了", "不要忘记");

    private static final Logger log = LoggerFactory.getLogger(MaintenanceExtractor.class);

    private final MaintenanceUpstream upstream;
    private final MemoryRepository memory;
    private final MemoryCryptoService crypto;
    private final TokenEstimator estimator;
    private final ObjectMapper mapper;

    @Autowired
    public MaintenanceExtractor(MaintenanceUpstream upstream, MemoryRepository memory, MemoryCryptoService crypto,
                                TokenEstimator estimator, ObjectMapper mapper) {
        this.upstream = upstream;
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
        AiUpstreamClient.Usage observed = result.usage();
        MaintenancePorts.Usage usage = new MaintenancePorts.Usage(
                observed == null || observed.inputTokens() == null ? null : observed.inputTokens().longValue(),
                observed == null || observed.outputTokens() == null ? null : observed.outputTokens().longValue(),
                observed == null || observed.cacheReadTokens() == null ? null : observed.cacheReadTokens().longValue(),
                observed == null || observed.cacheWriteTokens() == null ? null : observed.cacheWriteTokens().longValue(),
                observed != null && observed.cacheReadIncludedInInput());
        Map<String, Integer> shownTargets = targetsOf(facts);
        String raw = result.text();
        MemoryValidator.Outcome outcome = MemoryValidator.validate(raw, batch, shownTargets, mapper);
        if (!outcome.formatOk() && result.reasoning() != null && !result.reasoning().isBlank()) {
            MemoryValidator.Outcome fromReasoning =
                    MemoryValidator.validate(result.reasoning(), batch, shownTargets, mapper);
            if (fromReasoning.formatOk()) {
                outcome = fromReasoning;
                raw = result.reasoning();
            }
        }
        if (!outcome.formatOk() && outcome.format() == MemoryValidator.FormatStatus.WRONG_SCHEMA_VERSION && raw != null) {
            String normalized = MemoryValidator.normalizeSchemaVersion(raw, mapper);
            if (!normalized.equals(raw)) {
                MemoryValidator.Outcome upgraded = MemoryValidator.validate(normalized, batch, shownTargets, mapper);
                if (upgraded.formatOk()) {
                    outcome = upgraded;
                    raw = normalized;
                }
            }
        }
        List<MemoryValidator.ValidatedCandidate> validated =
                outcome.formatOk() ? outcome.candidates() : List.of();
        List<MemoryValidator.ValidatedCandidate> forced = memoryIntentCandidates(batch, validated);
        if (!forced.isEmpty()) {
            Set<Long> forcedMessages = new HashSet<>();
            for (MemoryValidator.ValidatedCandidate candidate : forced) {
                forcedMessages.add(candidate.userMessageId());
            }
            List<MemoryValidator.ValidatedCandidate> merged = new ArrayList<>();
            for (MemoryValidator.ValidatedCandidate candidate : validated) {
                if (!forcedMessages.contains(candidate.userMessageId())) {
                    merged.add(candidate);
                }
            }
            merged.addAll(forced);
            return new ExtractionResult(MemoryValidator.FormatStatus.OK, merged, outcome.rejections(), usage,
                    shownTargets, raw);
        }
        if (!outcome.formatOk()) {
            return ExtractionResult.formatFailure(outcome.format(), usage, shownTargets, raw);
        }
        return new ExtractionResult(outcome.format(), outcome.candidates(), outcome.rejections(), usage,
                shownTargets, raw);
    }

    /**
     * 模型对“请记住”类消息返回空候选时的确定性兜底：只补模型没有覆盖到的用户消息。
     * 模型已经为该消息产出了候选就不重复补，避免同一条话落两条记忆。
     */
    private static List<MemoryValidator.ValidatedCandidate> memoryIntentCandidates(
            Batch batch, List<MemoryValidator.ValidatedCandidate> already) {
        Set<Long> covered = new HashSet<>();
        List<MemoryValidator.ValidatedCandidate> result = new ArrayList<>();
        for (SourceTurn turn : batch.turns()) {
            Long userMessageId = turn.userMessageId();
            if (userMessageId == null || covered.contains(userMessageId)) {
                continue;
            }
            SourceBlock block = intentBlock(turn);
            if (block == null) {
                continue;
            }
            String quote = clipTo(block.text().strip(), MemoryValidator.MAX_QUOTE_CODE_POINTS);
            String value = clipTo(memoryValue(block.text()), MemoryValidator.MAX_VALUE_CODE_POINTS);
            if (value.isBlank()) {
                continue;
            }
            result.add(new MemoryValidator.ValidatedCandidate("ADD", null, null, intentKind(block.text()), "other",
                    MemoryValidator.familyKey("other", value), value, value, quote, turn.turnId(), userMessageId,
                    block.blockId(), blockCreatedAt(turn), turn.finishedMillis(), false, null));
            covered.add(userMessageId);
            if (result.size() + already.size() >= MemoryValidator.MAX_CANDIDATES) {
                break;
            }
        }
        return result;
    }

    private static SourceBlock intentBlock(SourceTurn turn) {
        for (SourceBlock block : turn.blocks()) {
            String text = block.text();
            if (text == null) {
                continue;
            }
            for (String marker : MEMORY_INTENT_MARKERS) {
                if (text.contains(marker)) {
                    return block;
                }
            }
        }
        return null;
    }

    private static String memoryValue(String text) {
        String value = text;
        for (String marker : MEMORY_INTENT_MARKERS) {
            value = value.replace(marker, "");
        }
        value = value.replaceAll("^[\\s，。！？、：；,.!?:;]+|[\\s，。！？、：；,.!?:;]+$", "");
        return value.isBlank() ? text.strip() : value.strip();
    }

    private static String intentKind(String text) {
        if (containsAny(text, "我叫", "我的名字", "我住", "常住", "来自", "我是")) {
            return "BACKGROUND";
        }
        if (containsAny(text, "以后", "之后", "每次", "回答", "回复", "格式", "语气", "完毕")) {
            return "PREFERENCE";
        }
        return "TASK";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String clipTo(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
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

    /** 一次带外发目标的调用：目标解析与指纹复验都在 {@link MaintenanceUpstream}，中途换模型就当场停下。 */
    private AiUpstreamClient.Result dispatch(String input) throws AttemptFailure {
        return upstream.call(MemoryValidator.systemPrompt(), input);
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
