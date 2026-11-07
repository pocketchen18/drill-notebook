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
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模型面向的记忆新增工具。参数 schema 与严格解析留在本类；
 * 授权、作用域、额度、去重与证据写入统一由 {@link ChatContextService} 把关。
 */
@Component
public class MemoryCreateTool implements AiService.MemoryTool {
    public static final String NAME = "create_memory";
    public static final int MAX_ARGUMENT_BYTES = 4096;
    public static final int MAX_TEXT_CODE_POINTS = 1000;
    private static final List<String> KINDS = List.of("PREFERENCE", "GOAL", "BACKGROUND", "TASK");

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChatContextService contexts;

    public MemoryCreateTool(ChatContextService contexts) {
        this.contexts = contexts;
    }

    @Override
    public AiUpstreamClient.ToolDefinition definition() {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "string");
        text.put("description", "要长期记住的内容，最多 " + MAX_TEXT_CODE_POINTS + " 个字符。");
        text.put("minLength", 1);
        text.put("maxLength", MAX_TEXT_CODE_POINTS);
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("description", "记忆类别。");
        kind.put("enum", KINDS);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "string");
        scope.put("description", "写入范围；省略时沿用当前会话范围。GLOBAL 会话不能指定 NOTEBOOK。");
        scope.put("enum", List.of("GLOBAL", "NOTEBOOK"));
        Map<String, Object> expiry = new LinkedHashMap<>();
        expiry.put("type", "string");
        expiry.put("description", "可选的 ISO-8601 UTC 到期时间；省略表示长期有效。");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", text);
        properties.put("kind", kind);
        properties.put("scope", scope);
        properties.put("expiry", expiry);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("required", List.of("text", "kind"));
        parameters.put("properties", properties);
        return new AiUpstreamClient.ToolDefinition(NAME,
                "把用户明确希望长期保留的个人事实新增为记忆。用户说记住、加入记忆或保存时必须调用；"
                        + "text 尽量保留用户原话，不要自行改写成第三人称或近义句；归一值相同的内容不会重复创建。"
                        + "如果这个主题之前被删除过，只有用户本轮明确要求记住时才会重新启用。", parameters);
    }

    @Override
    public Map<String, Object> execute(ChatContextPlan plan, String rawArguments, boolean memoryUseRequested,
                                       String userText) {
        return execute(plan, rawArguments, memoryUseRequested, userText, 0L, 0L);
    }

    @Override
    public Map<String, Object> execute(ChatContextPlan plan, String rawArguments, boolean memoryUseRequested,
                                       String userText, long turnId, long userMessageId) {
        Arguments arguments = parse(rawArguments);
        if (arguments == null) {
            return result(ChatContextService.MemoryWriteStatus.INVALID_ARGUMENT, null, null, null, null);
        }
        ChatContextService.MemoryWrite write = contexts.createMemoryForTool(plan, memoryUseRequested,
                arguments.text(), arguments.kind(), arguments.scope(), arguments.expiry(), userText, turnId,
                userMessageId);
        return result(write.status(), write.memoryId(), write.version(), write.availability(), write.reason());
    }

    @Override
    public boolean mutatesMemory() {
        return true;
    }

    @Override
    public String resultKey() {
        return "created";
    }

    private record Arguments(String text, String kind, String scope, String expiry) {
    }

    private static Arguments parse(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) return null;
        if (rawArguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return null;
        try (JsonParser parser = JSON.createParser(rawArguments)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) return null;
            if (parser.nextToken() != null) return null;
            String text = null;
            String kind = null;
            String scope = null;
            String expiry = null;
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                switch (field.getKey()) {
                    case "text" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        text = value.textValue();
                        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) return null;
                    }
                    case "kind" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null) return null;
                        kind = value.textValue().trim().toUpperCase(Locale.ROOT);
                        if (!KINDS.contains(kind)) return null;
                    }
                    case "scope" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        scope = value.textValue().trim().toUpperCase(Locale.ROOT);
                        if (!"GLOBAL".equals(scope) && !"NOTEBOOK".equals(scope)) return null;
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
            if (text == null || kind == null) return null;
            return new Arguments(text, kind, scope, expiry);
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
