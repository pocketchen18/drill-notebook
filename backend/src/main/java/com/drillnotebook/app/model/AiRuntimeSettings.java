package com.drillnotebook.app.model;

/** 对话执行预算，独立于供应商和模型；一轮开始后使用不可变快照。 */
public record AiRuntimeSettings(int maxToolStages, int maxToolExecutions, int turnTimeoutMinutes,
                                int requestTimeoutMinutes, int maxOutputTokens, int requestOutputTokens,
                                int finalOutputTokens, int repeatedToolLimit) {
    public static final AiRuntimeSettings DEFAULTS = new AiRuntimeSettings(50, 100, 30, 15,
            128_000, 16_384, 8_192, 3);

    public AiRuntimeSettings {
        check(maxToolStages, 1, 500, "工具阶段上限");
        check(maxToolExecutions, 1, 1000, "工具执行上限");
        check(turnTimeoutMinutes, 1, 240, "整轮最长时间");
        check(requestTimeoutMinutes, 1, 120, "单次请求最长时间");
        check(maxOutputTokens, 1024, 2_000_000, "整轮输出 Token 上限");
        check(requestOutputTokens, 256, 131_072, "单次请求输出上限");
        check(finalOutputTokens, 256, 65_536, "最终回答预留");
        check(repeatedToolLimit, 0, 20, "重复调用阈值");
        if (requestTimeoutMinutes > turnTimeoutMinutes) throw new IllegalArgumentException("单次请求时间不能超过整轮时间");
        if (finalOutputTokens >= maxOutputTokens) throw new IllegalArgumentException("最终回答预留须小于整轮输出上限");
    }

    private static void check(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + "须为 " + min + "–" + max + " 的整数");
    }
}
