package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 模型面向的记忆删除工具。定位方式与 {@link MemoryUpdateTool} 一致；
 * 授权、作用域、版本与遗忘语义由 {@link ChatContextService} 统一把关。
 */
@Component
public class MemoryDeleteTool implements AiService.MemoryTool {
    public static final String NAME = "delete_memory";

    private final ChatContextService contexts;

    public MemoryDeleteTool(ChatContextService contexts) {
        this.contexts = contexts;
    }

    @Override
    public AiUpstreamClient.ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("currentText", Map.of("type", "string", "minLength", 1,
                "maxLength", MemoryUpdateTool.MAX_TEXT_CODE_POINTS,
                "description", "要删除的现有记忆正文，先用 search_memories 查到后原样传入；与 memoryId 二选一。"));
        properties.put("memoryId", Map.of("type", "string", "maxLength", MemoryUpdateTool.MAX_MEMORY_ID_CODE_POINTS,
                "description", "要删除的记忆 ID；必须与 expectedVersion 一起使用。"));
        properties.put("expectedVersion", Map.of("type", "integer", "minimum", 1,
                "description", "读取到的记忆版本；与 memoryId 一起构成乐观锁。"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", properties);
        return new AiUpstreamClient.ToolDefinition(NAME,
                "仅按当前用户明确的要求永久删除一条长期记忆，无法恢复；用户只是纠正内容时应使用 update_memory。"
                        + "先用 search_memories 找到目标，把返回的 text 原样作为 currentText；一次只删一条。"
                        + "AMBIGUOUS 表示有多条匹配，须请用户明确；仅 DELETED 表示已删除，NOT_FOUND 表示没有这条记忆。"
                        + "删除后该主题不会被自动重新记住，除非用户再次明确要求。",
                parameters);
    }

    @Override
    public Map<String, Object> execute(ChatContextPlan plan, String rawArguments, boolean memoryUseRequested,
                                       String userText) {
        JsonNode args = SessionToolArguments.parse(rawArguments, Set.of("currentText", "memoryId", "expectedVersion"));
        if (args == null) return result(ChatContextService.MemoryWrite.invalid());
        final String currentText;
        final String memoryId;
        final Integer expectedVersion;
        try {
            currentText = SessionToolArguments.text(args, "currentText", null, MemoryUpdateTool.MAX_TEXT_CODE_POINTS);
            memoryId = SessionToolArguments.text(args, "memoryId", null, MemoryUpdateTool.MAX_MEMORY_ID_CODE_POINTS);
            expectedVersion = args.has("expectedVersion")
                    ? (int) SessionToolArguments.number(args, "expectedVersion", 0, Integer.MAX_VALUE) : null;
        } catch (IllegalArgumentException error) {
            return result(ChatContextService.MemoryWrite.invalid());
        }
        boolean byText = currentText != null && !currentText.isBlank();
        boolean byId = memoryId != null && !memoryId.isBlank();
        if (byText == byId || (byId && expectedVersion == null) || (byText && expectedVersion != null)) {
            return result(ChatContextService.MemoryWrite.invalid());
        }
        return result(contexts.deleteMemoryForTool(plan, memoryUseRequested, memoryId, expectedVersion, currentText));
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public boolean forgetsMemory() {
        return true;
    }

    @Override
    public String resultKey() {
        return "items";
    }

    private static Map<String, Object> result(ChatContextService.MemoryWrite write) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", write.status().name());
        if (write.memoryId() != null) body.put("memoryId", write.memoryId());
        if (write.availability() != null) body.put("availability", write.availability().name());
        if (write.status() == ChatContextService.MemoryWriteStatus.DELETED) body.put(AiService.VAULT_CHANGED, true);
        body.put("items", List.of());
        return body;
    }
}
