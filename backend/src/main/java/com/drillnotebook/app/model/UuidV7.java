package com.drillnotebook.app.model;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * UUIDv7（RFC 9562）：轮次请求身份的唯一形式。
 *
 * <p>只接受小写规范形：同一个请求写成大写会得到两个不同 ID，幂等就失效了。</p>
 */
public final class UuidV7 {
    /** 无记录的新请求只接受 24 小时内生成的 ID（执行方案 §7.2.2）。 */
    public static final long MAX_AGE_MILLIS = 24L * 3600L * 1000L;
    /** 允许客户端时钟超前 5 分钟，再多就判为异常时钟。 */
    public static final long MAX_FUTURE_MILLIS = 5L * 60L * 1000L;

    /** 新 ID 的时效判定结果。 */
    public enum Freshness { ACCEPTABLE, TOO_OLD, CLOCK_AHEAD }

    private static final Pattern CANONICAL = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static boolean isCanonical(String value) {
        return value != null && CANONICAL.matcher(value).matches();
    }

    /** 生成：高 48 位是毫秒时间戳，其余取随机位，version/variant 位按规范固定。 */
    public static String generate(long timestampMillis) {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        long millis = Math.max(timestampMillis, 0) & 0xFF_FFFF_FFFF_FFFFL;
        for (int index = 0; index < 6; index++) {
            raw[index] = (byte) (millis >>> (40 - 8 * index));
        }
        raw[6] = (byte) ((raw[6] & 0x0F) | 0x70);
        raw[8] = (byte) ((raw[8] & 0x3F) | 0x80);
        StringBuilder text = new StringBuilder(36);
        for (int index = 0; index < 16; index++) {
            if (index == 4 || index == 6 || index == 8 || index == 10) text.append('-');
            text.append(Character.forDigit((raw[index] >>> 4) & 0x0F, 16));
            text.append(Character.forDigit(raw[index] & 0x0F, 16));
        }
        return text.toString();
    }

    /** 取 ID 内嵌的毫秒时间戳；非法格式抛 IllegalArgumentException。 */
    public static long timestampMillis(String value) {
        if (!isCanonical(value)) throw new IllegalArgumentException("requestId 必须是 UUIDv7");
        return Long.parseLong(value.replace("-", "").substring(0, 12), 16);
    }

    /**
     * 新请求的时效判定。只在数据库查不到该 ID 时调用：
     * 已存在的 ID 走幂等重放或 410 墓碑分支，超期的旧轮次仍要能查状态。
     */
    public static Freshness freshness(String value, long nowMillis) {
        long created = timestampMillis(value);
        if (created > nowMillis + MAX_FUTURE_MILLIS) return Freshness.CLOCK_AHEAD;
        if (nowMillis - created > MAX_AGE_MILLIS) return Freshness.TOO_OLD;
        return Freshness.ACCEPTABLE;
    }
}
