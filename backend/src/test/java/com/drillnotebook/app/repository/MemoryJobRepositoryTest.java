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
import com.drillnotebook.app.repository.MemoryJobRepository.EnqueueResult;
import com.drillnotebook.app.repository.MemoryJobRepository.JobDraft;
import com.drillnotebook.app.repository.MemoryJobRepository.JobRow;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.MemoryJobRepository.LeaseOutcome;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持久任务与租约（contracts §2.4、§7）：入队幂等、认领互斥、结算要同时校验令牌与未过期租约、
 * 取消后旧租约永不可提交、epoch 与会话变化只作废仍在跑的任务。
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
    void changedInputRevisionIsANewBatch() throws Exception {
        jobs.enqueue(draft("input-1"));
        jobs.enqueue(draft("input-2"));

        assertEquals(2, count(), "批内源变更必须重新校验并生成新任务（§2.4）");
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
        String older = jobs.enqueue(draft("input-1", 1)).jobId();
        String current = jobs.enqueue(draft("input-2", 2)).jobId();

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
        jobs.enqueue(draft("input-1"));
        String waiting = jobs.enqueue(draft("input-2")).jobId();
        jobs.claim(waiting, "lease-a", LATER, NOW);
        jobs.markRetry(waiting, "lease-a", FUTURE, "UPSTREAM_TIMEOUT", NOW);
        jobs.enqueue(draft("input-3"));

        List<JobRow> now = jobs.findClaimable(NOW, 2);

        assertEquals(2, now.size(), "轮询批次有界，不一次抓走全部积压");
        assertTrue(now.stream().noneMatch(row -> row.id().equals(waiting)), "未到点的 RETRY 不进这一批");
        assertEquals(3, jobs.countByStatus(MemoryJobRepository.STATUS_QUEUED)
                + jobs.countByStatus(MemoryJobRepository.STATUS_RETRY));
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
        return draft(inputRevision, 1L);
    }

    private JobDraft draft(String inputRevision, long vaultEpoch) {
        return new JobDraft(Kind.EXTRACT, sessionId, 1L, 4L, 1L, "GLOBAL", 0, "Asia/Shanghai", vaultEpoch, 1L,
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
