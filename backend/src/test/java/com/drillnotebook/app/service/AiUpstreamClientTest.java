package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatStorageSizer;
import com.drillnotebook.app.repository.UsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test void cancellationInterruptsTheWaitForResponseHeaders() throws Exception {
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            arrived.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var handle = new ActiveTurnRegistry().acquire("cancel-headers", 1L).orElseThrow();
            var upstream = new AiUpstreamClient.Upstream("http://127.0.0.1:" + server.getAddress().getPort(), "", "stub", "chat_completions", 128, false);
            var pending = executor.submit(() -> client.call(upstream, List.of(Map.of("role", "user", "content", "test")), false, null, 900, handle));
            assertTrue(arrived.await(3, java.util.concurrent.TimeUnit.SECONDS));
            handle.requestCancel();
            var result = pending.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("aborted", result.finishReason());
            assertTrue(result.downstreamGone());
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    @Test void waitingTimeoutIsNotReportedAsUserCancellation() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var upstream = new AiUpstreamClient.Upstream("http://127.0.0.1:" + server.getAddress().getPort(), "", "stub", "chat_completions", 128, false);
            var result = client.call(upstream, List.of(Map.of("role", "user", "content", "test")), false, null, 1);
            assertEquals("deadline", result.finishReason());
            assertFalse(result.downstreamGone());
            assertFalse(result.terminalObserved());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void brokenStreamPreservesTextAndDoesNotExecuteIncompleteTools() throws Exception {
        var prefix = sse("{\"choices\":[{\"delta\":{\"content\":\"已收到的部分\"}}]}");
        var broken = new InputStream() {
            public int read() throws IOException {
                int next = prefix.read();
                if (next < 0) throw new IOException("fixture disconnect");
                return next;
            }
        };
        var deadline = idleDeadline();
        try {
            var failure = assertThrows(AiUpstreamException.class,
                    () -> client.consumeChatCompletionStream(broken, null, null, deadline));
            assertEquals(AiUpstreamException.Kind.STREAM, failure.kind());
            assertTrue(failure.retryable());
            assertTrue(failure.getMessage().contains("fixture disconnect"));
            var result = failure.partial();
            assertEquals("已收到的部分", result.text());
            assertEquals("stream_error", result.finishReason());
            assertFalse(result.terminalObserved());
            assertTrue(result.toolCalls().isEmpty());
        } finally { deadline.cancel(); }
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
    void maintenanceJsonModeOnlyAddsResponseFormatWhenRequested() {
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "提取"));
        AiUpstreamClient.Upstream plain = new AiUpstreamClient.Upstream("https://x/v1", "k", "m",
                AiUpstreamClient.FORMAT_CHAT_COMPLETIONS, 128, true);
        AiUpstreamClient.Upstream json = new AiUpstreamClient.Upstream("https://x/v1", "k", "m",
                AiUpstreamClient.FORMAT_CHAT_COMPLETIONS, 128, true, true);

        assertFalse(client.buildChatCompletionRequest(plain, messages, false).containsKey("response_format"));
        assertEquals(Map.of("type", "json_object"),
                client.buildChatCompletionRequest(json, messages, false).get("response_format"));
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
        // 连续两条 user 必须并成一条（Anthropic 不接受同角色相邻），块序原样保留
        assertEquals(1, payload.messages().size());
        JsonNode blocks = mapper.valueToTree(payload.messages().get(0).get("content"));
        assertEquals(3, blocks.size(), "两条 user 的三个块全部按顺序并进来");
        assertEquals("你好", blocks.get(0).path("text").asText());
        assertEquals("看图", blocks.get(1).path("text").asText());
        JsonNode imageBlock = blocks.get(2);
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
        var failure = assertThrows(AiUpstreamException.class, () -> client.consumeChatCompletionStream(
                sse("{\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}"), null, null, idleDeadline()));
        assertEquals(AiUpstreamException.Kind.STREAM, failure.kind());
        assertTrue(failure.retryable());
        var result = failure.partial();
        assertEquals("半句", result.text());
        assertFalse(result.terminalObserved(), "没看到终止帧就不能算正常结束（§7.2 第 4 条）");
    }

    @Test
    void chatCompletionsStreamAcceptsCrlfFrames() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"第一行\"}}]}\r\n\r\n"
                + "data: [DONE]\r\n\r\n";
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null, null, idleDeadline());
        assertEquals("第一行", result.text());
        assertTrue(result.terminalObserved());
    }

    @Test
    void chatCompletionsStreamDecodesUtf8SplitAcrossReads() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"跨字节中文\"}}]}\n\n"
                + "data: [DONE]\n\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new InputStream() {
            private int index;

            @Override
            public int read() {
                return index < bytes.length ? bytes[index++] & 0xff : -1;
            }
        };
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(
                oneByteAtATime, null, null, idleDeadline());
        assertEquals("跨字节中文", result.text());
    }

    @Test
    void chatCompletionsStreamJoinsMultilineData() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":\ndata: {\"content\":\"多行\"}}]}\n\n"
                + "data: [DONE]\n\n";
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null, null, idleDeadline());
        assertEquals("多行", result.text());
    }

    @Test
    void chatCompletionsStreamStopsAtFirstDone() throws Exception {
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"前\"}}]}",
                "[DONE]",
                "{\"choices\":[{\"delta\":{\"content\":\"后\"}}]}"), null, null, idleDeadline());
        assertEquals("前", result.text());
        assertTrue(result.terminalObserved());
    }

    @Test
    void chatCompletionsStreamReportsProviderUsage() throws Exception {
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":34,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":80}}}", "[DONE]"), null, null, idleDeadline());
        assertTrue(result.usage().reported());
        assertEquals("provider", result.usage().source());
        assertEquals(120, result.usage().inputTokens());
        assertEquals(34, result.usage().outputTokens());
        assertEquals(80, result.usage().cacheReadTokens());
        assertTrue(result.usage().cacheReadIncludedInInput());
        assertEquals(120, result.usage().totalInputTokens());
        assertEquals(154, result.usage().inputTokens() + result.usage().outputTokens());
    }

    @Test
    void openAiCachedPromptTokensAreNotChargedTwice() throws Exception {
        AiUpstreamClient.Usage usage = AiUpstreamClient.usageOfChatCompletion(mapper.readTree(
                "{\"usage\":{\"prompt_tokens\":2000,\"completion_tokens\":100,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":1000}}}"));
        assertEquals(2000, usage.inputTokens());
        assertEquals(1000, usage.cacheReadTokens());
        assertEquals(2000, usage.totalInputTokens());
        UsageRepository.ActualUsage actual = new UsageRepository.ActualUsage("provider", usage.inputTokens(),
                usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(),
                usage.cacheReadIncludedInInput());
        assertEquals(2100, actual.chargedTokens());
    }

    @Test
    void anthropicCacheBucketsAreCountedOnTopOfInput() throws Exception {
        AiUpstreamClient.Usage usage = AiUpstreamClient.usageOfAnthropic(mapper.readTree(
                "{\"usage\":{\"input_tokens\":100,\"output_tokens\":200,"
                        + "\"cache_read_input_tokens\":1000,\"cache_creation_input_tokens\":500}}"));
        assertEquals(1600, usage.totalInputTokens());
        UsageRepository.ActualUsage actual = new UsageRepository.ActualUsage("provider", usage.inputTokens(),
                usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(),
                usage.cacheReadIncludedInInput());
        assertEquals(1800, actual.chargedTokens());
    }

    @Test
    void anthropicStreamParsesThinkingAndText() throws Exception {
        InputStream body = new ByteArrayInputStream((
                "event: message_start\ndata: {\"type\":\"message_start\",\"usage\":{\"input_tokens\":100,"
                        + "\"cache_read_input_tokens\":1000,\"cache_creation_input_tokens\":500}}\n\n"
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
        assertEquals(100, result.usage().inputTokens());
        assertEquals(1000, result.usage().cacheReadTokens());
        assertEquals(500, result.usage().cacheWriteTokens());
        assertEquals(1600, result.usage().totalInputTokens());
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

    @Test
    void toolDefinitionsAreAddedOnlyWhenPresent() {
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "你好"));
        AiUpstreamClient.Upstream chat = new AiUpstreamClient.Upstream("https://x/v1", "k", "m",
                AiUpstreamClient.FORMAT_CHAT_COMPLETIONS, 128, false);
        AiUpstreamClient.Upstream anthropic = new AiUpstreamClient.Upstream("https://x", "k", "m",
                AiUpstreamClient.FORMAT_ANTHROPIC, 128, false);
        AiUpstreamClient.ToolDefinition tool = searchTool();

        assertFalse(client.buildChatCompletionRequest(chat, messages, false).containsKey("tools"),
                "没有工具时请求体保持旧形态");
        assertFalse(client.buildAnthropicRequest(anthropic, messages, false).containsKey("tools"));

        JsonNode chatTools = mapper.valueToTree(
                client.buildChatCompletionRequest(chat, messages, false, List.of(tool)).get("tools"));
        assertEquals("function", chatTools.get(0).path("type").asText());
        assertEquals("search", chatTools.get(0).path("function").path("name").asText());
        assertEquals("string", chatTools.get(0).path("function").path("parameters")
                .path("properties").path("q").path("type").asText());

        JsonNode anthropicTools = mapper.valueToTree(
                client.buildAnthropicRequest(anthropic, messages, false, List.of(tool)).get("tools"));
        assertEquals("search", anthropicTools.get(0).path("name").asText());
        assertEquals("object", anthropicTools.get(0).path("input_schema").path("type").asText());
    }

    @Test
    void toolDefinitionParametersAreDeeplyImmutable() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("type", "string");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("q", nested));
        AiUpstreamClient.ToolDefinition definition = new AiUpstreamClient.ToolDefinition("search", null, schema);

        nested.put("type", "number");
        schema.put("extra", true);

        assertEquals("", definition.description());
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals("string", ((Map<?, ?>) properties.get("q")).get("type"));
        assertFalse(definition.parameters().containsKey("extra"));
        assertThrows(UnsupportedOperationException.class, () -> definition.parameters().put("x", "y"));
    }

    @Test
    void anthropicRequestPairsToolUseWithToolResultAndMergesAdjacentTurns() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "系统"),
                Map.of("role", "user", "content", "问题"),
                assistantToolCall("call_1", "search", "{\"q\":\"笔记\"}"),
                Map.of("role", "tool", "tool_call_id", "call_1", "content", "结果"),
                Map.of("role", "user", "content", "继续"));

        Map<String, Object> request = client.buildAnthropicRequest(
                new AiUpstreamClient.Upstream("https://x", "k", "m", AiUpstreamClient.FORMAT_ANTHROPIC, 128, false),
                messages, false, List.of(searchTool()));

        JsonNode turns = mapper.valueToTree(request.get("messages"));
        assertEquals(3, turns.size(), "user / assistant / user(tool_result+text)");
        assertEquals("user", turns.get(0).path("role").asText());
        assertEquals("assistant", turns.get(1).path("role").asText());

        JsonNode use = turns.get(1).path("content").get(0);
        assertEquals("tool_use", use.path("type").asText());
        assertEquals("call_1", use.path("id").asText());
        assertEquals("search", use.path("name").asText());
        assertEquals("笔记", use.path("input").path("q").asText());

        JsonNode tail = turns.get(2).path("content");
        assertEquals(2, tail.size(), "tool_result 必须排在同一个 user 消息的最前面");
        assertEquals("tool_result", tail.get(0).path("type").asText());
        assertEquals("call_1", tail.get(0).path("tool_use_id").asText());
        assertEquals("结果", tail.get(0).path("content").asText());
        assertEquals("继续", tail.get(1).path("text").asText());
    }

    @Test
    void chatCompletionJsonParsesCompleteToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                    + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
                    + "{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"笔记\\\"}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        });
        server.start();
        try {
            AiUpstreamClient.Upstream upstream = new AiUpstreamClient.Upstream(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "k", "m",
                    AiUpstreamClient.FORMAT_CHAT_COMPLETIONS, 128, false);
            AiUpstreamClient.Result result = client.callWithTools(upstream,
                    List.of(Map.of("role", "user", "content", "查一下")), List.of(searchTool()),
                    false, null, 10, null);

            assertEquals(1, result.toolCalls().size());
            assertEquals("call_1", result.toolCalls().get(0).id());
            assertEquals("search", result.toolCalls().get(0).name());
            assertEquals("{\"q\":\"笔记\"}", result.toolCalls().get(0).arguments());
            assertEquals("", result.text(), "工具调用绝不能混进正文");
            assertEquals("tool_calls", result.finishReason());
            assertTrue(result.terminalObserved());
            assertEquals(10, result.usage().inputTokens());
            assertTrue(requestBody.get().contains("\"tools\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicJsonParsesToolUseBlocks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> respond(exchange, 200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"先查\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search\","
                        + "\"input\":{\"q\":\"笔记\"}}],"
                        + "\"stop_reason\":\"tool_use\","
                        + "\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}"));
        server.start();
        try {
            AiUpstreamClient.Upstream upstream = new AiUpstreamClient.Upstream(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "k", "m",
                    AiUpstreamClient.FORMAT_ANTHROPIC, 128, false);
            AiUpstreamClient.Result result = client.callWithTools(upstream,
                    List.of(Map.of("role", "user", "content", "查一下")), List.of(searchTool()),
                    false, null, 10, null);

            assertEquals("先查", result.text());
            assertEquals(1, result.toolCalls().size());
            assertEquals("toolu_1", result.toolCalls().get(0).id());
            assertEquals("search", result.toolCalls().get(0).name());
            assertEquals("{\"q\":\"笔记\"}", result.toolCalls().get(0).arguments());
            assertEquals("tool_use", result.finishReason());
            assertEquals(8, result.usage().inputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatCompletionStreamBuffersArgumentsByCallIndexUntilDone() throws Exception {
        List<String> listenerTypes = new ArrayList<>();
        List<String> listenerText = new ArrayList<>();
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"先查\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"k\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"a\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"\\\"b\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]"), (type, chunk) -> {
                    listenerTypes.add(type);
                    listenerText.add(chunk);
                    return true;
                }, null, idleDeadline());

        assertEquals(2, result.toolCalls().size());
        assertEquals("call_a", result.toolCalls().get(0).id());
        assertEquals("search", result.toolCalls().get(0).name());
        assertEquals("{\"q\":\"a\"}", result.toolCalls().get(0).arguments());
        assertEquals("call_b", result.toolCalls().get(1).id());
        assertEquals("{\"k\":\"b\"}", result.toolCalls().get(1).arguments());
        assertEquals("先查", result.text());
        assertEquals(List.of("text"), listenerTypes, "工具参数不得走 text/reasoning 回调");
        assertEquals(List.of("先查"), listenerText);
        assertTrue(result.terminalObserved());
    }

    @Test
    void chatCompletionStreamDropsToolCallsWithoutTerminalFrame() throws Exception {
        var failure = assertThrows(AiUpstreamException.class, () -> client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}"),
                null, null, idleDeadline()));
        var result = failure.partial();

        assertEquals(0, result.toolCalls().size(), "没有终止帧就不能把半截调用交给执行器");
        assertFalse(result.terminalObserved());
    }

    @Test
    void streamedToolCallsSupportLargeBatchesAndRejectOverflowAndFourKibArguments() throws Exception {
        List<String> events = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            events.add("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":" + index
                    + ",\"id\":\"call_" + index + "\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}");
        }
        events.add("[DONE]");
        AiUpstreamClient.Result capped = client.consumeChatCompletionStream(
                sse(events.toArray(String[]::new)), null, null, idleDeadline());
        assertEquals(128, capped.toolCalls().size(), "完整接收工具批次，不在第三项悄悄丢弃");
        events.add(128, "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":128,\"id\":\"overflow\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}");
        var oversized = client.consumeChatCompletionStream(sse(events.toArray(String[]::new)), null, null, idleDeadline());
        assertTrue(oversized.captureLimited());
        assertTrue(oversized.toolCalls().isEmpty(), "超量不能交付不完整批次");

        AiUpstreamClient.Result rejected = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"" + "a".repeat(5000)
                        + "\\\"}\"}}]}}]}",
                "[DONE]"), null, null, idleDeadline());
        assertEquals(0, rejected.toolCalls().size(), "超过 4 KiB 的参数必须整体拒绝");
    }

    @Test
    void malformedStreamedToolArgumentsFailClosed() throws Exception {
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"search\",\"arguments\":\"not-json\"}}]}}]}",
                "[DONE]"), null, null, idleDeadline());
        assertEquals(0, result.toolCalls().size());
        assertTrue(result.terminalObserved());
    }

    @Test
    void anthropicStreamBuffersInputJsonDeltasUntilMessageStop() throws Exception {
        List<String> listenerTypes = new ArrayList<>();
        String body = "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search\",\"input\":{}}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"笔记\\\"}\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        AiUpstreamClient.Result result = client.consumeAnthropicStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), (type, chunk) -> {
                    listenerTypes.add(type);
                    return true;
                }, null, idleDeadline());

        assertEquals(1, result.toolCalls().size());
        assertEquals("toolu_1", result.toolCalls().get(0).id());
        assertEquals("search", result.toolCalls().get(0).name());
        assertEquals("{\"q\":\"笔记\"}", result.toolCalls().get(0).arguments());
        assertEquals("tool_use", result.finishReason());
        assertTrue(result.terminalObserved());
        assertTrue(listenerTypes.isEmpty(), "工具参数不得走 text/reasoning 回调");
    }

    @Test
    void cancelledStreamDoesNotExposeBufferedToolCalls() throws Exception {
        ActiveTurnRegistry.Handle handle = new ActiveTurnRegistry.Handle("req-tool-cancel", 1L);
        AiUpstreamClient.Result result = client.consumeChatCompletionStream(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"继续\"}}]}",
                "[DONE]"), (type, chunk) -> {
                    handle.requestCancel();
                    return true;
                }, handle, idleDeadline());

        assertTrue(handle.cancelRequested());
        assertEquals(0, result.toolCalls().size(), "取消后不得执行任何工具调用");
    }

    private static AiUpstreamClient.ToolDefinition searchTool() {
        return new AiUpstreamClient.ToolDefinition("search", "查笔记本",
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))));
    }

    private static Map<String, Object> assistantToolCall(String id, String name, String arguments) {
        return Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                Map.of("id", id, "type", "function",
                        "function", Map.of("name", name, "arguments", arguments))));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
