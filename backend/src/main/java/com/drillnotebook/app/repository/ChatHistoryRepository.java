package com.drillnotebook.app.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 聊天历史的只读查询（执行方案 §7.2.2 尾段、§9.4，contracts §3 的 history 接口）。
 *
 * <p>两条纪律：模型上下文按 turn/message ID <b>倒序分页</b>读取，一批最多 16 轮、单请求最多扫 32 轮，
 * 不做"全量读取再过滤"；历史展示页与上下文投影用不同的行视图，展示过的内容不自动取得注入资格（§4.5）。</p>
 *
 * <p>本类一律不解密：正文与元数据列原样交回，加解密在服务层。</p>
 */
@Repository
public class ChatHistoryRepository {

    /** 一轮的历史视图：成对的用户/助手消息列，附件只给身份（字节与密文要另外按块取）。 */
    public record TurnHistory(long turnId, String requestId, String status, long contextGeneration,
                              String contentDeletedAt, String startedAt, String finishedAt,
                              Long userMessageId, String userContent, String userContentCipher, String userContentMeta,
                              Long assistantMessageId, String assistantContent, String assistantContentCipher,
                              String assistantContentMeta) {
    }

    /** 附件身份（不含密文）：投影据此判断"哪几轮有可重放的图片"。 */
    public record AttachmentStub(long turnId, String attachmentId, String blockId, String kind, long byteLength) {
    }

    /** 迁移前的旧消息：没有轮次归属，只在初始 generation 允许进有界近期上下文（§2.2）。 */
    public record LegacyMessage(long messageId, String role, String content, String contentCipher,
                                String contentMeta, String createdAt) {
    }

    /** 历史展示页的一行：带轮次状态与墓碑标记，让失败/legacy 都能被如实标注。 */
    public record HistoryRow(long messageId, String role, String content, String contentCipher, String contentMeta,
                             String reasoningCipher, String reasoningMeta, String createdAt, Long turnId,
                             String requestId, String turnRole, String turnStatus, boolean contentDeleted,
                             boolean legacy, String resultMetaCipher, String resultMetaCrypto) {
    }

    private static final String TURN_SELECT = "SELECT t.id, t.request_id, t.status, t.context_generation,"
            + " t.content_deleted_at, t.started_at, t.finished_at,"
            + " u.id AS u_id, u.content AS u_content, u.content_cipher AS u_cipher, u.content_meta AS u_meta,"
            + " a.id AS a_id, a.content AS a_content, a.content_cipher AS a_cipher, a.content_meta AS a_meta";
    /** 轮次与成对消息的 FROM：拆出来是因为维护扫描要多取两列身份，不能把列名拼到 FROM 之后。 */
    private static final String TURN_FROM = " FROM ai_chat_turn t"
            + " LEFT JOIN ai_chat_message u ON u.id = t.user_message_id"
            + " LEFT JOIN ai_chat_message a ON a.id = t.assistant_message_id";
    private static final String TURN_COLUMNS = TURN_SELECT + TURN_FROM;
    /** 维护扫描额外看的列：{@code source_revision} 是批次身份的一部分，{@code memory_eligible} 决定能否当来源，
     *  {@code request_meta} 两列给出 §4.3 的有序来源块（quote 只能在块内匹配），用户消息时刻是证据的源时间。 */
    private static final String MAINTENANCE_SELECT = TURN_SELECT
            + ", t.source_revision, t.memory_eligible, t.request_meta_cipher, t.request_meta_crypto"
            + ", u.created_at AS u_created_at" + TURN_FROM;

    private final DataSource dataSource;

    public ChatHistoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 倒序取 {@code beforeTurnId} 之前的一批轮次（含非终态与墓碑行，由投影按规则过滤）。 */
    public List<TurnHistory> turnBatch(long sessionId, long beforeTurnId, int limit) throws SQLException {
        String sql = TURN_COLUMNS + " WHERE t.session_id = ? AND t.id < ? ORDER BY t.id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeTurnId);
            statement.setInt(3, limit);
            List<TurnHistory> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new TurnHistory(result.getLong("id"), result.getString("request_id"),
                            result.getString("status"), result.getLong("context_generation"),
                            result.getString("content_deleted_at"), result.getString("started_at"),
                            result.getString("finished_at"), nullableLong(result, "u_id"),
                            result.getString("u_content"), result.getString("u_cipher"),
                            result.getString("u_meta"), nullableLong(result, "a_id"),
                            result.getString("a_content"), result.getString("a_cipher"),
                            result.getString("a_meta")));
                }
            }
            return rows;
        }
    }

    /**
     * 维护扫描的一行：轮次身份、来源版本、正文列与请求元数据列（都不解密）。
     * 与 {@link TurnHistory} 分开，是因为后台装载必须额外看到 {@code source_revision}
     * 与 {@code memory_eligible}——前者是批次身份的一部分，后者决定这一轮能不能当来源；
     * {@code request_meta} 里才有 §4.3 的有序来源块，提取侧的 quote 必须落在某个 {@code USER_AUTHORED} 块内。
     */
    public record MaintenanceRow(long turnId, String requestId, String status, long contextGeneration,
                                 long sourceRevision, boolean memoryEligible, String contentDeletedAt,
                                 String startedAt, String finishedAt, Long userMessageId, String userContent,
                                 String userContentCipher, String userContentMeta, Long assistantMessageId,
                                 String assistantContent, String assistantContentCipher, String assistantContentMeta,
                                 String requestMetaCipher, String requestMetaCrypto, String userCreatedAt) {
    }

    /** 待处理边界：区间内合格完整轮次的数量。 */
    public record MaintenanceBacklog(int eligibleTurns) {
    }

    /**
     * 按 ID <b>升序</b>取 {@code (afterTurnId, throughTurnId]} 区间的一批行（§2.5 的有界正序扫描）。
     * 与投影的倒序读取不同：后台水位是从旧往前推的，必须按正序取，并且把非终态、跨代次、
     * 墓碑行也一并交回，由装载器如实分类计数，而不是在 SQL 里悄悄过滤掉。
     */
    public List<MaintenanceRow> maintenanceRange(long sessionId, long afterTurnId, long throughTurnId, int limit)
            throws SQLException {
        String sql = MAINTENANCE_SELECT + " WHERE t.session_id = ? AND t.id > ? AND t.id <= ?"
                + " ORDER BY t.id ASC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, afterTurnId);
            statement.setLong(3, throughTurnId);
            statement.setInt(4, Math.max(1, limit));
            List<MaintenanceRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapMaintenanceRow(result));
                }
            }
            return rows;
        }
    }

    /** 该会话最早的 RUNNING 轮次 ID；没有则 0。水位永远停在它之前（§2.5）。 */
    public long firstRunningTurnId(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COALESCE(MIN(id), 0) FROM ai_chat_turn WHERE session_id = ? AND status = 'RUNNING'")) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    /** 该会话已有的最大轮次 ID；没有轮次时为 0（待处理边界的右端）。 */
    public long maxTurnId(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COALESCE(MAX(id), 0) FROM ai_chat_turn WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    /**
     * 区间内的合格来源统计：COMPLETED、可学习、同代次、未清理、且停在 RUNNING 之前。
     * 不含正文，因此锁定状态下也能算——触发判断只需要数量（§8.1）。
     */
    public MaintenanceBacklog maintenanceBacklog(long sessionId, long afterTurnId, long throughTurnId,
                                                 long contextGeneration, long historyFloor) throws SQLException {
        String sql = "SELECT COUNT(*) AS turns FROM ai_chat_turn"
                + " WHERE session_id = ? AND id > ? AND id <= ? AND status = 'COMPLETED'"
                + " AND memory_eligible = 1 AND context_generation = ? AND content_deleted_at IS NULL"
                + " AND id > ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, afterTurnId);
            statement.setLong(3, throughTurnId);
            statement.setLong(4, contextGeneration);
            statement.setLong(5, historyFloor);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new MaintenanceBacklog(0);
                return new MaintenanceBacklog(result.getInt("turns"));
            }
        }
    }

    /**
     * 摘要源的右边界：合格完整轮次里排除最近 {@code reservedRecent} 条之后，还能整理到的最大轮次 ID。
     * 返回 0 表示剩下的不够摘要（§8.4「至少最近 2 个完整轮次始终排除在摘要源之外」）。
     */
    public long summarySourceBound(long sessionId, long afterTurnId, long throughTurnId, long contextGeneration,
                                   long historyFloor, int reservedRecent) throws SQLException {
        String sql = "SELECT COALESCE(MIN(x.id), 0) AS boundary FROM (SELECT t.id FROM ai_chat_turn t"
                + " WHERE t.session_id = ? AND t.id > ? AND t.id <= ? AND t.status = 'COMPLETED'"
                + " AND t.memory_eligible = 1 AND t.context_generation = ? AND t.content_deleted_at IS NULL"
                + " AND t.id > ? ORDER BY t.id DESC LIMIT ?) x";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, afterTurnId);
            statement.setLong(3, throughTurnId);
            statement.setLong(4, contextGeneration);
            statement.setLong(5, historyFloor);
            statement.setInt(6, Math.max(1, reservedRecent));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong("boundary") == 0) return 0L;
                return result.getLong("boundary") - 1;
            }
        }
    }

    /**
     * 区间内前 {@code limit} 行的轮次 ID（升序，只读 ID 不读正文）。
     * 队列据此把待处理范围切成有界长度，不必为了算范围而取出整批密文。
     */
    public List<Long> maintenanceIds(long sessionId, long afterTurnId, long throughTurnId, int limit)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM ai_chat_turn WHERE session_id = ? AND id > ? AND id <= ?"
                             + " ORDER BY id ASC LIMIT ?")) {
            statement.setLong(1, sessionId);
            statement.setLong(2, afterTurnId);
            statement.setLong(3, throughTurnId);
            statement.setInt(4, Math.max(1, limit));
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
            return ids;
        }
    }

    /** 按 ID 集合取回轮次身份（提交事务里的来源复验用，§7「verify ... source revision」）。 */
    public List<MaintenanceRow> maintenanceByIds(long sessionId, List<Long> turnIds) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return maintenanceRows(connection, sessionId, turnIds);
        }
    }

    /**
     * 连接内原语：维护任务在<b>同一笔写事务里</b>复验来源身份必须用这条，
     * 另开连接读会看到与本次事务不一致的已提交快照。
     */
    public static List<MaintenanceRow> maintenanceRows(Connection connection, long sessionId, List<Long> turnIds)
            throws SQLException {
        if (turnIds == null || turnIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(MAINTENANCE_SELECT
                + " WHERE t.session_id = ? AND t.id IN (");
        for (int index = 0; index < turnIds.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY t.id ASC");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setLong(1, sessionId);
            for (int index = 0; index < turnIds.size(); index++) {
                statement.setLong(index + 2, turnIds.get(index));
            }
            List<MaintenanceRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapMaintenanceRow(result));
                }
            }
            return rows;
        }
    }

    /** 两处维护扫描共用同一列集合，行映射只写一遍。 */
    private static MaintenanceRow mapMaintenanceRow(ResultSet result) throws SQLException {
        return new MaintenanceRow(result.getLong("id"), result.getString("request_id"),
                result.getString("status"), result.getLong("context_generation"),
                result.getLong("source_revision"), result.getInt("memory_eligible") == 1,
                result.getString("content_deleted_at"), result.getString("started_at"),
                result.getString("finished_at"), nullableLong(result, "u_id"),
                result.getString("u_content"), result.getString("u_cipher"), result.getString("u_meta"),
                nullableLong(result, "a_id"), result.getString("a_content"),
                result.getString("a_cipher"), result.getString("a_meta"),
                result.getString("request_meta_cipher"), result.getString("request_meta_crypto"),
                result.getString("u_created_at"));
    }

    /** 一批轮次的附件身份：一次查完，避免每轮一条 SQL。 */
    public List<AttachmentStub> attachmentStubs(List<Long> turnIds) throws SQLException {
        if (turnIds == null || turnIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT turn_id, id, block_id, kind, byte_length"
                + " FROM ai_chat_attachment WHERE turn_id IN (");
        for (int index = 0; index < turnIds.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY turn_id, rowid");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < turnIds.size(); index++) {
                statement.setLong(index + 1, turnIds.get(index));
            }
            List<AttachmentStub> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new AttachmentStub(result.getLong("turn_id"), result.getString("id"),
                            result.getString("block_id"), result.getString("kind"),
                            result.getLong("byte_length")));
                }
            }
            return rows;
        }
    }

    /**
     * 一批轮次的 {@code result_meta} 密文列（不解密）。
     *
     * <p>摘要装载要用它：「本轮实际提供过哪些引用」只存在于落库的上下文审计里（contracts §6.1
     * 的 {@code allowedReferences}），而审计随 result_meta 加密（§4.5）。单独一条查询而不是加进
     * {@link MaintenanceRow}，是因为提取批次根本不需要这两列，而它们可能各带 16 KiB。</p>
     */
    public record AuditMetaRow(long turnId, String resultMetaCipher, String resultMetaCrypto) {
    }

    public List<AuditMetaRow> auditMetaByTurnIds(long sessionId, List<Long> turnIds) throws SQLException {
        if (turnIds == null || turnIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT id, result_meta_cipher, result_meta_crypto"
                + " FROM ai_chat_turn WHERE session_id = ? AND id IN (");
        for (int index = 0; index < turnIds.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY id ASC");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setLong(1, sessionId);
            for (int index = 0; index < turnIds.size(); index++) {
                statement.setLong(index + 2, turnIds.get(index));
            }
            List<AuditMetaRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new AuditMetaRow(result.getLong("id"), result.getString("result_meta_cipher"),
                            result.getString("result_meta_crypto")));
                }
            }
            return rows;
        }
    }

    /**
     * 倒序取一批没有轮次归属的旧消息（迁移前遗留）。它们全部早于任何轮次行，
     * 所以只在轮次窗口没扫满时补齐，并且只限初始 generation。
     */
    public List<LegacyMessage> legacyBatch(long sessionId, long beforeMessageId, int limit) throws SQLException {
        String sql = "SELECT id, role, content, content_cipher, content_meta, created_at FROM ai_chat_message"
                + " WHERE session_id = ? AND turn_id IS NULL AND id < ? ORDER BY id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeMessageId);
            statement.setInt(3, limit);
            List<LegacyMessage> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new LegacyMessage(result.getLong("id"), result.getString("role"),
                            result.getString("content"), result.getString("content_cipher"),
                            result.getString("content_meta"), result.getString("created_at")));
                }
            }
            return rows;
        }
    }

    /** 会话里最大的消息 ID：legacy 反向分页的起点（没有消息时为 0）。 */
    public long maxMessageId(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COALESCE(MAX(id), 0) FROM ai_chat_message WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    /** 该会话是否还有更早的、本次没扫到的轮次（审计的 hasOlderUnscanned）。 */
    public boolean hasOlderTurn(long sessionId, long beforeTurnId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM ai_chat_turn WHERE session_id = ? AND id < ? LIMIT 1")) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeTurnId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /** 更早的轮次计数：只在有有效摘要、能给出准确"范围外"数字时才花这次查询。 */
    public int countTurnsBefore(long sessionId, long beforeTurnId) throws SQLException {
        return count("SELECT COUNT(*) FROM ai_chat_turn WHERE session_id = ? AND id < ?", sessionId, beforeTurnId);
    }

    /** 会话里是否存在迁移前遗留的无轮次消息。 */
    public boolean hasLegacyMessages(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM ai_chat_message WHERE session_id = ? AND turn_id IS NULL LIMIT 1")) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * {@code throughTurnId} 之内有多少非 COMPLETED 的轮次：这些是检查点声称"已检查"却没真被总结的缺口，
     * 用于 hasGaps（§2.5「水位表示已检查至此，不表示都整理成功」）。
     */
    public long countNonCompletedThrough(long sessionId, long throughTurnId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_chat_turn WHERE session_id = ? AND id <= ? AND status <> 'COMPLETED'")) {
            statement.setLong(1, sessionId);
            statement.setLong(2, throughTurnId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    /** 页面属于哪本笔记本（v2 的 pageContext 作用域校验，contracts §4.1）；页面不存在时为空。 */
    public Optional<Long> notebookIdOfPage(long pageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT notebook_id FROM note_page WHERE id = ?")) {
            statement.setLong(1, pageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(result.getLong("notebook_id"));
            }
        }
    }

    /** 页面正文（TipTap JSON 原文）：笔记本体不加密，检查器据此给有界文本片段。 */
    public Optional<String> pageContent(long pageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT content FROM note_page WHERE id = ?")) {
            statement.setLong(1, pageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.ofNullable(result.getString("content"));
            }
        }
    }

    /** 页面正文的内容版本（NoteNormalizer 维护的 content_hash）；检查器用它核验来源没被改版。 */
    public Optional<String> pageContentHash(long pageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT content_hash FROM note_page WHERE id = ?")) {
            statement.setLong(1, pageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.ofNullable(result.getString("content_hash"));
            }
        }
    }

    private int count(String sql, long sessionId, long parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, parameter);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /**
     * 当前 generation 内、本轮之前最新的终态轮次（§4.5 的 resumeState）：
     * 只看这一条，COMPLETED 由调用方折成 null，不再往前翻更早的失败。
     */
    public Optional<TurnHistory> latestTerminalBefore(long sessionId, long beforeTurnId, long contextGeneration)
            throws SQLException {
        String sql = TURN_COLUMNS + " WHERE t.session_id = ? AND t.id < ? AND t.context_generation = ?"
                + " AND t.status <> 'RUNNING' ORDER BY t.id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeTurnId);
            statement.setLong(3, contextGeneration);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new TurnHistory(result.getLong("id"), result.getString("request_id"),
                        result.getString("status"), result.getLong("context_generation"),
                        result.getString("content_deleted_at"), result.getString("started_at"),
                        result.getString("finished_at"), nullableLong(result, "u_id"),
                        result.getString("u_content"), result.getString("u_cipher"), result.getString("u_meta"),
                        nullableLong(result, "a_id"), result.getString("a_content"), result.getString("a_cipher"),
                        result.getString("a_meta")));
            }
        }
    }

    /**
     * 只读历史分页（contracts §3：{@code GET /sessions/{id}/history}）：
     * 按稳定 message ID seek，before/after 二选一，新增末尾消息不会让更早的页丢失。
     * 返回按 ID 升序的页面行，{@code afterMessageId} 方向也一样是升序。
     */
    public List<HistoryRow> page(long sessionId, Long beforeMessageId, Long afterMessageId, int limit)
            throws SQLException {
        boolean backwards = afterMessageId == null;
        long cursor = backwards ? (beforeMessageId == null ? Long.MAX_VALUE : beforeMessageId) : afterMessageId;
        String sql = "SELECT m.id, m.role, m.content, m.content_cipher, m.content_meta, m.reasoning_cipher,"
                + " m.reasoning_meta, m.created_at, m.turn_id, m.turn_role,"
                + " t.request_id AS turn_request_id, t.status AS turn_status,"
                + " t.content_deleted_at AS turn_deleted, t.result_meta_cipher, t.result_meta_crypto"
                + " FROM ai_chat_message m LEFT JOIN ai_chat_turn t ON t.id = m.turn_id"
                + " WHERE m.session_id = ? AND m.id " + (backwards ? "< ?" : "> ?")
                + " ORDER BY m.id " + (backwards ? "DESC" : "ASC") + " LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, cursor);
            statement.setInt(3, limit);
            List<HistoryRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new HistoryRow(result.getLong("id"), result.getString("role"),
                            result.getString("content"), result.getString("content_cipher"),
                            result.getString("content_meta"), result.getString("reasoning_cipher"),
                            result.getString("reasoning_meta"), result.getString("created_at"),
                            nullableLong(result, "turn_id"), result.getString("turn_request_id"),
                            result.getString("turn_role"),
                            result.getString("turn_status"), result.getString("turn_deleted") != null,
                            result.getObject("turn_id") == null, result.getString("result_meta_cipher"),
                            result.getString("result_meta_crypto")));
                }
            }
            if (backwards) java.util.Collections.reverse(rows);
            return rows;
        }
    }

    /**
     * 清理范围里的一条消息（含 legacy 行）。字节按 {@link ChatStorageSizer#MESSAGE_BYTES_SQL} 现算，
     * 轮次归属留在行上：执行前要逐条核它还在不在原位，被改写就不许继续删。
     *
     * <p>{@code role} 是给 legacy 配对用的：迁移前的展示行没有 {@code turn_id}，
     * 契约（§3「v8临时展示轮次必须采用同一配对规则」）要求它按"一问一答"整组处理，
     * 没有角色就只能按行删，会把一对劈成半轮。</p>
     */
    public record CleanupMessage(long messageId, String role, long bytes, Long turnId) {
    }

    /**
     * 显式清理的预览范围（contracts §3 的 {@code POST /history/cleanup/preview}）。
     *
     * <p>选取口径三条：{@code t.session_id = ?}、行没被清过（{@code m.content IS NULL
     * AND m.content_cipher IS NOT NULL} 之外的老实文行不算可清）、以及 {@code m.id <= 边界}。
     * <b>轮次归属不是筛选条件</b>：一轮的配对消息由 {@code turn_id} 反指回轮次，若按归属筛，
     * {@link #cleanupTurns} 用子查询数到的行数会与这里的清单不一致，预览与执行的字节口径就会分叉。
     * 凑不满一条完整问答的行（比如只删了用户行）由服务层按 {@code requestId} 归组后判"本轮不完整"，
     * 界面据此说明，而不是悄悄少删一轮。</p>
     */
    public List<CleanupMessage> cleanupMessages(long sessionId, long beforeOrAtMessageId) throws SQLException {
        String sql = "SELECT id, role, " + ChatStorageSizer.MESSAGE_BYTES_SQL + " AS bytes, turn_id FROM ai_chat_message"
                + " WHERE session_id = ? AND id <= ? AND NOT (content IS NULL AND content_cipher IS NULL)"
                + " ORDER BY id DESC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeOrAtMessageId);
            List<CleanupMessage> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new CleanupMessage(result.getLong("id"), result.getString("role"),
                            result.getLong("bytes"), nullableLong(result, "turn_id")));
                }
            }
            return rows;
        }
    }

    /**
     * 边界必须是本会话里真实存在的一条消息。跨会话或已经删掉的 ID 会让范围算成空集，
     * 那样用户点"清理"时删掉的是他以为的那一批之外的东西，所以预览门口就拦住。
     */
    public boolean messageBelongsTo(long sessionId, long messageId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ? AND id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) > 0;
            }
        }
    }

    /**
     * 边界之后的<b>第一条</b> legacy 展示行。它只为了一件事：判"这一对有没有被边界劈开"。
     *
     * <p>契约 §3 要求 v8 的展示轮次按同一配对规则整组处理。若边界正好落在一问一答之间，
     * 只看边界内的行会以为那条问句是"孤儿"而删掉它，答案却留在库里——那正是 execution §7.4.5
     * 禁的「误删半轮」。取一行、不取全表，也就没有"全量读取再过滤"。</p>
     */
    public CleanupMessage firstLegacyAfter(long sessionId, long afterMessageId) throws SQLException {
        String sql = "SELECT id, role, " + ChatStorageSizer.MESSAGE_BYTES_SQL + " AS bytes, turn_id"
                + " FROM ai_chat_message"
                + " WHERE session_id = ? AND id > ? AND turn_id IS NULL"
                + " AND NOT (content IS NULL AND content_cipher IS NULL) ORDER BY id ASC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, afterMessageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new CleanupMessage(result.getLong("id"), result.getString("role"),
                        result.getLong("bytes"), nullableLong(result, "turn_id")) : null;
            }
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    /**
     * 清理范围里的一轮。字节口径与 {@code ChatTurnRepository.clearScope} 的释放量逐字一致：
     * 轮次小计 {@code stored_history_bytes} 加它自己的附件字节——预览报多少，执行就扣多少。
     */
    public record CleanupTurn(long turnId, String requestId, String status, long sourceRevision,
                              long storedHistoryBytes, long attachmentBytes, long lastMessageId) {
    }

    /**
     * 边界内的<b>完整轮次</b>清单（预览与执行共用同一条 SQL，重算必须逐字段相同）：
     * 非 RUNNING、未立墓碑、还挂着消息行，且它<b>最后</b>一条消息 ≤ 边界——
     * 只清"问答都在边界之内"的轮次，绝不把一轮劈成两半（契约 §3「固定最多 200 完整轮次」）。
     *
     * <p>到上限后取的是贴边界的最近若干轮（{@code last_message_id DESC}），所以"还有多少更早的轮次
     * 没纳入"由 {@link #cleanupTurnCount} 单独报，界面不许把它说成"全部清完"。</p>
     */
    public List<CleanupTurn> cleanupTurns(long sessionId, long beforeOrAtMessageId, int limit) throws SQLException {
        String sql = "SELECT t.id, t.request_id, t.status, t.source_revision, t.stored_history_bytes,"
                + " COALESCE((SELECT SUM(a.byte_length) FROM ai_chat_attachment a WHERE a.turn_id = t.id), 0)"
                + " AS attachment_bytes,"
                + " (SELECT MAX(m.id) FROM ai_chat_message m WHERE m.turn_id = t.id) AS last_message_id"
                + " FROM ai_chat_turn t"
                + " WHERE t.session_id = ? AND t.status <> 'RUNNING' AND t.content_deleted_at IS NULL"
                + " AND (SELECT COUNT(*) FROM ai_chat_message m WHERE m.turn_id = t.id) > 0"
                + " AND (SELECT MAX(m.id) FROM ai_chat_message m WHERE m.turn_id = t.id) <= ?"
                + " ORDER BY last_message_id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeOrAtMessageId);
            statement.setInt(3, Math.max(1, limit));
            List<CleanupTurn> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new CleanupTurn(result.getLong("id"), result.getString("request_id"),
                            result.getString("status"), result.getLong("source_revision"),
                            result.getLong("stored_history_bytes"), result.getLong("attachment_bytes"),
                            result.getLong("last_message_id")));
                }
            }
            return rows;
        }
    }

    /** 边界内的完整轮次总数（不受 limit 影响），用来如实报"更早的还有几轮没纳入这次预览"。 */
    public long cleanupTurnCount(long sessionId, long beforeOrAtMessageId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ai_chat_turn t"
                + " WHERE t.session_id = ? AND t.status <> 'RUNNING' AND t.content_deleted_at IS NULL"
                + " AND (SELECT COUNT(*) FROM ai_chat_message m WHERE m.turn_id = t.id) > 0"
                + " AND (SELECT MAX(m.id) FROM ai_chat_message m WHERE m.turn_id = t.id) <= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, beforeOrAtMessageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }
}
