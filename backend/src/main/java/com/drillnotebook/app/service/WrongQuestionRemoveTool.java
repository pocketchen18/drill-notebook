package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import java.util.*;
import org.springframework.stereotype.Component;

/** 「移出待巩固」与「删除原题」是两种语义：本工具只改错题成员状态，原题与作答记录保留。 */
@Component
public class WrongQuestionRemoveTool implements AiService.ChatTool {
    private final MaterialDeleteService service;
    public WrongQuestionRemoveTool(MaterialDeleteService service) { this.service = service; }
    @Override public AiUpstreamClient.ToolDefinition definition() {
        var properties = new LinkedHashMap<String,Object>();
        properties.put("questionId", Map.of("type", "integer", "minimum", 1));
        properties.put("operationId", Map.of("type", "string", "pattern", "^[a-zA-Z0-9_-]{1,80}$"));
        properties.put("expectedVersion", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$", "description", "read_wrong_questions 读取该题返回的 updateVersion。"));
        return new AiUpstreamClient.ToolDefinition("remove_wrong_question", "仅按当前用户明确要求，将一道题移出错题本（不再待巩固）。"
                + "不删除原题，不修改题目内容与任何作答记录；之后再次答错会按规则重新计入。先用 read_wrong_questions 读取目标取得 updateVersion。"
                + "REMOVED 表示已移出，NO_CHANGE 表示该题本就不在错题本，DUPLICATE 表示原操作已处理；CONFLICT 须重新读取核对。"
                + "重试复用 operationId 及全部参数；其余状态不得声称已移出。用户要求删除题目本身时不要使用本工具。",
                Map.of("type", "object", "additionalProperties", false, "required", List.of("questionId", "operationId", "expectedVersion"), "properties", properties));
    }
    @Override public Map<String,Object> execute(ChatContextPlan plan, String raw, boolean memory, String user) { return MaterialReadService.error("REJECTED"); }
    @Override public Map<String,Object> execute(ChatContextPlan plan, String raw, boolean memory, String user, AiService.ToolInvocation call) {
        final MaterialDeleteService.Input input;
        try { input = MaterialDeleteService.parse(raw, MaterialDeleteService.WRONG); }
        catch (IllegalArgumentException error) { return MaterialReadService.error("INVALID_ARGUMENT"); }
        return service.delete(plan, call, input);
    }
    @Override public boolean writes() { return true; }
    @Override public String resultKey() { return "items"; }
    @Override public int maxItemsPerCall() { return 1; }
}
