package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MemoryProposalRepository.ProposalDraft;
import com.drillnotebook.app.repository.MemoryProposalRepository.ProposalRow;
import com.drillnotebook.app.repository.MemoryProposalRepository.ReviewOutcome;
import com.drillnotebook.app.repository.MemoryProposalRepository.SubmitOutcome;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 替换/撤销建议（contracts §2.3.1）：建议不冒充事实、去重键幂等、审阅走版本 CAS、
 * 过期即时从读路径排除、目标或来源变化时变 STALE，并与事实的 PENDING 共用 500 上限。
 */
class MemoryProposalRepositoryTest {
    private static final String VAULT = "vault-1";
    private static final String NOW = "2026-10-11T00:00:00.000Z";
    private static final String LATER = "2026-10-11T00:03:00.000Z";
    private static final String EXPIRES = "2026-10-18T00:00:00.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryRepository facts;
    private MemoryProposalRepository proposals;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1c-proposal-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        facts = new MemoryRepository(dataSource, immediate);
        proposals = new MemoryProposalRepository(dataSource, immediate);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                + " VALUES (?, 'dek', '{}', 'fingerprint', ?)", VAULT, NOW);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void submitStoresAPendingProposalThatIsNotAFact() throws Exception {
        String target = newFact();

        var submitted = proposals.submit(draft("dedup-1", target));

        assertEquals(SubmitOutcome.SUBMITTED, submitted.outcome());
        ProposalRow row = proposals.find(submitted.id()).orElseThrow();
        assertEquals(MemoryProposalRepository.STATUS_PENDING, row.status());
        assertEquals(1, row.version());
        assertEquals("REPLACE_PROPOSAL", row.operation());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'PENDING'"),
                "替换/撤销建议只进 proposal，不给撤销制造假的 value/item");
    }

    @Test
    void sameDedupKeyReplaysTheExistingProposal() throws Exception {
        String target = newFact();
        String first = proposals.submit(draft("dedup-1", target)).id();

        var replay = proposals.submit(draft("dedup-1", target));

        assertEquals(SubmitOutcome.DUPLICATE, replay.outcome());
        assertEquals(first, replay.id(), "重放回同一条建议的 id，供前端对齐");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_proposal"), "同一建议不产生第二行");
    }

    @Test
    void dedupKeyIsAHardUniqueConstraint() throws Exception {
        String target = newFact();
        proposals.submit(draft("dedup-1", target));

        UncategorizedSQLException error = assertThrows(UncategorizedSQLException.class, () -> jdbc.update(
                "INSERT INTO ai_memory_proposal(id, vault_id, session_id, target_memory_id, target_version,"
                        + " operation, status, payload_cipher, crypto_meta, dedup_hmac, created_at, expires_at)"
                        + " VALUES ('raw-prop', ?, ?, ?, 1, 'REPLACE_PROPOSAL', 'PENDING', 'p', '{}', 'dedup-1', ?, ?)",
                VAULT, sessionId, target, NOW, EXPIRES));
        assertTrue(error.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE"), error.getMessage());
    }

    @Test
    void pendingCapOfFiveHundredIsSharedWithFacts() throws Exception {
        List<Object[]> bulk = new ArrayList<>();
        for (int index = 0; index < MemoryRepository.PENDING_QUOTA; index++) {
            bulk.add(new Object[]{"bulk-row-" + index, "bulk-" + index, VAULT, "f-bulk-" + index, "v-" + index,
                    NOW, NOW, NOW});
        }
        jdbc.batchUpdate("INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id,"
                + " kind, predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta,"
                + " origin, observed_at, recorded_at, last_confirmed_at)"
                + " VALUES (?, ?, ?, 1, 'GLOBAL', 0, 'GOAL', 'p', ?, ?, 'PENDING', 'x', '{}', 'AUTO', ?, ?, ?)", bulk);
        String target = newFact();

        assertEquals(SubmitOutcome.PENDING_QUOTA_FULL, proposals.submit(draft("dedup-cap", target)).outcome());
        assertEquals(0, proposals.pendingCount(VAULT), "超额的建议一条都不进");
    }

    @Test
    void reviewAppliesOnceAndLaterClicksOnlyReplay() throws Exception {
        String target = newFact();
        String id = proposals.submit(draft("dedup-1", target)).id();

        assertEquals(ReviewOutcome.APPLIED, proposals.review(id, 1, MemoryProposalRepository.STATUS_ACCEPTED));
        ProposalRow reviewed = proposals.find(id).orElseThrow();
        assertEquals(MemoryProposalRepository.STATUS_ACCEPTED, reviewed.status());
        assertEquals(2, reviewed.version());

        assertEquals(ReviewOutcome.ALREADY_DECIDED,
                proposals.review(id, 2, MemoryProposalRepository.STATUS_REJECTED), "重复点击不得改写成 REJECTED");
        assertEquals(MemoryProposalRepository.STATUS_ACCEPTED, proposals.find(id).orElseThrow().status());
        assertEquals(0, proposals.findPending(VAULT, NOW).size(), "终态建议不再出现在待确认列表");
    }

    @Test
    void reviewWithStaleVersionChangesNothing() throws Exception {
        String target = newFact();
        String id = proposals.submit(draft("dedup-1", target)).id();

        assertEquals(ReviewOutcome.VERSION_CONFLICT,
                proposals.review(id, 42, MemoryProposalRepository.STATUS_ACCEPTED));
        assertEquals(MemoryProposalRepository.STATUS_PENDING, proposals.find(id).orElseThrow().status());
        assertEquals(ReviewOutcome.NOT_FOUND, proposals.review("no-such-proposal", 1,
                MemoryProposalRepository.STATUS_ACCEPTED));
        assertThrows(SQLException.class, () -> proposals.review(id, 1, "KEEP_BOTH"));
    }

    @Test
    void expiredProposalsAreInvisibleImmediately() throws Exception {
        String target = newFact();
        proposals.submit(new ProposalDraft("prop-live", VAULT, sessionId, null, null, target, 1, "REPLACE_PROPOSAL",
                "p", "{}", "dedup-live", NOW, "2026-10-20T00:00:00.000Z"));
        proposals.submit(new ProposalDraft("prop-expired", VAULT, sessionId, null, null, target, 1, "RETRACT_PROPOSAL",
                "q", "{}", "dedup-expired", NOW, "2026-10-05T00:00:00.000Z"));

        List<ProposalRow> visible = proposals.findPending(VAULT, "2026-10-11T00:00:00.000Z");

        assertEquals(1, visible.size(), "过期的建议读路径即时排除，不靠后台清理");
        assertEquals("REPLACE_PROPOSAL", visible.get(0).operation());
    }

    @Test
    void targetOrSessionChangeMarksPendingProposalsStale() throws Exception {
        String target = newFact();
        String other = newFact();
        proposals.submit(draft("dedup-1", target));
        proposals.submit(new ProposalDraft("prop-session", VAULT, sessionId, null, null, other, 1, "RETRACT_PROPOSAL",
                "q", "{}", "dedup-2", NOW, EXPIRES));

        assertEquals(1, proposals.markStaleForTarget(VAULT, target), "目标更新使指向它的建议 STALE");
        assertEquals(1, proposals.markStaleForSession(sessionId), "作用域/会话变化使该会话剩下的建议 STALE");
        assertEquals(0, proposals.findPending(VAULT, NOW).size());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_proposal WHERE status = 'STALE'"));
    }

    @Test
    void terminalProposalsAgeOutWithoutTouchingPendingOnes() throws Exception {
        String target = newFact();
        String id = proposals.submit(draft("dedup-1", target)).id();
        proposals.review(id, 1, MemoryProposalRepository.STATUS_REJECTED);
        proposals.submit(draft("dedup-2", target));

        assertEquals(1, proposals.purgeTerminalBefore(LATER), "只清超期的终态建议");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_proposal WHERE status = 'PENDING'"));
        assertEquals(0, proposals.purgeTerminalBefore(LATER), "没有可清的就不动");
    }

    @Test
    void illegalOperationIsRefusedBeforeWriting() {
        assertThrows(SQLException.class, () -> proposals.submit(new ProposalDraft("prop-bad", VAULT, sessionId,
                null, null, "mem", 1, "DELETE_ALL", "p", "{}", "dedup-bad", NOW, EXPIRES)));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal"));
    }

    // ---- helpers ----

    private String newFact() throws SQLException {
        String family = "f-" + System.nanoTime();
        var added = facts.add(new MemoryRepository.NewMemory("row-" + family, VAULT, "GLOBAL", 0, "PREFERENCE",
                "p", family, "v", MemoryRepository.STATUS_ACTIVE, "payload", "{}", "MANUAL", true, false,
                null, null, NOW, NOW, NOW));
        return added.memoryId();
    }

    private ProposalDraft draft(String dedupHmac, String targetMemoryId) {
        return new ProposalDraft("prop-" + dedupHmac, VAULT, sessionId, null, null, targetMemoryId, 1,
                "REPLACE_PROPOSAL", "payload", "{}", dedupHmac, NOW, EXPIRES);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
