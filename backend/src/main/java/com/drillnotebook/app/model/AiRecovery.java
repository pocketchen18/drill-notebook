package com.drillnotebook.app.model;

/** 可公开的恢复事实；只存安全报错，不含请求、密钥或工具原始数据。 */
public record AiRecovery(String kind, String state, int attempt, Integer limit, String error, Long nextRetryAt) {
    public AiRecovery ended(String state) { return new AiRecovery(kind, state, attempt, limit, error, null); }
}
