package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.ArchiveResult;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.ScanKind;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.State;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话记忆/调度元数据（contracts §2.1、§2.5）：水位只前进、唤醒与归档的相互关系、
 * 作用域切换换代并前移水位、摘要尝试与跳过计数。
 */
class ChatSessionMetadataRepositoryTest {
    private static final String NOW = "2026-10-11T00:00:00.000Z";
    private static final String STAMP = "2026-10-11T00:05:00.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ImmediateTransaction immediate;
    private ChatSessionMetadataRepository metadata;
    private long sessionId;
    private long nextTurnId = 100;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-meta-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        immediate = new ImmediateTransaction(dataSource);
        metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void freshSessionCarriesDocumentedDefaults() throws Exception {
        State state = metadata.read(sessionId).orElseThrow();

        assertEquals("GLOBAL", state.memoryScopeType());
        assertNull(state.memoryNotebookId());
        assertTrue(state.learnEnabled());
        assertEquals(1, state.contextGeneration());
        assertEquals(1, state.historyRevision());
        assertEquals(0, state.memoryScanAfterTurn());
        assertEquals(0, state.summaryScanAfterTurn());
        assertNull(state.archivedAt());
        assertFalse(state.maintenanceWakePending());
        assertEquals(0, state.memorySkippedCount());
        assertEquals(0, state.summarySkippedCount());
        assertTrue(metadata.read(sessionId + 999).isEmpty(), "不存在的会话返回空而不是造一行");
    }

    @Test
    void scanWatermarksOnlyMoveForwardAndStayIndependent() throws Exception {
        long first = inTransaction(connection ->
                metadata.advanceScanWatermark(connection, sessionId, ScanKind.MEMORY, 7));
        assertEquals(7, first);
        long rolledBack = inTransaction(connection ->
                metadata.advanceScanWatermark(connection, sessionId, ScanKind.MEMORY, 3));
        assertEquals(7, rolledBack, "水位表示「已检查至此」，回退会重复扫已跳过的源");

        inTransaction(connection ->
                metadata.advanceScanWatermark(connection, sessionId, ScanKind.SUMMARY, 2));
        State state = metadata.read(sessionId).orElseThrow();
        assertEquals(7, state.memoryScanAfterTurn(), "两条水位各自独立，不互相牵连");
        assertEquals(2, state.summaryScanAfterTurn());
    }

    @Test
    void maintenanceWakeSurvivesUnarchiveButNotArchive() throws Exception {
        run(connection -> metadata.markMaintenanceWake(connection, sessionId));
        assertTrue(metadata.read(sessionId).orElseThrow().maintenanceWakePending());

        assertSame(ArchiveResult.ARCHIVED, metadata.setArchived(sessionId, true, STAMP));
        State archived = metadata.read(sessionId).orElseThrow();
        assertFalse(archived.maintenanceWakePending(), "归档暂停维护，积压唤醒要清掉");
        assertEquals(STAMP, archived.archivedAt());

        assertSame(ArchiveResult.UNARCHIVED, metadata.setArchived(sessionId, false, null));
        State reopened = metadata.read(sessionId).orElseThrow();
        assertFalse(reopened.maintenanceWakePending(), "取消归档与只读打开都不置唤醒");
        assertNull(reopened.archivedAt());
    }

    @Test
    void archiveRefusedWhileTurnRunning() throws Exception {
        insertTurn(sessionId, ChatTurnRepository.STATUS_RUNNING);

        assertSame(ArchiveResult.REFUSED_RUNNING, metadata.setArchived(sessionId, true, STAMP));
        State state = metadata.read(sessionId).orElseThrow();
        assertEquals(0, count("SELECT archived FROM ai_chat_session WHERE id = " + sessionId));
        assertNull(state.archivedAt(), "拒绝归档时一个字段都不改");

        jdbc.update("UPDATE ai_chat_turn SET status = ? WHERE session_id = ?",
                ChatTurnRepository.STATUS_COMPLETED, sessionId);
        assertSame(ArchiveResult.ARCHIVED, metadata.setArchived(sessionId, true, STAMP));
        assertSame(ArchiveResult.NOT_FOUND, metadata.setArchived(sessionId + 500, true, STAMP));
    }

    @Test
    void scopeSwitchRotatesGenerationAndAdvancesBothWatermarks() throws Exception {
        inTransaction(connection ->
                metadata.advanceScanWatermark(connection, sessionId, ScanKind.MEMORY, 4));

        long generation = metadata.changeScope(sessionId, "NOTEBOOK", 12L, 9L);
        State switched = metadata.read(sessionId).orElseThrow();
        assertEquals(2, generation);
        assertEquals(2, switched.contextGeneration());
        assertEquals("NOTEBOOK", switched.memoryScopeType());
        assertEquals(12L, switched.memoryNotebookId());
        assertEquals(9, switched.memoryScanAfterTurn(), "切换点之后的源才可学");
        assertEquals(9, switched.summaryScanAfterTurn());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"), "切作用域不动任何历史行");

        // 切回 GLOBAL 时水位不回退：restartThrough 比现有水位小就保持
        metadata.changeScope(sessionId, "GLOBAL", null, 5L);
        State back = metadata.read(sessionId).orElseThrow();
        assertEquals(3, back.contextGeneration());
        assertEquals("GLOBAL", back.memoryScopeType());
        assertNull(back.memoryNotebookId());
        assertEquals(9, back.memoryScanAfterTurn());
    }

    @Test
    void scopeSwitchRejectsInconsistentNotebookId() {
        assertThrows(SQLException.class, () -> metadata.changeScope(sessionId, "NOTEBOOK", null, 1L));
        assertThrows(SQLException.class, () -> metadata.changeScope(sessionId, "NOTEBOOK", 0L, 1L));
        assertThrows(SQLException.class, () -> metadata.changeScope(sessionId, "GLOBAL", 7L, 1L));
        assertThrows(SQLException.class, () -> metadata.changeScope(sessionId, "PAGE", null, 1L));
        assertEquals("GLOBAL", jdbc.queryForObject(
                "SELECT memory_scope_type FROM ai_chat_session WHERE id = ?", String.class, sessionId),
                "被拒的切换不得留下半改状态");
    }

    @Test
    void reEnablingLearningStartsAfterTheTurnsItSkipped() throws Exception {
        inTransaction(connection ->
                metadata.advanceScanWatermark(connection, sessionId, ScanKind.MEMORY, 6));
        metadata.setLearnEnabled(sessionId, false, 0L);
        assertFalse(metadata.read(sessionId).orElseThrow().learnEnabled());
        assertEquals(6, metadata.read(sessionId).orElseThrow().memoryScanAfterTurn(), "关闭不清水位");

        metadata.setLearnEnabled(sessionId, true, 11L);
        State state = metadata.read(sessionId).orElseThrow();
        assertTrue(state.learnEnabled());
        assertEquals(11, state.memoryScanAfterTurn());
        assertEquals(11, state.summaryScanAfterTurn(), "关闭期间新增的源不再回扫");
    }

    @Test
    void summaryAttemptRecordsCooldownInputs() throws Exception {
        run(connection -> metadata.recordSummaryAttempt(connection, sessionId, STAMP, 12L, "3:7"));

        State state = metadata.read(sessionId).orElseThrow();
        assertEquals(STAMP, state.summaryLastAttemptAt());
        assertEquals(12, state.summaryLastAttemptThrough());
        assertEquals("3:7", state.summaryLastInputRevision());
        assertThrows(SQLException.class, () ->
                run(connection -> metadata.recordSummaryAttempt(connection, sessionId + 500, STAMP, 1L, "1:1")),
                "会话不存在时不得静默成功");
    }

    @Test
    void skipCountersAccumulatePerKindWithoutTouchingContent() throws Exception {
        run(connection -> metadata.recordSkips(connection, sessionId, ScanKind.MEMORY, 3, "SOURCE_TOO_LARGE"));
        run(connection -> metadata.recordSkips(connection, sessionId, ScanKind.MEMORY, 2, "NO_VALID_CANDIDATES"));
        run(connection -> metadata.recordSkips(connection, sessionId, ScanKind.SUMMARY, 1, "NO_COMPRESSION_GAIN"));
        run(connection -> metadata.recordSkips(connection, sessionId, ScanKind.MEMORY, -5, "NO_VALID_CANDIDATES"));

        State state = metadata.read(sessionId).orElseThrow();
        assertEquals(5, state.memorySkippedCount(), "负数增量按 0 处理，计数不会倒退");
        assertEquals(1, state.summarySkippedCount());
        assertEquals("NO_VALID_CANDIDATES", state.lastScanNotice(), "只存短代码，不存正文");
    }

    @Test
    void historyRevisionBumpsAndReturnsNewValue() throws Exception {
        assertEquals(2, metadata.bumpHistoryRevision(sessionId));
        assertEquals(3, inTransaction(connection -> metadata.bumpHistoryRevision(connection, sessionId)).longValue());
        assertEquals(3, metadata.read(sessionId).orElseThrow().historyRevision());
        assertThrows(SQLException.class, () -> metadata.bumpHistoryRevision(sessionId + 700));
    }

    @Test
    void contextGenerationResetIsSeparateFromScopeSwitch() throws Exception {
        metadata.changeScope(sessionId, "NOTEBOOK", 3L, 1L);
        long after = metadata.resetContextGeneration(sessionId);

        State state = metadata.read(sessionId).orElseThrow();
        assertEquals(3, after);
        assertEquals(3, state.contextGeneration());
        assertEquals("NOTEBOOK", state.memoryScopeType(), "遗忘只换代，不改作用域");
    }

    @Test
    void bulkWatermarkAdvanceIsForwardOnlyAndPerSession() throws Exception {
        insertTurn(sessionId, ChatTurnRepository.STATUS_COMPLETED);
        insertTurn(sessionId, ChatTurnRepository.STATUS_COMPLETED);
        long other = 991L;
        jdbc.update("INSERT INTO ai_chat_session(id, title) VALUES (?, '第二个会话')", other);
        insertTurn(other, ChatTurnRepository.STATUS_COMPLETED);
        jdbc.update("UPDATE ai_chat_session SET memory_scan_after_turn = 999 WHERE id = ?", other);

        int advanced = metadata.advanceAllScanWatermarks(ScanKind.MEMORY);

        assertEquals(1, advanced, "只有落后的那个会话被推进");
        assertEquals(101L, metadata.read(sessionId).orElseThrow().memoryScanAfterTurn());
        assertEquals(999L, metadata.read(other).orElseThrow().memoryScanAfterTurn(), "领先的水位不被回拨");
        assertEquals(0L, metadata.read(other).orElseThrow().summaryScanAfterTurn(), "另一种水位不受影响");
        assertEquals(0, metadata.advanceAllScanWatermarks(ScanKind.MEMORY), "重跑没有变化");
        assertEquals(jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_turn", Long.class),
                metadata.globalMaxTurnId());
    }

    // ---- helpers ----

    private interface InConnection {
        void apply(Connection connection) throws SQLException;
    }

    private <T> T inTransaction(ImmediateTransaction.Work<T> work) throws SQLException {
        return immediate.execute(work);
    }

    private void run(InConnection work) throws SQLException {
        immediate.execute(connection -> {
            work.apply(connection);
            return null;
        });
    }

    private void insertTurn(long session, String status) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                    INSERT INTO ai_chat_turn(id, request_id, session_id, status, request_meta_cipher,
                        request_meta_crypto, context_generation, source_scope_type, source_scope_id,
                        source_timezone, runtime_generation, started_at)
                    VALUES (?, ?, ?, ?, 'm', '{}', 1, 'GLOBAL', 0, 'Asia/Shanghai', 'gen-test', ?)
                    """)) {
            statement.setLong(1, nextTurnId);
            statement.setString(2, "req-" + nextTurnId);
            statement.setLong(3, session);
            statement.setString(4, status);
            statement.setString(5, NOW);
            statement.executeUpdate();
            nextTurnId++;
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
