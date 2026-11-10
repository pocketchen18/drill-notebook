package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class MaterialUpdateArguments {
    static final int MAX_BYTES = 64 * 1024;
    static final Set<String> FIELDS = Set.of("type", "id", "operationId", "expectedVersion", "changes");
    static Set<String> editable(String type) {
        return switch (type) {
            case "notebook" -> Set.of("title");
            case "note" -> Set.of("title", "edits", "append");
            case "bank" -> Set.of("title", "description");
            case "knowledge" -> Set.of("title", "content", "category", "tags", "headingPath", "questionIds");
            case "question", "wrong" -> Set.of("questionType", "stem", "options", "answer", "analysis", "difficulty", "tags", "chapter");
            default -> throw new IllegalArgumentException();
        };
    }
    record Input(String type, long id, String operationId, String expectedVersion, Map<String, Object> changes) { }
    static Input parse(String raw) {
        JsonNode node = SessionToolArguments.parse(raw, FIELDS, MAX_BYTES);
        if (node == null || !node.has("id")) throw new IllegalArgumentException();
        String type = SessionToolArguments.text(node, "type", "", 20);
        Set<String> allowed = editable(type);
        String operation = SessionToolArguments.text(node, "operationId", "", 80);
        String version = SessionToolArguments.text(node, "expectedVersion", "", 64);
        if (!operation.matches("[a-zA-Z0-9_-]{1,80}") || !version.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        long id = SessionToolArguments.number(node, "id", 0, Long.MAX_VALUE);
        JsonNode patch = node.get("changes");
        if (patch == null || !patch.isObject() || patch.isEmpty()) throw new IllegalArgumentException();
        Map<String, Object> values = new TreeMap<>();
        patch.fields().forEachRemaining(entry -> {
            String key = entry.getKey(); JsonNode value = entry.getValue();
            if (!allowed.contains(key) || value.isNull()) throw new IllegalArgumentException();
            switch (key) {
                case "difficulty" -> values.put(key, (int) SessionToolArguments.number(patch, key, 3, 5));
                case "tags", "headingPath", "questionIds", "options" -> {
                    if (!value.isArray() || value.size() > (key.equals("options") ? 26 : 30)) throw new IllegalArgumentException();
                    List<Object> items = new ArrayList<>();
                    for (JsonNode item : value) {
                        if (key.equals("questionIds")) {
                            if (!item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() < 1) throw new IllegalArgumentException();
                            if (!items.contains(item.longValue())) items.add(item.longValue());
                        } else if (key.equals("options")) {
                            if (!item.isObject() || item.size() != 2 || !item.has("key") || !item.has("text")) throw new IllegalArgumentException();
                            items.add(Map.of("key", text(item.get("key"), 20, true).toUpperCase(Locale.ROOT), "text", text(item.get("text"), 2000, true)));
                        } else items.add(text(item, 200, true));
                    }
                    values.put(key, items);
                }
                case "edits" -> {
                    if (!value.isArray() || value.isEmpty() || value.size() > 20) throw new IllegalArgumentException();
                    List<Map<String,String>> edits = new ArrayList<>();
                    for (var edit : value) {
                        if (!edit.isObject() || edit.size() != 2 || !edit.has("oldText") || !edit.has("newText")) throw new IllegalArgumentException();
                        String old = literal(edit.get("oldText"), 12000), replacement = literal(edit.get("newText"), 12000);
                        if (old.isBlank()) throw new IllegalArgumentException();
                        edits.add(Map.of("oldText", old, "newText", replacement));
                    }
                    values.put(key, edits);
                }
                default -> {
                    int max = Set.of("content", "stem", "answer", "analysis", "append").contains(key) ? 12000 : key.equals("description") ? 2000 : 200;
                    String str = text(value, max, Set.of("title", "content", "stem", "append", "questionType").contains(key));
                    if (key.equals("questionType")) str = QuestionTypeRules.requireType(str);
                    values.put(key, str);
                }
            }
        });
        return new Input(type, id, operation, version, Collections.unmodifiableMap(values));
    }
    private static String text(JsonNode value, int max, boolean required) {
        String text = literal(value, max).trim();
        if (required && text.isBlank()) throw new IllegalArgumentException();
        return text;
    }
    private static String literal(JsonNode value, int max) {
        if (value == null || !value.isTextual() || value.textValue().codePointCount(0, value.textValue().length()) > max) throw new IllegalArgumentException();
        return value.textValue();
    }
}
