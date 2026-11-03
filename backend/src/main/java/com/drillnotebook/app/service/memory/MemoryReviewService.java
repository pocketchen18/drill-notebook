package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository.ProposalRow;
import com.drillnotebook.app.repository.MemoryProposalRepository.ReviewOutcome;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceRow;
import com.drillnotebook.app.repository.MemoryRepository.FactRow;
import com.drillnotebook.app.repository.MemoryRepository.NewMemory;
import com.drillnotebook.app.repository.MemoryRepository.VaultState;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository.Suppression;
import com.drillnotebook.app.service.memory.MemoryCryptoService.EncryptedValue;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 建议审阅（执行方案 §12 的三按钮、contracts §4.4 的审阅事务）。
 *
 * <p>模型只能建议，采纳与否是用户的动作，所以这一层是<b>唯一</b>能把建议变成事实的地方：
 * 采纳后走的是人工保护那套身份（{@code manualProtected=true}），下次自动提取不得覆盖它。</p>
 *
 * <p>§4.4 要求"所有变更、proposal 终态和 operation 记录同事务提交"，因此判据全部落在
 * 一笔 {@link ImmediateTransaction} 里：建议仍是 PENDING、版本对得上、没过期、目标还是用户看到的那一版、
 * 库 epoch 与会话 generation 没换代、来源原话还在。任何一条不满足就整笔不动，只把最新 metadata 回给用户。
 * 新族键已被第三条 ACTIVE 占用时也只是 409 让用户先整理，绝不自动删掉谁。</p>
 */
@Service
public class MemoryReviewService {
    public static final String ACTION_PROPOSAL_REVIEW = "MEMORY_PROPOSAL_REVIEW";
    public static final String ACTION_ITEM_REVIEW = "MEMORY_ITEM_REVIEW";
    private static final String DECISION_ACCEPT = "accept";
    private static final String DECISION_REJECT = "reject";
    private static final String DECISION_KEEP_BOTH = "keep_both";
    private static final String DECISION_REPLACE = "replace";
    private static final Set<String> DECISIONS = Set.of(DECISION_ACCEPT, DECISION_REJECT, DECISION_KEEP_BOTH);
    /** 待确认候选（{@code ADD_PENDING} 事实行）比建议多一个 {@code replace}：人可以指定要顶掉哪条旧值。 */
    private static final Set<String> ITEM_DECISIONS = Set.of(DECISION_ACCEPT, DECISION_REJECT, DECISION_KEEP_BOTH,
            DECISION_REPLACE);
    private static final String REPLACE = "REPLACE_PROPOSAL";
    private static final String RETRACT = "RETRACT_PROPOSAL";
    private static final int MAX_KEYWORDS = 8;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int SCAN_BATCH = 50;
    private static final String CURSOR_SEPARATOR = "|";

    private static final Logger log = LoggerFactory.getLogger(MemoryReviewService.class);

    /**
     * @param expectedTargetVersion 用户界面上"当前值"那一版；采纳与并存都必须带，撤销复用删除链路同样要带，
     *                              只有拒绝可以不带（它不动事实）。
     */
    public record ReviewInput(Integer expectedVersion, Integer expectedTargetVersion, String decision,
                             String operationId) {
    }

    /**
     * 待确认候选的审阅入参。{@code expectedVersion} 是用户读到的那条 PENDING 版本；
     * 只有 {@code replace} 需要再指明顶掉哪条旧记忆及其版本（§3：<b>人工选择旧目标</b>才允许替换）。
     */
    public record ItemReviewInput(Integer expectedVersion, String decision, String targetMemoryId,
                                  Integer expectedTargetVersion, String operationId) {
    }

    private final MemoryCryptoService crypto;
    private final MemoryRepository memory;
    private final MemoryProposalRepository proposals;
    private final MemorySuppressionRepository suppressions;
    private final MemoryOperationRepository operations;
    private final MemoryVaultService vault;
    private final MemoryPolicyService policy;
    private final MemoryJobRepository jobs;
    private final ImmediateTransaction immediate;
    private final ObjectMapper mapper;

    public MemoryReviewService(MemoryCryptoService crypto, MemoryRepository memory, MemoryProposalRepository proposals,
                               MemorySuppressionRepository suppressions, MemoryOperationRepository operations,
                               MemoryVaultService vault, MemoryPolicyService policy, MemoryJobRepository jobs,
                               ImmediateTransaction immediate, ObjectMapper mapper) {
        this.crypto = crypto;
        this.memory = memory;
        this.proposals = proposals;
        this.suppressions = suppressions;
        this.operations = operations;
        this.vault = vault;
        this.policy = policy;
        this.jobs = jobs;
        this.immediate = immediate;
        this.mapper = mapper;
    }

    /**
     * 建议列表（§3 的 {@code GET /memory/proposals}）：按作用域列出待确认的替换/撤销建议。
     *
     * <p>建议的 scope 在加密载荷里，明文列只有 vault/session，所以<b>必须</b>解密才能按 scope 过滤——
     * 这正是"已解锁才给列表"以及"不使用隐式当前会话"的原因。游标绑定筛选条件与库的 epoch/revision，
     * 任一变动作废（§3 末段"revision 变化返回可重置分页提示，不跨 scope 混用"）。</p>
     */
    public MemoryApiResult list(String scopeType, Long scopeId, Integer limit, String cursor) {
        String normalizedScope = blank(scopeType) ? null : scopeType.trim().toUpperCase(Locale.ROOT);
        if (normalizedScope != null && !"GLOBAL".equals(normalizedScope) && !"NOTEBOOK".equals(normalizedScope)) {
            return MemoryApiResult.error(400, "MEMORY_SCOPE_INVALID", "不支持的作用域过滤: " + scopeType);
        }
        if ("NOTEBOOK".equals(normalizedScope) && (scopeId == null || scopeId <= 0)) {
            return MemoryApiResult.error(400, "MEMORY_SCOPE_INVALID", "按笔记本过滤必须给出正的笔记本 ID");
        }
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(limit, MemoryItemService.MAX_PAGE_SIZE));
        String[] decoded;
        try {
            decoded = decodeCursor(cursor);
        } catch (RuntimeException error) {
            return MemoryApiResult.error(400, "MEMORY_CURSOR_INVALID", "分页游标无法解析，请从头开始");
        }
        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        long epoch;
        long revision;
        try {
            Optional<VaultState> state = memory.readVault(key.vaultId());
            if (state.isEmpty()) {
                return MemoryApiResult.error(500, "MEMORY_STORAGE_UNAVAILABLE", "记忆存储层暂时不可用");
            }
            epoch = state.get().epoch();
            revision = state.get().revision();
        } catch (SQLException error) {
            return storageError(error);
        }
        String binding = binding(normalizedScope, scopeId, epoch, revision);
        if (decoded != null && !binding.equals(decoded[2])) {
            return MemoryApiResult.error(409, "MEMORY_PAGE_STALE", "列表已经变化或筛选条件不同，请回到第一页", false,
                    Map.of("reason", "filter-or-generation-changed"));
        }
        String scanCreatedAt = decoded == null ? null : decoded[0];
        String scanId = decoded == null ? null : decoded[1];
        String asOf = MemoryTimes.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasMore = false;
        try {
            while (rows.size() < pageSize) {
                List<ProposalRow> batch = proposals.pagePending(key.vaultId(), asOf, scanCreatedAt, scanId,
                        SCAN_BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                int scanned = 0;
                for (ProposalRow row : batch) {
                    if (rows.size() >= pageSize) {
                        break;
                    }
                    scanCreatedAt = row.createdAt();
                    scanId = row.id();
                    scanned++;
                    Map<String, Object> view = proposalBody(key, row);
                    if (inScope(view, normalizedScope, scopeId)) {
                        rows.add(view);
                    }
                }
                boolean moreToFetch = batch.size() == SCAN_BATCH;
                if (rows.size() >= pageSize) {
                    hasMore = scanned < batch.size() || moreToFetch;
                }
                if (!moreToFetch) {
                    break;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("proposals", rows);
            body.put("limit", pageSize);
            body.put("hasMore", hasMore);
            if (scanId != null) {
                body.put("nextCursor", encodeCursor(scanCreatedAt, scanId, binding));
            }
            body.put("vaultEpoch", epoch);
            body.put("vaultRevision", revision);
            return MemoryApiResult.of(200, body);
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /** 列表里的一条：建议元信息 + 载荷字段 + 原话 + 目标当前值（三按钮要并排看新旧值才能决定）。 */
    private Map<String, Object> proposalBody(MemoryKey key, ProposalRow row) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("proposalId", row.id());
        view.put("version", row.version());
        view.put("status", row.status());
        view.put("operation", row.operation());
        view.put("targetMemoryId", row.targetMemoryId());
        view.put("targetVersion", row.targetVersion());
        view.put("sessionId", row.sessionId());
        view.put("sourceTurnId", row.sourceTurnId());
        view.put("createdAt", row.createdAt());
        view.put("expiresAt", row.expiresAt());
        Map<String, Object> payload;
        try {
            payload = readPayload(key, row);
            view.put("corrupt", false);
        } catch (MemoryCryptoException error) {
            view.put("corrupt", true);
            view.put("corruptCode", error.code());
            return view;
        }
        view.put("scopeType", payload.get("scopeType"));
        view.put("scopeId", payload.get("scopeId"));
        view.put("predicate", payload.get("predicate"));
        view.put("newValue", payload.get("newValue"));
        view.put("reviewReason", payload.get("reviewReason"));
        view.put("reviewReasonText", MemoryReviewReasons.text(text(payload.get("reviewReason"))));
        // 「分别保留」能不能点由服务端这张表说了算，前端不许复刻一份谓词清单（§12 的"仅多值类别"）
        view.put("multiValued", MemoryValidator.MULTI_VALUE_PREDICATES.contains(text(payload.get("predicate"))));
        view.put("quote", quoteOf(key, row.evidenceId()));
        view.put("target", targetOf(key, row.targetMemoryId()));
        return view;
    }

    /** 目标记忆的当前生效版元信息 + 正文；已经没有了就给 null（审阅事务会把它打成 STALE）。 */
    private Map<String, Object> targetOf(MemoryKey key, String targetMemoryId) throws SQLException {
        Optional<FactRow> target = memory.findCurrent(key.vaultId(), targetMemoryId);
        if (target.isEmpty()) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>(MemoryItemService.factKeys(target.get()));
        try {
            view.put("value", readFactPayload(key, target.get()).get("value"));
        } catch (MemoryCryptoException error) {
            view.put("value", null);
            view.put("corrupt", true);
        }
        return view;
    }

    /** 建议引用的那句原话；来源被清理后就没有了，界面据此显示"原话已不可读"。 */
    private String quoteOf(MemoryKey key, String evidenceId) throws SQLException {
        Optional<EvidenceRow> evidence = memory.findEvidence(evidenceId);
        if (evidence.isEmpty()) {
            return null;
        }
        try {
            return crypto.decrypt(key, "ai_memory_evidence", evidence.get().evidenceId(), 1,
                    evidence.get().quoteCipher(), evidence.get().cryptoMeta());
        } catch (MemoryCryptoException error) {
            return null;
        }
    }

    /** 载荷读不出来的行不参与作用域筛选：没有筛选条件时照样列出来，让用户看得见"有一条读不出来"。 */
    private static boolean inScope(Map<String, Object> view, String scopeType, Long scopeId) {
        if (scopeType == null) {
            return true;
        }
        return scopeType.equals(text(view.get("scopeType")))
                && (scopeId == null || number(view.get("scopeId")) == scopeId);
    }

    public MemoryApiResult review(String proposalId, ReviewInput input) {
        String decision = input.decision() == null ? "" : input.decision().trim().toLowerCase(Locale.ROOT);
        if (!DECISIONS.contains(decision)) {
            return MemoryApiResult.error(400, "MEMORY_DECISION_INVALID",
                    "审阅决定只能是 accept / reject / keep_both");
        }
        if (input.expectedVersion() == null) {
            return MemoryApiResult.error(400, "MEMORY_VERSION_REQUIRED", "审阅必须带上读到的建议版本");
        }
        if (!DECISION_REJECT.equals(decision) && input.expectedTargetVersion() == null) {
            return MemoryApiResult.error(400, "MEMORY_TARGET_VERSION_REQUIRED", "采纳或并存必须带上"
                    + "你看到的当前值版本");
        }
        Optional<ProposalRow> seen;
        try {
            seen = proposals.find(proposalId);
        } catch (SQLException error) {
            return storageError(error);
        }
        if (seen.isEmpty()) {
            return MemoryApiResult.error(404, "MEMORY_PROPOSAL_NOT_FOUND", "这条建议已经不在了");
        }
        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        Map<String, Object> payload;
        try {
            payload = readPayload(key, seen.get());
        } catch (MemoryCryptoException error) {
            return cryptoFailure(error);
        } catch (SQLException error) {
            return storageError(error);
        }
        String operation = String.valueOf(payload.get("operation"));
        String scopeType = String.valueOf(payload.get("scopeType"));
        long scopeId = number(payload.get("scopeId"));
        MemoryApiResult shapeProblem = shapeProblem(decision, operation, scopeType, scopeId, payload);
        if (shapeProblem != null) {
            return shapeProblem;
        }
        if (!DECISION_REJECT.equals(decision)) {
            try {
                MemoryPolicyService.Eligibility eligibility = policy.manualWriteEligibility(scopeType, scopeId);
                if (!eligibility.allowed()) {
                    return eligibility.toResult();
                }
            } catch (SQLException error) {
                return storageError(error);
            }
        }
        String dedupeId = blank(input.operationId()) ? null : input.operationId().trim();
        MemoryApiResult replay = begin(dedupeId, ACTION_PROPOSAL_REVIEW, proposalId);
        if (replay != null) {
            return replay;
        }
        try {
            CommitEffect effect = new CommitEffect();
            MemoryApiResult outcome = immediate.execute(connection -> {
                try {
                    return reviewIn(connection, key, seen.get(), payload, input, decision, dedupeId, effect);
                } catch (MemoryCryptoException error) {
                    // 加密失败也必须是整笔回滚：Work 只允许抛 SQLException，用 Refusal 带着响应穿出去
                    throw new Refusal(cryptoFailure(error));
                }
            });
            // 提交之后才做的前缀失效：照手动删除那条路，旧 epoch 的任务与摘要检查点都不许再被认领或续接
            if (effect.barrierEpoch > 0) {
                int cancelled = jobs.supersedeBeforeEpoch(effect.barrierEpoch);
                int contexts = memory.invalidateSessionContextsBelowEpoch(effect.barrierEpoch);
                log.info("撤销采纳已作废旧 epoch 派生物: epoch={}, 任务={}, 检查点={}", effect.barrierEpoch,
                        cancelled, contexts);
            }
            return release(dedupeId, outcome);
        } catch (Refusal refusal) {
            abandon(dedupeId);
            return refusal.result;
        } catch (SQLException error) {
            abandon(dedupeId);
            return storageError(error);
        }
    }

    /**
     * 被拒的决定要放掉 operationId：登记行留在 {@code IN_PROGRESS} 会让用户改好之后再点一次收到
     * {@code MEMORY_OPERATION_IN_FLIGHT}，等于把自己锁死。成功分支已在事务里 {@code completeIn}，不动。
     */
    private MemoryApiResult release(String operationId, MemoryApiResult outcome) {
        if (outcome.status() >= 400) {
            abandon(operationId);
        }
        return outcome;
    }

    /** 事务体内不可恢复的失败：带着要回给界面的响应穿出事务，回滚由 {@link ImmediateTransaction} 负责。 */
    private static final class Refusal extends RuntimeException {
        private final MemoryApiResult result;

        private Refusal(MemoryApiResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    /**
     * 事务体要交给提交之后的动作。目前只有一件：采纳撤销删掉了整条版本链，必须按
     * {@link MemoryItemService} 手动删除那条路同口径作废旧 epoch 的任务与摘要检查点——
     * {@code deleteVersionsByMemoryIdIn} 只删行并把屏障留给调用方，残留的旧 epoch 检查点会让那个会话
     * 的摘要再也续接不上（每 120 秒冷却后重新外发一次再被打回）。
     */
    private static final class CommitEffect {
        private long barrierEpoch;

        private void forgottenThrough(long epoch) {
            this.barrierEpoch = epoch;
        }
    }

    /** 事务体：任何一条前置判据不过就抛 {@link Refusal}，整笔回滚，事实与建议都不动。 */
    private MemoryApiResult reviewIn(Connection connection, MemoryKey key, ProposalRow seen,
                                     Map<String, Object> payload, ReviewInput input, String decision,
                                     String dedupeId, CommitEffect effect) throws SQLException, MemoryCryptoException {
        Optional<ProposalRow> current = MemoryProposalRepository.findIn(connection, seen.id());
        if (current.isEmpty()) {
            return MemoryApiResult.error(404, "MEMORY_PROPOSAL_NOT_FOUND", "这条建议已经不在了");
        }
        ProposalRow proposal = current.get();
        if (!MemoryProposalRepository.STATUS_PENDING.equals(proposal.status())) {
            return MemoryApiResult.of(200, Map.of("proposalId", proposal.id(), "status", proposal.status(),
                    "decision", decision, "changed", false));
        }
        if (proposal.version() != input.expectedVersion()) {
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "这条建议已经被处理过或改过",
                    false, proposalMeta(proposal));
        }
        String asOf = MemoryTimes.now();
        if (!after(proposal.expiresAt(), asOf)) {
            MemoryProposalRepository.markStaleIn(connection, proposal.id());
            return MemoryApiResult.error(409, "MEMORY_PROPOSAL_EXPIRED", "这条建议已过期，先看看当前值再决定",
                    false, proposalMeta(proposal));
        }
        VaultState vaultState = MemoryRepository.vaultStateIn(connection, key.vaultId()).orElseThrow(
                () -> new SQLException("记忆库行不存在"));
        if (number(payload.get("sourceEpoch")) != vaultState.epoch()) {
            MemoryProposalRepository.markStaleIn(connection, proposal.id());
            return MemoryApiResult.error(409, "MEMORY_PROPOSAL_STALE", "记忆库已经遗忘过一轮，这条建议不再成立",
                    false, proposalMeta(proposal));
        }
        Optional<EvidenceRow> source = MemoryRepository.findEvidenceIn(connection, proposal.evidenceId());
        if (source.isEmpty()) {
            MemoryProposalRepository.markStaleIn(connection, proposal.id());
            return MemoryApiResult.error(409, "MEMORY_PROPOSAL_STALE", "聊天原话已被清理，这条建议没有根据了",
                    false, proposalMeta(proposal));
        }
        Optional<ChatSessionMetadataRepository.State> state =
                ChatSessionMetadataRepository.read(connection, proposal.sessionId());
        if (state.isEmpty() || state.get().contextGeneration() != number(payload.get("sourceGeneration"))) {
            MemoryProposalRepository.markStaleIn(connection, proposal.id());
            return MemoryApiResult.error(409, "MEMORY_PROPOSAL_STALE", "这个会话已经换代，这条建议不再适用",
                    false, proposalMeta(proposal));
        }
        Optional<FactRow> target = MemoryRepository.findCurrent(connection, key.vaultId(),
                proposal.targetMemoryId());
        if (target.isEmpty()) {
            MemoryProposalRepository.markStaleForTargetIn(connection, key.vaultId(), proposal.targetMemoryId());
            return MemoryApiResult.error(409, "MEMORY_PROPOSAL_STALE", "那条记忆已经没有生效版本了", false,
                    proposalMeta(proposal));
        }
        FactRow active = target.get();
        if (active.version() != proposal.targetVersion()
                || (input.expectedTargetVersion() != null && input.expectedTargetVersion() != active.version())) {
            MemoryProposalRepository.markStaleForTargetIn(connection, key.vaultId(), proposal.targetMemoryId());
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "当前值已经被改过，请对照最新值再决定",
                    false, Map.of("target", MemoryItemService.factKeys(active)));
        }
        return switch (decision) {
            case DECISION_REJECT -> reject(connection, key, proposal, source.get(),
                    String.valueOf(payload.get("scopeType")), number(payload.get("scopeId")), dedupeId, asOf);
            case DECISION_KEEP_BOTH -> keepBoth(connection, key, proposal, payload, source.get(), dedupeId, asOf);
            default -> RETRACT.equals(String.valueOf(payload.get("operation")))
                    ? acceptRetract(connection, key, proposal, active, dedupeId, asOf, effect)
                    : acceptReplace(connection, key, proposal, payload, active, source.get(), dedupeId, asOf);
        };
    }

    /** 拒绝：事实一个字不动，只把这条来源抑制掉，免得下一次整理又把同一个建议提回来（§4.4）。 */
    private MemoryApiResult reject(Connection connection, MemoryKey key, ProposalRow proposal, EvidenceRow source,
                                   String scopeType, long scopeId, String dedupeId, String asOf)
            throws SQLException {
        if (MemoryProposalRepository.reviewIn(connection, proposal.id(), proposal.version(),
                MemoryProposalRepository.STATUS_REJECTED) != ReviewOutcome.APPLIED) {
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "这条建议刚被别人处理过", false,
                    Map.of("version", proposal.version()));
        }
        suppressions.recordIn(connection, key.vaultId(), new Suppression(scopeType, scopeId,
                MemorySuppressionRepository.KIND_EVIDENCE, source.sourceHmac(), proposal.sourceTurnId(), asOf));
        complete(connection, dedupeId, body(proposal.id(), MemoryProposalRepository.STATUS_REJECTED,
                proposal.targetMemoryId(), proposal.targetVersion(), false), asOf);
        log.info("建议已拒绝并抑制来源: proposalId={}", proposal.id());
        return MemoryApiResult.of(200, body(proposal.id(), MemoryProposalRepository.STATUS_REJECTED,
                proposal.targetMemoryId(), proposal.targetVersion(), false));
    }

    // ---- 待确认候选（提取链写下的 ADD_PENDING 事实行）----

    /**
     * 审阅一条待确认候选（§3 的 {@code POST /memory/items/{id}/review}）。
     *
     * <p>候选本身就是那条 PENDING 事实行，没有附带的建议行，所以决定只能落在行上：
     * {@code accept}/{@code keep_both} 就地转正，{@code reject} 删掉候选并抑制它的原话，
     * 只有 {@code replace} 才动旧值——而且必须由人点名顶掉哪条记忆（{@code targetMemoryId} +
     * {@code expectedTargetVersion}），写入段与建议采纳共用（§3"走 §4.4 同一替换事务"）。</p>
     */
    public MemoryApiResult reviewItem(String memoryId, ItemReviewInput input) {
        String decision = input.decision() == null ? "" : input.decision().trim().toLowerCase(Locale.ROOT);
        if (!ITEM_DECISIONS.contains(decision)) {
            return MemoryApiResult.error(400, "MEMORY_DECISION_INVALID",
                    "候选的决定只能是 accept / reject / keep_both / replace");
        }
        if (input.expectedVersion() == null) {
            return MemoryApiResult.error(400, "MEMORY_VERSION_REQUIRED", "审阅候选必须带上读到的候选版本");
        }
        if (DECISION_REPLACE.equals(decision) && (blank(input.targetMemoryId())
                || input.expectedTargetVersion() == null)) {
            return MemoryApiResult.error(400, "MEMORY_TARGET_REQUIRED", "替换必须点名要顶掉哪条记忆及其版本");
        }
        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        Optional<FactRow> seen;
        try {
            seen = memory.findVersion(key.vaultId(), memoryId, input.expectedVersion());
        } catch (SQLException error) {
            return storageError(error);
        }
        if (seen.isEmpty()) {
            return MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "这条候选已经不在了");
        }
        FactRow pending = seen.get();
        Map<String, Object> payload;
        try {
            payload = readFactPayload(key, pending);
        } catch (MemoryCryptoException error) {
            return cryptoFailure(error);
        }
        String predicate = text(payload.get("predicate"));
        if (DECISION_KEEP_BOTH.equals(decision) && !MemoryValidator.MULTI_VALUE_PREDICATES.contains(predicate)) {
            return MemoryApiResult.error(400, "MEMORY_DECISION_NOT_ALLOWED",
                    "这个谓词是单值偏好，候选只能采用或拒绝，不能与旧值并存");
        }
        if (!DECISION_REJECT.equals(decision)) {
            try {
                MemoryPolicyService.Eligibility eligibility = policy.manualWriteEligibility(pending.scopeType(),
                        pending.scopeId());
                if (!eligibility.allowed()) {
                    return eligibility.toResult();
                }
            } catch (SQLException error) {
                return storageError(error);
            }
        }
        String dedupeId = blank(input.operationId()) ? null : input.operationId().trim();
        MemoryApiResult replay = begin(dedupeId, ACTION_ITEM_REVIEW, memoryId);
        if (replay != null) {
            return replay;
        }
        try {
            MemoryApiResult outcome = immediate.execute(connection -> {
                try {
                    return reviewItemIn(connection, key, pending, payload, input, decision, dedupeId);
                } catch (MemoryCryptoException error) {
                    throw new Refusal(cryptoFailure(error));
                }
            });
            return release(dedupeId, outcome);
        } catch (Refusal refusal) {
            abandon(dedupeId);
            return refusal.result;
        } catch (SQLException error) {
            abandon(dedupeId);
            return storageError(error);
        }
    }

    /** 事务体：重读候选行确认它还是用户看到的那一版 PENDING，再分流。 */
    private MemoryApiResult reviewItemIn(Connection connection, MemoryKey key, FactRow pending,
                                          Map<String, Object> payload, ItemReviewInput input, String decision,
                                          String dedupeId) throws SQLException, MemoryCryptoException {
        FactRow current = MemoryRepository.findVersionIn(connection, key.vaultId(), pending.memoryId(),
                pending.version()).orElse(null);
        if (current == null) {
            return MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "这条候选已经不在了");
        }
        if (!MemoryRepository.STATUS_PENDING.equals(current.status())) {
            return MemoryApiResult.of(200, itemOutcome(current.memoryId(), current.version(), current.status(),
                    decision, false));
        }
        String asOf = MemoryTimes.now();
        List<EvidenceRow> sources = MemoryRepository.evidenceForItemIn(connection, current.rowId());
        EvidenceRow source = sources.isEmpty() ? null : sources.get(0);
        return DECISION_REJECT.equals(decision) ? rejectPending(connection, key, current, source, dedupeId, asOf)
                : DECISION_REPLACE.equals(decision)
                        ? replaceWithPending(connection, key, current, payload, source, input, dedupeId, asOf)
                        : activatePending(connection, key, current, decision, dedupeId, asOf);
    }

    /** 采用 / 分别保留：PENDING 就地转正并打人工保护；同族已有 ACTIVE 就 409 让人先整理。 */
    private MemoryApiResult activatePending(Connection connection, MemoryKey key, FactRow pending, String decision,
                                            String dedupeId, String asOf) throws SQLException {
        MemoryApiResult occupied = familyOccupied(connection, key, pending.scopeType(), pending.scopeId(),
                pending.familyKeyHmac(), null);
        if (occupied != null) {
            return occupied;
        }
        if (MemoryRepository.promotePendingIn(connection, key.vaultId(), pending.memoryId(), pending.version(),
                asOf) != 1) {
            throw new SQLException("候选状态在事务内被改：整笔作废");
        }
        MemoryRepository.updateVault(connection, key.vaultId(), false);
        Map<String, Object> response = itemOutcome(pending.memoryId(), pending.version(),
                MemoryRepository.STATUS_ACTIVE, decision, true);
        complete(connection, dedupeId, response, asOf);
        log.info("待确认候选已转正: memoryId={}, version={}, decision={}", pending.memoryId(), pending.version(),
                decision);
        return MemoryApiResult.of(200, response);
    }

    /** 拒绝候选：只删这一条 PENDING（不推遗忘边界、不写 family/predicate 抑制），抑制的是这句原话。 */
    private MemoryApiResult rejectPending(Connection connection, MemoryKey key, FactRow pending, EvidenceRow source,
                                          String dedupeId, String asOf) throws SQLException {
        if (MemoryRepository.deletePendingVersionIn(connection, key.vaultId(), pending.memoryId(), pending.version())
                != 1) {
            throw new SQLException("候选状态在事务内被改：整笔作废");
        }
        if (source != null) {
            suppressions.recordIn(connection, key.vaultId(), new Suppression(pending.scopeType(), pending.scopeId(),
                    MemorySuppressionRepository.KIND_EVIDENCE, source.sourceHmac(), source.turnId(), asOf));
        }
        MemoryRepository.updateVault(connection, key.vaultId(), false);
        Map<String, Object> response = itemOutcome(pending.memoryId(), pending.version(),
                MemoryProposalRepository.STATUS_REJECTED, DECISION_REJECT, true);
        complete(connection, dedupeId, response, asOf);
        log.info("待确认候选已拒绝: memoryId={}", pending.memoryId());
        return MemoryApiResult.of(200, response);
    }

    /** 用候选顶掉人点名选中的旧记忆：目标版本 CAS + 族占用复验 + §4.4 替换段，最后删掉候选行。 */
    private MemoryApiResult replaceWithPending(Connection connection, MemoryKey key, FactRow pending,
                                               Map<String, Object> payload, EvidenceRow source, ItemReviewInput input,
                                               String dedupeId, String asOf)
            throws SQLException, MemoryCryptoException {
        if (input.targetMemoryId().equals(pending.memoryId())) {
            return MemoryApiResult.error(400, "MEMORY_TARGET_INVALID", "候选不能替换它自己");
        }
        FactRow target = MemoryRepository.findCurrent(connection, key.vaultId(), input.targetMemoryId()).orElse(null);
        if (target == null) {
            return MemoryApiResult.error(409, "MEMORY_TARGET_NOT_FOUND", "要替换的那条记忆已经没有生效版本了", false,
                    Map.of("targetMemoryId", input.targetMemoryId()));
        }
        if (!target.scopeType().equals(pending.scopeType()) || target.scopeId() != pending.scopeId()) {
            return MemoryApiResult.error(400, "MEMORY_SCOPE_INVALID", "候选与要替换的记忆不在同一个作用域");
        }
        if (target.version() != input.expectedTargetVersion()) {
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "要替换的记忆已经被改过，请对照最新值再决定",
                    false, Map.of("target", MemoryItemService.factKeys(target)));
        }
        if (source == null) {
            return MemoryApiResult.error(409, "MEMORY_SOURCE_MISSING", "候选的聊天原话已经不在了，不能落成事实", false,
                    MemoryItemService.factKeys(pending));
        }
        String predicate = text(payload.get("predicate"));
        String value = text(payload.get("value"));
        String family = text(payload.get("family"));
        String familyKey = blank(family) ? MemoryValidator.familyKey(predicate, value) : family;
        String familyHmac = crypto.hmac(key, "family:" + familyKey);
        MemoryApiResult occupied = familyOccupied(connection, key, target.scopeType(), target.scopeId(), familyHmac,
                target);
        if (occupied != null) {
            return occupied;
        }
        writeReplacementIn(connection, key, new Replacement(target.scopeType(), target.scopeId(), payload, predicate,
                familyKey, value, source, source.turnId()), target, asOf);
        if (MemoryRepository.deletePendingVersionIn(connection, key.vaultId(), pending.memoryId(), pending.version())
                != 1) {
            throw new SQLException("候选状态在事务内被改：整笔作废");
        }
        Map<String, Object> response = itemOutcome(target.memoryId(), target.version() + 1,
                MemoryRepository.STATUS_ACTIVE, DECISION_REPLACE, true);
        response.put("pendingMemoryId", pending.memoryId());
        complete(connection, dedupeId, response, asOf);
        log.info("候选已替换旧记忆: pending={}, target={}, version={}", pending.memoryId(), target.memoryId(),
                target.version() + 1);
        return MemoryApiResult.of(200, response);
    }

    /** 采纳撤销：复用 deleteMemory 的仓储半边（同事务），建议先落 ACCEPTED 再删，免得被删除逻辑打成 STALE。 */
    private MemoryApiResult acceptRetract(Connection connection, MemoryKey key, ProposalRow proposal, FactRow active,
                                          String dedupeId, String asOf, CommitEffect effect) throws SQLException {
        if (MemoryProposalRepository.reviewIn(connection, proposal.id(), proposal.version(),
                MemoryProposalRepository.STATUS_ACCEPTED) != ReviewOutcome.APPLIED) {
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "这条建议刚被别人处理过", false,
                    Map.of("version", proposal.version()));
        }
        MemoryRepository.DeleteResult deleted = memory.deleteVersionsByMemoryIdIn(connection, key.vaultId(),
                active.memoryId(), active.version());
        if (deleted.outcome() == MemoryRepository.DeleteOutcome.VERSION_CONFLICT) {
            throw new SQLException("撤销目标版本在事务内被改：整笔作废");
        }
        if (deleted.outcome() == MemoryRepository.DeleteOutcome.NOT_FOUND) {
            return MemoryApiResult.error(409, "MEMORY_PROPOSAL_STALE", "那条记忆已经不在了", false,
                    proposalMeta(proposal));
        }
        complete(connection, dedupeId, body(proposal.id(), MemoryProposalRepository.STATUS_ACCEPTED,
                active.memoryId(), active.version(), true), asOf);
        if (deleted.deleted()) {
            effect.forgottenThrough(deleted.vault().epoch());
        }
        log.info("建议已采纳为撤销: proposalId={}, memoryId={}, 抑制={}, 建议置 STALE={}", proposal.id(),
                active.memoryId(), deleted.suppressionsAdded(), deleted.proposalsStaled());
        return MemoryApiResult.of(200, body(proposal.id(), MemoryProposalRepository.STATUS_ACCEPTED,
                active.memoryId(), active.version(), true));
    }

    /** 采纳替换：旧版置 SUPERSEDED，同一 memoryId 长出新版本，来源沿用建议那条原话。 */
    private MemoryApiResult acceptReplace(Connection connection, MemoryKey key, ProposalRow proposal,
                                          Map<String, Object> payload, FactRow active, EvidenceRow source,
                                          String dedupeId, String asOf)
            throws SQLException, MemoryCryptoException {
        String predicate = String.valueOf(payload.get("predicate"));
        String newValue = String.valueOf(payload.get("newValue"));
        String scopeType = String.valueOf(payload.get("scopeType"));
        long scopeId = number(payload.get("scopeId"));
        String familyKey = MemoryValidator.familyKey(predicate, newValue);
        String familyHmac = crypto.hmac(key, "family:" + familyKey);
        MemoryApiResult occupied = familyOccupied(connection, key, scopeType, scopeId, familyHmac, active);
        if (occupied != null) {
            return occupied;
        }
        if (MemoryProposalRepository.reviewIn(connection, proposal.id(), proposal.version(),
                MemoryProposalRepository.STATUS_ACCEPTED) != ReviewOutcome.APPLIED) {
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "这条建议刚被别人处理过", false,
                    Map.of("version", proposal.version()));
        }
        writeReplacementIn(connection, key, new Replacement(scopeType, scopeId, payload, predicate, familyKey,
                newValue, source, proposal.sourceTurnId()), active, asOf);
        Map<String, Object> response = body(proposal.id(), MemoryProposalRepository.STATUS_ACCEPTED,
                active.memoryId(), active.version() + 1, true);
        complete(connection, dedupeId, response, asOf);
        log.info("建议已采纳为替换: proposalId={}, memoryId={}, version={}", proposal.id(), active.memoryId(),
                active.version() + 1);
        return MemoryApiResult.of(200, response);
    }

    /** 替换段所需的材料：作用域、候选载荷、谓词/族键/新值、原话与来源轮次。 */
    private record Replacement(String scopeType, long scopeId, Map<String, Object> payload, String predicate,
                               String familyKey, String newValue, EvidenceRow source, Long sourceTurnId) {
    }

    /**
     * §4.4 替换半边的写入段，建议采纳与候选替换共用（§3 明写"人工选择旧目标时 replace 走 §4.4 同一替换事务"）：
     * 旧 ACTIVE 置 SUPERSEDED → 同一 memoryId 长出新版本 → 复用原话证据 → 族真的换了才抑制旧族 → 推 revision。
     * 调用方必须先做完族占用复验，这段里不判冲突、只落库。
     */
    private void writeReplacementIn(Connection connection, MemoryKey key, Replacement plan, FactRow target,
                                    String asOf) throws SQLException, MemoryCryptoException {
        if (MemoryRepository.supersedeVersionIn(connection, target.memoryId(), target.version()) != 1) {
            throw new SQLException("目标 ACTIVE 版在事务内被改：整笔作废");
        }
        int version = target.version() + 1;
        String rowId = UUID.randomUUID().toString();
        MemoryRepository.insertFact(connection, factDraft(key, plan.scopeType(), plan.scopeId(), plan.payload(),
                plan.predicate(), plan.familyKey(), plan.newValue(), MemoryRepository.STATUS_ACTIVE, rowId, version,
                target, plan.source(), asOf), target.memoryId(), rowId, version);
        MemoryRepository.linkEvidenceIn(connection, rowId, plan.source().evidenceId());
        String familyHmac = crypto.hmac(key, "family:" + plan.familyKey());
        // 只有族键真的换了才抑制旧族：单值谓词的新旧族键相同，抑制它等于把用户刚采纳的这条偏好通道一起关掉
        if (!familyHmac.equals(target.familyKeyHmac())) {
            suppressions.recordIn(connection, key.vaultId(), new Suppression(plan.scopeType(), plan.scopeId(),
                    MemorySuppressionRepository.KIND_FAMILY, target.familyKeyHmac(), plan.sourceTurnId(), asOf));
        }
        MemoryRepository.updateVault(connection, key.vaultId(), false);
    }

    /** 分别保留：旧值不动，新值另起一条 memoryId 的人工保护事实；仅多值谓词允许（§4.4）。 */
    private MemoryApiResult keepBoth(Connection connection, MemoryKey key, ProposalRow proposal,
                                     Map<String, Object> payload, EvidenceRow source, String dedupeId, String asOf)
            throws SQLException, MemoryCryptoException {
        String predicate = String.valueOf(payload.get("predicate"));
        String newValue = String.valueOf(payload.get("newValue"));
        String scopeType = String.valueOf(payload.get("scopeType"));
        long scopeId = number(payload.get("scopeId"));
        String familyKey = MemoryValidator.familyKey(predicate, newValue);
        String familyHmac = crypto.hmac(key, "family:" + familyKey);
        MemoryApiResult occupied = familyOccupied(connection, key, scopeType, scopeId, familyHmac, null);
        if (occupied != null) {
            return occupied;
        }
        if (MemoryProposalRepository.reviewIn(connection, proposal.id(), proposal.version(),
                MemoryProposalRepository.STATUS_ACCEPTED) != ReviewOutcome.APPLIED) {
            return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "这条建议刚被别人处理过", false,
                    Map.of("version", proposal.version()));
        }
        int version = 1;
        String memoryId = UUID.randomUUID().toString();
        String rowId = UUID.randomUUID().toString();
        MemoryRepository.insertFact(connection, factDraft(key, scopeType, scopeId, payload, predicate, familyKey,
                newValue, MemoryRepository.STATUS_ACTIVE, rowId, version, null, source, asOf), memoryId, rowId,
                version);
        MemoryRepository.linkEvidenceIn(connection, rowId, source.evidenceId());
        MemoryRepository.updateVault(connection, key.vaultId(), false);
        Map<String, Object> response = body(proposal.id(), MemoryProposalRepository.STATUS_ACCEPTED, memoryId,
                version, true);
        complete(connection, dedupeId, response, asOf);
        log.info("建议已采纳为并存: proposalId={}, memoryId={}", proposal.id(), memoryId);
        return MemoryApiResult.of(200, response);
    }

    /** 同族已有别的生效记忆：409 让用户先整理，绝不自动删第三条（§4.4）。 */
    private MemoryApiResult familyOccupied(Connection connection, MemoryKey key, String scopeType, long scopeId,
                                           String familyHmac, FactRow beingReplaced) throws SQLException {
        List<Map<String, Object>> blocking = new ArrayList<>();
        for (FactRow row : MemoryRepository.activeInFamily(connection, key.vaultId(), scopeType, scopeId,
                familyHmac)) {
            if (beingReplaced == null || !row.memoryId().equals(beingReplaced.memoryId())) {
                blocking.add(MemoryItemService.factKeys(row));
            }
        }
        if (blocking.isEmpty()) {
            return null;
        }
        return MemoryApiResult.error(409, "MEMORY_FAMILY_CONFLICT", "同一族已经有别的生效记忆，请先整理再采纳",
                false, Map.of("candidates", blocking));
    }

    /** 新版本的事实行：身份沿用被替换那条（有效期/置顶/谓词族），来源时间与原话来自这条建议。 */
    private MemoryRepository.NewMemory factDraft(MemoryKey key, String scopeType, long scopeId,
                                                 Map<String, Object> payload, String predicate, String familyKey,
                                                 String newValue, String status, String rowId, int version,
                                                 FactRow inherited, EvidenceRow source, String asOf)
            throws SQLException, MemoryCryptoException {
        String kind = String.valueOf(payload.get("kind"));
        EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_item", rowId, version,
                factPayload(kind, predicate, familyKey, newValue, source));
        return new NewMemory(rowId, key.vaultId(), scopeType, scopeId, kind,
                crypto.hmac(key, "predicate:" + predicate), crypto.hmac(key, "family:" + familyKey),
                crypto.hmac(key, "value:" + newValue), status, encrypted.cipher(), encrypted.cryptoMeta(),
                // origin 记的是内容来源（模型提取），人不点头才不改；采纳这个动作落在 manual_protected 上，
                // §4.4 要求的正是 manualProtected=true，不是把来源改写成人工。
                "AUTO", true, inherited != null && inherited.pinned(),
                inherited == null ? null : inherited.validFrom(), inherited == null ? null : inherited.validUntil(),
                source.sourceCreatedAt() == null ? asOf : source.sourceCreatedAt(), asOf, asOf);
    }

    /** 事实载荷与 {@link MemoryExtractionWriter} 同一套键：展示句由服务端模板生成，检索词由服务端派生。 */
    private String factPayload(String kind, String predicate, String familyKey, String value, EvidenceRow source)
            throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("subject", "self");
        payload.put("kind", kind);
        payload.put("predicate", predicate);
        payload.put("family", familyKey);
        payload.put("value", value);
        payload.put("normalizedValue", value);
        payload.put("displayText", MemoryExtractionWriter.displayText(predicate, value));
        payload.put("keywords", MemoryLexicon.tokens(value).stream().limit(MAX_KEYWORDS).toList());
        payload.put("sensitivity", MemorySensitivity.classify(value).name());
        if (source.sourceCreatedAt() != null) {
            payload.put("sourceTime", source.sourceCreatedAt());
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new SQLException("采纳后的记忆载荷无法序列化", error);
        }
    }

    /**
     * 按钮与类别的组合门：并存只在多值替换上成立，撤销建议没有可并存的新值。
     * 返回 null 表示形状合法。
     */
    private MemoryApiResult shapeProblem(String decision, String operation, String scopeType, long scopeId,
                                         Map<String, Object> payload) {
        if (!REPLACE.equals(operation) && !RETRACT.equals(operation)) {
            return MemoryApiResult.error(400, "MEMORY_PROPOSAL_OPERATION_INVALID", "这条建议的动作不认识");
        }
        if (DECISION_KEEP_BOTH.equals(decision)) {
            if (RETRACT.equals(operation)) {
                return MemoryApiResult.error(400, "MEMORY_DECISION_NOT_ALLOWED", "撤销建议没有可并存的新值");
            }
            String predicate = String.valueOf(payload.get("predicate"));
            if (!MemoryValidator.MULTI_VALUE_PREDICATES.contains(predicate)) {
                return MemoryApiResult.error(400, "MEMORY_DECISION_NOT_ALLOWED",
                        "这个谓词是单值偏好，只能采用新值或保留当前");
            }
        }
        if (REPLACE.equals(operation) && !DECISION_REJECT.equals(decision)
                && blank(String.valueOf(payload.get("newValue")))) {
            return MemoryApiResult.error(400, "MEMORY_VALUE_INVALID", "这条建议没有新值，无法采纳");
        }
        if (!"GLOBAL".equals(scopeType) && !"NOTEBOOK".equals(scopeType)) {
            return MemoryApiResult.error(400, "MEMORY_SCOPE_INVALID", "这条建议的作用域已不可用");
        }
        return null;
    }

    private Map<String, Object> readPayload(MemoryKey key, ProposalRow row)
            throws MemoryCryptoException, SQLException {
        try {
            return mapper.readValue(crypto.decrypt(key, "ai_memory_proposal", row.id(), 1, row.payloadCipher(),
                    row.cryptoMeta()), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (MemoryCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new SQLException("建议载荷不可读", error);
        }
    }

    /** 候选事实行的载荷：AAD 绑的是这一版自己的 rowId+version，与建议载荷是两套密文。 */
    private Map<String, Object> readFactPayload(MemoryKey key, FactRow row) throws MemoryCryptoException {
        try {
            return mapper.readValue(crypto.decrypt(key, "ai_memory_item", row.rowId(), row.version(),
                    row.payloadCipher(), row.cryptoMeta()), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (MemoryCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_CIPHER_CORRUPT", "候选载荷不是可用 JSON，按不可用隔离");
        }
    }

    /** 候选审阅的响应：只回身份与结果，正文由调用方另走 GET（错误与成功都不带明文）。 */
    private static Map<String, Object> itemOutcome(String memoryId, int version, String status, String decision,
                                                   boolean changed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memoryId", memoryId);
        body.put("version", version);
        body.put("status", status);
        body.put("decision", decision);
        body.put("changed", changed);
        return body;
    }

    /** 游标 = {@code createdAt|id|绑定串}：绑定串变了就作废，避免跨筛选条件或跨库代次翻页。 */
    private static String encodeCursor(String createdAt, String id, String binding) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (createdAt + CURSOR_SEPARATOR + id + CURSOR_SEPARATOR + binding).getBytes(StandardCharsets.UTF_8));
    }

    /** 解出 {@code [createdAt, id, binding]}；空游标返回 {@code null}，形状不对抛出让调用方回 400。 */
    private static String[] decodeCursor(String cursor) {
        if (blank(cursor)) {
            return null;
        }
        String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", 3);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("bad cursor");
        }
        return parts;
    }

    private static String binding(String scopeType, Long scopeId, long epoch, long revision) {
        return (scopeType == null ? "ALL" : scopeType) + ':' + (scopeId == null ? 0 : scopeId) + ':' + epoch + ':'
                + revision;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 建议的 409 metadata：只有版本与状态这类元信息，正文不进错误响应。 */
    private static Map<String, Object> proposalMeta(ProposalRow row) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("proposalId", row.id());
        meta.put("version", row.version());
        meta.put("status", row.status());
        meta.put("operation", row.operation());
        meta.put("targetMemoryId", row.targetMemoryId());
        meta.put("targetVersion", row.targetVersion());
        meta.put("expiresAt", row.expiresAt());
        return meta;
    }

    private static Map<String, Object> body(String proposalId, String status, String memoryId, int version,
                                            boolean changed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposalId);
        body.put("status", status);
        body.put("memoryId", memoryId);
        body.put("version", version);
        body.put("changed", changed);
        return body;
    }

    /** 幂等台账与事务内登记：同一 operationId 只生效一次，重放直接回当初的元信息。 */
    private MemoryApiResult begin(String operationId, String action, String targetId) {
        if (operationId == null) {
            return null;
        }
        try {
            MemoryOperationRepository.StartResult started = operations.start(operationId, action, targetId,
                    MemoryTimes.now());
            return switch (started.outcome()) {
                case REPLAY -> {
                    Map<String, Object> replayed = new LinkedHashMap<>(replayBody(started.resultMeta()));
                    replayed.put("replayed", true);
                    yield MemoryApiResult.of(200, replayed);
                }
                case IN_FLIGHT -> MemoryApiResult.error(409, "MEMORY_OPERATION_IN_FLIGHT", "同一操作正在进行中",
                        true, null);
                case CONFLICT -> MemoryApiResult.error(409, "MEMORY_OPERATION_ID_CONFLICT",
                        "operationId 已被别的动作或内容占用");
                case NEW -> null;
            };
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    private void complete(Connection connection, String operationId, Map<String, Object> body, String asOf)
            throws SQLException {
        if (operationId != null) {
            operations.completeIn(connection, operationId, serialize(body), asOf);
        }
    }

    private void abandon(String operationId) {
        if (operationId == null) {
            return;
        }
        try {
            operations.abandon(operationId);
        } catch (SQLException error) {
            log.warn("operationId 登记回滚失败: {}", error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> replayBody(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
    }

    private String serialize(Map<String, Object> body) throws SQLException {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new SQLException("审阅响应无法登记", error);
        }
    }

    private static boolean after(String timestamp, String asOf) throws SQLException {
        return MemoryTimes.parseMillis(timestamp) > MemoryTimes.parseMillis(asOf);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || "null".equals(value);
    }

    private static MemoryApiResult locked() {
        return MemoryApiResult.error(MemoryVaultService.HTTP_LOCKED, "MEMORY_LOCKED",
                "记忆库未解锁或已锁定，需要重新解锁", true, null);
    }

    private static MemoryApiResult cryptoFailure(MemoryCryptoException error) {
        return switch (error.code()) {
            case "MEMORY_LOCKED" -> locked();
            case "MEMORY_CIPHER_CORRUPT" -> MemoryApiResult.error(500, error.code(), "这条建议的密文不可用，已按隔离处理");
            default -> MemoryApiResult.error(500, error.code(), "记忆内容加解密失败，旧值保持不变");
        };
    }

    private static MemoryApiResult storageError(Exception error) {
        log.warn("记忆审阅存储层失败: {}", error.getMessage());
        return MemoryApiResult.error(500, "MEMORY_STORAGE_UNAVAILABLE", "记忆存储层暂时不可用");
    }
}
