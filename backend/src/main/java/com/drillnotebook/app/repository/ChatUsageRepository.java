package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Repository;

/** 只读请求账本：会话累计与每轮累计同源，不受历史分页、上下文代数或正文清理影响。 */
@Repository
public class ChatUsageRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;

    public ChatUsageRepository(DataSource dataSource) { this.dataSource = dataSource; }

    private static String measuredRows(String turnFilter) {
        return measuredRows(turnFilter, false);
    }

    private static String measuredRows(String turnFilter, boolean includePending) {
        return """
                WITH turns AS (
                    SELECT id, request_id FROM ai_chat_turn WHERE session_id = ?
                """ + turnFilter + """
                ), receipts AS (
                    SELECT request_id AS operation_id, request_id FROM turns
                    UNION
                    SELECT r.request_id, t.request_id FROM ai_chat_retry_receipt r JOIN turns t ON t.id = r.turn_id
                ), reported AS (
                    SELECT r.request_id, a.id AS attempt_id,
                """ + (includePending ? "a.operation_id, a.created_at, a.settled_at, a.state, a.usage_source, a.context_breakdown,\n" : "") + """
                        CASE WHEN a.state = 'SETTLED' AND a.usage_source = 'provider' AND a.input_tokens >= 0
                            THEN CASE
                                WHEN a.cache_read_included_in_input = 1 THEN a.input_tokens
                                WHEN a.cache_read_included_in_input = 0 THEN a.input_tokens
                                    + MAX(0, COALESCE(a.cache_read_tokens, 0))
                                    + MAX(0, COALESCE(a.cache_write_tokens, 0))
                                WHEN COALESCE(a.cache_read_tokens, 0) = 0 AND COALESCE(a.cache_write_tokens, 0) = 0
                                    THEN a.input_tokens
                            END END AS input,
                        CASE WHEN a.state = 'SETTLED' AND a.usage_source = 'provider' AND a.output_tokens >= 0
                            THEN a.output_tokens END AS output,
                        CASE WHEN a.state = 'SETTLED' AND a.usage_source = 'provider' AND a.cache_read_tokens >= 0
                            THEN a.cache_read_tokens END AS cache_read,
                        CASE WHEN a.state = 'SETTLED' AND a.usage_source = 'provider' AND a.input_tokens >= 0
                            AND (a.cache_read_included_in_input = 1
                                OR a.cache_read_included_in_input = 0 AND a.cache_read_tokens >= 0 AND a.cache_write_tokens >= 0
                                OR a.cache_read_included_in_input IS NULL
                                    AND COALESCE(a.cache_read_tokens, 0) = 0 AND COALESCE(a.cache_write_tokens, 0) = 0)
                            THEN 1 ELSE 0 END AS input_complete
                    FROM receipts r JOIN ai_usage_attempt a ON a.operation_id = r.operation_id
                    WHERE a.purpose = 'CHAT' AND a.state IN ('SETTLED', 'UNKNOWN'
                """ + (includePending ? ", 'SENT'" : "") + """
                    )
                ), measured AS (
                    SELECT *, CASE WHEN input IS NULL THEN cache_read ELSE MIN(input, cache_read) END AS hit,
                        CASE WHEN input IS NOT NULL AND cache_read IS NOT NULL
                            THEN MAX(0, input - cache_read) END AS miss,
                        CASE WHEN input_complete = 1 AND cache_read IS NOT NULL THEN 1 ELSE 0 END AS miss_complete
                    FROM reported
                )
                """;
    }

    private static final String SUMS = """
            COUNT(*) AS requests, SUM(input) AS input_tokens, SUM(input_complete) AS inputs,
            SUM(output) AS output_tokens, COUNT(output) AS outputs,
            SUM(hit) AS cache_read_tokens, COUNT(hit) AS hits,
            SUM(miss) AS cache_miss_tokens, SUM(miss_complete) AS misses
            """;

    public Map<String, Object> session(long sessionId) {
        String sql = measuredRows("") + "SELECT " + SUMS + " FROM measured";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return totals(row);
            }
        } catch (SQLException error) {
            throw new UncategorizedSQLException("读取会话累计用量", sql, error);
        }
    }

    Map<String, Map<String, Object>> turns(Connection connection, long sessionId, Collection<String> requestIds)
            throws SQLException {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (requestIds.isEmpty()) return result;
        String marks = String.join(",", Collections.nCopies(requestIds.size(), "?"));
        String sql = measuredRows(" AND request_id IN (" + marks + ")")
                + "SELECT request_id, " + SUMS + " FROM measured GROUP BY request_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionId);
            int index = 2;
            for (String requestId : requestIds) statement.setString(index++, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString("request_id"), totals(rows));
            }
        }
        return result;
    }

    /** 分页只读本轮及旧重试回执对应的请求；仅当前页解析有界组成摘要。 */
    public Map<String, Object> requestPage(long sessionId, String requestId, String beforeAttemptId) throws SQLException {
        final int limit = 50;
        String base = measuredRows(" AND request_id = ?", true)
                + ", ordered AS (SELECT *, ROW_NUMBER() OVER (ORDER BY created_at, attempt_id) AS ordinal FROM measured) ";
        try (Connection connection = dataSource.getConnection()) {
            if (beforeAttemptId != null) {
                try (PreparedStatement cursor = connection.prepareStatement(base + "SELECT 1 FROM ordered WHERE attempt_id = ?")) {
                    cursor.setLong(1, sessionId); cursor.setString(2, requestId); cursor.setString(3, beforeAttemptId);
                    try (ResultSet row = cursor.executeQuery()) {
                        if (!row.next()) throw new ChatTurnRejectException(400, "CONTEXT_REQUEST_CURSOR_INVALID",
                                "请求游标不属于本轮上下文", Map.of());
                    }
                }
            }
            long total;
            try (PreparedStatement count = connection.prepareStatement(base + "SELECT COUNT(*) FROM ordered")) {
                count.setLong(1, sessionId); count.setString(2, requestId);
                try (ResultSet row = count.executeQuery()) { row.next(); total = row.getLong(1); }
            }
            String sql = base + "SELECT * FROM ordered"
                    + (beforeAttemptId == null ? "" : " WHERE ordinal < (SELECT ordinal FROM ordered WHERE attempt_id = ?)")
                    + " ORDER BY ordinal DESC LIMIT ?";
            var items = new ArrayList<Map<String, Object>>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, sessionId); statement.setString(2, requestId);
                int index = 3;
                if (beforeAttemptId != null) statement.setString(index++, beforeAttemptId);
                statement.setInt(index, limit + 1);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var item = new LinkedHashMap<String, Object>();
                        item.put("attemptId", rows.getString("attempt_id"));
                        item.put("operationId", rows.getString("operation_id"));
                        item.put("ordinal", rows.getLong("ordinal"));
                        item.put("createdAt", rows.getString("created_at"));
                        item.put("settledAt", rows.getString("settled_at"));
                        item.put("state", rows.getString("state"));
                        item.put("usageSource", rows.getString("usage_source"));
                        Long input = nullableLong(rows, "input"), output = nullableLong(rows, "output");
                        item.put("inputTokens", input); item.put("outputTokens", output);
                        item.put("cacheReadTokens", nullableLong(rows, "hit"));
                        item.put("cacheMissTokens", nullableLong(rows, "miss"));
                        item.put("totalTokens", input == null || output == null ? null : input + output);
                        boolean inputComplete = rows.getBoolean("input_complete");
                        item.put("inputComplete", inputComplete); item.put("outputComplete", output != null);
                        item.put("cacheReadComplete", nullableLong(rows, "hit") != null);
                        item.put("cacheMissComplete", rows.getBoolean("miss_complete"));
                        item.put("totalComplete", inputComplete && output != null);
                        item.put("context", readContext(rows.getString("context_breakdown")));
                        items.add(item);
                    }
                }
            }
            boolean more = items.size() > limit;
            if (more) items.remove(items.size() - 1);
            Collections.reverse(items);
            var result = new LinkedHashMap<String, Object>();
            result.put("items", items); result.put("totalRequests", total); result.put("hasMore", more);
            result.put("nextBeforeAttemptId", more && !items.isEmpty() ? items.get(0).get("attemptId") : null);
            result.put("limit", limit);
            return result;
        }
    }

    private static RequestContextAudit readContext(String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RequestContextAudit.MAX_BYTES) return null;
        try { return JSON.readValue(json, RequestContextAudit.class); }
        catch (Exception error) { return null; }
    }

    private static Map<String, Object> totals(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        long requests = row.getLong("requests");
        Long input = nullableLong(row, "input_tokens");
        Long output = nullableLong(row, "output_tokens");
        result.put("requests", requests);
        result.put("inputTokens", input);
        result.put("outputTokens", output);
        result.put("cacheReadTokens", nullableLong(row, "cache_read_tokens"));
        result.put("cacheMissTokens", nullableLong(row, "cache_miss_tokens"));
        result.put("totalTokens", input == null || output == null ? null : input + output);
        boolean inputsComplete = requests > 0 && row.getLong("inputs") == requests;
        boolean outputsComplete = requests > 0 && row.getLong("outputs") == requests;
        result.put("inputComplete", inputsComplete);
        result.put("outputComplete", outputsComplete);
        result.put("cacheReadComplete", requests > 0 && row.getLong("hits") == requests);
        result.put("cacheMissComplete", requests > 0 && row.getLong("misses") == requests);
        result.put("totalComplete", inputsComplete && outputsComplete);
        return result;
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }
}
