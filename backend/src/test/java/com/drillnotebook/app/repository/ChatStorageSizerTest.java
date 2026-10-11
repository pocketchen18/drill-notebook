package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * contracts §2.7 的字节口径：账本数的是 UTF-8 落库长度，不是字符数、不是 Token，也不是物理磁盘占用。
 */
class ChatStorageSizerTest {
    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-sizer-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        // 启动迁移已经建好默认会话，这里复用它，不再另造 id=1
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void countsUtf8BytesAndTreatsNullAsZero() {
        assertEquals(6, ChatStorageSizer.storedBytes("中文"));
        assertEquals(1, ChatStorageSizer.storedBytes("A"));
        assertEquals(0, ChatStorageSizer.storedBytes(new String[]{null}));
        assertEquals(6, ChatStorageSizer.storedBytes("中", null, "ABC"));
        assertEquals(5, ChatStorageSizer.storedBytes(new byte[]{1, 2, 3, 4, 5}, null));
        assertEquals(4, ChatStorageSizer.storedBytes(new byte[]{1, 2}, "{}"));
    }

    @Test
    void sqlMeasureUsesBlobLengthSoChineseAndEmojiAreNotUndercounted() throws Exception {
        // 「中文🍀」落库 10 字节，但 SQLite 的 length(TEXT) 只数出 3 个字符
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content, content_cipher, content_meta,"
                + " reasoning_cipher, reasoning_meta) VALUES (1, ?, 'user', ?, 'cipher', NULL, 'think', NULL)",
                sessionId, "中文🍀");

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(10 + "cipher".length() + "think".length(), ChatStorageSizer.globalHistoryBytes(connection));
        }
    }

    @Test
    void legacyMessagesWithoutTurnStillCountIntoGlobal() throws Exception {
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content) VALUES (1, ?, 'user', '旧明文')", sessionId);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("旧明文".getBytes(StandardCharsets.UTF_8).length, ChatStorageSizer.globalHistoryBytes(connection));
        }
    }

    @Test
    void turnStoredIsOwnMetadataPlusItsMessagesAndIsNotDoubleCounted() throws Exception {
        jdbc.update("INSERT INTO ai_chat_turn(id, request_id, session_id, status, request_meta_cipher,"
                + " request_meta_crypto, context_generation, source_scope_type, source_scope_id, source_timezone,"
                + " runtime_generation, started_at) VALUES (7, 'req-1', ?, 'RUNNING', 'meta1234', '{}', 1,"
                + " 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', '2026-10-11T00:00:00.000Z')", sessionId);
        jdbc.update("INSERT INTO ai_chat_message(id, session_id, role, content, turn_id, turn_role)"
                + " VALUES (11, ?, 'user', '一二三', 7, 'USER')", sessionId);
        jdbc.update("UPDATE ai_chat_turn SET user_message_id = 11 WHERE id = 7");

        try (Connection connection = dataSource.getConnection()) {
            long turnSubtotal = ChatStorageSizer.turnStoredBytes(connection, 7);
            // 自身元数据 8+2=10，正文 9 字节
            assertEquals(10 + "一二三".getBytes(StandardCharsets.UTF_8).length, turnSubtotal);
            // 全局只按各行求和，小计不会被再加一次
            assertEquals(turnSubtotal, ChatStorageSizer.globalHistoryBytes(connection));
        }
    }

    @Test
    void attachmentBytesUseStoredBlobLength() throws Exception {
        jdbc.update("INSERT INTO ai_chat_turn(id, request_id, session_id, status, request_meta_cipher,"
                + " request_meta_crypto, context_generation, source_scope_type, source_scope_id, source_timezone,"
                + " runtime_generation, started_at) VALUES (1, 'req-att', ?, 'RUNNING', 'm', '{}', 1,"
                + " 'GLOBAL', 0, 'Asia/Shanghai', 'gen-1', '2026-10-11T00:00:00.000Z')", sessionId);
        jdbc.update("INSERT INTO ai_chat_attachment(id, turn_id, block_id, kind, payload_cipher, crypto_meta,"
                + " byte_length, created_at) VALUES ('a-1', 1, 'a0', 'IMAGE', ?, '{}', 5, ?)",
                new byte[]{1, 2, 3}, "2026-10-11T00:00:00.000Z");

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(5, ChatStorageSizer.globalAttachmentBytes(connection));
        }
        assertEquals(5L, new ChatTurnRepository.AttachmentPayload(
                "a-1", "a0", "IMAGE", new byte[]{1, 2, 3}, "{}").storedBytes());
    }

    @Test
    void base64FormulaEqualsRealEncoder() {
        for (long size : new long[]{0, 1, 2, 3, 15, 16, 17, 1024, 4095, 65536}) {
            String encoded = Base64.getEncoder().encodeToString(new byte[(int) size + 16]);
            assertEquals(encoded.length(), ChatStorageSizer.base64CipherBytes(size),
                    "Base64(明文 + GCM tag) 公式在 " + size + " 字节处与实际不一致");
        }
    }

    @Test
    void worstCaseReplyStaysInsideTwoMibReservation() {
        long worst = ChatStorageSizer.worstCaseReplyBytes();
        assertTrue(worst < 1_433_000L, "最坏封装必须低于契约给出的 1,433,000 字节，实际 " + worst);
        assertTrue(worst < ChatStorageSizer.REPLY_RESERVATION_BYTES, "最坏封装必须低于 2 MiB 预留，否则预留不再成立");
    }

    @Test
    void captureLimitStopsAtCodePointBoundaryWithoutSplittingUtf8() {
        long cap = ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES;
        String base = "中".repeat((int) (cap / 3));           // 3 字节码点填到底 → 1,048,575 字节
        assertEquals(cap - 1, base.getBytes(StandardCharsets.UTF_8).length);

        // 余下 1 字节：再来一个 3 字节汉字放不下，必须整体不进，而不是切半个 UTF-8 序列
        assertEquals(base, ChatStorageSizer.appendWithinCaptureLimit(base, "中"));
        // 换成 1 字节正好填满上限，填满之后连 ASCII 也不再加
        String full = ChatStorageSizer.appendWithinCaptureLimit(base, "A");
        assertEquals(cap, full.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(full, ChatStorageSizer.appendWithinCaptureLimit(full, "B"));
        assertEquals(full, ChatStorageSizer.appendWithinCaptureLimit(full, "🍀"));
        assertEquals(full, new String(full.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                "截断结果必须仍是完整码点序列");
    }

    @Test
    void captureLimitKeepsWholeCodePointsThatStillFit() {
        String prefix = "a".repeat((int) ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES - 12);
        String kept = ChatStorageSizer.appendWithinCaptureLimit(prefix, "🍀🍀🍀🍀🍀");
        assertEquals(ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES, kept.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(3, kept.codePoints().filter(point -> point == 0x1F340).count(), "12 字节余量只放得下 3 个 emoji");
    }

    @Test
    void reserveRefusesHistoryAndAttachmentSeparately() throws Exception {
        // 正文侧只留"999 字节 + 一份 2 MiB 回复预留"，附件留 1000 字节
        jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = history_bytes + reserved_history_bytes + ? + ?,"
                + " attachment_limit_bytes = attachment_bytes + 1000", ChatStorageSizer.REPLY_RESERVATION_BYTES, 999);
        ChatStorageLedger ledger = new ChatStorageLedger(dataSource, new ImmediateTransaction(dataSource));
        try (Connection connection = dataSource.getConnection()) {
            assertSame(ChatStorageLedger.Fit.HISTORY_STORAGE_FULL, ledger.reserve(connection, 1_000, 0),
                    "差一个字节也不能放行");
            assertSame(ChatStorageLedger.Fit.ACCEPTED, ledger.reserve(connection, 999, 0));

            // 正文宽裕、附件不足时给出附件码，两种额度不互相掩盖
            jdbc.update("UPDATE ai_chat_storage SET history_limit_bytes = 268435456,"
                    + " attachment_limit_bytes = attachment_bytes + 10");
            assertSame(ChatStorageLedger.Fit.ATTACHMENT_STORAGE_FULL, ledger.reserve(connection, 0, 11));
            assertSame(ChatStorageLedger.Fit.ACCEPTED, ledger.reserve(connection, 0, 10));
        }
    }
}
