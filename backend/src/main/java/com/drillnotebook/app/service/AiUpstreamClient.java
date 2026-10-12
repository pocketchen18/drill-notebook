package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatStorageSizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一上游 AI 客户端：支持 OpenAI (Chat Completions) 与 Anthropic (Messages) 两种 API 格式，
 * 覆盖非流式调用、SSE 流式调用（含思考链 delta）与模型列表拉取。
 * 本类不持有任何密钥——由调用方解密后传入 Upstream。
 */
public class AiUpstreamClient {
    public static final String FORMAT_CHAT_COMPLETIONS = "chat_completions";
    public static final String FORMAT_ANTHROPIC = "anthropic";

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient http;
    private final ObjectMapper mapper;

    public AiUpstreamClient(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** 一次上游调用的全部入参（密钥已解密，调用结束后由调用方置空）。 */
    public record Upstream(
            String baseUrl,
            String apiKey,
            String model,
            String apiFormat,
            int maxTokens,
            boolean disableThinking) {

        public boolean anthropic() {
            return FORMAT_ANTHROPIC.equals(apiFormat);
        }
    }

    /**
     * 上游返回的最终结果（§7.2 第 4 条要求统一带终态标记）：
     * text=正文，reasoning=思考链（可空），finishReason=厂商停止原因或本地判定，
     * terminalObserved=是否看到终止帧/终止字段，captureLimited=是否触及回复捕获上限，
     * downstreamGone=前端连接已断（等同于用户主动停止），usage=厂商用量或 unknown。
     */
    public record Result(String text, String reasoning, String finishReason, boolean terminalObserved,
                         boolean captureLimited, boolean downstreamGone, Usage usage) {

        /** 旧的三参形态：结构化调用方只关心正文，其余按"正常结束、无用量信息"补齐。 */
        public Result(String text, String reasoning, String finishReason) {
            this(text, reasoning, finishReason, true, false, false, Usage.unknown());
        }
    }

    /**
     * 一次外发的用量（contracts §2.6）：只有厂商明确回了数字才算 provider，
     * 缺字段一律 unknown，绝不拿估算值冒充当过的钱。
     */
    public record Usage(String source, Integer inputTokens, Integer outputTokens, Integer cacheReadTokens,
                        Integer cacheWriteTokens) {
        public static Usage unknown() {
            return new Usage("unknown", null, null, null, null);
        }

        public boolean reported() {
            return "provider".equals(source) && (inputTokens != null || outputTokens != null);
        }
    }

    /**
     * 流式监听器：每收到一个增量被调用一次。
     * @return false 表示下游已断开，客户端应中止读取上游。
     */
    public interface StreamListener {
        boolean onDelta(String type, String text);
    }

    /** 调用上游（无取消句柄：结构化解析、测活等一次性内部调用）。 */
    public Result call(Upstream upstream, List<Map<String, Object>> messages, boolean stream, StreamListener listener,
                       int timeoutSeconds) {
        return call(upstream, messages, stream, listener, timeoutSeconds, null);
    }

    /**
     * 调用上游。{@code handle} 非空时它同时承担"用户停止"和"总期限到期"两个中断源：
     * 期限覆盖建连、响应头与响应体读取，到期或取消都立刻关闭响应体，读取循环与结果提交前各查一次（§7.2.2）。
     * 只有句柄 claim 到终态的那条路径才允许交出结果，取消后晚到的正文不再算 COMPLETED。
     */
    public Result call(Upstream upstream, List<Map<String, Object>> messages, boolean stream, StreamListener listener,
                       int timeoutSeconds, ActiveTurnRegistry.Handle handle) {
        boolean anthropic = upstream.anthropic();
        String target = anthropic ? anthropicMessagesUrl(upstream.baseUrl()) : chatCompletionsUrl(upstream.baseUrl());
        Map<String, Object> request = anthropic
                ? buildAnthropicRequest(upstream, messages, stream)
                : buildChatCompletionRequest(upstream, messages, stream);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request)));
        if (anthropic) {
            builder.header("x-api-key", upstream.apiKey());
            builder.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.header("Authorization", "Bearer " + upstream.apiKey());
        }
        HttpRequest httpRequest = builder.build();
        Deadline deadline = new Deadline(Math.max(timeoutSeconds, 1) + 1L);
        if (handle != null) handle.bindAbortHook(deadline::expire);
        if (stopped(handle, deadline)) {
            // 排队期间用户已经点了停止：连一个字节都不必发出去（§7.3 服务端 token 与前端 abort 同效）
            deadline.cancel();
            return new Result("", "", reason(handle, deadline), false, false, true, Usage.unknown());
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException error) {
            deadline.cancel();
            throw new IllegalArgumentException("AI 请求超时（" + timeoutSeconds + " 秒），请换更快模型或加大超时");
        } catch (IOException error) {
            deadline.cancel();
            throw new IllegalArgumentException("AI 服务连接失败：" + error.getMessage());
        } catch (InterruptedException error) {
            deadline.cancel();
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("AI 请求被中断");
        }
        InputStream body = response.body();
        deadline.attach(body);
        // 尚未取得响应体时取消只能靠 HttpRequest.timeout；刚取得时若已取消/到期，立即关闭，一字节都不读
        if (stopped(handle, deadline)) {
            deadline.cancel();
            close(body);
            return new Result("", "", reason(handle, deadline), false, false, true, Usage.unknown());
        }
        try {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 错误体只取到上限就停：不能为了拼一句错误信息而无界读
                throw new IllegalArgumentException(errorMessage(response.statusCode(), readForError(body)));
            }
            Result result = anthropic
                    ? (stream ? consumeAnthropicStream(body, listener, handle, deadline)
                    : parseAnthropicResponse(readBounded(body)))
                    : (stream ? consumeChatCompletionStream(body, listener, handle, deadline)
                    : parseChatCompletionResponse(readBounded(body)));
            if (stopped(handle, deadline)) {
                // 已经收到的部分照常交回，但绝不能算成正常完成（§7.2.2 竞争只留一个终态）
                return new Result(result.text(), result.reasoning(), reason(handle, deadline), false,
                        result.captureLimited(), true, result.usage());
            }
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (ResponseTooLargeException error) {
            // 超限只给空正文 + TRUNCATED 标记，不冒充"正常但没内容"（§2.7）
            return new Result("", "", "OUTPUT_CAPTURE_LIMIT", false, true, false, Usage.unknown());
        } catch (IOException error) {
            // 到期或取消会表现为读失败：这是"无终止帧"，不是上游正常完成
            if (stopped(handle, deadline)) {
                return new Result("", "", reason(handle, deadline), false, false, true, Usage.unknown());
            }
            throw new IllegalArgumentException("AI 响应读取失败：" + error.getMessage());
        } finally {
            deadline.cancel();
            close(body);
            if (handle != null) handle.bindAbortHook(null);
        }
    }

    private static boolean stopped(ActiveTurnRegistry.Handle handle, Deadline deadline) {
        return deadline.expired() || (handle != null && handle.cancelRequested());
    }

    private static String reason(ActiveTurnRegistry.Handle handle, Deadline deadline) {
        if (handle != null && handle.cancelRequested()) return "aborted";
        return deadline.expired() ? "deadline" : "stream_error";
    }

    private static void close(java.io.Closeable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (IOException ignored) {
            // 关闭失败不掩盖原始结果
        }
    }

    /**
     * 总期限看门狗（§7.2.2）：到期就关闭已附上的响应体，让阻塞中的读取立刻失败。
     * 单线程守护调度器，一次调用一个任务，取消路径与 finally 都会撤销，不留悬空任务。
     */
    static final class Deadline {
        private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ai-upstream-deadline");
            thread.setDaemon(true);
            return thread;
        });

        private final AtomicBoolean expired = new AtomicBoolean();
        private final AtomicReference<InputStream> attached = new AtomicReference<>();
        private final ScheduledFuture<?> future;

        Deadline(long seconds) {
            this.future = WATCHDOG.schedule(this::expire, seconds, TimeUnit.SECONDS);
        }

        void attach(InputStream body) {
            attached.set(body);
            if (expired.get()) close(body);
        }

        void expire() {
            expired.set(true);
            close(attached.get());
        }

        boolean expired() {
            return expired.get();
        }

        void cancel() {
            future.cancel(false);
        }
    }

    /** 拉取可用模型列表（按字母序返回 id）。 */
    public List<String> listModels(Upstream upstream) {
        boolean anthropic = upstream.anthropic();
        String target = anthropic ? anthropicModelsUrl(upstream.baseUrl()) : chatCompletionsModelsUrl(upstream.baseUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (anthropic) {
            builder.header("x-api-key", upstream.apiKey());
            builder.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.header("Authorization", "Bearer " + upstream.apiKey());
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException error) {
            throw new IllegalArgumentException("获取模型列表超时，请稍后重试");
        } catch (IOException error) {
            throw new IllegalArgumentException("获取模型列表失败：" + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("获取模型列表被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException(errorMessage(response.statusCode(), response.body()));
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            List<String> models = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) models.add(id);
            }
            models.sort(String.CASE_INSENSITIVE_ORDER);
            return models;
        } catch (IOException error) {
            throw new IllegalArgumentException("模型列表解析失败：" + error.getMessage());
        }
    }

    // ---------- URL 归一化 ----------

    static String chatCompletionsUrl(String baseUrl) {
        String base = trimTrailingSlash(baseUrl);
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    static String chatCompletionsModelsUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + "/models";
    }

    /** 用户填写 https://host 或 https://host/v1 均可，统一落到 /v1/messages。 */
    static String anthropicMessagesUrl(String baseUrl) {
        String base = trimTrailingSlash(baseUrl);
        return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    static String anthropicModelsUrl(String baseUrl) {
        String base = trimTrailingSlash(baseUrl);
        return base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
    }

    private static String trimTrailingSlash(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (base.isBlank()) throw new IllegalArgumentException("Base URL 不能为空");
        return base;
    }

    // ---------- 请求体构建 ----------

    Map<String, Object> buildChatCompletionRequest(Upstream upstream, List<Map<String, Object>> messages, boolean stream) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", upstream.model());
        request.put("messages", messages);
        request.put("stream", stream);
        request.put("max_tokens", upstream.maxTokens());
        if (upstream.disableThinking()) {
            request.put("enable_thinking", false);
            // 部分兼容网关读取 chat_template_kwargs
            request.put("chat_template_kwargs", Map.of("enable_thinking", false));
            request.put("temperature", 0.2);
        }
        return request;
    }

    Map<String, Object> buildAnthropicRequest(Upstream upstream, List<Map<String, Object>> messages, boolean stream) {
        AnthropicPayload payload = toAnthropic(messages);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", upstream.model());
        if (!payload.system().isBlank()) request.put("system", payload.system());
        request.put("messages", payload.messages());
        request.put("max_tokens", Math.max(upstream.maxTokens(), 1024));
        request.put("stream", stream);
        return request;
    }

    record AnthropicPayload(String system, List<Map<String, Object>> messages) {}

    /** OpenAI messages → Anthropic 格式：system 消息提升为顶层 system；content 数组转为内容块。 */
    static AnthropicPayload toAnthropic(List<Map<String, Object>> messages) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            String role = String.valueOf(message.get("role"));
            Object content = message.get("content");
            if ("system".equals(role)) {
                String text = openAiText(content);
                if (!text.isBlank()) system.append(text).append("\n\n");
                continue;
            }
            out.add(Map.of("role", role, "content", toAnthropicBlocks(content)));
        }
        return new AnthropicPayload(system.toString().strip(), out);
    }

    static List<Map<String, Object>> toAnthropicBlocks(Object content) {
        if (content instanceof String text) {
            return List.of(Map.of("type", "text", "text", text));
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (content instanceof List<?> parts) {
            for (Object raw : parts) {
                if (!(raw instanceof Map<?, ?> part)) continue;
                String type = String.valueOf(part.get("type"));
                if ("text".equals(type)) {
                    blocks.add(Map.of("type", "text", "text", String.valueOf(part.get("text"))));
                } else if ("image_url".equals(type)) {
                    Object urlHolder = part.get("image_url");
                    String url = urlHolder instanceof Map<?, ?> holder ? String.valueOf(holder.get("url")) : String.valueOf(urlHolder);
                    blocks.add(anthropicImageBlock(url));
                }
            }
        }
        if (blocks.isEmpty()) blocks.add(Map.of("type", "text", "text", ""));
        return blocks;
    }

    static Map<String, Object> anthropicImageBlock(String url) {
        if (url != null && url.startsWith("data:")) {
            int comma = url.indexOf(',');
            String header = comma > 5 ? url.substring(5, comma) : "image/png;base64";
            String mime = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            return Map.of("type", "image", "source", Map.of(
                    "type", "base64", "media_type", mime, "data", comma >= 0 ? url.substring(comma + 1) : ""));
        }
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url == null ? "" : url));
    }

    private static String openAiText(Object content) {
        if (content instanceof String text) return text;
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object raw : parts) {
                if (raw instanceof Map<?, ?> part && "text".equals(String.valueOf(part.get("type")))) {
                    builder.append(part.get("text"));
                }
            }
            return builder.toString();
        }
        return String.valueOf(content);
    }

    // ---------- 响应解析 ----------

    private Result parseChatCompletionResponse(String raw) throws IOException {
        JsonNode root = mapper.readTree(raw);
        throwIfUpstreamError(root);
        String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
        JsonNode message = root.path("choices").path(0).path("message");
        String text = extractText(message);
        String reasoning = message.path("reasoning_content").asText("");
        return new Result(text, reasoning, finishReason, !finishReason.isEmpty() || !text.isEmpty(),
                false, false, usageOfChatCompletion(root));
    }

    private Result parseAnthropicResponse(String raw) throws IOException {
        JsonNode root = mapper.readTree(raw);
        throwIfUpstreamError(root);
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) text.append(block.path("text").asText(""));
            else if ("thinking".equals(type)) reasoning.append(block.path("thinking").asText(""));
        }
        String stopReason = root.path("stop_reason").asText("");
        return new Result(text.toString(), reasoning.toString(), stopReason, !stopReason.isEmpty(),
                false, false, usageOfAnthropic(root));
    }

    /** OpenAI 风格用量：只有厂商回了数字才算 provider，缓存写入字段并非所有网关都有。 */
    static Usage usageOfChatCompletion(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) return Usage.unknown();
        Integer input = intOrNull(usage.path("prompt_tokens"));
        Integer output = intOrNull(usage.path("completion_tokens"));
        JsonNode details = usage.path("prompt_tokens_details");
        Integer cacheRead = intOrNull(details.path("cached_tokens"));
        Integer cacheWrite = intOrNull(details.path("cache_write_tokens"));
        if (input == null && output == null && cacheRead == null && cacheWrite == null) return Usage.unknown();
        return new Usage("provider", input, output, cacheRead, cacheWrite);
    }

    /** Anthropic 风格用量：缓存读/写分开计费，缺失即 null（不折算进 input）。 */
    static Usage usageOfAnthropic(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) return Usage.unknown();
        Integer input = intOrNull(usage.path("input_tokens"));
        Integer output = intOrNull(usage.path("output_tokens"));
        Integer cacheRead = intOrNull(usage.path("cache_read_input_tokens"));
        Integer cacheWrite = intOrNull(usage.path("cache_creation_input_tokens"));
        if (input == null && output == null && cacheRead == null && cacheWrite == null) return Usage.unknown();
        return new Usage("provider", input, output, cacheRead, cacheWrite);
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || !node.isNumber()) return null;
        return node.asInt();
    }

    /** Chat Completions SSE：choices[0].delta.content / reasoning_content，data: [DONE] 结束。 */
    Result consumeChatCompletionStream(InputStream body, StreamListener listener,
                                       ActiveTurnRegistry.Handle handle, Deadline deadline) throws IOException {
        Capture capture = new Capture();
        String[] finish = {""};
        Usage[] usage = {Usage.unknown()};
        boolean[] terminal = {false};
        boolean[] downstreamGone = {false};
        readSse(body, (eventName, data) -> {
            if (stopped(handle, deadline)) return false;
            if ("[DONE]".equals(data.trim())) {
                terminal[0] = true;
                return false;
            }
            JsonNode node;
            try {
                node = mapper.readTree(data);
            } catch (IOException error) {
                return true; // 跳过无法解析的行
            }
            throwIfUpstreamError(node);
            Usage parsed = usageOfChatCompletion(node);
            if (parsed.reported()) usage[0] = parsed;
            JsonNode delta = node.path("choices").path(0).path("delta");
            String reasoningKept = capture.keepReasoning(delta.path("reasoning_content").asText(""));
            if (!reasoningKept.isEmpty() && listener != null && !listener.onDelta("reasoning", reasoningKept)) {
                downstreamGone[0] = true;
                return false;
            }
            String textKept = capture.keepText(delta.path("content").asText(""));
            if (!textKept.isEmpty() && listener != null && !listener.onDelta("text", textKept)) {
                downstreamGone[0] = true;
                return false;
            }
            String finishReason = node.path("choices").path(0).path("finish_reason").asText("");
            if (!finishReason.isEmpty()) {
                finish[0] = finishReason;
                terminal[0] = true;
            }
            return !capture.limited();
        });
        return new Result(capture.text(), capture.reasoning(), finish[0], terminal[0], capture.limited(),
                downstreamGone[0], usage[0]);
    }

    /** Anthropic SSE：content_block_delta 的 text_delta / thinking_delta，message_delta 带 stop_reason。 */
    Result consumeAnthropicStream(InputStream body, StreamListener listener,
                                  ActiveTurnRegistry.Handle handle, Deadline deadline) throws IOException {
        Capture capture = new Capture();
        String[] finish = {""};
        Usage[] usage = {Usage.unknown()};
        boolean[] terminal = {false};
        boolean[] downstreamGone = {false};
        readSse(body, (eventName, data) -> {
            if (stopped(handle, deadline)) return false;
            JsonNode node;
            try {
                node = mapper.readTree(data);
            } catch (IOException error) {
                return true;
            }
            throwIfUpstreamError(node);
            String type = node.path("type").asText(eventName);
            Usage parsed = usageOfAnthropic(node);
            if (parsed.reported()) usage[0] = parsed;
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                String deltaType = delta.path("type").asText("");
                if ("thinking_delta".equals(deltaType)) {
                    String kept = capture.keepReasoning(delta.path("thinking").asText(""));
                    if (!kept.isEmpty() && listener != null && !listener.onDelta("reasoning", kept)) {
                        downstreamGone[0] = true;
                        return false;
                    }
                } else if ("text_delta".equals(deltaType)) {
                    String kept = capture.keepText(delta.path("text").asText(""));
                    if (!kept.isEmpty() && listener != null && !listener.onDelta("text", kept)) {
                        downstreamGone[0] = true;
                        return false;
                    }
                }
            } else if ("message_delta".equals(type)) {
                String stopReason = node.path("delta").path("stop_reason").asText("");
                if (!stopReason.isEmpty()) finish[0] = stopReason;
                Usage tail = usageOfAnthropic(node);
                if (tail.reported()) usage[0] = tail;
            } else if ("message_stop".equals(type)) {
                terminal[0] = true;
            } else if ("error".equals(type)) {
                throw new IllegalArgumentException("AI 服务返回错误：" + node.path("error").path("message").asText("未知"));
            }
            return !capture.limited();
        });
        return new Result(capture.text(), capture.reasoning(), finish[0],
                terminal[0] || !finish[0].isEmpty(), capture.limited(), downstreamGone[0], usage[0]);
    }

    /**
     * 正文与思考链共用同一个捕获额度（contracts §2.7、执行方案 §7.4-3）：
     * 超过 1 MiB 原始 UTF-8 就停止累加并关闭上游，保留的必须停在完整码点边界上。
     */
    private static final class Capture {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private long used;
        private boolean limited;

        String keepText(String chunk) {
            return keep(chunk, text);
        }

        String keepReasoning(String chunk) {
            return keep(chunk, reasoning);
        }

        private String keep(String chunk, StringBuilder target) {
            if (limited || chunk == null || chunk.isEmpty()) return "";
            StringBuilder kept = new StringBuilder();
            for (int offset = 0; offset < chunk.length(); ) {
                int codePoint = chunk.codePointAt(offset);
                long bytes = utf8Length(codePoint);
                if (used + bytes > ChatStorageSizer.OUTPUT_CAPTURE_RAW_BYTES) {
                    limited = true;
                    break;
                }
                used += bytes;
                kept.appendCodePoint(codePoint);
                target.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            }
            return kept.toString();
        }

        String text() {
            return text.toString();
        }

        String reasoning() {
            return reasoning.toString();
        }

        boolean limited() {
            return limited;
        }

        private static long utf8Length(int codePoint) {
            if (codePoint <= 0x7F) return 1;
            if (codePoint <= 0x7FF) return 2;
            if (codePoint <= 0xFFFF) return 3;
            return 4;
        }
    }

    interface SseHandler {
        /** @return false 停止读取。 */
        boolean onEvent(String eventName, String data);
    }

    /** 通用 SSE 行解析：按空行分帧，event:/data: 行归一。 */
    private void readSse(InputStream body, SseHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventName = "";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (data.length() > 0) {
                        boolean keep = handler.onEvent(eventName, data.toString());
                        if (!keep) return;
                    }
                    eventName = "";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.length() > 5 ? line.substring(5).trim() : "");
                }
                // 忽略 id:/retry:/注释行
            }
            if (data.length() > 0) handler.onEvent(eventName, data.toString());
        }
    }

    private void throwIfUpstreamError(JsonNode node) {
        JsonNode error = node.path("error");
        if (error.isObject()) {
            String message = error.path("message").asText(error.path("type").asText("上游返回错误"));
            throw new IllegalArgumentException("AI 服务返回错误：" + message);
        }
    }

    private static String extractText(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) return "";
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            String text = content.asText("").trim();
            if (!text.isBlank()) return text;
        } else if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if (part == null) continue;
                if (part.isTextual()) builder.append(part.asText());
                else if ("text".equals(part.path("type").asText(""))) builder.append(part.path("text").asText(""));
                else if (part.has("text")) builder.append(part.path("text").asText(""));
            }
            String text = builder.toString().trim();
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalArgumentException("请求序列化失败");
        }
    }

    /**
     * 响应体硬上限读取（contracts §2.7）：先给 8 MiB 原始上限再解析，禁止无上限 readAllBytes。
     * 超限不再冒充正常空回复——交回空正文并标 TRUNCATED/OUTPUT_CAPTURE_LIMIT。
     */
    private static final long MAX_RAW_RESPONSE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_ERROR_BODY_BYTES = 1024 * 1024;

    static String readBounded(InputStream body) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
        long total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            if (total + read > MAX_RAW_RESPONSE_BYTES) {
                throw new ResponseTooLargeException();
            }
            total += read;
            kept.write(buffer, 0, read);
        }
        return kept.toString(StandardCharsets.UTF_8);
    }

    /** 错误响应体只读到 1 MiB（文案后面还要压成 240 码点），不能为了拼错误信息而无限占内存。 */
    static String readForError(InputStream body) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
        long total = 0;
        int read;
        while (total < MAX_ERROR_BODY_BYTES && (read = body.read(buffer)) != -1) {
            int take = (int) Math.min(read, MAX_ERROR_BODY_BYTES - total);
            kept.write(buffer, 0, take);
            total += take;
        }
        return kept.toString(StandardCharsets.UTF_8);
    }

    /** 响应体超过 8 MiB 上限：调用方按捕获超限处理，不继续吃内存。 */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("上游响应体超过 8 MiB 硬上限");
        }
    }

    static String errorMessage(int status, String body) {
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 240) snippet = snippet.substring(0, 240) + "…";
        if (status == 401 || status == 403) {
            return "AI 服务拒绝访问（HTTP " + status + "）。请检查 API Key、模型权限或额度。"
                    + (snippet.isBlank() ? "" : " 详情：" + snippet);
        }
        if (status == 404) {
            return "AI 服务路径不存在（HTTP 404）。请检查 Base URL 与 API 格式是否匹配。"
                    + (snippet.isBlank() ? "" : " 详情：" + snippet);
        }
        if (status == 429) {
            return "AI 服务限流（HTTP 429），请稍后重试。" + (snippet.isBlank() ? "" : " 详情：" + snippet);
        }
        return "AI 服务请求失败（HTTP " + status + "）" + (snippet.isBlank() ? "" : "：" + snippet);
    }
}
