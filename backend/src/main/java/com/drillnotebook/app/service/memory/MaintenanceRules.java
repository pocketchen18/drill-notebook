package com.drillnotebook.app.service.memory;

import java.util.Locale;

/**
 * 后台维护的触发与限额（执行方案 §8.1、§8.4、§8.5）：所有魔法数字集中在这里，
 * 调度器、装载器和 worker 只引用常量，不散落阈值。
 *
 * <p>口径提醒：这些是<b>待 P0a/P11 评测调整的工程初值</b>，不是从参考项目照抄的性能保证。
 * 判定函数都是纯函数——时间由调用方传入（{@code Clock}/{@code drainOnce(now)} 可注入），
 * 因此"冷却 119/120 秒""阈值 ±1"这类边界能被直接断言，不需要真等。</p>
 */
public final class MaintenanceRules {
    /** §8.1：完整轮次累计阈值，批处理降低调用次数。 */
    public static final int EXTRACT_TURN_THRESHOLD = 4;
    /** §8.1：有新合格内容后的空闲触发（短会话也能形成记忆；关闭侧栏不阻止处理）。 */
    public static final long IDLE_TRIGGER_MILLIS = 60_000L;
    /** §8.1：合并窗口——连续发言先并成一批，再发一次。 */
    public static final long MERGE_WINDOW_MILLIS = 5_000L;
    /** §8.1：单次提取/摘要最多 8 个完整轮次。 */
    public static final int MAX_TURNS_PER_BATCH = 8;
    /** §8.1：维护单次输入/输出预算（估算 Token）；提取用独立 options，不能复用聊天的 32K。 */
    public static final int INPUT_TOKEN_BUDGET = 3_000;
    public static final int OUTPUT_TOKEN_BUDGET = 1_024;
    /** 一次外发的全额预留口径：输入 + 输出，两者都要占当天的额度。 */
    public static final int RESERVE_TOKENS = INPUT_TOKEN_BUDGET + OUTPUT_TOKEN_BUDGET;
    /** §8.4：摘要最小新增量、冷却、最低净缩减。 */
    public static final int SUMMARY_MIN_NEW_TOKENS = 1_024;
    public static final long SUMMARY_COOLDOWN_MILLIS = 120_000L;
    public static final int SUMMARY_MIN_GAIN_TOKENS = 256;
    /** §8.4 第 1 条：近期正文压力门槛是近期预算的 70%；预算本身与上下文组装同源。 */
    public static final int RECENT_TOKEN_BUDGET = 2_400;
    public static final double RECENT_PRESSURE_RATIO = 0.7d;
    /** §8.4：至少最近 2 个完整轮次始终排除在摘要源之外。 */
    public static final int SUMMARY_RESERVED_RECENT_TURNS = 2;
    /** 模型可见摘要的估算上限（§2.5）：诊断 coverage 不混进这个预算。 */
    public static final int SUMMARY_MODEL_TOKEN_LIMIT = 600;
    /** 新建摘要的明文 payload 上限（§2.5）。 */
    public static final int SUMMARY_PAYLOAD_BYTE_LIMIT = 32 * 1024;
    /** §8.1：后台并发全应用 1，聊天优先。 */
    public static final int BACKEND_CONCURRENCY = 1;
    /** §8.1：调用超时/租约。租约必须长于超时，否则正常慢请求会被误当成僵尸接管。 */
    public static final long CALL_TIMEOUT_MILLIS = 30_000L;
    public static final long LEASE_MILLIS = 90_000L;
    /** §8.1：重试最多额外 2 次，所以一次任务上限 3 次外发。 */
    public static final int MAX_ATTEMPTS = 3;
    public static final long RETRY_BACKOFF_MILLIS_FIRST = 30_000L;
    public static final long RETRY_BACKOFF_MILLIS_SECOND = 120_000L;
    /** 重试退避的抖动上限（§8.1「30 秒、120 秒+jitter」）：由调用方注入，测试里给 0。 */
    public static final long RETRY_JITTER_MILLIS = 5_000L;
    /** 等待态重新探门禁的间隔：不满足开关/授权/预算时不忙循环（§8.2 第 2 条）。 */
    public static final long GATE_PROBE_MILLIS = 60_000L;
    /** §5 的提示词版本，落进任务行参与幂等键：换提示词等于换一批输入。 */
    public static final String PROMPT_VERSION_EXTRACT = "MEMORY_EXTRACT_V2";
    public static final String PROMPT_VERSION_SUMMARY = "SESSION_SUMMARY_V2";
    /** 跳过原因短代码（存 {@code last_scan_notice}，不存正文，§2.5）。 */
    public static final String NOTICE_SOURCE_TOO_LARGE = "SOURCE_TOO_LARGE";
    public static final String NOTICE_SOURCE_DELETED = "SOURCE_DELETED";
    public static final String NOTICE_WAITING_UNLOCK = "WAITING_UNLOCK";
    public static final String NOTICE_NO_NEW_INFORMATION = "NO_NEW_INFORMATION";

    private MaintenanceRules() {
    }

    /** 提取批次的触发结论。{@code NONE} 表示连任务都不该建。 */
    public enum ExtractTrigger {
        /** 水位之后没有合格完整轮次：不创建远程任务（§8.5「没有新内容时不创建远程任务」）。 */
        NONE,
        /** 累计满阈值轮次：先等一个合并窗口，让连续发言并进同一批。 */
        BATCH,
        /** 有新内容但不足阈值：等到空闲触发，短会话也能形成记忆。 */
        IDLE
    }

    /**
     * §8.1 的两种提取触发按<b>数量</b>分流，等待时长由 {@link #extractDelayMillis} 给出。
     *
     * <p>这里刻意<b>不</b>先看"已经静默了多久"：待处理范围一建成 QUEUED 行就带着
     * {@code next_run_at}，后续发言靠就地合并把范围与时刻一起推后（§2.4「QUEUED 可合并范围」）。
     * 反过来如果非要等够静默才建行，合并窗口就没有作用对象——批只是被延迟，不是被合并。</p>
     *
     * @param newEligibleTurns 水位之后新增的合格完整轮次数
     */
    public static ExtractTrigger extractTrigger(int newEligibleTurns) {
        if (newEligibleTurns <= 0) {
            return ExtractTrigger.NONE;
        }
        return newEligibleTurns >= EXTRACT_TURN_THRESHOLD ? ExtractTrigger.BATCH : ExtractTrigger.IDLE;
    }

    /**
     * 该触发结论对应的等待时长（相对<b>最近一条来源的结束时刻</b>）。
     *
     * <p>§8.1 的 5 秒合并窗口原文挂在"高信号单轮触发"那一行，而 P6 没有显式「记住」的服务端信号，
     * 所以这一窗口用在了满阈值的路径上；不足阈值仍按 60 秒空闲触发（§14 P6 决策 D51）。</p>
     */
    public static long extractDelayMillis(ExtractTrigger trigger) {
        return switch (trigger) {
            case BATCH -> MERGE_WINDOW_MILLIS;
            case IDLE, NONE -> IDLE_TRIGGER_MILLIS;
        };
    }

    /** 摘要排队结论（发送<b>之前</b>的三道门，收益要拿到草稿之后再判）。 */
    public enum SummaryGate {
        QUEUE, RECENT_PRESSURE, NEW_VOLUME, COOLDOWN
    }

    /**
     * §8.4 第 1 条：压力、新增量、冷却三选其一不过就不排队。
     * {@code manualFlush} 只越过新增量与冷却，<b>不越过压力</b>（§8.5）。
     */
    public static SummaryGate summaryGate(long pressureTokens, long newTokens, long millisSinceLastSend,
                                          boolean manualFlush) {
        if (pressureTokens < recentPressureThreshold()) {
            return SummaryGate.RECENT_PRESSURE;
        }
        if (manualFlush) {
            return SummaryGate.QUEUE;
        }
        if (newTokens < SUMMARY_MIN_NEW_TOKENS) {
            return SummaryGate.NEW_VOLUME;
        }
        if (millisSinceLastSend < SUMMARY_COOLDOWN_MILLIS) {
            return SummaryGate.COOLDOWN;
        }
        return SummaryGate.QUEUE;
    }

    /** 压力门槛取整到"至少这么多"，1,679 不算过线、1,680 算。 */
    public static long recentPressureThreshold() {
        return (long) Math.ceil(RECENT_TOKEN_BUDGET * RECENT_PRESSURE_RATIO);
    }

    /** 摘要发布结论（§8.4 第 4 条：净缩减不足就保留旧文本）。 */
    public enum SummaryVerdict {
        APPLY, NO_COMPRESSION_GAIN
    }

    /**
     * 净缩减 = 旧摘要 + 本批可替代原文 − 新摘要；不足 {@link #SUMMARY_MIN_GAIN_TOKENS} 就不发布新版。
     * 相同输入不会被反复压缩：终态与水位一起推进。
     */
    public static SummaryVerdict summaryVerdict(long previousSummaryTokens, long batchRawTokens, long draftTokens) {
        long gain = previousSummaryTokens + batchRawTokens - draftTokens;
        return gain >= SUMMARY_MIN_GAIN_TOKENS ? SummaryVerdict.APPLY : SummaryVerdict.NO_COMPRESSION_GAIN;
    }

    /**
     * 退避时刻（§8.1）：第一次失败 30 秒、第二次 120 秒，各自再加不超过 {@link #RETRY_JITTER_MILLIS} 的抖动。
     *
     * @param attemptsDone 已经用掉的次数（含本次），从 1 开始
     */
    public static long retryDelayMillis(int attemptsDone, long jitterMillis) {
        long base = attemptsDone <= 1 ? RETRY_BACKOFF_MILLIS_FIRST : RETRY_BACKOFF_MILLIS_SECOND;
        return base + Math.max(0, Math.min(RETRY_JITTER_MILLIS, jitterMillis));
    }

    /** 当天额度是否还容得下一次外发（§2.6：跨所有 target 与三个维护 purpose 求和，取先达到者）。 */
    public static boolean budgetAllows(int attemptsToday, long spentTokens, int dailyCallLimit,
                                       long dailyTokenLimit) {
        return attemptsToday < dailyCallLimit && spentTokens + RESERVE_TOKENS <= dailyTokenLimit;
    }

    /** 规范化时区 id；无法识别时退回调用方给的默认值。 */
    public static String zoneOrDefault(String zoneId, String fallback) {
        if (zoneId == null || zoneId.isBlank()) {
            return fallback;
        }
        try {
            return java.time.ZoneId.of(zoneId.trim()).getId();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    /** 用途名规范化（{@code MEMORY_EXTRACT}/{@code SESSION_SUMMARY} 等落库口径）。 */
    public static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
