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
import com.drillnotebook.app.repository.MemoryOperationRepository.OperationRow;
import com.drillnotebook.app.repository.MemoryOperationRepository.StartOutcome;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * operationId 去重登记（contracts §3 405 行）：重放只回吐已完成的结果，进行中的同键动作既不重跑也不冒充完成，
 * 被占用的 ID 一律 409，失败必须腾出 ID 才能重试；登记里不能有密码与正文。
 */
class MemoryOperationRepositoryTest {
    private static final String CREATED = "2026-10-12T00:00:00.000Z";
    private static final String DONE = "2026-10-12T00:00:05.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryOperationRepository operations;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-operation-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        operations = new MemoryOperationRepository(dataSource, new ImmediateTransaction(dataSource));
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void firstRegistrationIsInProgressAndReplayCarriesTheStoredBody() throws Exception {
        assertEquals(StartOutcome.NEW, operations.start("op-1", "VAULT_CREATE", null, CREATED).outcome());
        OperationRow running = operations.find("op-1").orElseThrow();
        assertEquals("IN_PROGRESS", running.status());
        assertFalse(running.completed());
        assertNull(running.resultMeta());

        operations.complete("op-1", "{\"vaultId\":\"v-1\"}", DONE);
        OperationRow done = operations.find("op-1").orElseThrow();
        assertEquals("COMPLETED", done.status());
        assertTrue(done.completed());
        assertEquals(DONE, done.completedAt());

        var replay = operations.start("op-1", "VAULT_CREATE", null, CREATED);
        assertEquals(StartOutcome.REPLAY, replay.outcome());
        assertEquals("{\"vaultId\":\"v-1\"}", replay.resultMeta());
        assertEquals(1L, count(), "重放不再插第二行");
    }

    @Test
    void sameKeyStillRunningIsNotAReplay() throws Exception {
        operations.start("op-2", "MEMORY_CLEAR", "vault-9", CREATED);
        var second = operations.start("op-2", "MEMORY_CLEAR", "vault-9", CREATED);
        assertEquals(StartOutcome.IN_FLIGHT, second.outcome());
        assertNull(second.resultMeta(), "还没做完就没有可回吐的响应");
    }

    @Test
    void idReusedByAnotherActionOrTargetConflicts() throws Exception {
        operations.start("op-3", "VAULT_CREATE", null, CREATED);
        assertEquals(StartOutcome.CONFLICT,
                operations.start("op-3", "VAULT_RESET", null, CREATED).outcome());
        assertEquals(StartOutcome.CONFLICT,
                operations.start("op-3", "VAULT_CREATE", "item-7", CREATED).outcome());
        assertEquals("VAULT_CREATE", operations.find("op-3").orElseThrow().action(), "冲突不改写原登记");
    }

    @Test
    void nullTargetAndSameActionAreNotAConflict() throws Exception {
        assertEquals(StartOutcome.NEW, operations.start("op-4", "MEMORY_ITEM_ADD", null, CREATED).outcome());
        assertEquals(StartOutcome.IN_FLIGHT, operations.start("op-4", "MEMORY_ITEM_ADD", null, CREATED).outcome());
    }

    @Test
    void abandoningReleasesTheIdButNeverTouchesACompletedRecord() throws Exception {
        operations.start("op-5", "VAULT_CREATE", null, CREATED);
        operations.abandon("op-5");
        assertTrue(operations.find("op-5").isEmpty());
        assertEquals(StartOutcome.NEW, operations.start("op-5", "VAULT_CREATE", null, CREATED).outcome());

        operations.complete("op-5", "{}", DONE);
        operations.abandon("op-5");
        assertEquals("COMPLETED", operations.find("op-5").orElseThrow().status(), "已完成的登记是幂等凭据，不能删");
    }

    @Test
    void completingAnUnregisteredIdFails() {
        assertThrows(SQLException.class, () -> operations.complete("op-ghost", "{}", DONE));
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_operation", Long.class);
    }
}
