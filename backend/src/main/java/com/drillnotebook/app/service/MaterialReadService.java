package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.MaterialReadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MaterialReadService {
    private final MaterialReadRepository materials;
    private final MaterialText text;
    public MaterialReadService(MaterialReadRepository materials, ObjectMapper json) {
        this.materials = materials; this.text = new MaterialText(json);
    }

    public Map<String, Object> execute(String name, JsonNode args, AiService.ToolInvocation invocation) {
        if (args == null) return error("INVALID_ARGUMENT");
        try {
            int offset = number(args, "offset", 0, 0, 10_000_000);
            int limit = number(args, "limit", 10, 1, 20);
            int maxChars = number(args, "maxChars", 4000, 1, 8000);
            String version = string(args, "version", "", 64);
            Long notebook = id(args, "notebookId"), bank = id(args, "bankId");
            if (notebook != null && !materials.exists("notebook", notebook)) return error("NOT_FOUND");
            if (bank != null && !materials.exists("bank", bank)) return error("NOT_FOUND");
            if (notebook != null && bank != null) throw new IllegalArgumentException();
            String query = string(args, "query", "", 200);
            if ("read_current_page".equals(name)) {
                String request = string(args, "requestId", invocation == null ? "" : invocation.requestId(), 36);
                PageSnapshot snapshot = invocation == null ? null : invocation.pageSnapshots().get(request);
                if (snapshot == null) return error("UNAVAILABLE");
                var row = new LinkedHashMap<>(snapshot.data());
                for (var target : Map.of("pageId", "note", "notebookId", "notebook", "pointId", "knowledge", "bankId", "bank", "questionId", "question").entrySet())
                    if (row.get(target.getKey()) instanceof Number n && !materials.exists(target.getValue(), n.longValue())) return error("NOT_FOUND");
                row.put("requestId", request);
                row.put("snapshot", true);
                for (String key : List.of("pageId", "pointId", "questionId", "notebookId", "bankId")) {
                    if (row.get(key) instanceof Number n) {
                        row.put("sourceLink", link(switch(key) { case "pageId" -> "note"; case "pointId" -> "knowledge";
                            case "questionId" -> "question"; case "notebookId" -> "notebook"; default -> "bank"; }, n.longValue()));
                        break;
                    }
                }
                String content = Objects.toString(row.remove("markdown"), "");
                if (content.isBlank()) row.put("notice", "当前页面没有可读取的业务正文；可按需查找已保存资料或请用户附加内容。");
                return content(row, content, offset, maxChars, version, Boolean.TRUE.equals(row.get("truncated")));
            }
            Long detailId = switch (name) {
                case "read_notebook" -> id(args, "pageId");
                case "read_knowledge" -> id(args, "pointId");
                case "read_question_bank", "read_wrong_questions" -> id(args, "questionId");
                default -> null;
            };
            if (detailId != null) {
                if (args.has("limit") || args.has("query")) throw new IllegalArgumentException();
                String type = switch (name) { case "read_notebook" -> "note"; case "read_knowledge" -> "knowledge"; default -> "question"; };
                var row = materials.detail(type, detailId);
                if (row == null || notebook != null && !same(row.get("notebookId"), notebook)
                        || bank != null && !same(row.get("bankId"), bank)) return error("NOT_FOUND");
                if ("read_wrong_questions".equals(name)) {
                    var matches = materials.list("wrong", "", null, bank, detailId, 0, 1);
                    if (matches.isEmpty()) return error("NOT_FOUND");
                    row.putAll(stats(matches.get(0))); type = "wrong";
                }
                String content;
                String updateVersion = MaterialVersion.of(type, row);
                if ("note".equals(type)) content = text.note(Objects.toString(row.remove("content"), ""));
                else if ("knowledge".equals(type)) {
                    String variant = string(args, "variant", "current", 10);
                    if (!Set.of("current", "original", "summary").contains(variant)) throw new IllegalArgumentException();
                    String current = Objects.toString(row.remove("content"), "");
                    content = "current".equals(variant) ? current : materials.knowledgeVersion(detailId, variant);
                    if (content == null) return error("NOT_FOUND");
                    row.put("variant", variant);
                    if (!"current".equals(variant)) updateVersion = null;
                } else {
                    content = text.question(row);
                    row.put("title", clip(Objects.toString(row.get("stem"), ""), 200));
                    row.keySet().removeAll(Set.of("stem", "options", "answer", "analysis"));
                }
                row.put("type", type); row.put("sourceLink", link(type, detailId));
                if (updateVersion != null) row.put("updateVersion", updateVersion);
                return content(row, content, offset, maxChars, version, false);
            }
            if (args.has("maxChars") || args.has("version") || args.has("variant")) throw new IllegalArgumentException();
            String type = switch (name) {
                case "search_materials" -> string(args, "type", "all", 10);
                case "read_notebook" -> notebook == null ? "notebook" : "note";
                case "read_knowledge" -> "knowledge";
                case "read_question_bank" -> bank == null ? "bank" : "question";
                case "read_wrong_questions" -> "wrong";
                default -> throw new IllegalArgumentException();
            };
            if (!Set.of("all", "notebook", "note", "knowledge", "bank", "question", "wrong").contains(type)) throw new IllegalArgumentException();
            if (notebook != null && !Set.of("all", "notebook", "note").contains(type)
                    || bank != null && Set.of("notebook", "note").contains(type)) throw new IllegalArgumentException();
            var rows = materials.list(type, query, notebook, bank, null, offset, limit + 1);
            boolean more = rows.size() > limit;
            var items = rows.stream().limit(limit).map(raw -> {
                var row = new LinkedHashMap<>(raw);
                row.put("sourceLink", link((String) row.get("type"), ((Number) row.get("id")).longValue()));
                if ("wrong".equals(row.get("type"))) row.putAll(stats(row));
                else row.keySet().removeAll(Set.of("wrongCount", "attemptCount"));
                return row;
            }).toList();
            var result = new LinkedHashMap<String, Object>();
            result.put("status", items.isEmpty() ? "NO_MATCH" : "OK"); result.put("items", items);
            result.put("offset", offset); result.put("hasMore", more); result.put("incomplete", more);
            if ("read_notebook".equals(name) && notebook != null || "read_question_bank".equals(name) && bank != null) {
                String containerType = notebook != null ? "notebook" : "bank";
                long containerId = notebook != null ? notebook : bank;
                var container = materials.detail(containerType, containerId);
                if (container == null) return error("NOT_FOUND");
                container.put("updateVersion", MaterialVersion.of(containerType, container));
                container.put("type", containerType); container.put("sourceLink", link(containerType, containerId));
                container.put("truncated", container.values().stream().anyMatch(v -> v instanceof String s && s.codePointCount(0, s.length()) > 2000));
                container.replaceAll((k,v) -> v instanceof String s ? clip(s, 2000) : v);
                result.put("container", container);
            }
            if (more) result.put("nextOffset", offset + items.size());
            return result;
        } catch (IllegalArgumentException error) { return error("INVALID_ARGUMENT"); }
        catch (RuntimeException error) { return error("UNAVAILABLE"); }
    }

    private Map<String, Object> content(Map<String, Object> source, String body, int offset, int max,
                                         String expectedVersion, boolean truncated) {
        String version;
        try { version = PageSnapshot.hash(new ObjectMapper().writeValueAsString(source) + "\n" + body); }
        catch (Exception error) { return error("UNAVAILABLE"); }
        if (offset > 0 && expectedVersion.isEmpty()) return error("INVALID_ARGUMENT");
        if (!expectedVersion.isEmpty() && !version.equals(expectedVersion)) return error("STALE");
        int total = body.codePointCount(0, body.length());
        if (offset > total) return error("INVALID_ARGUMENT");
        int end = Math.min(total, offset + max);
        var row = new LinkedHashMap<>(source);
        // 所有非正文描述有界，完整正文的版本计算仍包含完整元数据。
        row.replaceAll((k,v) -> v instanceof String s ? clip(s, "selection".equals(k) ? 2000 : 500) : v);
        row.put("text", body.substring(body.offsetByCodePoints(0, offset), body.offsetByCodePoints(0, end)));
        row.put("version", version); row.put("offset", offset); row.put("endOffset", end); row.put("totalChars", total);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "OK"); result.put("items", List.of(row)); result.put("version", version);
        result.put("hasMore", end < total); result.put("incomplete", truncated || end < total);
        if (end < total) result.put("nextOffset", end);
        if (truncated) result.put("notice", "提交时页面快照已截短；读取完整已保存资料请使用其 ID 调用对应资料工具。");
        return result;
    }
    private static boolean same(Object value, long expected) { return value instanceof Number n && n.longValue()==expected; }
    private static Map<String, Object> stats(Map<String, Object> row) {
        long wrong = ((Number)row.get("wrongCount")).longValue(), attempts = ((Number)row.get("attemptCount")).longValue();
        return Map.of("wrongCount", wrong, "attemptCount", attempts, "errorRate", attempts==0 ? 0 : Math.round(wrong*10000.0/attempts)/10000.0);
    }
    static String link(String type, long id) { return "drill-notebook://material/" + type + "/" + id; }
    static String clip(String value, int max) { return RetrievalService.truncateCodepoints(value, max); }
    static Map<String, Object> error(String status) { return Map.of("status", status, "items", List.of(), "incomplete", true); }
    private static Long id(JsonNode args, String key) { return args.has(key) ? SessionToolArguments.number(args, key, 0, Long.MAX_VALUE) : null; }
    private static int number(JsonNode args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        var n = args.get(key);
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue()<min || n.intValue()>max) throw new IllegalArgumentException();
        return n.intValue();
    }
    private static String string(JsonNode args, String key, String fallback, int max) {
        if (!args.has(key)) return fallback;
        if (!args.get(key).isTextual()) throw new IllegalArgumentException();
        String value = args.get(key).textValue().trim();
        if (value.codePointCount(0, value.length()) > max) throw new IllegalArgumentException();
        return value;
    }
}
