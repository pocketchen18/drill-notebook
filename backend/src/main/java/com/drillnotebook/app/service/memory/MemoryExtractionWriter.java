package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository.ProposalDraft;
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
 * 把通过 §5 校验的候选写成<b>加密事实、来源证据与待确认建议</b>（执行方案 §8.2 第 9 条、§8.3 判定表；
 * contracts §7 {@code applyExtraction}、§2.3、§2.3.1）。
 *
 * <p>分两步是刻意的：{@link #plan} 在<b>事务外</b>读同族当前值、跑 {@link MemoryConflictResolver}
 * 并把载荷与原话预加密（加密是 CPU 活，不占事务）；{@link #applyIn} 只在 worker 那一笔事务里搬字节，
 * 并且写每一条之前<b>再</b>核一次抑制、同族当前版本与 PENDING 配额——两次 CAS 之间不夹网络请求，
 * 也不夹索引重建（§7）。</p>
 *
 * <p>模型没有直写权限：能进 ACTIVE 的只有 {@code ADD_ACTIVE} 与 {@code REPLACE}，其余落 PENDING 或 proposal。
 * 一条都没写成也是合法结局：那时 {@code vaultChanged=false}，既不递增 vault 内容 revision 也不重建索引，
 * 但已扫描水位照样推进（§8.2 第 9 条）。</p>
 */
@Service
public class MemoryExtractionWriter {
    /** §2.3.1：建议默认 7 天失效。 */
    static final long PROPOSAL_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000L;
    /** keywords 上限（§2.3）：只由服务端从值里派生，绝不接受模型给的扩展词。 */
    private static final int MAX_KEYWORDS = 8;

    private final MemoryRepository memory;
    private final MemorySuppressionRepository suppressions;
    private final MemoryProposalRepository proposals;
    private final MemoryCryptoService crypto;
    private final ObjectMapper mapper;

    public MemoryExtractionWriter(MemoryRepository memory, MemorySuppressionRepository suppressions,
                                 MemoryProposalRepository proposals, MemoryCryptoService crypto,
                                 ObjectMapper mapper) {
        this.memory = memory;
        this.suppressions = suppressions;
        this.proposals = proposals;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /** 一条要写的事实版本。{@code targetVersion>0} 表示替换：旧版先 CAS 成 SUPERSEDED，新版 version+1。 */
    public record PlannedFact(NewMemory draft, String memoryId, int version, EvidenceDraft evidence,
                              String targetMemoryId, int targetVersion, String familyHmac, String predicateHmac) {
    }

    /** 一条要写的替换/撤销建议。 */
    public record PlannedProposal(ProposalDraft draft, EvidenceDraft evidence, String targetMemoryId,
                                  int targetVersion) {
    }

    /** 事务外备料的结果。{@code confirmRowIds} 与 {@code confirmEvidence} 按位一一对应。 */
    public record Plan(List<PlannedFact> facts, List<String> confirmRowIds, List<PlannedProposal> proposals,
                       List<EvidenceDraft> confirmEvidence, int ignored, List<String> decisions) {
        boolean nothingToWrite() {
            return facts.isEmpty() && confirmRowIds.isEmpty() && proposals.isEmpty();
        }
    }

    /** 写入统计。 */
    public record Applied(int factsWritten, int factsConfirmed, int proposalsWritten, int ignored,
                          boolean vaultChanged) {
        static final Applied NONE = new Applied(0, 0, 0, 0, false);
    }

    /** 逐候选跑判定表并备好材料；<b>一条都不写</b>，所以复验失败时整批可以干净作废。 */
    public Plan plan(Batch batch, ExtractionResult extracted, MemoryCryptoService.MemoryKey key, String asOf)
            throws SQLException, MemoryCryptoException {
        List<PlannedFact> facts = new ArrayList<>();
        List<PlannedProposal> proposalDrafts = new ArrayList<>();
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
            Decision decision = MemoryConflictResolver.decide(candidate, Optional.ofNullable(current),
                    new Context(byFamily, byPredicate, byEvidence, true));
            decisions.add(decision.action().name() + ":" + decision.reason());
            switch (decision.action()) {
                case IGNORE -> ignored++;
                case CONFIRM -> {
                    confirmRowIds.add(active.get(0).rowId());
                    confirmEvidence.add(evidence);
                }
                case ADD_PROPOSAL -> proposalDrafts.add(new PlannedProposal(
                        proposalOf(key, batch, candidate, decision, evidence, asOf), evidence,
                        decision.targetMemoryId(), decision.targetVersion()));
                default -> facts.add(plannedFact(key, batch, candidate, decision, evidence, asOf));
            }
        }
        return new Plan(List.copyOf(facts), List.copyOf(confirmRowIds), List.copyOf(proposalDrafts),
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
        int proposalCount = 0;
        int dropped = 0;
        for (PlannedFact fact : plan.facts()) {
            if (reSuppressed(connection, key, fact) || overQuota(connection, key, fact)) {
                dropped++;
                continue;
            }
            if (fact.targetVersion() > 0) {
                if (MemoryRepository.supersedeVersionIn(connection, fact.targetMemoryId(), fact.targetVersion())
                        != 1) {
                    // 目标在判定之后被人工改过：不覆盖，整条候选就地丢掉
                    dropped++;
                    continue;
                }
            } else if (MemoryRepository.STATUS_ACTIVE.equals(fact.draft().status())
                    && !MemoryRepository.activeInFamily(connection, key.vaultId(), fact.draft().scopeType(),
                    fact.draft().scopeId(), fact.familyHmac()).isEmpty()) {
                // 只有要写 ACTIVE 才受"同族一条 ACTIVE"约束；待确认事实与 ACTIVE 共存是 §8.3 的正常形态
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
        for (PlannedProposal proposal : plan.proposals()) {
            Optional<FactRow> target = MemoryRepository.findCurrent(connection, key.vaultId(),
                    proposal.targetMemoryId());
            if (target.isEmpty() || target.get().version() != proposal.targetVersion()) {
                // 目标已消失或已改版：这条建议失去意义（§2.3.1 的 STALE 语义），不替用户留半成品
                dropped++;
                continue;
            }
            String evidenceId = MemoryRepository.insertEvidence(connection, proposal.evidence()).evidenceId();
            ProposalDraft withEvidence = new ProposalDraft(proposal.draft().proposalId(), proposal.draft().vaultId(),
                    proposal.draft().sessionId(), proposal.draft().sourceTurnId(), evidenceId,
                    proposal.draft().targetMemoryId(), proposal.draft().targetVersion(),
                    proposal.draft().operation(), proposal.draft().payloadCipher(), proposal.draft().cryptoMeta(),
                    proposal.draft().dedupHmac(), proposal.draft().createdAt(), proposal.draft().expiresAt());
            if (proposals.submitIn(connection, withEvidence).outcome()
                    == MemoryProposalRepository.SubmitOutcome.SUBMITTED) {
                proposalCount++;
            } else {
                dropped++;
            }
        }
        boolean changed = written + confirmed + proposalCount > 0;
        if (changed) {
            MemoryRepository.updateVault(connection, key.vaultId(), false);
        }
        return new Applied(written, confirmed, proposalCount, plan.ignored() + dropped, changed);
    }

    // ---- 事务外备料 ----

    private PlannedFact plannedFact(MemoryCryptoService.MemoryKey key, Batch batch, ValidatedCandidate candidate,
                                    Decision decision, EvidenceDraft evidence, String asOf)
            throws SQLException, MemoryCryptoException {
        boolean replace = decision.action() == Action.REPLACE;
        boolean pending = decision.action() == Action.ADD_PENDING;
        String rowId = UUID.randomUUID().toString();
        String memoryId = replace ? decision.targetMemoryId() : UUID.randomUUID().toString();
        int version = replace ? decision.targetVersion() + 1 : 1;
        MemoryCryptoService.EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_item", rowId, version,
                payloadOf(batch, candidate, decision, pending));
        String observedAt = observedAt(candidate);
        NewMemory draft = new NewMemory(rowId, key.vaultId(), batch.scopeType(), batch.scopeId(), candidate.kind(),
                crypto.hmac(key, "predicate:" + candidate.predicate()),
                crypto.hmac(key, "family:" + candidate.family()),
                crypto.hmac(key, "value:" + candidate.normalizedValue()),
                pending ? MemoryRepository.STATUS_PENDING : MemoryRepository.STATUS_ACTIVE,
                encrypted.cipher(), encrypted.cryptoMeta(), "AUTO", false, false, null, null,
                observedAt, asOf, asOf);
        return new PlannedFact(draft, memoryId, version, evidence, replace ? decision.targetMemoryId() : null,
                replace ? decision.targetVersion() : 0, crypto.hmac(key, "family:" + candidate.family()),
                crypto.hmac(key, "predicate:" + candidate.predicate()));
    }

    private ProposalDraft proposalOf(MemoryCryptoService.MemoryKey key, Batch batch, ValidatedCandidate candidate,
                                     Decision decision, EvidenceDraft evidence, String asOf)
            throws SQLException, MemoryCryptoException {
        boolean retract = "RETRACT_PROPOSAL".equals(candidate.operation());
        String proposalId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("scopeType", batch.scopeType());
        payload.put("scopeId", batch.scopeId());
        payload.put("operation", candidate.operation());
        payload.put("kind", candidate.kind());
        payload.put("predicate", candidate.predicate());
        payload.put("newValue", retract ? null : candidate.normalizedValue());
        payload.put("blockId", candidate.sourceBlockId());
        payload.put("sourceTurnId", candidate.turnId());
        payload.put("sourceGeneration", batch.contextGeneration());
        payload.put("sourceEpoch", batch.vaultEpoch());
        payload.put("reviewReason", decision.reason());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("memoryId", decision.targetMemoryId());
        target.put("version", decision.targetVersion());
        payload.put("target", target);
        MemoryCryptoService.EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_proposal", proposalId, 1,
                serialize(payload));
        String dedup = crypto.hmac(key, "proposal:" + candidate.operation() + ":" + decision.targetMemoryId() + ":"
                + decision.targetVersion() + ":" + evidence.sourceHmac() + ":"
                + (retract ? "" : candidate.normalizedValue()));
        return new ProposalDraft(proposalId, key.vaultId(), batch.sessionId(), candidate.turnId(), null,
                decision.targetMemoryId(), decision.targetVersion(), candidate.operation(), encrypted.cipher(),
                encrypted.cryptoMeta(), dedup, asOf,
                MemoryTimes.formatMillis(MemoryTimes.parseMillis(asOf) + PROPOSAL_TTL_MILLIS));
    }

    /** 载荷 = contracts §2.3 的字段 + 现有读取方依赖的 {@code kind/predicate/family/value}，一份形状两边共用。 */
    private String payloadOf(Batch batch, ValidatedCandidate candidate, Decision decision, boolean pending) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("subject", "self");
        payload.put("kind", candidate.kind());
        payload.put("predicate", candidate.predicate());
        payload.put("family", candidate.family());
        payload.put("value", candidate.value());
        payload.put("normalizedValue", candidate.normalizedValue());
        payload.put("displayText", displayText(candidate));
        payload.put("keywords", MemoryLexicon.tokens(candidate.normalizedValue()).stream().limit(MAX_KEYWORDS)
                .toList());
        payload.put("sensitivity", MemorySensitivity.classify(candidate.value()).name());
        payload.put("sourceTimezone", batch.timezone());
        if (pending || decision.reason() != null) {
            payload.put("conflictReason", decision.reason());
        }
        if (decision.targetMemoryId() != null) {
            payload.put("proposedAgainstMemoryId", decision.targetMemoryId());
            payload.put("proposedAgainstVersion", decision.targetVersion());
        }
        return serialize(payload);
    }

    /** 展示句由服务端模板生成：模型自由造句不能混进给用户看的正文（§2.3）。 */
    private static String displayText(ValidatedCandidate candidate) {
        String value = candidate.normalizedValue();
        return switch (candidate.predicate()) {
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

    /** PENDING 与建议共用同一个 500 上限（§2.3.1）：配额满了就丢掉待确认候选，不挤掉已有条目。 */
    private boolean overQuota(Connection connection, MemoryCryptoService.MemoryKey key, PlannedFact fact)
            throws SQLException {
        return MemoryRepository.STATUS_PENDING.equals(fact.draft().status())
                && MemoryRepository.pendingCount(connection, key.vaultId()) >= MemoryRepository.PENDING_QUOTA;
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
            // 载荷坏了就当同族没有当前值：宁可走"新增待确认"，也不拿乱码冒充旧值做替换
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
