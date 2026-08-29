package com.drillnotebook.app.service;

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

    /** 上游返回的最终结果：text=正文，reasoning=思考链（可能为空），finishReason=原始停止原因。 */
    public record Result(String text, String reasoning, String finishReason) {}

    /**
     * 流式监听器：每收到一个增量被调用一次。
     * @return false 表示下游已断开，客户端应中止读取上游。
     */
    public interface StreamListener {
        boolean onDelta(String type, String text);
    }

    /** 调用上游。stream=true 时逐 delta 回调 listener（可传 null 等价于只累积）。 */
    public Result call(Upstream upstream, List<Map<String, Object>> messages, boolean stream, StreamListener listener, int timeoutSeconds) {
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
        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException error) {
            throw new IllegalArgumentException("AI 请求超时（" + timeoutSeconds + " 秒），请换更快模型或加大超时");
        } catch (IOException error) {
            throw new IllegalArgumentException("AI 服务连接失败：" + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("AI 请求被中断");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(errorMessage(response.statusCode(), readAll(body)));
            }
            return anthropic
                    ? (stream ? consumeAnthropicStream(body, listener) : parseAnthropicResponse(body))
                    : (stream ? consumeChatCompletionStream(body, listener) : parseChatCompletionResponse(body));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (IOException error) {
            throw new IllegalArgumentException("AI 响应读取失败：" + error.getMessage());
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

    private Result parseChatCompletionResponse(InputStream body) throws IOException {
        JsonNode root = mapper.readTree(body);
        throwIfUpstreamError(root);
        String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
        JsonNode message = root.path("choices").path(0).path("message");
        String text = extractText(message);
        String reasoning = message.path("reasoning_content").asText("");
        return new Result(text, reasoning, finishReason);
    }

    private Result parseAnthropicResponse(InputStream body) throws IOException {
        JsonNode root = mapper.readTree(body);
        throwIfUpstreamError(root);
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) text.append(block.path("text").asText(""));
            else if ("thinking".equals(type)) reasoning.append(block.path("thinking").asText(""));
        }
        return new Result(text.toString(), reasoning.toString(), root.path("stop_reason").asText(""));
    }

    /** Chat Completions SSE：choices[0].delta.content / reasoning_content，data: [DONE] 结束。 */
    private Result consumeChatCompletionStream(InputStream body, StreamListener listener) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String[] finish = {""};
        readSse(body, (eventName, data) -> {
            if ("[DONE]".equals(data.trim())) return false;
            JsonNode node;
            try {
                node = mapper.readTree(data);
            } catch (IOException error) {
                return true; // 跳过无法解析的行
            }
            throwIfUpstreamError(node);
            JsonNode delta = node.path("choices").path(0).path("delta");
            String reasoningDelta = delta.path("reasoning_content").asText("");
            if (!reasoningDelta.isEmpty()) {
                reasoning.append(reasoningDelta);
                if (listener != null && !listener.onDelta("reasoning", reasoningDelta)) return false;
            }
            String textDelta = delta.path("content").asText("");
            if (!textDelta.isEmpty()) {
                text.append(textDelta);
                if (listener != null && !listener.onDelta("text", textDelta)) return false;
            }
            String finishReason = node.path("choices").path(0).path("finish_reason").asText("");
            if (!finishReason.isEmpty()) finish[0] = finishReason;
            return true;
        });
        return new Result(text.toString(), reasoning.toString(), finish[0]);
    }

    /** Anthropic SSE：content_block_delta 的 text_delta / thinking_delta，message_delta 带 stop_reason。 */
    private Result consumeAnthropicStream(InputStream body, StreamListener listener) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String[] finish = {""};
        readSse(body, (eventName, data) -> {
            JsonNode node;
            try {
                node = mapper.readTree(data);
            } catch (IOException error) {
                return true;
            }
            throwIfUpstreamError(node);
            String type = node.path("type").asText(eventName);
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                String deltaType = delta.path("type").asText("");
                if ("thinking_delta".equals(deltaType)) {
                    String chunk = delta.path("thinking").asText("");
                    if (!chunk.isEmpty()) {
                        reasoning.append(chunk);
                        if (listener != null && !listener.onDelta("reasoning", chunk)) return false;
                    }
                } else if ("text_delta".equals(deltaType)) {
                    String chunk = delta.path("text").asText("");
                    if (!chunk.isEmpty()) {
                        text.append(chunk);
                        if (listener != null && !listener.onDelta("text", chunk)) return false;
                    }
                }
            } else if ("message_delta".equals(type)) {
                String stopReason = node.path("delta").path("stop_reason").asText("");
                if (!stopReason.isEmpty()) finish[0] = stopReason;
            }
            return true;
        });
        return new Result(text.toString(), reasoning.toString(), finish[0]);
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

    private static String readAll(InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
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
