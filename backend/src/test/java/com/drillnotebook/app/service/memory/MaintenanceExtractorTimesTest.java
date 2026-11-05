package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.drillnotebook.app.service.memory.MaintenancePorts.SourceBlock;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M11：相对日期必须锚定来源消息时间，而不是后台任务完成时间。 */
class MaintenanceExtractorTimesTest {

    @Test
    void sourceCreatedAtWinsOverJobFinishedTime() {
        long jobFinished = 1_760_000_000_000L;
        SourceTurn turn = new SourceTurn(
                7L, 1L, 101L, null, "明天提醒我交论文", null, 40, jobFinished,
                List.of(new SourceBlock("u0", "明天提醒我交论文")), "2026-10-01T23:30:00+08:00");

        assertEquals("2026-10-01T23:30:00+08:00", MaintenanceExtractor.blockCreatedAt(turn));
        assertNotEquals(MemoryTimes.formatMillis(jobFinished), MaintenanceExtractor.blockCreatedAt(turn),
                "后台次日执行时不能把来源日期顺延到执行日");
    }
}
