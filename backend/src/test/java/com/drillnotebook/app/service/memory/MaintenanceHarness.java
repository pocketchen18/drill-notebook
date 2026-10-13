package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.JobDraft;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.repository.UsageRepository;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.ChatMessageCipher;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenanceSourceScanner.Request;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P6 后台维护的测试装配：把队列、装载器、worker 与两个外发端口按生产依赖关系接起来。
 *
 * <p>时间是一条 {@link AtomicLong} 假时钟，提取与摘要是可编程的假端口，所以
 * "冷却 119/120 秒""租约过期""预算跨午夜""上游悬挂"都能直接断言，不需要真的等（§14 P6）。</p>
 *
 * <p>轮次按 {@link ChatTurnRepository} 的列形态用 SQL 落库：这里测的是维护链，不是轮次生命周期。
 * 正文一律按 {@code password} 模式加密，这样"有没有登记会话解锁材料"才真的能改变装载结果
 * （§2.5 的 WAITING_UNLOCK）。</p>
 */
final class MaintenanceHarness {
    static final String CHAT_ENDPOINT = "https://example.invalid/v1";
    static final String CHAT_PASSWORD = "chat-master-password";

    final Path appRoot;
    final DataSource dataSource;
    final JdbcTemplate jdbc;
    final ImmediateTransaction immediate;
    final MemoryJobRepository jobs;
    final UsageRepository usage;
    final ChatSessionMetadataRepository metadata;
    final ChatHistoryRepository history;
    final MemoryRepository memory;
    final MemoryVaultService vault;
    final MemoryPolicyService policy;
    final MemoryCryptoService crypto;
    final SessionContextRepository contexts;
    final MaintenanceSourceScanner scanner;
    final MaintenanceQueue queue;
    final MemoryMaintenanceWorker worker;
    final ActiveTurnRegistry handles;
    final StagingExtractor extractor = new StagingExtractor();
    final StagingDrafter drafter = new StagingDrafter();
    final AtomicLong clock = new AtomicLong(Instant.parse("2026-10-11T00:00:00.000Z").toEpochMilli());
    final ObjectMapper mapper = new ObjectMapper();

    private long sessionId;
    private int turnSequence;

    private MaintenanceHarness(String prefix) throws Exception {
        appRoot = TestWorkspace.scratchDirectory(prefix);
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        immediate = new ImmediateTransaction(dataSource);
        ChatLegacyWiring.Wiring wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        jobs = new MemoryJobRepository(dataSource, immediate);
        usage = wiring.usage();
        metadata = wiring.metadata();
        history = new ChatHistoryRepository(dataSource);
        memory = wiring.memory();
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        vault = new MemoryVaultService(crypto, memory, new MemoryOperationRepository(dataSource, immediate),
                metadata);
        TokenEstimator estimator = new TokenEstimator(mapper);
        policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate), memory, jobs,
                new AiConfigRepository(jdbc), new NotebookRepository(jdbc, mapper), vault);
        contexts = new SessionContextRepository(dataSource);
        scanner = new MaintenanceSourceScanner(history, wiring.cipher(), estimator, crypto, contexts, mapper);
        extractor.clock = clock;
        queue = new MaintenanceQueue(metadata, history, jobs, policy, vault, memory, scanner, extractor, drafter);
        handles = wiring.handles();
        worker = new MemoryMaintenanceWorker(jobs, usage, metadata, history, memory, policy, vault, crypto, scanner,
                queue, extractor, drafter, handles, immediate, estimator, mapper, clock::get);
        worker.useRetryJitter(0L);
        // 用例自己按假时钟调 drainOnce：后台线程跑起来的话，"排队落在哪一次 drain"就不确定了（§14 P6）
        worker.useBackgroundDrain(false);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        // 预算日按 UTC 固定：跨午夜用例不能依赖开发机的系统时区
        setBudgetZone("UTC");
    }

    static MaintenanceHarness open(String prefix) throws Exception {
        return new MaintenanceHarness(prefix);
    }

    void close() throws Exception {
        worker.shutdown();
        TestWorkspace.deleteRecursively(appRoot);
    }

    long session() {
        return sessionId;
    }

    // ---- 时间 ----

    static long millis(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    String stamp(long epochMilli) {
        return MemoryTimes.formatMillis(epochMilli);
    }

    void advance(long deltaMillis) {
        clock.addAndGet(deltaMillis);
    }

    long now() {
        return clock.get();
    }

    // ---- 会话与轮次 ----

    /** 造一个绑定笔记本的会话（作用域切换会推进上下文代次）。 */
    long notebookSession(long notebookId) throws SQLException {
        jdbc.update("INSERT INTO notebook(id, title) VALUES (?, '测试笔记本')", notebookId);
        jdbc.update("INSERT INTO ai_chat_session(id, title) VALUES (?, '笔记本会话')", 900L + notebookId);
        metadata.changeScope(900L + notebookId, "NOTEBOOK", notebookId, 0L);
        return 900L + notebookId;
    }

    /** 落一条完整轮次：正文按 password 模式加密，结束时刻取当前假时钟。 */
    long seedTurn(String userText, String replyText) throws SQLException {
        return seedTurn(sessionId, ChatTurnRepository.STATUS_COMPLETED, true, userText, replyText);
    }

    long seedTurn(long session, String status, boolean eligible, String userText, String replyText)
            throws SQLException {
        ChatMessageCipher cipher = new ChatMessageCipher(new ApiKeyEncryptor(), mapper);
        ChatMessageCipher.Encrypted user = cipher.encrypt(userText, CHAT_PASSWORD);
        ChatMessageCipher.Encrypted reply = cipher.encrypt(replyText, CHAT_PASSWORD);
        String requestId = String.format("01a109ca-1100-7000-8000-%012d", ++turnSequence);
        long generation = jdbc.queryForObject("SELECT context_generation FROM ai_chat_session WHERE id = ?",
                Long.class, session);
        Long notebookId = jdbc.queryForObject("SELECT memory_notebook_id FROM ai_chat_session WHERE id = ?",
                Long.class, session);
        boolean scoped = notebookId != null && notebookId > 0;
        jdbc.update("INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher,"
                        + " request_meta_crypto, context_generation, source_scope_type, source_scope_id,"
                        + " source_timezone, runtime_generation, memory_eligible, stored_history_bytes,"
                        + " reserved_history_bytes, persistence_status, started_at, finished_at)"
                        + " VALUES (?, ?, 'RUNNING', 'meta', 'metaMeta', ?, ?, ?, 'Asia/Shanghai', 'run-1', ?, 0, 0,"
                        + " 'pending', ?, ?)", requestId, session, generation,
                scoped ? "NOTEBOOK" : "GLOBAL", scoped ? notebookId : 0L, eligible ? 1 : 0,
                stamp(clock.get()), stamp(clock.get()));
        long turnId = jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE request_id = ?", Long.class, requestId);
        long userMessage = insertMessage(session, "user", "USER", user);
        long assistantMessage = insertMessage(session, "assistant", "ASSISTANT", reply);
        jdbc.update("UPDATE ai_chat_message SET turn_id = ? WHERE id IN (?, ?)", turnId, userMessage,
                assistantMessage);
        jdbc.update("UPDATE ai_chat_turn SET user_message_id = ?, assistant_message_id = ?, status = ?,"
                + " persistence_status = 'saved' WHERE id = ?", userMessage, assistantMessage, status, turnId);
        return turnId;
    }

    private long insertMessage(long session, String role, String turnRole, ChatMessageCipher.Encrypted body) {
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta,"
                        + " turn_role, created_at) VALUES (?, ?, '', ?, ?, ?, ?)", session, role, body.cipher(),
                body.meta(), turnRole, stamp(clock.get()));
        return jdbc.queryForObject("SELECT id FROM ai_chat_message WHERE session_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, session);
    }

    void seedTurns(int count, int characters) throws SQLException {
        for (int index = 0; index < count; index++) {
            seedTurn(pad("问题" + index + " ", characters), pad("回答" + index + " ", characters));
        }
    }

    static String pad(String prefix, int characters) {
        StringBuilder text = new StringBuilder(prefix);
        while (text.length() < characters) {
            text.append("请再展开讲一下这部分的细节与例子");
        }
        return text.toString();
    }

    void wake() throws SQLException {
        metadata.markMaintenanceWake(sessionId);
    }

    void wake(long session) throws SQLException {
        metadata.markMaintenanceWake(session);
    }

    boolean wakePending() {
        Integer value = jdbc.queryForObject("SELECT maintenance_wake_pending FROM ai_chat_session WHERE id = ?",
                Integer.class, sessionId);
        return value != null && value == 1;
    }

    long memoryWatermark() throws SQLException {
        return state().memoryScanAfterTurn();
    }

    long summaryWatermark() throws SQLException {
        return state().summaryScanAfterTurn();
    }

    ChatSessionMetadataRepository.State state() throws SQLException {
        return metadata.read(sessionId).orElseThrow();
    }

    // ---- 库、开关与授权 ----

    void createVaultAndUnlock() {
        MemoryApiResult created = vault.create("fingerprint", "", "01a109ca-1100-7000-8000-0000000000a1");
        if (created.status() != 201 && created.status() != 200) {
            throw new IllegalStateException("记忆库创建失败: " + created.body());
        }
    }

    /** 解锁并登记会话解锁材料：没有这一步，来源正文一律解不开（§2.5）。 */
    void unlockSessions(long... sessions) {
        List<MemoryVaultService.SessionUnlock> unlocks = new ArrayList<>();
        long[] targets = sessions.length == 0 ? new long[]{sessionId} : sessions;
        for (long session : targets) {
            unlocks.add(new MemoryVaultService.SessionUnlock(session, CHAT_PASSWORD));
        }
        MemoryApiResult result = vault.unlock(null, unlocks);
        if (result.status() != 200) {
            throw new IllegalStateException("解锁失败: " + result.body());
        }
    }

    void grantMaintenanceConsent() throws SQLException {
        new AiConfigRepository(jdbc).upsertChat("custom", CHAT_ENDPOINT, "stub-model", "enc", "{}", "{}");
        MemoryPolicyService.Target target = policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE);
        MemoryApiResult granted = policy.grantConsent(MemoryPolicyService.PURPOSE_MAINTENANCE, target.fingerprint(),
                MemoryPolicyService.POLICY_VERSION);
        if (granted.status() != 200) {
            throw new IllegalStateException("授权失败: " + granted.body());
        }
    }

    /** 自动提取要同时开 {@code enabled} 与 {@code autoExtract}（§5.1 开关不合并）。 */
    void enableExtract() throws SQLException {
        update(true, true, null, null);
    }

    /** 压缩只看 {@code compressSession}，长期记忆关掉也能压（§2.4）。 */
    void enableSummary() throws SQLException {
        update(null, null, true, null);
    }

    void enableBoth() throws SQLException {
        update(true, true, true, null);
    }

    void disableExtract() throws SQLException {
        update(null, false, null, null);
    }

    /** 两个维护开关都关：用来测"开关是主动排除，临时等待才留着待处理"（§2.1）。 */
    void disableAll() throws SQLException {
        update(false, false, false, null);
    }

    void setBudgetZone(String zoneId) throws SQLException {
        update(null, null, null, Map.of("budgetTimezone", zoneId));
    }

    /** 本轮回复结束：释放聊天句柄，维护才不再让路。 */
    void releaseTurn(String requestId) {
        handles.release(requestId, handles.find(requestId).orElseThrow());
    }

    void setDailyLimits(int calls, int tokens) throws SQLException {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("dailyCallLimit", calls);
        patch.put("dailyTokenLimit", tokens);
        update(null, null, null, patch);
    }

    private void update(Boolean enabled, Boolean autoExtract, Boolean compress, Map<String, Object> patch)
            throws SQLException {
        MemoryApiResult result = policy.update(policy.current().revision(), enabled, autoExtract, compress, null,
                patch);
        if (result.status() != 200) {
            throw new IllegalStateException("设置更新失败: " + result.body());
        }
    }

    /** 标准前置：库已建好并解锁、开关全开、授权已给。 */
    void readyForMaintenance() throws SQLException {
        createVaultAndUnlock();
        unlockSessions();
        grantMaintenanceConsent();
        enableBoth();
    }

    /** 只开自动提取：摘要那一路也排队时 {@link #onlyJob()} 会被两条任务打乱，排队用例只看提取。 */
    void readyForExtraction() throws SQLException {
        createVaultAndUnlock();
        unlockSessions();
        grantMaintenanceConsent();
        enableExtract();
    }

    // ---- 断言辅助 ----

    MemoryJobRepository.JobRow onlyJob() throws SQLException {
        List<String> ids = jdbc.queryForList("SELECT id FROM ai_memory_job ORDER BY id", String.class);
        if (ids.size() != 1) {
            throw new IllegalStateException("预期只有一条任务，实际 " + ids.size());
        }
        return jobs.find(ids.get(0)).orElseThrow();
    }

    MemoryJobRepository.JobRow job(String id) throws SQLException {
        return jobs.find(id).orElseThrow();
    }

    int jobCount() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_job", Integer.class);
        return value == null ? 0 : value;
    }

    long vaultRevision() throws SQLException {
        return memory.readEnvelope().map(MemoryRepository.VaultEnvelope::revision).orElse(0L);
    }

    long vaultEpoch() throws SQLException {
        return memory.readEnvelope().map(MemoryRepository.VaultEnvelope::epoch).orElse(0L);
    }

    Optional<SessionContextRepository.Checkpoint> checkpoint() throws SQLException {
        return contexts.read(sessionId);
    }

    /** 当天后台已花额度：预算日按假时钟算，与 worker 的口径一致。 */
    UsageRepository.MaintenanceSpend spend() throws SQLException {
        return usage.maintenanceSpend(budgetDate(), budgetZone().getId());
    }

    ZoneId budgetZone() throws SQLException {
        return ZoneId.of(policy.policyOf(policy.current()).budgetTimezone());
    }

    String budgetDate() throws SQLException {
        return Instant.ofEpochMilli(clock.get()).atZone(budgetZone()).toLocalDate().toString();
    }

    int attemptCount(String state) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE state = ?", Integer.class,
                state);
        return value == null ? 0 : value;
    }

    List<String> attemptStates() {
        return jdbc.queryForList("SELECT state FROM ai_usage_attempt ORDER BY created_at, id", String.class);
    }

    int reservedTotal() {
        Integer value = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_tokens), 0) FROM ai_usage_daily",
                Integer.class);
        return value == null ? 0 : value;
    }

    /** 最新入队的那条任务（按插入顺序，不是按 UUID 字典序）。 */
    MemoryJobRepository.JobRow latestJob() throws SQLException {
        String id = jdbc.queryForObject("SELECT id FROM ai_memory_job ORDER BY rowid DESC LIMIT 1", String.class);
        return jobs.find(id).orElseThrow();
    }

    /** 某类型最新的一条：提取与摘要同时在跑时按 kind 定位（§2.4）。 */
    MemoryJobRepository.JobRow latestJob(MemoryJobRepository.Kind kind) throws SQLException {
        String id = jdbc.queryForObject("SELECT id FROM ai_memory_job WHERE kind = ? ORDER BY rowid DESC LIMIT 1",
                String.class, kind.name());
        return jobs.find(id).orElseThrow();
    }

    /** 某类型的活跃（未终态）任务条数。 */
    int activeJobCount(MemoryJobRepository.Kind kind) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_job WHERE kind = ?"
                + " AND status NOT IN ('COMPLETED','SUPERSEDED','FAILED')", Integer.class, kind.name());
        return value == null ? 0 : value;
    }

    MemoryMaintenanceWorker.Drain drain() throws SQLException {
        return worker.drainOnce(clock.get());
    }

    MemoryMaintenanceWorker.Drain drainAt(long epochMilli) throws SQLException {
        clock.set(epochMilli);
        return worker.drainOnce(epochMilli);
    }

    /** 把假时钟推到"最近一条任务真正到点"的时刻再 drain：退避与合并窗口都能直接断言。 */
    MemoryMaintenanceWorker.Drain drainWhenDue() throws SQLException {
        return drainJobWhenDue(latestJob().id());
    }

    /** 按<b>指定</b>任务的到点时刻 drain：队列里已经有好几条时也能精确驱动某一条（§14 P6）。 */
    MemoryMaintenanceWorker.Drain drainJobWhenDue(String jobId) throws SQLException {
        MemoryJobRepository.JobRow row = job(jobId);
        long due = row.nextRunAt() == null ? clock.get() : Math.max(clock.get(), millis(row.nextRunAt()));
        return drainAt(due);
    }

    /**
     * 直接按 §8.5 的口径装载一批来源并排队，绕开排队门限但<b>不</b>绕开批次身份：
     * 用来测只有 worker 才能碰到的分支（比如收益不足门）。
     */
    String enqueueManualJob(MemoryJobRepository.Kind kind, long session, long fromTurnId, long throughTurnId)
            throws SQLException {
        MemoryRepository.VaultEnvelope envelope = memory.readEnvelope().orElseThrow();
        MemoryCryptoService.MemoryKey key = vault.currentKey().orElseThrow();
        boolean summary = kind == MemoryJobRepository.Kind.SUMMARY;
        String promptVersion = summary ? drafter.promptVersion() : extractor.promptVersion();
        int framingTokens = summary ? drafter.framingTokens() : extractor.framingTokens();
        MaintenanceSourceScanner.PreviousSummary previous = summary
                ? scanner.previousSummary(session, stateOf(session).contextGeneration(), envelope.epoch(), key)
                : MaintenanceSourceScanner.PreviousSummary.NONE;
        MaintenanceSourceScanner.Loaded loaded = scanner.load(new Request(kind, session, fromTurnId, throughTurnId,
                stateOf(session).contextGeneration(), stateOf(session).memoryScopeType(),
                stateOf(session).memoryNotebookId() == null ? 0L : stateOf(session).memoryNotebookId(),
                "Asia/Shanghai", envelope.epoch(), policy.current().revision(), previous.revision(),
                previous.modelSummary(), policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE).fingerprint(),
                promptVersion, framingTokens, 0L, CHAT_PASSWORD, key));
        if (!loaded.ready()) {
            throw new IllegalStateException("手动装载失败: " + loaded.outcome());
        }
        return jobs.enqueue(new JobDraft(kind, session, loaded.batch().fromTurnId(), loaded.batch().throughTurnId(),
                stateOf(session).contextGeneration(), stateOf(session).memoryScopeType(),
                stateOf(session).memoryNotebookId() == null ? 0L : stateOf(session).memoryNotebookId(),
                "Asia/Shanghai", envelope.epoch(), policy.current().revision(),
                policy.targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE).fingerprint(), promptVersion,
                loaded.batch().inputRevision(), stamp(clock.get()))).jobId();
    }

    ChatSessionMetadataRepository.State stateOf(long session) throws SQLException {
        return metadata.read(session).orElseThrow();
    }

    /** 假装进程被杀后重启：把假时钟推到租约之后，再 drain 走恢复分支。 */
    MemoryMaintenanceWorker.Drain drainAfterLease() throws SQLException {
        return drainAt(clock.get() + MaintenanceRules.LEASE_MILLIS + 1_000L);
    }

    long turnId(int offsetFromOldest) {
        return jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE session_id = ? ORDER BY id LIMIT 1 OFFSET ?",
                Long.class, sessionId, offsetFromOldest);
    }

    long firstTurnId() {
        return jdbc.queryForObject("SELECT MIN(id) FROM ai_chat_turn WHERE session_id = ?", Long.class, sessionId);
    }

    void deleteTurnContent(long turnId) {
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = ?, source_revision = source_revision + 1,"
                + " user_message_id = NULL, assistant_message_id = NULL WHERE id = ?", stamp(clock.get()), turnId);
        jdbc.update("DELETE FROM ai_chat_message WHERE turn_id = ?", turnId);
    }

    void bumpSourceRevision(long turnId) {
        jdbc.update("UPDATE ai_chat_turn SET source_revision = source_revision + 1 WHERE id = ?", turnId);
    }

    void setTurnStatus(long turnId, String status) {
        jdbc.update("UPDATE ai_chat_turn SET status = ? WHERE id = ?", status, turnId);
    }

    /** 直接改任务行：模拟"上次进程死在这里"留下的中间态。 */
    void forceJobState(String jobId, String status, String leaseToken, String leaseUntil, int attempts) {
        jdbc.update("UPDATE ai_memory_job SET status = ?, lease_token = ?, lease_until = ?, attempts = ?,"
                + " next_run_at = NULL WHERE id = ?", status, leaseToken, leaseUntil, attempts, jobId);
    }

    // ---- 可编程外发端口 ----

    /** 假提取器：按脚本返回候选数量或抛错，并统计被调用次数；脚本用完就回空批次。 */
    static final class StagingExtractor implements MaintenancePorts.Extractor {
        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private AtomicLong clock;
        private long hangMillis;
        private MaintenancePorts.Batch lastBatch;

        void willReturn(int candidates, int rejected) {
            script.add(new MaintenancePorts.CandidateBatch(candidates, rejected,
                    new MaintenancePorts.Usage(1_200L, 200L)));
        }

        void willReturnNothing() {
            script.add(MaintenancePorts.CandidateBatch.none());
        }

        void willFail(String code, boolean retryable) {
            script.add(new MaintenancePorts.AttemptFailure(code, "假提取失败", retryable));
        }

        /** 在"外发期间"执行一段动作：用来制造取消与提交竞争、上游悬挂。 */
        void willInvoke(Runnable action) {
            script.add(action);
        }

        /** 模拟上游悬挂：本次调用先把假时钟推过租约再返回，用来测"悬挂的结果不许提交"。 */
        void hangFor(long millis) {
            hangMillis = millis;
        }

        MaintenancePorts.Batch lastBatch() {
            return lastBatch;
        }

        int calls() {
            return calls.get();
        }

        void resetCalls() {
            calls.set(0);
        }

        @Override
        public String promptVersion() {
            return MaintenanceRules.PROMPT_VERSION_EXTRACT;
        }

        @Override
        public int framingTokens() {
            return 120;
        }

        @Override
        public MaintenancePorts.CandidateBatch extract(MaintenancePorts.Batch batch)
                throws MaintenancePorts.AttemptFailure {
            lastBatch = batch;
            calls.incrementAndGet();
            if (hangMillis > 0) {
                clock.addAndGet(hangMillis);
                hangMillis = 0;
            }
            Object next = script.poll();
            if (next instanceof MaintenancePorts.AttemptFailure failure) {
                throw failure;
            }
            if (next instanceof Runnable action) {
                action.run();
                return MaintenancePorts.CandidateBatch.none();
            }
            return next instanceof MaintenancePorts.CandidateBatch produced ? produced
                    : MaintenancePorts.CandidateBatch.none();
        }
    }

    /** 假摘要器：脚本给草稿，或按"没有产出"结束。 */
    static final class StagingDrafter implements MaintenancePorts.SummaryDrafter {
        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private MaintenancePorts.Batch lastBatch;

        void willReturn(String modelSummary, int estimatedTokens) {
            script.add(new MaintenancePorts.SummaryDraft(modelSummary, estimatedTokens,
                    new MaintenancePorts.Usage(2_000L, 400L)));
        }

        void willReturnNothing() {
            script.add(Optional.empty());
        }

        void willFail(String code, boolean retryable) {
            script.add(new MaintenancePorts.AttemptFailure(code, "假摘要失败", retryable));
        }

        MaintenancePorts.Batch lastBatch() {
            return lastBatch;
        }

        int calls() {
            return calls.get();
        }

        void resetCalls() {
            calls.set(0);
        }

        @Override
        public String promptVersion() {
            return MaintenanceRules.PROMPT_VERSION_SUMMARY;
        }

        @Override
        public int framingTokens() {
            return 120;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<MaintenancePorts.SummaryDraft> draft(MaintenancePorts.Batch batch)
                throws MaintenancePorts.AttemptFailure {
            lastBatch = batch;
            calls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof MaintenancePorts.AttemptFailure failure) {
                throw failure;
            }
            if (next instanceof Optional<?> typed) {
                return (Optional<MaintenancePorts.SummaryDraft>) typed;
            }
            return next instanceof MaintenancePorts.SummaryDraft draft
                    ? Optional.of(draft) : Optional.empty();
        }
    }
}
