package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 历史显式清理（contracts §3 的 {@code /history/cleanup/preview} 与 {@code /history/cleanup}，
 * 执行方案 §7.4）。
 *
 * <p>四条底线：预览只读、什么都不删；执行只清预览钉住的那一份范围（来源变了就 409，绝不扩大）；
 * 释放的字节与账本扣减同源且同事务；删原文时清理证据，已保存记忆保留。</p>
 */
class ChatHistoryCleanupTest {
    private static final Map<String, Object> MOCK_CONFIG = Map.of(
            "provider", "mock", "endpoint", "mock://local", "model", "local-demo", "apiKey", "demo-key");
    /** 夹具里所有 seeded 行共用的时间戳：清理判定不看它，但 NOT NULL 列必须有值。 */
    private static final String SEED_AT = "2026-10-18T00:00:00.000Z";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p9c6-cleanup-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), stack.contexts());
        ai.saveConfig(MOCK_CONFIG);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void previewPinsTheScopeAndDeletesNothing() throws Exception {
        chat("第一问");
        chat("第二问");
        Map<String, Object> before = stack.storage().snapshot();

        Map<String, Object> preview = preview(newestMessageId(), 200);
        assertEquals(2L, number(preview, "turnCount"));
        assertEquals(4L, number(preview, "messageCount"), "一轮的配对两行都算进范围");
        assertEquals(0L, number(preview, "legacyCount"));
        assertEquals(0L, number(preview, "excludedMessageCount"));
        assertEquals(0L, number(preview, "olderTurnCount"));
        assertTrue(number(preview, "historyBytes") > 0, "报的是逻辑落库字节，不是条数");
        assertNotNull(preview.get("previewId"));
        assertNotNull(preview.get("expiresAt"));
        assertEquals(revision(), number(preview, "historyRevision"), "预览要带回 CAS 起点");
        // 契约 §3：预览必须把实际要删的 message ID 摊开，界面才能让用户"看见"再确认
        assertEquals(4, messageIds(preview).size(), "一问一答两行 × 两轮都要列出来");
        assertEquals(2, requestIdList(preview).size(), "轮次身份也一起给，墓碑按 requestId 立");
        assertTrue(messageIds(preview).get(0) < messageIds(preview).get(1), "ID 升序，界面不用自己排");

        assertEquals(4, count("SELECT COUNT(*) FROM ai_chat_message"), "预览一行都不删");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"));
        assertEquals(number(before, "historyBytes"), number(stack.storage().snapshot(), "historyBytes"),
                "预览不得扣账本");
        assertEquals(number(before, "revision"), number(stack.storage().snapshot(), "revision"));
    }

    @Test
    void cleanupReleasesExactlyThePreviewedBytesAndLeavesTombstones() throws Exception {
        chat("第一问");
        chat("第二问");
        long stored = number(stack.storage().snapshot(), "historyBytes");
        Map<String, Object> preview = preview(newestMessageId(), 200);

        Map<String, Object> result = execute(preview, "op-exact");
        assertEquals(2L, number(result, "cleanedTurns"));
        assertEquals(4L, number(result, "messageCount"));
        for (Long messageId : messageIds(preview)) {
            assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE id = ?", messageId),
                    "预览列出的 ID 就是实际删掉的 ID，一条不多一条不少");
        }
        assertEquals(number(preview, "historyBytes"), number(result, "releasedHistoryBytes"),
                "预览报多少就扣多少，字节口径必须同源");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"), "范围里的正文行都删掉了");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"),
                "墓碑行留着：重复 request ID 还要据此报 RESULT_EXPIRED");
        assertEquals(stored - number(preview, "historyBytes"),
                number(stack.storage().snapshot(), "historyBytes"), "账本只扣了这一批");
        assertTrue(wiring.ledger().reconcile().consistent(), "删除与扣账必须同事务，不留漂移");
        assertEquals(number(preview, "historyRevision") + 1, number(result, "historyRevision"),
                "清理是一次源变更");
        assertFalse(String.valueOf(stack.history().page(sessionId, null, null, 10)).contains("第一问"),
                "清理过的正文不能继续从历史分页里读出来");
    }

    @Test
    void purgesEvidenceButKeepsSavedMemoriesWithTheContent() throws Exception {
        chat("这一问被记住了");
        long turnId = jdbc.queryForObject("SELECT id FROM ai_chat_turn ORDER BY id DESC LIMIT 1", Long.class);
        seedEvidenceOwnedBy(turnId);

        execute(preview(newestMessageId(), 200), "op-evidence");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence"), "原话不得以快照名义留存");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item_evidence"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE row_id = 'row-1'"),
                "清理原话不能连带删除已保存的长期记忆");
    }

    @Test
    void legacyDisplayRowsAreCountedAndCleared() throws Exception {
        // 迁移前的旧行先躺在库里：进程第一次 begin() 的 rebuild 会把它测进账本，清理才扣得动
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'user', '迁移前的旧消息')",
                sessionId);
        chat("有一轮才有边界");

        Map<String, Object> preview = preview(newestMessageId(), 200);
        assertEquals(1L, number(preview, "legacyCount"), "legacy 行没有轮次也要算进范围，不能被静默跳过");
        assertEquals(1L, number(preview, "turnCount"));
        assertEquals(3L, number(preview, "messageCount"));

        Map<String, Object> result = execute(preview, "op-legacy");
        assertEquals(1L, number(result, "cleanedLegacyRows"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id IS NULL"),
                "legacy 行没有墓碑列可写，只能删行");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"),
                "同一批里的配对轮次照常立墓碑，两批一起清完");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"), "预览报三条就删三条");
        assertTrue(wiring.ledger().reconcile().consistent(), "legacy 的字节也要账本两清");
    }

    @Test
    void maxTurnsKeepsTheNewestTurnsAndCountsTheRestSeparately() throws Exception {
        chat("最早的一问");
        chat("中间的一问");
        chat("最新的一问");

        Map<String, Object> preview = preview(newestMessageId(), 2);
        assertEquals(2L, number(preview, "turnCount"));
        assertEquals(1L, number(preview, "olderTurnCount"), "更早那轮要如实报成「还没纳入」，不许说成全部清完");
        assertEquals(2L, number(preview, "excludedMessageCount"));

        Map<String, Object> result = execute(preview, "op-cap");
        assertEquals(2L, number(result, "cleanedTurns"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "只清贴边界的两轮，更早那轮原样留着");
        long oldest = jdbc.queryForObject("SELECT id FROM ai_chat_turn ORDER BY id LIMIT 1", Long.class);
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id = ?", oldest));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE id = ? AND content_deleted_at IS NOT NULL",
                oldest), "没纳入范围的轮次不许被顺手立墓碑");
    }

    /**
     * Q1 定为<b>会话级</b>：同会话只要还有一轮在流式，整次清理都拒绝。
     *
     * <p>原用例写的是"范围级"——RUNNING 那一轮不进范围就可以照删别的。这个前提现在被契约推翻：
     * 流式中的轮次马上要往同一页里写正文，让它和清理抢同一批行会留下半新半旧的会话。
     * 预览是纯读，所以照旧能出；拒的是执行，且拒在 CAS 之前（版本一步都不许动）。</p>
     */
    @Test
    void aRunningTurnInThisSessionRefusesTheWholeCleanup() throws Exception {
        chat("已经完成的一问");
        ChatTurnService.Lease live = chatLease("还在流式的一问");

        Map<String, Object> preview = preview(newestMessageId(), 200);
        assertEquals(1L, number(preview, "turnCount"), "RUNNING 那一轮还没成形，不许进范围");
        assertEquals(1L, number(preview, "excludedMessageCount"), "它的用户行留着，界面据此说明本轮不完整");

        Map<String, Object> before = stack.storage().snapshot();
        long revisionBefore = revision();
        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class,
                () -> execute(preview, "op-running"));
        assertEquals(409, error.status());
        assertEquals("CLEANUP_SESSION_RUNNING", error.code(), "同会话还有流式轮次时不许清：两条路径会抢同一批行");
        assertEquals(revisionBefore, number(error.details(), "historyRevision"), "拒绝要带回最新版本供界面重锚");

        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_chat_turn WHERE id = ?",
                String.class, live.turnId()));
        assertEquals(3, count("SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?", sessionId),
                "拒绝就不能删任何东西，连已完成那轮也不行");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"));
        assertEquals(revisionBefore, revision(), "会话级前提排在 CAS 之前：拒绝不能推进历史版本");
        assertEquals(number(before, "historyBytes"), number(stack.storage().snapshot(), "historyBytes"),
                "拒绝也不能扣账本");
    }

    @Test
    void turnThatFinishesAfterPreviewStaysOutOfScopeAndIsRefusedByTheRevisionGate() throws Exception {
        chat("第一问");
        ChatTurnService.Lease live = chatLease("第二问");
        Map<String, Object> preview = preview(newestMessageId(), 200);
        assertEquals(1L, number(preview, "turnCount"), "预览时还在流式的那轮不进范围");
        finish(live, "第二问的回复");
        // 收尾本身就往账本加了字节，所以要拿拒绝之前的这一份比，才能证明拒绝没有再扣一次
        Map<String, Object> before = stack.storage().snapshot();

        // 边界按 message ID 钉死：它后来多出来的回复行越出边界，所以范围没变大，拒的是"会话版本已被推进"
        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class,
                () -> execute(preview, "op-finished"));
        assertEquals(409, error.status());
        assertEquals("CLEANUP_REVISION_CONFLICT", error.code());
        assertEquals(4, count("SELECT COUNT(*) FROM ai_chat_message"), "拒绝就不能删任何东西");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"));
        assertEquals(number(before, "historyBytes"), number(stack.storage().snapshot(), "historyBytes"),
                "拒绝也不能扣账本");
        assertEquals(revision(), number(error.details(), "historyRevision"), "拒绝要带回最新版本供界面重锚");
    }

    @Test
    void tombstoneWrittenAfterPreviewIsRefusedAndNeverDoubleReleased() throws Exception {
        Map<String, Object> first = chat("先清掉的一问");
        chat("后清掉的一问");
        Map<String, Object> preview = preview(newestMessageId(), 200);
        assertEquals(2L, number(preview, "turnCount"));
        // 另一条生产删除路径先把其中一轮清了：预览的字节口径已经对不上号
        wiring.turns().clearContent(String.valueOf(first.get("requestId")), "2026-10-18T00:00:00.000Z");

        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class,
                () -> execute(preview, "op-tombstoned"));
        assertEquals(409, error.status());
        assertEquals("CLEANUP_SOURCE_CHANGED", error.code());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "剩下那轮的行还在");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"),
                "同一批字节不能被释放第二次");
    }

    @Test
    void expiredPreviewIsRefusedWith410() throws Exception {
        chat("要被清的一问");
        stack.storage().usePreviewTtlMillis(-1000L);

        ChatTurnRejectException expired = assertThrows(ChatTurnRejectException.class,
                () -> execute(preview(newestMessageId(), 200), "op-expired"));
        assertEquals(410, expired.status());
        assertEquals("CLEANUP_PREVIEW_EXPIRED", expired.code());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "过期令牌不能删任何东西");
    }

    @Test
    void foreignOrMalformedTokenIsRefusedWith410() throws Exception {
        chat("要被清的一问");
        Map<String, Object> preview = preview(newestMessageId(), 200);
        // 换一把签名密钥 = 应用重启之后的旧令牌：预览只活五分钟（契约 §3），而且不建驻留表，
        // 重启后只能重新预览——这条代价在 D190 里登记为有意偏离，并用下面这两枚钉成断言
        ChatStorageService restarted = new ChatStorageService(wiring.ledger(), stack.historyRepo(),
                wiring.metadata(), wiring.turns(), wiring.memory(),
                new MemoryOperationRepository(dataSource, wiring.immediate()), new ObjectMapper());

        assertEquals(410, assertThrows(ChatTurnRejectException.class,
                () -> restarted.cleanup(Map.of("previewId", preview.get("previewId"),
                        "expectedHistoryRevision", number(preview, "historyRevision"), "confirm", true))).status());
        ChatTurnRejectException garbage = assertThrows(ChatTurnRejectException.class,
                () -> stack.storage().cleanup(Map.of("previewId", "not-a-token",
                        "expectedHistoryRevision", number(preview, "historyRevision"), "confirm", true)));
        assertEquals(410, garbage.status());
        assertEquals("CLEANUP_PREVIEW_EXPIRED", garbage.code());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "两枚不认得的令牌都动不到内容");
    }

    /**
     * 复核 N2（F4 唯一没有臂的分支）：令牌里签的 revision 与请求带的必须是同一份。
     *
     * <p>复核方的 M8 实测：把这段检查删掉，其余 17 条全绿——说明它当时没有任何用例守着。
     * 这道门防的不是"内容变了"（那是指纹与 CAS 的事），而是客户端自己串了线：
     * 拿 A 会话/上一次的令牌去配 B 的 CAS 基线。它排在签名之后、{@code operationId} 去重之前，
     * 所以拒绝既不能删东西、也不能留下一条进行中的登记、更不能推进会话版本。</p>
     */
    @Test
    void mismatchedRevisionIsRefusedWith400() throws Exception {
        chat("要被清的一问");
        Map<String, Object> preview = preview(newestMessageId(), 200);
        long previewRevision = number(preview, "historyRevision");

        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class,
                () -> executeBody(preview, "op-wrong-revision", true, previewRevision + 1));
        assertEquals(400, error.status());
        assertEquals("CLEANUP_PREVIEW_REVISION_MISMATCH", error.code());
        assertEquals(previewRevision, number(error.details(), "previewRevision"));
        assertEquals(previewRevision + 1, number(error.details(), "expectedHistoryRevision"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "拒绝不能删任何东西");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_operation WHERE operation_id = ?", "op-wrong-revision"),
                "这道门排在去重之前：拒绝不该留下一条进行中的登记");
        assertEquals(previewRevision, revision(), "也不能推进会话历史版本");

        // 带对的那一份就过得去：证明拒的是数字不一致，不是这道门本身坏了
        assertEquals(2L, number(execute(preview, "op-right-revision"), "messageCount"));
    }

    @Test
    void sameOperationIdClearsOnlyOnce() throws Exception {
        chat("只该被清一次的一问");
        Map<String, Object> preview = preview(newestMessageId(), 200);

        Map<String, Object> first = execute(preview, "op-twice");
        Map<String, Object> replay = execute(preview, "op-twice");
        assertEquals(true, replay.get("replayed"), "重放要如实标注，不能伪装成又清了一次");
        assertEquals(number(first, "releasedHistoryBytes"), number(replay, "releasedHistoryBytes"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"), "第二次不扩大范围也不重复扣账本");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_operation WHERE operation_id = 'op-twice'"));
    }

    @Test
    void confirmationAndShapeGuardsComeBeforeAnyWrite() throws Exception {
        chat("形状不对就动不到它");
        long boundary = newestMessageId();
        Map<String, Object> preview = preview(boundary, 200);

        ChatTurnRejectException unconfirmed = assertThrows(ChatTurnRejectException.class,
                () -> executeBody(preview, "op-no", false, number(preview, "historyRevision")));
        assertEquals(400, unconfirmed.status());
        assertEquals("CLEANUP_CONFIRM_REQUIRED", unconfirmed.code());

        ChatTurnRejectException noToken = assertThrows(ChatTurnRejectException.class,
                () -> stack.storage().cleanup(Map.of("expectedHistoryRevision", 1, "confirm", true)));
        assertEquals(400, noToken.status());
        assertEquals("CLEANUP_PREVIEW_REQUIRED", noToken.code());

        ChatTurnRejectException tooMany = assertThrows(ChatTurnRejectException.class,
                () -> preview(boundary, 201));
        assertEquals(400, tooMany.status());
        assertEquals("CLEANUP_MAX_TURNS_INVALID", tooMany.code());
        assertEquals(400, assertThrows(ChatTurnRejectException.class, () -> preview(boundary, 0)).status());

        jdbc.update("INSERT INTO ai_chat_session(title) VALUES ('另一个会话')");
        long other = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_session", Long.class);
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'user', '别人的消息')", other);
        long foreign = jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message", Long.class);
        ChatTurnRejectException boundaryElsewhere = assertThrows(ChatTurnRejectException.class,
                () -> preview(foreign, 200));
        assertEquals(400, boundaryElsewhere.status());
        assertEquals("CLEANUP_BOUNDARY_UNKNOWN", boundaryElsewhere.code(), "边界必须是本会话真实存在的消息");

        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?", sessionId),
                "五道拒绝都没动过本会话的行");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE content_deleted_at IS NOT NULL"));
    }

    // ---------- F1：清原文必须同时断开摘要与任务 ----------

    /**
     * 复核 F1：清理过去只删消息行，摘要检查点与在队任务原地留着，读侧会继续拿一份引用了已删原话的
     * 摘要当结论（execution §7.4.5「失效相关摘要/任务」、contracts §3「先断开相关读/任务」）。
     *
     * <p>作废任务只作废<b>读数跨过被清轮次</b>的那几条：把整个会话的任务一并 SUPERSEDED，会让用户
     * 删掉一条老消息之后，连新消息也没人整理了——那是把清理的代价转嫁成记忆停摆。</p>
     */
    @Test
    void cleanupDropsTheSummaryCheckpointAndSupersedesJobsThatReadTheCleanedTurns() throws Exception {
        chat("早的一问");
        chat("晚的一问");
        List<Long> turnIds = jdbc.queryForList("SELECT id FROM ai_chat_turn ORDER BY id", Long.class);
        long older = turnIds.get(0);
        long newer = turnIds.get(1);
        seedCheckpoint(older);
        seedJob("job-overlap", older, newer);
        seedJob("job-kept", newer, newer);

        Map<String, Object> preview = preview(lastMessageOf(older), 200);
        assertEquals(1L, number(preview, "turnCount"), "边界钉在早一轮的末行：只清早一轮");

        Map<String, Object> result = execute(preview, "op-checkpoint");
        assertEquals(1L, number(result, "checkpointsInvalidated"), "摘要检查点必须随原文一起作废");
        assertEquals(1L, number(result, "jobsSuperseded"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = ?", sessionId),
                "检查点没有改一半的余地：整份删掉，读侧从此按原文重建");
        assertTrue(stack.checkpoints().read(sessionId).isEmpty());
        assertEquals("SUPERSEDED", jobStatus("job-overlap"), "读数跨过被清轮次的任务不能再去整理已经不存在的原话");
        assertEquals("QUEUED", jobStatus("job-kept"), "只读保留轮次的任务不该被顺手作废");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id = ?", newer),
                "保留那一轮一行都不能动");
        assertTrue(wiring.ledger().reconcile().consistent());
    }

    /**
     * 复核 N1：检查点的 {@code through_turn_id} 可以<b>落后于</b>本批清理范围，那种情况下它引用不到任何
     * 一条要消失的原话，删它就是无故让一段有效摘要失效。判定只看"已检查上界有没有落进本批"，
     * 而本批最早的轮次必须自己算 min——选取顺序是最新在前，{@code get(0)} 是最新的那一轮。
     *
     * <p>同一格里把反方向也钉住：一旦把摘要覆盖过的那一轮清掉，整行必须作废（M5"从不删"那一格的臂）。
     * 但这一格<b>区分不出</b> {@code min()} 与 {@code get(0)}——那要另一格"检查点停在本批中段"的形状，
     * 见 {@link #checkpointThroughTheMiddleOfTheCleanedBatchIsStillInvalidated}（M10）。</p>
     */
    @Test
    void checkpointThatStopsBeforeTheCleanedBatchSurvivesTheCleanup() throws Exception {
        chat("最早的一问");
        chat("中间的一问");
        chat("最新的一问");
        List<Long> turnIds = jdbc.queryForList("SELECT id FROM ai_chat_turn ORDER BY id", Long.class);
        long oldest = turnIds.get(0);
        seedCheckpoint(oldest);
        seedJob("job-oldest", oldest, oldest);

        Map<String, Object> preview = preview(newestMessageId(), 2);
        assertEquals(2L, number(preview, "turnCount"), "边界在最新一轮，maxTurns=2 取的是最近两轮");

        Map<String, Object> result = execute(preview, "op-checkpoint-ahead");
        assertEquals(0L, number(result, "checkpointsInvalidated"), "已检查上界在本批之前：不该被顺手删");
        assertEquals(0L, number(result, "jobsSuperseded"), "同理：只读最早那一轮的任务没道理被作废");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = ?", sessionId));
        assertEquals("QUEUED", jobStatus("job-oldest"));
        assertEquals(oldest,
                jdbc.queryForObject("SELECT through_turn_id FROM ai_session_context", Long.class).longValue(),
                "留着的那一份必须还是原来那一份");

        // 反方向：把最早那一轮也清掉，摘要就还在替已消失的原话说话，整行作废
        Map<String, Object> last = preview(lastMessageOf(oldest), 200);
        assertEquals(1L, number(last, "turnCount"), "最近两轮已立墓碑，这一批只剩最早那一轮");
        Map<String, Object> covered = execute(last, "op-checkpoint-covered");
        assertEquals(1L, number(covered, "checkpointsInvalidated"), "覆盖过的轮次被清：整行作废");
        assertEquals(1L, number(covered, "jobsSuperseded"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = ?", sessionId));
        assertEquals("SUPERSEDED", jobStatus("job-oldest"));
    }

    /**
     * 复核 M10：阈值必须是<b>本批最早的轮次</b>，不能是 {@code get(0)}——选取是
     * {@code ORDER BY last_message_id DESC}，{@code get(0)} 是<b>最新</b>那一轮。
     * 这两个值只有在"检查点停在本批中段"这种形状下才区分得开：上一格的两段都区分不开，
     * 所以我在 D208 里写的"反方向也有臂"是<b>错的</b>，复核方实测 {@code min()}→{@code get(0)} 之后
     * 19 条全绿。这条就是补那一个形状（一个会话最多一行检查点，塞不进上一格）。
     */
    @Test
    void checkpointThroughTheMiddleOfTheCleanedBatchIsStillInvalidated() throws Exception {
        chat("最早的一问");
        chat("中间的一问");
        chat("最新的一问");
        List<Long> turnIds = jdbc.queryForList("SELECT id FROM ai_chat_turn ORDER BY id", Long.class);
        long oldest = turnIds.get(0);
        long middle = turnIds.get(1);
        seedCheckpoint(middle);

        Map<String, Object> preview = preview(newestMessageId(), 2);
        assertEquals(2L, number(preview, "turnCount"), "本批是中间与最新两轮，检查点的上界正好是本批最早那轮");
        assertEquals(1L, number(preview, "olderTurnCount"), "最早那轮没纳入本批");

        Map<String, Object> result = execute(preview, "op-checkpoint-middle");
        assertEquals(2L, number(result, "cleanedTurns"));
        assertEquals(1L, number(result, "checkpointsInvalidated"),
                "摘要确实覆盖过被清的原话，整行必须作废；阈值若取 get(0)=最新那轮就会算成不用删");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = ?", sessionId));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id = ?", oldest),
                "没纳入本批的最早那轮照常留着");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE id = ? AND content_deleted_at IS NOT NULL",
                oldest), "也不能顺手给它立墓碑");
    }

    @Test
    void legacyOnlyCleanupLeavesTheCheckpointWhereItIs() throws Exception {
        long orphan = insertLegacy("user", "只有问句留下的迁移前展示行");
        chat("有一轮才有边界");
        long older = jdbc.queryForObject("SELECT id FROM ai_chat_turn ORDER BY id LIMIT 1", Long.class);
        seedCheckpoint(older);

        Map<String, Object> preview = preview(orphan, 200);
        assertEquals(1L, number(preview, "legacyCount"));
        assertEquals(0L, number(preview, "turnCount"), "这一批里一条轮次都没有");

        Map<String, Object> result = execute(preview, "op-legacy-only");
        assertEquals(0L, number(result, "checkpointsInvalidated"));
        assertEquals(0L, number(result, "jobsSuperseded"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_session_context WHERE session_id = ?", sessionId),
                "摘要引用的还是那一轮的轮次身份，没被这次清理碰到");
    }

    // ---------- F2：legacy 配对、边界劈开与残缺组 ----------

    /**
     * 复核 F2：迁移前的展示行没有 turn_id，边界一旦落在「问句在内、答句在外」，按行删就等于只删前半句。
     * 契约 §3 要求同一配对规则：这一对两边都不进范围，宁可少删一轮。
     */
    @Test
    void legacyPairSplitByTheBoundaryStaysWholeOnBothSides() throws Exception {
        long asked = insertLegacy("user", "迁移前的问句");
        long answered = insertLegacy("assistant", "迁移前的答句");
        chat("有一轮才有边界");

        Map<String, Object> preview = preview(asked, 200);
        assertEquals(0L, number(preview, "legacyCount"), "被边界劈开的这一对两边都不进范围");
        assertEquals(0L, number(preview, "incompleteLegacyCount"));
        assertEquals(1L, number(preview, "excludedMessageCount"), "要如实报成没纳入，不能当它不存在");
        assertTrue(messageIds(preview).isEmpty(), "预览里既没有问句 ID 也没有答句 ID");
        assertTrue(incompleteIds(preview).isEmpty(), "被劈开的那一对不算残缺组：它整对都不删");
        assertEquals(0L, number(preview, "historyBytes"), "半轮都不许算进待释放字节");

        Map<String, Object> result = execute(preview, "op-split");
        assertEquals(0L, number(result, "messageCount"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id IS NULL"), "两条都还在");

        // 边界推进到答句之后，整对都进了范围才一起清——ID 也在此刻如实出现在预览里
        Map<String, Object> whole = preview(answered, 200);
        assertEquals(2L, number(whole, "legacyCount"), "整对都进边界之后才谈得上清理");
        assertEquals(List.of(asked, answered), messageIds(whole));
        execute(whole, "op-split-whole");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id IS NULL"));
    }

    /** 落单的展示行：单列成残缺组让界面先看见，但仍在本次清理里删掉（契约 §3 的「由用户看见后才可清理」）。 */
    @Test
    void orphanLegacyRowIsReportedAsAnIncompleteGroupAndStillCleared() throws Exception {
        long orphan = insertLegacy("assistant", "只有答句留下的迁移前展示行");
        chat("有一轮才有边界");

        Map<String, Object> preview = preview(orphan, 200);
        assertEquals(1L, number(preview, "legacyCount"));
        assertEquals(1L, number(preview, "incompleteLegacyCount"), "凑不成一问一答的落单行要单列");
        assertEquals(List.of(orphan), messageIds(preview));
        assertEquals(List.of(orphan), incompleteIds(preview), "还要指名道姓说是哪一条，界面才能让用户看见它");

        Map<String, Object> result = execute(preview, "op-orphan");
        assertEquals(1L, number(result, "cleanedLegacyRows"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message WHERE id = ?", orphan));
    }

    // ---------- F3：预览报的记忆影响必须与实删数一致 ----------

    /**
     * 复核 F3：清理会连带删掉证据，甚至让自动记忆失去唯一来源。用户点确认之前必须看到这几个数，
     * 而且预览说的数与实删数不能分叉——预览用与 {@code purgeEvidence} 逐字同源的孤儿判据（§2.3）。
     */
    @Test
    void previewReportsTheMemoriesThatWillGoAndTheExecutionAgrees() throws Exception {
        chat("第一问：这一句被记住了");
        chat("第二问：这一句也被记住了");
        List<Long> turnIds = jdbc.queryForList("SELECT id FROM ai_chat_turn ORDER BY id", Long.class);
        seedVault();
        seedEvidence("ev-1", turnIds.get(0));
        seedEvidence("ev-2", turnIds.get(1));
        seedEvidence("ev-3", turnIds.get(1));
        // row-1 的原话横跨两轮：整批清完才算失去唯一来源，逐轮累加也必须得出同一个 1
        seedFact("row-1", "mem-1", false, "ev-1", "ev-2");
        // row-2 有人工保护：失去来源只断来源，内容留着
        seedFact("row-2", "mem-2", true, "ev-3");

        Map<String, Object> preview = preview(newestMessageId(), 200);
        assertEquals(3L, number(preview, "affectedEvidence"), "三条原话证据都在这一批里");
        assertEquals(0L, number(preview, "autoFactsRemoved"), "已保存的自动记忆不会随原话消失");
        assertEquals(2L, number(preview, "protectedFactsKept"));
        assertEquals(0L, number(preview, "affectedProposals"));

        Map<String, Object> result = execute(preview, "op-impact");
        assertEquals(number(preview, "affectedEvidence"), number(result, "evidenceDeleted"),
                "预览报几条证据，实删就必须是几条");
        assertEquals(number(preview, "autoFactsRemoved"), number(result, "autoFactsRemoved"));
        assertEquals(number(preview, "protectedFactsKept"), number(result, "protectedFactsKept"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE row_id = 'row-1'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item WHERE row_id = 'row-2'"),
                "人工保护的记忆不许被清理顺手删掉");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item_evidence"), "来源链接必须一起断干净");
    }

    /**
     * N3：受影响建议只数"挂在会被清掉的证据上"的那些。整个库一条都不能算进来，
     * 预览报的数还必须与实删数一致——否则用户确认时看到的是一份假的清单。
     */
    @Test
    void previewCountsOnlyProposalsAttachedToTheCleanedEvidenceAndTheExecutionAgrees() throws Exception {
        chat("第一问：这条原话挂着一条建议");
        chat("第二问：这条原话的建议不在清理范围");
        List<Long> turnIds = jdbc.queryForList("SELECT id FROM ai_chat_turn ORDER BY id", Long.class);
        seedVault();
        seedEvidence("ev-1", turnIds.get(0));
        seedEvidence("ev-2", turnIds.get(1));
        seedProposal("p-1", "ev-1", "PENDING");
        seedProposal("p-2", "ev-2", "PENDING");

        Map<String, Object> preview = preview(lastMessageOf(turnIds.get(0)), 10);
        assertEquals(1L, number(preview, "affectedEvidence"), "边界只到第一轮");
        assertEquals(1L, number(preview, "affectedProposals"), "只有挂在 ev-1 上的建议会一起删");

        Map<String, Object> result = execute(preview, "op-proposal");
        assertEquals(number(preview, "affectedProposals"), number(result, "proposalsDeleted"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal WHERE id = 'p-1'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_proposal WHERE id = 'p-2'"),
                "范围之外的建议不许被顺手删掉");
    }

    // ---------- 夹具 ----------

    private Map<String, Object> chat(String userText) {
        return ai.chat(Map.of("protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", userText)));
    }

    private ChatTurnService.Lease chatLease(String userText) {
        return wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, userText, "", List.of(),
                Map.of())).lease();
    }

    private void finish(ChatTurnService.Lease lease, String reply) {
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of(reply, null, "stop"), null);
    }

    private Map<String, Object> preview(long boundary, int maxTurns) throws SQLException {
        return stack.storage().previewCleanup(Map.of("sessionId", sessionId,
                "beforeOrAtMessageId", boundary, "maxTurns", maxTurns));
    }

    private Map<String, Object> execute(Map<String, Object> preview, String operationId) throws SQLException {
        return executeBody(preview, operationId, true, number(preview, "historyRevision"));
    }

    private Map<String, Object> executeBody(Map<String, Object> preview, String operationId, boolean confirm,
                                           long expectedHistoryRevision) throws SQLException {
        return stack.storage().cleanup(Map.of("previewId", preview.get("previewId"),
                "expectedHistoryRevision", expectedHistoryRevision, "operationId", operationId,
                "confirm", confirm));
    }

    private long newestMessageId() {
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message WHERE session_id = ?",
                Long.class, sessionId);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT history_revision FROM ai_chat_session WHERE id = ?",
                Long.class, sessionId);
    }

    /** 该轮拥有的证据与已保存记忆：清范围时删证据、保留正文，不能用 SET NULL 留下原话。 */
    private void seedEvidenceOwnedBy(long turnId) throws SQLException {
        seedVault();
        seedEvidence("ev-1", turnId);
        seedFact("row-1", "mem-1", false, "ev-1");
    }

    /** 一个够用的记忆库行：清理只按 turn/message 范围筛证据，不解 DEK。 */
    private void seedVault() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, "INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                    + " VALUES ('vault-1', 'dek', '{}', 'fingerprint', ?)", SEED_AT);
        }
    }

    /** 属于某轮原话的一条证据：{@code message_id} 取该轮最早的一行，与 source block u0 同一口径。 */
    private void seedEvidence(String evidenceId, long turnId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // (vault_id, source_hmac) 上有唯一索引，所以每条证据的 hmac 必须跟着 ID 走
            insert(connection, """
                    INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id, source_block_id,
                        source_hmac, quote_cipher, crypto_meta, source_created_at)
                    SELECT ?, 'vault-1', ?, ?, MIN(id), 'u0', 'hmac-' || ?, ?, '{}', ?
                    FROM ai_chat_message WHERE turn_id = ?
                    """, evidenceId, sessionId, turnId, evidenceId, "原始话语", SEED_AT, turnId);
        }
    }

    /** 一条已保存事实与它的来源集合：无论是否人工保护，失去来源都保留正文。 */
    private void seedFact(String rowId, String memoryId, boolean manualProtected, String... evidenceIds)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, """
                    INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id, kind,
                        predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,
                        manual_protected, observed_at, recorded_at, last_confirmed_at)
                    VALUES (?, ?, 'vault-1', 1, 'GLOBAL', 0, 'PREFERENCE', ?, ?, ?, 'ACTIVE', 'x', '{}', 'AUTO',
                        ?, ?, ?, ?)
                    """, rowId, memoryId, "p-" + rowId, "family-" + rowId, "v-" + rowId, manualProtected ? 1 : 0,
                    SEED_AT, SEED_AT, SEED_AT);
            for (String evidenceId : evidenceIds) {
                insert(connection, "INSERT INTO ai_memory_item_evidence(item_row_id, evidence_id) VALUES (?, ?)",
                        rowId, evidenceId);
            }
        }
    }

    /** 一条挂在证据上的待审建议：清理该证据时它必须跟着被计数、被删除。 */
    private void seedProposal(String id, String evidenceId, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, """
                    INSERT INTO ai_memory_proposal(id, vault_id, session_id, evidence_id, target_memory_id,
                        target_version, operation, status, version, payload_cipher, crypto_meta, dedup_hmac,
                        created_at, expires_at)
                    VALUES (?, 'vault-1', ?, ?, 'mem-target', 1, 'REPLACE_PROPOSAL', ?, 1, 'x', '{}', ?, ?, ?)
                    """, id, sessionId, evidenceId, status, "dedup-" + id, SEED_AT, "2099-01-01T00:00:00.000Z");
        }
    }

    /** 一行摘要检查点。密文是占位的：这条用例核的是「清理之后这行还在不在」，不是能不能解密。 */
    private void seedCheckpoint(long throughTurnId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, """
                    INSERT INTO ai_session_context(session_id, revision, schema_version, through_turn_id,
                        context_generation, vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)
                    VALUES (?, 1, 2, ?, 1, 1, '占位密文', '{}', 40, ?)
                    """, sessionId, throughTurnId, SEED_AT);
        }
    }

    /** 一条排队中的维护任务，读数范围是 {@code from_turn_id..through_turn_id}。 */
    private void seedJob(String id, long fromTurnId, long throughTurnId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, """
                    INSERT INTO ai_memory_job(id, kind, session_id, from_turn_id, through_turn_id,
                        context_generation, source_scope_type, source_scope_id, source_timezone, vault_epoch,
                        policy_revision, target_fingerprint, prompt_version, input_revision, status,
                        created_at, updated_at)
                    VALUES (?, 'SUMMARY', ?, ?, ?, 1, 'GLOBAL', 0, 'UTC+8', 1, 1, 'fp', 'SESSION_SUMMARY_V3',
                        'rev-1', 'QUEUED', ?, ?)
                    """, id, sessionId, fromTurnId, throughTurnId, SEED_AT, SEED_AT);
        }
    }

    private String jobStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM ai_memory_job WHERE id = ?", String.class, id);
    }

    /** 一轮的最后一条消息 ID：把边界钉在「正好到这一轮为止」时用。 */
    private long lastMessageOf(long turnId) {
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message WHERE turn_id = ?", Long.class, turnId);
    }

    /** 直接插一条迁移前的展示行（没有 turn_id），返回它的 message ID。 */
    private long insertLegacy(String role, String content) {
        jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, ?, ?)",
                sessionId, role, content);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message WHERE session_id = ?", Long.class, sessionId);
    }

    private List<Long> messageIds(Map<String, Object> preview) {
        Object value = preview.get("messageIds");
        assertTrue(value instanceof List<?>, "messageIds 必须是数组，实际：" + value);
        return ((List<?>) value).stream().map(item -> ((Number) item).longValue()).toList();
    }

    private List<String> requestIdList(Map<String, Object> preview) {
        Object value = preview.get("turnRequestIds");
        assertTrue(value instanceof List<?>, "turnRequestIds 必须是数组，实际：" + value);
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }

    private List<Long> incompleteIds(Map<String, Object> preview) {
        Object value = preview.get("incompleteLegacyMessageIds");
        assertTrue(value instanceof List<?>, "incompleteLegacyMessageIds 必须是数组，实际：" + value);
        return ((List<?>) value).stream().map(item -> ((Number) item).longValue()).toList();
    }

    private static void insert(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            statement.executeUpdate();
        }
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertTrue(value instanceof Number, key + " 必须是数字，实际：" + value);
        return ((Number) value).longValue();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
