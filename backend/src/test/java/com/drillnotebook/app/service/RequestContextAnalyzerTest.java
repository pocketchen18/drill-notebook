package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.model.RequestContextAudit.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestContextAnalyzerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RequestContextAnalyzer analyzer = new RequestContextAnalyzer(json);
    private final AiUpstreamClient upstream = new AiUpstreamClient(HttpClient.newHttpClient(), json);
    private static final Set<String> NAMES = Set.of("search_web", "fetch_web");
    private static final List<AiUpstreamClient.ToolDefinition> TOOLS = List.of(
            new AiUpstreamClient.ToolDefinition("search_web", "PRIVATE_DEFINITION_TEXT", Map.of("type", "object", "properties", Map.of())),
            new AiUpstreamClient.ToolDefinition("fetch_web", "网页读取", Map.of("type", "object")));

    private static AiUpstreamClient.Upstream target(boolean anthropic) {
        return new AiUpstreamClient.Upstream("http://127.0.0.1/unused", "PRIVATE_KEY_NOT_IN_METADATA", "test-model",
                anthropic ? "anthropic" : "chat_completions", 1000, false);
    }
    private static List<Map<String, Object>> messages() {
        return List.of(Map.of("role", "system", "content", "PRIVATE_SYSTEM_TEXT"),
                Map.of("role", "user", "content", "CONTEXT_DATA_V1\nPRIVATE_INJECTION"),
                Map.of("role", "user", "content", "PRIVATE_USER_INPUT"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call-1", "type", "function",
                        "function", Map.of("name", "search_web", "arguments", "{\"query\":\"PRIVATE_QUERY\"}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "PRIVATE_WEB_BODY"));
    }
    private static RequestContextAudit.Category category(RequestContextAudit audit, Kind kind) {
        return audit.categories().stream().filter(row -> row.kind() == kind).findFirst().orElseThrow();
    }

    @Test void separatesFinalChatRequestIntoToolDefinitionsCallsAndResultsWithoutKeepingBodies() throws Exception {
        var body = upstream.buildChatCompletionRequest(target(false), messages(), false, TOOLS);
        var audit = analyzer.analyze(body, 128000, "CHAT", NAMES);
        assertEquals(2, category(audit, Kind.TOOL_DEFINITIONS).itemCount());
        assertEquals(1, category(audit, Kind.TOOL_CALLS).itemCount());
        assertEquals(1, category(audit, Kind.TOOL_RESULTS).itemCount());
        assertEquals("search_web", category(audit, Kind.TOOL_RESULTS).items().get(0).name());
        assertEquals(1, category(audit, Kind.USER).itemCount());
        assertEquals(1, category(audit, Kind.INJECTED_CONTEXT).itemCount());
        assertEquals(audit.estimatedInputTokens(), audit.categories().stream().mapToInt(RequestContextAudit.Category::estimatedTokens).sum());
        String stored = json.writeValueAsString(audit);
        assertFalse(stored.contains("PRIVATE_"), "摘要不能保存输入、注入、定义正文、参数、结果或 Key");
        assertFalse(stored.contains("call-1"));
        assertTrue(stored.getBytes(StandardCharsets.UTF_8).length < RequestContextAudit.MAX_BYTES);
    }

    @Test void classifiesAnthropicToolBlocksAsToolResultsInsteadOfUserMessages() {
        var body = upstream.buildAnthropicRequest(target(true), messages(), false, TOOLS);
        var audit = analyzer.analyze(body, 128000, "CHAT", NAMES);
        assertEquals(1, category(audit, Kind.TOOL_RESULTS).itemCount());
        assertEquals("search_web", category(audit, Kind.TOOL_RESULTS).items().get(0).name());
        assertEquals(1, category(audit, Kind.TOOL_CALLS).itemCount());
        assertEquals(1, category(audit, Kind.USER).itemCount());
        assertEquals(2, category(audit, Kind.TOOL_DEFINITIONS).itemCount());
    }

    @Test void imageEstimateDoesNotGrowWithBase64AndNeverPersistsImageBytes() throws Exception {
        var shortImage = Map.<String, Object>of("model", "test", "messages", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,a"))))));
        var longImage = Map.<String, Object>of("model", "test", "messages", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + "A".repeat(100000)))))));
        var first = analyzer.analyze(shortImage, 128000, "CHAT", Set.of());
        var second = analyzer.analyze(longImage, 128000, "CHAT", Set.of());
        assertEquals(first.estimatedInputTokens(), second.estimatedInputTokens());
        assertTrue(category(first, Kind.IMAGE).estimatedTokens() >= TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS);
        assertFalse(json.writeValueAsString(second).contains("base64"));
    }

    @Test void nativeResponsesPromptIsUserInputAndBuiltInToolRemainsASeparateDefinition() throws Exception {
        var body = com.drillnotebook.app.service.web.NativeWebClient.request("test", false, false,
                "PRIVATE_SEARCH_QUERY", List.of(), 1024);
        var audit = analyzer.analyze(body, 128000, "NATIVE_WEB", Set.of("web_search", "web_fetch"));
        assertEquals("NATIVE_WEB", audit.kind());
        assertEquals(1, category(audit, Kind.USER).itemCount());
        assertEquals(1, category(audit, Kind.TOOL_DEFINITIONS).itemCount());
        assertEquals("web_search", category(audit, Kind.TOOL_DEFINITIONS).items().get(0).name());
        assertEquals(audit.estimatedInputTokens(), audit.categories().stream().mapToInt(RequestContextAudit.Category::estimatedTokens).sum());
        assertFalse(json.writeValueAsString(audit).contains("PRIVATE_SEARCH_QUERY"));
    }

    @Test void largeToolResultListsKeepCountsAndTotalsAndRetainTheLargestItemsWithinBoundedMetadata() throws Exception {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", "规则"));
        messages.add(Map.of("role", "user", "content", "本轮输入"));
        for (int i = 0; i < 700; i++) messages.add(Map.of("role", "tool", "tool_call_id", "call-" + i, "content", "x"));
        messages.add(Map.of("role", "tool", "tool_call_id", "last", "content", "BIG_PRIVATE_RESULT".repeat(2000)));
        var audit = analyzer.analyze(Map.of("model", "test", "messages", messages), 1000000, "CHAT", NAMES);
        var tools = category(audit, Kind.TOOL_RESULTS);
        assertEquals(701, tools.itemCount());
        assertTrue(audit.detailsTruncated());
        assertTrue(audit.categories().stream().mapToInt(row -> row.items().size()).sum() <= RequestContextAudit.MAX_ITEMS);
        assertTrue(tools.items().get(0).estimatedTokens() > 1000, "末尾的大结果不能被前面小条目的数量挤掉");
        assertFalse(category(audit, Kind.SYSTEM).items().isEmpty());
        assertFalse(category(audit, Kind.USER).items().isEmpty());
        assertTrue(json.writeValueAsBytes(audit).length <= RequestContextAudit.MAX_BYTES);
        assertEquals(audit.estimatedInputTokens(), audit.categories().stream().mapToInt(RequestContextAudit.Category::estimatedTokens).sum());
    }

    @Test void longToolNamesAndManyCallsAlsoFitTheByteBoundWithoutLosingCategoryTotals() throws Exception {
        String name = "tool" + "x".repeat(60);
        var messages = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 256; i++) messages.add(Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                "id", "call-" + i, "function", Map.of("name", name, "arguments", "x".repeat(10000))))));
        var audit = analyzer.analyze(Map.of("model", "test", "messages", messages), 2000000, "CHAT", Set.of(name));
        assertEquals(256, category(audit, Kind.TOOL_CALLS).itemCount());
        assertFalse(category(audit, Kind.TOOL_CALLS).items().isEmpty());
        assertTrue(json.writeValueAsBytes(audit).length <= RequestContextAudit.MAX_BYTES);
        assertEquals(audit.estimatedInputTokens(), audit.categories().stream().mapToInt(RequestContextAudit.Category::estimatedTokens).sum());
    }

    @Test void malformedNumericSummariesAreRejectedWithoutAffectingMeasuredLedger() {
        assertThrows(IllegalArgumentException.class, () -> new RequestContextAudit(1, "test", "test", "CHAT", 10, 100,
                List.of(new RequestContextAudit.Category(Kind.USER, 10, 1,
                        List.of(new RequestContextAudit.Item("message-0", null, 11)))), false));
        assertThrows(IllegalArgumentException.class, () -> new RequestContextAudit(1, "test", "test", "CHAT", 20, 100,
                List.of(new RequestContextAudit.Category(Kind.USER, 10, 0, List.of()),
                        new RequestContextAudit.Category(Kind.USER, 10, 0, List.of())), false));
    }
}
