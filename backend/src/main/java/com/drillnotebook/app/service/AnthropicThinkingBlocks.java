package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatStorageSizer;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 思考签名仅在当前工具轮次内传递；不进入展示文本、日志或历史存储。 */
final class AnthropicThinkingBlocks {
    private final Map<Integer, Map<String, StringBuilder>> blocks = new TreeMap<>();
    private long bytes;
    private boolean limited;
    void open(int index, JsonNode node) {
        String type = node.path("type").asText();
        if (!List.of("thinking", "redacted_thinking").contains(type)) return;
        if (blocks.size() >= 64 || blocks.containsKey(index)) { limited = true; return; }
        Map<String, StringBuilder> block = new LinkedHashMap<>();
        block.put("type", new StringBuilder(type)); blocks.put(index, block);
        if ("thinking".equals(type)) {
            append(index, "thinking", node.path("thinking").asText(""));
            append(index, "signature", node.path("signature").asText(""));
        } else append(index, "data", node.path("data").asText(""));
    }
    void delta(int index, JsonNode node) {
        String type = node.path("type").asText();
        if ("thinking_delta".equals(type)) append(index, "thinking", node.path("thinking").asText(""));
        if ("signature_delta".equals(type)) append(index, "signature", node.path("signature").asText(""));
    }
    private void append(int index, String field, String text) {
        if (limited || !blocks.containsKey(index)) return;
        bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES) { limited = true; return; }
        blocks.get(index).computeIfAbsent(field, key -> new StringBuilder()).append(text);
    }
    boolean limited() { return limited; }
    List<Map<String, Object>> finish() {
        if (limited) return List.of();
        return blocks.values().stream().map(block -> {
            Map<String, Object> value = new LinkedHashMap<>();
            block.forEach((key, text) -> value.put(key, text.toString()));
            return Map.copyOf(value);
        }).toList();
    }
}
