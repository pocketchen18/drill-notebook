package com.drillnotebook.app.repository;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 历史容量计数（contracts §2.7）：账本记的是**落库字节**，不是 Token，也不是物理磁盘占用。
 *
 * <p>口径固定为 UTF-8 落库长度，SQL 侧一律用 {@code length(CAST(COALESCE(col,'') AS BLOB))}——
 * SQLite 的 {@code length(TEXT)} 数的是字符，中文/emoji 会少算。字段、状态、索引与页碎片不在这个
 * 逻辑额度里，因此不能宣称物理 DB 有同样的硬上限。</p>
 *
 * <p>所有聚合查询都接 {@link Connection}，调用方必须把它们和写入放在同一个事务里：
 * 计数与写入不同事务，账本就会与实测脱节（P0a 第④组的显式结论）。</p>
 */
public final class ChatStorageSizer {
    /** 开始轮次为回复预留的 2 MiB（contracts §2.7）。 */
    public static final long REPLY_RESERVATION_BYTES = 2_097_152L;
    /** 正文 + reasoning 的原始 UTF-8 捕获上限（两者合计）。 */
    public static final long OUTPUT_CAPTURE_RAW_BYTES = 1_048_576L;
    /** result_meta 序列化明文上限。 */
    public static final long RESULT_META_PLAIN_MAX_BYTES = 16_384L;
    /** 每份 crypto_meta 上限。 */
    public static final long CRYPTO_META_MAX_BYTES = 4_096L;
    /** AES-GCM 认证标签长度。 */
    public static final long GCM_TAG_BYTES = 16L;

    /** 消息侧计入历史容量的五列（NULL 按 0）。 */
    public static final String MESSAGE_BYTES_SQL =
            "length(CAST(COALESCE(content, '') AS BLOB))"
                    + " + length(CAST(COALESCE(content_cipher, '') AS BLOB))"
                    + " + length(CAST(COALESCE(content_meta, '') AS BLOB))"
                    + " + length(CAST(COALESCE(reasoning_cipher, '') AS BLOB))"
                    + " + length(CAST(COALESCE(reasoning_meta, '') AS BLOB))";

    /** 轮次侧计入历史容量的四列加密元数据（NULL 按 0）。 */
    public static final String TURN_META_BYTES_SQL =
            "length(CAST(COALESCE(request_meta_cipher, '') AS BLOB))"
                    + " + length(CAST(COALESCE(request_meta_crypto, '') AS BLOB))"
                    + " + length(CAST(COALESCE(result_meta_cipher, '') AS BLOB))"
                    + " + length(CAST(COALESCE(result_meta_crypto, '') AS BLOB))";

    private ChatStorageSizer() {
    }

    /** 若干落库列的 UTF-8 字节合计，NULL 记 0（用于写入前先算准增量）。 */
    public static long storedBytes(String... columns) {
        long total = 0;
        for (String column : columns) {
            total += column == null ? 0 : column.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    /** 密文 BLOB 与其 crypto_meta 的合计（附件按 byte_length 同口径）。 */
    public static long storedBytes(byte[] payload, String cryptoMeta) {
        long length = payload == null ? 0 : payload.length;
        return length + (cryptoMeta == null ? 0 : cryptoMeta.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 全局历史字节：全部消息的五列 + 全部轮次的四列元数据。
     * 没有轮次的 legacy 消息照常计入全局（§2.7）。
     */
    public static long globalHistoryBytes(Connection connection) throws SQLException {
        String sql = "SELECT"
                + " (SELECT COALESCE(SUM(" + MESSAGE_BYTES_SQL + "), 0) FROM ai_chat_message)"
                + " + (SELECT COALESCE(SUM(" + TURN_META_BYTES_SQL + "), 0) FROM ai_chat_turn)";
        return singleScalar(connection, sql);
    }

    /** 全局附件字节。 */
    public static long globalAttachmentBytes(Connection connection) throws SQLException {
        return singleScalar(connection, "SELECT COALESCE(SUM(byte_length), 0) FROM ai_chat_attachment");
    }

    /**
     * 某会话的历史字节：它的消息五列 + 它的轮次四列元数据。
     * 轮次行里的 {@code stored_history_bytes} 只是小计，不参与这里的求和，因此不会重复计一次消息。
     */
    public static long sessionHistoryBytes(Connection connection, long sessionId) throws SQLException {
        return scalar(connection, "SELECT COALESCE(SUM(" + MESSAGE_BYTES_SQL + "), 0) FROM ai_chat_message"
                + " WHERE session_id = ?", sessionId)
                + scalar(connection, "SELECT COALESCE(SUM(" + TURN_META_BYTES_SQL + "), 0) FROM ai_chat_turn"
                + " WHERE session_id = ?", sessionId);
    }

    /** 某会话的附件字节（经轮次归属）。 */
    public static long sessionAttachmentBytes(Connection connection, long sessionId) throws SQLException {
        return scalar(connection, "SELECT COALESCE(SUM(a.byte_length), 0) FROM ai_chat_attachment a"
                + " JOIN ai_chat_turn t ON t.id = a.turn_id WHERE t.session_id = ?", sessionId);
    }

    /** 某会话仍未结算的回复预留总和（删除或恢复时一并释放）。 */
    public static long liveReservationBytes(Connection connection, long sessionId) throws SQLException {
        return scalar(connection, "SELECT COALESCE(SUM(reserved_history_bytes), 0) FROM ai_chat_turn"
                + " WHERE session_id = ? AND status = 'RUNNING'", sessionId);
    }

    /**
     * 单轮自身的存量小计：它自己的四列元数据 + 它关联的 user/assistant 消息五列。
     * 这个小计不再重复加到全局，只用于结算时算 newStored − oldStored。
     *
     * <p>关联消息按 {@code user_message_id}/{@code assistant_message_id} 走主键查找，
     * 与 {@code ai_chat_message.turn_id} 反向指针由同一事务写入（有用例保证两者一致）。</p>
     */
    public static long turnStoredBytes(Connection connection, long turnId) throws SQLException {
        try (PreparedStatement turn = connection.prepareStatement(
                "SELECT " + TURN_META_BYTES_SQL + ", user_message_id, assistant_message_id"
                        + " FROM ai_chat_turn WHERE id = ?")) {
            turn.setLong(1, turnId);
            try (ResultSet result = turn.executeQuery()) {
                if (!result.next()) throw new SQLException("轮次不存在: " + turnId);
                long bytes = result.getLong(1);
                long userMessageId = result.getLong(2);
                long assistantMessageId = result.getLong(3);
                return bytes + messageBytesById(connection, userMessageId) + messageBytesById(connection, assistantMessageId);
            }
        }
    }

    private static long messageBytesById(Connection connection, long messageId) throws SQLException {
        if (messageId <= 0) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + MESSAGE_BYTES_SQL + " FROM ai_chat_message WHERE id = ?")) {
            statement.setLong(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    /** Base64 编码后的密文长度：4*ceil((明文 + GCM tag)/3)。 */
    public static long base64CipherBytes(long plainBytes) {
        long withTag = plainBytes + GCM_TAG_BYTES;
        return 4L * ((withTag + 2L) / 3L);
    }

    /** 一份落库值的封装上界：Base64(明文 + tag) + 该行的 crypto_meta 上限。 */
    public static long encapsulatedBytes(long plainBytes) {
        return base64CipherBytes(plainBytes) + CRYPTO_META_MAX_BYTES;
    }

    /** 一份落库值的封装上界（crypto_meta 已知时按实际长度计）。 */
    public static long encapsulatedBytes(long plainBytes, String actualCryptoMeta) {
        return base64CipherBytes(plainBytes) + (actualCryptoMeta == null
                ? 0 : actualCryptoMeta.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 回复预留的可验证上界（§2.7）：正文与 reasoning 合计 1 MiB 原文按最坏分配各自封装，
     * 再加 result_meta 与三份 crypto_meta。必须严格低于 {@link #REPLY_RESERVATION_BYTES}，
     * 否则预留不再成立，要停下来改预留而不是临时无界放大。
     */
    public static long worstCaseReplyBytes() {
        long half = OUTPUT_CAPTURE_RAW_BYTES / 2;
        return encapsulatedBytes(half) + encapsulatedBytes(OUTPUT_CAPTURE_RAW_BYTES - half)
                + encapsulatedBytes(RESULT_META_PLAIN_MAX_BYTES);
    }

    private static long singleScalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("容量统计没有返回行: " + sql);
            return result.getLong(1);
        }
    }

    private static long scalar(Connection connection, String sql, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("容量统计没有返回行: " + sql);
                return result.getLong(1);
            }
        }
    }
}
