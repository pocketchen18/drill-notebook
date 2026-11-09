package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Canonical, bounded inputs; only fields relevant to the selected material type are accepted. */
final class MaterialCreateArguments {
    static final int MAX_BYTES = 64 * 1024;
    static final Set<String> FIELDS = Set.of("type", "operationId", "title", "content", "notebookId", "parentPageId",
            "bankId", "description", "category", "tags", "headingPath", "questionIds", "questionId",
            "questionType", "stem", "options", "answer", "analysis", "difficulty", "chapter");
    private static final List<String> QUESTION_FIELDS = List.of("bankId", "questionType", "stem", "options",
            "answer", "analysis", "difficulty", "tags", "chapter");

    record Input(String operationId, String type, Map<String, Object> values) {
        String text(String key) { return (String) values.get(key); }
        Long id(String key) { return (Long) values.get(key); }
        @SuppressWarnings("unchecked") <T> List<T> list(String key) { return (List<T>) values.get(key); }
    }

    static Input parse(String raw) {
        JsonNode node = SessionToolArguments.parse(raw, FIELDS, MAX_BYTES);
        if (node == null) throw invalid("参数必须是合法 JSON 对象，且不能重复或超长");
        String type = text(node, "type", true, 20);
        String operation = text(node, "operationId", true, 80);
        if (!operation.matches("[a-zA-Z0-9_-]{1,80}")) throw invalid("operationId 只能包含字母、数字、下划线或短横线");
        Set<String> allowed = new HashSet<>(Set.of("type", "operationId"));
        switch (type) {
            case "notebook" -> allowed.add("title");
            case "note" -> allowed.addAll(List.of("notebookId", "parentPageId", "title", "content"));
            case "knowledge" -> allowed.addAll(List.of("bankId", "title", "content", "category", "tags", "headingPath", "questionIds"));
            case "bank" -> allowed.addAll(List.of("title", "description"));
            case "question" -> allowed.addAll(QUESTION_FIELDS);
            case "wrong" -> { if (node.has("questionId")) allowed.add("questionId"); else allowed.addAll(QUESTION_FIELDS); }
            default -> throw invalid("不支持的资料类型");
        }
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid("该类型不支持参数 " + field); });
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", type);
        switch (type) {
            case "notebook" -> values.put("title", text(node, "title", true, 200));
            case "bank" -> {
                values.put("title", text(node, "title", true, 200));
                values.put("description", text(node, "description", false, 2000));
            }
            case "note" -> {
                values.put("notebookId", id(node, "notebookId", true));
                values.put("parentPageId", id(node, "parentPageId", false));
                values.put("title", text(node, "title", true, 200));
                values.put("content", text(node, "content", true, 12000));
            }
            case "knowledge" -> {
                values.put("bankId", id(node, "bankId", false));
                values.put("title", text(node, "title", true, 200));
                values.put("content", text(node, "content", true, 12000));
                values.put("category", text(node, "category", false, 200));
                values.put("tags", strings(node, "tags"));
                values.put("headingPath", strings(node, "headingPath"));
                List<Long> ids = new ArrayList<>();
                for (JsonNode entry : array(node, "questionIds", 30)) {
                    if (!entry.isIntegralNumber() || !entry.canConvertToLong() || entry.longValue() <= 0)
                        throw invalid("questionIds 必须是正整数数组");
                    if (!ids.contains(entry.longValue())) ids.add(entry.longValue());
                }
                values.put("questionIds", ids);
            }
            default -> {
                if (node.has("questionId")) values.put("questionId", id(node, "questionId", true));
                else question(node, values);
            }
        }
        return new Input(operation, type, Collections.unmodifiableMap(values));
    }

    private static void question(JsonNode node, Map<String, Object> values) {
        values.put("bankId", id(node, "bankId", true));
        String type = QuestionTypeRules.requireType(text(node, "questionType", true, 20));
        values.put("questionType", type);
        values.put("stem", text(node, "stem", true, 12000));
        String answer = QuestionTypeRules.canonicalAnswer(type, text(node, "answer", false, 12000));
        List<Map<String, String>> options = new ArrayList<>();
        for (JsonNode option : array(node, "options", 26)) {
            if (!option.isObject() || option.size() != 2 || !option.has("key") || !option.has("text"))
                throw invalid("选项只支持 key 和 text");
            options.add(Map.of("key", text(option, "key", true, 20).toUpperCase(Locale.ROOT),
                    "text", text(option, "text", true, 2000)));
        }
        QuestionTypeRules.validate(type, answer, options);
        values.put("options", options);
        values.put("answer", answer);
        values.put("analysis", text(node, "analysis", false, 12000));
        values.put("difficulty", (int) SessionToolArguments.number(node, "difficulty", 3, 5));
        values.put("tags", strings(node, "tags"));
        values.put("chapter", text(node, "chapter", false, 200));
    }

    private static String text(JsonNode node, String key, boolean required, int max) {
        if (!node.has(key)) { if (required) throw invalid("缺少 " + key); return ""; }
        JsonNode value = node.get(key);
        if (!value.isTextual()) throw invalid(key + " 必须是字符串");
        String result = value.textValue().trim();
        if (required && result.isBlank() || result.codePointCount(0, result.length()) > max)
            throw invalid(key + " 为空或过长");
        return result;
    }

    private static Long id(JsonNode node, String key, boolean required) {
        if (!node.has(key)) { if (required) throw invalid("缺少 " + key); return null; }
        return SessionToolArguments.number(node, key, 0, Long.MAX_VALUE);
    }

    private static List<JsonNode> array(JsonNode node, String key, int max) {
        if (!node.has(key)) return List.of();
        JsonNode value = node.get(key);
        if (!value.isArray() || value.size() > max) throw invalid(key + " 必须是有限长度数组");
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    private static List<String> strings(JsonNode node, String key) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, key, 30)) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().codePointCount(0, value.textValue().length()) > 200)
                throw invalid(key + " 中每一项必须是非空短文本");
            result.add(value.textValue().trim());
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
