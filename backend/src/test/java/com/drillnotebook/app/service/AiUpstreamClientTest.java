package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatStorageSizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 测试用的宽限 deadline：只负责到期关闭，正常用例里它永远不到期。 */
    private static AiUpstreamClient.Deadline idleDeadline() {
        return new AiUpstreamClient.Deadline(3600);
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
    void chatCompletionsStreamParsesReasoningTextAndTerminalFrame() throws Exception {
        InputStream body = sse(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"思考A\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"答案1\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"答案2\"},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(body, (type, chunk) -> {
            if ("reasoning".equals(type)) reasoning.append(chunk);
            else text.append(chunk);
            return true;
        }, null, idleDeadline());
        assertEquals("思考A", result.reasoning());
        assertEquals("答案1答案2", result.text());
        assertEquals("答案1答案2", text.toString());
        assertEquals("思考A", reasoning.toString());
        assertEquals("stop", result.finishReason());
        assertTrue(result.terminalObserved(), "finish_reason 就是终止帧");
        assertFalse(result.captureLimited());
        assertFalse(result.downstreamGone());
        assertEquals("unknown", result.usage().source());
    }

    @Test
    void chatCompletionsStreamWithoutTerminalFrameIsNotCompleted() throws Exception {
        // 上游断在半路：一个字节都没有再来了，但没有 [DONE] 也没有 finish_reason
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(
                sse("{\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}"), null, null, idleDeadline());
        assertEquals("半句", result.text());
        assertFalse(result.terminalObserved(), "没看到终止帧就不能算正常结束（§7.2 第 4 条）");
    }

    @Test
    void chatCompletionsStreamReportsProviderUsage() throws Exception {
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":34,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":80}}}"), null, null, idleDeadline());
        assertTrue(result.usage().reported());
        assertEquals("provider", result.usage().source());
        assertEquals(120, result.usage().inputTokens());
        assertEquals(34, result.usage().outputTokens());
        assertEquals(80, result.usage().cacheReadTokens());
        assertEquals(154, result.usage().inputTokens() + result.usage().outputTokens());
    }

    @Test
    void anthropicStreamParsesThinkingAndText() throws Exception {
        InputStream body = new ByteArrayInputStream((
                "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想一想\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"回答\"}}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        AiUpstreamClient.Result result = client.consumeAnthropicStream(body, (type, chunk) -> {
            if ("reasoning".equals(type)) reasoning.append(chunk);
            else text.append(chunk);
            return true;
        }, null, idleDeadline());
        assertEquals("想一想", result.reasoning());
        assertEquals("回答", result.text());
        assertEquals("想一想", reasoning.toString());
        assertEquals("回答", text.toString());
        assertEquals("end_turn", result.finishReason());
        assertTrue(result.terminalObserved(), "message_stop 是 Anthropic 的终止帧");
        assertEquals(7, result.usage().outputTokens());
    }

    @Test
    void midStreamCancelStopsReadingAndKeepsWhatWasCaptured() throws Exception {
        ActiveTurnRegistry.Handle handle = new ActiveTurnRegistry.Handle("req-cancel", 1L);
        StringBuilder heard = new StringBuilder();
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"不该出现\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"更不该出现\"}}]}"), (type, chunk) -> {
            heard.append(chunk);
            handle.requestCancel();
            return true;
        }, handle, idleDeadline());
        assertTrue(handle.cancelRequested());
        assertEquals("第一段", result.text(), "取消后已捕获的前缀要留着展示，但不算完成（§7.2 第 5 条）");
        assertFalse(result.terminalObserved());
    }

    @Test
    void expiredDeadlineStopsTheReadLoopImmediately() throws Exception {
        AiUpstreamClient.Deadline deadline = idleDeadline();
        deadline.expire();
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"永远不会被读到\"}}]}"), null, null, deadline);
        assertEquals("", result.text(), "到期之后一字节都不再解析（§7.2.2）");
        assertFalse(result.terminalObserved());
        assertTrue(deadline.expired());
    }

    @Test
    void streamCaptureStopsAtOneMibAndKeepsCodePointBoundary() throws Exception {
        long limit = ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES;
        // 一个 delta 远超上限：保留的部分必须停在完整码点边界，且总字节数不超过 1 MiB
        StringBuilder huge = new StringBuilder();
        while (huge.length() * 3 < limit + 3000) huge.append('城');
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(
                sse("{\"choices\":[{\"delta\":{\"content\":\"" + huge + "\"}}]}"), null, null, idleDeadline());
        assertTrue(result.captureLimited());
        long kept = result.text().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(kept <= limit, "捕获字节必须低于上限，实际 " + kept);
        assertEquals(limit / 3, kept / 3, "按整码点截断，不留半个 UTF-8 序列");
        assertFalse(result.text().isEmpty());
    }

    @Test
    void reasoningAndTextShareOneCaptureBudget() throws Exception {
        long limit = ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES;
        StringBuilder half = new StringBuilder();
        while (half.length() * 3 < limit / 2 + 3000) half.append('城');
        String delta = half.toString();
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"" + delta + "\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"" + delta + "\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"" + delta + "\"}}]}"), null, null, idleDeadline());
        assertTrue(result.captureLimited(), "正文与思考链共用同一个 1 MiB 额度，不是各给 1 MiB");
        long total = result.text().getBytes(StandardCharsets.UTF_8).length
                + result.reasoning().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(total <= limit, "合计不得超过原始捕获上限，实际 " + total);
    }

    @Test
    void downstreamRefusalIsReportedAsGone() throws Exception {
        StringBuilder heard = new StringBuilder();
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"第二段\"}}]}"), (type, chunk) -> {
            heard.append(chunk);
            return false; // SSE 写失败，等同用户主动停止
        }, null, idleDeadline());
        assertEquals("第一段", heard.toString());
        assertEquals("第一段", result.text());
        assertTrue(result.downstreamGone());
    }

    @Test
    void streamCaptureKeepsOnlyWholeCodePointsThatStillFit() throws Exception {
        long limit = ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES;
        // 先留 12 字节余量，再喂五个 emoji：只进得去三个，且截断处不出现半个 UTF-8 序列
        String prefix = "a".repeat((int) limit - 12);
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"" + prefix + "\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"🍀🍀🍀🍀🍀\"}}]}"), null, null, idleDeadline());

        assertTrue(result.captureLimited());
        assertEquals(limit, result.text().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(3, result.text().codePoints().filter(point -> point == 0x1F340).count());
    }

    @Test
    void rawResponseBodyBeyondEightMibIsRejectedNotBuffered() {
        InputStream huge = new SequenceInputStream(
                Collections.enumeration(List.of(
                        new ByteArrayInputStream(new byte[8 * 1024 * 1024]),
                        new ByteArrayInputStream(new byte[8192]))));
        assertThrows(AiUpstreamClient.ResponseTooLargeException.class, () -> AiUpstreamClient.readBounded(huge));
    }

    @Test
    void errorBodyIsReadWithOneMibCap() throws Exception {
        byte[] chunk = new byte[64 * 1024];
        List<ByteArrayInputStream> parts = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) parts.add(new ByteArrayInputStream(chunk));
        InputStream huge = new SequenceInputStream(Collections.enumeration(parts));
        String body = AiUpstreamClient.readForError(huge);
        assertEquals(1024 * 1024, body.getBytes(StandardCharsets.UTF_8).length,
                "错误体只为拼一句提示，读到 1 MiB 就停");
    }

    @Test
    void cancelBeforeDispatchSkipsTheNetworkEntirely() {
        ActiveTurnRegistry.Handle handle = new ActiveTurnRegistry.Handle("req-early", 1L);
        handle.requestCancel();
        // 端口 1 上没有任何服务：真发了请求就会以"连接失败"抛出，这里必须根本不发（§7.3）
        AiUpstreamClient.Result result = client.call(new AiUpstreamClient.Upstream(
                "http://127.0.0.1:1/v1", "key", "model", "openai", 128, false),
                List.of(Map.of("role", "user", "content", "停止后再也没发出去")), false, null, 5, handle);

        assertTrue(result.downstreamGone());
        assertFalse(result.terminalObserved(), "取消的结果永远不能算正常完成（§7.2.2）");
        assertEquals("", result.text());
        assertEquals("aborted", result.finishReason());
    }

    @Test
    void errorMessageClassification() {
        String message = AiUpstreamClient.errorMessage(401, "{\"error\":{\"message\":\"bad key\"}}");
        assertTrue(message.contains("401"));
        assertTrue(message.contains("bad key"));
        String notFound = AiUpstreamClient.errorMessage(404, "");
        assertTrue(notFound.contains("Base URL"));
        // 超长错误体只留 240 码点摘要，不能把整段塞进日志和响应
        assertTrue(AiUpstreamClient.errorMessage(500, "x".repeat(1024 * 1024)).length() < 400);
    }
}
