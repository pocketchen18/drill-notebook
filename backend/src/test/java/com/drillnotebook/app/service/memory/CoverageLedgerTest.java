package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.model.ContextAudit.CoverageRange;
import com.drillnotebook.app.service.memory.CoverageLedger.Ledger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * coverage 台账的合并规则（contracts §2.5；执行方案 §8.4 第 4-5 条）。
 *
 * <p>换代继承是这里的重点：第二批必须把第一批的段带过来，否则读侧会把已整理的轮次重新拼进近期原文窗口，
 * 摘要永远换不掉那部分内容。缺口不许被"合并"掉，所以相邻才并段、中间断了就保持两段。</p>
 */
class CoverageLedgerTest {

    @Test
    void adjacentAndOverlappingSegmentsMerge() {
        assertEquals(List.of(new CoverageRange(1, 12)),
                CoverageLedger.merge(List.of(new CoverageRange(1, 6)), List.of(new CoverageRange(7, 12))).ranges(),
                "紧接上一段的新批次合成一段（§2.5）");
        assertEquals(List.of(new CoverageRange(1, 10)),
                CoverageLedger.merge(List.of(new CoverageRange(1, 6)), List.of(new CoverageRange(4, 10))).ranges());
        assertEquals(List.of(new CoverageRange(3, 9)),
                CoverageLedger.merge(List.of(new CoverageRange(5, 9)), List.of(new CoverageRange(3, 6))).ranges(),
                "输入顺序不参与判断，合并结果按轮次升序");
    }

    @Test
    void skippedTurnsStayVisibleAsSeparateSegments() {
        Ledger merged = CoverageLedger.merge(List.of(new CoverageRange(1, 4)), List.of(new CoverageRange(7, 9)));
        assertEquals(List.of(new CoverageRange(1, 4), new CoverageRange(7, 9)), merged.ranges(),
                "中间跳过的轮次不能靠合并假装整理过（§2.5「不能跨被跳过的本会话轮次」）");
        assertFalse(merged.overCap());
    }

    @Test
    void illegalSegmentsAreIgnoredAndEmptyLedgerStaysEmpty() {
        assertEquals(List.of(), CoverageLedger.merge(List.of(), List.of()).ranges());
        assertEquals(List.of(new CoverageRange(2, 5)), CoverageLedger.merge(
                List.of(new CoverageRange(0, 9), new CoverageRange(7, 3)),
                List.of(new CoverageRange(2, 5))).ranges(), "first>last 或 ID 非正的不合进台账");
        assertEquals(Ledger.NONE, CoverageLedger.merge(null, null));
    }

    @Test
    void overCapIsReportedInsteadOfSilentlyDroppingSegments() {
        List<CoverageRange> previous = new ArrayList<>();
        for (int index = 0; index < CoverageLedger.MAX_RANGES; index++) {
            // 每段之间留一个断点，保证它们不会被合并
            previous.add(new CoverageRange(index * 2L + 1, index * 2L + 1));
        }
        assertFalse(CoverageLedger.merge(previous, List.of()).overCap(), "刚好 128 段还有效（§2.5）");

        Ledger over = CoverageLedger.merge(previous, List.of(new CoverageRange(400, 401)));
        assertEquals(CoverageLedger.MAX_RANGES + 1, over.ranges().size());
        assertTrue(over.overCap(), "超上限要如实报出来，由调用方丢继承段并标缺口");
    }

    @Test
    void batchTurnsSplitWhereIdsAreNotConsecutive() {
        List<MaintenancePorts.SourceTurn> turns = List.of(turn(5), turn(6), turn(9));
        assertEquals(List.of(new CoverageRange(5, 6), new CoverageRange(9, 9)),
                CoverageLedger.ofTurns(turns).ranges(), "被跳过的 7、8 算缺口，不算已覆盖");
        assertEquals(List.of(), CoverageLedger.ofTurns(List.of()).ranges());
    }

    private static MaintenancePorts.SourceTurn turn(long turnId) {
        return new MaintenancePorts.SourceTurn(turnId, 1L, turnId * 10, turnId * 10 + 1, "用户正文", "助手正文",
                40, 0L, List.of(), "2026-10-11T00:00:00.000Z");
    }
}
