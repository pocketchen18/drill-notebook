package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.JobRow;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.repository.UsageRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.ChatMessageCipher;
import com.drillnotebook.app.service.ConversationProjection;
import com.drillnotebook.app.service.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 后台维护 worker 的行为（执行方案 §14 P6、§8.2、§8.5；contracts §2.4、§2.5、§2.6、§7）。
 *
 * <p>时间是一条假时钟，提取与摘要是可编程端口，所以这些用例覆盖的正是那些<b>只能靠精确时刻才能证伪</b>
 * 的路径：断电各点恢复、claim 过期、取消与提交竞争、上游悬挂、有聊天时让路、预算跨午夜、
 * unknown usage 预留不返还（§14 P6「不靠长 sleep」）。</p>
 *
 * <p>估算口径：一条 60 字符的中文轮次约 188 估算 Token，所以"8 轮不够压力、9 轮过 1,680"
 * "6 轮不够新增量、7 轮过 1,024"这类边界在测试里是可以手算核对的。</p>
 */
class MemoryMaintenanceWorkerTest {
    private static final int TURN_CHARS = 60;
    private static final String NEXT_BUDGET_DAY = "2026-10-12T00:00:00.000Z";
    private static final long NOTEBOOK = 77L;
    private static final long OTHER_NOTEBOOK = 78L;

    private MaintenanceHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = MaintenanceHarness.open("p6-worker-");
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    // ---- §14 P6 退出条件：一次输入只提交一次 ----

    @Test
    void oneBatchOfTurnsIsSubmittedExactlyOnce() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();

        MemoryMaintenanceWorker.Drain queued = harness.drain();
        assertEquals(1, queued.enqueued(), "满 4 轮先形成待处理范围（§8.1）");
        assertEquals(0, queued.claimed(), "合并窗口没到就不认领");
        assertEquals(0, harness.extractor.calls(), "没到点一个字都不发");

        harness.extractor.willReturn(0, 0);
        MemoryMaintenanceWorker.Drain done = harness.drainWhenDue();
        assertEquals(1, done.committed());
        assertEquals(1, harness.extractor.calls(), "退出条件：一次输入只提交一次");
        JobRow job = harness.onlyJob();
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, job.status());
        assertEquals("NO_NEW_INFORMATION", job.outcomeCode(), "占位提取器不产出候选");
        assertEquals(4L, harness.memoryWatermark(), "水位推进到最后一条已检查轮次（§2.5）");
        assertEquals(List.of("SETTLED"), harness.attemptStates(), "厂商回了数字就按实际结算");
        assertEquals(1_400L, harness.spend().chargedTokens(), "1,200 + 200，不按预留冒充");
        assertEquals(1, harness.spend().attempts(), "当天尝试数就是那一次（§2.6）");

        harness.extractor.resetCalls();
        MemoryMaintenanceWorker.Drain again = harness.drainAt(harness.now() + 60_000L);
        assertEquals(0, harness.extractor.calls(), "同输入不会被重跑（§2.4）");
        assertEquals(1, harness.jobCount(), "同一会话同一类型只留一个待处理范围（§8.1）");
        assertEquals(0, again.claimed(), "已终态的行不再被认领");
    }

    @Test
    void defaultSettingsLeaveTheBackendCompletelySilent() throws Exception {
        harness.seedTurns(8, TURN_CHARS);
        harness.wake();

        MemoryMaintenanceWorker.Drain drain = harness.drainAt(harness.now() + 600_000L);

        assertEquals(0, drain.enqueued(), "开关默认关：不产生任何任务（§5.1）");
        assertEquals(0, drain.claimed());
        assertEquals(0, harness.jobCount());
        assertEquals(0, harness.extractor.calls(), "后台空闲没有远程调用（§13.3）");
        assertTrue(harness.attemptStates().isEmpty(), "一次尝试都没记");
        assertEquals(0L, harness.memoryWatermark());
        assertFalse(harness.wakePending(), "开关是用户主动排除，不算待处理（§2.1、D54）");
    }

    // ---- §2.5 缺源与等待态 ----

    @Test
    void lockedVaultWaitsAndNeverAdvancesTheWatermark() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        harness.vault.lock();

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.waiting());
        JobRow job = harness.onlyJob();
        assertEquals(MemoryJobRepository.STATUS_WAITING_UNLOCK, job.status());
        assertEquals(0, job.attempts(), "等待解锁不算一次远程尝试（§8.2 第 2 条）");
        assertEquals(0, harness.extractor.calls());
        assertEquals(0L, harness.memoryWatermark(), "没扫过的源不许推进水位");
        assertTrue(harness.attemptStates().isEmpty(), "没过门禁连预留都不该有");
        assertTrue(harness.wakePending(), "解锁等待不丢 pending（§2.1）");
    }

    @Test
    void missingSessionPasswordWaitsInsteadOfMarkingTheSourceMissing() throws Exception {
        harness.createVaultAndUnlock();
        harness.grantMaintenanceConsent();
        harness.enableBoth();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        // 只解锁记忆库、不登记会话解锁材料：聊天正文这时无解
        harness.vault.lock();
        harness.vault.unlock(null, List.of());

        assertEquals(0, harness.drain().enqueued(), "解不开就不该排这一批");
        assertEquals(0, harness.jobCount(), "临时等待不能变成任务，也不能变成永久跳过");
        assertEquals(0L, harness.memoryWatermark(), "把解不开的源记成已检查等于永久丢弃（§2.5）");
        assertTrue(harness.wakePending(), "待处理标记留着");

        harness.unlockSessions();
        harness.drain();
        assertEquals(1, harness.drainWhenDue().committed(), "补上会话解锁材料后同一批照常处理");
        assertEquals(4L, harness.memoryWatermark());
        assertEquals(0, harness.state().memorySkippedCount(), "解锁等待一次都不算缺源");
    }

    @Test
    void oversizedSingleTurnIsSkippedWholeAtQueueTime() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurn(MaintenanceHarness.pad("超长发言 ", 5_000), "短回复");
        harness.wake();

        MemoryMaintenanceWorker.Drain drain = harness.drain();

        assertEquals(0, drain.enqueued(), "本地就装不下，不必花一次外发（§8.2 第 4 条）");
        assertEquals(0, harness.jobCount());
        assertEquals(0, harness.extractor.calls(), "装不下的批次一个字都不发");
        assertEquals(1L, harness.memoryWatermark(), "整轮永久跳过也算已检查至此（§2.5）");
        assertEquals(1L, harness.state().memorySkippedCount(), "跳过只存计数（§2.5）");
        assertEquals(MaintenanceRules.NOTICE_SOURCE_TOO_LARGE, harness.state().lastScanNotice());
        assertFalse(harness.wakePending(), "死源已越过，唤醒可以收口（§2.1）");
    }

    @Test
    void failedAndDeletedTurnsAreCountedAndSkippedOverByTheWatermark() throws Exception {
        harness.readyForMaintenance();
        long failed = harness.seedTurn("失败的一轮原文", "没有回复");
        long deleted = harness.seedTurn("要被清理的一轮", "回复内容");
        long third = harness.seedTurn("用户问背诵计划", "建议每天二十个");
        long fourth = harness.seedTurn("又问了一次", "再答一次");
        harness.setTurnStatus(failed, ChatTurnRepository.STATUS_FAILED);
        harness.deleteTurnContent(deleted);
        harness.wake();

        assertEquals(1, harness.drain().enqueued(), "只有 2 条合格来源，走 60 秒空闲触发（§8.1）");
        assertEquals(1, harness.drainWhenDue().committed());

        assertEquals(fourth, harness.memoryWatermark(), "跳过的轮次被水位越过");
        MaintenancePorts.Batch batch = harness.extractor.lastBatch();
        assertEquals(2, batch.turns().size(), "只有两条合格完整轮次进了输入");
        assertEquals(List.of(third, fourth), batch.turns().stream().map(MaintenancePorts.SourceTurn::turnId).toList(),
                "失败轮与已清理轮都不该出现在输入里");
        assertEquals(2L, harness.state().memorySkippedCount(), "非终态 + 已清理各计一次（§2.5）");
        assertEquals(MaintenanceRules.ExtractTrigger.IDLE,
                MaintenanceRules.extractTrigger(batch.turns().size()));
    }

    @Test
    void clearedSourcesReportTheirOwnSkipReasonInsteadOfTheTerminalCode() throws Exception {
        harness.readyForExtraction();
        harness.deleteTurnContent(harness.seedTurn("先清掉的一轮", "回复内容"));
        harness.deleteTurnContent(harness.seedTurn("又清掉的一轮", "回复内容"));
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();

        assertEquals(1, harness.drain().enqueued());
        harness.extractor.willReturn(2, 0);
        assertEquals(1, harness.drainWhenDue().committed());

        assertEquals("APPLIED", harness.onlyJob().outcomeCode(), "还有可用来源就照常出结果");
        assertEquals(6L, harness.memoryWatermark(), "已清理的轮次也算已检查至此");
        assertEquals(2L, harness.state().memorySkippedCount());
        assertEquals(MaintenanceRules.NOTICE_SOURCE_DELETED, harness.state().lastScanNotice(),
                "「最近跳过原因」存的是跳过理由本身，不是任务终态码（§2.5）");
    }

    @Test
    void batchClearedBeforeTheClaimEndsAsSourceDeletedWithoutSending() throws Exception {
        harness.readyForExtraction();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        assertEquals(1, harness.drain().enqueued(), "先按正常批次排好一条任务");
        for (int offset = 0; offset < 4; offset++) {
            harness.deleteTurnContent(harness.turnId(offset));
        }
        harness.extractor.willReturn(1, 0);

        MemoryMaintenanceWorker.Drain done = harness.drainWhenDue();

        assertEquals(1, done.committed());
        assertEquals(0, harness.extractor.calls(), "整批都被清理：一个字都不发，也不发占位（§2.5）");
        JobRow job = harness.onlyJob();
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, job.status());
        assertEquals(MaintenanceRules.NOTICE_SOURCE_DELETED, job.outcomeCode(),
                "认领前就全没了的批要如实落成 SOURCE_DELETED");
        assertEquals(4L, harness.memoryWatermark());
        assertEquals(4L, harness.state().memorySkippedCount());
        assertEquals(List.of(), harness.attemptStates(), "没有外发就不该有预留");
    }

    // ---- 门禁与竞争 ----

    @Test
    void staleConsentMakesTheJobWaitWithoutConsumingAnAttempt() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        // 换模型：授权指纹立刻过期，但没有任何代码去"撤销"它
        harness.jdbc.update("UPDATE ai_config SET model = 'other-model' WHERE purpose = 'chat'");

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.waiting());
        JobRow job = harness.onlyJob();
        assertEquals(MemoryJobRepository.STATUS_WAITING_CONSENT, job.status());
        assertEquals(0, job.attempts());
        assertEquals(0L, harness.memoryWatermark());
        assertEquals(0, harness.extractor.calls());
        assertTrue(harness.attemptStates().isEmpty(), "没过门禁连预留都不该有");
        assertTrue(harness.wakePending(), "授权等待不丢 pending（§2.1）");
    }

    @Test
    void chatTurnMakesMaintenanceGiveWay() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String requestId = UuidV7.generate(harness.now());
        harness.handles.acquire(requestId, harness.session());

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.yielded(), "聊天优先：后台不抢占同一个维护槽（§8.1）");
        assertEquals(0, harness.extractor.calls());
        JobRow job = harness.onlyJob();
        assertEquals(MemoryJobRepository.STATUS_QUEUED, job.status(), "让路只是推迟，不是作废");
        assertEquals(0, job.attempts());
        assertEquals(0L, harness.memoryWatermark());

        harness.releaseTurn(requestId);
        assertEquals(1, harness.drainWhenDue().committed(), "本轮回复结束后再处理");
    }

    @Test
    void cancelBetweenSendAndCommitDiscardsTheResult() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.onlyJob().id();
        harness.extractor.willInvoke(() -> cancelQuietly(jobId));

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.rejected(), "取消与提交竞争由租约 CAS 分胜负（§7）");
        assertEquals(0, drain.committed());
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, harness.job(jobId).status());
        assertEquals(0L, harness.memoryWatermark(), "结果被丢弃时水位也不能动");
        assertEquals(1, harness.extractor.calls(), "外发确实发生过，只是结果不许落库");
    }

    @Test
    void revokingConsentBetweenSendAndCommitDiscardsTheResult() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.onlyJob().id();
        harness.extractor.willInvoke(
                () -> harness.policy.revokeConsent(MemoryPolicyService.PURPOSE_MAINTENANCE));

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.rejected(), "授权撤销后旧结果必须被租约/CAS 拒绝（§5.1）");
        assertEquals(0, drain.committed());
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, harness.job(jobId).status());
        assertEquals(0L, harness.memoryWatermark(), "结果被丢弃时水位也不能动");
        assertEquals(1, harness.extractor.calls(), "外发确实发生过，只是结果不许落库");
    }

    @Test
    void sourceEditedWhileRetryingSupersedesTheStaleBatchAndRequeuesFreshly() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.latestJob().id();
        harness.extractor.willFail("UPSTREAM_500", true);
        assertEquals(1, harness.drainWhenDue().retried());

        harness.bumpSourceRevision(harness.firstTurnId());
        MemoryMaintenanceWorker.Drain stale = harness.drainJobWhenDue(jobId);

        assertEquals(1, stale.superseded(), "RETRY 行占着范围，不能被合并改写（§2.4）");
        assertEquals(0, stale.committed());
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, harness.job(jobId).status());
        assertEquals(0L, harness.memoryWatermark(), "旧批结果作废时水位不动");
        assertEquals(1, harness.spend().attempts(), "作废不额外花当天额度");

        harness.extractor.willReturn(0, 0);
        MemoryMaintenanceWorker.Drain fresh = harness.drainAt(harness.now() + 60_000L);
        assertEquals(1, fresh.enqueued(), "按新的批次身份重新入队");
        assertEquals(1, fresh.committed(), "新身份的这一批照常被处理");
        assertEquals(4L, harness.memoryWatermark());
        assertEquals(2, harness.jobCount());
    }

    @Test
    void hungUpstreamCommitsNothingAndItsReservationIsNotRefunded() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        harness.extractor.hangFor(MaintenanceRules.LEASE_MILLIS + 5_000L);

        MemoryMaintenanceWorker.Drain hung = harness.drainWhenDue();
        assertEquals(1, hung.rejected(), "租约过期的结果不许提交");
        assertEquals(0L, harness.memoryWatermark());
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, harness.onlyJob().status());

        MemoryMaintenanceWorker.Drain recovered = harness.drainAfterLease();
        assertEquals(1, recovered.recovered(), "陈旧租约由下一轮 drain 收尾");
        JobRow retried = harness.onlyJob();
        assertEquals(MemoryJobRepository.STATUS_RETRY, retried.status());
        assertEquals(1, retried.attempts(), "悬挂的一次要占用重试额度（§8.1）");
        assertEquals(List.of("UNKNOWN"), harness.attemptStates(), "SENT 未结算按 UNKNOWN 消费预留（§2.6）");
        assertEquals(MaintenanceRules.RESERVE_TOKENS, harness.spend().chargedTokens(), "预留不返还");
        assertEquals(0, harness.spend().reservedTokens(), "飞行预留已经收干");
    }

    @Test
    void lockingTheVaultMidFlightDiscardsTheInFlightResult() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.onlyJob().id();
        // 外发期间用户点了锁定：这把钥匙保护的内容此刻不再外泄，结果不能"等写完再说"
        harness.extractor.willInvoke(() -> harness.vault.lock());

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.rejected(), "锁定后的飞行结果不许落库（§6.1.7、S11）");
        assertEquals(0, drain.committed());
        assertEquals(0L, harness.memoryWatermark(), "连水位也不推进");
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, harness.job(jobId).status(),
                "不提交也不作废：交给陈旧租约收尾，解锁后重来");
        assertEquals(1, harness.extractor.calls(), "外发确实发生过");
        assertEquals(List.of("UNKNOWN"), harness.attemptStates(), "已经发出去的钱不退还（§2.6）");
        assertEquals(MaintenanceRules.RESERVE_TOKENS, harness.spend().chargedTokens());

        assertEquals(0, harness.drainAt(harness.now() + 60_000L).enqueued(), "飞行中的范围仍占着名额");
        assertEquals(1, harness.drainAfterLease().recovered(), "陈旧租约由下一轮 drain 收尾");
        assertEquals(MemoryJobRepository.STATUS_RETRY, harness.job(jobId).status());
        harness.drainJobWhenDue(jobId);
        assertEquals(MemoryJobRepository.STATUS_WAITING_UNLOCK, harness.job(jobId).status(),
                "没解锁之前只会等待，不会再试第二次（§8.2 第 2 条）");
        assertEquals(1, harness.job(jobId).attempts(), "被丢弃的结果只吃掉租约那一次尝试");
        assertEquals(0L, harness.memoryWatermark());
    }

    // ---- 预算 ----

    @Test
    void dailyCallCapPausesTheBackendAndResumesOnTheNextBudgetDay() throws Exception {
        harness.readyForMaintenance();
        harness.setDailyLimits(1, 30_000);
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        harness.extractor.willReturn(0, 0);
        assertEquals(1, harness.drainWhenDue().committed(), "当天第一次正常提交");
        assertEquals(1, harness.spend().attempts());

        harness.seedTurns(4, TURN_CHARS);
        harness.extractor.resetCalls();
        MemoryMaintenanceWorker.Drain drained = harness.drainAt(harness.now() + 600_000L);
        assertEquals(1, drained.enqueued(), "第二批照样排队");
        assertEquals(1, drained.waiting(), "当天额度取先达到者（§2.6）");
        assertEquals(0, drained.committed());
        JobRow paused = harness.latestJob(Kind.EXTRACT);
        assertEquals(MemoryJobRepository.STATUS_PAUSED_BUDGET, paused.status());
        assertEquals(0, paused.attempts(), "暂停不算一次远程尝试（§8.2 第 2 条）");
        assertEquals(0, harness.extractor.calls(), "没过门禁就不许外发");
        assertEquals(1, harness.spend().attempts(), "暂停不记当天尝试");
        assertEquals(MaintenanceHarness.millis(NEXT_BUDGET_DAY), MaintenanceHarness.millis(paused.nextRunAt()),
                "探视时刻就是下一个预算日零点");

        MemoryMaintenanceWorker.Drain nextDay = harness.drainJobWhenDue(paused.id());
        assertEquals(1, nextDay.committed(), "跨到下一个预算日就能继续（§2.6）");
        assertEquals("2026-10-12", harness.budgetDate());
        assertEquals(1, harness.spend().attempts(), "第二天的尝试记在另一天上");
        assertEquals(2, harness.attemptStates().size());
        assertEquals(1, harness.usage.maintenanceSpend("2026-10-11", "UTC").attempts(), "昨天那一笔不会被改记");
    }

    @Test
    void unknownUsageConsumesTheWholeReservation() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        harness.extractor.willReturnNothing();

        assertEquals(1, harness.drainWhenDue().committed());

        assertEquals(List.of("UNKNOWN"), harness.attemptStates());
        assertEquals(MaintenanceRules.RESERVE_TOKENS, harness.spend().chargedTokens(),
                "没有用量就按预留全额记账（§2.6）");
        assertEquals(0, harness.spend().reservedTokens());
    }

    @Test
    void emptyAndRejectedCandidateBatchesAreBothTerminal() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        long revisionBefore = harness.vaultRevision();
        harness.extractor.willReturnNothing();

        assertEquals(1, harness.drainWhenDue().committed());
        assertEquals("NO_NEW_INFORMATION", harness.latestJob().outcomeCode());

        harness.seedTurns(4, TURN_CHARS);
        harness.extractor.willReturn(0, 3);
        MemoryMaintenanceWorker.Drain second = harness.drainAt(harness.now() + 600_000L);
        assertEquals(1, second.committed());
        assertEquals("NO_VALID_CANDIDATES", harness.latestJob(Kind.EXTRACT).outcomeCode());
        assertEquals(revisionBefore, harness.vaultRevision(),
                "P6 不写事实：内容 revision 不递增、索引不重建（§8.2 第 9 条）");
    }

    // ---- 断电各点恢复 ----

    @Test
    void crashAfterReservationBeforeSendReleasesIt() throws Exception {
        String jobId = crashWithAttempt("RESERVED");

        MemoryMaintenanceWorker.Drain drain = harness.drainAfterLease();

        assertEquals(1, drain.recovered());
        assertEquals(List.of("RELEASED"), harness.attemptStates(), "可证明未发出的预留才释放（§2.6）");
        assertEquals(0, harness.spend().chargedTokens());
        assertEquals(0, harness.spend().attempts(), "释放后当天额度退回");
        assertEquals(MemoryJobRepository.STATUS_RETRY, harness.job(jobId).status());
        assertEquals(1, harness.job(jobId).attempts());
    }

    @Test
    void crashAfterSendBeforeSettleConsumesTheReservation() throws Exception {
        String jobId = crashWithAttempt("SENT");

        assertEquals(1, harness.drainAfterLease().recovered());

        assertEquals(List.of("UNKNOWN"), harness.attemptStates());
        assertEquals(MaintenanceRules.RESERVE_TOKENS, harness.spend().chargedTokens());
        assertEquals(1, harness.spend().attempts());
        assertEquals(MemoryJobRepository.STATUS_RETRY, harness.job(jobId).status());
    }

    @Test
    void crashAfterSettleNeverDoubleCounts() throws Exception {
        String jobId = crashWithAttempt("SETTLED");

        assertEquals(1, harness.drainAfterLease().recovered());

        assertEquals(List.of("SETTLED"), harness.attemptStates(), "同一 attempt 只结算一次（§2.6）");
        assertEquals(1_400, harness.spend().chargedTokens(), "按实际 1,200 + 200 记，不再叠加预留");
        assertEquals(1, harness.spend().attempts());
        assertEquals(MemoryJobRepository.STATUS_RETRY, harness.job(jobId).status());
    }

    @Test
    void committedBacklogSettlesTheStaleWakeFlag() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableExtract();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        assertEquals(1, harness.drainWhenDue().committed());
        assertFalse(harness.wakePending(), "两类范围都没有剩余时唤醒自动落回 false（§2.1）");

        harness.wake();
        assertTrue(harness.wakePending(), "模拟\"提交成功但没来得及收口唤醒\"");
        harness.extractor.resetCalls();
        MemoryMaintenanceWorker.Drain idle = harness.drainAt(harness.now() + 60_000L);

        assertEquals(0, idle.claimed(), "没有可发内容就不该再认领");
        assertEquals(0, idle.enqueued(), "同输入不会再排一条（§2.4）");
        assertEquals(0, harness.extractor.calls());
        assertFalse(harness.wakePending(), "陈旧唤醒标记由下一轮静默收口");
    }

    // ---- 摘要（§8.4：排队门禁在 P6，coverage 换代与检查点组装在 P8b）----

    @Test
    void summaryIsQueuedOnlyAfterPressureVolumeAndCooldown() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(8, TURN_CHARS);
        harness.wake();

        assertEquals(0, harness.drain().enqueued(), "近期正文压力不到 2,400 的 70% 不排队（§8.4 第 1 条）");

        harness.seedTurns(1, TURN_CHARS);
        harness.drafter.willReturn("已确认：每天背二十个词", 300);
        MemoryMaintenanceWorker.Drain done = harness.drain();
        assertEquals(1, done.enqueued(), "压力过了、新增量也过了");
        assertEquals(1, done.committed(), "摘要没有合并窗口，排到就发（§8.4）");
        assertEquals(1, harness.drafter.calls());
        assertEquals("APPLIED", harness.latestJob(Kind.SUMMARY).outcomeCode());
        assertEquals(1L, harness.checkpoint().orElseThrow().revision(), "净缩减足够才发布新检查点");
        assertNotNull(harness.state().summaryLastAttemptAt(), "实际外发之前就记尝试（§2.4）");

        // 提交后两类范围都清了，唤醒被收口；再来的轮次要靠新的完成事件重新置位（§2.1）
        harness.seedTurns(9, TURN_CHARS);
        harness.wake();
        assertEquals(0, harness.drainAt(harness.now() + 60_000L).enqueued(), "还在 120 秒冷却里");
        assertEquals(1, harness.drainAt(harness.now() + 61_000L).enqueued(), "过了冷却才能继续压");
        assertEquals(2, harness.drafter.calls());
    }

    @Test
    void summaryWithoutNetReductionKeepsTheOldCheckpoint() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(3, TURN_CHARS);
        // 手动入队：只绕开排队门限，批次身份仍由装载器按 §8.5 算
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(2));
        // 三条轮次约 564 估算 Token，草稿 400 → 净缩减 164 < 256
        harness.drafter.willReturn("这一批其实没什么可压缩的，把三条问答原样再说了一遍", 400);

        assertEquals(1, harness.drainWhenDue().committed());

        JobRow settled = harness.job(jobId);
        assertEquals("NO_COMPRESSION_GAIN", settled.outcomeCode());
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, settled.status());
        assertTrue(harness.checkpoint().isEmpty(), "无收益就不写新版检查点（§8.4 第 4 条）");
        assertEquals(3L, harness.summaryWatermark(), "无收益同样推进已检查水位，同输入不再反复压");
        assertEquals(1, harness.spend().attempts(), "这次外发确实花了额度，所以照记");
    }

    @Test
    void publishedCheckpointOnlyClaimsTurnsItActuallyCovered() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(12, TURN_CHARS);
        harness.wake();
        harness.drafter.willReturn("要点：先背再练，每天二十个", 200);

        assertEquals(1, harness.drain().committed());

        SessionContextRepository.Checkpoint row = harness.checkpoint().orElseThrow();
        assertEquals(8L, row.throughTurnId(), "本批只装了 8 轮（§8.1 单批上限）");
        assertTrue(row.throughTurnId() < harness.turnId(11), "最近两条完整轮次始终排除在摘要源之外（§8.4）");
        String payload = harness.crypto.decrypt(harness.vault.currentKey().orElseThrow(), "ai_session_context",
                String.valueOf(harness.session()), row.revision(), row.payloadCipher(), row.cryptoMeta());
        Map<String, Object> parsed = harness.mapper.readValue(payload, Map.class);
        assertEquals(2, ((Number) parsed.get("schemaVersion")).intValue());
        assertEquals("要点：先背再练，每天二十个", parsed.get("modelSummary"));
        assertEquals(MaintenanceRules.PROMPT_VERSION_SUMMARY, parsed.get("producer"));
        List<?> coverage = (List<?>) parsed.get("serverCoverage");
        assertEquals(1, coverage.size(), "覆盖范围只有一段连续轮次（§2.5）");
        Map<?, ?> first = (Map<?, ?>) coverage.get(0);
        assertEquals(harness.firstTurnId(), ((Number) first.get("firstTurnId")).longValue());
        assertEquals(row.throughTurnId(), ((Number) first.get("lastTurnId")).longValue());
        assertEquals(0, harness.extractor.calls(), "压缩不触发提取端口");
        assertEquals(1, harness.drafter.calls());
        assertEquals(8L, harness.summaryWatermark());
    }

    @Test
    void newerSummaryBatchInheritsAndMergesTheOlderCoverage() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(12, TURN_CHARS);
        harness.wake();
        harness.drafter.willReturn("要点：先背再练，每天二十个", 200);
        assertEquals(1, harness.drain().committed());
        assertEquals(8L, harness.checkpoint().orElseThrow().throughTurnId());

        // 会话又聊了两轮，第二批只装中间那段；旧批的八轮必须继续算已整理
        harness.seedTurns(2, TURN_CHARS);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.turnId(8),
                harness.turnId(11));
        harness.drafter.willReturn("要点不变，另加了错题本用法", 180);

        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        SessionContextRepository.Checkpoint row = harness.checkpoint().orElseThrow();
        assertEquals(2L, row.revision());
        assertEquals(harness.turnId(11), row.throughTurnId());
        List<?> coverage = (List<?>) payloadOf(row).get("serverCoverage");
        assertEquals(1, coverage.size(), "相邻的两段合并成一段，不留下两条可拼的碎片（§2.5）");
        Map<?, ?> only = (Map<?, ?>) coverage.get(0);
        assertEquals(harness.firstTurnId(), endpoint(only, "firstTurnId"),
                "第二批必须继承第一批的覆盖：否则读侧会把已整理的十轮重新拼进近期原文窗口（§8.4 第 4-5 条）");
        assertEquals(harness.turnId(11), endpoint(only, "lastTurnId"));
        assertEquals(row.throughTurnId(), harness.summaryWatermark());
    }

    @Test
    void checkpointThroughStaysAtTheCheckedBoundWhenTheTailIsSkipped() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(4, TURN_CHARS);
        // 尾轮的原话已被用户清理：永久跳过，它没被整理，但"这一轮确实检查过了"要记进水位（§2.5）
        harness.deleteTurnContent(harness.turnId(3));
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(3));
        harness.drafter.willReturn("要点：先背再练", 100);

        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        SessionContextRepository.Checkpoint row = harness.checkpoint().orElseThrow();
        assertEquals(harness.turnId(3), row.throughTurnId(), "through 记的是已检查上界，不是最后一条覆盖轮次");
        List<?> coverage = (List<?>) payloadOf(row).get("serverCoverage");
        assertEquals(1, coverage.size());
        Map<?, ?> only = (Map<?, ?>) coverage.get(0);
        assertEquals(harness.turnId(2), endpoint(only, "lastTurnId"), "被跳过的那轮不算已整理（§2.5）");
        assertEquals(harness.turnId(3), harness.summaryWatermark(), "永久跳过照样推进水位，不然同一段反复重扫");
        assertEquals(1L, harness.stateOf(harness.session()).summarySkippedCount());
    }

    @Test
    void overCapCoverageRebuildsFromThisWindowAndMarksGaps() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(6, TURN_CHARS);
        List<long[]> legacy = new ArrayList<>();
        for (int index = 0; index <= CoverageLedger.MAX_RANGES; index++) {
            // 旧摘要把每一轮拆成互不相邻的一段，段数越界：这份继承关系已经不可信
            legacy.add(new long[]{10_000L + index * 2L, 10_000L + index * 2L});
        }
        harness.seedCheckpoint(7L, harness.turnId(5), "旧摘要：段数已经爆表", legacy);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(5));
        harness.drafter.willReturn("要点：先背再练，每天二十个", 150);

        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        SessionContextRepository.Checkpoint row = harness.checkpoint().orElseThrow();
        assertEquals(8L, row.revision());
        List<?> coverage = (List<?>) payloadOf(row).get("serverCoverage");
        assertEquals(1, coverage.size(), "超上限就丢掉继承段，只按当前窗口重建（§2.5）");
        Map<?, ?> only = (Map<?, ?>) coverage.get(0);
        assertEquals(harness.firstTurnId(), endpoint(only, "firstTurnId"));
        assertEquals(harness.turnId(5), endpoint(only, "lastTurnId"));
    }

    @Test
    void summaryDraftOverTheModelCapEndsTheJobInsteadOfRetryingForever() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(3, TURN_CHARS);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(2));
        harness.drafter.willReturn("超出模型可见上限的长草稿", MaintenanceRules.SUMMARY_MODEL_TOKEN_LIMIT + 1);

        MemoryMaintenanceWorker.Drain drain = harness.drainWhenDue();

        assertEquals(1, drain.failed(), "超出 600 估算 Token 是非法产出，不是网络故障（§8.4 第 2 条）");
        assertEquals(MemoryJobRepository.STATUS_FAILED, harness.job(jobId).status());
        assertEquals(1, harness.drafter.calls(), "非法产出也只外发一次，不盲重试");
        assertTrue(harness.checkpoint().isEmpty(), "不合格的草稿不写检查点");
        assertEquals(0L, harness.summaryWatermark(), "没落结果就不推进水位");
        assertEquals(List.of("SETTLED"), harness.attemptStates(),
                "端口回了实际用量就按实际量结算，不冒充当过的钱也不把知道的装成不知道（§2.6）");
    }

    @Test
    void malformedSummaryGetsExactlyOneRepairAndThenPublishes() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(4, TURN_CHARS);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(3));
        harness.drafter.willReturnBody("这根本不是JSON");
        harness.drafter.willReturnBody(validDraftBody());

        assertEquals(1, harness.drainWhenDue().committed());

        assertEquals(2, harness.drafter.calls(), "严重结构错误只修一次，不循环（§6.3 第 1 条）");
        assertEquals(List.of("SESSION_SUMMARY", "FORMAT_REPAIR"), harness.attemptPurposes(),
                "修复同样是第二次真实外发，自己占额度、自己记一笔（§2.6）");
        assertEquals("这根本不是JSON", harness.drafter.lastRepairOf(), "修复请求只带上一次的坏原文，不多给一份数据");
        JobRow settled = harness.job(jobId);
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, settled.status());
        assertEquals("APPLIED", settled.outcomeCode());
        assertEquals(1L, harness.checkpoint().orElseThrow().revision(), "修得好就照常发布，不是白跑一次");
    }

    @Test
    void summaryStillBrokenAfterTheRepairEndsFailedWithoutTouchingTheCheckpoint() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(4, TURN_CHARS);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(3));
        harness.drafter.willReturnBody("这根本不是JSON");
        harness.drafter.willReturnBody("修完还不是JSON");

        assertEquals(1, harness.drainWhenDue().failed());

        assertEquals(2, harness.drafter.calls(), "第二次仍不合格就终态，不再有第三次（§6.3 第 1 条）");
        assertEquals(MemoryJobRepository.STATUS_FAILED, harness.job(jobId).status());
        assertTrue(harness.checkpoint().isEmpty(), "不合格的两份草稿都不写检查点");
        assertEquals(0L, harness.summaryWatermark(), "失败的批次不推进水位，来源还在下一批");
        assertEquals(List.of("SESSION_SUMMARY", "FORMAT_REPAIR"), harness.attemptPurposes());
    }

    @Test
    void referenceLedgerOnlyCarriesPagesTheBatchActuallySawInScope() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.useNotebookScope(NOTEBOOK);
        harness.seedTurns(4, TURN_CHARS);
        long inScope = harness.seedPage(NOTEBOOK, "服务端现读的版本");
        long outOfScope = harness.seedPage(OTHER_NOTEBOOK, "别本笔记本的页");
        harness.seedProvidedPage(harness.turnId(1), inScope);
        harness.seedProvidedPage(harness.turnId(2), outOfScope);
        // 模型只能抄：给了在范围内的、越作用域的和凭空的三条，只有第一条能进台账
        harness.drafter.willReturnBody(validDraftBody()
                .replace("\"references\":[]", "\"references\":[" + reference(inScope, "服务端现读的版本",
                        harness.turnId(1)) + "," + reference(outOfScope, "别本笔记本的页", harness.turnId(2)) + ","
                        + reference(404L, "从没提供过", harness.turnId(1)) + "]"));
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(3));

        assertEquals(1, harness.drainWhenDue().committed());

        assertEquals(List.of(new SessionSummaryValidator.Reference(inScope, "服务端现读的版本", harness.turnId(1))),
                harness.drafter.lastBatch().allowedReferences(),
                "允许清单只收\"本批真发过且属于当前作用域\"的页，版本取服务端现读而不是审计里的前端串（§6.1）");
        List<?> references = (List<?>) payloadOf(harness.checkpoint().orElseThrow()).get("references");
        assertEquals(1, references.size(), "抄来的越作用域与凭空的引用都不进台账（§8.4 第 7 条）");
        Map<?, ?> only = (Map<?, ?>) references.get(0);
        assertEquals(inScope, endpoint(only, "pageId"));
        assertEquals("服务端现读的版本", only.get("contentVersion"));
        assertEquals(harness.turnId(1), endpoint(only, "sourceTurnId"));
        assertEquals("APPLIED", harness.job(jobId).outcomeCode());
    }

    // ---- §14 P8 验收：CAS、停止轮次、真实发布长度与用户纠正 ----

    @Test
    void checkpointReplacedAfterTheSendCannotOverwriteTheNewerOne() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(6, TURN_CHARS);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(4));
        harness.drafter.willReturn("这份草稿在路上时被人超过了", 120);
        // 外发期间另一条链先写了 revision=9：本批的 CAS 期望值还是装载时的旧版本
        harness.drafter.invokeDuringDraft(() -> {
            harness.jdbc.update("DELETE FROM ai_session_context");
            try {
                harness.seedCheckpoint(9L, harness.turnId(4), "另一条链先写的新摘要",
                        List.of(new long[]{harness.firstTurnId(), harness.turnId(2)}));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });

        MemoryMaintenanceWorker.Drain done = harness.drainJobWhenDue(jobId);

        assertEquals(1, done.superseded(), "检查点在发送之后被替换，这一批只能作废");
        assertEquals(0, done.committed());
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, harness.job(jobId).status());
        SessionContextRepository.Checkpoint row = harness.checkpoint().orElseThrow();
        assertEquals(9L, row.revision(), "旧结果不许覆新摘要（§8.4 第 7 条）");
        assertEquals("另一条链先写的新摘要", payloadOf(row).get("modelSummary"));
        assertEquals(0L, harness.summaryWatermark(), "作废的一批不推进已检查水位");
    }

    @Test
    void stoppedTurnStaysOutOfTheSummarySourcesAndOutOfCoverage() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(6, TURN_CHARS);
        // 第三条被用户按了停止：半截回答不是"完成的事实"，既不能进摘要输入，也不算已整理
        harness.setTurnStatus(harness.turnId(2), ChatTurnRepository.STATUS_ABORTED);
        harness.drafter.willReturn("要点：先背再练", 120);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(3));

        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        List<Long> sourced = harness.drafter.lastBatch().turns().stream()
                .map(MaintenancePorts.SourceTurn::turnId).toList();
        assertEquals(List.of(harness.turnId(0), harness.turnId(1), harness.turnId(3)), sourced,
                "ABORTED 那一轮的原文没答完，不能当摘要来源（§8.4 第 2 条）");
        List<?> coverage = (List<?>) payloadOf(harness.checkpoint().orElseThrow()).get("serverCoverage");
        assertEquals(2, coverage.size(), "被跳过的那一轮把覆盖切成两段，读侧才看得见缺口（§2.5）");
        assertEquals(harness.turnId(1), endpoint((Map<?, ?>) coverage.get(0), "lastTurnId"));
        assertEquals(harness.turnId(3), endpoint((Map<?, ?>) coverage.get(1), "firstTurnId"));
        assertEquals(1L, harness.state().summarySkippedCount());
    }

    @Test
    void compressionGainIsMeasuredOnTheRenderedViewNotOnAClaimedNumber() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(3, TURN_CHARS);
        // 三条来源约 564 估算 Token。这份草稿九栏形状完全合法，只是把三条约束都写满 80 码点：
        // 渲染后的发布视图压不出 256 的净缩减，就该判"没有收益"——判定吃的是发布视图的实际长度（D60）
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(2));
        harness.drafter.willReturnBody(verboseDraftBody());

        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        assertEquals("NO_COMPRESSION_GAIN", harness.job(jobId).outcomeCode(),
                "写满的发布视图吃掉了缩减量：换成模型自称的小数字也不算数");
        assertTrue(harness.checkpoint().isEmpty(), "没到 256 就不替换检查点（§8.4 第 4 条）");
        assertEquals(3L, harness.summaryWatermark(), "无收益照样推进已检查水位，同输入不再反复压");

        // 真短的草稿：检查点记的估算量必须等于发布正文的现算值
        harness.seedTurns(3, TURN_CHARS);
        String second = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.turnId(3),
                harness.turnId(5));
        harness.drafter.willReturnBody(validDraftBody());
        assertEquals(1, harness.drainJobWhenDue(second).committed());

        SessionContextRepository.Checkpoint row = harness.checkpoint().orElseThrow();
        String published = (String) payloadOf(row).get("modelSummary");
        assertEquals(new com.drillnotebook.app.service.TokenEstimator(harness.mapper).estimateText(published),
                row.estimatedTokens(), "存的是渲染后的真实估算 Token，不是夹具或模型给的数");
    }

    @Test
    void userCorrectionSurvivesWhileAssistantSelfReportedDoneIsDropped() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(3, TURN_CHARS);
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(2));
        harness.drafter.willReturnBody(validDraftBody()
                .replace("\"confirmedUserConstraints\":[]", "\"confirmedUserConstraints\":["
                        + "{\"text\":\"不对，卡片还没做完，先别归档\",\"sourceRole\":\"user\","
                        + "\"sourceTurnIds\":[{turn}]}]")
                .replace("\"workItems\":[]", "\"workItems\":["
                        + "{\"text\":\"边界检查已经完成\",\"sourceRole\":\"assistant\",\"sourceTurnIds\":[{turn}],"
                        + "\"state\":\"user_reported_done\"}]"));

        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        String published = (String) payloadOf(harness.checkpoint().orElseThrow()).get("modelSummary");
        assertTrue(published.contains("不对，卡片还没做完"), "用户的纠正必须留在续接摘要里（§6.3 第 2 条）");
        assertFalse(published.contains("边界检查已经完成"),
                "助手自述完成不是用户说过的，整行删掉；摘要不能把建议升格成已验证事实（§8.4）");
        assertTrue(published.contains("复习二分查找"), "删一条不许连累整份草稿");
        assertEquals("APPLIED", harness.job(jobId).outcomeCode());
    }

    /**
     * §14 P8 验收的交接证据：写侧落盘的 payload 形状，就是下一轮读侧要解析的形状。
     * 两头都用真实现（{@link ConversationProjection} 走真解密、真 coverage 判定），
     * 任何一侧改了键名或版本口径，这条就会红。
     */
    @Test
    void publishedCheckpointIsWhatTheNextPlanReads() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        harness.enableSummary();
        harness.seedTurns(6, TURN_CHARS);
        // §14 P8 退出条件"旧聊天记录没有被摘要改写"：写侧只许动 ai_session_context 自己那一行
        long messages = harness.jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Long.class);
        long historyRevision = harness.state().historyRevision();
        String jobId = harness.enqueueManualJob(Kind.SUMMARY, harness.session(), harness.firstTurnId(),
                harness.turnId(4));
        harness.drafter.willReturnBody(validDraftBody());
        assertEquals(1, harness.drainJobWhenDue(jobId).committed());

        assertEquals(messages, harness.jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Long.class),
                "摘要不删原文、不补原文（§8.4）");
        assertEquals(historyRevision, harness.state().historyRevision(),
                "history_revision 不因摘要改写递增（§2.4）");

        ConversationProjection projection = new ConversationProjection(harness.history, harness.contexts,
                harness.metadata, new ChatMessageCipher(new ApiKeyEncryptor(), harness.mapper), harness.memory,
                harness.crypto, harness.vault, harness.policy, new TokenEstimator(harness.mapper), harness.mapper);
        ContextAudit.Builder audit = new ContextAudit.Builder("plan-handoff", TokenEstimator.VERSION);
        ConversationProjection.Projection read = projection.project(
                new ConversationProjection.Request(harness.session(), harness.turnId(5), 1L,
                        MaintenanceHarness.CHAT_PASSWORD, 2400), audit);

        assertNotNull(read.summary(), "写完读不到就是摘要白做（§2.5）");
        assertEquals(1L, read.summary().audit().revision());
        assertEquals(harness.turnId(4), read.summary().audit().throughTurnId());
        assertTrue(read.summary().modelSummary().contains("复习二分查找"), "发布视图的正文要原样回到读侧");
        assertEquals(2, read.candidates().size(), "覆盖只到最近两轮之前：那两条始终留原文（§9.4）");
        assertEquals(3, read.coveredCandidates().size(), "回补素材正好是被摘要替掉的那三轮");
    }

    // ---- 重试与失败 ----

    @Test
    void rateLimitThenServerErrorThenSuccessCommitsWithoutRepeatingTheBatch() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.onlyJob().id();

        harness.extractor.willFail("UPSTREAM_429", true);
        assertEquals(1, harness.drainWhenDue().retried());

        harness.extractor.willFail("UPSTREAM_500", true);
        assertEquals(1, harness.drainJobWhenDue(jobId).retried());

        harness.extractor.willReturn(1, 0);
        MemoryMaintenanceWorker.Drain done = harness.drainJobWhenDue(jobId);

        assertEquals(1, done.committed(), "第三次成功只提交一次");
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, harness.job(jobId).status());
        assertEquals(3, harness.extractor.calls(), "429、5xx、成功各一次");
        assertEquals(3, harness.spend().attempts(), "每次外发都占一次尝试");
    }

    @Test
    void retryBackoffFollowsThirtyThenOneHundredTwentySeconds() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.onlyJob().id();
        harness.extractor.willFail("UPSTREAM_429", true);

        assertEquals(1, harness.drainWhenDue().retried());
        JobRow first = harness.job(jobId);
        assertEquals(MaintenanceRules.RETRY_BACKOFF_MILLIS_FIRST,
                MaintenanceHarness.millis(first.nextRunAt()) - harness.now(), "第一次退避 30 秒（§8.1）");

        harness.extractor.willFail("UPSTREAM_429", true);
        assertEquals(1, harness.drainJobWhenDue(jobId).retried());
        JobRow second = harness.job(jobId);
        assertEquals(MaintenanceRules.RETRY_BACKOFF_MILLIS_SECOND,
                MaintenanceHarness.millis(second.nextRunAt()) - harness.now(), "第二次退避 120 秒");

        harness.extractor.willFail("UPSTREAM_429", true);
        assertEquals(1, harness.drainJobWhenDue(jobId).failed(), "首试加两次重试用完就终止（§8.1）");
        assertEquals(MemoryJobRepository.STATUS_FAILED, harness.job(jobId).status());
        assertEquals(3, harness.spend().attempts(), "每次重试都占当天预算");
        assertEquals(3, harness.extractor.calls(), "一共只外发三次，不会越界（§8.1）");
    }

    @Test
    void nonRetryableFailureEndsImmediatelyWithoutLooping() throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        String jobId = harness.onlyJob().id();
        harness.extractor.willFail("UPSTREAM_401", false);

        assertEquals(1, harness.drainWhenDue().failed(), "401/403 与结构非法不盲重试（§8.1）");
        assertEquals(MemoryJobRepository.STATUS_FAILED, harness.job(jobId).status());
        assertEquals(1, harness.extractor.calls());
        assertEquals(List.of("UNKNOWN"), harness.attemptStates(), "失败也按已发出消费预留");
        assertEquals(0L, harness.memoryWatermark(), "失败的批不推进水位");

        harness.extractor.resetCalls();
        MemoryMaintenanceWorker.Drain later = harness.drainAt(harness.now() + 600_000L);
        assertEquals(0, later.enqueued(), "同输入的终态不会被定时器重跑（§2.4）");
        assertEquals(0, later.claimed());
        assertEquals(0, harness.extractor.calls());
        assertEquals(1, harness.jobCount());
        assertTrue(harness.wakePending(), "这批源还没真正整理完，待处理标记留着（§2.1）");
    }

    // ---- helpers ----

    /** 解开当前检查点的 payload：coverage 与 references 是服务端字段，模型看不见，只能这样核。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(SessionContextRepository.Checkpoint row) throws Exception {
        String payload = harness.crypto.decrypt(harness.vault.currentKey().orElseThrow(), "ai_session_context",
                String.valueOf(harness.session()), row.revision(), row.payloadCipher(), row.cryptoMeta());
        return harness.mapper.readValue(payload, Map.class);
    }

    private static long endpoint(Map<?, ?> range, String field) {
        return ((Number) range.get(field)).longValue();
    }

    /** 一份能过 §6.1 全部字段门的草稿：{@code {turn}} 由假摘要器在调用时换成本批第一条轮次 ID。 */
    private static String validDraftBody() {
        return "{\"schemaVersion\":2,\"topic\":\"先背再练\",\"taskGoal\":"
                + "{\"text\":\"复习二分查找\",\"sourceRole\":\"user\",\"sourceTurnIds\":[{turn}]}"
                + ",\"confirmedUserConstraints\":[],\"confirmedDecisions\":[],\"discussionNotes\":[],"
                + "\"workItems\":[],\"openQuestions\":[],\"nextSteps\":[],\"references\":[]}";
    }

    private static String reference(long pageId, String contentVersion, long sourceTurnId) {
        return "{\"pageId\":" + pageId + ",\"contentVersion\":\"" + contentVersion + "\",\"sourceTurnId\":"
                + sourceTurnId + "}";
    }

    /** 四条各写满 80 码点、互不重复的约束：形状合法，但渲染出来的发布视图几乎和原文一样长。 */
    private static String verboseDraftBody() {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            items.append(items.isEmpty() ? "" : ",");
            items.append("{\"text\":\"").append(codePoints(78)).append((char) ('甲' + index)).append("条")
                    .append("\",\"sourceRole\":\"user\",\"sourceTurnIds\":[{turn}]}");
        }
        return validDraftBody().replace("\"confirmedUserConstraints\":[]",
                "\"confirmedUserConstraints\":[" + items + "]");
    }

    private static String codePoints(int count) {
        String unit = "复习二分查找的边界条件";
        return unit.repeat(count / unit.length() + 1).substring(0, count);
    }

    /** 造"进程死在预留/发送/结算之后"的状态：任务仍是 CLAIMED 且租约已经过期。 */
    private String crashWithAttempt(String attemptState) throws Exception {
        harness.readyForMaintenance();
        harness.seedTurns(4, TURN_CHARS);
        harness.wake();
        harness.drain();
        JobRow queued = harness.onlyJob();
        String jobId = queued.id();
        harness.forceJobState(jobId, MemoryJobRepository.STATUS_CLAIMED, "dead-lease",
                harness.stamp(harness.now() - 1L), 0);
        String attemptId = UuidV7.generate(harness.now());
        harness.usage.reserve(new UsageRepository.Attempt(attemptId, jobId,
                UsageRepository.PURPOSE_MEMORY_EXTRACT, queued.targetFingerprint(), harness.budgetDate(),
                harness.budgetZone().getId(), MaintenanceRules.RESERVE_TOKENS, harness.stamp(harness.now()), null));
        if (!"RESERVED".equals(attemptState)) {
            harness.usage.markSent(attemptId);
        }
        if ("SETTLED".equals(attemptState)) {
            harness.usage.settle(attemptId, new UsageRepository.ActualUsage("provider", 1_200, 200, null, null),
                    harness.stamp(harness.now()));
        }
        harness.extractor.resetCalls();
        return jobId;
    }

    private void cancelQuietly(String jobId) {
        try {
            harness.jobs.cancel(List.of(jobId), harness.stamp(harness.now()));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
