package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    /** 单次记忆选择的总时限（§9.1-7）：超时按"本轮不使用记忆"降级，不拖住聊天。 */
    static final int MEMORY_DEADLINE_MILLIS = 100;
    public static final int MEMORY_TOOL_LIMIT = 5;

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

    private final ConversationProjection projection;
    private final ChatContextAssembler assembler;
    private final ContextBudgeter budgeter;
    private final ContextMemorySource memories;
    private final ChatSessionMetadataRepository sessions;
    private final SessionContextRepository checkpoints;
    private final MemoryRepository memory;
    private final MemoryPolicyService policy;
    private final ChatHistoryRepository history;

    public ChatContextService(ConversationProjection projection, ChatContextAssembler assembler,
                              ContextBudgeter budgeter, ContextMemorySource memories,
                              ChatSessionMetadataRepository sessions, SessionContextRepository checkpoints,
                              MemoryRepository memory, MemoryPolicyService policy, ChatHistoryRepository history) {
        this.projection = projection;
        this.assembler = assembler;
        this.budgeter = budgeter;
        this.memories = memories;
        this.sessions = sessions;
        this.checkpoints = checkpoints;
        this.memory = memory;
        this.policy = policy;
        this.history = history;
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
                Integer.MAX_VALUE, ContextBudgeter.OUTPUT_RESERVE_TOKENS);
    }

    public ChatContextPlan plan(ChatTurnService.Lease lease, String userText, List<RetrievalHit> hits,
                                Map<String, Object> pageContext, Map<String, Object> memoryOptions,
                                int contextWindow, boolean windowKnown, int maxInputTokens, int outputReserve) throws SQLException {
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
                policyRevision, contextWindow, windowKnown, maxInputTokens, outputReserve);
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
            MemoryPolicyService.Eligibility eligibility = policy.useEligibility();
            if (!eligibility.allowed()) {
                return MemoryLookup.unavailable(ContextMemorySource.Availability.DENIED, false);
            }
            ChatSessionMetadataRepository.State state = sessions.read(plan.sessionId()).orElse(null);
            if (state == null || state.contextGeneration() != plan.contextGeneration()) return MemoryLookup.stale();
            Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
            if (envelope.isEmpty()) {
                return MemoryLookup.unavailable(ContextMemorySource.Availability.LOCKED, false);
            }
            MemoryRepository.VaultEnvelope current = envelope.get();
            if (!Objects.equals(plan.vaultEpoch(), current.epoch())
                    || !Objects.equals(plan.vaultRevision(), current.revision())) {
                return MemoryLookup.stale();
            }
            ContextMemorySource.Selection selection = memories.select(current.vaultId(), state.memoryScopeType(),
                    state.memoryNotebookId() == null ? 0L : state.memoryNotebookId(), query,
                    System.currentTimeMillis(), MEMORY_DEADLINE_MILLIS);
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
