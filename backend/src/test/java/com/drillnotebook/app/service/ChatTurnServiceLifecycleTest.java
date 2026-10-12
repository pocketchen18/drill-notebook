package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatStorageLedger;
import com.drillnotebook.app.repository.ChatStorageSizer;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.service.ChatTurnService.BeginInput;
import com.drillnotebook.app.service.ChatTurnService.BeginResult;
import com.drillnotebook.app.service.ChatTurnService.FinalResult;
import com.drillnotebook.app.service.ChatTurnService.Lease;
import com.drillnotebook.app.service.ChatTurnService.UpstreamReply;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轮次生命周期服务（执行方案 §7.2/§7.2.1/§7.3）：幂等重放、容量拒绝、终态裁决、
 * 失败兜底、孤儿恢复与存储降级门闩。本类只验生命周期，prompt 组装留给 P4。
 */
class ChatTurnServiceLifecycleTest {
    private static final String REPLY = "好的，已记住。";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatTurnService service;
    private ChatStorageLedger ledger;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p3a-turns-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        ledger = wiring.ledger();
        service = wiring.turnService();
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE ai_chat_session SET memory_learn_enabled = 1 WHERE id = ?", sessionId);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void beginPersistsOnlyCiphertextAndReservesReplySpace() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "我把雅思安排在周末"));

        assertTrue(begun.started());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content, content_cipher IS NULL AS no_cipher FROM ai_chat_message WHERE id = ?",
                begun.lease().userMessageId());
        assertEquals("", row.get("content"), "明文列必须留空");
        assertEquals(0, ((Number) row.get("no_cipher")).intValue(), "用户原话只能以密文存在");
        assertFalse(dbContains("我把雅思安排在周末"), "落库文件里不得出现本轮原话");
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, ledger.read().reservedHistoryBytes(), "回复预留已占用");
        assertEquals(1, wiring.handles().size(), "RUNNING 之前句柄已登记");
    }

    @Test
    void sameRequestIdReplaysStoredReplyWithoutSecondWrite() throws Exception {
        BeginInput first = input(sessionId, "重复请求测试");
        BeginResult begun = service.begin(first);
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);
        long bytesAfterFirst = ledger.read().historyBytes();
        long revisionAfterFirst = revision(sessionId);

        BeginResult again = service.begin(sameId(first));

        assertTrue(again.replayed(), "同 ID 再发只回放，不再外发");
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, again.replayStatus());
        assertEquals(REPLY, again.replayReply(), "回放要能把已存回复解出来");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "重放不追加消息");
        assertEquals(bytesAfterFirst, ledger.read().historyBytes(), "重放不二次记账");
        assertEquals(revisionAfterFirst, revision(sessionId), "重放不是源变更");
    }

    @Test
    void runningDuplicatePointsToStatusInsteadOfRegenerating() throws Exception {
        BeginInput first = input(sessionId, "仍在进行的请求");
        BeginResult begun = service.begin(first);

        ChatTurnRejectException conflict = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(sameId(first)));

        assertEquals(409, conflict.status());
        assertEquals("SESSION_BUSY", conflict.code());
        assertEquals("/api/ai/turns/" + begun.lease().requestId(), conflict.details().get("statusUrl"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(1, wiring.handles().size(), "重复请求不得挤掉正在跑的句柄");
    }

    @Test
    void duplicateWithDifferentSessionOrDifferentInputIsConflict() throws Exception {
        BeginInput first = input(sessionId, "原会话内容");
        BeginResult begun = service.begin(first);
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);
        long other = newSession("另一会话");

        ChatTurnRejectException wrongSession = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(new BeginInput(begun.lease().requestId(), 2, other, "原会话内容", "",
                        List.of(), Map.of())));
        assertEquals(409, wrongSession.status());
        assertEquals("REQUEST_CONFLICT", wrongSession.code());

        ChatTurnRejectException wrongInput = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(sameIdButText(first, "改过的内容")));
        assertEquals(409, wrongInput.status(), "同 ID 不同输入不能冒充幂等恢复");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(0, wiring.handles().size());
    }

    @Test
    void secondTurnInSameSessionWaitsButAnotherSessionProceeds() throws Exception {
        service.begin(input(sessionId, "会话占用测试"));

        ChatTurnRejectException busy = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(input(sessionId, "同一会话的第二轮")));
        assertEquals("SESSION_BUSY", busy.code());

        long other = newSession("并行会话");
        assertTrue(service.begin(input(other, "别的会话可以开始")).started());
    }

    @Test
    void capacityFullStartsNothingAndCallsNoUpstream() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "先把存量用起来"));
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);
        ChatStorageLedger.Snapshot before = ledger.read();
        // 额度压到「当前用量 + 一点余量」：新轮次连同 2MiB 回复预留必然装不下
        ledger.updateLimits(before.revision(), before.historyBytes() + 1024, before.attachmentBytes() + 1024);

        ChatTurnRejectException full = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(input(sessionId, "装不下的新轮次")));

        assertEquals(409, full.status());
        assertEquals("HISTORY_STORAGE_FULL", full.code());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"), "不留残轮");
        assertEquals(0, ledger.read().reservedHistoryBytes(), "拒绝时不占用预留");
        assertEquals(0, wiring.handles().size(), "开始失败必须撤销句柄");
    }

    @Test
    void completedTurnSettlesReservationExactlyOnce() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "结算只一次"));
        FinalResult done = service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, "想想", "stop"), null);

        assertEquals(ChatTurnRepository.STATUS_COMPLETED, done.status());
        assertEquals("saved", done.persistenceStatus());
        assertTrue(done.stateDurable());
        assertEquals(0, ledger.read().reservedHistoryBytes(), "预留随终态一次性释放");
        assertEquals(0, wiring.handles().size());

        // 迟到的第二次收尾（例如重试的旧线程）不能重复插消息、重复结算
        FinalResult late = service.finalizeTurn(begun.lease(), UpstreamReply.of("另一段正文", null, "stop"), null);
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, late.status());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"));
        assertTrue(ledger.reconcile().consistent(), "重复收尾不得再记账");
    }

    @Test
    void onlyReasoningIsFailedButKeptAndNotLearnable() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "只有思考链"));

        FinalResult done = service.finalizeTurn(begun.lease(), UpstreamReply.of("", "我在想要不要回答", "stop"), null);

        assertEquals(ChatTurnRepository.STATUS_FAILED, done.status(), "正文为空不能算 COMPLETED");
        assertEquals("empty_response", jdbc.queryForObject(
                "SELECT finish_reason FROM ai_chat_turn WHERE request_id = ?", String.class,
                begun.lease().requestId()));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE role = 'assistant'"), "思考链仍要留着展示");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'FAILED' AND memory_eligible = 1"),
                "失败轮次不得成为记忆来源");
    }

    @Test
    void userStopKeepsPartialReplyAsAborted() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "中途停止"));
        service.cancel(begun.lease().requestId());

        FinalResult done = service.finalizeTurn(begun.lease(), UpstreamReply.of("只说了一半", null, null), null);

        assertEquals(ChatTurnRepository.STATUS_ABORTED, done.status());
        assertEquals("只说了一半", assistantText(begun.lease().requestId()));
        assertEquals(0, ledger.read().reservedHistoryBytes());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'ABORTED' AND memory_eligible = 1"));
    }

    @Test
    void captureLimitAndProviderLengthBothTruncate() throws Exception {
        BeginResult limited = service.begin(input(sessionId, "触及捕获上限"));
        assertEquals(ChatTurnRepository.STATUS_TRUNCATED,
                service.finalizeTurn(limited.lease(), new UpstreamReply("半段正文", null, "stop", true), null).status());
        assertEquals("OUTPUT_CAPTURE_LIMIT", jdbc.queryForObject(
                "SELECT finish_reason FROM ai_chat_turn WHERE request_id = ?", String.class,
                limited.lease().requestId()));

        BeginResult byProvider = service.begin(input(sessionId, "厂商截断"));
        assertEquals(ChatTurnRepository.STATUS_TRUNCATED,
                service.finalizeTurn(byProvider.lease(), UpstreamReply.of("半段正文", null, "length"), null).status());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'TRUNCATED' AND memory_eligible = 1"),
                "超限输出不可学习");
    }

    @Test
    void missingTerminalFrameIsFailedEvenWithText() throws Exception {
        // 上游断流后确实收到了正文，但没看到终止帧：不能算正常完成，也不能进学习（§7.2 第 4 条）
        BeginResult begun = service.begin(input(sessionId, "流断在半路"));
        FinalResult done = service.finalizeTurn(begun.lease(),
                new UpstreamReply("半句就断了", null, "stream_error", false, false, false,
                        AiUpstreamClient.Usage.unknown()), null);

        assertEquals(ChatTurnRepository.STATUS_FAILED, done.status());
        assertEquals("半句就断了", assistantText(begun.lease().requestId()), "部分回复仍要能展示");
        assertEquals("stream_error", jdbc.queryForObject(
                "SELECT finish_reason FROM ai_chat_turn WHERE request_id = ?", String.class,
                begun.lease().requestId()));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'FAILED' AND memory_eligible = 1"));
    }

    @Test
    void downstreamGoneIsAbortedInsteadOfCompleted() throws Exception {
        // 前端断开（关页面/切会话）：等同于用户主动停止，按实际收到的部分收尾，不谎称完成（§7.3）
        BeginResult begun = service.begin(input(sessionId, "页面被关掉了"));
        FinalResult done = service.finalizeTurn(begun.lease(),
                new UpstreamReply("已经写了一半", null, "aborted", false, false, true,
                        AiUpstreamClient.Usage.unknown()), null);

        assertEquals(ChatTurnRepository.STATUS_ABORTED, done.status());
        assertEquals("已经写了一半", assistantText(begun.lease().requestId()));
        assertEquals(0, ledger.read().reservedHistoryBytes());
    }

    @Test
    void replyOverReservationFallsBackToFailedTerminalAndReleasesOnce() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "回复会超出预留"));
        service.finalizeTurn(begun.lease(), UpstreamReply.of("回".repeat(1_300_000), null, "stop"), null);

        Map<String, Object> row = status(begun.lease().requestId());
        assertEquals(ChatTurnRepository.STATUS_FAILED, row.get("status"), "装不下就不许伪装 COMPLETED");
        assertEquals("failed", row.get("persistenceStatus"));
        assertEquals(0, ledger.read().reservedHistoryBytes(), "预留只释放一次");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_role = 'ASSISTANT'"),
                "落库失败的回复不落密文，也不伪造可恢复的持久回复");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_role = 'USER'"), "用户原话保留并显示该轮失败");
        assertTrue(ledger.reconcile().consistent(), "回滚后账本与实测仍要一致");
        assertFalse(service.storageDegraded(), "DB 本身没问题，不该开降级门闩");
    }

    @Test
    void orphanWithoutHandleIsInterruptedButActiveOneIsLeftAlone() throws Exception {
        BeginResult abandoned = service.begin(input(sessionId, "被丢下的轮次"));
        long other = newSession("另一个会话");
        BeginResult running = service.begin(input(other, "还在读的轮次"));
        releaseHandle(abandoned.lease());

        assertEquals(1, service.sweepOrphans());

        assertEquals(ChatTurnRepository.STATUS_INTERRUPTED, status(abandoned.lease().requestId()).get("status"));
        assertEquals(ChatTurnRepository.STATUS_RUNNING, status(running.lease().requestId()).get("status"),
                "有活跃句柄的慢请求绝不按年龄清掉");
        assertEquals(ChatStorageSizer.REPLY_RESERVATION_BYTES, ledger.read().reservedHistoryBytes(),
                "只释放孤儿那一轮的预留，还在跑的那轮继续占用");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'INTERRUPTED' AND memory_eligible = 1"),
                "重启不自动重新外发，也不留下可学习来源");
    }

    @Test
    void newRequestClearsOrphanBeforeStarting() throws Exception {
        BeginResult abandoned = service.begin(input(sessionId, "上一进程留下的轮次"));
        releaseHandle(abandoned.lease());

        BeginResult next = service.begin(input(sessionId, "重新开始的一轮"));

        assertTrue(next.started(), "孤儿被就地收尾，不再一直 SESSION_BUSY");
        assertEquals(ChatTurnRepository.STATUS_INTERRUPTED, status(abandoned.lease().requestId()).get("status"));
    }

    @Test
    void statusIsReadOnlyAndJudgesUnknownIdsByTimeWindow() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "状态查询"));
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);

        Map<String, Object> body = service.status(begun.lease().requestId());
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, body.get("status"));
        assertEquals(false, body.get("active"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "查状态不生成、不提取");

        String ancient = UuidV7.generate(System.currentTimeMillis() - UuidV7.MAX_AGE_MILLIS - 60_000);
        assertEquals(410, assertThrows(ChatTurnRejectException.class, () -> service.status(ancient)).status());
        String future = UuidV7.generate(System.currentTimeMillis() + UuidV7.MAX_FUTURE_MILLIS + 60_000);
        assertEquals(400, assertThrows(ChatTurnRejectException.class, () -> service.status(future)).status());
        assertEquals(400, assertThrows(ChatTurnRejectException.class,
                () -> service.status("01a109ca-1100-7000-8000-00000000000")).status(), "非法 ID 直接拒");
    }

    @Test
    void tombstoneReplayExpiresInsteadOfRegenerating() throws Exception {
        BeginInput first = input(sessionId, "清理后的重放");
        BeginResult begun = service.begin(first);
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = ? WHERE request_id = ?",
                "2026-10-12T00:00:00.000Z", begun.lease().requestId());

        ChatTurnRejectException expired = assertThrows(ChatTurnRejectException.class, () -> service.begin(sameId(first)));

        assertEquals(410, expired.status());
        assertEquals("RESULT_EXPIRED", expired.code(), "回复被明确删除后不能用重新生成冒充幂等恢复");
    }

    @Test
    void v1RequestStillStartsTurnWithoutIdempotencyClaim() throws Exception {
        BeginResult begun = service.begin(new BeginInput(null, 1, sessionId, "旧客户端的一轮", "", List.of(), Map.of()));

        assertTrue(begun.started(), "v1 由服务端生成 requestId，幂等不作保证但照样进容量/历史层");
        assertNotNull(begun.lease().requestId());
        assertTrue(UuidV7.isCanonical(begun.lease().requestId()));
        FinalResult done = service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, done.status());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE memory_eligible = 1"), "v1 不自动提取（§7.1）");
    }

    @Test
    void storageDegradationGatesNewTurnsUntilProbeRecovers() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "先看住降级"));
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);
        jdbc.execute("DROP TABLE ai_chat_turn");

        ChatTurnRejectException refused = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(input(sessionId, "降级期间的请求")));
        assertEquals(503, refused.status());
        assertEquals("STORAGE_UNAVAILABLE", refused.code());
        assertTrue(service.storageDegraded());

        service.probeStorage();
        assertTrue(service.storageDegraded(), "DB 仍不可用时不能谎称恢复");

        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        service.probeStorage();
        assertFalse(service.storageDegraded(), "探测成功后解除门闩");
        assertNotNull(service.begin(input(sessionId, "恢复后的请求")).lease());
    }

    @Test
    void retryOfLinksANewTurnToATerminalTarget() throws Exception {
        BeginInput stopped = input("上一轮被打断");
        BeginResult first = service.begin(stopped);
        service.cancel(first.lease().requestId());
        service.finalizeTurn(first.lease(), UpstreamReply.of("半句话", null, "aborted"), null);

        BeginResult retry = service.begin(new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId,
                "重新生成一次", "", List.of(), Map.of(), first.lease().requestId()));
        assertEquals(first.lease().requestId(), jdbc.queryForObject(
                "SELECT retry_of FROM ai_chat_turn WHERE request_id = ?", String.class, retry.lease().requestId()),
                "重新生成必须显式留下 retryOf，前端才能去掉旧的中断续接提示（§7.2-7）");
        assertEquals(first.lease().requestId(), status(first.lease().requestId()).get("requestId"));
        service.finalizeTurn(retry.lease(), UpstreamReply.of("完整回复", null, "stop"), null);
    }

    @Test
    void retryTargetMustBeKnownSameSessionAndAlreadyTerminal() throws Exception {
        BeginResult live = service.begin(input("还在跑的一轮"));
        ChatTurnRejectException active = assertThrows(ChatTurnRejectException.class, () -> service.begin(
                new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "重试在跑的", "",
                        List.of(), Map.of(), live.lease().requestId())));
        assertEquals("RETRY_TARGET_ACTIVE", active.code());
        service.finalizeTurn(live.lease(), UpstreamReply.of(REPLY, null, "stop"), null);

        assertEquals("RETRY_TARGET_UNKNOWN", assertThrows(ChatTurnRejectException.class, () -> service.begin(
                new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "重试不存在的", "",
                        List.of(), Map.of(), UuidV7.generate(System.currentTimeMillis())))).code());
        long other = newSession("另一个会话");
        assertEquals("RETRY_TARGET_FOREIGN", assertThrows(ChatTurnRejectException.class, () -> service.begin(
                new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, other, "跨会话重试", "",
                        List.of(), Map.of(), live.lease().requestId()))).code());
        assertEquals("RETRY_ID_INVALID", assertThrows(ChatTurnRejectException.class, () -> service.begin(
                new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "写法不对", "",
                        List.of(), Map.of(), "01A109CA-1100-7000-8000-000000000001"))).code());
        assertEquals("RETRY_OF_REQUIRES_V2", assertThrows(ChatTurnRejectException.class, () -> service.begin(
                new BeginInput(null, 1, other, "旧客户端带 retryOf", "", List.of(), Map.of(),
                        live.lease().requestId()))).code());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"), "被拒的重试一个残轮都不留");
    }

    @Test
    void switchingSessionsKeepsTheOldTurnAndResumesWithANewRequestId() throws Exception {
        BeginResult first = service.begin(input("会话 A 的一轮"));
        long other = newSession("会话 B");
        BeginResult elsewhere = service.begin(input(other, "切到 B 也要能发言"));

        // 切走不等于停止：A 仍在跑，句柄还在，孤儿判定不能动它
        service.cancel(first.lease().requestId());
        assertEquals(true, status(first.lease().requestId()).get("active"));
        FinalResult stopped = service.finalizeTurn(first.lease(), UpstreamReply.of("切走前只说了一半", null, "aborted"),
                null);
        assertEquals(ChatTurnRepository.STATUS_ABORTED, stopped.status());

        BeginResult resumed = service.begin(new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId,
                "回到 A 重新生成", "", List.of(), Map.of(), first.lease().requestId()));
        service.finalizeTurn(resumed.lease(), UpstreamReply.of("这次说完了", null, "stop"), null);
        service.finalizeTurn(elsewhere.lease(), UpstreamReply.of("B 的回答", null, "stop"), null);

        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'ABORTED' AND memory_eligible = 1"),
                "被打断的旧轮不因重试行而被当成来源");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED' AND retry_of IS NOT NULL"));
        assertEquals(0, ledger.read().reservedHistoryBytes(), "三个会话交错收尾后预留全部结算");
    }

    @Test
    void chatUsageAttemptSettlesExactlyOnce() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "计量一次就好"));
        Lease lease = begun.lease();
        String attempt = service.startUsage(lease, "target-fp");
        assertNotNull(attempt);
        assertEquals("SENT", usageState(attempt), "外发之前就必须置 SENT（§2.6）");

        service.settleUsage(attempt, new AiUpstreamClient.Usage("provider", 100, 40, 10, 0));
        service.finalizeTurn(lease, UpstreamReply.of(REPLY, null, "stop"), null);
        assertEquals("SETTLED", usageState(attempt));

        // 迟到的重复结算不得把数字再加一遍：attempt 是结算依据，daily 只是可重建聚合
        service.settleUsage(attempt, new AiUpstreamClient.Usage("provider", 900, 900, 900, 900));
        Map<String, Object> daily = jdbc.queryForMap(
                "SELECT attempts, charged_tokens, reserved_tokens, unknown_attempts FROM ai_usage_daily"
                        + " WHERE purpose = 'CHAT'");
        assertEquals(1, ((Number) daily.get("attempts")).intValue());
        assertEquals(150, ((Number) daily.get("charged_tokens")).intValue());
        assertEquals(0, ((Number) daily.get("reserved_tokens")).intValue(), "CHAT 只计量，不占后台预留");
    }

    @Test
    void missingProviderUsageIsSettledAsUnknownNotEstimated() throws Exception {
        BeginResult begun = service.begin(input(sessionId, "厂商没回用量"));
        String attempt = service.startUsage(begun.lease(), "target-fp");
        service.settleUsage(attempt, AiUpstreamClient.Usage.unknown());
        service.finalizeTurn(begun.lease(), UpstreamReply.of(REPLY, null, "stop"), null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, usage_source, input_tokens, output_tokens FROM ai_usage_attempt WHERE id = ?", attempt);
        assertEquals("SETTLED", row.get("state"));
        assertEquals("unknown", row.get("usage_source"), "缺字段一律 unknown，绝不拿估算冒充当过的钱");
        assertNull(row.get("input_tokens"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_usage_daily WHERE charged_tokens > 0"));
    }

    @Test
    void recoveryConsumesSentLeftoversAndReleasesProvenUnsent() throws Exception {
        BeginResult crashed = service.begin(input(sessionId, "发到一半进程没了"));
        String sent = service.startUsage(crashed.lease(), "target-fp");
        service.finalizeTurn(crashed.lease(), UpstreamReply.of(REPLY, null, "stop"), null);

        BeginResult running = service.begin(input(newSession("另一个会话"), "还在读"));
        String live = service.startUsage(running.lease(), "target-fp");

        jdbc.update("INSERT INTO ai_usage_attempt(id, operation_id, purpose, target_fingerprint, budget_date,"
                + " budget_timezone, reserved_tokens, usage_source, state, created_at) VALUES"
                + " ('never-sent','op-never','CHAT','fp','2026-10-12','Asia/Shanghai',500,'unknown','RESERVED',"
                + " '2026-10-12T00:00:00.000000Z')");

        assertEquals(2, service.cleanupUnsettledUsage());
        assertEquals("UNKNOWN", usageState(sent), "SENT 未结算按 UNKNOWN 消费预留");
        assertEquals("RELEASED", usageState("never-sent"), "可证明未发出的 RESERVED 退回预留");
        assertEquals("SENT", usageState(live), "仍有活跃句柄的请求不能被恢复流程清掉（§7.2.2）");
        assertEquals(0, count("SELECT reserved_tokens FROM ai_usage_daily WHERE target_fingerprint = 'fp'"),
                "释放后当天预留退回");
    }

    // ---- 辅助 ----

    private String usageState(String attemptId) {
        return jdbc.queryForObject("SELECT state FROM ai_usage_attempt WHERE id = ?", String.class, attemptId);
    }

    private BeginInput input(long session, String text) {
        return new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, session, text, "", List.of(), Map.of());
    }

    private BeginInput input(String text) {
        return input(sessionId, text);
    }

    private static BeginInput sameId(BeginInput source) {
        return new BeginInput(source.requestId(), source.protocolVersion(), source.sessionId(), source.userText(),
                source.masterPassword(), source.attachments(), source.requestOptions());
    }

    private static BeginInput sameIdButText(BeginInput source, String text) {
        return new BeginInput(source.requestId(), source.protocolVersion(), source.sessionId(), text,
                source.masterPassword(), source.attachments(), source.requestOptions());
    }

    private void releaseHandle(Lease lease) {
        wiring.handles().release(lease.requestId(), lease.handle());
    }

    private Map<String, Object> status(String requestId) {
        return service.status(requestId);
    }

    private String assistantText(String requestId) {
        Long id = jdbc.queryForObject(
                "SELECT assistant_message_id FROM ai_chat_turn WHERE request_id = ?", Long.class, requestId);
        if (id == null) return null;
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content_cipher, content_meta FROM ai_chat_message WHERE id = ?", id);
        return wiring.cipher().read((String) row.get("content_cipher"), (String) row.get("content_meta"), "")
                .value();
    }

    private long newSession(String title) {
        jdbc.update("INSERT INTO ai_chat_session(title) VALUES (?)", title);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session", Long.class);
    }

    private long revision(long session) {
        return jdbc.queryForObject("SELECT history_revision FROM ai_chat_session WHERE id = ?", Long.class, session);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** 直接扫库文件字节：写路径一旦降级成明文，这里立刻暴露（§4 不变量）。 */
    private boolean dbContains(String text) throws Exception {
        byte[] raw = Files.readAllBytes(appRoot.resolve("data/study.db"));
        String asLatin = new String(raw, StandardCharsets.ISO_8859_1);
        return asLatin.contains(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }
}
