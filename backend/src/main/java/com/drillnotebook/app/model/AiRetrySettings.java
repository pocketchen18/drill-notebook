package com.drillnotebook.app.model;

/** 重试上限随整轮设置冻结；等待策略固定采用已核实的 Codex 核心基线。 */
public record AiRetrySettings(int httpRetries, int streamRetries, boolean networkPersistent, int networkRetries) {
    public static final AiRetrySettings DEFAULTS = new AiRetrySettings(4, 5, true, 5);

    public AiRetrySettings {
        check(httpRetries, "请求重试次数");
        check(streamRetries, "回答重连次数");
        check(networkRetries, "断网重试次数");
    }

    private static void check(int value, String name) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(name + "须为 0–100 的整数");
    }
}
