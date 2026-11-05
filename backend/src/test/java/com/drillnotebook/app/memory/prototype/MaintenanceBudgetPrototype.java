package com.drillnotebook.app.memory.prototype;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0a 第②组原型（执行方案 §8.1/§8.4/§8.5、§10.2、验收 F17/F18/H17/H20）：
 * 用可注入的 fake Clock 跑"同日 100 轮"，验证后台限额与摘要门槛是不是真能被机器满足，
 * 以及达到限额时普通聊天是否不受影响。
 *
 * <p>只在测试作用域存在；正式实现属于 P6（MemoryJobRepository/MaintenanceWorker）。
 * 记账口径照 contracts §2.6：先 CAS 预留额度再写 attempt，发请求前置 SENT，重启不返还已发出的预留。</p>
 */
public final class MaintenanceBudgetPrototype {

    public static final int DAILY_ATTEMPT_LIMIT = 20;
    public static final long DAILY_TOKEN_LIMIT = 30_000L;
    public static final long EXTRACT_INPUT_BUDGET = 3_000L;
    public static final long EXTRACT_OUTPUT_BUDGET = 1_024L;
    public static final long SUMMARY_INPUT_BUDGET = 3_000L;
    public static final long SUMMARY_OUTPUT_BUDGET = 1_024L;
    /** §8.1：每累计 4 个完整轮次批处理一次提取。 */
    public static final int EXTRACT_TURN_THRESHOLD = 4;
    /** §8.4：摘要最小新增量、冷却与最低净缩减。 */
    public static final long SUMMARY_MIN_NEW_TOKENS = 1_024L;
    public static final long SUMMARY_COOLDOWN_SECONDS = 120L;
    public static final long SUMMARY_MIN_GAIN_TOKENS = 256L;
    /** §8.4 第 1 条：近期预算压力门槛（2,400 的 70%）。 */
    public static final long RECENT_BUDGET = 2_400L;
    public static final double RECENT_PRESSURE_RATIO = 0.7;
    /** §8.4：至少最近 2 个完整轮次始终排除在摘要源之外。 */
    public static final int SUMMARY_RESERVED_RECENT_TURNS = 2;

    private MaintenanceBudgetPrototype() {
    }

    public enum Purpose {
        CHAT, MEMORY_EXTRACT, SESSION_SUMMARY, FORMAT_REPAIR
    }

    public enum AttemptState {
        RESERVED, SENT, SETTLED, UNKNOWN, RELEASED
    }

    /** 固定时刻的 fake Clock：绝不依赖真实 sleep，也不能靠改系统时间测试。 */
    public static final class FakeClock {
        private ZonedDateTime now;

        public FakeClock(ZonedDateTime start) {
            this.now = start;
        }

        public ZonedDateTime now() {
            return now;
        }

        public LocalDate budgetDate() {
            return now.toLocalDate();
        }

        public String budgetZone() {
            return now.getZone().getId();
        }

        public void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    public static final class Attempt {
        public final String id;
        public final Purpose purpose;
        public final long reservedTokens;
        public final String budgetKey;
        public AttemptState state;
        public Long inputTokens;
        public Long outputTokens;

        Attempt(String id, Purpose purpose, long reservedTokens, String budgetKey) {
            this.id = id;
            this.purpose = purpose;
            this.reservedTokens = reservedTokens;
            this.budgetKey = budgetKey;
            this.state = AttemptState.RESERVED;
        }
    }

    public static final class DayBudget {
        public int maintenanceAttempts;
        public int chatAttempts;
        public long chargedTokens;
        public long chatChargedTokens;
        public long reservedTokens;
        public int unknownAttempts;
        public final Map<Purpose, Integer> perPurpose = new EnumMap<>(Purpose.class);
    }

    /** 限额横跨所有 target 与三个维护 purpose 求和；CHAT 计量但不占后台额度。 */
    public static final class Ledger {
        private final Map<String, DayBudget> days = new LinkedHashMap<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private long sequence;

        public DayBudget day(LocalDate date, String zone) {
            return days.computeIfAbsent(date + "|" + zone, key -> new DayBudget());
        }

        /** 返回 null 表示额度不足，任务应进入 PAUSED_BUDGET 等待态，不算一次远程尝试。 */
        public Attempt reserve(FakeClock clock, Purpose purpose, long inputBudget, long outputBudget) {
            long reserved = inputBudget + outputBudget;
            String key = clock.budgetDate() + "|" + clock.budgetZone();
            DayBudget budget = days.computeIfAbsent(key, unused -> new DayBudget());
            if (purpose != Purpose.CHAT
                    && (budget.maintenanceAttempts + 1 > DAILY_ATTEMPT_LIMIT
                    || budget.chargedTokens + budget.reservedTokens + reserved > DAILY_TOKEN_LIMIT)) {
                return null;
            }
            Attempt attempt = new Attempt("attempt-" + (++sequence), purpose, reserved, key);
            // 发请求前置 SENT：之后崩溃也不返还预留
            attempt.state = AttemptState.SENT;
            if (purpose == Purpose.CHAT) {
                budget.chatAttempts++;
            } else {
                budget.maintenanceAttempts++;
            }
            budget.reservedTokens += reserved;
            budget.perPurpose.merge(purpose, 1, Integer::sum);
            attempts.add(attempt);
            return attempt;
        }

        /** 有 usage 按实际扣账并归还多余预留；无 usage 记 UNKNOWN 并按预留全额消费。 */
        public void settle(Attempt attempt, Long inputTokens, Long outputTokens) {
            DayBudget budget = days.get(attempt.budgetKey);
            budget.reservedTokens -= attempt.reservedTokens;
            boolean unknown = inputTokens == null && outputTokens == null;
            long charged = unknown ? attempt.reservedTokens
                    : (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
            if (attempt.purpose == Purpose.CHAT) {
                // 聊天只计量，不占后台 20 次 / 30,000 Token 限额
                attempt.state = unknown ? AttemptState.UNKNOWN : AttemptState.SETTLED;
                budget.chatChargedTokens += charged;
                if (unknown) {
                    budget.unknownAttempts++;
                }
                return;
            }
            if (unknown) {
                attempt.state = AttemptState.UNKNOWN;
                budget.unknownAttempts++;
            } else {
                attempt.state = AttemptState.SETTLED;
                attempt.inputTokens = inputTokens;
                attempt.outputTokens = outputTokens;
            }
            budget.chargedTokens += charged;
        }

        public List<Attempt> attempts() {
            return attempts;
        }

        public Map<String, DayBudget> days() {
            return days;
        }
    }

    public record Turn(int index, long estimatedTokens, ZonedDateTime at) {
    }

    public static final class MaintenanceResult {
        public int extractRuns;
        public int summaryRuns;
        public int summarySkippedByNewTokens;
        public int summarySkippedByCooldown;
        public int summarySkippedByPressure;
        public int summaryRejectedNoGain;
        public int pausedBudgetWaits;
        public int chatTurnsServed;
        public long chatTokens;
        public int maxSummaryLagTurns;
        public int unextractedTurns;
    }

    public enum SummaryVerdict {
        APPLY, SKIP_RECENT_PRESSURE, SKIP_NEW_TOKENS, SKIP_COOLDOWN, NO_COMPRESSION_GAIN
    }

    /**
     * §8.4 的摘要门槛判定，四道门各自独立可机械断言（验收 §10.1.3 要求 threshold±1 与冷却
     * 119/120 可测）。manualFlush 只越过新增量与冷却，不越过压力、预算与收益判断。
     */
    public static SummaryVerdict summaryVerdict(long pressureTokens, long newTokens, long secondsSinceLastSend,
            long gainTokens, boolean manualFlush) {
        if (pressureTokens < (long) Math.ceil(RECENT_BUDGET * RECENT_PRESSURE_RATIO)) {
            return SummaryVerdict.SKIP_RECENT_PRESSURE;
        }
        if (!manualFlush && newTokens < SUMMARY_MIN_NEW_TOKENS) {
            return SummaryVerdict.SKIP_NEW_TOKENS;
        }
        if (!manualFlush && secondsSinceLastSend < SUMMARY_COOLDOWN_SECONDS) {
            return SummaryVerdict.SKIP_COOLDOWN;
        }
        if (gainTokens < SUMMARY_MIN_GAIN_TOKENS) {
            return SummaryVerdict.NO_COMPRESSION_GAIN;
        }
        return SummaryVerdict.APPLY;
    }

    /**
     * 一次同日 100 轮的事件模拟。提取按 4 轮阈值批处理；摘要必须同时跨过压力、新增量与冷却，
     * 并且收益不低于 256。
     */
    public static MaintenanceResult runDay(Ledger ledger, FakeClock clock, int turns, long tokensPerTurn,
            long secondsBetweenTurns, long tokensPerUsage, boolean usageAlwaysComplete) {
        MaintenanceResult result = new MaintenanceResult();
        List<Turn> history = new ArrayList<>();
        int turnsSinceExtract = 0;
        long lastSummarySendEpochSecond = Long.MIN_VALUE / 4;
        long summarisedThroughIndex = 0;
        for (int index = 1; index <= turns; index++) {
            clock.advanceSeconds(index == 1 ? 0 : secondsBetweenTurns);
            Turn turn = new Turn(index, tokensPerTurn, clock.now());
            history.add(turn);
            turnsSinceExtract++;
            result.chatTurnsServed++;
            Attempt chat = ledger.reserve(clock, Purpose.CHAT, 8_000L, 4_096L);
            if (chat != null) {
                ledger.settle(chat, usageAlwaysComplete ? 6_000L : null, usageAlwaysComplete ? 800L : null);
            }

            if (turnsSinceExtract >= EXTRACT_TURN_THRESHOLD) {
                Attempt attempt = ledger.reserve(clock, Purpose.MEMORY_EXTRACT, EXTRACT_INPUT_BUDGET,
                        EXTRACT_OUTPUT_BUDGET);
                if (attempt == null) {
                    result.pausedBudgetWaits++;
                } else {
                    result.extractRuns++;
                    turnsSinceExtract = 0;
                    ledger.settle(attempt, usageAlwaysComplete ? tokensPerUsage : null,
                            usageAlwaysComplete ? Math.max(0, tokensPerUsage / 3) : null);
                }
            }

            long newTokens = (index - summarisedThroughIndex) * tokensPerTurn;
            long pressureTokens = recentPressureTokens(history, tokensPerTurn);
            long secondsSinceSummary = clock.now().toEpochSecond() - lastSummarySendEpochSecond;
            SummaryVerdict verdictBeforeSend = summaryVerdict(pressureTokens, newTokens, secondsSinceSummary,
                    SUMMARY_MIN_GAIN_TOKENS, false);
            if (verdictBeforeSend == SummaryVerdict.SKIP_RECENT_PRESSURE) {
                result.summarySkippedByPressure++;
            } else if (verdictBeforeSend == SummaryVerdict.SKIP_NEW_TOKENS) {
                result.summarySkippedByNewTokens++;
            } else if (verdictBeforeSend == SummaryVerdict.SKIP_COOLDOWN) {
                result.summarySkippedByCooldown++;
            } else {
                Attempt attempt = ledger.reserve(clock, Purpose.SESSION_SUMMARY, SUMMARY_INPUT_BUDGET,
                        SUMMARY_OUTPUT_BUDGET);
                if (attempt == null) {
                    result.pausedBudgetWaits++;
                } else {
                    // 已经外发并扣账，之后才判收益：无收益也是终态，不重试
                    lastSummarySendEpochSecond = clock.now().toEpochSecond();
                    long gain = estimateGain(newTokens);
                    if (summaryVerdict(pressureTokens, newTokens, secondsSinceSummary, gain, false)
                            == SummaryVerdict.NO_COMPRESSION_GAIN) {
                        result.summaryRejectedNoGain++;
                    } else {
                        result.summaryRuns++;
                        summarisedThroughIndex = Math.max(summarisedThroughIndex,
                                index - SUMMARY_RESERVED_RECENT_TURNS);
                    }
                    ledger.settle(attempt, usageAlwaysComplete ? tokensPerUsage : null,
                            usageAlwaysComplete ? Math.max(0, tokensPerUsage / 4) : null);
                }
            }
            result.maxSummaryLagTurns = Math.max(result.maxSummaryLagTurns, (int) (index - summarisedThroughIndex));
        }
        result.unextractedTurns = turnsSinceExtract;
        return result;
    }

    /** 近期完整轮次区域的压力：最近若干轮的估算 Token 之和（有界，不全库扫描）。 */
    static long recentPressureTokens(List<Turn> history, long tokensPerTurn) {
        long total = 0;
        for (int index = history.size() - 1; index >= 0 && total < RECENT_BUDGET; index--) {
            total += history.get(index).estimatedTokens();
        }
        return Math.min(total, RECENT_BUDGET);
    }

    /** 摘要净缩减：旧原文估算量减去发布摘要的 600 估算 Token 上限。 */
    static long estimateGain(long newTokens) {
        return newTokens - 600L;
    }
}
