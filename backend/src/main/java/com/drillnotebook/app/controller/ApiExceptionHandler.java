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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        // 旧路径：服务层直接抛 IllegalArgumentException 的场景。message 已是简要文案。
        log.warn("非法参数：{}", error.getMessage());
        String message = error.getMessage() == null ? "请求参数无效" : error.getMessage();
        return ResponseEntity.badRequest().body(body("invalid_request", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception error) {
        // 兜底：对外不暴露任何细节，对内完整记录堆栈
        log.error("未捕获异常", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("internal_error", "服务暂时不可用，请稍后重试"));
    }

    private static Map<String, String> body(String code, String message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("error", code);
        result.put("errorCode", code);
        result.put("message", message);
        return result;
    }
}
