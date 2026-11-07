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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模型面向的只读记忆列表工具。只返回标题、作用域与更新时间；
 * 授权、作用域、库版本与索引可用性仍由 {@link ChatContextService} 统一把关。
 */
@Component
public class MemoryListTool implements AiService.MemoryTool {
    public static final String NAME = "list_memories";
    public static final int MAX_ARGUMENT_BYTES = 4096;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 20;
    public static final int DEFAULT_LIMIT = 10;

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChatContextService contexts;

    public MemoryListTool(ChatContextService contexts) {
        this.contexts = contexts;
    }

    @Override
    public AiUpstreamClient.ToolDefinition definition() {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "最多返回多少条记忆标题，默认 " + DEFAULT_LIMIT + "。");
        limit.put("minimum", MIN_LIMIT);
        limit.put("maximum", MAX_LIMIT);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "string");
        scope.put("description", "GLOBAL 只列全局，NOTEBOOK 只列当前笔记本，ALL 列两者，默认 ALL。");
        scope.put("enum", List.of("GLOBAL", "NOTEBOOK", "ALL"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("limit", limit);
        properties.put("scope", scope);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", properties);
        return new AiUpstreamClient.ToolDefinition(NAME, "只读列出当前会话可见的长期记忆标题。", parameters);
    }

    @Override
    public Map<String, Object> execute(ChatContextPlan plan, String rawArguments, boolean memoryUseRequested,
                                       String userText) {
        Arguments arguments = parse(rawArguments);
        if (arguments == null) {
            return result(ChatContextService.MemoryLookupStatus.INVALID_ARGUMENT.name(), null, List.of(), false);
        }
        return render(contexts.listMemoriesForTool(plan, memoryUseRequested, arguments.limit(), arguments.scope()));
    }

    @Override
    public String resultKey() {
        return "items";
    }

    @Override
    public int maxItemsPerCall() {
        return MAX_LIMIT;
    }

    private record Arguments(int limit, String scope) {
    }

    private static Arguments parse(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) return null;
        if (rawArguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return null;
        try (JsonParser parser = JSON.createParser(rawArguments)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) return null;
            if (parser.nextToken() != null) return null;
            int limit = DEFAULT_LIMIT;
            String scope = "ALL";
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                switch (field.getKey()) {
                    case "limit" -> {
                        JsonNode value = field.getValue();
                        if (!value.isIntegralNumber() || !value.canConvertToInt()) return null;
                        limit = value.intValue();
                        if (limit < MIN_LIMIT || limit > MAX_LIMIT) return null;
                    }
                    case "scope" -> {
                        JsonNode value = field.getValue();
                        if (!value.isTextual() || value.textValue() == null || value.textValue().isBlank()) {
                            return null;
                        }
                        scope = value.textValue().trim().toUpperCase(Locale.ROOT);
                        if (!"GLOBAL".equals(scope) && !"NOTEBOOK".equals(scope) && !"ALL".equals(scope)) {
                            return null;
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return new Arguments(limit, scope);
        } catch (IOException error) {
            return null;
        }
    }

    private static Map<String, Object> render(ChatContextService.MemoryListing listing) {
        return result(listing.status().name(), listing.availability(), listing.items(), listing.incomplete());
    }

    private static Map<String, Object> result(String status, ContextMemorySource.Availability availability,
                                              List<ContextMemorySource.ListedItem> items, boolean incomplete) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (availability != null) body.put("availability", availability.name());
        body.put("items", itemRows(items));
        body.put("incomplete", incomplete);
        return Collections.unmodifiableMap(body);
    }

    private static List<Map<String, Object>> itemRows(List<ContextMemorySource.ListedItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ContextMemorySource.ListedItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", item.title());
            row.put("scope", item.scopeType() + ":" + item.scopeId());
            row.put("updatedAt", item.updatedAtEpochMilli() == null ? null
                    : Instant.ofEpochMilli(item.updatedAtEpochMilli()).toString());
            rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }
}
