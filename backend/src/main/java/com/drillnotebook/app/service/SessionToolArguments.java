package com.drillnotebook.app.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 拒绝模型参数中的隐式类型转换、重复键、未知字段及尾随内容。 */
final class SessionToolArguments {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private SessionToolArguments() { }

    static JsonNode parse(String raw, Set<String> fields) {
        return parse(raw, fields, 4096);
    }

    static JsonNode parse(String raw, Set<String> fields, int maxBytes) {
        if (raw == null || raw.isBlank() || raw.getBytes(StandardCharsets.UTF_8).length > maxBytes) return null;
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) return null;
            var names = node.fieldNames();
            while (names.hasNext()) if (!fields.contains(names.next())) return null;
            return node;
        } catch (Exception ignored) { return null; }
    }

    static String text(JsonNode node, String name, String fallback, int max) {
        if (!node.has(name)) return fallback;
        JsonNode value = node.get(name);
        if (!value.isTextual()) throw new IllegalArgumentException();
        String text = value.textValue().trim();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > max) throw new IllegalArgumentException();
        return text;
    }

    static long number(JsonNode node, String name, long fallback, long max) {
        if (!node.has(name)) return fallback;
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1 || value.longValue() > max)
            throw new IllegalArgumentException();
        return value.longValue();
    }

    static boolean flag(JsonNode node, String name) {
        if (!node.has(name)) return false;
        if (!node.get(name).isBoolean()) throw new IllegalArgumentException();
        return node.get(name).booleanValue();
    }
}
