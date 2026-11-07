package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模型面向的记忆更新工具。优先用 {@code currentText} 指代检索结果里的现有正文，
 * 也支持 create_memory 返回的 {@code memoryId + expectedVersion} 做精确 CAS；
 * 授权、作用域、版本与敏感内容仍由 {@link ChatContextService} 统一把关。
 */
@Component
public class MemoryUpdateTool implements AiService.MemoryTool {
    public static final String NAME = "update_memory";
    public static final int MAX_ARGUMENT_BYTES = 4096;
    public static final int MAX_TEXT_CODE_POINTS = 1000;
    public static final int MAX_MEMORY_ID_CODE_POINTS = 64;

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChatContextService contexts;

    public MemoryUpdateTool(ChatContextService contexts) {
        this.contexts = contexts;
    }

    @Override
    public AiUpstreamClient.ToolDefinition definition() {
        Map<String, Object> memoryId = new LinkedHashMap<>();
        memoryId.put("type", "string");
        memoryId.put("description", "要更新的记忆 ID；必须与 expectedVersion 一起使用。");
        memoryId.put("maxLength", MAX_MEMORY_ID_CODE_POINTS);
        Map<String, Object> expectedVersion = new LinkedHashMap<>();
        expectedVersion.put("type", "integer");
        expectedVersion.put("description", "读取到的记忆版本；与 memoryId 一起构成乐观锁。");
        expectedVersion.put("minimum", 1);
        Map<String, Object> currentText = new LinkedHashMap<>();
        currentText.put("type", "string");
        currentText.put("description", "要更新的现有记忆正文，先用 search_memories 查到后原样传入；与 memoryId 二选一。");
        currentText.put("minLength", 1);
        currentText.put("maxLength", MAX_TEXT_CODE_POINTS);
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "string");
        text.put("description", "更新后的正文，最多 " + MAX_TEXT_CODE_POINTS + " 个字符。");
        text.put("minLength", 1);
        text.put("maxLength", MAX_TEXT_CODE_POINTS);
        Map<String, Object> expiry = new LinkedHashMap<>();
        expiry.put("type", "string");
        expiry.put("description", "可选的 ISO-8601 UTC 到期时间；省略时保留原值。");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("memoryId", memoryId);
        properties.put("expectedVersion", expectedVersion);
        properties.put("currentText", currentText);
        properties.put("text", text);
        properties.put("expiry", expiry);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("required", java.util.List.of("text"));
        parameters.put("properties", properties);
        return new AiUpstreamClient.ToolDefinition(NAME,
                "更新已有记忆。先用 search_memories 找到目标，把返回的 text 原样作为 currentText；"
                        + "纠正时要更新被纠正的那条旧事实，不要把新值写到另一条相似记忆上；"
                        + "也可用 create_memory 返回的 memoryId + expectedVersion 精确更新。",
                parameters);
    }

    @Override
    public Map<String, Object> execute(ChatContextPlan plan, String rawArguments, boolean memoryUseRequested,
                                       String userText) {
        Arguments arguments = parse(rawArguments);
        if (arguments == null) {
            return result(ChatContextService.MemoryWriteStatus.INVALID_ARGUMENT, null, null, null, null);
        }
        ChatContextService.MemoryWrite write = contexts.updateMemoryForTool(plan, memoryUseRequested,
                arguments.memoryId(), arguments.expectedVersion(), arguments.currentText(), arguments.text(),
                arguments.expiry());
        return result(write.status(), write.memoryId(), write.version(), write.availability(), write.reason());
    }

    @Override
    public boolean mutatesMemory() {
        return true;
    }

    @Override
    public String resultKey() {
        return "updated";
    }

    private record Arguments(String memoryId, Integer expectedVersion, String currentText, String text, String expiry) {
    }

    private static Arguments parse(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) return null;
        if (rawArguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return null;
        try (JsonParser parser = JSON.createParser(rawArguments)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) return null;
            if (parser.nextToken() != null) return null;
            String memoryId = null;
            Integer expectedVersion = null;
            String currentText = null;
            String text = null;
            String expiry = null;
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                switch (field.getKey()) {
                    case "memoryId" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        memoryId = value.textValue().trim();
                        if (memoryId.codePointCount(0, memoryId.length()) > MAX_MEMORY_ID_CODE_POINTS) return null;
                    }
                    case "expectedVersion" -> {
                        JsonNode value = field.getValue();
                        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
                            return null;
                        }
                        expectedVersion = value.intValue();
                    }
                    case "currentText" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        currentText = value.textValue();
                        if (currentText.codePointCount(0, currentText.length()) > MAX_TEXT_CODE_POINTS) return null;
                    }
                    case "text" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        text = value.textValue();
                        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) return null;
                    }
                    case "expiry" -> {
                        JsonNode value = field.getValue();
                        if (value.isNull()) {
                            expiry = null;
                            break;
                        }
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        expiry = value.textValue().trim();
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (text == null) return null;
            boolean byId = memoryId != null;
            boolean byText = currentText != null;
            if (byId == byText) return null;
            if (byId && expectedVersion == null) return null;
            if (byText && expectedVersion != null) return null;
            return new Arguments(memoryId, expectedVersion, currentText, text, expiry);
        } catch (IOException error) {
            return null;
        }
    }

    private static Map<String, Object> result(ChatContextService.MemoryWriteStatus status, String memoryId,
                                              Integer version, ContextMemorySource.Availability availability,
                                              String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.name());
        if (memoryId != null) body.put("memoryId", memoryId);
        if (version != null) body.put("version", version);
        if (availability != null) body.put("availability", availability.name());
        if (reason != null) body.put("reason", reason);
        return Collections.unmodifiableMap(body);
    }
}
