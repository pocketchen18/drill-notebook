package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SessionListTool implements AiService.ChatTool {
    public static final String NAME = "list_sessions";
    private final SessionLookupService sessions;
    private SessionDispatchService dispatch;
    public SessionListTool(SessionLookupService sessions) { this.sessions = sessions; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setDispatch(SessionDispatchService dispatch) { this.dispatch=dispatch; }

    @Override public AiUpstreamClient.ToolDefinition definition() {
        return new AiUpstreamClient.ToolDefinition(NAME,
                "只读查找本地历史会话标题。按标题关键词筛选，按 ID 从新到旧分页；不读取正文、不含当前会话。"
                        + "需要内容时用 read_session。记忆开关关闭时也可使用。",
                Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", 200,
                                "description", "标题关键词（字面子串），省略时列全部。"),
                        "beforeSessionId", Map.of("type", "integer", "minimum", 1,
                                "description", "续页使用上次的 nextBeforeSessionId，并保持同一 query/includeArchived。"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20,
                                "description", "默认 10，最多 20。"),
                        "includeArchived", Map.of("type", "boolean", "description", "默认 false，true 时包含归档。"))));
    }

    @Override public Map<String, Object> execute(ChatContextPlan plan, String raw, boolean memoryUse, String userText) {
        var args = SessionToolArguments.parse(raw, Set.of("query", "beforeSessionId", "limit", "includeArchived"));
        if (args == null) return SessionLookupService.error("INVALID_ARGUMENT", "items");
        try {
            var result = sessions.list(plan, SessionToolArguments.text(args, "query", "", 200),
                    SessionToolArguments.number(args, "beforeSessionId", Long.MAX_VALUE, Long.MAX_VALUE),
                    (int) SessionToolArguments.number(args, "limit", 10, 20),
                    SessionToolArguments.flag(args, "includeArchived"));
            if(dispatch!=null && result.get("items") instanceof java.util.List<?> items) {
                var states=(java.util.Collection<?>)dispatch.runtime(null).get("sessions");
                for(var item:items) if(item instanceof Map<?,?> row) {
                    @SuppressWarnings("unchecked") var writable=(Map<String,Object>)row;
                    for(var state:states) if(state instanceof Map<?,?> runtime && java.util.Objects.equals(row.get("sessionId"),runtime.get("sessionId"))) {
                        writable.put("runStatus",runtime.get("status")); writable.put("queueCount",runtime.get("queueCount"));
                    }
                }
            }
            return result;
        } catch (IllegalArgumentException error) { return SessionLookupService.error("INVALID_ARGUMENT", "items"); }
    }

    @Override public String resultKey() { return "items"; }
    @Override public int maxItemsPerCall() { return 20; }
}
