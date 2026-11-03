package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.model.ContextAudit.CoverageRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * coverage 台账的合并规则（执行方案 §8.4 第 4-5 条；contracts §2.5「coverage 只含合格完成轮次与旧有效摘要覆盖，
 * 最多 128 个范围」）。
 *
 * <p>换代时必须把旧检查点的段带过来：否则第二批之后，第一批已整理的轮次在读侧不再算"已覆盖"，
 * 会被重新拼进近期原文窗口，摘要就永远换不掉那部分内容（§14 P8 的"长会话可控"正是这件事）。</p>
 */
final class CoverageLedger {
    /** §2.5：范围段数硬上限，超了旧检查点失效、从当前可用窗口重建并标缺口。 */
    static final int MAX_RANGES = 128;

    private CoverageLedger() {
    }

    /** 合并结果。{@code overCap} 为真表示段数越界，调用方据此丢弃继承段（§2.5）。 */
    record Ledger(List<CoverageRange> ranges, boolean overCap) {
        static final Ledger NONE = new Ledger(List.of(), false);
    }

    /** 排序后折叠：相邻（{@code last + 1 == next.first}）与重叠的段合成一段，非法段直接忽略。 */
    static Ledger merge(List<CoverageRange> previous, List<CoverageRange> added) {
        List<CoverageRange> all = new ArrayList<>();
        collect(all, previous);
        collect(all, added);
        if (all.isEmpty()) {
            return Ledger.NONE;
        }
        all.sort(Comparator.comparingLong(CoverageRange::firstTurnId).thenComparingLong(CoverageRange::lastTurnId));
        List<CoverageRange> merged = new ArrayList<>();
        long first = -1L;
        long last = -1L;
        for (CoverageRange range : all) {
            if (first < 0) {
                first = range.firstTurnId();
                last = range.lastTurnId();
                continue;
            }
            if (range.firstTurnId() <= last + 1) {
                last = Math.max(last, range.lastTurnId());
                continue;
            }
            merged.add(new CoverageRange(first, last));
            first = range.firstTurnId();
            last = range.lastTurnId();
        }
        merged.add(new CoverageRange(first, last));
        return new Ledger(List.copyOf(merged), merged.size() > MAX_RANGES);
    }

    /** 本批实际参与渲染的连续段：ID 不连续（中间有被跳过的轮次）就断成多段，缺口如实留下（§2.5）。 */
    static Ledger ofTurns(List<MaintenancePorts.SourceTurn> turns) {
        List<CoverageRange> ranges = new ArrayList<>();
        long first = -1L;
        long last = -1L;
        for (MaintenancePorts.SourceTurn turn : turns) {
            if (first < 0) {
                first = turn.turnId();
                last = turn.turnId();
                continue;
            }
            if (turn.turnId() == last + 1) {
                last = turn.turnId();
                continue;
            }
            ranges.add(new CoverageRange(first, last));
            first = turn.turnId();
            last = turn.turnId();
        }
        if (first >= 0) {
            ranges.add(new CoverageRange(first, last));
        }
        return new Ledger(List.copyOf(ranges), ranges.size() > MAX_RANGES);
    }

    private static void collect(List<CoverageRange> target, List<CoverageRange> source) {
        if (source == null) {
            return;
        }
        for (CoverageRange range : source) {
            if (range != null && range.firstTurnId() > 0 && range.lastTurnId() >= range.firstTurnId()) {
                target.add(range);
            }
        }
    }
}
