package com.drillnotebook.app.service.memory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * 记忆子系统的时间口径：固定宽度 ISO-8601 UTC，毫秒精度。
 *
 * <p>{@code Instant.now().toString()} 的位数会变（{@code ...:14Z} 与 {@code ...:14.656Z} 都合法），
 * 而 v9 里的 {@code expires_at}/{@code next_run_at}/{@code lease_until} 全靠文本比较，
 * 长度不等的字符串一旦混写，字典序就不再等于时间序。所有写入记忆表的时间戳都必须经这里生成。</p>
 */
public final class MemoryTimes {
    private static final DateTimeFormatter FIXED_WIDTH_UTC = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
            .appendLiteral('Z')
            .toFormatter()
            .withZone(ZoneOffset.UTC);

    private MemoryTimes() {
    }

    public static String now() {
        return format(Instant.now());
    }

    public static String format(Instant instant) {
        return FIXED_WIDTH_UTC.format(instant);
    }

    /**
     * 文本时刻转 epoch 毫秒；无法识别（含 {@code null}、空白和各种乱码）返回 0，调用方据此判"没有时刻"。
     *
     * <p>兼容 SQLite 默认值那种没有 {@code T}/{@code Z} 的写法：{@code yyyy-MM-dd HH:mm:ss} 正好
     * <b>19</b> 个字符（第 10 位是空格），历史行里确实存在。这里判成 19 而不是 20，否则这一路静默
     * 退化成"没有时刻"——冷却会被旁路、合并窗口会落到 1970 年。</p>
     */
    public static long parseMillis(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        String text = value.trim();
        try {
            if (text.length() >= 19 && text.charAt(10) == ' ') {
                text = text.substring(0, 10) + "T" + text.substring(11) + "Z";
            }
            return Instant.parse(text).toEpochMilli();
        } catch (RuntimeException error) {
            try {
                return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli();
            } catch (RuntimeException fallback) {
                return 0L;
            }
        }
    }

    /** epoch 毫秒转库内文本时刻；{@code null} 写成 SQL NULL 由调用方负责。 */
    public static String formatMillis(long epochMilli) {
        return format(Instant.ofEpochMilli(epochMilli));
    }
}
