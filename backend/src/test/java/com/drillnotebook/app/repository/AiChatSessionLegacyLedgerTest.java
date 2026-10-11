package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.ChatTurnRepository.AttachmentPayload;
import com.drillnotebook.app.repository.ChatTurnRepository.Draft;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceDraft;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 旧（v1）聊天写路径与容量账本同事务（contracts §2.7）：新增即时记账、裁剪只裁 legacy 消息、
 * 删除会话把该会话的历史/附件/未结算预留一并扣回，失败写入不留半笔账。
 */
class AiChatSessionLegacyLedgerTest {
    private static final String NOW = "2026-10-11T00:00:00.000Z";
    private static final String VAULT = "vault-1";
    private static final AttachmentPayload IMAGE =
            new AttachmentPayload("att-1", "a0", "IMAGE", new byte[64], "{\"aad\":\"x\"}");

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ImmediateTransaction immediate;
    private ChatStorageLedger ledger;
    private ChatTurnRepository turns;
    private AiChatSessionRepository sessions;
    private MemoryRepository memory;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-legacy-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        immediate = new ImmediateTransaction(dataSource);
        ledger = new ChatStorageLedger(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        memory = new MemoryRepository(dataSource, immediate);
        turns = new ChatTurnRepository(dataSource, immediate, ledger, metadata, memory);
        sessions = new AiChatSessionRepository(jdbc, immediate, ledger, metadata, memory);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void insertMessageAccountsEveryStoredColumn() throws Exception {
        sessions.insertMessage(sessionId, "user", "中文正文", "Y2lwaGVy", "{}", null, null);

        long expected = bytes("中文正文", "Y2lwaGVy", "{}", null, null);
        ChatStorageLedger.Snapshot snapshot = ledger.read();
        assertEquals(expected, snapshot.historyBytes(), "计数按 UTF-8 落库字节，不是字符数");
        assertTrue(ledger.reconcile().consistent(), "写入与记账同事务");
        assertEquals(2, revision(), "消息新增推进 history_revision");
    }

    @Test
    void plaintextFallbackMessageIsCountedToo() throws Exception {
        sessions.insertMessage(sessionId, "assistant", "明文兜底", null, null);

        assertEquals(bytes("明文兜底", null, null, null, null), ledger.read().historyBytes());
        assertTrue(ledger.reconcile().consistent());
    }

    @Test
    void firstLegacyWriteRebuildsCountersFromMigratedRows() throws Exception {
        // 模拟迁移后的旧数据：直接写行、不记账，账本仍是 0
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'user', '迁移前的旧消息')",
                sessionId);
        assertEquals(0, ledger.read().historyBytes());

        sessions.insertMessage(sessionId, "user", "新消息", null, null);

        assertEquals(bytes("迁移前的旧消息", null, null, null, null) + bytes("新消息", null, null, null, null),
                ledger.read().historyBytes(), "开放写入口前先按实测重建，legacy 行不得漏计");
        assertTrue(ledger.reconcile().consistent());
    }

    @Test
    void pruneReleasesOnlyLegacyMessages() throws Exception {
        turns.begin(draft("req-1", sessionId, "轮次里的用户正文", List.of()));
        sessions.insertMessage(sessionId, "user", "L1", null, null);
        long afterL1 = ledger.read().historyBytes();
        sessions.insertMessage(sessionId, "assistant", "L2", null, null);
        sessions.insertMessage(sessionId, "user", "L3", null, null);
        long revisionBeforePrune = revision();

        sessions.pruneToNewest(sessionId, 2);

        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id IS NOT NULL"),
                "带轮次的正文只由轮次侧的显式清理删除");
        assertEquals(3, count("SELECT COUNT(*) FROM ai_chat_message"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE content = 'L1'"));
        assertEquals(afterL1 + bytes("L2", null, null, null, null) + bytes("L3", null, null, null, null)
                        - bytes("L1", null, null, null, null),
                ledger.read().historyBytes(), "扣减恰等于删掉的字节");
        assertTrue(ledger.reconcile().consistent());
        assertEquals(revisionBeforePrune + 1, revision(), "裁剪是一次源变更");
    }

    @Test
    void pruneThatDeletesNothingLeavesLedgerAndRevisionAlone() throws Exception {
        sessions.insertMessage(sessionId, "user", "唯一一条", null, null);
        ChatStorageLedger.Snapshot before = ledger.read();
        long revisionBefore = revision();

        sessions.pruneToNewest(sessionId, 5);

        assertEquals(before, ledger.read());
        assertEquals(revisionBefore, revision());
    }

    @Test
    void deleteSessionReleasesHistoryAttachmentsAndLiveReservation() throws Exception {
        long doomed = newSession("待删会话");
        sessions.insertMessage(doomed, "user", "要删掉的正文", null, null);
        turns.begin(draft("req-doomed", doomed, "轮次正文", List.of(IMAGE)));

        ChatStorageLedger.Snapshot before = ledger.read();
        sessions.delete(doomed);
        ChatStorageLedger.Snapshot after = ledger.read();

        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_session WHERE id = " + doomed));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE session_id = " + doomed));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE session_id = " + doomed));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_attachment"));
        assertEquals(before.attachmentBytes() - IMAGE.storedBytes(), after.attachmentBytes());
        assertEquals(before.reservedHistoryBytes() - ChatStorageSizer.REPLY_RESERVATION_BYTES,
                after.reservedHistoryBytes(), "未结算的回复预留随会话一起释放");
        assertTrue(ledger.reconcile().consistent(), "删除与账本扣减同事务");
        assertEquals(bytes("要删掉的正文", null, null, null, null)
                        + ChatStorageSizer.storedBytes("{}", "{}")
                        + bytes("轮次正文", null, null, null, null),
                before.historyBytes() - after.historyBytes(), "扣掉的正是该会话实测的字节");
    }

    @Test
    void deleteSessionPurgesMemorySourcesInTheSameTransaction() throws Exception {
        jdbc.update("INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                + " VALUES (?, 'dek', '{}', 'fingerprint', ?)", VAULT, NOW);
        long doomed = newSession("带记忆的会话");
        sessions.insertMessage(doomed, "user", "被采纳的原话", null, null);
        long messageId = jdbc.queryForObject(
                "SELECT id FROM ai_chat_message WHERE session_id = ? AND content = ?", Long.class, doomed, "被采纳的原话");
        var auto = memory.add(fact("f-auto", "AUTO", false));
        var manual = memory.add(fact("f-manual", "MANUAL", true));
        EvidenceDraft shared = new EvidenceDraft(VAULT, doomed, null, messageId, "u0", "hmac-shared",
                "原话密文", "{}", NOW);
        memory.attachEvidence(shared, auto.rowId());
        memory.attachEvidence(shared, manual.rowId());
        ChatStorageLedger.Snapshot before = ledger.read();

        sessions.delete(doomed);

        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence"), "原话不得以快照名义留存");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item_evidence"));
        assertTrue(memory.findCurrent(VAULT, auto.memoryId()).isEmpty(),
                "自动事实的唯一来源被删 → 同一事务里作为孤儿移除");
        assertTrue(memory.findCurrent(VAULT, manual.memoryId()).isPresent(),
                "人工保护记忆保留内容，只去掉被删来源");
        assertTrue(ledger.reconcile().consistent(), "记忆重算、删行与账本扣减同批提交");
        assertEquals(before.historyBytes() - bytes("被采纳的原话", null, null, null, null),
                ledger.read().historyBytes());
    }

    @Test
    void failedLegacyInsertLeavesNoRowAndNoLedgerChange() throws Exception {
        sessions.insertMessage(sessionId, "user", "先建立基线", null, null);
        ChatStorageLedger.Snapshot before = ledger.read();
        long revisionBefore = revision();

        assertThrows(UncategorizedSQLException.class,
                () -> sessions.insertMessage(999_999L, "user", "挂在不存在的会话上", null, null));

        assertEquals(before, ledger.read(), "消息写失败时账本一分不动");
        assertEquals(revisionBefore, revision(), "失败的事务不得推进版本");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message"));
    }

    @Test
    void listViewStillReadsWhatTheLedgerCounted() throws Exception {
        sessions.insertMessage(sessionId, "user", "问题", "Y2lwaGVy", "{}");
        sessions.insertMessage(sessionId, "assistant", "", "cmVwbHk=", "{\"salt\":\"s\"}");

        assertNotNull(sessions.find(sessionId));
        assertEquals(2, sessions.messages(sessionId).size());
        assertEquals(2L, sessions.list(false).get(0).get("messageCount"));
        assertTrue(ledger.reconcile().consistent());
    }

    // ---- helpers ----

    private Draft draft(String requestId, long session, String userContent, List<AttachmentPayload> attachments) {
        return new Draft(requestId, session, 1, "GLOBAL", 0, "Asia/Shanghai", "gen-test", true, 1L,
                "{}", "{}", userContent, null, null, attachments, NOW);
    }

    private MemoryRepository.NewMemory fact(String family, String origin, boolean manualProtected) {
        return new MemoryRepository.NewMemory(VAULT, "GLOBAL", 0, "PREFERENCE", "predicate-" + family, family,
                "value-" + family, MemoryRepository.STATUS_ACTIVE, "payload", "{}", origin, manualProtected, false,
                null, null, NOW, NOW, NOW);
    }

    private static long bytes(String content, String cipher, String meta, String reasoningCipher, String reasoningMeta) {
        return ChatStorageSizer.storedBytes(content, cipher, meta, reasoningCipher, reasoningMeta);
    }

    private long newSession(String title) {
        jdbc.update("INSERT INTO ai_chat_session(title) VALUES (?)", title);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session", Long.class);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT history_revision FROM ai_chat_session WHERE id = ?",
                Long.class, sessionId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
