package com.drillnotebook.app.service.memory;

import java.util.List;

/**
 * 用户是否在本轮明确要求记住某件事。
 *
 * <p>提取链拿它兜底写入；对话写工具拿它决定能否解除用户之前删除时写下的 FAMILY 抑制。
 * 顺序从长到短，避免“请记住”先被“记住”拆掉后留下一个孤零零的“请”。</p>
 */
public final class MemoryIntent {
    public static final List<String> MARKERS = List.of(
            "请帮我加入记忆", "帮我加入记忆", "请加入记忆",
            "请帮我记住", "帮我记住", "让你记住",
            "请记到记忆", "记到记忆", "请存到记忆", "存到记忆",
            "请记住", "加入记忆", "记住", "记一下", "记下来", "别忘了", "不要忘记");

    private MemoryIntent() {
    }

    public static boolean explicitRememberRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String marker : MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
