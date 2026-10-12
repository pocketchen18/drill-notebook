package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MemorySuppressionRepository.RecordOutcome;
import com.drillnotebook.app.repository.MemorySuppressionRepository.Suppression;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 遗忘与拒绝抑制（contracts §2.4）：主键幂等、三档互不牵连、只能显式解除，库里只有 HMAC。
 */
class MemorySuppressionRepositoryTest {
    private static final String VAULT = "vault-1";
    private static final String NOW = "2026-10-11T00:00:00.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemorySuppressionRepository suppressions;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1c-suppression-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        suppressions = new MemorySuppressionRepository(dataSource, new ImmediateTransaction(dataSource));
        jdbc.update("INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                + " VALUES (?, 'dek', '{}', 'fingerprint', ?)", VAULT, NOW);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void recordingTheSameKeyTwiceIsIdempotent() throws Exception {
        assertEquals(RecordOutcome.APPLIED,
                suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_FAMILY, "hmac-family", 12L)));
        assertEquals(RecordOutcome.ALREADY_PRESENT,
                suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_FAMILY, "hmac-family", 40L)));
        assertEquals(1, suppressions.countAll(VAULT), "重复抑制不产生第二行");
        assertTrue(suppressions.isSuppressed(VAULT, "GLOBAL", 0, MemorySuppressionRepository.KIND_FAMILY,
                "hmac-family"));
    }

    @Test
    void theThreeKindsDoNotLeakIntoEachOther() throws Exception {
        suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_FAMILY, "same-key", null));
        suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_PREDICATE, "same-key", null));
        suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_EVIDENCE, "same-key", null));

        assertEquals(3, suppressions.countAll(VAULT));
        List<String> familyKeys = suppressions.activeKeys(VAULT, "GLOBAL", 0,
                MemorySuppressionRepository.KIND_FAMILY);
        List<String> predicateKeys = suppressions.activeKeys(VAULT, "GLOBAL", 0,
                MemorySuppressionRepository.KIND_PREDICATE);
        List<String> evidenceKeys = suppressions.activeKeys(VAULT, "GLOBAL", 0,
                MemorySuppressionRepository.KIND_EVIDENCE);
        assertEquals(List.of("same-key"), familyKeys);
        assertEquals(List.of("same-key"), predicateKeys);
        assertEquals(List.of("same-key"), evidenceKeys);
    }

    @Test
    void notebookScopeDoesNotSuppressTheGlobalOne() throws Exception {
        suppressions.record(VAULT, new Suppression("NOTEBOOK", 7, MemorySuppressionRepository.KIND_FAMILY,
                "hmac-shared", null, NOW));

        assertFalse(suppressions.isSuppressed(VAULT, "NOTEBOOK", 8, MemorySuppressionRepository.KIND_FAMILY,
                "hmac-shared"), "另一个笔记本不受影响");
        assertFalse(suppressions.isSuppressed(VAULT, "GLOBAL", 0, MemorySuppressionRepository.KIND_FAMILY,
                "hmac-shared"));
        assertTrue(suppressions.isSuppressed(VAULT, "NOTEBOOK", 7, MemorySuppressionRepository.KIND_FAMILY,
                "hmac-shared"));
    }

    @Test
    void releaseOnlyRemovesTheExplicitlySelectedKey() throws Exception {
        suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_FAMILY, "hmac-a", null));
        suppressions.record(VAULT, suppression(MemorySuppressionRepository.KIND_FAMILY, "hmac-b", null));

        assertEquals(1, suppressions.release(VAULT, "GLOBAL", 0, MemorySuppressionRepository.KIND_FAMILY, "hmac-a"));
        assertEquals(0, suppressions.release(VAULT, "GLOBAL", 0, MemorySuppressionRepository.KIND_FAMILY, "hmac-a"),
                "再次解除返回 0，供接口给出\"无变化\"");
        assertEquals(List.of("hmac-b"),
                suppressions.activeKeys(VAULT, "GLOBAL", 0, MemorySuppressionRepository.KIND_FAMILY));
    }

    @Test
    void unknownKindOrMissingVaultIsRefused() throws Exception {
        assertThrows(SQLException.class, () -> suppressions.record(VAULT,
                suppression("MEMORY", "hmac", null)));
        // 仓储自己开连接，外键违反原样抛出 SQLException（不经 Spring 异常翻译）
        assertThrows(SQLException.class, () -> suppressions.record("no-such-vault",
                suppression(MemorySuppressionRepository.KIND_FAMILY, "hmac", null)));
        assertThrows(SQLException.class, () -> suppressions.activeKeys(VAULT, "GLOBAL", 0, "ANY"));
        assertEquals(0, suppressions.countAll(VAULT));
    }

    private Suppression suppression(String kind, String keyHmac, Long throughTurnId) {
        return new Suppression("GLOBAL", 0, kind, keyHmac, throughTurnId, NOW);
    }
}
