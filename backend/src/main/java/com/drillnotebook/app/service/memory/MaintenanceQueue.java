package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.Backlog;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.ScanKind;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.State;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.EnqueueResult;
import com.drillnotebook.app.repository.MemoryJobRepository.JobDraft;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.drillnotebook.app.service.memory.MaintenanceSourceScanner.Loaded;
import com.drillnotebook.app.service.memory.MaintenanceSourceScanner.Request;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 维护排队（执行方案 §8.1、§8.5；contracts §2.1 尾段）：把会话上的"待处理边界"变成有界的任务行。
 *
 * <p>入队只存<b>水位与范围</b>，正文一律不进队列（§8.5「超过队列容量只保存水位，不把正文复制到队列」）。
 * 三条"不会白跑"的口径都在这里：</p>
 * <ul>
 *   <li>没有新内容不建任务；有内容时按数量分流，满阈值给 5 秒合并窗口、不足给 60 秒空闲触发
 *       （{@link MaintenanceRules#extractTrigger}），等待期间靠就地合并扩范围而不是另建一条；</li>
 *   <li>同一 {@code (kind, session)} 已有 QUEUED 行就地合并，已有 CLAIMED/RETRY/等待态行则等它结束；
 *       整批都是永久跳过的死源时直接推进水位，不留待办也不外发（§2.5）；</li>
 *   <li>摘要必须同时过压力、新增量与冷却三道门，manualFlush 只越过其中两道（§8.4 第 1 条、§8.5）。</li>
 * </ul>
 *
 * <p>开关、授权与库锁定沿用 {@link MemoryPolicyService} 的判定：不合格时<b>不建任务</b>，
 * 也不清除会话上的待处理标记——恢复授权后由水位重新入队。</p>
 */
@Service
public class MaintenanceQueue {
    /** 一次排队最多检查多少个待处理会话：其余留在唤醒标记上，下一轮再来。 */
    public static final int SESSIONS_PER_SWEEP = 20;

    private static final Logger log = LoggerFactory.getLogger(MaintenanceQueue.class);

    /** 入队统计与本轮检查过的会话：不含任何正文，会话 ID 交给 worker 收口唤醒标记。 */
    public record Sweep(int sessionsScanned, int queued, int merged, int deferred, int refusedCapacity,
                        List<Long> scannedSessionIds) {
    }

    private final ChatSessionMetadataRepository sessions;
    private final ChatHistoryRepository history;
    private final MemoryJobRepository jobs;
    private final MemoryPolicyService policy;
    private final MemoryVaultService vault;
    private final MemoryRepository memory;
    private final MaintenanceSourceScanner scanner;
    private final MaintenancePorts.Extractor extractor;
    private final MaintenancePorts.SummaryDrafter drafter;

    public MaintenanceQueue(ChatSessionMetadataRepository sessions, ChatHistoryRepository history,
                            MemoryJobRepository jobs, MemoryPolicyService policy, MemoryVaultService vault,
                            MemoryRepository memory, MaintenanceSourceScanner scanner,
                            MaintenancePorts.Extractor extractor, MaintenancePorts.SummaryDrafter drafter) {
        this.sessions = sessions;
        this.history = history;
        this.jobs = jobs;
        this.policy = policy;
        this.vault = vault;
        this.memory = memory;
        this.scanner = scanner;
        this.extractor = extractor;
        this.drafter = drafter;
    }

    /** 扫描所有待处理会话并按需入队。 */
    public Sweep sweep(long nowMillis) throws SQLException {
        List<Backlog> pending = sessions.findPendingMaintenance(SESSIONS_PER_SWEEP);
        if (pending.isEmpty()) {
            return new Sweep(0, 0, 0, 0, 0, List.of());
        }
        List<Long> scanned = pending.stream().map(Backlog::state).map(State::sessionId).toList();
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        Optional<MemoryCryptoService.MemoryKey> key = vault.currentKey();
        MemorySettingsRepository.Settings settings = policy.current();
        if (envelope.isEmpty() || key.isEmpty() || !policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE)
                .configured()) {
            // 没有库、没解锁、外发目标没配好：既不外发也不入队，待处理标记保留
            return new Sweep(pending.size(), 0, 0, 0, 0, scanned);
        }
        MemoryPolicyService.Target target = policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE);
        long historyFloor = policy.policyOf(settings).memoryHistoryFloor();
        String timezone = ZoneId.systemDefault().getId();
        int queued = 0;
        int merged = 0;
        int deferred = 0;
        int refused = 0;
        for (Backlog backlog : pending) {
            State state = backlog.state();
            if (!state.learnEnabled() || state.archivedAt() != null) {
                continue;
            }
            long scopeId = state.memoryNotebookId() == null ? 0L : state.memoryNotebookId();
            String sessionPassword = vault.sessionPassword(state.sessionId()).orElse(null);
            Counts extract = queueExtract(state, scopeId, backlog.maxTurnId(), envelope.get(), key.get(), target,
                    settings.revision(), historyFloor, timezone, sessionPassword, nowMillis);
            Counts summary = queueSummary(state, scopeId, backlog.maxTurnId(), envelope.get(), key.get(), target,
                    settings.revision(), historyFloor, timezone, sessionPassword, nowMillis);
            queued += extract.queued();
            merged += extract.merged();
            deferred += extract.deferred();
            refused += extract.refused();
            queued += summary.queued();
            merged += summary.merged();
            deferred += summary.deferred();
            refused += summary.refused();
        }
        return new Sweep(pending.size(), queued, merged, deferred, refused, scanned);
    }

    private Counts queueExtract(State state, long scopeId, long maxTurnId,
                                MemoryRepository.VaultEnvelope envelope, MemoryCryptoService.MemoryKey key,
                                MemoryPolicyService.Target target, long policyRevision, long historyFloor,
                                String timezone, String sessionPassword, long nowMillis) throws SQLException {
        if (!policy.extractionEligibility(state.memoryScopeType(), scopeId).allowed()) {
            return Counts.NONE;
        }
        long bound = pendingBound(state.sessionId(), maxTurnId);
        if (bound <= state.memoryScanAfterTurn()) {
            return Counts.NONE;
        }
        ChatHistoryRepository.MaintenanceBacklog backlog = history.maintenanceBacklog(state.sessionId(),
                state.memoryScanAfterTurn(), bound, state.contextGeneration(), historyFloor);
        MaintenanceRules.ExtractTrigger trigger = MaintenanceRules.extractTrigger(backlog.eligibleTurns());
        if (trigger == MaintenanceRules.ExtractTrigger.NONE) {
            return Counts.NONE;
        }
        long candidateThrough = lastId(state.sessionId(), state.memoryScanAfterTurn(), bound);
        if (candidateThrough <= state.memoryScanAfterTurn()) {
            return Counts.NONE;
        }
        Loaded loaded = scanner.load(new Request(Kind.EXTRACT, state.sessionId(),
                state.memoryScanAfterTurn() + 1L, candidateThrough, state.contextGeneration(),
                state.memoryScopeType(), scopeId, timezone, envelope.epoch(), policyRevision, 0L, null,
                target.fingerprint(), extractor.promptVersion(), extractor.framingTokens(), historyFloor,
                sessionPassword, key));
        if (!loaded.ready()) {
            return recordPermanentSkips(state.sessionId(), ScanKind.MEMORY, loaded);
        }
        Batch batch = loaded.batch();
        // 等待时长按"最近一条来源的结束时刻"起算，用假时钟就能直接断言合并窗口与空闲触发
        long probeAt = batch.latestSourceMillis() + MaintenanceRules.extractDelayMillis(trigger);
        return record(jobs.enqueue(new JobDraft(Kind.EXTRACT, state.sessionId(), batch.fromTurnId(),
                batch.throughTurnId(), batch.contextGeneration(), batch.scopeType(), batch.scopeId(), timezone,
                envelope.epoch(), policyRevision, target.fingerprint(), extractor.promptVersion(),
                batch.inputRevision(), MemoryTimes.formatMillis(nowMillis)), MemoryTimes.formatMillis(probeAt)));
    }

    private Counts queueSummary(State state, long scopeId, long maxTurnId, MemoryRepository.VaultEnvelope envelope,
                                MemoryCryptoService.MemoryKey key, MemoryPolicyService.Target target,
                                long policyRevision, long historyFloor, String timezone, String sessionPassword,
                                long nowMillis) throws SQLException {
        if (!policy.summaryEligibility().allowed()) {
            return Counts.NONE;
        }
        long lastAttempt = MemoryTimes.parseMillis(state.summaryLastAttemptAt());
        long sinceLastSend = lastAttempt == 0L ? Long.MAX_VALUE : nowMillis - lastAttempt;
        if (sinceLastSend < MaintenanceRules.SUMMARY_COOLDOWN_MILLIS) {
            // 冷却期内连装载都省了：摘要源本身还要再排除最近两条完整轮次
            return Counts.NONE;
        }
        long bound = pendingBound(state.sessionId(), maxTurnId);
        long summaryBound = history.summarySourceBound(state.sessionId(), state.summaryScanAfterTurn(), bound,
                state.contextGeneration(), historyFloor, MaintenanceRules.SUMMARY_RESERVED_RECENT_TURNS);
        if (summaryBound <= state.summaryScanAfterTurn()) {
            return Counts.NONE;
        }
        long candidateThrough = lastId(state.sessionId(), state.summaryScanAfterTurn(), summaryBound);
        if (candidateThrough <= state.summaryScanAfterTurn()) {
            return Counts.NONE;
        }
        MaintenanceSourceScanner.PreviousSummary previous = scanner.previousSummary(state.sessionId(),
                state.contextGeneration(), envelope.epoch(), key);
        Loaded loaded = scanner.load(new Request(Kind.SUMMARY, state.sessionId(),
                state.summaryScanAfterTurn() + 1L, candidateThrough, state.contextGeneration(),
                state.memoryScopeType(), scopeId, timezone, envelope.epoch(), policyRevision, previous.revision(),
                previous.modelSummary(), target.fingerprint(), drafter.promptVersion(), drafter.framingTokens(),
                historyFloor, sessionPassword, key));
        if (!loaded.ready()) {
            return recordPermanentSkips(state.sessionId(), ScanKind.SUMMARY, loaded);
        }
        Batch batch = loaded.batch();
        long newTokens = batch.turns().stream().mapToLong(SourceTurn::estimatedTokens).sum();
        long pressure = scanner.recentPressure(state.sessionId(), bound, state.contextGeneration(),
                sessionPassword);
        MaintenanceRules.SummaryGate gate = MaintenanceRules.summaryGate(pressure, newTokens, sinceLastSend, false);
        if (gate != MaintenanceRules.SummaryGate.QUEUE) {
            log.debug("摘要排队被拦：session={}, gate={}, pressure={}, newTokens={}", state.sessionId(), gate,
                    pressure, newTokens);
            return Counts.NONE;
        }
        return record(jobs.enqueue(new JobDraft(Kind.SUMMARY, state.sessionId(), batch.fromTurnId(),
                batch.throughTurnId(), batch.contextGeneration(), batch.scopeType(), batch.scopeId(), timezone,
                envelope.epoch(), policyRevision, target.fingerprint(), drafter.promptVersion(),
                batch.inputRevision(), MemoryTimes.formatMillis(nowMillis))));
    }

    private static Counts record(EnqueueResult result) {
        return switch (result.outcome()) {
            case INSERTED -> new Counts(1, 0, 0, 0);
            case MERGED -> new Counts(0, 1, 0, 0);
            case REPLAYED -> Counts.NONE;
            case DEFERRED -> new Counts(0, 0, 1, 0);
            case CAPACITY_FULL -> new Counts(0, 0, 0, 1);
        };
    }

    /**
     * 整批都是<b>永久跳过</b>（已清理原文、单轮超限、不合格）时，就地推进"已检查至此"水位并记跳过计数。
     *
     * <p>这一类来源永远不会变成可发送的批次，若不为它们推进水位，每一轮 tick 都会重新扫到同一批死源，
     * 会话永远收不了口（§2.5「永久跳过可以越过去」）。这里不建任务也不需要外发：
     * 本地就已判定完毕，外发只会白花额度。</p>
     *
     * <p>{@code WAITING_UNLOCK} 与 {@code skips==0} 一律不动水位——前者只是<b>暂时</b>解不开，
     * 推进水位等于把用户的内容永久丢掉（§2.5）。</p>
     */
    private Counts recordPermanentSkips(long sessionId, ScanKind kind, Loaded loaded) throws SQLException {
        if (loaded.outcome() == MaintenanceSourceScanner.Outcome.WAITING_UNLOCK
                || loaded.skips().total() == 0) {
            return Counts.NONE;
        }
        sessions.advanceScanWatermark(sessionId, kind, loaded.examinedThrough());
        // 与 worker 同一条口径：没有对应短代码的跳过类别也要留个可解释的值，不能把该列写回 NULL
        sessions.recordSkips(sessionId, kind, loaded.skips().total(), loaded.notice() == null
                ? MaintenanceRules.NOTICE_NO_NEW_INFORMATION : loaded.notice());
        return Counts.NONE;
    }

    /** 待处理边界：停在最早的 RUNNING 轮次之前，没有则取最大轮次。 */
    private long pendingBound(long sessionId, long maxTurnId) throws SQLException {
        long running = history.firstRunningTurnId(sessionId);
        return running > 0 ? running - 1L : maxTurnId;
    }

    /** 有界区间里最后一个轮次 ID：范围再长也只处理这么多行。 */
    private long lastId(long sessionId, long afterTurnId, long throughTurnId) throws SQLException {
        List<Long> ids = history.maintenanceIds(sessionId, afterTurnId, throughTurnId,
                MaintenanceSourceScanner.MAX_RANGE_ROWS);
        return ids.isEmpty() ? afterTurnId : ids.get(ids.size() - 1);
    }

    /** 四类入队结局的计数。 */
    private record Counts(int queued, int merged, int deferred, int refused) {
        private static final Counts NONE = new Counts(0, 0, 0, 0);
    }
}
