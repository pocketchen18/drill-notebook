package com.drillnotebook.app.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 面向界面的工具审计白名单；绝不接收参数、正文、外部调用 ID 或任意错误文本。 */
public record ToolActivity(int sequence, String toolName, String state, String outcome, String startedAt,
                           Long elapsedMs, int count, String availability, boolean incomplete,
                           String sessionLink, String requestId) {
    public ToolActivity(int sequence,String toolName,String state,String outcome,String startedAt,
                        Long elapsedMs,int count,String availability,boolean incomplete) {
        this(sequence,toolName,state,outcome,startedAt,elapsedMs,count,availability,incomplete,null,null);
    }
    public static final int MAX_ENTRIES = 1200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOOLS = Set.of("search_memories", "list_memories", "create_memory", "update_memory",
            "list_sessions", "read_session", "create_session", "send_message");
    private static final Set<String> OUTCOMES = Set.of("OK", "NO_MATCH", "CREATED", "UPDATED", "DUPLICATE",
            "NO_CHANGE", "UNAVAILABLE", "STALE", "LIMIT_REACHED", "INVALID_ARGUMENT", "NOT_FOUND",
            "AMBIGUOUS", "SUPPRESSED", "REJECTED", "UNKNOWN", "RESULT_TOO_LARGE", "CONTEXT_LIMIT", "REPEATED_CALL", "TIME_LIMIT",
            "SENT", "QUEUED", "RUNNING", "CANCELLED", "CYCLE_BLOCKED", "ARCHIVED", "LOCKED");
    private static final Set<String> AVAILABILITIES = Set.of("READY", "BUILDING", "LOCKED", "UNREADABLE", "TIMEOUT", "NOT_REQUESTED", "DENIED");

    public ToolActivity {
        sessionLink=sessionLink!=null && sessionLink.matches("drill-notebook://session/[a-f0-9]{32}") ? sessionLink : null;
        requestId=UuidV7.isCanonical(requestId) ? requestId : null;
        toolName = TOOLS.contains(toolName == null ? "" : toolName) ? toolName : "unknown_tool";
        state = Set.of("RUNNING", "FINISHED", "UNKNOWN").contains(state == null ? "" : state) ? state : "UNKNOWN";
        outcome = OUTCOMES.contains(outcome == null ? "" : outcome) ? outcome : "UNKNOWN";
        availability = AVAILABILITIES.contains(availability == null ? "" : availability) ? availability : null;
        count = Math.max(0, Math.min(1000, count));
        elapsedMs = elapsedMs == null ? null : Math.max(0, elapsedMs);
        try {
            startedAt = startedAt == null ? null : Instant.parse(startedAt).toString();
        } catch (RuntimeException ignored) {
            startedAt = null;
        }
    }

    public static ToolActivity started(int sequence, String toolName) {
        return new ToolActivity(sequence, toolName, "RUNNING", "UNKNOWN", Instant.now().toString(),
                null, 0, null, false);
    }

    public ToolActivity finished(String json, long durationMs) {
        try {
            JsonNode result = JSON.readTree(json);
            String status = result.path("status").asText("UNKNOWN");
            int returned = result.path("facts").isArray() ? result.path("facts").size()
                    : result.path("items").isArray() ? result.path("items").size()
                    : result.path("messages").isArray() ? result.path("messages").size() : 0;
            if ("read_session".equals(toolName) && result.path("messages").isArray()) {
                returned = 0;
                for (JsonNode message : result.path("messages")) if (message.has("content")) returned++;
            }
            if ("CREATED".equals(status) || "UPDATED".equals(status)) returned = 1;
            return new ToolActivity(sequence, toolName, "FINISHED", status, startedAt, durationMs, returned,
                    result.path("availability").asText(null), result.path("incomplete").asBoolean(false),
                    result.path("sessionLink").asText(null),result.path("requestId").asText(null));
        } catch (Exception ignored) {
            return unknown();
        }
    }

    public ToolActivity unknown() {
        return new ToolActivity(sequence, toolName, "UNKNOWN", "UNKNOWN", startedAt, null, 0, null, incomplete);
    }

    /** 历史/恢复只取白名单字段；没有活跃执行者时不能继续显示加载状态。 */
    public static List<ToolActivity> readMeta(String json, boolean active) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode rows = JSON.readTree(json).path("toolActivities");
            if (!rows.isArray()) return List.of();
            List<ToolActivity> result = new ArrayList<>();
            for (JsonNode row : rows) {
                if (result.size() >= MAX_ENTRIES) break;
                ToolActivity item = new ToolActivity(result.size() + 1, row.path("toolName").asText(),
                        row.path("state").asText(), row.path("outcome").asText(), row.path("startedAt").asText(null),
                        row.path("elapsedMs").isIntegralNumber() ? row.path("elapsedMs").longValue() : null,
                        row.path("count").asInt(0), row.path("availability").asText(null),
                        row.path("incomplete").asBoolean(false),row.path("sessionLink").asText(null),row.path("requestId").asText(null));
                result.add(!active && "RUNNING".equals(item.state()) ? item.unknown() : item);
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
