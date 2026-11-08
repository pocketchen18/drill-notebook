package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SessionSendTool implements AiService.ChatTool {
    private final SessionDispatchService dispatch;
    public SessionSendTool(SessionDispatchService dispatch) { this.dispatch=dispatch; }
    @Override public AiUpstreamClient.ToolDefinition definition() {
        return new AiUpstreamClient.ToolDefinition("send_message",
                "仅在当前用户授权向指定会话发送时发送文本；session 必须是准确链接/ref/ID。忙碌时按顺序排队，不打断。返回 SENT/QUEUED 仅说明受理，用 read_session + requestId 获取本次回复。",
                Map.of("type","object","additionalProperties",false,"required",List.of("session","message"),"properties",Map.of(
                        "session",Map.of("type","string","minLength",1,"maxLength",200),
                        "message",Map.of("type","string","minLength",1,"maxLength",2000))));
    }
    @Override public Map<String,Object> execute(ChatContextPlan p,String raw,boolean use,String text) { return SessionDispatchService.error("REJECTED"); }
    @Override public Map<String,Object> execute(ChatContextPlan p,String raw,boolean use,String text,AiService.ToolInvocation invocation) {
        var args=SessionToolArguments.parse(raw,Set.of("session","message"),maxArgumentBytes());
        if(args==null || !args.has("message") || !args.has("session")) return SessionDispatchService.error("INVALID_ARGUMENT");
        try { return dispatch.dispatch(p,invocation,SessionToolArguments.text(args,"session",null,200),
                SessionToolArguments.text(args,"message",null,2000),null); }
        catch(ChatTurnRejectException e) { return SessionDispatchService.error(e.code()); }
        catch(IllegalArgumentException e) { return SessionDispatchService.error("INVALID_ARGUMENT"); }
    }
    @Override public boolean writes() { return true; }
    @Override public int maxArgumentBytes() { return 16384; }
}
