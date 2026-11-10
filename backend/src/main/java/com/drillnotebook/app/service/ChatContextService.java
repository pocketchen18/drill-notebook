package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryAiWriter;
import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.drillnotebook.app.service.memory.MemoryIndexService;
import com.drillnotebook.app.service.memory.MemoryIntent;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemorySensitivity;
import com.drillnotebook.app.service.memory.MemoryTimes;
import com.drillnotebook.app.service.memory.MemoryValidator;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上下文计划的唯一入口（执行方案 §9.2、contracts §4.5）：
 * {@code projection → assemble → fit → 冻结}，普通与流式两条路径共用同一个产物。
 *
 * <p>v2 只发本轮输入，历史由服务端从"有效检查点 + 有界尾部"构建；v1 完全不走这里，
 * 保持客户端自带 messages 的旧语义（§7.1 一个版本周期）。</p>
 */
@Service
public class ChatContextService {
    private static final Logger log = LoggerFactory.getLogger(ChatContextService.class);
    /** 单次记忆选择的总时限（§9.1-7）：超时按"本轮不使用记忆"降级，不拖住聊天。 */
    static final int MEMORY_DEADLINE_MILLIS = 100;
    public static final int MEMORY_TOOL_LIMIT = 5;
    public static final int MAX_CREATE_TEXT_CODE_POINTS = 1000;
    private static final Set<String> CREATE_KINDS = Set.of("PREFERENCE", "GOAL", "BACKGROUND", "TASK");

    /** 计划身份与版本快照：发送前复核就比这几个数。 */
    public record Snapshot(long historyRevision, long contextGeneration, Long summaryRevision, Long vaultEpoch,
                           Long vaultRevision, long policyRevision) {
    }

    public enum MemoryLookupStatus {
        OK, NO_MATCH, UNAVAILABLE, STALE, LIMIT_REACHED, INVALID_ARGUMENT
    }

    public record MemoryLookup(MemoryLookupStatus status, ContextMemorySource.Availability availability,
                               List<ContextMemorySource.Fact> facts, boolean incomplete) {
        public MemoryLookup {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        static MemoryLookup stale() {
            return new MemoryLookup(MemoryLookupStatus.STALE, null, List.of(), false);
        }

        static MemoryLookup unavailable(ContextMemorySource.Availability availability, boolean incomplete) {
            return new MemoryLookup(MemoryLookupStatus.UNAVAILABLE, availability, List.of(), incomplete);
        }
    }

    public record MemoryListing(MemoryLookupStatus status, ContextMemorySource.Availability availability,
                                List<ContextMemorySource.ListedItem> items, boolean incomplete) {
        public MemoryListing {
            items = items == null ? List.of() : List.copyOf(items);
        }

        static MemoryListing stale() {
            return new MemoryListing(MemoryLookupStatus.STALE, null, List.of(), false);
        }

        static MemoryListing unavailable(ContextMemorySource.Availability availability, boolean incomplete) {
            return new MemoryListing(MemoryLookupStatus.UNAVAILABLE, availability, List.of(), incomplete);
        }

        static MemoryListing invalid() {
            return new MemoryListing(MemoryLookupStatus.INVALID_ARGUMENT, null, List.of(), false);
        }
    }

    public enum MemoryWriteStatus {
        CREATED, UPDATED, DUPLICATE, NO_CHANGE, NOT_FOUND, AMBIGUOUS, SUPPRESSED, UNAVAILABLE, STALE,
        INVALID_ARGUMENT, LIMIT_REACHED, REJECTED
    }

    /** 写入结果只带定位信息；不回显正文，也不暴露内部谓词与来源密文。 */
    public record MemoryWrite(MemoryWriteStatus status, String memoryId, Integer version,
                              ContextMemorySource.Availability availability, String reason) {
        static MemoryWrite created(String memoryId, int version) {
            return new MemoryWrite(MemoryWriteStatus.CREATED, memoryId, version, null, null);
        }

        static MemoryWrite duplicate(String memoryId, int version) {
            return new MemoryWrite(MemoryWriteStatus.DUPLICATE, memoryId, version, null, null);
        }

        static MemoryWrite updated(String memoryId, int version) {
            return new MemoryWrite(MemoryWriteStatus.UPDATED, memoryId, version, null, null);
        }

        static MemoryWrite noChange(String memoryId, int version) {
            return new MemoryWrite(MemoryWriteStatus.NO_CHANGE, memoryId, version, null, null);
        }

        static MemoryWrite notFound() {
            return new MemoryWrite(MemoryWriteStatus.NOT_FOUND, null, null, null, null);
        }

        static MemoryWrite ambiguous() {
            return new MemoryWrite(MemoryWriteStatus.AMBIGUOUS, null, null, null, null);
        }

        static MemoryWrite suppressed() {
            return new MemoryWrite(MemoryWriteStatus.SUPPRESSED, null, null, null, null);
        }

        static MemoryWrite stale() {
            return new MemoryWrite(MemoryWriteStatus.STALE, null, null, null, null);
        }

        static MemoryWrite invalid() {
            return new MemoryWrite(MemoryWriteStatus.INVALID_ARGUMENT, null, null, null, null);
        }

        static MemoryWrite unavailable(ContextMemorySource.Availability availability) {
            return new MemoryWrite(MemoryWriteStatus.UNAVAILABLE, null, null, availability, null);
        }

        static MemoryWrite rejected(String reason) {
            return new MemoryWrite(MemoryWriteStatus.REJECTED, null, null, null, reason);
        }
    }

    /** 授权通过后交给工具读取的作用域快照；status 非空表示授权失败。 */
    private record ToolAccess(MemoryLookupStatus status, ContextMemorySource.Availability availability,
                              String vaultId, String scopeType, long scopeId) {
        static ToolAccess failure(MemoryLookupStatus status, ContextMemorySource.Availability availability) {
            return new ToolAccess(status, availability, null, null, 0L);
        }
    }

    private final ConversationProjection projection;
    private final ChatContextAssembler assembler;
    private final ContextBudgeter budgeter;
    private final ContextMemorySource memories;
    private final ChatSessionMetadataRepository sessions;
    private final SessionContextRepository checkpoints;
    private final MemoryRepository memory;
    private final MemoryPolicyService policy;
    private final ChatHistoryRepository history;
    private final MemoryVaultService vault;
    private final MemoryCryptoService crypto;
    private final MemoryAiWriter aiWriter;
    private final MemoryItemService items;
    private final MemoryIndexService indexes;

    public ChatContextService(ConversationProjection projection, ChatContextAssembler assembler,
                              ContextBudgeter budgeter, ContextMemorySource memories,
                              ChatSessionMetadataRepository sessions, SessionContextRepository checkpoints,
                              MemoryRepository memory, MemoryPolicyService policy, ChatHistoryRepository history,
                              MemoryVaultService vault, MemoryCryptoService crypto, MemoryAiWriter aiWriter,
                              MemoryItemService items, MemoryIndexService indexes) {
        this.projection = projection;
        this.assembler = assembler;
        this.budgeter = budgeter;
        this.memories = memories;
        this.sessions = sessions;
        this.checkpoints = checkpoints;
        this.memory = memory;
        this.policy = policy;
        this.history = history;
        this.vault = vault;
        this.crypto = crypto;
        this.aiWriter = aiWriter;
        this.items = items;
        this.indexes = indexes;
    }

    /**
     * 构建本轮计划。
     *
     * @param hits 已经检索好的笔记片段（由调用方持有检索入口，本类不重复查询）
     */
    public ChatContextPlan plan(ChatTurnService.Lease lease, String userText, List<RetrievalHit> hits,
                                Map<String, Object> pageContext, Map<String, Object> memoryOptions,
                                int contextWindow, boolean windowKnown) throws SQLException {
        return plan(lease, userText, hits, pageContext, memoryOptions, contextWindow, windowKnown,
                ContextBudgeter.OUTPUT_RESERVE_TOKENS);
    }

    public ChatContextPlan plan(ChatTurnService.Lease lease, String userText, List<RetrievalHit> hits,
                                Map<String, Object> pageContext, Map<String, Object> memoryOptions,
                                int contextWindow, boolean windowKnown, int outputReserve) throws SQLException {
        ChatSessionMetadataRepository.State state = sessions.read(lease.sessionId())
                .orElseThrow(() -> new ChatTurnRejectException(404, "SESSION_NOT_FOUND", "会话不存在", Map.of()));
        validatePageContext(pageContext, state);

        String planId = "plan-" + UuidV7.generate(System.currentTimeMillis());
        ContextAudit.Builder audit = new ContextAudit.Builder(planId, TokenEstimator.VERSION);
        ConversationProjection.Projection projected = projection.project(
                new ConversationProjection.Request(lease.sessionId(), lease.turnId(), state.contextGeneration(),
                        lease.masterPassword(), ChatContextAssembler.RECENT_TURNS_BUDGET), audit);

        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        long policyRevision = policyRevision();
        boolean allowed = memoriesAllowed(memoryOptions);
        ContextMemorySource.Selection selection = allowed
                ? memories.select(envelope.map(MemoryRepository.VaultEnvelope::vaultId).orElse(null),
                        state.memoryScopeType(), state.memoryNotebookId() == null ? 0L : state.memoryNotebookId(),
                        userText, System.currentTimeMillis(), MEMORY_DEADLINE_MILLIS)
                : ContextMemorySource.Selection.EMPTY;

        ChatContextAssembler.Draft draft = assembler.assemble(
                new ChatContextAssembler.Input(userText, lease.masterPassword(), lease.attachments(), pageContext,
                        pageVersion(pageContext), allowed), projected, selection, hits, audit);
        ContextBudgeter.Target target = new ContextBudgeter.Target(planId, lease.sessionId(),
                state.contextGeneration(), projected.historyRevision(),
                // 复核比的是"观测到的检查点版本"而不是"实际采用的摘要"：无效/锁定摘要降级后
                // matches() 不该误报 CONTEXT_CHANGED，但检查点真的被替换时仍要发现（§4.5）
                projected.observedSummaryRevision(),
                envelope.map(MemoryRepository.VaultEnvelope::epoch).orElse(null),
                envelope.map(MemoryRepository.VaultEnvelope::revision).orElse(null),
                policyRevision, contextWindow, windowKnown, outputReserve);
        return budgeter.fit(draft, projected, target, audit);
    }

    /**
     * 发送前复核（§4.5）：generation、历史/摘要版本、库与授权版本任一变化都要丢掉旧计划，
     * 返回可重试的 CONTEXT_CHANGED，而不是把旧 metadata 绑到新请求上。
     */
    public boolean matches(ChatContextPlan plan) {
        try {
            Snapshot current = snapshot(plan.sessionId());
            return current.historyRevision() == plan.historyRevision()
                    && current.contextGeneration() == plan.contextGeneration()
                    && java.util.Objects.equals(current.summaryRevision(), plan.summaryRevision())
                    && java.util.Objects.equals(current.vaultEpoch(), plan.vaultEpoch())
                    && java.util.Objects.equals(current.vaultRevision(), plan.vaultRevision())
                    && current.policyRevision() == plan.policyRevision();
        } catch (SQLException error) {
            return false;
        }
    }

    /**
     * 模型只读工具入口：策略、授权、当前作用域与库版本都必须在服务边界内重新取，
     * 不能相信调用方传来的计划副本或参数。
     */
    public MemoryLookup readMemoriesForTool(ChatContextPlan plan, boolean memoryUseRequested, String query) {
        if (plan == null || !matches(plan)) return MemoryLookup.stale();
        if (!memoryUseRequested) {
            return MemoryLookup.unavailable(ContextMemorySource.Availability.NOT_REQUESTED, false);
        }
        if (query == null || query.isBlank()) {
            return new MemoryLookup(MemoryLookupStatus.INVALID_ARGUMENT, null, List.of(), false);
        }
        try {
            ToolAccess access = authorizeToolRead(plan);
            if (access.status() != null) return lookupFailure(access);
            ContextMemorySource.Selection selection = memories.select(access.vaultId(), access.scopeType(),
                    access.scopeId(), query, System.currentTimeMillis(), MEMORY_DEADLINE_MILLIS);
            if (!matches(plan)) return MemoryLookup.stale();
            boolean incomplete = selection.truncated() || selection.corruptRows() > 0;
            if (selection.availability() != ContextMemorySource.Availability.READY) {
                return MemoryLookup.unavailable(selection.availability(), incomplete);
            }
            List<ContextMemorySource.Fact> facts = new ArrayList<>(selection.topical());
            facts.addAll(selection.style());
            if (facts.size() > MEMORY_TOOL_LIMIT) {
                facts = new ArrayList<>(facts.subList(0, MEMORY_TOOL_LIMIT));
            }
            if (facts.isEmpty()) {
                return new MemoryLookup(MemoryLookupStatus.NO_MATCH, ContextMemorySource.Availability.READY,
                        facts, incomplete);
            }
            MemoryLookupStatus status = facts.size() >= MEMORY_TOOL_LIMIT
                    && selection.candidatesSeen() > facts.size()
                    ? MemoryLookupStatus.LIMIT_REACHED : MemoryLookupStatus.OK;
            return new MemoryLookup(status, ContextMemorySource.Availability.READY, facts, incomplete);
        } catch (SQLException error) {
            return MemoryLookup.unavailable(ContextMemorySource.Availability.LOCKED, false);
        }
    }

    /**
     * 模型只读工具入口：按当前会话可见范围列出记忆标题。scope 只接受 GLOBAL / NOTEBOOK / ALL，
     * NOTEBOOK 仅在会话本身是笔记本作用域时合法；越权请求明确报 INVALID_ARGUMENT。
     */
    public MemoryListing listMemoriesForTool(ChatContextPlan plan, boolean memoryUseRequested, int limit,
                                             String scope) {
        if (plan == null || !matches(plan)) return MemoryListing.stale();
        if (!memoryUseRequested) {
            return MemoryListing.unavailable(ContextMemorySource.Availability.NOT_REQUESTED, false);
        }
        String normalizedScope = scope == null || scope.isBlank() ? "ALL" : scope.trim().toUpperCase(Locale.ROOT);
        if (!"GLOBAL".equals(normalizedScope) && !"NOTEBOOK".equals(normalizedScope)
                && !"ALL".equals(normalizedScope)) {
            return MemoryListing.invalid();
        }
        try {
            ToolAccess access = authorizeToolRead(plan);
            if (access.status() != null) return listingFailure(access);
            if ("NOTEBOOK".equals(normalizedScope) && !"NOTEBOOK".equals(access.scopeType())) {
                return MemoryListing.invalid();
            }
            ContextMemorySource.Listing listing = memories.list(access.vaultId(), access.scopeType(),
                    access.scopeId(), normalizedScope, System.currentTimeMillis(), MEMORY_DEADLINE_MILLIS, limit);
            if (!matches(plan)) return MemoryListing.stale();
            boolean incomplete = listing.hasMore() || listing.truncated() || listing.corruptRows() > 0;
            if (listing.availability() != ContextMemorySource.Availability.READY) {
                return MemoryListing.unavailable(listing.availability(), incomplete);
            }
            if (listing.items().isEmpty()) {
                return new MemoryListing(MemoryLookupStatus.NO_MATCH, ContextMemorySource.Availability.READY,
                        listing.items(), incomplete);
            }
            MemoryLookupStatus status = listing.hasMore() || listing.truncated()
                    ? MemoryLookupStatus.LIMIT_REACHED : MemoryLookupStatus.OK;
            return new MemoryListing(status, ContextMemorySource.Availability.READY, listing.items(), incomplete);
        } catch (SQLException error) {
            return MemoryListing.unavailable(ContextMemorySource.Availability.LOCKED, false);
        }
    }

    /**
     * 模型写入工具入口：当前会话可见范围、用户授权、额度与库版本都重新判定。
     * 成功写入会推进 vault revision；调用方随后必须用 {@link #refreshAfterMemoryWrite} 更新本轮计划，
     * 否则下一次工具调用或下一次上游请求会把"自己刚写的版本"误判成 STALE。
     */
    public MemoryWrite createMemoryForTool(ChatContextPlan plan, boolean memoryUseRequested, String text,
                                           String kind, String scope, String expiry, String userText,
                                           long turnId, long userMessageId) {
        if (plan == null || !matches(plan)) return MemoryWrite.stale();
        if (!memoryUseRequested) {
            return MemoryWrite.unavailable(ContextMemorySource.Availability.NOT_REQUESTED);
        }
        String value = text == null ? "" : text.strip();
        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_CREATE_TEXT_CODE_POINTS
                || !CREATE_KINDS.contains(normalizedKind)) {
            return MemoryWrite.invalid();
        }
        String validUntil;
        try {
            validUntil = normalizeExpiry(expiry);
        } catch (RuntimeException error) {
            return MemoryWrite.invalid();
        }
        try {
            ToolAccess access = authorizeToolRead(plan);
            if (access.status() != null) {
                return access.status() == MemoryLookupStatus.STALE ? MemoryWrite.stale()
                        : MemoryWrite.unavailable(access.availability());
            }
            String targetScope = access.scopeType();
            long targetId = access.scopeId();
            String requestedScope = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
            if (!requestedScope.isEmpty()) {
                if ("GLOBAL".equals(requestedScope)) {
                    targetScope = "GLOBAL";
                    targetId = 0L;
                } else if ("NOTEBOOK".equals(requestedScope)) {
                    if (!"NOTEBOOK".equals(access.scopeType())) return MemoryWrite.invalid();
                } else {
                    return MemoryWrite.invalid();
                }
            }
            MemoryPolicyService.Eligibility eligibility = policy.aiWriteEligibility(targetScope, targetId);
            if (!eligibility.allowed()) {
                return switch (eligibility.gate()) {
                    case QUOTA_ACTIVE_FULL, QUOTA_PENDING_FULL -> new MemoryWrite(MemoryWriteStatus.LIMIT_REACHED,
                            null, null, null, null);
                    case SCOPE_INVALID -> MemoryWrite.invalid();
                    case LOCKED, VAULT_MISSING -> MemoryWrite.unavailable(ContextMemorySource.Availability.LOCKED);
                    default -> MemoryWrite.unavailable(ContextMemorySource.Availability.DENIED);
                };
            }
            if (!matches(plan)) return MemoryWrite.stale();
            MemoryKey key = vault.requireKey();
            String asOf = MemoryTimes.now();
            MemoryAiWriter.Outcome outcome = aiWriter.create(key, new MemoryAiWriter.Input(targetScope, targetId,
                    normalizedKind, value, validUntil, plan.sessionId(), turnId, userMessageId, userText, asOf,
                    MemoryIntent.explicitRememberRequest(userText)));
            if (outcome.releasedSuppression()) {
                // 解除家族抑制不推进 revision，但会让既有行重新可见；必须同步换一份快照
                rebuildIndexAfterWrite();
            }
            return switch (outcome.status()) {
                case CREATED -> {
                    if (!outcome.releasedSuppression()) rebuildIndexAfterWrite();
                    yield MemoryWrite.created(outcome.memoryId(), outcome.version());
                }
                case DUPLICATE -> MemoryWrite.duplicate(outcome.memoryId(), outcome.version());
                case SUPPRESSED -> MemoryWrite.suppressed();
                case REJECTED_CREDENTIAL -> MemoryWrite.rejected("CREDENTIAL");
                case REJECTED_SENSITIVE -> MemoryWrite.rejected("SENSITIVE");
                case FAMILY_CONFLICT -> MemoryWrite.rejected("FAMILY_CONFLICT");
            };
        } catch (MemoryCryptoException error) {
            return MemoryWrite.unavailable(ContextMemorySource.Availability.LOCKED);
        } catch (SQLException error) {
            log.warn("对话内写入记忆失败: {}", error.getClass().getSimpleName());
            return MemoryWrite.unavailable(null);
        }
    }

    /**
     * 模型更新工具入口。定位有两种方式：用 {@code currentText} 在当前会话可见范围内按归一值找，
     * 或用 {@code memoryId + expectedVersion} 精确指定。后者是 plan 的 CAS 契约，前者让模型能直接用
     * 检索/列表里看到的正文定位；两者都在服务端重新校验作用域，不允许跨笔记本移动。
     */
    public MemoryWrite updateMemoryForTool(ChatContextPlan plan, boolean memoryUseRequested, String memoryId,
                                           Integer expectedVersion, String currentText, String text, String expiry) {
        if (plan == null || !matches(plan)) return MemoryWrite.stale();
        if (!memoryUseRequested) {
            return MemoryWrite.unavailable(ContextMemorySource.Availability.NOT_REQUESTED);
        }
        String newValue = text == null ? "" : text.strip();
        boolean byText = currentText != null && !currentText.isBlank();
        boolean byId = memoryId != null && !memoryId.isBlank();
        if (newValue.isEmpty() || newValue.codePointCount(0, newValue.length()) > MAX_CREATE_TEXT_CODE_POINTS
                || byText == byId || (byId && (expectedVersion == null || expectedVersion <= 0))
                || (byText && expectedVersion != null)) {
            return MemoryWrite.invalid();
        }
        String validUntil;
        try {
            validUntil = normalizeExpiry(expiry);
        } catch (RuntimeException error) {
            return MemoryWrite.invalid();
        }
        try {
            ToolAccess access = authorizeToolRead(plan);
            if (access.status() != null) {
                return access.status() == MemoryLookupStatus.STALE ? MemoryWrite.stale()
                        : MemoryWrite.unavailable(access.availability());
            }
            MemoryKey key = vault.requireKey();
            UpdateTarget target = byId
                    ? resolveUpdateTarget(access, memoryId.strip(), expectedVersion)
                    : resolveUpdateTarget(access, key, currentText.strip());
            if (target.failure() != null) return target.failure();
            MemoryApiResult current = items.get(target.memoryId());
            if (current.status() != 200) return mapEditFailure(current);
            Object rawValue = current.body().get("value");
            Object rawVersion = current.body().get("version");
            if (rawValue == null || !(rawVersion instanceof Number)) {
                return MemoryWrite.unavailable(null);
            }
            int currentVersion = ((Number) rawVersion).intValue();
            if (currentVersion != target.expectedVersion()) {
                return MemoryWrite.stale();
            }
            String normalizedNew = MemoryValidator.normalizeValueText(newValue);
            String normalizedCurrent = MemoryValidator.normalizeValueText(String.valueOf(rawValue));
            if (normalizedNew != null && normalizedNew.equals(normalizedCurrent)) {
                return MemoryWrite.noChange(target.memoryId(), currentVersion);
            }
            MemorySensitivity.Level sensitivity = MemorySensitivity.classify(newValue);
            if (sensitivity == MemorySensitivity.Level.CREDENTIAL) {
                return MemoryWrite.rejected("CREDENTIAL");
            }
            if (sensitivity == MemorySensitivity.Level.SENSITIVE) {
                return MemoryWrite.rejected("SENSITIVE");
            }
            MemoryApiResult edited = items.editFromAi(target.memoryId(), new MemoryItemService.EditInput(
                    target.expectedVersion(), null, null, newValue, validUntil, null), MAX_CREATE_TEXT_CODE_POINTS);
            if (edited.status() != 200) return mapEditFailure(edited);
            Object editedVersion = edited.body().get("version");
            rebuildIndexAfterWrite();
            return MemoryWrite.updated(target.memoryId(), editedVersion instanceof Number number
                    ? number.intValue() : currentVersion + 1);
        } catch (MemoryCryptoException error) {
            return MemoryWrite.unavailable(ContextMemorySource.Availability.LOCKED);
        } catch (SQLException error) {
            log.warn("对话内更新记忆失败: {}", error.getClass().getSimpleName());
            return MemoryWrite.unavailable(null);
        }
    }

    private record UpdateTarget(String memoryId, int expectedVersion, MemoryWrite failure) {
        static UpdateTarget found(String memoryId, int version) {
            return new UpdateTarget(memoryId, version, null);
        }

        static UpdateTarget failed(MemoryWrite failure) {
            return new UpdateTarget(null, 0, failure);
        }
    }

    private UpdateTarget resolveUpdateTarget(ToolAccess access, String memoryId, int expectedVersion)
            throws SQLException {
        MemoryRepository.FactRow row = memory.findCurrentAnyStatus(access.vaultId(), memoryId).orElse(null);
        if (row == null || !accessibleScope(access, row.scopeType(), row.scopeId())) {
            return UpdateTarget.failed(MemoryWrite.notFound());
        }
        if (!editableRow(row)) {
            return UpdateTarget.failed(MemoryWrite.stale());
        }
        if (row.version() != expectedVersion) {
            return UpdateTarget.failed(MemoryWrite.stale());
        }
        return UpdateTarget.found(row.memoryId(), expectedVersion);
    }

    private UpdateTarget resolveUpdateTarget(ToolAccess access, MemoryKey key, String currentText)
            throws SQLException, MemoryCryptoException {
        List<MemoryRepository.FactRow> matches = new ArrayList<>();
        for (ScopePoint scope : visibleScopes(access)) {
            for (String candidate : valueHmacCandidates(currentText)) {
                String hmac = crypto.hmac(key, "value:" + candidate);
                for (MemoryRepository.FactRow row : memory.findCurrentByValueHmacInScope(access.vaultId(),
                        scope.scopeType(), scope.scopeId(), hmac)) {
                    if (editableRow(row)) matches.add(row);
                }
            }
        }
        // 同一正文可能同时出现在本册与 GLOBAL：没有 scope 参数时不能替用户猜，交给模型说明歧义
        if (matches.size() > 1) return UpdateTarget.failed(MemoryWrite.ambiguous());
        if (matches.size() == 1) {
            MemoryRepository.FactRow row = matches.get(0);
            return UpdateTarget.found(row.memoryId(), row.version());
        }
        return UpdateTarget.failed(MemoryWrite.notFound());
    }

    /** 与 MemoryItemService/提取链相同的查找键前缀；值候选按原样、折叠空白、归一值依次尝试。 */
    private static List<String> valueHmacCandidates(String currentText) {
        List<String> candidates = new ArrayList<>();
        String trimmed = currentText.strip();
        addCandidate(candidates, trimmed);
        addCandidate(candidates, trimmed.replaceAll("\\s+", " "));
        addCandidate(candidates, MemoryValidator.normalizeValueText(trimmed));
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String value) {
        if (value != null && !value.isEmpty() && !candidates.contains(value)) {
            candidates.add(value);
        }
    }

    private record ScopePoint(String scopeType, long scopeId) {
    }

    private static List<ScopePoint> visibleScopes(ToolAccess access) {
        if ("NOTEBOOK".equals(access.scopeType())) {
            return List.of(new ScopePoint("NOTEBOOK", access.scopeId()), new ScopePoint("GLOBAL", 0L));
        }
        return List.of(new ScopePoint("GLOBAL", 0L));
    }

    private static boolean accessibleScope(ToolAccess access, String scopeType, long scopeId) {
        if ("GLOBAL".equals(scopeType)) {
            return scopeId == 0L;
        }
        return "NOTEBOOK".equals(access.scopeType()) && scopeId == access.scopeId();
    }

    private static boolean editableStatus(String status) {
        return MemoryRepository.STATUS_ACTIVE.equals(status) || "DISABLED".equals(status);
    }

    /** 已过到期时间的记忆不再可更新：否则用户纠正一条过期事实会把它"复活"。 */
    private static boolean editableRow(MemoryRepository.FactRow row) {
        if (!editableStatus(row.status())) return false;
        long expiresAt = MemoryTimes.parseMillis(row.validUntil());
        return expiresAt == 0L || expiresAt > System.currentTimeMillis();
    }

    private MemoryWrite mapEditFailure(MemoryApiResult result) {
        Object codeValue = result.body().get("code");
        String code = codeValue == null ? "" : String.valueOf(codeValue);
        return switch (code) {
            case "MEMORY_VERSION_CONFLICT", "MEMORY_STATUS_FROZEN" -> MemoryWrite.stale();
            case "MEMORY_ITEM_NOT_FOUND" -> MemoryWrite.notFound();
            case "MEMORY_FAMILY_CONFLICT" -> MemoryWrite.rejected("FAMILY_CONFLICT");
            case "MEMORY_SENSITIVE_CREDENTIAL" -> MemoryWrite.rejected("CREDENTIAL");
            case "MEMORY_LOCKED" -> MemoryWrite.unavailable(ContextMemorySource.Availability.LOCKED);
            case "MEMORY_VALUE_INVALID", "MEMORY_PREDICATE_INVALID", "MEMORY_EXPIRY_INVALID",
                    "MEMORY_EXPIRY_IN_PAST", "MEMORY_NOTHING_TO_EDIT", "MEMORY_VERSION_REQUIRED" ->
                    MemoryWrite.invalid();
            default -> result.status() == MemoryVaultService.HTTP_LOCKED
                    ? MemoryWrite.unavailable(ContextMemorySource.Availability.LOCKED)
                    : result.status() == 400 ? MemoryWrite.invalid()
                    : MemoryWrite.unavailable(null);
        };
    }

    /**
     * 写入成功后只更新 vault 快照；历史、摘要、generation、策略版本任一变化都不在这里掩盖，
     * 交给发送前复核按既有语义返回 CONTEXT_CHANGED。
     */
    public ChatContextPlan refreshAfterMemoryWrite(ChatContextPlan plan) {
        if (plan == null) return null;
        try {
            Snapshot current = snapshot(plan.sessionId());
            if (current.historyRevision() != plan.historyRevision()
                    || current.contextGeneration() != plan.contextGeneration()
                    || !Objects.equals(current.summaryRevision(), plan.summaryRevision())
                    || current.policyRevision() != plan.policyRevision()) {
                return plan;
            }
            return plan.withVaultSnapshot(current.vaultEpoch(), current.vaultRevision());
        } catch (SQLException | RuntimeException error) {
            return plan;
        }
    }

    /** 同步换入新快照，让同轮后续检索能看到刚写入的版本；失败只降级为 BUILDING，不影响写入成功。 */
    private void rebuildIndexAfterWrite() {
        try {
            Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
            if (envelope.isEmpty()) return;
            MemoryRepository.VaultEnvelope current = envelope.get();
            indexes.buildNow(current.vaultId(), current.epoch(), current.revision());
        } catch (RuntimeException | SQLException error) {
            log.warn("写入后重建记忆索引失败: {}", error.getClass().getSimpleName());
        }
    }

    private static String normalizeExpiry(String expiry) {
        if (expiry == null) return null;
        String trimmed = expiry.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("expiry 不能为空串");
        }
        Instant instant = Instant.parse(trimmed);
        if (instant.isBefore(Instant.now().minusSeconds(60))) {
            throw new IllegalArgumentException("expiry 不能是过去");
        }
        return MemoryTimes.format(instant);
    }

    /** 策略、会话 generation、库版本与作用域的共享授权链；调用前已确认 plan 与开关。 */
    private ToolAccess authorizeToolRead(ChatContextPlan plan) throws SQLException {
        MemoryPolicyService.Eligibility eligibility = policy.useEligibility();
        if (!eligibility.allowed()) {
            boolean locked = eligibility.gate() == MemoryPolicyService.Gate.LOCKED
                    || eligibility.gate() == MemoryPolicyService.Gate.VAULT_MISSING;
            return ToolAccess.failure(MemoryLookupStatus.UNAVAILABLE, locked
                    ? ContextMemorySource.Availability.LOCKED
                    : ContextMemorySource.Availability.DENIED);
        }
        ChatSessionMetadataRepository.State state = sessions.read(plan.sessionId()).orElse(null);
        if (state == null || state.contextGeneration() != plan.contextGeneration()) {
            return ToolAccess.failure(MemoryLookupStatus.STALE, null);
        }
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        if (envelope.isEmpty()) {
            return ToolAccess.failure(MemoryLookupStatus.UNAVAILABLE, ContextMemorySource.Availability.LOCKED);
        }
        MemoryRepository.VaultEnvelope current = envelope.get();
        if (!Objects.equals(plan.vaultEpoch(), current.epoch())
                || !Objects.equals(plan.vaultRevision(), current.revision())) {
            return ToolAccess.failure(MemoryLookupStatus.STALE, null);
        }
        return new ToolAccess(null, null, current.vaultId(), state.memoryScopeType(),
                state.memoryNotebookId() == null ? 0L : state.memoryNotebookId());
    }

    private static MemoryLookup lookupFailure(ToolAccess access) {
        return access.status() == MemoryLookupStatus.STALE
                ? MemoryLookup.stale()
                : MemoryLookup.unavailable(access.availability(), false);
    }

    private static MemoryListing listingFailure(ToolAccess access) {
        return access.status() == MemoryLookupStatus.STALE
                ? MemoryListing.stale()
                : MemoryListing.unavailable(access.availability(), false);
    }

    public MemoryLookup readMemoriesForTool(ChatContextPlan plan, String query, boolean memoryUseRequested) {
        return readMemoriesForTool(plan, memoryUseRequested, query);
    }

    /** 当前版本快照：检查器与发送前复核共用一个口径。 */
    public Snapshot snapshot(long sessionId) throws SQLException {
        ChatSessionMetadataRepository.State state = sessions.read(sessionId).orElseThrow();
        Optional<SessionContextRepository.Checkpoint> checkpoint = checkpoints.read(sessionId);
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        return new Snapshot(state.historyRevision(), state.contextGeneration(),
                checkpoint.map(SessionContextRepository.Checkpoint::revision).orElse(null),
                envelope.map(MemoryRepository.VaultEnvelope::epoch).orElse(null),
                envelope.map(MemoryRepository.VaultEnvelope::revision).orElse(null),
                policyRevision());
    }

    /** 本次是否使用记忆：请求开关与"使用记忆"授权是两个条件，缺一不可（S04/S05）。 */
    private boolean memoriesAllowed(Map<String, Object> memoryOptions) {
        if (memoryOptions == null || !Boolean.TRUE.equals(memoryOptions.get("use"))) return false;
        try {
            return policy.useEligibility().allowed();
        } catch (SQLException error) {
            return false;
        }
    }

    /** 页面上下文必须与会话作用域一致（contracts §4.1）：不接受越界的 pageId，也不拿它当权威正文。 */
    private void validatePageContext(Map<String, Object> pageContext, ChatSessionMetadataRepository.State state)
            throws SQLException {
        if (pageContext == null || pageContext.get("pageId") == null) return;
        long pageId;
        try {
            pageId = Long.parseLong(String.valueOf(pageContext.get("pageId")));
        } catch (NumberFormatException error) {
            throw new ChatTurnRejectException(422, "PAGE_CONTEXT_INVALID", "pageContext.pageId 必须是数字", Map.of());
        }
        Optional<Long> notebookId = history.notebookIdOfPage(pageId);
        if (notebookId.isEmpty()) {
            throw new ChatTurnRejectException(422, "PAGE_CONTEXT_INVALID", "pageContext.pageId 不存在", Map.of());
        }
        if ("NOTEBOOK".equals(state.memoryScopeType())
                && !notebookId.get().equals(state.memoryNotebookId())) {
            throw new ChatTurnRejectException(422, "PAGE_CONTEXT_MISMATCH",
                    "pageContext 与当前会话的记忆作用域不是同一本笔记本", Map.of());
        }
    }

    /** 页面正文的权威版本（note_page.content_hash），没有就给 null——检查器据此核验预览。 */
    private String pageVersion(Map<String, Object> pageContext) throws SQLException {
        if (pageContext == null || pageContext.get("pageId") == null) return null;
        long pageId = Long.parseLong(String.valueOf(pageContext.get("pageId")));
        return history.pageContentHash(pageId).orElse(null);
    }

    private long policyRevision() throws SQLException {
        return policy.current().revision();
    }
}
