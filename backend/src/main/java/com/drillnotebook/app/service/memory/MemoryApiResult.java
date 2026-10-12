package com.drillnotebook.app.service.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆接口的响应载体：沿用本仓库「服务定状态码与响应体、控制器只做 HTTP 映射」的写法。
 *
 * <p>错误体形状是 contracts §3 的 {@code {code,message,retryable?,details?}}，message 一律脱敏——
 * 不带密码、正文、SQL 片段或堆栈。</p>
 */
public record MemoryApiResult(int status, Map<String, Object> body) {

    public static MemoryApiResult of(int status, Map<String, Object> body) {
        return new MemoryApiResult(status, body);
    }

    /** 204 无正文。 */
    public static MemoryApiResult noContent() {
        return new MemoryApiResult(204, Map.of());
    }

    public static MemoryApiResult error(int status, String code, String message) {
        return error(status, code, message, false, null);
    }

    public static MemoryApiResult error(int status, String code, String message, boolean retryable,
                                        Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        if (retryable) {
            body.put("retryable", true);
        }
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return new MemoryApiResult(status, body);
    }
}
