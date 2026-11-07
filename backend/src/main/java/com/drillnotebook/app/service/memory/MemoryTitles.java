package com.drillnotebook.app.service.memory;

/**
 * 列表工具面向用户的记忆标题：只从正文派生，不暴露内部 predicate。
 * 与前端 MemoryManager.deriveMemoryName 保持同一口径（压缩空白、前 20 个码点、空值兜底）。
 */
public final class MemoryTitles {
    static final int MAX_TITLE_CODE_POINTS = 20;
    static final String FALLBACK = "未命名记忆";

    private MemoryTitles() {
    }

    public static String displayTitle(String value) {
        if (value == null) {
            return FALLBACK;
        }
        // 先折叠再 strip：U+3000 等全角空白不会被 trim 去掉，先换成半角空格才能清干净
        String compact = value.replaceAll("[\\s\\u3000]+", " ").strip();
        if (compact.isEmpty()) {
            return FALLBACK;
        }
        if (compact.codePointCount(0, compact.length()) <= MAX_TITLE_CODE_POINTS) {
            return compact;
        }
        return compact.substring(0, compact.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS));
    }
}
