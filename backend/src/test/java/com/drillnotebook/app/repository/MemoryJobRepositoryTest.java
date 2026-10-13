package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MemoryJobRepository.EnqueueOutcome;
import com.drillnotebook.app.repository.MemoryJobRepository.EnqueueResult;
import com.drillnotebook.app.repository.MemoryJobRepository.JobDraft;
import com.drillnotebook.app.repository.MemoryJobRepository.JobRow;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.MemoryJobRepository.LeaseOutcome;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持久任务与租约（contracts §2.4、§7、执行方案 §8.1）：入队幂等与范围合并、每会话每类型只有一个待处理范围、
 * 队列容量按活跃会话数生效、认领互斥、结算要同时校验令牌与未过期租约、取消后旧租约永不可提交、
 * 等待态到点重探且不吃尝试次数、过期租约被接管时照旧消费一次预算，epoch 与会话变化只作废仍在跑的任务。
 */
class MemoryJobRepositoryTest {
    private static final String NOW = "2026-10-11T00:00:00.000Z";
    private static final String LATER = "2026-10-11T00:05:00.000Z";
    private static final String FUTURE = "2026-10-11T01:00:00.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryJobRepository jobs;
    private long sessionId;
    private long otherSessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1c-job-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        jobs = new MemoryJobRepository(dataSource, new ImmediateTransaction(dataSource));
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO ai_chat_session(title) VALUES ('第二个会话')");
        otherSessionId = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void enqueueIsIdempotentForTheSameInputRevision() throws Exception {
        EnqueueResult first = jobs.enqueue(draft("input-1"));
        EnqueueResult replay = jobs.enqueue(draft("input-1"));

        assertFalse(first.replayed());
        assertTrue(replay.replayed(), "相同输入的重复入队回放既有行");
        assertEquals(first.jobId(), replay.jobId());
        assertEquals(1, count());
        assertEquals(Kind.EXTRACT, first.kind());
        assertEquals(MemoryJobRepository.STATUS_QUEUED, first.status());
        assertEquals(0, jobs.find(first.jobId()).orElseThrow().attempts());
    }

    @Test
    void pendingQueuedRangeIsMergedInsteadOfDuplicated() throws Exception {
        EnqueueResult first = jobs.enqueue(draft("input-1"));
        EnqueueResult wider = jobs.enqueue(draft("input-2", 1L, 6L));

        assertEquals(1, count(), "每会话每类型至多 1 个待处理范围（§8.1）");
        assertEquals(first.jobId(), wider.jobId(), "合并是就地扩写，不制造第二个任务");
        assertEquals(EnqueueOutcome.MERGED, wider.outcome());
        JobRow merged = jobs.find(first.jobId()).orElseThrow();
        assertEquals(6L, merged.throughTurnId(), "新轮次把待处理边界推宽（§2.4）");
        assertEquals("input-2", merged.inputRevision(), "合并后身份按合并后的范围重算");
        assertEquals(MemoryJobRepository.STATUS_QUEUED, merged.status());
        assertEquals(1, jobs.findClaimable(NOW, 10).size());
    }

    @Test
    void claimedRangeIsImmutableSoNewTurnsWaitForTheNextBatch() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        EnqueueResult later = jobs.enqueue(draft("input-2", 1L, 9L));

        assertEquals(EnqueueOutcome.DEFERRED, later.outcome(), "当前 claim 结束再生成下一 job（§2.4）");
        assertEquals(1, count());
        JobRow stillRunning = jobs.find(id).orElseThrow();
        assertEquals(4L, stillRunning.throughTurnId(), "CLAIMED 之后批次内容不可变");
        assertEquals("input-1", stillRunning.inputRevision());
    }

    @Test
    void retryRowAlsoOccupiesThePendingRange() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);
        jobs.markRetry(id, "lease-a", FUTURE, "UPSTREAM_TIMEOUT", NOW);

        assertEquals(EnqueueOutcome.DEFERRED, jobs.enqueue(draft("input-2", 1L, 9L)).outcome(),
                "重试仍是同一批输入，不能并排放第二批");
        assertEquals(1, count());
    }

    @Test
    void onlyOneWorkerCanClaimAJob() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();

        assertEquals(LeaseOutcome.APPLIED, jobs.claim(id, "lease-a", LATER, NOW));
        assertEquals(LeaseOutcome.REJECTED, jobs.claim(id, "lease-b", LATER, NOW));
        JobRow claimed = jobs.find(id).orElseThrow();
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, claimed.status());
        assertEquals("lease-a", claimed.leaseToken());
        assertEquals(LATER, claimed.leaseUntil());
    }

    @Test
    void retryJobStaysUnclaimableUntilNextRunAt() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);
        assertEquals(LeaseOutcome.APPLIED, jobs.markRetry(id, "lease-a", FUTURE, "UPSTREAM_TIMEOUT", NOW));

        JobRow retrying = jobs.find(id).orElseThrow();
        assertEquals(MemoryJobRepository.STATUS_RETRY, retrying.status());
        assertEquals(1, retrying.attempts(), "每次重试都在原任务上限内计数");
        assertNull(retrying.leaseToken(), "回队即作废本次租约");
        assertEquals("UPSTREAM_TIMEOUT", retrying.errorCode());
        assertEquals(LeaseOutcome.REJECTED, jobs.claim(id, "lease-b", FUTURE, NOW), "未到点不能被认领");
        assertEquals(List.of(), jobs.findClaimable(NOW, 10));
        assertEquals(1, jobs.findClaimable(FUTURE, 10).size(), "到点后进入可认领视图");

        assertEquals(LeaseOutcome.APPLIED, jobs.claim(id, "lease-b", FUTURE, FUTURE));
        assertEquals(List.of(), jobs.findClaimable(FUTURE, 10), "CLAIMED 的任务不再出现在可认领视图");
    }

    @Test
    void settleRequiresMatchingTokenAndLiveLease() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        assertEquals(LeaseOutcome.REJECTED, jobs.settle(id, "lease-b", NOW,
                MemoryJobRepository.STATUS_COMPLETED, "APPLIED"), "别的令牌不能提交别人的批次");
        assertEquals(LeaseOutcome.REJECTED, jobs.settle(id, "lease-a", LATER,
                MemoryJobRepository.STATUS_COMPLETED, "APPLIED"), "租约到点的瞬间同样不可提交");
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, jobs.find(id).orElseThrow().status());

        assertEquals(LeaseOutcome.APPLIED, jobs.settle(id, "lease-a", NOW,
                MemoryJobRepository.STATUS_COMPLETED, "APPLIED"));
        JobRow settled = jobs.find(id).orElseThrow();
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, settled.status());
        assertEquals("APPLIED", settled.outcomeCode());
        assertNull(settled.leaseToken());
        assertNull(settled.leaseUntil());
        assertThrows(SQLException.class, () -> jobs.settle(id, "lease-a", NOW, "RUNNING", "APPLIED"));
    }

    @Test
    void finishedBatchIsNeverRerunByTheSameInput() throws Exception {
        JobDraft draft = draft("input-1");
        String id = jobs.enqueue(draft).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);
        jobs.settle(id, "lease-a", NOW, MemoryJobRepository.STATUS_COMPLETED, "NO_NEW_INFORMATION");

        EnqueueResult again = jobs.enqueue(draft);

        assertTrue(again.replayed());
        assertEquals(MemoryJobRepository.STATUS_COMPLETED, again.status(),
                "相同输入的终态不被定时器或 flush 重跑");
        assertEquals(LeaseOutcome.REJECTED, jobs.claim(id, "lease-c", LATER, NOW));
        assertEquals(0, jobs.findClaimable(NOW, 10).size());
    }

    @Test
    void cancelMakesTheOldLeaseUnsubmittableForever() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        assertEquals(1, jobs.cancel(List.of(id), NOW));
        JobRow cancelled = jobs.find(id).orElseThrow();
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, cancelled.status());
        assertNull(cancelled.leaseToken());

        assertEquals(LeaseOutcome.REJECTED, jobs.settle(id, "lease-a", NOW,
                MemoryJobRepository.STATUS_COMPLETED, "APPLIED"), "取消后即使重新开启也不能恢复旧租约");
        assertEquals(0, jobs.cancel(List.of(id), NOW), "已终态的任务不被第二次取消");
        assertEquals(0, jobs.cancel(List.of(), NOW));
    }

    @Test
    void epochAdvanceSupersedesOnlyOlderJobs() throws Exception {
        String older = jobs.enqueue(draft("input-1", 1L, 4L)).jobId();
        String current = jobs.enqueue(extractOn(otherSessionId, "input-2", 2L)).jobId();

        assertEquals(1, jobs.supersedeBeforeEpoch(2));
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, jobs.find(older).orElseThrow().status());
        assertEquals(MemoryJobRepository.STATUS_QUEUED, jobs.find(current).orElseThrow().status());
    }

    @Test
    void sessionSupersedeLeavesOtherSessionsAlone() throws Exception {
        String mine = jobs.enqueue(draftFor(sessionId, "input-1")).jobId();
        String theirs = jobs.enqueue(draftFor(otherSessionId, "input-2")).jobId();
        jobs.claim(mine, "lease-a", LATER, NOW);

        assertEquals(1, jobs.supersedeForSession(sessionId));
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, jobs.find(mine).orElseThrow().status());
        assertNull(jobs.find(mine).orElseThrow().leaseToken());
        assertEquals(MemoryJobRepository.STATUS_QUEUED, jobs.find(theirs).orElseThrow().status());
    }

    @Test
    void claimableViewIsBoundedAndSkipsFutureWork() throws Exception {
        String waiting = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(waiting, "lease-a", LATER, NOW);
        jobs.markRetry(waiting, "lease-a", FUTURE, "UPSTREAM_TIMEOUT", NOW);
        jobs.enqueue(extractOn(otherSessionId, "input-2", 1L));
        jobs.enqueue(draftFor(otherSessionId, "input-3"));

        List<JobRow> now = jobs.findClaimable(NOW, 2);

        assertEquals(2, now.size(), "轮询批次有界，不一次抓走全部积压");
        assertTrue(now.stream().noneMatch(row -> row.id().equals(waiting)), "未到点的 RETRY 不进这一批");
        assertEquals(3, jobs.countByStatus(MemoryJobRepository.STATUS_QUEUED)
                + jobs.countByStatus(MemoryJobRepository.STATUS_RETRY));
    }

    @Test
    void waitingStatesAreReProbedOnlyWhenTheirClockArrives() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        assertEquals(LeaseOutcome.APPLIED, jobs.markWaiting(id, "lease-a",
                MemoryJobRepository.STATUS_PAUSED_BUDGET, FUTURE, "DAILY_BUDGET", NOW));
        JobRow paused = jobs.find(id).orElseThrow();
        assertEquals(MemoryJobRepository.STATUS_PAUSED_BUDGET, paused.status());
        assertEquals(0, paused.attempts(), "等待态不算一次远程尝试（§8.2 第 2 条）");
        assertNull(paused.leaseToken(), "探视之前租约必须作废，不能占着批次");
        assertEquals(List.of(), jobs.findClaimable(NOW, 10), "没到探视时刻就不重跑");
        assertEquals(1, jobs.findClaimable(FUTURE, 10).size(), "到点后重新探门禁是可以认领的");
        assertEquals(LeaseOutcome.APPLIED, jobs.claim(id, "lease-b", LATER, FUTURE));
    }

    @Test
    void markWaitingRejectsForeignOrExpiredLease() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        assertEquals(LeaseOutcome.REJECTED, jobs.markWaiting(id, "lease-b",
                MemoryJobRepository.STATUS_WAITING_UNLOCK, FUTURE, "VAULT_LOCKED", NOW));
        assertEquals(LeaseOutcome.REJECTED, jobs.markWaiting(id, "lease-a",
                MemoryJobRepository.STATUS_WAITING_UNLOCK, FUTURE, "VAULT_LOCKED", LATER));
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, jobs.find(id).orElseThrow().status());
        assertThrows(SQLException.class, () -> jobs.markWaiting(id, "lease-a",
                MemoryJobRepository.STATUS_COMPLETED, FUTURE, "NO_NEW_INFORMATION", NOW));
    }

    @Test
    void waitingStatesAreCancelledAndSupersededLikeQueuedOnes() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);
        jobs.markWaiting(id, "lease-a", MemoryJobRepository.STATUS_WAITING_CONSENT, FUTURE, "CONSENT_MISSING", NOW);

        assertEquals(1, jobs.cancel(List.of(id), NOW), "等待中的任务同样要能被取消");
        assertEquals(MemoryJobRepository.STATUS_SUPERSEDED, jobs.find(id).orElseThrow().status());
        assertEquals(LeaseOutcome.REJECTED, jobs.claim(id, "lease-b", FUTURE, FUTURE));
    }

    @Test
    void expiredLeaseIsReclaimedOnceAndItsRetryBudgetConsumed() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        assertEquals(List.of(), jobs.findExpiredClaims(NOW, 10), "没到点之前还算进行中");
        assertEquals(1, jobs.findExpiredClaims(LATER, 10).size(),
                "到点即过期：与 settleIn 的 lease_until > asOf 是同一条边界，两边不能都放行");

        List<JobRow> stale = jobs.findExpiredClaims(LATER, 10);
        assertEquals(1, stale.size());
        assertEquals(LeaseOutcome.REJECTED, jobs.markRetry(id, "lease-a", FUTURE, "UPSTREAM_TIMEOUT", LATER),
                "过期租约不能走 markRetry：那条 CAS 要求租约仍有效");
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, jobs.find(id).orElseThrow().status(),
                "被拒绝的结算不留下半个状态");
        assertEquals(LeaseOutcome.APPLIED, jobs.expireClaim(id, "lease-a", MemoryJobRepository.STATUS_RETRY,
                FUTURE, "LEASE_EXPIRED", LATER));
        JobRow retried = jobs.find(id).orElseThrow();
        assertEquals(1, retried.attempts(), "过期租约也吃掉一次尝试");
        assertEquals(MemoryJobRepository.STATUS_RETRY, retried.status());
        assertEquals(LeaseOutcome.REJECTED, jobs.expireClaim(id, "lease-a", MemoryJobRepository.STATUS_RETRY,
                FUTURE, "LEASE_EXPIRED", NOW), "已经不是 CLAIMED 的行不再被接管");
    }

    @Test
    void settleInParticipatesInTheCallersTransaction() throws Exception {
        String id = jobs.enqueue(draft("input-1")).jobId();
        jobs.claim(id, "lease-a", LATER, NOW);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(1, MemoryJobRepository.settleIn(connection, id, "lease-a", NOW,
                    MemoryJobRepository.STATUS_COMPLETED, "APPLIED"));
            connection.rollback();
        }
        assertEquals(MemoryJobRepository.STATUS_CLAIMED, jobs.find(id).orElseThrow().status(),
                "同一笔事务回滚时任务终态也不留下");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals(0, MemoryJobRepository.settleIn(connection, id, "lease-b", NOW,
                    MemoryJobRepository.STATUS_COMPLETED, "APPLIED"), "令牌不符就写不进去");
            connection.commit();
        }
    }

    @Test
    void queueCapacityCountsActiveSessionsNotRows() throws Exception {
        List<Long> seeded = new ArrayList<>();
        for (int index = 0; index < MemoryJobRepository.QUEUE_CAPACITY; index++) {
            jdbc.update("INSERT INTO ai_chat_session(id, title) VALUES (?, ?)", 1000L + index, "会话" + index);
            seeded.add(1000L + index);
            jobs.enqueue(new JobDraft(Kind.EXTRACT, 1000L + index, 1L, 2L, 1L, "GLOBAL", 0, "Asia/Shanghai", 1L, 1L,
                    "target-fingerprint", "extract-v1", "input-" + index, NOW));
        }
        assertEquals(MemoryJobRepository.QUEUE_CAPACITY, jobs.activeSessionCount());

        EnqueueResult refused = jobs.enqueue(extractOn(sessionId, "over-capacity", 1L));

        assertEquals(EnqueueOutcome.CAPACITY_FULL, refused.outcome(), "排不下只回容量满，正文不进队列");
        assertNull(refused.jobId());
        // 已在队列里的会话仍然可以合并扩写：容量限制的是"活跃会话数"，不是新增工作量
        assertEquals(EnqueueOutcome.MERGED, jobs.enqueue(new JobDraft(Kind.EXTRACT, seeded.get(0), 1L, 9L, 1L,
                "GLOBAL", 0, "Asia/Shanghai", 1L, 1L, "target-fingerprint", "extract-v1", "wider", NOW)).outcome());
    }

    @Test
    void unknownJobIsNotFoundRatherThanAssumedClaimable() throws Exception {
        assertEquals(Optional.empty(), jobs.find("no-such-job"));
        assertEquals(LeaseOutcome.REJECTED, jobs.claim("no-such-job", "lease-a", LATER, NOW));
        assertEquals(LeaseOutcome.REJECTED, jobs.settle("no-such-job", "lease-a", NOW,
                MemoryJobRepository.STATUS_COMPLETED, "APPLIED"));
    }

    // ---- helpers ----

    private JobDraft draft(String inputRevision) {
        return new JobDraft(Kind.EXTRACT, sessionId, 1L, 4L, 1L, "GLOBAL", 0, "Asia/Shanghai", 1L, 1L,
                "target-fingerprint", "extract-v1", inputRevision, NOW);
    }

    private JobDraft draft(String inputRevision, long fromTurnId, long throughTurnId) {
        return new JobDraft(Kind.EXTRACT, sessionId, fromTurnId, throughTurnId, 1L, "GLOBAL", 0, "Asia/Shanghai",
                1L, 1L, "target-fingerprint", "extract-v1", inputRevision, NOW);
    }

    private JobDraft extractOn(long session, String inputRevision, long vaultEpoch) {
        return new JobDraft(Kind.EXTRACT, session, 1L, 4L, 1L, "GLOBAL", 0, "Asia/Shanghai", vaultEpoch, 1L,
                "target-fingerprint", "extract-v1", inputRevision, NOW);
    }

    private JobDraft draftFor(long session, String inputRevision) {
        return new JobDraft(Kind.SUMMARY, session, 1L, 4L, 1L, "GLOBAL", 0, "Asia/Shanghai", 1L, 1L,
                "target-fingerprint", "summary-v1", inputRevision, NOW);
    }

    private int count() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_job", Integer.class);
        return value == null ? 0 : value;
    }
}
