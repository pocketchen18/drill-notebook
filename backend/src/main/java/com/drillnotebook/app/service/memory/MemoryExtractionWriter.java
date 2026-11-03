package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceDraft;
import com.drillnotebook.app.repository.MemoryRepository.FactRow;
import com.drillnotebook.app.repository.MemoryRepository.NewMemory;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.ExtractionResult;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.Action;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.ActiveFact;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.Context;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.Decision;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryValidator.ValidatedCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 把通过 §5 校验的候选写成<b>加密事实与来源证据</b>（执行方案 §8.2 第 9 条、§8.3 判定表；
 * contracts §7 {@code applyExtraction}、§2.3、§2.3.1）。
 *
 * <p>用户已删除确认机制：这里只新增 ACTIVE 事实或给同值事实追加来源，不再写 PENDING，
 * 也不再生成替换/撤销建议。写库前按归一值做一次全库精确校验——能确定是同一段内容就拒绝，
 * 不确定是否重复则照常新增。</p>
 *
 * <p>分两步是刻意的：{@link #plan} 在<b>事务外</b>读同族当前值、跑 {@link MemoryConflictResolver}
 * 并把载荷与原话预加密（加密是 CPU 活，不占事务）；{@link #applyIn} 只在 worker 那一笔事务里搬字节，
 * 并且写每一条之前<b>再</b>核一次抑制与归一值重复——两次检查之间不夹网络请求，也不夹索引重建（§7）。</p>
 */
@Service
public class MemoryExtractionWriter {
    /** keywords 上限（§2.3）：只由服务端从值里派生，绝不接受模型给的扩展词。 */
    private static final int MAX_KEYWORDS = 8;

    private final MemoryRepository memory;
    private final MemorySuppressionRepository suppressions;
    private final MemoryCryptoService crypto;
    private final ObjectMapper mapper;

    public MemoryExtractionWriter(MemoryRepository memory, MemorySuppressionRepository suppressions,
                                 MemoryCryptoService crypto, ObjectMapper mapper) {
        this.memory = memory;
        this.suppressions = suppressions;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public record PlannedFact(NewMemory draft, String memoryId, int version, EvidenceDraft evidence,
                              String targetMemoryId, int targetVersion, String familyHmac, String predicateHmac,
                              String valueHmac) {
    }

    /** 事务外备料的结果。{@code confirmRowIds} 与 {@code confirmEvidence} 按位一一对应。 */
    public record Plan(List<PlannedFact> facts, List<String> confirmRowIds, List<EvidenceDraft> confirmEvidence,
                       int ignored, List<String> decisions) {
        boolean nothingToWrite() {
            return facts.isEmpty() && confirmRowIds.isEmpty();
        }
    }

    /** 写入统计。 */
    public record Applied(int factsWritten, int factsConfirmed, int ignored, boolean vaultChanged) {
        static final Applied NONE = new Applied(0, 0, 0, false);
    }

    /** 逐候选跑判定表并备好材料；<b>一条都不写</b>，所以复验失败时整批可以干净作废。 */
    public Plan plan(Batch batch, ExtractionResult extracted, MemoryCryptoService.MemoryKey key, String asOf)
            throws SQLException, MemoryCryptoException {
        List<PlannedFact> facts = new ArrayList<>();
        List<String> confirmRowIds = new ArrayList<>();
        List<EvidenceDraft> confirmEvidence = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        int ignored = 0;
        for (ValidatedCandidate candidate : extracted.candidates()) {
            SourceTurn source = sourceOf(batch, candidate.turnId());
            if (source == null) {
                // 装载之后来源整轮被换掉：不写，交给 SUPERSEDED 路径重排（§2.4）
                ignored++;
                decisions.add("IGNORE:SOURCE_NOT_IN_BATCH");
                continue;
            }
            String familyHmac = crypto.hmac(key, "family:" + candidate.family());
            String predicateHmac = crypto.hmac(key, "predicate:" + candidate.predicate());
            String valueHmac = crypto.hmac(key, "value:" + candidate.normalizedValue());
            EvidenceDraft evidence = evidenceOf(key, batch, candidate, source);
            boolean byFamily = suppressions.isSuppressed(key.vaultId(), batch.scopeType(), batch.scopeId(),
                    MemorySuppressionRepository.KIND_FAMILY, familyHmac);
            boolean byPredicate = !byFamily && suppressions.isSuppressed(key.vaultId(), batch.scopeType(),
                    batch.scopeId(), MemorySuppressionRepository.KIND_PREDICATE, predicateHmac);
            boolean byEvidence = !byFamily && !byPredicate && suppressions.isSuppressed(key.vaultId(),
                    batch.scopeType(), batch.scopeId(), MemorySuppressionRepository.KIND_EVIDENCE,
                    evidence.sourceHmac());
            List<FactRow> active = memory.findActiveInFamily(key.vaultId(), batch.scopeType(), batch.scopeId(),
                    familyHmac);
            ActiveFact current = active.isEmpty() ? null : toActiveFact(key, active.get(0));
            Optional<FactRow> duplicate = memory.findCurrentByValueHmac(key.vaultId(), valueHmac);
            if (duplicate.isPresent() && (current == null || !duplicate.get().memoryId().equals(current.memoryId()))) {
                ignored++;
                decisions.add("IGNORE:DUPLICATE_VALUE");
                continue;
            }
            Decision decision = MemoryConflictResolver.decide(candidate, Optional.ofNullable(current),
                    new Context(byFamily, byPredicate, byEvidence, true));
            decisions.add(decision.action().name() + ":" + decision.reason());
            switch (decision.action()) {
                case IGNORE -> ignored++;
                case CONFIRM -> {
                    confirmRowIds.add(active.get(0).rowId());
                    confirmEvidence.add(evidence);
                }
                default -> facts.add(plannedFact(key, batch, candidate, decision, evidence, valueHmac, asOf));
            }
        }
        return new Plan(List.copyOf(facts), List.copyOf(confirmRowIds),
                List.copyOf(confirmEvidence), ignored, List.copyOf(decisions));
    }

    /**
     * 事务内写入：由 worker 在 §7 那一笔事务里调用（租约 CAS 已赢）。每条都按<b>当前</b>库状态再判一次，
     * 掉了就地跳过；抛出的异常由调用方回滚整笔事务。
     */
    public Applied applyIn(Connection connection, Plan plan, MemoryCryptoService.MemoryKey key, String asOf)
            throws SQLException {
        if (plan.nothingToWrite()) {
            return Applied.NONE;
        }
        int written = 0;
        int confirmed = 0;
        int dropped = 0;
        for (PlannedFact fact : plan.facts()) {
            if (reSuppressed(connection, key, fact) || duplicateIn(connection, key, fact)) {
                dropped++;
                continue;
            }
            if (fact.targetVersion() > 0) {
                if (MemoryRepository.supersedeVersionIn(connection, fact.targetMemoryId(), fact.targetVersion()) != 1) {
                    dropped++;
                    continue;
                }
            } else if (!MemoryRepository.activeInFamily(connection, key.vaultId(), fact.draft().scopeType(),
                    fact.draft().scopeId(), fact.familyHmac()).isEmpty()) {
                dropped++;
                continue;
            }
            MemoryRepository.insertFact(connection, fact.draft(), fact.memoryId(), fact.draft().rowId(),
                    fact.version());
            MemoryRepository.attachEvidenceIn(connection, fact.evidence(), fact.draft().rowId());
            written++;
        }
        for (int index = 0; index < plan.confirmRowIds().size(); index++) {
            String rowId = plan.confirmRowIds().get(index);
            if (MemoryRepository.confirmIn(connection, rowId, asOf) == 1) {
                MemoryRepository.attachEvidenceIn(connection, plan.confirmEvidence().get(index), rowId);
                confirmed++;
            } else {
                dropped++;
            }
        }
        boolean changed = written + confirmed > 0;
        if (changed) {
            MemoryRepository.updateVault(connection, key.vaultId(), false);
        }
        return new Applied(written, confirmed, plan.ignored() + dropped, changed);
    }

    // ---- 事务外备料 ----

    private PlannedFact plannedFact(MemoryCryptoService.MemoryKey key, Batch batch, ValidatedCandidate candidate,
                                    Decision decision, EvidenceDraft evidence, String valueHmac, String asOf)
            throws SQLException, MemoryCryptoException {
        String rowId = UUID.randomUUID().toString();
        boolean replace = decision.action() == Action.REPLACE;
        String memoryId = replace ? decision.targetMemoryId() : UUID.randomUUID().toString();
        int version = replace ? decision.targetVersion() + 1 : 1;
        MemoryCryptoService.EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_item", rowId, version,
                payloadOf(batch, candidate, decision));
        String observedAt = observedAt(candidate);
        NewMemory draft = new NewMemory(rowId, key.vaultId(), batch.scopeType(), batch.scopeId(), candidate.kind(),
                crypto.hmac(key, "predicate:" + candidate.predicate()),
                crypto.hmac(key, "family:" + candidate.family()),
                valueHmac, MemoryRepository.STATUS_ACTIVE,
                encrypted.cipher(), encrypted.cryptoMeta(), "AUTO", false, false, null, null,
                observedAt, asOf, asOf);
        return new PlannedFact(draft, memoryId, version, evidence,
                replace ? decision.targetMemoryId() : null, replace ? decision.targetVersion() : 0,
                crypto.hmac(key, "family:" + candidate.family()),
                crypto.hmac(key, "predicate:" + candidate.predicate()), valueHmac);
    }

    /** 载荷 = contracts §2.3 的字段 + 现有读取方依赖的 {@code kind/predicate/family/value}，一份形状两边共用。 */
    private String payloadOf(Batch batch, ValidatedCandidate candidate, Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("subject", "self");
        payload.put("kind", candidate.kind());
        payload.put("predicate", candidate.predicate());
        payload.put("family", candidate.family());
        payload.put("value", candidate.value());
        payload.put("normalizedValue", candidate.normalizedValue());
        payload.put("displayText", displayText(candidate.predicate(), candidate.normalizedValue()));
        payload.put("keywords", MemoryLexicon.tokens(candidate.normalizedValue()).stream().limit(MAX_KEYWORDS)
                .toList());
        payload.put("sensitivity", MemorySensitivity.classify(candidate.value()).name());
        payload.put("sourceTimezone", batch.timezone());
        if (decision.reason() != null) {
            payload.put("conflictReason", decision.reason());
        }
        if (decision.targetMemoryId() != null) {
            payload.put("proposedAgainstMemoryId", decision.targetMemoryId());
            payload.put("proposedAgainstVersion", decision.targetVersion());
        }
        return serialize(payload);
    }

    /** 展示句由服务端模板生成：模型自由造句不能混进给用户看的正文（§2.3）。审阅采纳走同一套模板。 */
    static String displayText(String predicate, String value) {
        return switch (predicate) {
            case "answer.language" -> "回答语言：" + value;
            case "answer.detail" -> "回答详略：" + ("concise".equals(value) ? "简洁" : "详细");
            case "answer.order" -> "讲解顺序：" + ("example_first".equals(value) ? "先例子后原理" : "先原理后例子");
            case "study.goal" -> "学习目标：" + value;
            case "study.background" -> "学习背景：" + value;
            case "task.constraint" -> "任务约束：" + value;
            default -> "其他偏好：" + value;
        };
    }

    private EvidenceDraft evidenceOf(MemoryCryptoService.MemoryKey key, Batch batch, ValidatedCandidate candidate,
                                     SourceTurn source) throws SQLException, MemoryCryptoException {
        String evidenceId = UUID.randomUUID().toString();
        MemoryCryptoService.EncryptedValue quote = crypto.encrypt(key, "ai_memory_evidence", evidenceId, 1,
                candidate.quote());
        return new EvidenceDraft(evidenceId, key.vaultId(), batch.sessionId(), candidate.turnId(),
                candidate.userMessageId(), candidate.sourceBlockId(),
                crypto.hmac(key, "source:" + batch.sessionId() + ":" + candidate.turnId() + ":"
                        + candidate.sourceBlockId() + ":" + normalizeQuote(candidate.quote())),
                quote.cipher(), quote.cryptoMeta(), observedAt(candidate));
    }

    // ---- 复验与小工具 ----

    private boolean reSuppressed(Connection connection, MemoryCryptoService.MemoryKey key, PlannedFact fact)
            throws SQLException {
        NewMemory draft = fact.draft();
        return suppressions.isSuppressedIn(connection, key.vaultId(), draft.scopeType(), draft.scopeId(),
                MemorySuppressionRepository.KIND_FAMILY, fact.familyHmac())
                || suppressions.isSuppressedIn(connection, key.vaultId(), draft.scopeType(), draft.scopeId(),
                MemorySuppressionRepository.KIND_PREDICATE, fact.predicateHmac())
                || suppressions.isSuppressedIn(connection, key.vaultId(), draft.scopeType(), draft.scopeId(),
                MemorySuppressionRepository.KIND_EVIDENCE, fact.evidence().sourceHmac());
    }

    private boolean duplicateIn(Connection connection, MemoryCryptoService.MemoryKey key, PlannedFact fact)
            throws SQLException {
        return MemoryRepository.findCurrentByValueHmac(connection, key.vaultId(), fact.valueHmac()).isPresent();
    }

    private ActiveFact toActiveFact(MemoryCryptoService.MemoryKey key, FactRow row) {
        Map<String, Object> payload = payloadOf(key, row);
        return new ActiveFact(row.memoryId(), row.version(), row.origin(), row.manualProtected(), row.pinned(),
                text(payload.get("normalizedValue"), text(payload.get("value"), "")),
                MemoryTimes.parseMillis(row.observedAt()), MemoryTimes.parseMillis(row.lastConfirmedAt()));
    }

    private Map<String, Object> payloadOf(MemoryCryptoService.MemoryKey key, FactRow row) {
        try {
            return mapper.readValue(crypto.decrypt(key, "ai_memory_item", row.rowId(), row.version(),
                    row.payloadCipher(), row.cryptoMeta()), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            // 载荷坏了就当同族没有当前值：宁可新增，也不拿乱码冒充旧值
            return Map.of();
        }
    }

    private static SourceTurn sourceOf(Batch batch, long turnId) {
        for (SourceTurn turn : batch.turns()) {
            if (turn.turnId() == turnId) {
                return turn;
            }
        }
        return null;
    }

    /** 事实的"来源时间"就是用户说那句话的时刻；缺元数据才退到轮次完成时刻。 */
    private static String observedAt(ValidatedCandidate candidate) {
        return candidate.sourceCreatedAt() == null || candidate.sourceCreatedAt().isBlank()
                ? MemoryTimes.formatMillis(candidate.sourceMillis()) : candidate.sourceCreatedAt();
    }

    private static String normalizeQuote(String quote) {
        return quote == null ? "" : quote.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("记忆载荷无法序列化", error);
        }
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
