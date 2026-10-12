package com.drillnotebook.app.memory.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.prototype.HangingResponsePrototype.Outcome;
import org.junit.jupiter.api.Test;

/**
 * P0a 第③组（执行方案 §7.2.2、验收 F11/F12、§9.1.3）：响应头之后悬挂时，
 * 总期限必须能关闭响应体、让读取任务退出、只留一个终态，且不会重发请求。
 * 测试用 200ms 总期限和有界 await，不依赖真实 30 秒 sleep。
 */
class HangingResponsePrototypeTest {

    @Test
    void deadlineCancelsHangingJsonBody() throws Exception {
        Outcome outcome = HangingResponsePrototype.run(HangingResponsePrototype.MODE_BODY_HANG, 200L, 2_000L);
        System.out.println("[P0a-③] body-hang " + outcome);
        assertTrue(outcome.headersSeen(), "响应头已到达");
        assertTrue(outcome.readerExited(), "读取任务必须在 deadline 后退出，否则 worker 执行槽无法释放");
        assertTrue(outcome.singleTerminalState(), "只能有一个终态，实际提交 " + outcome.terminalCommits());
        assertTrue(outcome.duplicateCommits() <= 1,
                "竞争方可以尝试提交，但门闩必须拦住第二个终态，实际重复提交 " + outcome.duplicateCommits());
        assertTrue(outcome.bodyCloseAttempted(), "到期必须主动关闭响应体");
        assertTrue(outcome.readerPoolTerminated(), "取消后不能遗留读取线程");
        assertEquals(1, outcome.servedRequests(), "取消不得变成重发");
        assertTrue(outcome.readerExitMillis() >= 150L, "退出应发生在 deadline 附近，实际 "
                + outcome.readerExitMillis() + "ms");
        assertTrue(outcome.readerExitMillis() < 2_000L);
    }

    @Test
    void deadlineCancelsSseWithoutAnotherDelta() throws Exception {
        // 正文里只有一段不含换行的数据：不靠"发出下一 delta"才取消
        Outcome outcome = HangingResponsePrototype.run(HangingResponsePrototype.MODE_SSE_NO_NEWLINE, 200L, 2_000L);
        System.out.println("[P0a-③] sse-no-newline " + outcome);
        assertTrue(outcome.headersSeen());
        assertTrue(outcome.readerExited(), "SSE 行读取卡在无换行处时也必须能被总期限打断");
        assertTrue(outcome.singleTerminalState());
        assertTrue(outcome.bodyCloseAttempted(), "退出必须由总期限关闭响应体触发，而不是等下一段数据");
        assertTrue(outcome.readerExitMillis() >= 150L,
                "读取任务应在 deadline 附近才退出，实际 " + outcome.readerExitMillis() + "ms");
        assertTrue(outcome.readerPoolTerminated());
        assertEquals(1, outcome.servedRequests());
    }

    @Test
    void cancelWorksBeforeResponseBodyIsObtained() throws Exception {
        Outcome outcome = HangingResponsePrototype.run(HangingResponsePrototype.MODE_BEFORE_HEADERS, 200L, 2_000L);
        System.out.println("[P0a-③] before-headers " + outcome);
        assertFalse(outcome.headersSeen(), "服务端故意不发响应头");
        assertTrue(outcome.singleTerminalState(), "未取得响应体时同样只能有一个终态");
        assertTrue(outcome.duplicateCommits() <= 1, "取消与到期竞争不得产生第二个赢家");
        assertFalse(outcome.bodyCloseAttempted(), "还没有响应体可关");
        assertTrue(outcome.readerPoolTerminated());
        assertEquals(1, outcome.servedRequests());
    }

    @Test
    void unexitedReaderIsDetectableSoTheWorkerStaysUnhealthy() throws Exception {
        // §7.2.2：关闭/取消后 2 秒仍未退出 → 标记 worker 不健康并暂停，不新建线程绕过并发 1。
        // 这里用 1ms 的退出等待窗口模拟"读取任务还没退出"的可观测状态。
        Outcome outcome = HangingResponsePrototype.run(HangingResponsePrototype.MODE_BODY_HANG, 200L, 1L);
        System.out.println("[P0a-③] 立即判定（1ms 窗口） " + outcome);
        assertFalse(outcome.readerExited(), "窗口不足时应能观测到读取任务尚未退出，据此把 worker 标为不健康");
        assertTrue(outcome.terminalCommits() <= 1, "判定不健康的瞬间也不该已经出现两个终态");
    }
}
