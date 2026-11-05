package com.drillnotebook.app.memory.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.Attempt;
import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.AttemptState;
import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.FakeClock;
import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.Ledger;
import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.MaintenanceResult;
import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.Purpose;
import com.drillnotebook.app.memory.prototype.MaintenanceBudgetPrototype.SummaryVerdict;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P0a 第②组（执行方案 §8.1/§8.4、验收 F17/F18/H17/H20、§10.1.3）：
 * fake Clock 下同日 100 轮，日限额 20 次 + 30,000 Token 与摘要四道门槛是否真能被机械满足，
 * 以及限额打满时普通聊天是否不受影响。
 */
class MaintenanceBudgetPrototypeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static FakeClock clockAtMorning() {
        return new FakeClock(ZonedDateTime.of(2026, 10, 8, 9, 0, 0, 0, ZONE));
    }

    @Test
    void rapidHundredTurnDayStaysInsideBothCaps() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        MaintenanceResult result = MaintenanceBudgetPrototype.runDay(ledger, clock, 100, 400, 6, 2_200, true);

        int maintenanceAttempts = maintenanceAttempts(ledger);
        assertTrue(maintenanceAttempts <= MaintenanceBudgetPrototype.DAILY_ATTEMPT_LIMIT,
                "维护调用次数必须 ≤ 20，实际 " + maintenanceAttempts);
        long charged = maintenanceCharged(ledger);
        assertTrue(charged <= MaintenanceBudgetPrototype.DAILY_TOKEN_LIMIT,
                "维护扣账必须 ≤ 30,000 Token，实际 " + charged);
        assertEquals(100, result.chatTurnsServed, "聊天必须照常服务");
        assertTrue(result.pausedBudgetWaits > 0, "100 轮快聊一定会有任务因限额排队：" + result.pausedBudgetWaits);
        System.out.println("[P0a-②] rapid: attempts=" + maintenanceAttempts + " charged=" + charged
                + " extract=" + result.extractRuns + " summary=" + result.summaryRuns
                + " paused=" + result.pausedBudgetWaits + " maxSummaryLagTurns=" + result.maxSummaryLagTurns
                + " unextractedTurns=" + result.unextractedTurns
                + " skipPressure=" + result.summarySkippedByPressure
                + " skipNew=" + result.summarySkippedByNewTokens + " skipCooldown="
                + result.summarySkippedByCooldown + " noGain=" + result.summaryRejectedNoGain);
    }

    @Test
    void idleScheduleAlsoRespectsAttemptCap() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        MaintenanceResult result = MaintenanceBudgetPrototype.runDay(ledger, clock, 100, 400, 61, 2_200, true);

        assertTrue(maintenanceAttempts(ledger) <= MaintenanceBudgetPrototype.DAILY_ATTEMPT_LIMIT);
        assertTrue(maintenanceCharged(ledger) <= MaintenanceBudgetPrototype.DAILY_TOKEN_LIMIT);
        // 61 秒间隔下冷却不再是主要瓶颈，但 20 次上限仍然封住
        assertTrue(result.summarySkippedByCooldown < result.summarySkippedByNewTokens + result.summaryRuns + 1);
        assertEquals(100, result.chatTurnsServed);
        System.out.println("[P0a-②] idle: attempts=" + maintenanceAttempts(ledger) + " charged="
                + maintenanceCharged(ledger) + " summary=" + result.summaryRuns + " paused="
                + result.pausedBudgetWaits + " maxSummaryLagTurns=" + result.maxSummaryLagTurns);
    }

    @Test
    void fullReserveUsageAllowsOnlySevenMaintenanceCalls() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        int allowed = 0;
        for (int index = 0; index < 40; index++) {
            Attempt attempt = ledger.reserve(clock, Purpose.MEMORY_EXTRACT,
                    MaintenanceBudgetPrototype.EXTRACT_INPUT_BUDGET, MaintenanceBudgetPrototype.EXTRACT_OUTPUT_BUDGET);
            if (attempt == null) {
                break;
            }
            allowed++;
            // 每次固定满额 3,000 + 1,024 = 4,024
            ledger.settle(attempt, 3_000L, 1_024L);
        }
        assertEquals(7, allowed, "30,000 只容纳 7 次满额消耗（acceptance §7.2）");
        assertTrue(maintenanceCharged(ledger) <= MaintenanceBudgetPrototype.DAILY_TOKEN_LIMIT);
    }

    @Test
    void actualUsageReleasesUnusedReservation() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        int allowed = 0;
        for (int index = 0; index < 40; index++) {
            Attempt attempt = ledger.reserve(clock, Purpose.MEMORY_EXTRACT, 3_000L, 1_024L);
            if (attempt == null) {
                break;
            }
            allowed++;
            ledger.settle(attempt, 900L, 120L);
        }
        assertTrue(allowed > 7, "实际用量小就应该归还预留，不能测成固定 7 次，实际 " + allowed);
        assertEquals(20, allowed, "次数上限仍然封死在 20");
    }

    @Test
    void unknownUsageConsumesFullReservation() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        int allowed = 0;
        for (int index = 0; index < 40; index++) {
            Attempt attempt = ledger.reserve(clock, Purpose.SESSION_SUMMARY, 3_000L, 1_024L);
            if (attempt == null) {
                break;
            }
            allowed++;
            ledger.settle(attempt, null, null);
        }
        assertEquals(7, allowed, "无 usage 时按预留消费，不能当零成本");
        assertEquals(7, ledger.days().values().iterator().next().unknownAttempts);
        assertEquals(28_168L, maintenanceCharged(ledger));
    }

    @Test
    void unsettledSentAttemptIsNotRefundedAfterRestart() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        Attempt hung = ledger.reserve(clock, Purpose.MEMORY_EXTRACT, 3_000L, 1_024L);
        assertNotNull(hung);
        // 模拟进程重启：该 attempt 一直停在 SENT，没有结算，预留也不返还
        int settled = 0;
        for (int index = 0; index < 12; index++) {
            Attempt attempt = ledger.reserve(clock, Purpose.MEMORY_EXTRACT, 3_000L, 1_024L);
            if (attempt == null) {
                break;
            }
            ledger.settle(attempt, 3_000L, 1_024L);
            settled++;
        }
        assertEquals(6, settled, "被悬挂预留吃掉一轮额度，实际完成 " + settled + " 次");
        assertEquals(24_144L, maintenanceCharged(ledger), "6 次满额扣账");
        assertEquals(MaintenanceBudgetPrototype.AttemptState.SENT, hung.state, "已发出未结算的预留不得被返还");
    }

    @Test
    void chatTrafficDoesNotConsumeMaintenanceBudget() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        for (int index = 0; index < 200; index++) {
            Attempt chat = ledger.reserve(clock, Purpose.CHAT, 8_000L, 4_096L);
            assertNotNull(chat, "聊天不受后台限额约束");
            ledger.settle(chat, 6_000L, 800L);
        }
        Attempt maintenance = ledger.reserve(clock, Purpose.MEMORY_EXTRACT, 3_000L, 1_024L);
        assertNotNull(maintenance, "200 轮聊天之后仍应有完整后台额度");
        assertEquals(201, ledger.attempts().size());
    }

    @Test
    void summaryThresholdsAreIndividuallyTestable() {
        long pressure = 1_680L;
        assertEquals(SummaryVerdict.SKIP_RECENT_PRESSURE,
                MaintenanceBudgetPrototype.summaryVerdict(pressure - 1, 4_000L, 600L, 3_000L, false));
        assertEquals(SummaryVerdict.SKIP_NEW_TOKENS,
                MaintenanceBudgetPrototype.summaryVerdict(pressure, MaintenanceBudgetPrototype.SUMMARY_MIN_NEW_TOKENS - 1,
                        600L, 3_000L, false));
        assertEquals(SummaryVerdict.APPLY,
                MaintenanceBudgetPrototype.summaryVerdict(pressure, MaintenanceBudgetPrototype.SUMMARY_MIN_NEW_TOKENS,
                        600L, 3_000L, false));
        assertEquals(SummaryVerdict.SKIP_COOLDOWN,
                MaintenanceBudgetPrototype.summaryVerdict(pressure, 4_000L,
                        MaintenanceBudgetPrototype.SUMMARY_COOLDOWN_SECONDS - 1, 3_000L, false));
        assertEquals(SummaryVerdict.APPLY,
                MaintenanceBudgetPrototype.summaryVerdict(pressure, 4_000L,
                        MaintenanceBudgetPrototype.SUMMARY_COOLDOWN_SECONDS, 3_000L, false));
        assertEquals(SummaryVerdict.NO_COMPRESSION_GAIN,
                MaintenanceBudgetPrototype.summaryVerdict(pressure, 800L, 600L,
                        MaintenanceBudgetPrototype.SUMMARY_MIN_GAIN_TOKENS - 1, true));

        // 手动 flush 只越过新增量与冷却，不越过压力与收益
        assertEquals(SummaryVerdict.SKIP_RECENT_PRESSURE,
                MaintenanceBudgetPrototype.summaryVerdict(100L, 10L, 0L, 3_000L, true));
        assertEquals(SummaryVerdict.NO_COMPRESSION_GAIN,
                MaintenanceBudgetPrototype.summaryVerdict(pressure, 10L, 0L, 10L, true));
        assertEquals(SummaryVerdict.APPLY, MaintenanceBudgetPrototype.summaryVerdict(pressure, 10L, 0L, 3_000L, true));
    }

    @Test
    void extractUsesTurnThresholdNotSummaryThreshold() {
        Ledger ledger = new Ledger();
        FakeClock clock = clockAtMorning();
        // 每轮 400 Token、间隔 200 秒：摘要门槛早已满足，提取仍必须按 4 轮阈值批处理
        MaintenanceResult result = MaintenanceBudgetPrototype.runDay(ledger, clock, 8, 400, 200, 2_200, true);
        assertEquals(2, result.extractRuns, "8 轮 / 4 轮阈值 = 2 次提取");
        assertTrue(result.summaryRuns <= maintenanceAttempts(ledger));
        assertEquals(0, result.unextractedTurns);
    }

    private static int maintenanceAttempts(Ledger ledger) {
        return (int) ledger.attempts().stream()
                .filter(attempt -> attempt.purpose != Purpose.CHAT)
                .count();
    }

    private static long maintenanceCharged(Ledger ledger) {
        return List.copyOf(ledger.days().values()).stream()
                .mapToLong(day -> day.chargedTokens)
                .sum();
    }
}
