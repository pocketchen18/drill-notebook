package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MemoryApiResult;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 上下文计划入口（执行方案 §9.1/§9.2/§9.4，验收 M05/M12/S04/S05/F13/F14）。
 *
 * <p>轮次、附件与记忆都走生产写路径，只有检索结果由调用方给定：这一层要证明的是
 * "什么内容允许进本轮上下文"，不是检索算法本身。</p>
 */
class ChatContextServiceTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String GOAL = "本周准备操作系统的内存管理考试";
    private static final String PNG = java.util.Base64.getEncoder().encodeToString(new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R',
            0, 0, 0, 2, 0, 0, 0, 2, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private MemoryItemService items;
    private long sessionId;
    private int sequence;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p4-service-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        ObjectMapper mapper = new ObjectMapper();
        MemoryOperationRepository operations = new MemoryOperationRepository(dataSource, wiring.immediate());
        items = new MemoryItemService(stack.crypto(), wiring.memory(), stack.vault(), stack.policy(), operations,
                new MemoryProposalRepository(dataSource, wiring.immediate()),
                new MemorySuppressionRepository(dataSource, wiring.immediate()),
                new MemoryJobRepository(dataSource, wiring.immediate()), wiring.metadata());
        new AiConfigRepository(jdbc).upsertChat("custom", "https://example.invalid/v1", "stub-model", "enc", "{}",
                "{\"contextWindow\":32000}");
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void memoryIsProvidedOnlyWhenAuthorizedRelevantAndUnlocked() throws Exception {
        unlockVault();
        enableUseConsent();
        assertEquals(201, items.add(new MemoryItemService.AddInput("GOAL", "GLOBAL", 0L, "考试安排", null, GOAL,
                null, false, op("add"))).status(), "手动录入应落成为活跃事实");
        indexReady();

        ChatContextPlan provided = plan("内存管理考试怎么复习", Map.of("use", true));
        assertEquals(1, regionCount(provided, ContextAudit.Region.MEMORY), "相关且已授权才注入（M05）");
        assertTrue(serialized(provided).contains(GOAL), "注入的是事实正文");
        assertEquals(1, ((List<?>) provided.memoryContext().get("provided")).size());

        ChatContextPlan unrelated = plan("今天有什么好看的纪录片", Map.of("use", true));
        assertEquals(0, regionCount(unrelated, ContextAudit.Region.MEMORY),
                "零重合就不注入，绝不为了凑三条拿无关记忆（M12）");

        ChatContextPlan switchedOff = plan("内存管理考试怎么复习", Map.of("use", false));
        assertEquals(0, regionCount(switchedOff, ContextAudit.Region.MEMORY), "请求侧开关关掉就不注入（S05）");

        stack.policy().revokeConsent(MemoryPolicyService.PURPOSE_USE);
        ChatContextPlan revoked = plan("内存管理考试怎么复习", Map.of("use", true));
        assertEquals(0, regionCount(revoked, ContextAudit.Region.MEMORY), "没有外发授权同样不注入（S04）");

        stack.vault().lock();
        ChatContextPlan locked = plan("内存管理考试怎么复习", Map.of("use", true));
        assertEquals(0, regionCount(locked, ContextAudit.Region.MEMORY), "锁定时既不解密也不回退明文");
        assertFalse(serialized(locked).contains(GOAL));
    }

    @Test
    void notebookScopeReadsOnlyItsOwnFactsAndPageMustMatchTheSession() throws Exception {
        unlockVault();
        enableUseConsent();
        jdbc.update("INSERT INTO notebook(id, title) VALUES (8, '操作系统')");
        jdbc.update("INSERT INTO notebook(id, title) VALUES (9, '雅思')");
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 8L, completeTurn("定作用域", "回答", null).turnId());
        assertEquals(201, items.add(new MemoryItemService.AddInput("GOAL", "NOTEBOOK", 8L, "考试安排", null, GOAL,
                null, false, op("add-notebook"))).status());
        indexReady();

        ChatContextPlan own = plan("内存管理考试怎么复习", Map.of("use", true));
        assertEquals(1, regionCount(own, ContextAudit.Region.MEMORY), "NOTEBOOK 作用域读自身与全局");

        ChatTurnRejectException missing = assertThrows(ChatTurnRejectException.class,
                () -> plan("内存管理考试怎么复习", Map.of("use", true), Map.of("pageId", "404")));
        assertEquals(422, missing.status());
        assertEquals("PAGE_CONTEXT_INVALID", missing.code());

        ChatTurnRejectException mismatch = assertThrows(ChatTurnRejectException.class,
                () -> plan("内存管理考试怎么复习", Map.of("use", true),
                        Map.of("pageId", String.valueOf(pageIn(9L)))));
        assertEquals(422, mismatch.status());
        assertEquals("PAGE_CONTEXT_MISMATCH", mismatch.code(), "页面作用域与会话不一致就直接拒（§4.1）");

        long ownPage = pageIn(8L);
        ChatContextPlan ok = plan("内存管理考试怎么复习", Map.of("use", true),
                Map.of("pageId", String.valueOf(ownPage), "markdown", "虚拟内存的分页机制"));
        assertEquals(1, regionCount(ok, ContextAudit.Region.PAGE), "同作用域的页面才进上下文");
    }

    @Test
    void historyImagesReplayOnlyWithTheNewestCompleteTurns() throws Exception {
        ChatTurnService.Lease first = completeTurn("看这张图", "回答一", PNG);
        ChatTurnService.Lease second = completeTurn("再看一张", "回答二", PNG);

        ChatContextPlan plan = plan("这两张图有什么共同点", Map.of("use", false));
        List<ContextAudit.Selected> images = selected(plan, "IMAGE");
        assertEquals(2, images.size(), "历史图片随最近 2 个完整轮次重放（§4.3）");
        assertEquals(first.turnId(), images.get(0).sourceTurnId().longValue());
        assertEquals(second.turnId(), images.get(1).sourceTurnId().longValue());
        String payload = serialized(plan);
        assertTrue(payload.contains("data:image/png;base64," + PNG), "重放的是原图字节，不是占位文本");

        completeTurn("第三张", "回答三", PNG);
        ChatContextPlan shifted = plan("再讲讲", Map.of("use", false));
        List<ContextAudit.Selected> afterThird = selected(shifted, "IMAGE");
        assertEquals(2, afterThird.size(), "最多两张，且只跟最近两轮");
        assertNotEquals(first.turnId(), afterThird.get(0).sourceTurnId().longValue());
        assertTrue(hasOmission(shifted, ContextAudit.Omission.ATTACHMENT_POLICY, "IMAGE"),
                "更早的原图仍可按需从历史读取，但本轮没提供就得如实标出来");
    }

    @Test
    void historyWithoutTheMasterPasswordIsNotProvidedAtAll() throws Exception {
        completeTurn("看这张图", "回答一", PNG);
        // 本轮没有主密码：上一轮连正文都解不开，历史就只能整轮缺席，不能塞"（图片不可用）"这类占位
        ChatContextPlan plan = plan("图里是什么", Map.of("use", false), Map.of(), "");
        assertTrue(selected(plan, "IMAGE").isEmpty());
        assertTrue(hasOmission(plan, ContextAudit.Omission.SOURCE_CHANGED, "TURN"),
                "解不开的来源要如实标省略原因：" + plan.audit().omissions());
        assertEquals(0, regionCount(plan, ContextAudit.Region.RECENT_TURNS));
        assertFalse(serialized(plan).contains(PNG));
        assertFalse(serialized(plan).contains("不可用"));
        assertEquals("图里是什么", firstBlockText(plan.messages().get(plan.messages().size() - 1)),
                "缺历史不影响当前问题完整送出");
    }

    @Test
    void stalePlanIsRejectedBeforeItReachesTheUpstream() throws Exception {
        ChatTurnService.Lease lease = beginTurn("先问一句", null, PASSWORD);
        ChatContextPlan plan = stack.contexts().plan(lease, "先问一句", List.of(), Map.of(), Map.of("use", false),
                32000, true);
        assertTrue(stack.contexts().matches(plan), "没有变化时同一份计划仍然有效");
        wiring.turnService().abandon(lease, "test-cleanup");

        completeTurn("中途别人又发了一轮", "回答", null);
        assertFalse(stack.contexts().matches(plan), "历史版本变了就要丢掉旧计划（§4.5）");

        ChatContextService.Snapshot snapshot = stack.contexts().snapshot(sessionId);
        assertNotEquals(plan.historyRevision(), snapshot.historyRevision());
        assertEquals(snapshot.contextGeneration(), plan.contextGeneration());
    }

    @Test
    void contextGenerationChangeNeverReplaysTheOldBody() throws Exception {
        jdbc.update("INSERT INTO notebook(id, title) VALUES (3, '数据结构')");
        long boundary = completeTurn("定作用域", "回答", null).turnId();
        ChatContextPlan before = plan("旧作用域的问题", Map.of("use", false));
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 3L, boundary);

        assertFalse(stack.contexts().matches(before), "切过作用域后旧计划不能复用（§2.2）");
        ChatContextService.Snapshot after = stack.contexts().snapshot(sessionId);
        assertEquals(after.contextGeneration(), before.contextGeneration() + 1, "代次只往前走");

        ChatContextPlan rebuilt = plan("旧作用域的问题", Map.of("use", false));
        assertNotEquals(before.planId(), rebuilt.planId(), "重组一律换新 planId，绝不把旧 metadata 绑到新请求");
        assertEquals(after.contextGeneration(), rebuilt.contextGeneration());
    }

    @Test
    void unusableCheckpointDowngradesWithoutBeingMistakenForAChange() throws Exception {
        completeTurn("更早的一轮", "回答", null);
        // 检查点行存在但没有摘要授权（compressSession 默认关）：采用不了，只能降级为近期历史
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                + " VALUES (?, 7, 1, 1, 0, 'x', 'y', 0, '2026-10-12T00:00:00.000Z')", sessionId);

        ChatTurnService.Lease lease = beginTurn("这一轮的问题", null, PASSWORD);
        ChatContextPlan plan = stack.contexts().plan(lease, "这一轮的问题", List.of(), Map.of(),
                Map.of("use", false), 32000, true);
        assertEquals(7L, plan.summaryRevision().longValue(), "观测到的检查点版本要进计划，复核才有比较依据");
        assertNull(plan.audit().summary(), "没有摘要授权就不能采用，安静降级为无摘要");
        assertTrue(stack.contexts().matches(plan), "不可用的摘要不是并发变化，不能误报 CONTEXT_CHANGED");
        wiring.turnService().abandon(lease, "test-cleanup");

        // 重建一份计划后再单独替换检查点：真正的并发变化仍要发现（§4.5）
        ChatTurnService.Lease again = beginTurn("再问一句", null, PASSWORD);
        ChatContextPlan rebuilt = stack.contexts().plan(again, "再问一句", List.of(), Map.of(),
                Map.of("use", false), 32000, true);
        assertTrue(stack.contexts().matches(rebuilt));
        jdbc.update("UPDATE ai_session_context SET revision = revision + 1 WHERE session_id = ?", sessionId);
        assertFalse(stack.contexts().matches(rebuilt), "检查点被替换 → 旧计划必须丢弃重建");
        wiring.turnService().abandon(again, "test-cleanup");
    }

    // ---------- 夹具 ----------

    /** 走完一轮真实写路径（含附件）并收成 COMPLETED，下一轮才可能把它当历史。 */
    private ChatTurnService.Lease completeTurn(String userText, String reply, String imageBase64) {
        ChatTurnService.Lease lease = beginTurn(userText, imageBase64, PASSWORD);
        wiring.turnService().finalizeTurn(lease, ChatTurnService.UpstreamReply.of(reply, null, "stop"), null);
        return lease;
    }

    private ChatTurnService.Lease beginTurn(String userText, String imageBase64, String password) {
        List<Map<String, Object>> attachments = imageBase64 == null ? List.of()
                : List.of(Map.of("clientId", "c1", "kind", "image", "name", "截图.png", "mimeType", "image/png",
                "dataBase64", imageBase64));
        ChatTurnService.BeginResult begun = wiring.turnService().begin(new ChatTurnService.BeginInput(
                requestId(), 2, sessionId, userText, password, attachments, Map.of()));
        return begun.lease();
    }

    private ChatContextPlan plan(String userText, Map<String, Object> memoryOptions) throws Exception {
        return plan(userText, memoryOptions, Map.of());
    }

    private ChatContextPlan plan(String userText, Map<String, Object> memoryOptions, Map<String, Object> pageContext)
            throws Exception {
        return plan(userText, memoryOptions, pageContext, PASSWORD);
    }

    private ChatContextPlan plan(String userText, Map<String, Object> memoryOptions, Map<String, Object> pageContext,
                                 String password) throws Exception {
        ChatTurnService.Lease lease = beginTurn(userText, null, password);
        try {
            return stack.contexts().plan(lease, userText, List.of(), pageContext, memoryOptions, 32000, true);
        } finally {
            // 一个会话同时只能有一轮在跑：查完计划就把它收成 FAILED，别把后面的用例挡住
            wiring.turnService().abandon(lease, "test-cleanup");
        }
    }

    private void unlockVault() {
        MemoryApiResult created = stack.vault().create("password", PASSWORD, op("vault"));
        assertEquals(201, created.status(), String.valueOf(created.body()));
        assertEquals(200, stack.vault().unlock(PASSWORD, List.of(new MemoryVaultService.SessionUnlock(sessionId,
                PASSWORD))).status(), String.valueOf(stack.vault().status().body()));
    }

    /**
     * 解锁后同步建一份检索快照（§9.1-1 的构建实现，只是不走后台线程）：
     * P5 的读路径只查内存快照，测试等异步线程只会把断言变成轮询。
     */
    private void indexReady() throws SQLException {
        var envelope = wiring.memory().readEnvelope().orElseThrow();
        stack.indexes().buildNow(envelope.vaultId(), envelope.epoch(), envelope.revision());
    }

    /** 打开长期记忆开关并给 MEMORY_USE 授权：注入资格靠这两样，缺一个都不给。 */
    private void enableUseConsent() throws Exception {
        long revision = stack.policy().current().revision();
        assertEquals(200, stack.policy().update(revision, true, false, false, false, null).status());
        MemoryPolicyService.Target target = stack.policy().targetOf(MemoryPolicyService.PURPOSE_USE);
        assertEquals(200, stack.policy().grantConsent(MemoryPolicyService.PURPOSE_USE, target.fingerprint(),
                MemoryPolicyService.POLICY_VERSION).status(), target.toString());
    }

    private long pageIn(long notebookId) {
        long pageId = 100 + notebookId;
        jdbc.update("INSERT INTO note_page(id, notebook_id, title, content) VALUES (?, ?, '页面', '{}')",
                pageId, notebookId);
        return pageId;
    }

    private String requestId() {
        return com.drillnotebook.app.model.UuidV7.generate(System.currentTimeMillis());
    }

    private String op(String label) {
        return "op-" + label + '-' + (++sequence);
    }

    private static List<ContextAudit.Selected> selected(ChatContextPlan plan, String kind) {
        return plan.audit().selected().stream().filter(row -> kind.equals(row.kind())).toList();
    }

    private static int regionCount(ChatContextPlan plan, ContextAudit.Region region) {
        for (ContextAudit.RegionUsage row : plan.audit().regions()) {
            if (row.region() == region) return row.itemCount();
        }
        return 0;
    }

    private static boolean hasOmission(ChatContextPlan plan, ContextAudit.Omission reason, String kind) {
        for (ContextAudit.OmissionGroup group : plan.audit().omissions()) {
            if (group.reason() == reason && kind.equals(group.kind())) return true;
        }
        return false;
    }

    private static String serialized(ChatContextPlan plan) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> message : plan.messages()) builder.append(message);
        return builder.toString();
    }

    private static String firstBlockText(Map<String, Object> message) {
        Object content = message.get("content");
        if (content instanceof String text) return text;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
        return String.valueOf(blocks.get(0).get("text"));
    }
}
