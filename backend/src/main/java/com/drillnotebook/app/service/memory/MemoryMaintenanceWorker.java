package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.MaintenanceRow;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.ScanKind;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.State;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.JobRow;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.MemoryJobRepository.LeaseOutcome;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.repository.UsageRepository;
import com.drillnotebook.app.repository.UsageRepository.ActualUsage;
import com.drillnotebook.app.repository.UsageRepository.Attempt;
import com.drillnotebook.app.repository.UsageRepository.AttemptRow;
import com.drillnotebook.app.repository.UsageRepository.MaintenanceSpend;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.AttemptFailure;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.CandidateBatch;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.drillnotebook.app.service.memory.MaintenancePorts.SummaryDraft;
import com.drillnotebook.app.service.memory.MaintenancePorts.Usage;
import com.drillnotebook.app.service.memory.MaintenanceSourceScanner.Loaded;
import com.drillnotebook.app.service.memory.MaintenanceSourceScanner.Request;
import com.drillnotebook.app.service.memory.MaintenanceSourceScanner.Skips;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 后台维护 worker（执行方案 §8、§8.2、§8.5；contracts §7 的 {@code applyExtraction}/{@code applySummary}）。
 *
 * <p>一轮 drain 按固定顺序做四件事：收尾陈旧租约 → 排队 → 按限额认领 → 处理。全应用同时只有一条维护链
 * （§8.1 后台并发 1），聊天正在生成本轮回复时让路，不抢占同一个配置的维护槽。</p>
 *
 * <p>四条不能破的规矩：</p>
 * <ul>
 *   <li><b>网络永不在写事务里</b>：装载、预留、外发、结算各走独立短事务，只有最终结果落库才开写事务；</li>
 *   <li><b>提交前复验</b>：同一笔事务里先做租约 CAS（令牌匹配且未过期），再复验来源身份、库 epoch、
 *       策略版本与上下文代次，任一条不符就整批作废而不是写旧结果；后面的步骤失败会抛错回滚整笔事务，
 *       绝不会留下"任务已终态但结果没落"的半提交；</li>
 *   <li><b>门禁不算尝试</b>：开关、授权、锁定、额度不满足时只进等待态并留一个探视时刻，
 *       不增加 {@code attempts}，也不消耗当天额度（§8.2 第 2 条）；</li>
 *   <li><b>预留不返还</b>：已经置 SENT 的外发即使没有用量也按 {@code UNKNOWN} 消费（§2.6），
 *       只有可证明未发出的 RESERVED 才释放。</li>
 * </ul>
 *
 * <p>{@code clock} 与 {@link #drainOnce(long)} 都可注入：冷却 119/120 秒、租约过期、预算跨午夜这些
 * 边界用合成时刻直接断言，不靠长 sleep（§14 P6）。</p>
 */
@Service
public class MemoryMaintenanceWorker {
    /** 一次 drain 最多连续处理多少个任务：仍然串行，所以并发度还是 1。 */
    public static final int JOBS_PER_DRAIN = 8;
    /** 一次 drain 最多收尾多少条陈旧租约。 */
    public static final int RECOVERY_PER_DRAIN = 20;

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceWorker.class);

    /** 单个任务的处理结局。 */
    public enum JobResult {
        COMMITTED, SUPERSEDED, RETRIED, FAILED, WAITING, YIELDED, NOT_CLAIMED, REJECTED
    }


    /** 一次 drain 的统计，不含任何正文。{@code rejected} 是"结果写好但租约已失效"的次数（§7）。 */
    public record Drain(int recovered, int enqueued, int claimed, int committed, int superseded, int retried,
                        int failed, int waiting, int yielded, int rejected) {
    }

    private final MemoryJobRepository jobs;
    private final UsageRepository usage;
    private final ChatSessionMetadataRepository metadata;
    private final ChatHistoryRepository history;
    private final MemoryRepository memory;
    private final MemoryPolicyService policy;
    private final MemoryVaultService vault;
    private final MemoryCryptoService crypto;
    private final MaintenanceSourceScanner scanner;
    private final MaintenanceQueue queue;
    private final MaintenancePorts.Extractor extractor;
    private final MaintenancePorts.SummaryDrafter drafter;
    private final ActiveTurnRegistry turns;
    private final ImmediateTransaction immediate;
    private final TokenEstimator estimator;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    private final AtomicLong retryJitter = new AtomicLong();
    /**
     * 锁定代数（§6.1.7、S11）：认领时记下快照，落库前必须还是同一代。
     *
     * <p>为什么单独记这个：锁定<b>不</b>改库 epoch、也不改策略版本，所以"epoch/policy 复验"这条路拦不住它
     * （遗忘与撤销授权那两条路会把任务行打成 SUPERSEDED，租约 CAS 自己就拒绝了旧结果）。
     * 而"用户刚点了锁定，飞行中的结果仍然落库"是 S11 明确禁止的。</p>
     */
    private final AtomicLong lockGeneration = new AtomicLong();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean drainQueued = new AtomicBoolean();
    private volatile boolean backgroundDrain = true;

    @Autowired
    public MemoryMaintenanceWorker(MemoryJobRepository jobs, UsageRepository usage,
                                   ChatSessionMetadataRepository metadata, ChatHistoryRepository history,
                                   MemoryRepository memory, MemoryPolicyService policy, MemoryVaultService vault,
                                   MemoryCryptoService crypto, MaintenanceSourceScanner scanner,
                                   MaintenanceQueue queue, MaintenancePorts.Extractor extractor,
                                   MaintenancePorts.SummaryDrafter drafter, ActiveTurnRegistry turns,
                                   ImmediateTransaction immediate, TokenEstimator estimator, ObjectMapper mapper) {
        this(jobs, usage, metadata, history, memory, policy, vault, crypto, scanner, queue, extractor, drafter,
                turns, immediate, estimator, mapper, System::currentTimeMillis);
    }

    public MemoryMaintenanceWorker(MemoryJobRepository jobs, UsageRepository usage,
                                   ChatSessionMetadataRepository metadata, ChatHistoryRepository history,
                                   MemoryRepository memory, MemoryPolicyService policy, MemoryVaultService vault,
                                   MemoryCryptoService crypto, MaintenanceSourceScanner scanner,
                                   MaintenanceQueue queue, MaintenancePorts.Extractor extractor,
                                   MaintenancePorts.SummaryDrafter drafter, ActiveTurnRegistry turns,
                                   ImmediateTransaction immediate, TokenEstimator estimator, ObjectMapper mapper,
                                   LongSupplier clock) {
        this.jobs = jobs;
        this.usage = usage;
        this.metadata = metadata;
        this.history = history;
        this.memory = memory;
        this.policy = policy;
        this.vault = vault;
        this.crypto = crypto;
        this.scanner = scanner;
        this.queue = queue;
        this.extractor = extractor;
        this.drafter = drafter;
        this.turns = turns;
        this.immediate = immediate;
        this.estimator = estimator;
        this.mapper = mapper;
        this.clock = clock;
        // 锁定即不再提交飞行结果（§6.1.7、S11）：钥匙被抹掉后，任何在批结果都只会被丢弃
        vault.onLock(this::invalidateInFlight);
        vault.onUnlock(this::wake);
    }

    // ---- 调度入口 ----

    /** 定时 tick：空闲时只跑本地 SQL，不发任何远程调用（§13.3 运行资源门禁）。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void tick() {
        wake();
    }

    /** 解锁或新轮次后的联动唤醒：多次调用合并成一次 drain。 */
    public void wake() {
        if (!backgroundDrain || !drainQueued.compareAndSet(false, true)) {
            return;
        }
        executor.submit(this::runDrain);
    }

    /**
     * 测试装配用（包内）：关掉解锁/定时联动的<b>后台</b> drain，改由用例直接调
     * {@link #drainOnce(long)}。
     *
     * <p>与 {@link #useRetryJitter(long)}、可注入的 {@code clock} 属于同一类注入缝（§14 P6
     * 「Clock 和 drainOnce 可注入，不靠长 sleep」）：后台线程自己跑起来时，"排队"究竟落在
     * 哪一次 drain 里就不确定了，只能靠 sleep 猜时间。</p>
     */
    void useBackgroundDrain(boolean enabled) {
        backgroundDrain = enabled;
        if (!enabled) {
            drainQueued.set(false);
        }
    }

    private void runDrain() {
        drainQueued.set(false);
        try {
            drainOnce(clock.getAsLong());
        } catch (SQLException error) {
            log.warn("后台维护本轮失败: {}", error.toString());
        } catch (RuntimeException error) {
            log.error("后台维护出现未预期错误", error);
        }
    }

    /** 应用退出时停掉执行链：正在写的事务由 SQLite 自己回滚。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 测试用：把重试抖动固定下来，让退避时刻可以被断言。 */
    public void useRetryJitter(long jitterMillis) {
        retryJitter.set(Math.max(0, jitterMillis));
    }

    // ---- 一轮 drain ----

    public Drain drainOnce(long nowMillis) throws SQLException {
        int recovered = recover(nowMillis);
        MaintenanceQueue.Sweep sweep = queue.sweep(clock.getAsLong());
        int claimed = 0;
        int committed = 0;
        int superseded = 0;
        int retried = 0;
        int failed = 0;
        int waiting = 0;
        int yielded = 0;
        int rejected = 0;
        for (int index = 0; index < JOBS_PER_DRAIN; index++) {
            long asOf = clock.getAsLong();
            List<JobRow> claimable = jobs.findClaimable(MemoryTimes.formatMillis(asOf), 1);
            if (claimable.isEmpty()) {
                break;
            }
            claimed++;
            JobResult result = process(claimable.get(0), asOf);
            switch (result) {
                case COMMITTED -> committed++;
                case SUPERSEDED -> superseded++;
                case RETRIED -> retried++;
                case FAILED -> failed++;
                case WAITING -> waiting++;
                case YIELDED -> yielded++;
                case REJECTED -> rejected++;
                case NOT_CLAIMED -> {
                    // 没抢到：交给下一轮与陈旧租约收尾
                }
            }
        }
        // 本轮扫过但没产生任务的会话也要收口：所有范围都处理完时唤醒标记必须能落回 false（§2.1）
        for (Long sessionId : sweep.scannedSessionIds()) {
            reconcileWake(sessionId);
        }
        return new Drain(recovered, sweep.queued(), claimed, committed, superseded, retried, failed, waiting,
                yielded, rejected);
    }

    /**
     * 陈旧租约收尾：进程被杀、上游悬挂都会把任务留在 {@code CLAIMED} 且租约已过点。
     * 先把它的用量按状态结算（SENT→UNKNOWN 消费预留；可证明未发的 RESERVED→释放），
     * 再决定回队重试还是达到上限终止（§11.4「不恢复正在运行的 lease」）。
     */
    private int recover(long nowMillis) throws SQLException {
        String asOf = MemoryTimes.formatMillis(nowMillis);
        List<JobRow> stale = jobs.findExpiredClaims(asOf, RECOVERY_PER_DRAIN);
        int handled = 0;
        for (JobRow job : stale) {
            settleLeftoverAttempts(job.id(), asOf);
            int attemptsDone = job.attempts() + 1;
            boolean exhausted = attemptsDone >= MaintenanceRules.MAX_ATTEMPTS;
            String status = exhausted ? MemoryJobRepository.STATUS_FAILED : MemoryJobRepository.STATUS_RETRY;
            String nextRun = exhausted ? null : MemoryTimes.formatMillis(nowMillis
                    + MaintenanceRules.retryDelayMillis(attemptsDone, retryJitter.get()));
            if (jobs.expireClaim(job.id(), job.leaseToken(), status, nextRun, "LEASE_EXPIRED", asOf)
                    == LeaseOutcome.APPLIED) {
                handled++;
            }
        }
        if (handled > 0) {
            log.info("陈旧租约已收尾: {} 个", handled);
        }
        return handled;
    }

    private void settleLeftoverAttempts(String jobId, String asOf) throws SQLException {
        for (AttemptRow attempt : usage.findUnsettledByOperation(jobId)) {
            try {
                if ("SENT".equals(attempt.state())) {
                    // 已经发出去过：预留按 UNKNOWN 消费，不谎称知道实际 token
                    usage.markUnknown(attempt.id(), asOf);
                } else {
                    usage.releaseUnsent(attempt.id(), asOf);
                }
            } catch (SQLException error) {
                log.warn("陈旧用量结算失败 attemptId={}", attempt.id(), error);
            }
        }
    }

    // ---- 单个任务 ----

    private JobResult process(JobRow job, long claimedAt) throws SQLException {
        String leaseToken = UUID.randomUUID().toString();
        String leaseUntil = MemoryTimes.formatMillis(claimedAt + MaintenanceRules.LEASE_MILLIS);
        if (jobs.claim(job.id(), leaseToken, leaseUntil, MemoryTimes.formatMillis(claimedAt))
                != LeaseOutcome.APPLIED) {
            return JobResult.NOT_CLAIMED;
        }
        // 认领之后任何一次锁定都要作废本批结果（§6.1.7、S11）
        long claimGeneration = lockGeneration.get();
        // 1. 聊天优先：本轮回复还在生成就让路，不抢占同一个维护槽（§8.1）
        if (turns.size() > 0) {
            jobs.markWaiting(job.id(), leaseToken, MemoryJobRepository.STATUS_QUEUED,
                    MemoryTimes.formatMillis(claimedAt + MaintenanceRules.MERGE_WINDOW_MILLIS), "CHAT_ACTIVE",
                    MemoryTimes.formatMillis(claimedAt));
            return JobResult.YIELDED;
        }
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        Optional<MemoryCryptoService.MemoryKey> key = vault.currentKey();
        if (envelope.isEmpty() || key.isEmpty()) {
            return wait(job, leaseToken, MemoryJobRepository.STATUS_WAITING_UNLOCK, clock.getAsLong(),
                    "VAULT_LOCKED");
        }
        // 2. 门禁：不满足只进等待态，不算一次远程尝试（§8.2 第 2 条）
        JobResult gate = applyGates(job, leaseToken, envelope.get(), key.get());
        if (gate != null) {
            return gate;
        }
        // 3. 装载本批来源并复验批次身份
        Loaded loaded = scanner.load(requestFor(job, envelope.get(), key.get()));
        if (loaded.outcome() == MaintenanceSourceScanner.Outcome.WAITING_UNLOCK) {
            return wait(job, leaseToken, MemoryJobRepository.STATUS_WAITING_UNLOCK, clock.getAsLong(),
                    MaintenanceRules.NOTICE_WAITING_UNLOCK);
        }
        if (!loaded.ready()) {
            return commit(job, leaseToken, outcomeOfNotice(loaded.notice()), loaded.skips(), loaded.notice(),
                    loaded.examinedThrough(), null, null, claimGeneration);
        }
        Batch batch = loaded.batch();
        if (batch.inputRevision() == null || !batch.inputRevision().equals(job.inputRevision())) {
            // 批内源变更：本批结果作废，水位不动，由队列按新身份重新入队（§2.4）
            return settleSuperseded(job, leaseToken, "INPUT_REVISION_CHANGED");
        }
        // 4. 先占额度，再发送（§2.6）
        ZoneId zone = budgetZone();
        String attemptId = UuidV7.generate(clock.getAsLong());
        Attempt attempt = new Attempt(attemptId, job.id(), purposeOf(job.kind()), job.targetFingerprint(),
                budgetDate(zone), zone.getId(), MaintenanceRules.RESERVE_TOKENS,
                MemoryTimes.formatMillis(clock.getAsLong()), null);
        if (!usage.reserve(attempt)) {
            return wait(job, leaseToken, MemoryJobRepository.STATUS_PAUSED_BUDGET, clock.getAsLong(),
                    "RESERVE_FAILED");
        }
        usage.markSent(attemptId);
        if (job.kind() == Kind.SUMMARY) {
            // 摘要尝试记账在实际外发之前：失败同样计入冷却，避免同一批被反复压缩（§2.4、§8.5）
            metadata.recordSummaryAttempt(job.sessionId(), MemoryTimes.formatMillis(clock.getAsLong()),
                    job.throughTurnId(), job.inputRevision());
        }
        Outcome outcome;
        try {
            outcome = job.kind() == Kind.EXTRACT ? runExtract(batch) : runSummary(batch, key.get());
        } catch (AttemptFailure failure) {
            // 已经发出去了：用量按 UNKNOWN 消费，预留不返还（§2.6）
            settle(attemptId, ActualUsage.unknown());
            return onFailure(job, leaseToken, failure);
        } catch (RuntimeException error) {
            settle(attemptId, ActualUsage.unknown());
            log.warn("维护任务出现未预期失败 job={}: {}", job.id(), error.toString());
            return onFailure(job, leaseToken, new AttemptFailure("MAINTENANCE_UNEXPECTED", "本轮维护失败", false));
        }
        settle(attemptId, actual(outcome.usage()));
        return commit(job, leaseToken, outcome.outcomeCode(), loaded.skips(), loaded.notice(),
                outcome.examinedThrough(), batch, outcome.checkpoint(), claimGeneration);
    }

    /** 开关 / 授权 / 额度 / 版本四道门禁。通过返回 null，否则任务已进等待态或终态。 */
    private JobResult applyGates(JobRow job, String leaseToken, MemoryRepository.VaultEnvelope envelope,
                                 MemoryCryptoService.MemoryKey key) throws SQLException {
        long nowMillis = clock.getAsLong();
        if (envelope.epoch() != job.vaultEpoch()) {
            // 遗忘/重置之后的旧批次不允许重跑（§3）
            return settleSuperseded(job, leaseToken, "VAULT_EPOCH_CHANGED");
        }
        long policyRevision = policy.current().revision();
        if (policyRevision != job.policyRevision()) {
            return settleSuperseded(job, leaseToken, "POLICY_REVISION_CHANGED");
        }
        Optional<State> row = metadata.read(job.sessionId());
        if (row.isEmpty() || row.get().contextGeneration() != job.contextGeneration()
                || !row.get().learnEnabled() || row.get().archivedAt() != null) {
            return settleSuperseded(job, leaseToken, "SESSION_STATE_CHANGED");
        }
        MemoryPolicyService.Eligibility eligibility = job.kind() == Kind.EXTRACT
                ? policy.extractionEligibility(job.sourceScopeType(), job.sourceScopeId())
                : policy.summaryEligibility();
        if (!eligibility.allowed()) {
            if (eligibility.gate() == MemoryPolicyService.Gate.SCOPE_INVALID) {
                return settleSuperseded(job, leaseToken, eligibility.gate().name());
            }
            String status = eligibility.gate() == MemoryPolicyService.Gate.LOCKED
                    || eligibility.gate() == MemoryPolicyService.Gate.VAULT_MISSING
                    ? MemoryJobRepository.STATUS_WAITING_UNLOCK : MemoryJobRepository.STATUS_WAITING_CONSENT;
            return wait(job, leaseToken, status, nowMillis, eligibility.gate().name());
        }
        if (!policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE).configured()) {
            return wait(job, leaseToken, MemoryJobRepository.STATUS_WAITING_CONSENT, nowMillis, "TARGET_MISSING");
        }
        if (key.erased()) {
            return wait(job, leaseToken, MemoryJobRepository.STATUS_WAITING_UNLOCK, nowMillis, "VAULT_LOCKED");
        }
        MemoryPolicyService.Policy policyRow = policy.policyOf(policy.current());
        ZoneId zone = budgetZone();
        MaintenanceSpend spend = usage.maintenanceSpend(budgetDate(zone), zone.getId());
        if (!MaintenanceRules.budgetAllows(spend.attempts(), spend.spentTokens(), policyRow.dailyCallLimit(),
                policyRow.dailyTokenLimit())) {
            // 额度按预算日重置：跨午夜才恢复；若还有未结算预留则先短探一次，别白等一天
            long probe = spend.reservedTokens() > 0 ? nowMillis + MaintenanceRules.GATE_PROBE_MILLIS
                    : nextBudgetDayMillis(zone, nowMillis);
            return wait(job, leaseToken, MemoryJobRepository.STATUS_PAUSED_BUDGET, nowMillis, "DAILY_BUDGET", probe);
        }
        return null;
    }

    /**
     * 提取结果映射成终态代码（§2.4）。
     *
     * <p>P6 不写事实：候选的确定性冲突处理与加密落库属 P7（§8.3）。既然事实库没有变化，
     * 这里就不递增 vault 内容 revision、也不重建索引（§8.2 第 9 条）。</p>
     */
    private Outcome runExtract(Batch batch) throws AttemptFailure {
        CandidateBatch candidates = extractor.extract(batch);
        String code = candidates.candidateCount() > 0 ? "APPLIED"
                : candidates.rejectedCount() > 0 ? "NO_VALID_CANDIDATES" : "NO_NEW_INFORMATION";
        return new Outcome(code, batch.throughTurnId(), candidates.usage(), null);
    }

    /**
     * 摘要结果：先判净缩减，不够就保留旧文本（§8.4 第 4 条）；够则把检查点加密好待同一事务写入。
     * 草稿超出模型可见上限或 payload 上限都算非法产出，直接终态而不是盲目重试。
     */
    private Outcome runSummary(Batch batch, MemoryCryptoService.MemoryKey key)
            throws AttemptFailure, SQLException {
        Optional<SummaryDraft> produced = drafter.draft(batch);
        if (produced.isEmpty()) {
            return new Outcome("NO_NEW_INFORMATION", batch.throughTurnId(), Usage.unknown(), null);
        }
        SummaryDraft draft = produced.get();
        if (draft.estimatedTokens() > MaintenanceRules.SUMMARY_MODEL_TOKEN_LIMIT) {
            throw new AttemptFailure("SUMMARY_DRAFT_TOO_LARGE", "摘要草稿超出 600 估算 Token 上限", false);
        }
        long previous = batch.previousSummary() == null ? 0 : estimator.estimateText(batch.previousSummary());
        long raw = batch.turns().stream().mapToLong(SourceTurn::estimatedTokens).sum();
        if (MaintenanceRules.summaryVerdict(previous, raw, draft.estimatedTokens())
                == MaintenanceRules.SummaryVerdict.NO_COMPRESSION_GAIN) {
            return new Outcome("NO_COMPRESSION_GAIN", batch.throughTurnId(), draft.usage(), null);
        }
        Checkpoint checkpoint = checkpointOf(batch, draft, key);
        return new Outcome("APPLIED", batch.throughTurnId(), draft.usage(), checkpoint);
    }

    /**
     * 组装检查点。覆盖范围只标实际进入本批的<b>连续</b>轮次段（§2.5），被跳过的那段不算已整理；
     * 加密身份绑 {@code (ai_session_context, sessionId, 新 revision)}，所以必须先把 revision 定下来。
     */
    private Checkpoint checkpointOf(Batch batch, SummaryDraft draft, MemoryCryptoService.MemoryKey key)
            throws SQLException, AttemptFailure {
        List<Map<String, Object>> ranges = new ArrayList<>();
        long first = -1L;
        long last = -1L;
        for (SourceTurn turn : batch.turns()) {
            if (first < 0) {
                first = turn.turnId();
                last = turn.turnId();
                continue;
            }
            if (turn.turnId() == last + 1) {
                last = turn.turnId();
                continue;
            }
            ranges.add(coverage(first, last));
            first = turn.turnId();
            last = turn.turnId();
        }
        if (first >= 0) {
            ranges.add(coverage(first, last));
        }
        long newRevision = batch.summaryRevision() + 1;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("modelSummary", draft.modelSummary());
        payload.put("serverCoverage", ranges);
        payload.put("producer", drafter.promptVersion());
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new AttemptFailure("SUMMARY_PAYLOAD_INVALID", "摘要 payload 无法序列化，本轮不发布", false);
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MaintenanceRules.SUMMARY_PAYLOAD_BYTE_LIMIT) {
            throw new AttemptFailure("SUMMARY_PAYLOAD_TOO_LARGE", "摘要 payload 超出 32 KiB 上限", false);
        }
        try {
            MemoryCryptoService.EncryptedValue encrypted = crypto.encrypt(key, "ai_session_context",
                    String.valueOf(batch.sessionId()), newRevision, json);
            return new Checkpoint(encrypted.cipher(), encrypted.cryptoMeta(), newRevision,
                    last < 0 ? batch.throughTurnId() : last, draft.estimatedTokens());
        } catch (MemoryCryptoService.MemoryCryptoException error) {
            throw new AttemptFailure("SUMMARY_ENCRYPTION_FAILED", "摘要无法加密，本轮不发布检查点", false);
        }
    }

    private static Map<String, Object> coverage(long first, long last) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("firstTurnId", first);
        range.put("lastTurnId", last);
        return range;
    }

    /**
     * 结果落库：一笔事务里先做租约 CAS，再复验来源身份并写水位。
     * 取消与提交竞争就是靠这一次 CAS 分胜负（§7）；后面的步骤失败会抛错回滚整笔事务，
     * 然后单独把任务改成 SUPERSEDED，不会留下"已终态但没有结果"的半提交。
     *
     * <p>第一道闸是锁定代次：本批从认领到落库之间只要发生过锁定（{@code claimGeneration} 变了）就整份丢掉，
     * 连水位也不推进——锁定的语义是"这把钥匙保护的内容此刻不再外泄"，不是"等它写完再说"（§6.1.7、S11）。</p>
     */
    private JobResult commit(JobRow job, String leaseToken, String outcomeCode, Skips skips, String skipNotice,
                             long examinedThrough, Batch batch, Checkpoint checkpoint, long claimGeneration)
            throws SQLException {
        if (lockGeneration.get() != claimGeneration) {
            log.info("记忆库在本批飞行期间被锁定，结果整份丢弃 job={}", job.id());
            return JobResult.REJECTED;
        }
        long nowMillis = clock.getAsLong();
        String asOf = MemoryTimes.formatMillis(nowMillis);
        ScanKind kind = job.kind() == Kind.EXTRACT ? ScanKind.MEMORY : ScanKind.SUMMARY;
        boolean applied;
        try {
            applied = Boolean.TRUE.equals(immediate.execute(connection -> {
                if (MemoryJobRepository.settleIn(connection, job.id(), leaseToken, asOf,
                        MemoryJobRepository.STATUS_COMPLETED, outcomeCode) != 1) {
                    return Boolean.FALSE;
                }
                if (batch != null && !sourcesIntact(connection, job, batch)) {
                    throw new BatchChangedException();
                }
                if (batch != null && checkpoint != null) {
                    SessionContextRepository.SaveOutcome saved = SessionContextRepository.saveIn(connection,
                            job.sessionId(), batch.summaryRevision(), job.contextGeneration(), job.vaultEpoch(),
                            checkpoint.revision(), checkpoint.coveredThrough(), job.contextGeneration(),
                            job.vaultEpoch(), checkpoint.cipher(), checkpoint.cryptoMeta(),
                            checkpoint.estimatedTokens(), asOf);
                    if (saved != SessionContextRepository.SaveOutcome.APPLIED) {
                        throw new BatchChangedException();
                    }
                }
                metadata.advanceScanWatermark(connection, job.sessionId(), kind, examinedThrough);
                if (skips != null && skips.total() > 0) {
                    // "最近跳过原因"存跳过原因本身，不存任务终态：一批照样发布成功，也可能顺带跳过了几轮（§2.5）
                    metadata.recordSkips(connection, job.sessionId(), kind, skips.total(),
                            skipNotice == null ? outcomeCode : skipNotice);
                }
                return Boolean.TRUE;
            }));
        } catch (BatchChangedException changed) {
            log.info("维护批次已失效，结果作废 job={}：来源或检查点在发送之后变了", job.id());
            return settleSuperseded(job, leaseToken, "SOURCE_CHANGED_AFTER_SEND");
        }
        if (!applied) {
            log.info("维护结果被丢弃：租约已失效 job={}", job.id());
            return JobResult.REJECTED;
        }
        reconcileWake(job.sessionId());
        return JobResult.COMMITTED;
    }

    /** 来源复验：每条都还在、仍是同代次的 COMPLETED、内容版本没动、原话没被清理、仍然合格。 */
    private boolean sourcesIntact(java.sql.Connection connection, JobRow job, Batch batch) throws SQLException {
        List<Long> ids = batch.turns().stream().map(SourceTurn::turnId).toList();
        List<MaintenanceRow> rows = ChatHistoryRepository.maintenanceRows(connection, job.sessionId(), ids);
        if (rows.size() != ids.size()) {
            return false;
        }
        for (MaintenanceRow row : rows) {
            SourceTurn expected = batch.turns().stream()
                    .filter(turn -> turn.turnId() == row.turnId()).findFirst().orElse(null);
            if (expected == null || expected.sourceRevision() != row.sourceRevision()
                    || !ChatTurnRepository.STATUS_COMPLETED.equals(row.status())
                    || row.contextGeneration() != job.contextGeneration()
                    || row.contentDeletedAt() != null || !row.memoryEligible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 唤醒标记的收口：先确认没有活跃任务，再看两类工作还剩多少待处理范围，两者都空才清。
     *
     * <p>口径严格照 §2.1：「全部处理完成置 false，预算/解锁等待不丢 pending」。所以
     * <b>等待类</b>拦截（锁库、授权过期、当天额度、冷却、压力）都留着标记——条件恢复后没人再回头看
     * 这批源就等于丢内容；<b>开关类</b>排除（{@code enabled}/{@code autoExtract}/{@code compressSession}、
     * 会话级 learnEnabled、作用域失效）按 {@link MemoryPolicyService#switchedOff} 收口，
     * 因为那批来源本就不该进维护链，而扫描始终从水位算起，重新开启后不会漏区间里的来源。</p>
     *
     * <p>摘要那一路按"排除最近两轮之后还剩不剩可整理的内容"算，不然最后两轮会让唤醒永远清不掉。</p>
     */
    private void reconcileWake(long sessionId) throws SQLException {
        if (!jobs.findActiveForSession(sessionId).isEmpty()) {
            return;
        }
        Optional<State> row = metadata.read(sessionId);
        if (row.isEmpty()) {
            return;
        }
        State state = row.get();
        long running = history.firstRunningTurnId(sessionId);
        long maxTurnId = history.maxTurnId(sessionId);
        long bound = running > 0 ? Math.min(maxTurnId, running - 1L) : maxTurnId;
        long floor = policy.policyOf(policy.current()).memoryHistoryFloor();
        long scopeId = state.memoryNotebookId() == null ? 0L : state.memoryNotebookId();
        if (!state.learnEnabled()) {
            metadata.clearMaintenanceWake(sessionId);
            return;
        }
        boolean extractPending = !MemoryPolicyService.switchedOff(
                policy.extractionEligibility(state.memoryScopeType(), scopeId).gate())
                && history.maintenanceBacklog(sessionId, state.memoryScanAfterTurn(), bound,
                        state.contextGeneration(), floor).eligibleTurns() > 0;
        boolean summaryPending = !MemoryPolicyService.switchedOff(policy.summaryEligibility().gate())
                && history.summarySourceBound(sessionId, state.summaryScanAfterTurn(), bound,
                        state.contextGeneration(), floor, MaintenanceRules.SUMMARY_RESERVED_RECENT_TURNS)
                > state.summaryScanAfterTurn();
        if (!extractPending && !summaryPending) {
            metadata.clearMaintenanceWake(sessionId);
        }
    }

    private JobResult onFailure(JobRow job, String leaseToken, AttemptFailure failure) throws SQLException {
        int attemptsDone = job.attempts() + 1;
        if (!failure.retryable() || attemptsDone >= MaintenanceRules.MAX_ATTEMPTS) {
            return jobs.settle(job.id(), leaseToken, MemoryTimes.formatMillis(clock.getAsLong()),
                    MemoryJobRepository.STATUS_FAILED, null) == LeaseOutcome.APPLIED
                    ? JobResult.FAILED : JobResult.REJECTED;
        }
        String nextRun = MemoryTimes.formatMillis(clock.getAsLong()
                + MaintenanceRules.retryDelayMillis(attemptsDone, retryJitter.get()));
        return jobs.markRetry(job.id(), leaseToken, nextRun, failure.code(),
                MemoryTimes.formatMillis(clock.getAsLong())) == LeaseOutcome.APPLIED
                ? JobResult.RETRIED : JobResult.REJECTED;
    }

    private JobResult wait(JobRow job, String leaseToken, String status, long nowMillis, String reason)
            throws SQLException {
        return wait(job, leaseToken, status, nowMillis, reason, nowMillis + MaintenanceRules.GATE_PROBE_MILLIS);
    }

    private JobResult wait(JobRow job, String leaseToken, String status, long nowMillis, String reason, long probeAt)
            throws SQLException {
        return jobs.markWaiting(job.id(), leaseToken, status, MemoryTimes.formatMillis(probeAt), reason,
                MemoryTimes.formatMillis(nowMillis)) == LeaseOutcome.APPLIED
                ? JobResult.WAITING : JobResult.REJECTED;
    }

    private JobResult settleSuperseded(JobRow job, String leaseToken, String reason) throws SQLException {
        log.info("维护任务作废 job={}：{}", job.id(), reason);
        return jobs.settle(job.id(), leaseToken, MemoryTimes.formatMillis(clock.getAsLong()),
                MemoryJobRepository.STATUS_SUPERSEDED, null) == LeaseOutcome.APPLIED
                ? JobResult.SUPERSEDED : JobResult.REJECTED;
    }

    /**
     * 结算一次外发。厂商没回数字就走 {@code UNKNOWN}：预留全额计入当天额度，
     * 只有真拿到数字才按实际扣账（§2.6）。用 {@code settle(unknown)} 会一分钱都不记，
     * 等于让没有用量的调用白跑。
     */
    private void settle(String attemptId, ActualUsage actual) {
        String asOf = MemoryTimes.formatMillis(clock.getAsLong());
        try {
            if (actual.reported()) {
                usage.settle(attemptId, actual, asOf);
            } else {
                usage.markUnknown(attemptId, asOf);
            }
        } catch (SQLException error) {
            log.warn("维护用量结算失败 attemptId={}", attemptId, error);
        }
    }

    /** 没有厂商数字就按 UNKNOWN 消费预留；有的话按实际扣账（§2.6）。 */
    private static ActualUsage actual(Usage usage) {
        if (usage == null || !usage.reported()) return ActualUsage.unknown();
        return new ActualUsage("provider", toInt(usage.inputTokens()), toInt(usage.outputTokens()), null, null);
    }

    private static int toInt(Long value) {
        return value == null || value < 0 ? 0 : value.intValue();
    }

    /** 认领时的装载请求：范围与身份材料全部取自任务行，保证与排队那次一致。 */
    private Request requestFor(JobRow job, MemoryRepository.VaultEnvelope envelope,
                               MemoryCryptoService.MemoryKey key) throws SQLException {
        long floor = policy.policyOf(policy.current()).memoryHistoryFloor();
        MaintenanceSourceScanner.PreviousSummary previous = job.kind() == Kind.SUMMARY
                ? scanner.previousSummary(job.sessionId(), job.contextGeneration(), envelope.epoch(), key)
                : MaintenanceSourceScanner.PreviousSummary.NONE;
        return new Request(job.kind(), job.sessionId(), job.fromTurnId(), job.throughTurnId(),
                job.contextGeneration(), job.sourceScopeType(), job.sourceScopeId(), job.sourceTimezone(),
                envelope.epoch(), job.policyRevision(), previous.revision(), previous.modelSummary(),
                job.targetFingerprint(), job.promptVersion(),
                job.kind() == Kind.EXTRACT ? extractor.framingTokens() : drafter.framingTokens(), floor,
                vault.sessionPassword(job.sessionId()).orElse(null), key);
    }

    private static String purposeOf(Kind kind) {
        return kind == Kind.EXTRACT ? UsageRepository.PURPOSE_MEMORY_EXTRACT : UsageRepository.PURPOSE_SESSION_SUMMARY;
    }

    private static String outcomeOfNotice(String notice) {
        if (MaintenanceRules.NOTICE_SOURCE_DELETED.equals(notice)) {
            return "SOURCE_DELETED";
        }
        if (MaintenanceRules.NOTICE_SOURCE_TOO_LARGE.equals(notice)) {
            return "SOURCE_TOO_LARGE";
        }
        return "NO_NEW_INFORMATION";
    }

    /**
     * 预算日：按注入的时钟而不是系统时间算。跨午夜测试要能把额度判定推到下一个预算日，
     * 而且门禁判定与 attempt 记账必须落在同一个日期上，否则当天的额度会被拆成两天。
     */
    private String budgetDate(ZoneId zone) {
        return java.time.Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate().toString();
    }

    private ZoneId budgetZone() {
        try {
            return ZoneId.of(MaintenanceRules.zoneOrDefault(policy.policyOf(policy.current()).budgetTimezone(),
                    ZoneId.systemDefault().getId()));
        } catch (SQLException error) {
            return ZoneId.systemDefault();
        }
    }

    /** 预算日界：跨午夜后额度重置，所以 {@code PAUSED_BUDGET} 的探视时刻取下一个预算日零点。 */
    private static long nextBudgetDayMillis(ZoneId zone, long nowMillis) {
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        ZonedDateTime next = today.plusDays(1).atStartOfDay(zone);
        long candidate = next.toInstant().toEpochMilli();
        return candidate > nowMillis ? candidate : nowMillis + MaintenanceRules.GATE_PROBE_MILLIS;
    }

    /** 锁定联动：钥匙没了，任何在飞的维护结果都不允许再提交（§6.1.7、S11）。 */
    private void invalidateInFlight() {
        lockGeneration.incrementAndGet();
        log.info("记忆库已锁定：后台维护的飞行结果不再提交");
    }

    // ---- 内部类型 ----

    private record Outcome(String outcomeCode, long examinedThrough, Usage usage, Checkpoint checkpoint) {
    }

    /** 待写入的检查点：密文与身份在发送阶段就已定，写事务里只做 CAS。 */
    private record Checkpoint(String cipher, String cryptoMeta, long revision, long coveredThrough,
                              int estimatedTokens) {
    }

    /** 批次或检查点在发送之后变了：整笔写事务回滚，任务改判 SUPERSEDED。 */
    private static final class BatchChangedException extends SQLException {
        private BatchChangedException() {
            super("维护批次已失效");
        }
    }
}
