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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模型面向的只读记忆检索工具。参数校验、工具 schema 与安全字段投影留在本类；
 * 授权、作用域、库版本与检索实现仍由 {@link ChatContextService} 统一把关。
 */
@Component
public class MemorySearchTool implements AiService.MemoryTool {
    public static final String NAME = "search_memories";
    public static final int MAX_ARGUMENT_BYTES = 4096;
    public static final int MAX_QUERY_CODE_POINTS = 200;

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChatContextService contexts;

    public MemorySearchTool(ChatContextService contexts) {
        this.contexts = contexts;
    }

    @Override
    public AiUpstreamClient.ToolDefinition definition() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "要检索的长期记忆关键词或问题，最多 200 个字符。");
        query.put("minLength", 1);
        query.put("maxLength", MAX_QUERY_CODE_POINTS);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("required", List.of("query"));
        parameters.put("properties", properties);
        return new AiUpstreamClient.ToolDefinition(NAME, "只读检索当前会话可见的长期记忆。", parameters);
    }

    @Override
    public Map<String, Object> execute(ChatContextPlan plan, String rawArguments, boolean memoryUseRequested,
                                       String userText) {
        String query = parseQuery(rawArguments);
        if (query == null) {
            return result(ChatContextService.MemoryLookupStatus.INVALID_ARGUMENT.name(), null, List.of(), false);
        }
        return render(contexts.readMemoriesForTool(plan, memoryUseRequested, query));
    }

    private static String parseQuery(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) return null;
        if (rawArguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return null;
        try (JsonParser parser = JSON.createParser(rawArguments)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) return null;
            if (parser.nextToken() != null) return null;
            if (root.size() != 1 || !root.has("query")) return null;
            JsonNode query = root.get("query");
            if (query == null || !query.isTextual()) return null;
            String text = query.textValue();
            if (text == null || text.isBlank()) return null;
            if (text.codePointCount(0, text.length()) > MAX_QUERY_CODE_POINTS) return null;
            return text;
        } catch (IOException error) {
            return null;
        }
    }

    private static Map<String, Object> render(ChatContextService.MemoryLookup lookup) {
        return result(lookup.status().name(), lookup.availability(), lookup.facts(), lookup.incomplete());
    }

    private static Map<String, Object> result(String status, ContextMemorySource.Availability availability,
                                              List<ContextMemorySource.Fact> facts, boolean incomplete) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (availability != null) body.put("availability", availability.name());
        body.put("facts", factRows(facts));
        body.put("incomplete", incomplete);
        return Collections.unmodifiableMap(body);
    }

    private static List<Map<String, Object>> factRows(List<ContextMemorySource.Fact> facts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ContextMemorySource.Fact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", fact.value());
            row.put("kind", fact.kind());
            row.put("scope", fact.scopeType() + ":" + fact.scopeId());
            row.put("expiry", fact.validUntilEpochMilli() == null ? null
                    : Instant.ofEpochMilli(fact.validUntilEpochMilli()).toString());
            rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }
}
