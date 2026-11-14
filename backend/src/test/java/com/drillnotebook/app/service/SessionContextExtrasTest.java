package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionContextExtrasTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static Map<String, Object> text(String role, String value) {
        return Map.of("role", role, "content", List.of(Map.of("type", "text", "text", value)));
    }
    private long count(List<Map<String, Object>> messages, int initial, Set<Map<String, Object>> supplements) {
        return SessionContextExtras.estimate(messages, initial, supplements, mapper);
    }

    @Test void ordinaryHistoryAndRetainedAnswerPrefixesAreNotCountedAgainAsExtras() {
        var messages = new ArrayList<>(List.of(text("system", "固定规则"), text("user", "历史提问"),
                text("assistant", "历史回答"), text("user", "当前提问")));
        int initial = messages.size();
        messages.add(Map.of("role", "assistant", "content", "已经显示的回答前半段"));
        assertEquals(0, count(messages, initial, Set.of()));
        messages.add(Map.of("role", "user", "content", "请接着回答"));
        assertTrue(count(messages, initial, Set.of()) > 0, "续接指令是额外内容");
    }

    @Test void toolCallsAndResultsAreOneWorkingSetRatherThanMultipliedByRequestCount() {
        var messages = new ArrayList<>(List.of(text("user", "当前提问")));
        var call = Map.<String, Object>of("role", "assistant", "content", "工具前的说明",
                "tool_calls", List.of(Map.of("id", "call-1", "function", Map.of("name", "search_web", "arguments", "{}"))));
        var result = Map.<String, Object>of("role", "tool", "tool_call_id", "call-1", "content", "检索结果");
        messages.add(call); messages.add(result);
        var estimator = new TokenEstimator(mapper);
        long expected = (long) estimator.estimateInput(call) + estimator.estimateInput(result);
        assertEquals(expected, count(messages, 1, Set.of()));
        assertEquals(expected, count(messages, 1, Set.of()), "刷新或重试读取同一工作集不再加一份");
        var breakdown = SessionContextExtras.breakdown(messages, 1, Set.of(), mapper);
        assertEquals(expected, breakdown.tokens());
        assertEquals((long) estimator.estimateInput(call.get("content")),
                breakdown.categories().get(com.drillnotebook.app.model.RequestContextAudit.Kind.ASSISTANT));
        assertEquals((long) estimator.estimateInput(call.get("tool_calls")),
                breakdown.categories().get(com.drillnotebook.app.model.RequestContextAudit.Kind.TOOL_CALLS));
        assertEquals((long) estimator.estimateInput(result.get("content")),
                breakdown.categories().get(com.drillnotebook.app.model.RequestContextAudit.Kind.TOOL_RESULTS));
        assertTrue(breakdown.categories().get(com.drillnotebook.app.model.RequestContextAudit.Kind.OTHER) > 0);
    }

    @Test void injectedDataExcludesSeparatelyCountedSummaryButRetainsNotebookAndMemoryContent() throws Exception {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("kind", "CONTEXT_DATA_V1"); envelope.put("summary", "长摘要".repeat(1000));
        envelope.put("notebookExcerpts", List.of(Map.of("text", "笔记片段")));
        envelope.put("memories", List.of(Map.of("text", "已授权的记忆")));
        var messages = List.of(text("user", mapper.writeValueAsString(envelope)), text("user", "提问"));
        long withSummary = count(messages, 2, Set.of());
        envelope.put("summary", null);
        assertEquals(withSummary, count(List.of(text("user", mapper.writeValueAsString(envelope)), text("user", "提问")), 2, Set.of()));
        assertTrue(withSummary > 0);
    }

    @Test void imagesUseAFixedCostAndSupplementsOnlyAddTheirAttachedData() {
        Map<String, Object> supplement = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "页面定位"),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAAA")),
                Map.of("type", "text", "text", "补充提问".repeat(1000))));
        Set<Map<String, Object>> received = Collections.newSetFromMap(new IdentityHashMap<>());
        received.add(supplement);
        long expected = count(List.of(text("user", "原始提问"), supplement), 1, received);
        assertTrue(expected >= TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS);
        Map<String, Object> longerBytes = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "页面定位"),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + "A".repeat(100000))),
                Map.of("type", "text", "text", "不同长度的补充提问")));
        received.add(longerBytes);
        assertEquals(expected, count(List.of(text("user", "原始提问"), longerBytes), 1, received),
                "补充提问由全局正文数值计一次，图像不能按 base64 大小算");
    }
}
