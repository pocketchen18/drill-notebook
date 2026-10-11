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
import com.drillnotebook.app.repository.MemoryRepository.AddOutcome;
import com.drillnotebook.app.repository.MemoryRepository.CasOutcome;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceDraft;
import com.drillnotebook.app.repository.MemoryRepository.FactRow;
import com.drillnotebook.app.repository.MemoryRepository.NewMemory;
import com.drillnotebook.app.repository.MemoryRepository.PurgeResult;
import com.drillnotebook.app.repository.MemoryRepository.ReplaceOutcome;
import com.drillnotebook.app.repository.MemoryRepository.VaultState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
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
 * 事实版本链与证据（contracts §2.3、§2.3.1 尾段、§7 的 deleteMemory/applyExtraction 片段）：
 * 替换同事务、版本 CAS、family 唯一约束、证据去重，以及删来源后的孤儿自动事实重算。
 */
class MemoryRepositoryTest {
    private static final String VAULT = "vault-1";
    private static final String NOW = "2026-10-11T00:00:00.000Z";
    private static final String LATER = "2026-10-11T00:02:00.000Z";
    private static final String EXPIRES = "2026-10-18T00:00:00.000Z";
    private static final String FACT = "row_id, memory_id, vault_id, version, scope_type, scope_id, kind,"
            + " predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,"
            + " manual_protected, pinned, valid_from, valid_until, observed_at, recorded_at, last_confirmed_at";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryRepository memory;
    private long sessionId;
    private long nextTurnId = 500;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1c-memory-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        memory = new MemoryRepository(dataSource, new ImmediateTransaction(dataSource));
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                + " VALUES (?, 'dek', '{}', 'fingerprint', ?)", VAULT, NOW);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void addStoresFirstActiveVersionAndBumpsVaultRevision() throws Exception {
        var added = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(AddOutcome.ADDED, added.outcome());
        assertNotNull(added.memoryId());
        assertEquals(1, added.version());
        assertEquals(2, added.vaultRevision());
        FactRow row = memory.findCurrent(VAULT, added.memoryId()).orElseThrow();
        assertEquals(MemoryRepository.STATUS_ACTIVE, row.status());
        assertEquals("MANUAL", row.origin());
        assertEquals(added.rowId(), row.rowId());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"));
    }

    @Test
    void addRefusesSecondActiveFactInSameFamilyWithoutWritingAnything() throws Exception {
        memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));
        long revisionBefore = vault().revision();

        var conflict = memory.add(memory("f-language", "v-2", MemoryRepository.STATUS_ACTIVE, "AUTO"));

        assertEquals(AddOutcome.FAMILY_CONFLICT, conflict.outcome());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "冲突不留下第二行 ACTIVE");
        assertEquals(revisionBefore, vault().revision(), "被拒的新增不得推进内容版本");
    }

    @Test
    void pendingCandidateMayCoexistWithActiveFactOfSameFamily() throws Exception {
        memory.add(memory("f-goal", "v-1", MemoryRepository.STATUS_ACTIVE, "AUTO"));

        var pending = memory.add(memory("f-goal", "v-2", MemoryRepository.STATUS_PENDING, "AUTO"));

        assertEquals(AddOutcome.ADDED, pending.outcome(), "同 family 的 PENDING 与 ACTIVE 可以并存（§2.3）");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"),
                "部分唯一索引只约束 ACTIVE");
    }

    @Test
    void pendingQuotaIsASharedHardCap() throws Exception {
        List<Object[]> bulk = new ArrayList<>();
        for (int index = 0; index < MemoryRepository.PENDING_QUOTA; index++) {
            bulk.add(new Object[]{"bulk-row-" + index, "bulk-" + index, VAULT, "f-bulk-" + index,
                    "v-" + index, NOW, NOW, NOW});
        }
        jdbc.batchUpdate("INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id,"
                + " kind, predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta,"
                + " origin, observed_at, recorded_at, last_confirmed_at)"
                + " VALUES (?, ?, ?, 1, 'GLOBAL', 0, 'GOAL', 'p', ?, ?, 'PENDING', 'x', '{}', 'AUTO', ?, ?, ?)", bulk);
        assertEquals(MemoryRepository.PENDING_QUOTA, memory.pendingCount(VAULT));

        var refused = memory.add(memory("f-goal", "over-limit", MemoryRepository.STATUS_PENDING, "AUTO"));

        assertEquals(AddOutcome.PENDING_QUOTA_FULL, refused.outcome());
        assertEquals(MemoryRepository.PENDING_QUOTA, count("SELECT COUNT(*) FROM ai_memory_item"), "超额的一条都不进");
        assertEquals(AddOutcome.ADDED, memory.add(memory("f-language", "active-ok",
                MemoryRepository.STATUS_ACTIVE, "AUTO")).outcome(), "ACTIVE 不受待审阅上限影响");
    }

    @Test
    void replaceSupersedesOldVersionAndInsertsNextInOneTransaction() throws Exception {
        var first = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        var replaced = memory.replace(VAULT, first.memoryId(), 1,
                memory("f-language", "v-1-replaced", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(ReplaceOutcome.REPLACED, replaced.outcome());
        assertEquals(2, replaced.version());
        List<FactRow> chain = jdbc.query("SELECT " + FACT + " FROM ai_memory_item WHERE memory_id = ? ORDER BY version",
                (result, index) -> map(result), first.memoryId());
        assertEquals(2, chain.size());
        assertEquals(MemoryRepository.STATUS_SUPERSEDED, chain.get(0).status());
        assertEquals(MemoryRepository.STATUS_ACTIVE, chain.get(1).status());
        assertEquals("v-1-replaced", chain.get(1).valueHmac());
        assertEquals(3, vault().revision(), "add + replace 各推进一次内容版本");
    }

    @Test
    void replaceWithStaleExpectedVersionRefusesAndKeepsOldFact() throws Exception {
        var first = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));
        long revisionBefore = vault().revision();

        var conflict = memory.replace(VAULT, first.memoryId(), 99,
                memory("f-language", "v-99", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(ReplaceOutcome.VERSION_CONFLICT, conflict.outcome());
        assertNotNull(conflict.current(), "409 必须带最新 metadata");
        assertEquals(1, conflict.current().version());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "CAS 失手时不删旧版、也不插新版");
        assertEquals(revisionBefore, vault().revision());
    }

    @Test
    void replaceTwiceWithTheSameOldVersionLosesTheSecondTime() throws Exception {
        var first = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(ReplaceOutcome.REPLACED, memory.replace(VAULT, first.memoryId(), 1,
                memory("f-language", "a", MemoryRepository.STATUS_ACTIVE, "MANUAL")).outcome());
        var late = memory.replace(VAULT, first.memoryId(), 1,
                memory("f-language", "b", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(ReplaceOutcome.VERSION_CONFLICT, late.outcome());
        assertEquals(2, late.current().version(), "回给调用方的是当前 ACTIVE 版本");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE status = 'ACTIVE'"),
                "失败的第二次替换不得再产生一个 ACTIVE");
    }

    @Test
    void unknownMemoryIdIsNotFoundRatherThanCreated() throws Exception {
        var missing = memory.replace(VAULT, "no-such-memory", 1,
                memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(ReplaceOutcome.NOT_FOUND, missing.outcome());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item"));
    }

    @Test
    void oneActivePerFamilyIsAHardIndexNotAServiceConvention() throws Exception {
        memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        // sqlite-jdbc 的错误码不在 Spring 的映射表里，落进 UncategorizedSQLException，但约束本身生效
        UncategorizedSQLException error = assertThrows(UncategorizedSQLException.class, () -> jdbc.update(
                "INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,"
                        + " predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta,"
                        + " origin, observed_at, recorded_at, last_confirmed_at)"
                        + " VALUES ('raw-2', 'raw-2', ?, 1, 'GLOBAL', 0, 'PREFERENCE', 'p', 'f-language', 'other',"
                        + " 'ACTIVE', 'x', '{}', 'AUTO', ?, ?, ?)", VAULT, NOW, NOW, NOW));
        assertTrue(error.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE"),
                "绕过仓储也必须被部分唯一索引挡住");
    }

    @Test
    void versionChainRejectsTwoRowsWithSameVersion() throws Exception {
        var first = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        UncategorizedSQLException error = assertThrows(UncategorizedSQLException.class, () -> jdbc.update(
                "INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,"
                        + " predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta,"
                        + " origin, observed_at, recorded_at, last_confirmed_at)"
                        + " VALUES ('raw-dup', ?, ?, 1, 'GLOBAL', 0, 'PREFERENCE', 'p', 'other', 'x',"
                        + " 'PENDING', 'y', '{}', 'AUTO', ?, ?, ?)", first.memoryId(), VAULT, NOW, NOW, NOW));
        assertTrue(error.getMessage().contains("UNIQUE constraint failed: ai_memory_item.memory_id, ai_memory_item.version"));
    }

    @Test
    void scopeAndOriginViolationsAreRefusedBeforeWriting() {
        assertThrows(SQLException.class, () -> memory.add(new NewMemory(VAULT, "NOTEBOOK", 0, "GOAL", "p", "f1", "v",
                MemoryRepository.STATUS_ACTIVE, "x", "{}", "AUTO", false, false, null, null, NOW, NOW, NOW)));
        assertThrows(SQLException.class, () -> memory.add(new NewMemory(VAULT, "GLOBAL", 7, "GOAL", "p", "f2", "v",
                MemoryRepository.STATUS_ACTIVE, "x", "{}", "AUTO", false, false, null, null, NOW, NOW, NOW)));
        assertThrows(SQLException.class, () -> memory.add(new NewMemory(VAULT, "GLOBAL", 0, "GOAL", "p", "f3", "v",
                "ARCHIVED", "x", "{}", "AUTO", false, false, null, null, NOW, NOW, NOW)));
        assertThrows(SQLException.class, () -> memory.add(new NewMemory(VAULT, "GLOBAL", 0, "GOAL", "p", "f4", "v",
                MemoryRepository.STATUS_ACTIVE, "x", "{}", "MODEL", false, false, null, null, NOW, NOW, NOW)));
        assertThrows(SQLException.class, () -> memory.add(new NewMemory("missing-vault", "GLOBAL", 0, "GOAL", "p",
                "f5", "v", MemoryRepository.STATUS_ACTIVE, "x", "{}", "AUTO", false, false, null, null, NOW, NOW, NOW)));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item"));
    }

    @Test
    void confirmRefreshesTimestampWithoutProducingANewVersion() throws Exception {
        var added = memory.add(memory("f-goal", "v-1", MemoryRepository.STATUS_ACTIVE, "AUTO"));

        memory.confirm(VAULT, added.memoryId(), 1, LATER);

        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "重复确认不产生新版（多值事实）");
        assertEquals(LATER, memory.findCurrent(VAULT, added.memoryId()).orElseThrow().lastConfirmedAt());
        assertThrows(SQLException.class, () -> memory.confirm(VAULT, added.memoryId(), 42, NOW),
                "确认必须落在真实存在的版本上");
    }

    @Test
    void statusChangeIsVersionCasAndNeverRewritesHistory() throws Exception {
        var added = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "MANUAL"));

        assertEquals(CasOutcome.VERSION_CONFLICT,
                memory.setStatus(VAULT, added.memoryId(), 99, MemoryRepository.STATUS_ACTIVE, "DISABLED"));
        assertEquals(CasOutcome.APPLIED,
                memory.setStatus(VAULT, added.memoryId(), 1, MemoryRepository.STATUS_ACTIVE, "DISABLED"));
        assertEquals(CasOutcome.NOT_FOUND,
                memory.setStatus(VAULT, added.memoryId(), 1, MemoryRepository.STATUS_ACTIVE, "DISABLED"));

        assertEquals("DISABLED", jdbc.queryForObject(
                "SELECT status FROM ai_memory_item WHERE row_id = ?", String.class, added.rowId()));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "停用只改状态，不删版本也不造新版");
    }

    @Test
    void vaultRevisionAndEpochMoveSeparatelyTogether() throws Exception {
        assertEquals(1, vault().revision());
        assertEquals(1, vault().epoch());

        VaultState afterRevision = memory.bumpRevision(VAULT);
        assertEquals(2, afterRevision.revision());
        assertEquals(1, afterRevision.epoch(), "内容版本推进不是遗忘边界");

        VaultState afterEpoch = memory.bumpEpoch(VAULT);
        assertEquals(3, afterEpoch.revision());
        assertEquals(2, afterEpoch.epoch());
        assertThrows(SQLException.class, () -> memory.bumpRevision("no-such-vault"));
    }

    @Test
    void evidenceDeduplicatesByVaultAndSourceHmac() throws Exception {
        EvidenceDraft draft = evidence("hmac-source-1", "原话一");

        var first = memory.insertEvidence(draft);
        var second = memory.insertEvidence(draft);

        assertFalse(first.reused());
        assertTrue(second.reused(), "同一来源重复提交只留一份原话");
        assertEquals(first.evidenceId(), second.evidenceId());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_evidence"));
        UncategorizedSQLException error = assertThrows(UncategorizedSQLException.class, () -> jdbc.update(
                "INSERT INTO ai_memory_evidence(id, vault_id, source_block_id, source_hmac, quote_cipher,"
                        + " crypto_meta, source_created_at) VALUES ('raw-ev', ?, 'b1', 'hmac-source-1', '别的原话',"
                        + " '{}', ?)", VAULT, NOW));
        assertTrue(error.getMessage().contains("UNIQUE constraint failed: ai_memory_evidence.vault_id,"
                + " ai_memory_evidence.source_hmac"));
    }

    @Test
    void attachEvidenceLinksOnceAndKeepsOneCopy() throws Exception {
        var fact = memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "AUTO"));
        EvidenceDraft draft = evidence("hmac-block", "被采纳的最短原话");

        var firstLink = memory.attachEvidence(draft, fact.rowId());
        var secondLink = memory.attachEvidence(draft, fact.rowId());

        assertEquals(firstLink.evidenceId(), secondLink.evidenceId());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_evidence"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item_evidence"), "重复关联是幂等的");
        assertEquals(List.of(firstLink.evidenceId()), memory.evidenceIdsForItem(fact.rowId()));
    }

    @Test
    void evidenceRefusesAnythingLongerThanTheWrappedQuoteCap() throws Exception {
        int cap = (int) MemoryRepository.MAX_QUOTE_CIPHER_CODE_POINTS;

        assertNotNull(memory.insertEvidence(evidence("hmac-at-cap", "A".repeat(cap))).evidenceId());
        assertThrows(IllegalArgumentException.class, () -> evidence("hmac-over", "A".repeat(cap + 1)),
                "超过 400 明文码点封装上界的原话根本不该进仓储");
    }

    @Test
    void deletedSourcesDropOrphanAutoFactsButKeepProtectedContent() throws Exception {
        long turnOne = insertTurn();
        long turnTwo = insertTurn();

        String autoWithTwoSources = attachFact("f-a", "v-a", turnOne, turnTwo);
        String autoOnlySource = attachFact("f-b", "v-b", turnOne);
        String protectedOnlySource = attachFactProtected("f-c", "v-c", turnOne);
        long revisionBeforePurge = vault().revision();

        insertProposal("prop-by-evidence", evidenceIdOf(turnOne, "f-a"), autoOnlySource);
        insertProposal("prop-by-target", evidenceIdOf(turnTwo, "f-a"), autoOnlySource);

        PurgeResult result = memory.purgeEvidenceForDeletedSources(null, List.of(turnOne), List.of());

        // turnOne 上挂着 f-a / f-b / f-c 三条原话，全部按同一来源范围一起清掉
        assertEquals(3, result.evidenceDeleted());
        assertEquals(1, result.autoFactsRemoved(), "没有任何来源的自动事实被移除");
        assertEquals(1, result.protectedFactsKept(), "人工保护记忆留着，只去掉被删来源");
        assertEquals(1, result.proposalsDeleted(), "引用被删证据的建议直接删，不靠 SET NULL 留原话");
        assertEquals(1, result.proposalsStaled(), "目标被移除的建议变 STALE");
        assertTrue(result.changed());
        assertEquals(revisionBeforePurge + 1, result.vaultRevision());
        assertEquals(revisionBeforePurge + 1, vault().revision(), "证据/事实/建议任一变更都要推进内容版本");

        assertTrue(memory.findCurrent(VAULT, autoWithTwoSources).isPresent(), "还有另一条来源的自动事实不受影响");
        assertTrue(memory.findCurrent(VAULT, autoOnlySource).isEmpty());
        FactRow kept = memory.findCurrent(VAULT, protectedOnlySource).orElseThrow();
        assertEquals("MANUAL", kept.origin());
        assertEquals(List.of(), memory.evidenceIdsForItem(kept.rowId()));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item_evidence WHERE item_row_id = '" + kept.rowId() + "'"),
                "关联行随证据级联清掉");
        assertEquals("STALE", jdbc.queryForObject(
                "SELECT status FROM ai_memory_proposal WHERE id = 'prop-by-target'", String.class));
    }

    @Test
    void purgeFindsLegacyEvidenceThatOnlyCarriesSessionId() throws Exception {
        // 旧版证据没有 turn/message 指针，§2.3 要求仍用 session_id 定位
        jdbc.update("INSERT INTO ai_memory_evidence(id, vault_id, session_id, source_block_id, source_hmac,"
                + " quote_cipher, crypto_meta, source_created_at) VALUES ('ev-legacy', ?, ?, 'b0', 'hmac-legacy',"
                + " '旧原话', '{}', ?)", VAULT, sessionId, NOW);

        PurgeResult result = memory.purgeEvidenceForDeletedSources(sessionId, List.of(), List.of());

        assertEquals(1, result.evidenceDeleted());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence"));
    }

    @Test
    void purgeWithoutAnyScopeIsRefused() {
        assertThrows(SQLException.class, () -> memory.purgeEvidenceForDeletedSources(null, List.of(), List.of()));
    }

    @Test
    void purgeWithNothingMatchedWritesNothing() throws Exception {
        memory.add(memory("f-language", "v-1", MemoryRepository.STATUS_ACTIVE, "AUTO"));
        long revisionBefore = vault().revision();

        PurgeResult result = memory.purgeEvidenceForDeletedSources(null, List.of(9_999L), List.of());

        assertEquals(0, result.evidenceDeleted());
        assertFalse(result.changed());
        assertEquals(revisionBefore, vault().revision());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"));
    }

    // ---- helpers ----

    private NewMemory memory(String family, String valueHmac, String status, String origin) {
        return new NewMemory(VAULT, "GLOBAL", 0, "PREFERENCE", "predicate-" + family, family, valueHmac, status,
                "payload-" + valueHmac, "{}", origin, "MANUAL".equals(origin), false, null, null, NOW, NOW, NOW);
    }

    private EvidenceDraft evidence(String sourceHmac, String quoteCipher) {
        return new EvidenceDraft(VAULT, sessionId, null, null, "u0", sourceHmac, quoteCipher, "{}", NOW);
    }

    /** 给一条自动事实挂上若干来源，返回它的 memoryId。 */
    private String attachFact(String family, String value, long... turnIds) throws SQLException {
        var added = memory.add(memory(family, value, MemoryRepository.STATUS_ACTIVE, "AUTO"));
        for (long turnId : turnIds) {
            memory.attachEvidence(evidenceFor(turnId, family), added.rowId());
        }
        return added.memoryId();
    }

    private String attachFactProtected(String family, String value, long turnId) throws SQLException {
        var added = memory.add(memory(family, value, MemoryRepository.STATUS_ACTIVE, "MANUAL"));
        memory.attachEvidence(evidenceFor(turnId, family), added.rowId());
        return added.memoryId();
    }

    private EvidenceDraft evidenceFor(long turnId, String family) {
        return new EvidenceDraft(VAULT, sessionId, turnId, null, "u0",
                "hmac-turn-" + turnId + "-" + family, "原话-" + turnId, "{}", NOW);
    }

    private String evidenceIdOf(long turnId, String family) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT id FROM ai_memory_evidence WHERE source_hmac = ? LIMIT 1")) {
            statement.setString(1, "hmac-turn-" + turnId + "-" + family);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void insertProposal(String id, String evidenceId, String targetMemoryId) {
        jdbc.update("INSERT INTO ai_memory_proposal(id, vault_id, session_id, evidence_id, target_memory_id,"
                        + " target_version, operation, status, payload_cipher, crypto_meta, dedup_hmac, created_at,"
                        + " expires_at) VALUES (?, ?, ?, ?, ?, 1, 'REPLACE_PROPOSAL', 'PENDING', 'p', '{}', ?, ?, ?)",
                id, VAULT, sessionId, evidenceId, targetMemoryId, "dedup-" + id, NOW, EXPIRES);
    }

    private long insertTurn() {
        long id = nextTurnId++;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("INSERT INTO ai_chat_turn(id, request_id, session_id,"
                     + " status, request_meta_cipher, request_meta_crypto, context_generation, source_scope_type,"
                     + " source_scope_id, source_timezone, runtime_generation, started_at)"
                     + " VALUES (?, ?, ?, 'COMPLETED', 'm', '{}', 1, 'GLOBAL', 0, 'Asia/Shanghai', 'gen-test', ?)")) {
            statement.setLong(1, id);
            statement.setString(2, "req-turn-" + id);
            statement.setLong(3, sessionId);
            statement.setString(4, NOW);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        return id;
    }

    private VaultState vault() throws SQLException {
        return memory.readVault(VAULT).orElseThrow();
    }

    private static FactRow map(ResultSet result) throws SQLException {
        return new FactRow(result.getString("row_id"), result.getString("memory_id"),
                result.getString("vault_id"), result.getInt("version"), result.getString("scope_type"),
                result.getLong("scope_id"), result.getString("kind"), result.getString("predicate_key_hmac"),
                result.getString("family_key_hmac"), result.getString("value_hmac"), result.getString("status"),
                result.getString("payload_cipher"), result.getString("crypto_meta"), result.getString("origin"),
                result.getInt("manual_protected") == 1, result.getInt("pinned") == 1,
                result.getString("valid_from"), result.getString("valid_until"), result.getString("observed_at"),
                result.getString("recorded_at"), result.getString("last_confirmed_at"));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
