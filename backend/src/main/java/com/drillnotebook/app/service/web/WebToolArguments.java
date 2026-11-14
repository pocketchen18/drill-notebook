package com.drillnotebook.app.service.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class WebToolArguments {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private WebToolArguments() { }
    public static JsonNode read(String arguments, Set<String> names) {
        try {
            if (arguments == null || arguments.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException();
            JsonNode root = JSON.readTree(arguments);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            root.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw new IllegalArgumentException(); });
            return root;
        } catch (Exception error) { throw new WebFailure(WebFailure.Code.INVALID_ARGUMENT); }
    }
    public static int integer(JsonNode root, String name, int fallback, int min, int max) {
        if (!root.has(name)) return fallback;
        JsonNode value = root.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new WebFailure(WebFailure.Code.INVALID_ARGUMENT);
        }
        return value.intValue();
    }
}
