package com.drillnotebook.app.service;

import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.model.RequestContextAudit.Category;
import com.drillnotebook.app.model.RequestContextAudit.Item;
import com.drillnotebook.app.model.RequestContextAudit.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.PriorityQueue;

/** 从最终请求形状提取组成估算；只输出固定分类、工具名和数字，正文不进入持久摘要。 */
public final class RequestContextAnalyzer {
    private final TokenEstimator estimator;
    private final ObjectMapper mapper;

    public RequestContextAnalyzer(ObjectMapper mapper) { this.mapper = mapper; estimator = new TokenEstimator(mapper); }

    public RequestContextAudit analyze(Map<String, Object> request, int window, String requestKind, Set<String> toolNames) {
        var groups = new EnumMap<Kind, Group>(Kind.class);
        Object messages = request.getOrDefault("messages", request.get("input"));
        var calls = new HashMap<String, String>();
        if (messages instanceof List<?> list) for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> message)) continue;
            if (message.get("tool_calls") instanceof List<?> toolCalls) for (Object call : toolCalls) {
                if (call instanceof Map<?, ?> row && row.get("function") instanceof Map<?, ?> fn)
                    calls.put(String.valueOf(row.get("id")), safeTool(fn.get("name"), toolNames));
            }
            if (message.get("content") instanceof List<?> blocks) for (Object block : blocks) {
                if (block instanceof Map<?, ?> row && "tool_use".equals(row.get("type")))
                    calls.put(String.valueOf(row.get("id")), safeTool(row.get("name"), toolNames));
            }
        }
        if (request.get("system") != null) add(groups, Kind.SYSTEM, "system", null, request.get("system"));
        if (request.get("tools") instanceof List<?> tools) {
            int index = 0;
            for (Object raw : tools) {
                Object name = raw instanceof Map<?, ?> tool
                        ? tool.get("function") instanceof Map<?, ?> fn ? fn.get("name") : tool.get("name") : null;
                if (name == null && raw instanceof Map<?, ?> tool) name = tool.get("type");
                add(groups, Kind.TOOL_DEFINITIONS, "definition-" + index++, safeTool(name, toolNames), raw);
            }
        }
        if (messages instanceof List<?> list) {
            int messageIndex = 0;
            for (Object raw : list) {
                String key = "message-" + messageIndex++;
                if (!(raw instanceof Map<?, ?> message)) { add(groups, Kind.OTHER, key, null, raw); continue; }
                String role = String.valueOf(message.get("role"));
                String resultName = calls.get(String.valueOf(message.get("tool_call_id")));
                Object content = message.get("content");
                if (content instanceof List<?> blocks) {
                    int blockIndex = 0;
                    for (Object rawBlock : blocks) {
                        String blockKey = key + "-" + blockIndex++;
                        if (rawBlock instanceof Map<?, ?> block) {
                            String type = String.valueOf(block.get("type"));
                            Kind kind = switch (type) {
                                case "image", "image_url" -> Kind.IMAGE;
                                case "tool_result" -> Kind.TOOL_RESULTS;
                                case "tool_use" -> Kind.TOOL_CALLS;
                                default -> category(role, block.get("text"));
                            };
                            String name = kind == Kind.TOOL_RESULTS ? calls.get(String.valueOf(block.get("tool_use_id")))
                                    : kind == Kind.TOOL_CALLS ? safeTool(block.get("name"), toolNames) : resultName;
                            add(groups, kind, blockKey, name, block);
                        } else add(groups, category(role, rawBlock), blockKey, resultName, rawBlock);
                    }
                } else if (content != null) add(groups, category(role, content), key, resultName, content);
                if (message.get("tool_calls") instanceof List<?> toolCalls) {
                    int index = 0;
                    for (Object rawCall : toolCalls) {
                        String name = rawCall instanceof Map<?, ?> call && call.get("function") instanceof Map<?, ?> fn
                                ? safeTool(fn.get("name"), toolNames) : null;
                        add(groups, Kind.TOOL_CALLS, key + "-call-" + index++, name, rawCall);
                    }
                }
                if (message.get("anthropic_thinking") != null)
                    add(groups, Kind.ASSISTANT, key + "-thinking", null, message.get("anthropic_thinking"));
            }
        } else if (messages != null) add(groups, Kind.USER, "input", null, messages);
        var input = new LinkedHashMap<String, Object>();
        if (request.containsKey("system")) input.put("system", request.get("system"));
        if (request.containsKey("tools")) input.put("tools", request.get("tools"));
        input.put("messages", messages == null ? List.of() : messages);
        int pieces = groups.values().stream().mapToInt(group -> group.tokens).sum();
        int total = Math.max(pieces, estimator.estimateInput(input));
        if (total > pieces) groups.computeIfAbsent(Kind.OTHER, kind -> new Group()).add("envelope", null, total - pieces);
        List<Category> categories = new ArrayList<>();
        // 先给每类保留明细，再优先补齐工具结果、定义和调用；大组不能挤掉其它分类。
        var counts = new EnumMap<Kind, Integer>(Kind.class);
        groups.forEach((kind, group) -> counts.put(kind, Math.min(16, group.items.size())));
        int remaining = RequestContextAudit.MAX_ITEMS - counts.values().stream().mapToInt(Integer::intValue).sum();
        for (Kind kind : List.of(Kind.TOOL_RESULTS, Kind.TOOL_DEFINITIONS, Kind.TOOL_CALLS, Kind.ASSISTANT,
                Kind.USER, Kind.INJECTED_CONTEXT, Kind.SYSTEM, Kind.IMAGE, Kind.OTHER)) {
            Group group = groups.get(kind);
            if (group == null) continue;
            int added = Math.min(remaining, group.items.size() - counts.get(kind));
            counts.put(kind, counts.get(kind) + added); remaining -= added;
        }
        boolean truncated = false;
        for (var entry : groups.entrySet()) {
            Group group = entry.getValue();
            int shown = counts.get(entry.getKey());
            truncated |= shown < group.count;
            var items = group.items.stream().sorted(Comparator.comparingInt(Item::estimatedTokens).reversed()
                    .thenComparing(Item::key)).limit(shown).toList();
            categories.add(new Category(entry.getKey(), group.tokens, group.count, items));
        }
        return bounded(new RequestContextAudit(RequestContextAudit.VERSION, TokenEstimator.VERSION,
                String.valueOf(request.getOrDefault("model", "")), requestKind, total, window, categories, truncated));
    }

    /** 较长工具名也必须满足字节上限；裁去较小明细，分类计数与合计保持完整。 */
    private RequestContextAudit bounded(RequestContextAudit audit) {
        try {
            while (mapper.writeValueAsBytes(audit).length > RequestContextAudit.MAX_BYTES) {
                var trimmed = new ArrayList<Category>();
                boolean changed = false;
                for (Category category : audit.categories()) {
                    int keep = category.items().size() > 1 ? Math.max(1, category.items().size() * 3 / 4) : category.items().size();
                    changed |= keep < category.items().size();
                    trimmed.add(new Category(category.kind(), category.estimatedTokens(), category.itemCount(),
                            category.items().subList(0, keep)));
                }
                if (!changed) throw new IllegalArgumentException("请求上下文摘要元数据过大");
                audit = new RequestContextAudit(audit.schemaVersion(), audit.estimatorVersion(), audit.model(), audit.kind(),
                        audit.estimatedInputTokens(), audit.contextWindow(), trimmed, true);
            }
            return audit;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("请求上下文摘要无法序列化", error);
        }
    }

    private void add(Map<Kind, Group> groups, Kind kind, String key, String name, Object value) {
        groups.computeIfAbsent(kind, ignored -> new Group()).add(key, name, estimator.estimateInput(value));
    }

    private static Kind category(String role, Object value) {
        if (value instanceof String text && (text.startsWith("CONTEXT_DATA_V1") || text.startsWith("NOTEBOOK_RAG_V1")
                || text.contains("\"kind\":\"CONTEXT_DATA_V1\""))) return Kind.INJECTED_CONTEXT;
        return switch (role) {
            case "system", "developer" -> Kind.SYSTEM;
            case "assistant" -> Kind.ASSISTANT;
            case "tool" -> Kind.TOOL_RESULTS;
            case "user" -> Kind.USER;
            default -> Kind.OTHER;
        };
    }

    private static String safeTool(Object raw, Set<String> toolNames) {
        return raw instanceof String name && toolNames.contains(name) && name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") ? name : null;
    }

    private static final class Group {
        int tokens, count;
        final PriorityQueue<Item> items = new PriorityQueue<>(Comparator.comparingInt(Item::estimatedTokens).thenComparing(Item::key));
        void add(String key, String name, int estimated) {
            tokens += estimated; count++;
            Item item = new Item(key, name, estimated);
            if (items.size() < RequestContextAudit.MAX_ITEMS) items.add(item);
            else if (items.peek().estimatedTokens() < estimated) { items.poll(); items.add(item); }
        }
    }
}
