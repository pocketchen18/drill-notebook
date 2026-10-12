package com.drillnotebook.app.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一异常处理：对外只返回稳定的 error code + 用户可读的简要 message，
 * 内部通过 logger 记录完整堆栈与原因，便于排查。
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> business(BusinessException error) {
        // 业务异常：message 本身就是面向用户的简要文案
        log.warn("业务失败 code={} message={} cause={}", error.code(), error.getMessage(),
                error.getCause() == null ? "" : error.getCause().toString());
        if (error.getCause() != null) {
            log.warn("业务失败原因详情", error.getCause());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(error.code(), error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException error) {
        log.warn("请求失败 status={} reason={}", error.getStatusCode(), error.getReason());
        String message = error.getReason() == null ? "请求失败" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(body("request_failed", message));
    }

    /**
     * 轮次拒绝：容量、并发、幂等或存储降级各有各的状态码，
     * 前端要能区分"清历史"、"等上一轮"和"稍后重试"，不能都当参数错误。
     */
    @ExceptionHandler(com.drillnotebook.app.service.ChatTurnRejectException.class)
    public ResponseEntity<Map<String, Object>> chatTurnReject(com.drillnotebook.app.service.ChatTurnRejectException error) {
        log.warn("轮次拒绝 status={} code={} message={}", error.status(), error.code(), error.getMessage());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error.code());
        payload.put("errorCode", error.code());
        payload.put("message", error.getMessage());
        payload.putAll(error.details());
        return ResponseEntity.status(error.status()).body(payload);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        // 旧路径：服务层直接抛 IllegalArgumentException 的场景。message 已是简要文案。
        log.warn("非法参数：{}", error.getMessage());
        String message = error.getMessage() == null ? "请求参数无效" : error.getMessage();
        return ResponseEntity.badRequest().body(body("invalid_request", message));
    }

    /**
     * 请求体读不出来的两种真相要分开报：越界（§4.3 的 20 MiB，含 Jackson 自身的单字符串护栏）是 413，
     * JSON 本来不合法才是 400。都塞进兜底当 500 的话，前端会把"图太大"当成"服务坏了"反复重试。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException error,
            jakarta.servlet.http.HttpServletRequest request) {
        if (bodyTooLarge(request, error)) {
            log.warn("聊天请求体越界：{}", error.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body(
                    com.drillnotebook.app.config.ChatBodyLimitFilter.ERROR_CODE,
                    com.drillnotebook.app.config.ChatBodyLimitFilter.ERROR_MESSAGE));
        }
        log.warn("请求体解析失败：{}", error.getMessage());
        return ResponseEntity.badRequest().body(body("invalid_request", "请求体不是合法 JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception error,
                                                        jakarta.servlet.http.HttpServletRequest request) {
        if (bodyTooLarge(request, error)) {
            log.warn("聊天请求体越界：{}", error.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body(
                    com.drillnotebook.app.config.ChatBodyLimitFilter.ERROR_CODE,
                    com.drillnotebook.app.config.ChatBodyLimitFilter.ERROR_MESSAGE));
        }
        // 兜底：对外不暴露任何细节，对内完整记录堆栈
        log.error("未捕获异常", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("internal_error", "服务暂时不可用，请稍后重试"));
    }

    /**
     * 越界判定不看异常被包装成什么：过滤器在请求上留旗标，Jackson 的体量护栏留在 cause 链里，
     * 两条信号任一命中即按 §4.3 的 413 处理。
     */
    private static boolean bodyTooLarge(jakarta.servlet.http.HttpServletRequest request, Throwable error) {
        if (Boolean.TRUE.equals(request.getAttribute(
                com.drillnotebook.app.config.ChatBodyLimitFilter.TRIP_ATTRIBUTE))) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.drillnotebook.app.config.ChatBodyLimitFilter.BodyTooLargeException
                    || cause instanceof com.fasterxml.jackson.core.exc.StreamConstraintsException) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> body(String code, String message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("error", code);
        result.put("errorCode", code);
        result.put("message", message);
        return result;
    }
}
