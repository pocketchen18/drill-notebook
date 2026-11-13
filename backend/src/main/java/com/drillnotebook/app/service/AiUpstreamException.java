package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 供应商失败的结构化分类，部分输出只用于原轮次恢复。 */
public final class AiUpstreamException extends IllegalArgumentException {
    public enum Kind { REQUEST, STREAM, NETWORK }
    private final Kind kind;
    private final boolean retryable;
    private final Long retryAt;
    private final AiUpstreamClient.Result partial;
    private final boolean busyNeedsAdvice;

    private AiUpstreamException(Kind kind, boolean retryable, String error, Long retryAt, AiUpstreamClient.Result partial) {
        this(kind, retryable, error, retryAt, partial, false);
    }
    private AiUpstreamException(Kind kind, boolean retryable, String error, Long retryAt, AiUpstreamClient.Result partial,
                                boolean busyNeedsAdvice) {
        super(error);
        this.kind = kind;
        this.retryable = retryable;
        this.retryAt = retryAt;
        this.partial = partial;
        this.busyNeedsAdvice = busyNeedsAdvice;
    }

    public Kind kind() { return kind; }
    public boolean retryable() { return retryable; }
    public Long retryAt() { return retryAt; }
    public AiUpstreamClient.Result partial() { return partial; }
    public AiUpstreamException withPartial(AiUpstreamClient.Result value) {
        return new AiUpstreamException(kind, retryable, getMessage(), retryAt, value, busyNeedsAdvice);
    }
    public AiUpstreamException redacted(String key) {
        return new AiUpstreamException(kind, retryable, safe(getMessage(), key), retryAt, partial, busyNeedsAdvice);
    }
    AiUpstreamException withResponseAdvice(HttpHeaders headers, long receivedAt) {
        if (kind != Kind.STREAM) return this;
        Long advisedAt = retryAt(headers, null, receivedAt);
        return advisedAt == null ? this : new AiUpstreamException(kind, retryable || busyNeedsAdvice,
                getMessage(), advisedAt, partial, busyNeedsAdvice);
    }

    static AiUpstreamException http(int status, String body, HttpHeaders headers, long receivedAt, String key) {
        JsonNode error = parse(body);
        String code = code(error);
        String message = message(error, body);
        Long retryAt = retryAt(headers, error, receivedAt);
        boolean quota = quota(code + " " + message);
        boolean retryable = !quota && (status >= 500 && status <= 599 || status == 429 || status == 408);
        Kind kind = status == 429 ? Kind.STREAM : Kind.REQUEST;
        String text = "HTTP " + status + (code.isBlank() ? "" : " · " + code) + (message.isBlank() ? "" : "\n" + message);
        return new AiUpstreamException(kind, retryable, safe(text, key), retryAt, null);
    }

    static AiUpstreamException event(JsonNode node) {
        JsonNode error = node.path("error");
        String code = code(error);
        String message = message(error, "上游返回错误");
        String lowered = (code + " " + message).toLowerCase(Locale.ROOT);
        Long retryAt = retryAt(null, error, System.currentTimeMillis());
        boolean busy = lowered.contains("overloaded") || lowered.contains("server_busy");
        boolean recoverable = !quota(lowered) && (lowered.contains("rate_limit") || lowered.contains("rate limit")
                || lowered.contains("server_error") || lowered.contains("api_error") || lowered.contains("stream_disconnected")
                || busy && retryAt != null);
        return new AiUpstreamException(Kind.STREAM, recoverable,
                safe((code.isBlank() ? "" : code + "\n") + message, null), retryAt, null, busy && !quota(lowered));
    }

    static AiUpstreamException disconnected(String reason, AiUpstreamClient.Result partial) {
        String detail = reason == null || reason.isBlank() ? "stream disconnected before completion" : reason;
        return new AiUpstreamException(Kind.STREAM, true, safe("stream_disconnected\n" + detail, null), null, partial);
    }

    static AiUpstreamException transport(Throwable error) {
        boolean network = false, tls = false;
        String message = "";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            network |= cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException;
            tls |= cause instanceof javax.net.ssl.SSLException;
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        if (message.isBlank()) message = error.getClass().getSimpleName();
        return new AiUpstreamException(network ? Kind.NETWORK : Kind.REQUEST, !tls,
                safe("AI 服务连接失败：" + message, null), null, null);
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body == null ? "" : body);
            return root != null && root.path("error").isObject() ? root.path("error") : root;
        } catch (Exception ignored) { return null; }
    }
    private static String code(JsonNode error) {
        return error == null ? "" : error.path("code").asText(error.path("type").asText(""));
    }
    private static String message(JsonNode error, String fallback) {
        return error == null ? fallback : error.path("message").asText(error.isTextual() ? error.asText() : "上游返回错误");
    }
    private static boolean quota(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return value.contains("insufficient_quota") || value.contains("quota_exceeded") || value.contains("quota exceeded")
                || value.contains("usage_limit_reached") || value.contains("usage_limit_exceeded")
                || value.contains("exceeded your current quota") || value.contains("insufficient credits")
                || value.contains("billing") || value.contains("credit balance") || value.contains("payment_required")
                || value.contains("余额不足") || value.contains("额度用尽");
    }

    /** 截止时刻从收到响应头时计算，读取错误体及展示的耗时不会延长它。 */
    static Long retryAt(HttpHeaders headers, JsonNode error, long receivedAt) {
        if (headers != null) {
            String value = headers.firstValue("retry-after").orElse("").trim();
            if (!value.isEmpty()) {
                try {
                    double seconds = Double.parseDouble(value);
                    if (Double.isFinite(seconds) && seconds >= 0 && seconds <= Integer.MAX_VALUE)
                        return receivedAt + (long) (seconds * 1000);
                } catch (NumberFormatException ignored) {
                    try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
                    catch (RuntimeException invalid) { /* 无效建议回到本地退避。 */ }
                }
            }
        }
        if (error != null) for (String key : new String[] { "retry_after_ms", "retry_after_seconds" }) {
            JsonNode value = error.path(key);
            double delay = value.asDouble(-1);
            if (value.isNumber() && Double.isFinite(delay) && delay >= 0 && delay <= Integer.MAX_VALUE)
                return receivedAt + (long) (delay * (key.endsWith("seconds") ? 1000 : 1));
        }
        return null;
    }

    static String safe(String text, String key) {
        String value = text == null ? "" : text;
        if (key != null && !key.isBlank()) value = value.replace(key, "[已隐藏]");
        value = value.replaceAll("(?i)(bearer\\s+)[^\\s,;\\\"]+", "$1[已隐藏]")
                .replaceAll("(?i)([\\\"]?(?:api[_-]?key|authorization|access[_-]?token|secret|password)[\\\"]?\\s*[:=]\\s*)[\\\"]?[^\\s,;}\\\"]+[\\\"]?", "$1[已隐藏]")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");
        return value.length() > 4000 ? value.substring(0, 4000) + "…" : value;
    }
}
