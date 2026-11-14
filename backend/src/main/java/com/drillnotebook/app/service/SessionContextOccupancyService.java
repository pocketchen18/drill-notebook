package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.model.RequestContextAudit.Kind;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * 当前会话保留的全局上下文，不是最近一次请求输入，也不是累计计费用量。
 * 以当前代、未清理的消息为集合，每份正文只计算一次；读取逐行进行，不受历史驻留或外发裁剪上限影响。
 */
@Service
public class SessionContextOccupancyService {
    private final DataSource source;
    private final ChatMessageCipher cipher;
    private final ConversationProjection projection;
    private final ChatTurnService turns;
    private final ObjectMapper mapper;
    private final Map<String, Integer> supplementTokens = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) { return size() > 4096; }
            });

    public SessionContextOccupancyService(DataSource source, ChatMessageCipher cipher,
                                         ConversationProjection projection, ChatTurnService turns, ObjectMapper mapper) {
        this.source = source; this.cipher = cipher; this.projection = projection; this.turns = turns; this.mapper = mapper;
    }

    public Map<String, Object> read(long sessionId, long prefixTokens) {
        return read(sessionId, new ContextTokenBreakdown(Map.of(Kind.SYSTEM, Math.max(0, prefixTokens))));
    }

    public Map<String, Object> read(long sessionId, ContextTokenBreakdown prefix) {
        try (Connection connection = source.getConnection()) {
            // 多个查询使用同一读快照；窗口统计不进行写入、维护、检索或模型调用。
            connection.setAutoCommit(false);
            var state = ChatSessionMetadataRepository.read(connection, sessionId).orElseThrow();
            var summary = projection == null ? null : projection.currentSummary(sessionId, state.contextGeneration());
            if (summary != null && !sameSummary(connection, sessionId, summary.audit().revision())) summary = null;
            long recentFloor = recentFloor(connection, sessionId, state.contextGeneration());
            var totals = new Totals(prefix);
            if (summary != null) totals.add(Kind.INJECTED_CONTEXT, summary.estimatedTokens(), true);
            String latestRequest = null, latestStatus = null, latestCrypto = null;
            try (var statement = connection.prepareStatement("""
                    SELECT request_id, status, result_meta_crypto FROM ai_chat_turn
                    WHERE session_id = ? AND context_generation = ? AND content_deleted_at IS NULL
                    ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END,
                      julianday(COALESCE(finished_at, started_at)) DESC, request_id DESC, id DESC LIMIT 1
                    """)) {
                statement.setLong(1, sessionId); statement.setLong(2, state.contextGeneration());
                try (var row = statement.executeQuery()) {
                    if (row.next()) { latestRequest = row.getString(1); latestStatus = row.getString(2); latestCrypto = row.getString(3); }
                }
            }
            try (var statement = connection.prepareStatement("""
                    SELECT m.role, m.content, m.content_cipher, m.content_meta, t.id, t.status
                    FROM ai_chat_message m LEFT JOIN ai_chat_turn t ON t.id = m.turn_id AND t.session_id = m.session_id
                    WHERE m.session_id = ? AND m.role IN ('user', 'assistant') AND
                      ((t.context_generation = ? AND t.content_deleted_at IS NULL)
                        OR (m.turn_id IS NULL AND ? = 1)) ORDER BY m.id
                    """)) {
                statement.setLong(1, sessionId); statement.setLong(2, state.contextGeneration()); statement.setLong(3, state.contextGeneration());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long turnId = rows.getLong(5);
                        if (covered(summary, turnId, recentFloor, rows.getString(6))) continue;
                        if ("assistant".equals(rows.getString(1)) && "RUNNING".equals(rows.getString(6))) continue;
                        totals.add("user".equals(rows.getString(1)) ? Kind.USER : Kind.ASSISTANT,
                                cipher.estimateStoredText(rows.getString(2), rows.getString(3), rows.getString(4)), true);
                    }
                }
            }
            // 只有已被本轮接收的插话属于上下文；队列和未确认交付项不计入。
            try (var statement = connection.prepareStatement("""
                    SELECT p.request_id, p.payload_cipher, p.payload_meta, t.id, t.status
                    FROM ai_chat_pending p JOIN ai_chat_turn t ON t.request_id = p.parent_request_id AND t.session_id = p.session_id
                    WHERE t.session_id = ? AND t.context_generation = ? AND t.content_deleted_at IS NULL
                      AND p.state = 'RECEIVED' ORDER BY t.id, p.request_id
                    """)) {
                statement.setLong(1, sessionId); statement.setLong(2, state.contextGeneration());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (!covered(summary, rows.getLong(4), recentFloor, rows.getString(5)))
                            totals.add(Kind.USER, supplement(rows.getString(1), rows.getString(2), rows.getString(3)), true);
                    }
                }
            }
            if (latestRequest != null) {
                boolean running = "RUNNING".equals(latestStatus);
                var activeTokens = running ? turns.currentContextTokens(latestRequest) : null;
                Long extras = running ? (activeTokens == null ? null : activeTokens.extraTokens())
                        : metaNumber(latestCrypto, "contextExtraTokens");
                if (extras != null) {
                    ContextTokenBreakdown categories = running
                            ? validCategories(activeTokens.extraCategories(), extras) : metaCategories(latestCrypto, extras);
                    // 旧版本只保存了总量时如实归入其它内容，不借用历史请求的分类。
                    if (categories == null) totals.categories.add(Kind.OTHER, extras);
                    else totals.categories.add(categories);
                }
                else oldExtras(connection, latestRequest, totals);
                if (running) {
                    long output = activeTokens == null ? 0 : activeTokens.outputTokens();
                    if (output > 0) {
                        totals.categories.add(Kind.ASSISTANT, output);
                        totals.categories.add(Kind.OTHER, TokenEstimator.MESSAGE_OVERHEAD_TOKENS);
                    }
                }
            }
            var result = new LinkedHashMap<String, Object>();
            var breakdown = totals.categories.build();
            result.put("tokens", breakdown.tokens()); result.put("estimated", true); result.put("complete", totals.complete);
            result.put("categories", breakdown.categories().entrySet().stream()
                    .map(entry -> Map.of("kind", entry.getKey().name(), "tokens", entry.getValue())).toList());
            result.put("estimatorVersion", TokenEstimator.VERSION);
            result.put("contextGeneration", state.contextGeneration()); result.put("historyRevision", state.historyRevision());
            result.put("requestId", latestRequest); result.put("active", "RUNNING".equals(latestStatus));
            result.put("summaryRevision", summary == null ? null : summary.audit().revision());
            connection.commit();
            return result;
        } catch (SQLException error) { throw new IllegalStateException("当前会话上下文暂时无法统计", error); }
    }

    private static final class Totals {
        final ContextTokenBreakdown.Builder categories = new ContextTokenBreakdown.Builder();
        boolean complete = true;
        Totals(ContextTokenBreakdown prefix) { categories.add(prefix); }
        void add(Kind kind, Integer value, boolean message) {
            if (value == null) { complete = false; return; }
            categories.add(kind, value.longValue());
            if (message) categories.add(Kind.OTHER, TokenEstimator.MESSAGE_OVERHEAD_TOKENS);
        }
    }

    private boolean sameSummary(Connection connection, long sessionId, long revision) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision FROM ai_session_context WHERE session_id = ?")) {
            statement.setLong(1, sessionId);
            try (var row = statement.executeQuery()) { return row.next() && row.getLong(1) == revision; }
        }
    }

    private static long recentFloor(Connection connection, long sessionId, long generation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT MIN(id) FROM (SELECT id FROM ai_chat_turn WHERE session_id = ? AND context_generation = ?
                    AND content_deleted_at IS NULL AND status = 'COMPLETED' ORDER BY id DESC LIMIT 2)
                """)) {
            statement.setLong(1, sessionId); statement.setLong(2, generation);
            try (var row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    private static boolean covered(ConversationProjection.SummaryView summary, long turnId, long recentFloor, String status) {
        if (summary == null || turnId <= 0 || turnId >= recentFloor || !"COMPLETED".equals(status)) return false;
        for (ContextAudit.CoverageRange range : summary.audit().coverageRanges())
            if (turnId >= range.firstTurnId() && turnId <= range.lastTurnId()) return true;
        return false;
    }

    private Long metaNumber(String crypto, String key) {
        if (crypto == null) return null;
        try {
            var meta = mapper.readTree(crypto);
            if (!TokenEstimator.VERSION.equals(meta.path("tokenEstimatorVersion").asText())) return null;
            var count = meta.path(key);
            return count.isIntegralNumber() && count.canConvertToLong() && count.longValue() >= 0 ? count.longValue() : null;
        } catch (Exception ignored) { return null; }
    }

    private static ContextTokenBreakdown validCategories(Map<Kind, Long> categories, long expected) {
        try {
            var breakdown = new ContextTokenBreakdown(categories);
            return breakdown.tokens() == expected ? breakdown : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private ContextTokenBreakdown metaCategories(String crypto, long expected) {
        try {
            var values = mapper.readTree(crypto).path("contextExtraCategories");
            if (!values.isObject() || values.size() > Kind.values().length) return null;
            var categories = new java.util.EnumMap<Kind, Long>(Kind.class);
            var fields = values.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                var value = field.getValue();
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) return null;
                categories.put(Kind.valueOf(field.getKey()), value.longValue());
            }
            return validCategories(categories, expected);
        } catch (Exception ignored) { return null; }
    }

    private Integer supplement(String requestId, String encrypted, String meta) {
        Long recorded = metaNumber(meta, "supplementTextTokens");
        if (recorded != null && recorded <= Integer.MAX_VALUE) return recorded.intValue();
        String key = requestId + ':' + ChatMessageCipher.estimateKey(encrypted, meta);
        Integer cached = supplementTokens.get(key);
        if (cached != null) return cached;
        var read = cipher.read(encrypted, meta, null);
        if (read.status() != ChatMessageCipher.ReadStatus.DECRYPTED) return null;
        try {
            var text = mapper.readTree(read.value()).path("body").path("input").path("userText");
            if (!text.isTextual()) return null;
            int tokens = new TokenEstimator(mapper).estimateText(text.textValue());
            supplementTokens.put(key, tokens); return tokens;
        } catch (Exception ignored) { return null; }
    }

    /** 旧请求只能提供部分非正文组成；保留已知下界，不改写旧正文或借用其它轮次。 */
    private void oldExtras(Connection connection, String requestId, Totals totals) throws SQLException {
        totals.complete = false;
        try (var statement = connection.prepareStatement("""
                SELECT context_breakdown FROM ai_usage_attempt WHERE operation_id = ? AND purpose = 'CHAT'
                  AND state IN ('SENT', 'SETTLED', 'UNKNOWN') ORDER BY created_at DESC, id DESC
                """)) {
            statement.setString(1, requestId);
            try (var row = statement.executeQuery()) {
                while (row.next()) {
                    String json = row.getString(1);
                    if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RequestContextAudit.MAX_BYTES) return;
                    try {
                        var audit = mapper.readValue(json, RequestContextAudit.class);
                        if ("NATIVE_WEB".equals(audit.kind())) continue;
                        if (!TokenEstimator.VERSION.equals(audit.estimatorVersion())) return;
                        for (var category : audit.categories()) if (category.kind() == RequestContextAudit.Kind.TOOL_CALLS
                                || category.kind() == RequestContextAudit.Kind.TOOL_RESULTS || category.kind() == RequestContextAudit.Kind.IMAGE)
                            totals.categories.add(category.kind(), category.estimatedTokens());
                    } catch (Exception ignored) { }
                    return;
                }
            }
        }
    }
}
