package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 记忆表的时间戳口径（§2.7 与 §6.2 的比较全靠文本）：定宽 ISO-8601 UTC 才能做到字典序==时间序。
 */
class MemoryTimesTest {
    @Test
    void everyStampIsTheSameWidthAndEndsInUtc() {
        assertEquals("1970-01-01T00:00:00.000Z", MemoryTimes.format(Instant.EPOCH));
        assertEquals("2026-10-12T00:00:00.000Z", MemoryTimes.format(Instant.parse("2026-10-12T00:00:00Z")));
        assertEquals("2026-10-12T00:00:00.656Z", MemoryTimes.format(Instant.parse("2026-10-12T00:00:00.656Z")));
        assertEquals(24, MemoryTimes.now().length());
        assertTrue(MemoryTimes.now().endsWith("Z"));
        Instant.parse(MemoryTimes.now());
    }

    @Test
    void variableWidthIsoFlipsOrderInsideTheSameSecond() {
        String wholeSecond = "2026-10-12T00:00:00Z";
        String sameSecondLater = "2026-10-12T00:00:00.500Z";
        assertTrue(wholeSecond.compareTo(sameSecondLater) > 0,
                "Instant.now().toString() 会省掉 .000，字典序把整秒排到同秒的毫秒之后");
        assertTrue(MemoryTimes.format(Instant.parse(wholeSecond))
                .compareTo(MemoryTimes.format(Instant.parse(sameSecondLater))) < 0, "定宽后顺序恢复正常");
    }

    @Test
    void sortingTheStampsEqualsSortingTheInstants() {
        List<Instant> ascending = List.of(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00.001Z"),
                Instant.parse("2026-01-01T00:00:00.999Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("2027-06-30T23:59:59.500Z"));
        List<String> stamps = ascending.stream().map(MemoryTimes::format).toList();
        for (int index = 1; index < stamps.size(); index++) {
            assertTrue(stamps.get(index - 1).compareTo(stamps.get(index)) < 0,
                    stamps.get(index - 1) + " 应当排在 " + stamps.get(index) + " 之前");
            assertEquals(24, stamps.get(index).length());
        }
        assertNotEquals(stamps.get(0), stamps.get(1));
    }

    @Test
    void sqliteStampWithoutTheTAndZStillParses() {
        long expected = Instant.parse("2026-10-12T08:30:00Z").toEpochMilli();
        assertEquals(19, "2026-10-12 08:30:00".length(), "SQLite 默认值的宽度正好 19，兼容分支要按 19 判");
        assertEquals(expected, MemoryTimes.parseMillis("2026-10-12 08:30:00"));
        assertEquals(expected + 656L, MemoryTimes.parseMillis("2026-10-12 08:30:00.656"));
        assertEquals(expected, MemoryTimes.parseMillis(MemoryTimes.formatMillis(expected)), "写读要能往返对上");
    }

    @Test
    void unreadableStampsParseToZeroInsteadOfAGuess() {
        assertEquals(0L, MemoryTimes.parseMillis(null));
        assertEquals(0L, MemoryTimes.parseMillis("  "));
        assertEquals(0L, MemoryTimes.parseMillis("2026-10-12 08:30"), "缺秒的半截时刻不能当成时刻");
        assertEquals(0L, MemoryTimes.parseMillis("2026-13-45 99:99:99"));
        assertEquals(0L, MemoryTimes.parseMillis("刚刚"));
    }
}
