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
}
