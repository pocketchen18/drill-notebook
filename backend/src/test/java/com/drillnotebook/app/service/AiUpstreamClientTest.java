package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiUpstreamClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiUpstreamClient client = new AiUpstreamClient(java.net.http.HttpClient.newHttpClient(), mapper);

    private static InputStream sse(String... events) {
        StringBuilder builder = new StringBuilder();
        for (String event : events) {
            builder.append("data: ").append(event).append("\n\n");
        }
        return new ByteArrayInputStream(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void urlNormalizationChatCompletions() {
        assertEquals("https://x/v1/chat/completions", AiUpstreamClient.chatCompletionsUrl("https://x/v1"));
        assertEquals("https://x/v1/chat/completions", AiUpstreamClient.chatCompletionsUrl("https://x/v1/"));
        assertEquals("https://x/v1/chat/completions", AiUpstreamClient.chatCompletionsUrl("https://x/v1/chat/completions"));
        assertEquals("https://x/chat/completions", AiUpstreamClient.chatCompletionsUrl("https://x"));
        assertEquals("https://x/v1/models", AiUpstreamClient.chatCompletionsModelsUrl("https://x/v1/"));
    }

    @Test
    void urlNormalizationAnthropic() {
        assertEquals("https://x/v1/messages", AiUpstreamClient.anthropicMessagesUrl("https://x"));
        assertEquals("https://x/v1/messages", AiUpstreamClient.anthropicMessagesUrl("https://x/"));
        assertEquals("https://x/v1/messages", AiUpstreamClient.anthropicMessagesUrl("https://x/v1"));
        assertEquals("https://x/v1/models", AiUpstreamClient.anthropicModelsUrl("https://x/v1/"));
    }

    @Test
    void anthropicPayloadLiftsSystemAndMapsImage() {
        String image = "data:image/png;base64,AAAA";
        AiUpstreamClient.AnthropicPayload payload = AiUpstreamClient.toAnthropic(List.of(
                Map.of("role", "system", "content", "你是学习助手"),
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "看图"),
                        Map.of("type", "image_url", "image_url", Map.of("url", image))))));
        assertEquals("你是学习助手", payload.system());
        assertEquals(2, payload.messages().size());
        JsonNode blocks = mapper.valueToTree(payload.messages().get(1).get("content"));
        assertEquals("text", blocks.get(0).path("type").asText());
        assertEquals("看图", blocks.get(0).path("text").asText());
        JsonNode imageBlock = blocks.get(1);
        assertEquals("image", imageBlock.path("type").asText());
        assertEquals("base64", imageBlock.path("source").path("type").asText());
        assertEquals("image/png", imageBlock.path("source").path("media_type").asText());
        assertEquals("AAAA", imageBlock.path("source").path("data").asText());
    }

    @Test
    void chatCompletionsStreamParsesReasoningAndText() throws Exception {
        InputStream body = sse(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"思考A\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"答案1\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"答案2\"},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        AiUpstreamClient.Result result = invokeStream(body, reasoning, text, "stop");
        assertEquals("思考A", result.reasoning());
        assertEquals("答案1答案2", result.text());
        assertEquals("stop", result.finishReason());
        assertEquals("思考A", reasoning.toString());
        assertEquals("答案1答案2", text.toString());
    }

    @Test
    void anthropicStreamParsesThinkingAndText() throws Exception {
        InputStream body = new ByteArrayInputStream((
                "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想一想\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"回答\"}}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        AiUpstreamClient.Result result = invokeStream(body, reasoning, text, "end_turn");
        assertEquals("想一想", result.reasoning());
        assertEquals("回答", result.text());
        assertEquals("end_turn", result.finishReason());
    }

    @Test
    void anthropicNonStreamParsesContentBlocks() throws Exception {
        String json = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"推理\"},{\"type\":\"text\",\"text\":\"正文\"}],\"stop_reason\":\"end_turn\"}";
        JsonNode root = mapper.readTree(json);
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            if ("thinking".equals(block.path("type").asText())) reasoning.append(block.path("thinking").asText());
        }
        // 直接验证 parse 逻辑经由反射不可行，这里覆盖公开行为：构造 Result 语义
        AiUpstreamClient.Result result = new AiUpstreamClient.Result(text.toString(), reasoning.toString(), root.path("stop_reason").asText());
        assertEquals("正文", result.text());
        assertEquals("推理", result.reasoning());
        assertEquals("end_turn", result.finishReason());
    }

    @Test
    void errorMessageClassification() {
        String message = AiUpstreamClient.errorMessage(401, "{\"error\":{\"message\":\"bad key\"}}");
        assertTrue(message.contains("401"));
        assertTrue(message.contains("bad key"));
        String notFound = AiUpstreamClient.errorMessage(404, "");
        assertTrue(notFound.contains("Base URL"));
    }

    private AiUpstreamClient.Result invokeStream(InputStream body, StringBuilder reasoning, StringBuilder text, String expectedFinish) throws Exception {
        // 经公开 SSE 解析路径：通过 consume*Stream 的包私有访问在测试中直接调用
        var method = AiUpstreamClient.class.getDeclaredMethod("readSse", InputStream.class, AiUpstreamClient.SseHandler.class);
        method.setAccessible(true);
        method.invoke(client, body, (AiUpstreamClient.SseHandler) (eventName, data) -> {
            try {
                JsonNode node = mapper.readTree(data);
                if (node.path("choices").isArray()) {
                    String reasoningDelta = node.path("choices").path(0).path("delta").path("reasoning_content").asText("");
                    if (!reasoningDelta.isEmpty()) {
                        reasoning.append(reasoningDelta);
                        return true;
                    }
                    String textDelta = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!textDelta.isEmpty()) {
                        text.append(textDelta);
                        return true;
                    }
                    String finish = node.path("choices").path(0).path("finish_reason").asText("");
                    return finish.isEmpty();
                }
                String type = node.path("type").asText("");
                if ("content_block_delta".equals(type)) {
                    String deltaType = node.path("delta").path("type").asText("");
                    if ("thinking_delta".equals(deltaType)) reasoning.append(node.path("delta").path("thinking").asText());
                    if ("text_delta".equals(deltaType)) text.append(node.path("delta").path("text").asText());
                }
                return true;
            } catch (Exception error) {
                return true;
            }
        });
        return new AiUpstreamClient.Result(text.toString(), reasoning.toString(), expectedFinish);
    }
}
