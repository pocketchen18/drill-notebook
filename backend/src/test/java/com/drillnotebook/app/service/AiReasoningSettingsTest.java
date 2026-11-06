package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.repository.AiConnectionsStore.Provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiReasoningSettingsTest {
    private final AiUpstreamClient client = new AiUpstreamClient(HttpClient.newHttpClient(), new ObjectMapper());
    private final List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hi"));
    private AiUpstreamClient.Upstream target(String format, AiReasoningSettings settings, boolean disabled) {
        return new AiUpstreamClient.Upstream("https://example.invalid", "", "custom", format, 4096, disabled).withReasoning(settings);
    }
    @Test void defaultIsEffortButDoesNotSendAnyControlParameter() {
        var settings = AiReasoningSettings.read(Map.of(), "chat_completions");
        assertEquals("effort", settings.control()); assertTrue(settings.efforts().isEmpty());
        var body = client.buildChatCompletionRequest(target("chat_completions", settings, false), messages, true);
        assertFalse(body.containsKey("reasoning_effort")); assertFalse(body.containsKey("enable_thinking"));
        assertFalse(client.buildAnthropicRequest(target("anthropic", settings, false), messages, false).containsKey("thinking"));
    }
    @Test void rejectsHarnessLevelsInvalidDefaultAndWrongProtocol() {
        assertThrows(IllegalArgumentException.class, () -> AiReasoningSettings.read(Map.of("reasoningEfforts", List.of("ultra")), "chat_completions"));
        assertThrows(IllegalArgumentException.class, () -> AiReasoningSettings.read(Map.of("reasoningEfforts", List.of("low"), "defaultReasoningEffort", "high"), "chat_completions"));
        assertThrows(IllegalArgumentException.class, () -> AiReasoningSettings.read(Map.of("reasoningEfforts", List.of("none")), "anthropic"));
        assertThrows(IllegalArgumentException.class, () -> AiReasoningSettings.read(Map.of("reasoningControl", "budget"), "chat_completions"));
    }
    @Test void bothProtocolsUseNativeFieldsAndBackgroundDisableWins() {
        var settings = AiReasoningSettings.read(Map.of("reasoningEfforts", List.of("high", "low"), "defaultReasoningEffort", "high"), "chat_completions");
        assertEquals(List.of("low", "high"), settings.efforts());
        for (boolean stream : List.of(true, false)) {
            var target = target("chat_completions", settings, false).withCacheBreakpoints(false);
            assertEquals("high", client.buildChatCompletionRequest(target, messages, stream).get("reasoning_effort"));
            assertEquals(Map.of("effort", "high"), client.buildAnthropicRequest(target("anthropic", settings, false), messages, stream).get("output_config"));
        }
        assertFalse(client.buildChatCompletionRequest(target("chat_completions", settings, true), messages, false).containsKey("reasoning_effort"));
        var none = AiReasoningSettings.read(Map.of("reasoningEfforts", List.of("none"), "defaultReasoningEffort", "none"), "chat_completions");
        assertEquals("none", client.buildChatCompletionRequest(target("chat_completions", none, false), messages, false).get("reasoning_effort"));
    }
    @Test void budgetHonorsActualRequestCapAndOpenAiReasonersUseCompletionCap() {
        var settings = AiReasoningSettings.read(Map.of("reasoningControl", "budget", "thinkingBudgetTokens", 2048), "anthropic");
        assertEquals(Map.of("type", "enabled", "budget_tokens", 2048), client.buildAnthropicRequest(target("anthropic", settings, false), messages, false).get("thinking"));
        var small = new AiUpstreamClient.Upstream("https://example.invalid", "", "claude", "anthropic", 2048, false).withReasoning(settings);
        assertThrows(IllegalArgumentException.class, () -> client.buildAnthropicRequest(small, messages, false));
        var openai = new AiUpstreamClient.Upstream("https://api.openai.com/v1", "", "gpt-5.2", "chat_completions", 2048, false);
        var body = client.buildChatCompletionRequest(openai, messages, false);
        assertEquals(2048, body.get("max_completion_tokens")); assertFalse(body.containsKey("max_tokens"));
    }
    @Test void precisePresetMatchingPreservesIdsAndNeverStripsSuffixes() {
        var gateway = new Provider("p", "Gateway", "chat_completions", "https://example.invalid", "none", null, null, List.of());
        assertEquals("gpt-5.2", AiModelPresets.match(gateway, "openai/gpt-5.2").id());
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), AiModelPresets.match(gateway, "gpt-5.2").reasoningEfforts());
        assertNull(AiModelPresets.match(gateway, "gpt-5.2-private"));
        var official = new Provider("p", "OpenAI", "chat_completions", "https://api.openai.com/v1", "none", null, null, List.of());
        assertNull(AiModelPresets.match(official, "claude-opus-4-6"));
        assertTrue(AiModelPresets.match(gateway, "claude-opus-4-6").reasoningEfforts().isEmpty());
        for (var preset : AiModelPresets.forProvider(gateway)) {
            assertFalse(preset.reasoningEfforts().contains("ultra"));
            if (preset.contextWindow() != null && preset.maxOutputTokens() != null) assertTrue(preset.maxOutputTokens() <= preset.contextWindow(), preset.id());
        }
    }
    @Test void signedThinkingSurvivesStreamingToolContinuationWithoutLeakingSignatureToText() throws Exception {
        String sse = """
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"consider"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-value"}}

                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call-1","name":"lookup"}}

                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                data: {"type":"message_stop"}

                """;
        var result = client.consumeAnthropicStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), null, null, new AiUpstreamClient.Deadline(10));
        assertEquals("consider", result.reasoning()); assertEquals(1, result.toolCalls().size());
        assertEquals("signed-value", result.thinkingBlocks().get(0).get("signature"));
        var message = Map.<String, Object>of("role", "assistant", "content", "", "anthropic_thinking", result.thinkingBlocks(),
                "tool_calls", List.of(Map.of("id", "call-1", "function", Map.of("name", "lookup", "arguments", "{}"))));
        var payload = client.toAnthropicWithTools(List.of(message));
        assertEquals(result.thinkingBlocks().get(0), ((List<?>) payload.messages().get(0).get("content")).get(0));
        assertFalse(result.text().contains("signed-value"));
    }
}
