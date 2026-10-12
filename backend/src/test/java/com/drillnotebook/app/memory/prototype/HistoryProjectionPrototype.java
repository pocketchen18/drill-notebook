package com.drillnotebook.app.memory.prototype;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * P0a 第⑤组原型（执行方案 §14 P0a、验收 H01/H02/H03）：验证"历史保留与模型输入分别管理"
 * 这条地基——分页/压缩都不删原文，模型侧读取按轮次有界，请求与检查器共用同一个不可变投影。
 *
 * <p>只在测试作用域存在。生产实现属于 P1（仓储分页）、P3（轮次表）、P4（ConversationProjection
 * 与 ChatContextPlan）；这里用最小形状先证明读边界做得到，并给出解密计数器口径。</p>
 */
public final class HistoryProjectionPrototype {

    /** §7.4：历史 UI 每页最多 50 条。 */
    public static final int HISTORY_PAGE_SIZE = 50;
    /** §7.2.2：模型组装一批最多 16 轮。 */
    public static final int MODEL_BATCH_TURNS = 16;
    /** §7.2.2：单请求最多扫描 32 轮。 */
    public static final int MODEL_MAX_TURNS = 32;
    /** fixture 里每轮的固定消息条数（user + assistant）。 */
    public static final int MESSAGES_PER_TURN = 2;
    /** §9.2：尽可能保留最近至少 2 轮，即使区域预算已经吃紧。 */
    public static final int MIN_RETAINED_TURNS = 2;
    /** 演示用的区域预算（字节）；正式实现按 contracts 的估算口径。 */
    public static final long RECENT_TURNS_BUDGET_BYTES = 60_000L;

    private HistoryProjectionPrototype() {
    }

    public record Message(long id, long turnId, String role, String cipher, String meta) {
    }

    public record Turn(long turnId, List<Message> messages) {
        long bytes() {
            long total = 0;
            for (Message message : messages) {
                total += StorageLedgerPrototype.storedBytes(message.cipher(), message.meta());
            }
            return total;
        }
    }

    public record SummaryCheckpoint(long throughTurnId, Set<Long> coveredTurnIds, String summaryCipher) {
    }

    /** 只在测试里使用的读/解密计数器（acceptance §10.1.2 要求真实走分页与 32 轮路径）。 */
    public static final class Counters {
        public long statements;
        public long rowsRead;
        public long decrypted;

        public void reset() {
            statements = 0;
            rowsRead = 0;
            decrypted = 0;
        }
    }

    /** 造 history_limit 内足够的完整轮次：每轮 user + assistant 两条消息。 */
    public static int seedTurns(DataSource dataSource, long sessionId, int turnCount, int cipherChars)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement turn = connection.prepareStatement("""
                    INSERT INTO p0a_turn(request_id, session_id, status, reserved_history_bytes, created_at)
                    VALUES (?, ?, 'COMPLETED', 0, datetime('now'))
                    """);
                 PreparedStatement message = connection.prepareStatement("""
                        INSERT INTO ai_chat_message(id, session_id, role, content, content_cipher, content_meta)
                        VALUES (?, ?, ?, '', ?, ?)
                        """)) {
                String fill = "A".repeat(Math.max(0, cipherChars - 7));
                int messageIndex = 1;
                for (int index = 1; index <= turnCount; index++) {
                    turn.setString(1, String.format("018fseed-0000-7000-8000-%012d", index));
                    turn.setLong(2, sessionId);
                    turn.addBatch();
                    // 演示用"密文"：首字符占位 + 6 位轮次号 + 填充，便于从字段本身还原轮次归属
                    String cipher = "z" + String.format("%06d", index) + fill;
                    for (String role : List.of("user", "assistant")) {
                        message.setInt(1, messageIndex);
                        message.setLong(2, sessionId);
                        message.setString(3, role);
                        message.setString(4, cipher);
                        message.setString(5, "{\"salt\":\"s\",\"iv\":\"i\"}");
                        message.addBatch();
                        messageIndex++;
                    }
                }
                turn.executeBatch();
                message.executeBatch();
                connection.commit();
                return messageIndex - 1;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** 历史 UI 的游标分页：按 message id 倒序取一页，不改写也不删除任何行。 */
    public static List<Message> pageMessages(DataSource dataSource, long sessionId, long beforeId, int pageSize,
            Counters counters) throws SQLException {
        counters.statements++;
        List<Message> page = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.id,
                           CAST(substr(m.content_cipher, 2) AS INTEGER) AS turn_id,
                           m.role, m.content_cipher, m.content_meta
                      FROM ai_chat_message m
                     WHERE m.session_id = ? AND m.id < ?
                     ORDER BY m.id DESC
                     LIMIT ?
                    """)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeId);
            statement.setInt(3, pageSize);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    counters.rowsRead++;
                    page.add(new Message(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4),
                            rows.getString(5)));
                }
            }
        }
        return page;
    }

    /**
     * 模型侧读取：按轮次倒序分批扫描，一批最多 {@link #MODEL_BATCH_TURNS} 轮、总量不超过
     * {@link #MODEL_MAX_TURNS} 轮；只纳入完整轮次（半轮会改变否定语义，§9.2），
     * 并且只解密真正进入投影的轮次。
     */
    public static ScanResult boundedModelScan(DataSource dataSource, long sessionId, Counters counters)
            throws SQLException {
        List<Turn> turns = new ArrayList<>();
        long beforeTurnId = Long.MAX_VALUE;
        while (turns.size() < MODEL_MAX_TURNS) {
            int want = Math.min(MODEL_BATCH_TURNS, MODEL_MAX_TURNS - turns.size());
            counters.statements++;
            Map<Long, List<Message>> grouped = new LinkedHashMap<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                        SELECT m.id,
                               CAST(substr(m.content_cipher, 2) AS INTEGER) AS turn_id,
                               m.role, m.content_cipher, m.content_meta
                          FROM ai_chat_message m
                         WHERE m.session_id = ?
                           AND CAST(substr(m.content_cipher, 2) AS INTEGER) < ?
                         ORDER BY turn_id DESC, m.id ASC
                         LIMIT ?
                        """)) {
                statement.setLong(1, sessionId);
                statement.setLong(2, beforeTurnId);
                // 每轮两条消息；多取两条以便识别被 LIMIT 切到的半轮
                statement.setInt(3, want * MESSAGES_PER_TURN + MESSAGES_PER_TURN);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        counters.rowsRead++;
                        long turnId = rows.getLong(2);
                        grouped.computeIfAbsent(turnId, key -> new ArrayList<>())
                                .add(new Message(rows.getLong(1), turnId, rows.getString(3), rows.getString(4),
                                        rows.getString(5)));
                    }
                }
            }
            if (grouped.isEmpty()) {
                break;
            }
            List<Turn> complete = new ArrayList<>();
            for (Map.Entry<Long, List<Message>> entry : grouped.entrySet()) {
                if (entry.getValue().size() == MESSAGES_PER_TURN && complete.size() < want) {
                    complete.add(new Turn(entry.getKey(), entry.getValue()));
                }
            }
            for (Turn turn : complete) {
                counters.decrypted++;
                decrypt(turn.messages());
                turns.add(turn);
            }
            if (complete.isEmpty()) {
                break;
            }
            // 游标停在"最旧的一个已纳入轮次"，被 LIMIT 多读到但没纳入的完整轮次下一批会重新读到
            beforeTurnId = complete.get(complete.size() - 1).turnId();
            if (complete.size() < want) {
                break;
            }
        }
        return new ScanResult(turns, counters.decrypted, counters.rowsRead);
    }

    public record ScanResult(List<Turn> turns, long decryptCount, long rowsRead) {
    }

    /** 密文里存的是 "轮次号 + 填充"，这里当作解密出可读文本并统计一次。 */
    private static List<String> decrypt(List<Message> messages) {
        List<String> plain = new ArrayList<>();
        for (Message message : messages) {
            plain.add(new String(message.cipher().substring(1).getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
        }
        return plain;
    }

    /**
     * 唯一上下文投影：摘要检查点替代它实际覆盖的轮次，其余从新到旧按预算纳入，装不下就停止
     * （被裁掉的是最早的近期轮次，§9.2 裁剪顺序）；返回的 planId、纳入轮次、被摘要替代轮次、
     * 被裁剪轮次与估算字节都固化在这一份不可变结果里。
     */
    public static ContextPlan project(String planId, List<Turn> scannedDesc, SummaryCheckpoint checkpoint,
            long budgetBytes) {
        Set<Long> covered = checkpoint == null ? Set.of() : checkpoint.coveredTurnIds();
        List<Turn> included = new ArrayList<>();
        List<Long> replacedBySummary = new ArrayList<>();
        List<Long> trimmed = new ArrayList<>();
        long used = checkpoint == null ? 0 : StorageLedgerPrototype.storedBytes(checkpoint.summaryCipher());
        boolean exhausted = false;
        for (Turn turn : scannedDesc) {
            if (covered.contains(turn.turnId())) {
                replacedBySummary.add(turn.turnId());
                continue;
            }
            if (exhausted || (included.size() >= MIN_RETAINED_TURNS && used + turn.bytes() > budgetBytes)) {
                exhausted = true;
                trimmed.add(turn.turnId());
                continue;
            }
            included.add(turn);
            used += turn.bytes();
        }
        List<Long> includedIds = new ArrayList<>();
        for (Turn turn : included) {
            includedIds.add(turn.turnId());
        }
        List<Long> ordered = new ArrayList<>(includedIds);
        java.util.Collections.reverse(ordered);
        List<Long> trimmedAsc = new ArrayList<>(trimmed);
        java.util.Collections.reverse(trimmedAsc);
        return new ContextPlan(planId, ordered, List.copyOf(replacedBySummary), List.copyOf(trimmedAsc), used,
                checkpoint != null, scannedDesc.size());
    }

    public record ContextPlan(String planId, List<Long> includedTurnIds, List<Long> replacedBySummaryTurnIds,
            List<Long> trimmedTurnIds, long estimatedBytes, boolean usesSummary, long scannedTurnCount) {

        /** 发给模型的请求来源标识：由本 plan 派生，不另起一次挑选。 */
        public List<Long> requestSourceIds() {
            return includedTurnIds;
        }

        /** 本轮检查器显示的来源：与请求来自同一个 plan。 */
        public List<Long> auditSourceIds() {
            return includedTurnIds;
        }
    }

    /** 快照全部消息的密文字节，用于证明分页/压缩没有改写或删掉原始历史。 */
    public static Map<Long, String> snapshotCiphers(DataSource dataSource, long sessionId) throws SQLException {
        Map<Long, String> snapshot = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, content_cipher FROM ai_chat_message WHERE session_id = ? ORDER BY id")) {
            statement.setLong(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    snapshot.put(rows.getLong(1), rows.getString(2));
                }
            }
        }
        return snapshot;
    }

    public static long countMessages(DataSource dataSource, long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** 被摘要覆盖的轮次集合：coverage 只标实际来源，不按 through 整段排除（H02/T02）。 */
    public static Set<Long> turnIdsBetween(long fromInclusive, long toInclusive) {
        Set<Long> ids = new LinkedHashSet<>();
        for (long id = fromInclusive; id <= toInclusive; id++) {
            ids.add(id);
        }
        return ids;
    }
}
