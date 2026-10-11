package com.drillnotebook.app.service;

import java.util.Map;

/**
 * 轮次拒绝：容量、并发、幂等、时效或存储降级导致的开始失败。
 *
 * <p>带稳定 code 与状态码，前端据此区分「额度满/会话忙/重放冲突」，不能一概当成 400 参数错误
 * （执行方案 §7.2、§7.4）。</p>
 */
public class ChatTurnRejectException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ChatTurnRejectException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
