package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.JobRow;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 维护排队（执行方案 §8.1、§8.5；contracts §2.1 尾段、§2.4、§2.5）。
 *
 * <p>worker 那一层测"提交与结算"，这里只测把会话上的待处理边界变成任务行的规则：队列形态
 * （每会话每类型 1 个待处理范围、就地合并、同输入重放、每轮扫描有界）、单批 8 轮上限与连续拆批、
 * RUNNING 把扫描截断、永久跳过在排队阶段就收口，以及作用域随范围落库。</p>
 *
 * <p>这里统一走 {@link MaintenanceHarness#readyForExtraction()}：摘要与提取同时在跑时
 * {@code onlyJob()} 会被两条任务打乱，而摘要自己的门禁属 worker 用例。</p>
 */
class MaintenanceQueueTest {
    private static final int TURN_CHARS = 60;

    private MaintenanceHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = MaintenanceHarness.open("p6-queue-");
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    @Test
    void consecutiveTurnsWidenTheSamePendingRangeInsteadOfAddingASibling() throws Exception {
        harness.readyForExtraction();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();

        MaintenanceQueue.Sweep first = harness.queue.sweep(harness.now());
        assertEquals(1, first.queued());
        JobRow job = harness.onlyJob();
        assertEquals(1L, job.fromTurnId());
        assertEquals(4L, job.throughTurnId());
        assertEquals(harness.now() + MaintenanceRules.MERGE_WINDOW_MILLIS,
                MaintenanceHarness.millis(job.nextRunAt()), "成批后先等一个合并窗口（§8.1）");

        // 窗口内又发一条：范围就地加宽、等待时刻重算，绝不并排出第二条
        harness.advance(MaintenanceRules.MERGE_WINDOW_MILLIS - 1_000L);
        harness.seedTurn("补充一句", "再答一句");
        MaintenanceQueue.Sweep second = harness.queue.sweep(harness.now());
        assertEquals(0, second.queued(), "合并不算新排队");
        assertEquals(1, second.merged());
        JobRow merged = harness.onlyJob();
        assertEquals(job.id(), merged.id(), "同一待处理范围只能有一条（§2.4）");
        assertEquals(5L, merged.throughTurnId());
        assertEquals(1, harness.jobCount());
        assertEquals(harness.now() + MaintenanceRules.MERGE_WINDOW_MILLIS,
                MaintenanceHarness.millis(merged.nextRunAt()), "合并等于重开一次静默窗口");
    }

    @Test
    void shortRunIsScheduledOnTheIdleTriggerWhileAFullBatchUsesTheMergeWindow() throws Exception {
        harness.readyForExtraction();
        harness.seedTurns(2, TURN_CHARS);
        harness.wake();

        harness.queue.sweep(harness.now());
        JobRow idle = harness.onlyJob();
        assertEquals(1L, idle.fromTurnId());
        assertEquals(2L, idle.throughTurnId());
        assertEquals(harness.now() + MaintenanceRules.IDLE_TRIGGER_MILLIS,
                MaintenanceHarness.millis(idle.nextRunAt()), "不足阈值就等空闲触发（§8.1）");

        harness.seedTurns(2, TURN_CHARS);
        harness.queue.sweep(harness.now());
        JobRow batch = harness.onlyJob();
        assertEquals(4L, batch.throughTurnId(), "两条新轮次并进同一条待处理范围");
        assertEquals(harness.now() + MaintenanceRules.MERGE_WINDOW_MILLIS,
                MaintenanceHarness.millis(batch.nextRunAt()), "满阈值后改用 5 秒合并窗口");
        assertEquals(0, harness.extractor.calls(), "排队阶段一次都不外发");
    }

    @Test
    void repeatedSweepsReplayTheSameRangeWithoutGrowingTheQueue() throws Exception {
        harness.readyForExtraction();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();

        assertEquals(1, harness.queue.sweep(harness.now()).queued());
        for (int index = 0; index < 5; index++) {
            MaintenanceQueue.Sweep again = harness.queue.sweep(harness.now());
            assertEquals(0, again.queued(), "同输入不再排新行（§2.4）");
            assertEquals(0, again.merged(), "范围没变也不算合并");
            assertEquals(0, again.deferred());
        }
        assertEquals(1, harness.jobCount(), "反复 tick 不会把队列撑大（§8.1 队列形态）");
        assertEquals(0L, harness.memoryWatermark(), "排队本身不推进水位");
    }

    @Test
    void runningTurnStopsTheScanAndTheRangeEndsBeforeIt() throws Exception {
        harness.readyForExtraction();
        long beforeRunning = harness.seedTurn("运行前的一轮", "回答一");
        harness.seedTurn("还有一轮", "回答二");
        // 每会话只允许一条 RUNNING 轮次，所以先把四轮都落成 COMPLETED 再把中间那条改回 RUNNING
        long running = harness.seedTurn("正在生成的一轮", "半句还没写完");
        harness.seedTurn("运行后的一轮", "回答四");
        harness.setTurnStatus(running, ChatTurnRepository.STATUS_RUNNING);
        harness.wake();

        harness.queue.sweep(harness.now());

        JobRow job = harness.onlyJob();
        assertEquals(Kind.EXTRACT, job.kind());
        assertEquals(beforeRunning + 1L, job.throughTurnId(), "扫描停在 RUNNING 之前（§2.5）");
        assertTrue(job.throughTurnId() < running, "正在生成的轮次永远不是来源");
        assertEquals(0L, harness.memoryWatermark(), "还没结算就不该有水");

        // RUNNING 只是暂停扫描，不是永久跳过：它结束后同一范围可以继续往后扩
        harness.setTurnStatus(running, ChatTurnRepository.STATUS_COMPLETED);
        harness.queue.sweep(harness.now());
        assertEquals(4L, harness.onlyJob().throughTurnId(), "同一待处理范围被就地加宽，而不是另起一条");
        assertEquals(1, harness.jobCount());
    }

    @Test
    void tenEligibleTurnsAreCutIntoConsecutiveBatchesOfEight() throws Exception {
        harness.readyForExtraction();
        harness.seedTurns(10, TURN_CHARS);
        harness.wake();

        assertEquals(1, harness.queue.sweep(harness.now()).queued());
        JobRow head = harness.onlyJob();
        assertEquals(1L, head.fromTurnId());
        assertEquals(MaintenanceRules.MAX_TURNS_PER_BATCH, head.throughTurnId(),
                "单批最多 8 个完整轮次，其余排下一批（§8.1）");

        assertEquals(1, harness.drainJobWhenDue(head.id()).committed());
        assertEquals(MaintenanceRules.MAX_TURNS_PER_BATCH, harness.memoryWatermark());

        MaintenanceQueue.Sweep next = harness.queue.sweep(harness.now());
        assertEquals(1, next.queued(), "剩下的两条继续排下一批，不会被跳掉");
        JobRow tail = harness.latestJob(Kind.EXTRACT);
        assertEquals(9L, tail.fromTurnId());
        assertEquals(10L, tail.throughTurnId());
    }

    @Test
    void unusableSourcesAloneCreateNoTaskAndSettleTheWake() throws Exception {
        harness.readyForExtraction();
        harness.setTurnStatus(harness.seedTurn("流式被中断的一轮", "半句回答"),
                ChatTurnRepository.STATUS_INTERRUPTED);
        harness.deleteTurnContent(harness.seedTurn("已被用户清理的一轮", "回答"));
        harness.wake();

        MemoryMaintenanceWorker.Drain sweep = harness.drain();

        assertEquals(0, sweep.enqueued(), "合格来源数是 0，连装载都不必（§8.5）");
        assertEquals(0, harness.jobCount());
        assertEquals(0, harness.extractor.calls(), "非终态与已清理内容一个字都不发（§2.5）");
        assertEquals(0L, harness.memoryWatermark(), "没有可检查的来源就不制造水位");
        assertFalse(harness.wakePending(), "两类都没有剩余范围，唤醒收口而不是每轮重扫（§2.1）");
    }

    @Test
    void notebookScopeTravelsWithTheQueuedRange() throws Exception {
        harness.createVaultAndUnlock();
        harness.grantMaintenanceConsent();
        harness.enableExtract();
        long notebookSession = harness.notebookSession(8);
        harness.unlockSessions(notebookSession);
        for (int index = 0; index < 4; index++) {
            harness.seedTurn(notebookSession, ChatTurnRepository.STATUS_COMPLETED, true,
                    MaintenanceHarness.pad("笔记本问题" + index + " ", TURN_CHARS),
                    MaintenanceHarness.pad("笔记本回答" + index + " ", TURN_CHARS));
        }
        harness.wake(notebookSession);

        assertEquals(1, harness.queue.sweep(harness.now()).queued());

        JobRow job = harness.onlyJob();
        assertEquals(notebookSession, job.sessionId(), "全局会话没有待处理标记，不该被排上");
        assertEquals(Kind.EXTRACT, job.kind());
        assertEquals("NOTEBOOK", job.sourceScopeType(), "作用域随范围落库，认领时按同一口径重算（§2.4）");
        assertEquals(8L, job.sourceScopeId());
        assertTrue(job.inputRevision() != null && job.inputRevision().length() > 20, "批次身份是派生摘要，不是正文");
    }

    @Test
    void oneSweepOnlyLooksAtABoundedNumberOfSessions() throws Exception {
        harness.createVaultAndUnlock();
        harness.grantMaintenanceConsent();
        harness.enableExtract();
        long[] sessions = new long[MaintenanceQueue.SESSIONS_PER_SWEEP + 5];
        int index = 0;
        for (long id = 2_000L; id < 2_000L + MaintenanceQueue.SESSIONS_PER_SWEEP + 5L; id++) {
            harness.jdbc.update("INSERT INTO ai_chat_session(id, title) VALUES (?, '批量会话')", id);
            harness.seedTurn(id, ChatTurnRepository.STATUS_COMPLETED, true, "问题 " + id, "回答 " + id);
            harness.wake(id);
            sessions[index++] = id;
        }
        harness.unlockSessions(sessions);

        MaintenanceQueue.Sweep sweep = harness.queue.sweep(harness.now());

        assertEquals(MaintenanceQueue.SESSIONS_PER_SWEEP, sweep.sessionsScanned(),
                "每轮只看有界的一批会话，剩下的下次再来（§8.1）");
        assertEquals(MaintenanceQueue.SESSIONS_PER_SWEEP, sweep.queued());
        assertEquals(MaintenanceQueue.SESSIONS_PER_SWEEP, harness.jobCount(), "队列不会在一次 tick 里被撑开");
    }

    @Test
    void theQueueCarriesWatermarksAndIdentityNotPlaintext() throws Exception {
        harness.readyForExtraction();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        assertEquals(1, harness.queue.sweep(harness.now()).queued());

        Map<String, Object> row = harness.jdbc.queryForMap("SELECT * FROM ai_memory_job");
        assertEquals(java.util.Set.of("id", "kind", "session_id", "from_turn_id", "through_turn_id",
                "context_generation", "source_scope_type", "source_scope_id", "source_timezone", "vault_epoch",
                "policy_revision", "target_fingerprint", "prompt_version", "input_revision", "status",
                "lease_token", "lease_until", "attempts", "outcome_code", "next_run_at", "error_code",
                "created_at", "updated_at"), row.keySet(), "队列行只许有水位与身份列（§8.5）");
        String plaintext = MaintenanceHarness.pad("问题0 ", TURN_CHARS);
        for (Map.Entry<String, Object> column : row.entrySet()) {
            String value = String.valueOf(column.getValue());
            assertFalse(value.contains(plaintext), column.getKey() + " 里不许出现正文");
            assertFalse(value.contains("回答0"), column.getKey() + " 里不许出现正文");
        }
        assertTrue(String.valueOf(row.get("input_revision")).matches("[A-Za-z0-9+/=]{44}"),
                "批次身份是派生摘要，不是正文拼接");
    }

    @Test
    void missingVaultKeepsTheWakeButQueuesNothing() throws Exception {
        harness.grantMaintenanceConsent();
        harness.enableExtract();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();

        assertEquals(0, harness.drain().enqueued(), "没有库就没有批次身份，不能排队（§2.5）");
        assertEquals(0, harness.jobCount());
        assertTrue(harness.wakePending(), "缺库只是等待，不丢 pending（§2.1）");

        harness.createVaultAndUnlock();
        harness.unlockSessions();
        assertEquals(1, harness.queue.sweep(harness.now()).queued(), "补上库之后同一批照样排得上");
        JobRow job = harness.onlyJob();
        assertEquals(1L, job.fromTurnId());
        assertEquals(4L, job.throughTurnId());
        assertEquals(MemoryJobRepository.STATUS_QUEUED, job.status());
    }
}
