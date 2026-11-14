package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 旧（v1）聊天会话与消息的读写入口。
 *
 * <p>消息新增、裁剪和会话删除都会改动历史存量，因此一律走 {@link ImmediateTransaction}，
 * 在同一次事务里连同 {@link ChatStorageLedger} 记账一起提交：账本与实测脱节会让后续额度判定失真。
 * 额度上限的准入判定属于轮次路径（P3 的开始预留），这里只保证「写多少记多少、删多少扣多少」。</p>
 */
@Repository
public class AiChatSessionRepository {
    private static final String SELECT = """
            SELECT s.*, COUNT(m.id) AS message_count,
              EXISTS (SELECT 1 FROM ai_chat_turn t
                JOIN ai_chat_message u ON u.id = t.user_message_id
                JOIN ai_chat_message a ON a.id = t.assistant_message_id
                WHERE t.session_id = s.id AND t.status = 'COMPLETED'
                  AND t.persistence_status = 'saved' AND t.content_deleted_at IS NULL) AS can_generate_title
            FROM ai_chat_session s LEFT JOIN ai_chat_message m ON m.session_id = s.id
            """;
    private final JdbcTemplate jdbc;
    private final ImmediateTransaction immediate;
    private final ChatStorageLedger ledger;
    private final ChatSessionMetadataRepository metadata;
    private final MemoryRepository memory;

    public AiChatSessionRepository(JdbcTemplate jdbc, ImmediateTransaction immediate,
                                   ChatStorageLedger ledger, ChatSessionMetadataRepository metadata,
                                   MemoryRepository memory) {
        this.jdbc = jdbc;
        this.immediate = immediate;
        this.ledger = ledger;
        this.metadata = metadata;
        this.memory = memory;
    }

    public List<Map<String, Object>> list(boolean includeArchived) {
        String sql = includeArchived
                ? SELECT + " GROUP BY s.id ORDER BY s.updated_at DESC, s.id DESC"
                : SELECT + " WHERE s.archived = 0 GROUP BY s.id ORDER BY s.updated_at DESC, s.id DESC";
        return jdbc.query(sql, (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id"));
            item.put("title", result.getString("title"));
            item.put("archived", result.getInt("archived") == 1);
            item.put("model", result.getString("model"));
            item.put("tags", result.getString("tags"));
            item.put("createdAt", result.getString("created_at"));
            item.put("updatedAt", result.getString("updated_at"));
            item.put("messageCount", result.getLong("message_count"));
            item.put("modelConfigId", result.getString("model_config_id"));
            item.put("reasoningEffort", result.getString("reasoning_effort"));
            item.put("modelChoiceRevision", result.getLong("model_choice_revision"));
            item.put("pinnedAt", result.getString("pinned_at"));
            titleMetadata(result, item);
            return item;
        });
    }

    public Map<String, Object> find(long id) {
        return jdbc.queryForObject(SELECT + " WHERE s.id = ? GROUP BY s.id", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id"));
            item.put("title", result.getString("title"));
            item.put("archived", result.getInt("archived") == 1);
            item.put("model", result.getString("model"));
            item.put("tags", result.getString("tags"));
            item.put("createdAt", result.getString("created_at"));
            item.put("updatedAt", result.getString("updated_at"));
            item.put("messageCount", result.getLong("message_count"));
            item.put("modelConfigId", result.getString("model_config_id"));
            item.put("reasoningEffort", result.getString("reasoning_effort"));
            item.put("modelChoiceRevision", result.getLong("model_choice_revision"));
            item.put("pinnedAt", result.getString("pinned_at"));
            titleMetadata(result, item);
            return item;
        }, id);
    }

    public long create(String title, String model) {
        return create(title, model, new ModelChoice(null, null));
    }

    public long create(String title, String model, ModelChoice choice) {
        String name = title == null || title.isBlank() ? "新会话" : title.trim();
        boolean temporary = "新会话".equals(name) || "默认会话".equals(name);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_chat_session(title, model, session_ref, title_source, title_status, model_config_id, reasoning_effort, model_choice_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, model);
            statement.setString(3, java.util.UUID.randomUUID().toString().replace("-", ""));
            statement.setString(4, temporary ? "temporary" : "manual");
            statement.setString(5, temporary ? "pending" : "idle");
            statement.setString(6, choice.modelConfigId());
            statement.setString(7, choice.effort());
            statement.setInt(8, choice.modelConfigId() == null ? 0 : 1);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void update(long id, String title, Boolean archived, String model) {
        if(archived!=null) {
            try {
                if(metadata.setArchived(id,archived,archived ? java.time.Instant.now().toString() : null) == ChatSessionMetadataRepository.ArchiveResult.REFUSED_RUNNING)
                    throw new com.drillnotebook.app.service.ChatTurnRejectException(409,"SESSION_BUSY","请先停止正在运行和待发送的消息",Map.of());
            } catch(SQLException e) { throw new IllegalStateException("会话归档失败",e); }
        }
        jdbc.update("""
                UPDATE ai_chat_session SET
                    title = COALESCE(?, title),
                    archived = COALESCE(?, archived),
                    model = COALESCE(?, model),
                    title_source = CASE WHEN ? IS NULL THEN title_source ELSE 'manual' END,
                    title_status = CASE WHEN ? IS NULL THEN title_status ELSE 'idle' END,
                    title_revision = title_revision + CASE WHEN ? IS NULL THEN 0 ELSE 1 END
                WHERE id = ?
                """, title == null ? null : title.trim(), archived == null ? null : (archived ? 1 : 0), model,
                title, title, title, id);
    }

    /** 会话自己的模型与思考档位；空模型只表示尚未初始化，空档位使用这个模型的默认值。 */
    public record ModelChoice(String modelConfigId, String effort) { }

    public ModelChoice modelChoice(long id) {
        return jdbc.query("SELECT model_config_id, reasoning_effort FROM ai_chat_session WHERE id = ?",
                (row, index) -> new ModelChoice(row.getString(1), row.getString(2)), id)
                .stream().findFirst().orElse(new ModelChoice(null, null));
    }

    public void setModelChoice(long id, String modelConfigId, String effort) {
        jdbc.update("UPDATE ai_chat_session SET model_config_id = ?, reasoning_effort = ?, model_choice_revision = model_choice_revision + 1 WHERE id = ?", modelConfigId, effort, id);
    }

    /** 置顶只记录时刻，不改最近活动时间；重复置顶保留最初的时刻，顺序不变。 */
    public void setPinned(long id, boolean pinned) {
        jdbc.update("UPDATE ai_chat_session SET pinned_at = CASE WHEN ? THEN COALESCE(pinned_at, ?) ELSE NULL END WHERE id = ?",
                pinned ? 1 : 0, java.time.Instant.now().toString(), id);
    }

    /** 这一轮实际使用的模型与档位：历史里的显示不随之后的改选变化。 */
    public void recordTurnModel(String requestId, String modelLabel, String effort) {
        jdbc.update("UPDATE ai_chat_turn SET model_label = ?, reasoning_effort = ? WHERE request_id = ?", modelLabel, effort, requestId);
    }

    public void recordTurnPage(String requestId, String title, String link) {
        jdbc.update("UPDATE ai_chat_turn SET page_title = ?, page_link = ? WHERE request_id = ?", title, link, requestId);
    }

    /**
     * 一次模型请求实际送入的长度，以及供应商回报的缓存命中部分与输出长度；
     * 同一轮多次请求时保留最近一次，不累加。
     */
    public void recordTurnContext(String requestId, int contextTokens, int contextWindow, Integer cacheReadTokens, Integer outputTokens) {
        jdbc.update("UPDATE ai_chat_turn SET context_tokens = ?, context_window = ?, cache_read_tokens = ?, output_tokens = ? WHERE request_id = ?",
                contextTokens, contextWindow, cacheReadTokens, outputTokens, requestId);
    }

    /** 当前上下文代数里最近一次有实测输入的轮次；没有则返回 null。 */
    public Map<String, Object> latestContext(long sessionId) {
        return jdbc.query("""
                SELECT t.request_id, t.context_tokens, t.context_window, t.model_label,
                       t.cache_read_tokens, t.output_tokens FROM ai_chat_turn t
                JOIN ai_chat_session s ON s.id = t.session_id
                WHERE t.session_id = ? AND t.context_tokens IS NOT NULL AND t.content_deleted_at IS NULL
                  AND t.context_generation = s.context_generation
                ORDER BY t.id DESC LIMIT 1
                """, (row, index) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requestId", row.getString(1));
            item.put("inputTokens", row.getInt(2));
            item.put("contextWindow", row.getInt(3));
            item.put("model", row.getString(4));
            item.put("cacheReadTokens", integerOrNull(row, 5));
            item.put("outputTokens", integerOrNull(row, 6));
            return item;
        }, sessionId).stream().findFirst().orElse(null);
    }

    /** 供应商没回报的字段保持 NULL，界面按「未回报」显示而不是 0。 */
    private static Integer integerOrNull(ResultSet row, int index) throws SQLException {
        int value = row.getInt(index);
        return row.wasNull() ? null : value;
    }

    private static void titleMetadata(ResultSet row, Map<String, Object> item) throws SQLException {
        item.put("sessionLink", "drill-notebook://session/" + row.getString("session_ref"));
        item.put("titleSource", row.getString("title_source"));
        item.put("titleStatus", row.getString("title_status"));
        item.put("titleRevision", row.getLong("title_revision"));
        item.put("canGenerateTitle", row.getInt("can_generate_title") == 1);
    }

    public void fillTemporaryTitle(long id, String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) return;
        int end = compact.offsetByCodePoints(0, Math.min(24, compact.codePointCount(0, compact.length())));
        String title = compact.substring(0, end) + (end < compact.length() ? "…" : "");
        jdbc.update("""
                UPDATE ai_chat_session SET title = ? WHERE id = ? AND title_source = 'temporary'
                AND title_status = 'pending' AND title IN ('新会话', '默认会话')
                """, title, id);
    }

    public boolean claimTitle(long id, long revision, boolean automatic) {
        return jdbc.update("""
                UPDATE ai_chat_session SET title_status = 'generating', title_revision = title_revision + 1
                WHERE id = ? AND title_revision = ? AND title_status <> 'generating'
                """ + (automatic ? " AND title_source = 'temporary' AND title_status = 'pending'" : ""),
                id, revision) == 1;
    }

    public boolean finishTitle(long id, long revision, String title) {
        return jdbc.update("""
                UPDATE ai_chat_session SET title = COALESCE(?, title),
                  title_source = CASE WHEN ? IS NULL THEN title_source ELSE 'ai' END,
                  title_status = ?, title_revision = title_revision + 1
                WHERE id = ? AND title_revision = ? AND title_status = 'generating'
                """, title, title, title == null ? "failed" : "ready", id, revision) == 1;
    }

    /** 只读首个持久化完整问答；不把中断半成品、工具结果或已清理正文当素材。 */
    public List<MessageRow> firstCompleteExchange(long sessionId) {
        return jdbc.query("""
                SELECT m.* FROM ai_chat_message m WHERE m.id IN (
                  SELECT u.id FROM ai_chat_turn t
                  JOIN ai_chat_message u ON u.id IN (t.user_message_id, t.assistant_message_id)
                  WHERE t.id = (SELECT t2.id FROM ai_chat_turn t2
                    JOIN ai_chat_message q ON q.id = t2.user_message_id
                    JOIN ai_chat_message a ON a.id = t2.assistant_message_id
                    WHERE t2.session_id = ? AND t2.status = 'COMPLETED'
                      AND t2.persistence_status = 'saved' AND t2.content_deleted_at IS NULL ORDER BY t2.id LIMIT 1)
                ) ORDER BY m.id
                """, (r, row) -> new MessageRow(r.getLong("id"), sessionId, r.getString("role"),
                r.getString("content"), r.getString("content_cipher"), r.getString("content_meta"),
                null, null, r.getString("created_at")), sessionId);
    }

    /**
     * 删除会话：先在同一事务里清理来源证据与待确认项，已保存记忆保留原内容与来源类型，
     * 再按实测把历史与附件字节、以及仍未结算的回复预留一并扣回账本，最后删行（§2.3）。
     */
    public void delete(long id) {
        delete(id, connection -> null);
    }

    /** 在同一写事务内执行调用方的身份校验，避免查找之后目标或来源轮次已变化。 */
    public void delete(long id, org.springframework.jdbc.core.ConnectionCallback<Void> guard) {
        try {
            ledger.ensureRebuiltInProcess();
            immediate.execute(connection -> {
                guard.doInConnection(connection);
                if (SessionDispatchRepository.busy(connection,id)) throw new com.drillnotebook.app.service.ChatTurnRejectException(
                        409,"SESSION_BUSY","会话有运行或待发送消息，请先停止",Map.of());
                memory.purgeEvidence(connection, id, List.of(), List.of());
                long historyBytes = ChatStorageSizer.sessionHistoryBytes(connection, id);
                long attachmentBytes = ChatStorageSizer.sessionAttachmentBytes(connection, id);
                long liveReservation = ChatStorageSizer.liveReservationBytes(connection, id);
                // 附件经轮次归属，必须先于轮次删除；消息与轮次互相引用但都是 SET NULL，顺序无关
                execute(connection, "DELETE FROM ai_chat_attachment WHERE turn_id IN"
                        + " (SELECT id FROM ai_chat_turn WHERE session_id = ?)", id);
                execute(connection, "DELETE FROM ai_chat_message WHERE session_id = ?", id);
                execute(connection, "DELETE FROM ai_chat_turn WHERE session_id = ?", id);
                execute(connection, "DELETE FROM ai_chat_session WHERE id = ?", id);
                ledger.releaseStorage(connection, historyBytes, attachmentBytes);
                ledger.releaseReservation(connection, liveReservation);
                return null;
            });
        } catch (SQLException error) {
            throw new UncategorizedSQLException("删除聊天会话", null, error);
        }
    }

    public List<MessageRow> messages(long sessionId) {
        return jdbc.query("""
                SELECT id, session_id, role, content, content_cipher, content_meta, reasoning_cipher, reasoning_meta, created_at
                FROM ai_chat_message WHERE session_id = ? ORDER BY id
                """, (result, row) -> new MessageRow(
                result.getLong("id"),
                result.getObject("session_id") == null ? null : result.getLong("session_id"),
                result.getString("role"),
                result.getString("content"),
                result.getString("content_cipher"),
                result.getString("content_meta"),
                result.getString("reasoning_cipher"),
                result.getString("reasoning_meta"),
                result.getString("created_at")
        ), sessionId);
    }

    /** Supplements belong to a turn, rather than separate legacy message rows. */
    public List<com.drillnotebook.app.service.ChatSupplement> supplements(long assistantMessageId,
            com.drillnotebook.app.service.ChatMessageCipher cipher, String password) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<List<com.drillnotebook.app.service.ChatSupplement>>) connection -> {
            String requestId = null;
            try (var statement = connection.prepareStatement("SELECT request_id FROM ai_chat_turn WHERE assistant_message_id = ? AND content_deleted_at IS NULL")) {
                statement.setLong(1, assistantMessageId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) requestId = rows.getString(1);
                }
            }
            return com.drillnotebook.app.service.ChatSupplement.read(connection, requestId, cipher, password);
        });
    }

    /** 返回真实消息 ID：轮次回填、导出与来源绑定都要用，不能再靠"最后一条"猜。 */
    public long insertMessage(long sessionId, String role, String content, String contentCipher, String contentMeta) {
        return insertMessage(sessionId, role, content, contentCipher, contentMeta, null, null);
    }

    public long insertMessage(long sessionId, String role, String content, String contentCipher, String contentMeta,
                              String reasoningCipher, String reasoningMeta) {
        String storedContent = content == null ? "" : content;
        long newBytes = ChatStorageSizer.storedBytes(storedContent, contentCipher, contentMeta,
                reasoningCipher, reasoningMeta);
        try {
            ledger.ensureRebuiltInProcess();
            return immediate.execute(connection -> {
                long messageId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta,
                            reasoning_cipher, reasoning_meta)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, sessionId);
                    statement.setString(2, role);
                    statement.setString(3, storedContent);
                    statement.setString(4, contentCipher);
                    statement.setString(5, contentMeta);
                    statement.setString(6, reasoningCipher);
                    statement.setString(7, reasoningMeta);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("旧聊天消息写入未命中单行");
                    }
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("消息写入后没有取回自增主键");
                        messageId = keys.getLong(1);
                    }
                }
                ledger.addStoredHistory(connection, newBytes);
                metadata.bumpHistoryRevision(connection, sessionId);
                return messageId;
            });
        } catch (SQLException error) {
            throw new UncategorizedSQLException("写入聊天消息", null, error);
        }
    }

    public Long firstSessionId() {
        List<Long> ids = jdbc.query("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", (result, row) -> result.getLong(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long ensureDefaultSession() {
        Long existing = firstSessionId();
        return existing == null ? create("默认会话", null) : existing;
    }

    public record MessageRow(long id, Long sessionId, String role, String content, String contentCipher, String contentMeta,
                             String reasoningCipher, String reasoningMeta, String createdAt) {}

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static long scalar(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("容量统计没有返回行: " + sql);
                return result.getLong(1);
            }
        }
    }
}
