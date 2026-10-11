package com.drillnotebook.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * UUIDv7 的形态、时间戳位与时效窗口（执行方案 §7.2.2）：
 * 请求身份全靠这些位判定，写错一位就等于给同一请求发了两个 ID。
 */
class UuidV7Test {
    private static final long NOW = 1_760_000_000_000L;

    @Test
    void generatedIdsCarryVersionAndVariantBits() {
        String id = UuidV7.generate(NOW);

        assertTrue(UuidV7.isCanonical(id), "生成的必须是小写规范形：" + id);
        assertEquals(7, Character.digit(id.charAt(14), 16), "版本位必须是 7");
        assertTrue("89ab".indexOf(id.charAt(19)) >= 0, "变体位必须是 10xx");
        assertEquals(NOW, UuidV7.timestampMillis(id), "高 48 位就是毫秒时间戳");
    }

    @Test
    void randomBitsDistinguishRepeatedRequests() {
        assertFalse(UuidV7.generate(NOW).equals(UuidV7.generate(NOW)), "同一毫秒也要给出不同 ID");
    }

    @Test
    void rejectsUuidv4AndNonCanonicalForms() {
        assertFalse(UuidV7.isCanonical(UUID.randomUUID().toString()), "v4 不能冒充 v7");
        assertFalse(UuidV7.isCanonical(UuidV7.generate(NOW).toUpperCase()));
        assertFalse(UuidV7.isCanonical(UuidV7.generate(NOW).replace("-", "")));
        assertFalse(UuidV7.isCanonical("01a109ca-1100-8000-8000-000000000001"), "变体位不对");
        assertFalse(UuidV7.isCanonical(null));
        assertThrows(IllegalArgumentException.class, () -> UuidV7.timestampMillis("not-an-id"));
    }

    @Test
    void freshnessWindowsMatchContract() {
        assertEquals(UuidV7.Freshness.ACCEPTABLE, UuidV7.freshness(UuidV7.generate(NOW), NOW));
        assertEquals(UuidV7.Freshness.ACCEPTABLE,
                UuidV7.freshness(UuidV7.generate(NOW - UuidV7.MAX_AGE_MILLIS + 1000), NOW), "24 小时内仍可用");
        assertEquals(UuidV7.Freshness.TOO_OLD,
                UuidV7.freshness(UuidV7.generate(NOW - UuidV7.MAX_AGE_MILLIS - 1000), NOW));
        assertEquals(UuidV7.Freshness.CLOCK_AHEAD,
                UuidV7.freshness(UuidV7.generate(NOW + UuidV7.MAX_FUTURE_MILLIS + 1000), NOW),
                "超前 5 分钟以外按异常时钟处理");
        assertEquals(UuidV7.Freshness.ACCEPTABLE,
                UuidV7.freshness(UuidV7.generate(NOW + UuidV7.MAX_FUTURE_MILLIS - 1000), NOW));
    }
}
