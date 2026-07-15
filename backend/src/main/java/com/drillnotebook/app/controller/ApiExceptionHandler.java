package com.drillnotebook.app.controller;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException error) {
        log.warn("请求失败 status={} reason={}", error.getStatusCode(), error.getReason());
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", "request_failed", "message", error.getReason() == null ? "请求失败" : error.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        log.warn("非法参数：{}", error.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", error.getMessage() == null ? "请求参数无效" : error.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception error) {
        log.error("未捕获异常", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal_error", "message", "服务暂时不可用"));
    }
}
