package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 后台限额的边界（执行方案 §8.1、§8.4、§8.5；验收 F17/F18/H17/H20）：
 * 阈值正负 1、冷却 119/120 秒、收益 255/256、退避 30/120 秒、额度"取先达到者"都必须被直接断言，
 * 不依赖真实时间也不依赖数据库。
 */
class MaintenanceRulesTest {

    @Test
    void fourTurnsMakeABatchWhileShortRunsWaitForTheIdleWindow() {
        assertEquals(MaintenanceRules.ExtractTrigger.NONE, MaintenanceRules.extractTrigger(0),
                "没有新内容就不该创建远程任务（§8.5）");
        assertEquals(MaintenanceRules.ExtractTrigger.IDLE, MaintenanceRules.extractTrigger(1),
                "短会话也要能形成记忆（§8.1）");
        assertEquals(MaintenanceRules.ExtractTrigger.IDLE,
                MaintenanceRules.extractTrigger(MaintenanceRules.EXTRACT_TURN_THRESHOLD - 1), "差 1 轮不算成批");
        assertEquals(MaintenanceRules.ExtractTrigger.BATCH,
                MaintenanceRules.extractTrigger(MaintenanceRules.EXTRACT_TURN_THRESHOLD), "阈值正负 1 分界（§8.1）");
        assertEquals(MaintenanceRules.MERGE_WINDOW_MILLIS,
                MaintenanceRules.extractDelayMillis(MaintenanceRules.ExtractTrigger.BATCH),
                "成批后先等一个合并窗口，连续发言并进同一批");
        assertEquals(MaintenanceRules.IDLE_TRIGGER_MILLIS,
                MaintenanceRules.extractDelayMillis(MaintenanceRules.ExtractTrigger.IDLE));
    }

    @Test
    void summaryPressureGateIsSeventyPercentOfTheRecentBudget() {
        assertEquals(1_680L, MaintenanceRules.recentPressureThreshold(), "2,400 的 70%，向上取整");
        assertEquals(MaintenanceRules.SummaryGate.RECENT_PRESSURE,
                MaintenanceRules.summaryGate(1_679L, 4_000L, Long.MAX_VALUE, false));
        assertEquals(MaintenanceRules.SummaryGate.QUEUE, MaintenanceRules.summaryGate(1_680L, 1_024L,
                MaintenanceRules.SUMMARY_COOLDOWN_MILLIS, false));
        assertEquals(MaintenanceRules.SummaryGate.RECENT_PRESSURE, MaintenanceRules.summaryGate(0L, 9_999L,
                Long.MAX_VALUE, true), "手动 flush 也越不过压力门槛（§8.5）");
    }

    @Test
    void newVolumeAndCooldownAreSeparateGates() {
        assertEquals(MaintenanceRules.SummaryGate.NEW_VOLUME, MaintenanceRules.summaryGate(2_400L,
                MaintenanceRules.SUMMARY_MIN_NEW_TOKENS - 1L, Long.MAX_VALUE, false));
        assertEquals(MaintenanceRules.SummaryGate.COOLDOWN, MaintenanceRules.summaryGate(2_400L, 2_000L,
                MaintenanceRules.SUMMARY_COOLDOWN_MILLIS - 1, false), "差 1 毫秒仍在冷却里");
        assertEquals(MaintenanceRules.SummaryGate.QUEUE, MaintenanceRules.summaryGate(2_400L,
                MaintenanceRules.SUMMARY_MIN_NEW_TOKENS, MaintenanceRules.SUMMARY_COOLDOWN_MILLIS, false));
        assertEquals(MaintenanceRules.SummaryGate.QUEUE, MaintenanceRules.summaryGate(2_400L, 10L, 0L, true),
                "手动 flush 越过新增量与冷却，但只此两项");
    }

    @Test
    void summaryWithoutEnoughNetReductionKeepsTheOldText() {
        assertEquals(MaintenanceRules.SummaryVerdict.NO_COMPRESSION_GAIN,
                MaintenanceRules.summaryVerdict(600L, 0L, 600L), "完全没缩减");
        assertEquals(MaintenanceRules.SummaryVerdict.NO_COMPRESSION_GAIN,
                MaintenanceRules.summaryVerdict(0L, 255L, 0L), "差 1 个 Token 也不算有收益");
        assertEquals(MaintenanceRules.SummaryVerdict.APPLY, MaintenanceRules.summaryVerdict(0L, 256L, 0L));
        assertEquals(MaintenanceRules.SummaryVerdict.APPLY, MaintenanceRules.summaryVerdict(500L, 1_024L, 600L),
                "旧摘要 + 本批原文 − 新摘要，才是净缩减");
    }

    @Test
    void retryBackoffGrowsAndJitterIsClamped() {
        assertEquals(30_000L, MaintenanceRules.retryDelayMillis(1, 0L));
        assertEquals(120_000L, MaintenanceRules.retryDelayMillis(2, 0L));
        assertEquals(120_000L, MaintenanceRules.retryDelayMillis(3, 0L), "第二次之后的退避不再翻倍");
        assertEquals(30_000L + MaintenanceRules.RETRY_JITTER_MILLIS,
                MaintenanceRules.retryDelayMillis(1, MaintenanceRules.RETRY_JITTER_MILLIS + 999_999L),
                "抖动不能超过上限");
        assertEquals(30_000L, MaintenanceRules.retryDelayMillis(1, -5_000L), "负抖动按 0 算");
    }

    @Test
    void dailyBudgetStopsOnWhicheverLimitIsHitFirst() {
        assertTrue(MaintenanceRules.budgetAllows(19, 0L, 20, 30_000L));
        assertFalse(MaintenanceRules.budgetAllows(20, 0L, 20, 30_000L), "第 21 次尝试要等明天");
        long spent = 30_000L - MaintenanceRules.RESERVE_TOKENS;
        assertTrue(MaintenanceRules.budgetAllows(0, spent, 20, 30_000L), "刚好留得下这一笔预留");
        assertFalse(MaintenanceRules.budgetAllows(0, spent + 1, 20, 30_000L), "Token 先达到也一样停");
        assertEquals(4_024, MaintenanceRules.RESERVE_TOKENS, "输入 3,000 + 输出 1,024 全额预留");
    }

    @Test
    void leaseIsLongerThanTheCallTimeout() {
        assertTrue(MaintenanceRules.LEASE_MILLIS > MaintenanceRules.CALL_TIMEOUT_MILLIS,
                "否则正常慢请求会被当成僵尸租约接管");
        assertEquals(3, MaintenanceRules.MAX_ATTEMPTS, "首次 + 最多额外 2 次（§8.1）");
    }

    @Test
    void budgetTimezoneFallsBackWhenUnreadable() {
        assertEquals("Asia/Shanghai", MaintenanceRules.zoneOrDefault("Asia/Shanghai", "UTC"));
        assertEquals("UTC", MaintenanceRules.zoneOrDefault("不是时区", "UTC"));
        assertEquals("UTC", MaintenanceRules.zoneOrDefault(null, "UTC"));
        assertEquals("Asia/Shanghai", MaintenanceRules.zoneOrDefault("  Asia/Shanghai  ", "UTC"));
    }
}
