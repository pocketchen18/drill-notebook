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
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 实际外发的请求体（执行方案 §9.3、contracts §4.3/§4.5，验收 F13/T14）。
 *
 * <p>计划说"图片随最近两轮重放"只算一半，另一半是<b>网关真的收到了那串字节</b>：
 * 这里用回环服务把请求体原样抓下来核对，并确认引用、省略与估算都出自同一个 plan。</p>
 */
class ChatUpstreamDispatchTest {
    /** 最小合法 PNG：只带签名与 IHDR 宽高，够过有界校验。 */
    private static final String PNG_A = java.util.Base64.getEncoder().encodeToString(new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R',
            0, 0, 0, 2, 0, 0, 0, 2, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private UpstreamFixtureServer server;
    private AiService ai;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p4-dispatch-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        server = new UpstreamFixtureServer();
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), stack.contexts());
        ai.saveConfig(Map.of("provider", "custom", "endpoint", server.baseUrl(), "model", "stub-model",
                "apiKey", "stub-key"));
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void teardown() throws Exception {
        server.close();
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void followUpTurnActuallyResendsThePreviousImage() throws Exception {
        Map<String, Object> first = ai.chat(withImage("看这张图", PNG_A));
        Map<String, Object> second = ai.chat(v2("图里第二个标记是什么"));

        List<String> bodies = server.bodies();
        assertEquals(2, bodies.size(), "两轮各外发一次");
        assertTrue(bodies.get(0).contains(PNG_A), "当前轮的图片必须真的送出去");
        assertTrue(bodies.get(1).contains(PNG_A), "追问时模型必须再次看到上一轮那张图（F13/T14）");
        assertEquals(1, occurrences(bodies.get(1), PNG_A), "重放一份就够，不能同一张图塞两次（§9.4 退出条件）");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode turn2 = mapper.readTree(bodies.get(1)).get("messages");
        assertEquals("system", turn2.get(0).get("role").asText());
        assertFalse(turn2.get(0).toString().contains("图里第二个标记"), "固定 system 里不能混进本轮内容");
        JsonNode last = turn2.get(turn2.size() - 1);
        assertEquals("user", last.get("role").asText());
        assertEquals("图里第二个标记是什么", last.get("content").get(0).get("text").asText(),
                "当前问题永远排在最后一条");

        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED'"), "两轮都收成终态");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"));
    }

    @Test
    void usageAttemptIsBoundToThePlanThatWasActuallySent() throws Exception {
        Map<String, Object> response = ai.chat(withImage("看这张图", PNG_A));
        String requestId = (String) response.get("requestId");

        Map<String, Object> audit = stack.inspector().audit(requestId);
        String planId = String.valueOf(audit.get("planId"));
        assertEquals("sent", audit.get("dispatchState"), "已经发出去的计划不能还标 not_sent");
        assertEquals(planId, jdbc.queryForObject(
                "SELECT context_plan_id FROM ai_usage_attempt WHERE operation_id = ?", String.class, requestId),
                "计量要绑回真正外发的那份计划（交接清单 C）");

        // 引用、省略与估算都只有 plan 一个来源：响应与落库必须给同一份数字
        Map<?, ?> stored = (Map<?, ?>) audit.get("audit");
        Map<?, ?> usage = (Map<?, ?>) response.get("contextUsage");
        assertEquals(stored.get("estimatedInputTokens"), usage.get("estimatedInputTokens"));
        assertEquals(stored.get("inputBudget"), usage.get("inputBudget"));
        assertEquals(11, jdbc.queryForObject("SELECT input_tokens FROM ai_usage_attempt", Integer.class).intValue(),
                "厂商回了数字就按 provider 记，不用估算冒充");
    }

    @Test
    void replayedTurnDoesNotDispatchAgain() throws Exception {
        Map<String, Object> body = v2("同一份请求发两次");
        ai.chat(body);
        Map<String, Object> replay = ai.chat(body);

        assertEquals(true, replay.get("replayed"));
        assertEquals(1, server.bodies().size(), "重放不再外发");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_usage_attempt"), "重放也不再多计量一次");
    }

    @Test
    void changingRetryOfUnderTheSameRequestIdIsAConflictNotAReplay() throws Exception {
        Map<String, Object> first = v2("先发一轮");
        ai.chat(first);
        Map<String, Object> changed = new java.util.LinkedHashMap<>(first);
        changed.put("retryOf", UuidV7.generate(System.currentTimeMillis()));

        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class, () -> ai.chat(changed));
        assertEquals(409, error.status());
        assertEquals("REQUEST_CONFLICT", error.code(), "retryOf 是有效输入：换了就不许悄悄重放原结果（§7.2 第 7 条）");
        assertEquals(1, server.bodies().size(), "冲突请求绝不再次外发");
    }

    @Test
    void configDecryptionFailureAfterBeginStillFreesTheTurn() throws Exception {
        jdbc.update("UPDATE ai_config SET encrypted_key = 'corrupted' WHERE purpose = 'chat'");
        Map<String, Object> body = v2("配置坏了也要收尾");
        String requestId = (String) body.get("requestId");

        assertThrows(IllegalArgumentException.class, () -> ai.chatStream(body, null));

        Map<String, Object> status = wiring.turnService().status(requestId);
        assertEquals("FAILED", status.get("status"), "begin() 之后的失败必须把轮次收成终态（§7.2.1）");
        assertEquals(false, status.get("active"), "句柄要释放，否则孤儿清扫永远跳过它，会话被占死");
        assertEquals(0, jdbc.queryForObject("SELECT reserved_history_bytes FROM ai_chat_storage WHERE id = 1",
                Long.class).longValue(), "预留与终态同事务结算");

        // 修好配置后同一会话还能开新的一轮：悬挂没有占住会话
        ai.saveConfig(Map.of("provider", "custom", "endpoint", server.baseUrl(), "model", "stub-model",
                "apiKey", "stub-key"));
        assertEquals("COMPLETED", ai.chat(v2("修好之后继续")).get("status"));
    }

    /**
     * 模型沉默时用户点"停止"：前端只断 fetch 是不够的——后端要等下一次写 SSE 失败或总期限
     * 到期才察觉，会话会被"该会话已有一轮正在进行"占住（实测等满 180 秒）。v1 流式必须先交出
     * requestId，前端才能调用取消接口：句柄立刻释放、上游连接被关闭、会话随即可用。
     */
    @Test
    void cancellingASilentV1StreamFreesTheSessionWithoutWaitingForTheDeadline() throws Exception {
        server.hangSilently();
        java.util.concurrent.LinkedBlockingQueue<Map<String, Object>> events =
                new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.CompletableFuture<AiService.ChatResult> streaming =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> ai.chatStream(
                        Map.of("sessionId", sessionId,
                                "messages", List.of(Map.of("role", "user", "content", "模型沉默的一问"))),
                        (type, text) -> true, events::offer));

        Map<String, Object> metadata = events.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(metadata, "v1 流式也要先给 metadata，否则前端无从取消");
        String requestId = String.valueOf(metadata.get("requestId"));
        assertEquals(sessionId, ((Number) metadata.get("sessionId")).longValue());
        assertEquals("prepared", metadata.get("status"));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_chat_turn WHERE request_id = ?",
                String.class, requestId));

        Map<String, Object> cancelled = wiring.turnService().cancel(requestId);
        assertEquals(true, cancelled.get("cancelSignalled"), "取消必须命中活跃句柄（关闭上游响应体）");

        // 不许等满总期限：取消后读取立刻失败，读取线程退出、句柄释放
        AiService.ChatResult result = streaming.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("ABORTED", result.status());
        assertEquals("ABORTED", jdbc.queryForObject("SELECT status FROM ai_chat_turn WHERE request_id = ?",
                String.class, requestId));
        assertFalse(wiring.handles().isActive(requestId), "句柄必须已释放，新轮次才不被 SESSION_BUSY 挡住");

        // 用户紧接着再发一条：不再遇到"已有一轮正在进行"
        server.respondNormally();
        assertEquals("COMPLETED", ai.chat(v2("继续")).get("status"));
    }

    @Test
    void storedSummaryPreviewUsesTheCheckpointVersionRecordedWhenSent() throws Exception {
        ai.chat(v2("先正常聊一轮"));
        stack.vault().create("fingerprint", "", "op-dispatch-summary");
        enableSummaryConsent();
        String summaryText = "用户希望先看结论再听推导。";
        insertSummaryCheckpoint(3L, summaryText);
        assertFalse(server.bodies().get(0).contains(summaryText), "检查点建立之前的轮次不该有摘要");

        String requestId = (String) ai.chat(v2("带着摘要聊一句")).get("requestId");
        assertTrue(server.bodies().get(1).contains(summaryText), "已授权的摘要必须真的进外发正文");

        Map<?, ?> audit = (Map<?, ?>) stack.inspector().audit(requestId).get("audit");
        String summaryKey = keyOf(audit, "SUMMARY");
        Map<String, Object> preview = stack.inspector().preview(requestId, List.of(summaryKey)).get(0);
        assertEquals(true, preview.get("available"), "版本没被替换，有效摘要应能预览：" + preview);
        assertEquals(summaryText, preview.get("text"));

        jdbc.update("UPDATE ai_session_context SET revision = 4 WHERE session_id = ?", sessionId);
        Map<String, Object> replaced = stack.inspector().preview(requestId, List.of(summaryKey)).get(0);
        assertEquals(false, replaced.get("available"), "检查点被替换：历史版本的摘要不提供预览（§4.5）");
        assertEquals("CHECKPOINT_REPLACED", replaced.get("reason"));
    }

    @Test
    void memoryPreviewUsesTheSessionsCurrentScopeNotTheOldSnapshot() throws Exception {
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '操作系统')");
        ChatTurnService.Lease boundary = wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "定作用域", "", List.of(),
                Map.of())).lease();
        wiring.turnService().finalizeTurn(boundary, ChatTurnService.UpstreamReply.of("好", null, "stop"), null);
        wiring.metadata().changeScope(sessionId, "NOTEBOOK", 7L, boundary.turnId());

        stack.vault().create("fingerprint", "", "op-scope-vault");
        long revision = stack.policy().current().revision();
        stack.policy().update(revision, true, false, false, false, null);
        MemoryPolicyService.Target use = stack.policy().targetOf(MemoryPolicyService.PURPOSE_USE);
        stack.policy().grantConsent(MemoryPolicyService.PURPOSE_USE, use.fingerprint(),
                MemoryPolicyService.POLICY_VERSION);
        MemoryItemService items = new MemoryItemService(stack.crypto(), wiring.memory(), stack.vault(),
                stack.policy(), new com.drillnotebook.app.repository.MemoryOperationRepository(dataSource,
                wiring.immediate()), new com.drillnotebook.app.repository.MemoryProposalRepository(dataSource,
                wiring.immediate()), new com.drillnotebook.app.repository.MemorySuppressionRepository(dataSource,
                wiring.immediate()), new com.drillnotebook.app.repository.MemoryJobRepository(dataSource,
                wiring.immediate()), wiring.metadata());
        assertEquals(201, items.add(new MemoryItemService.AddInput("GOAL", "NOTEBOOK", 7L, "考试安排", null,
                "本周准备操作系统考试", null, false, "op-scope-add")).status());

        Map<String, Object> body = new java.util.LinkedHashMap<>(v2("操作系统考试怎么复习"));
        body.put("memoryOptions", Map.of("use", true));
        String requestId = (String) ai.chat(body).get("requestId");
        String memoryKey = keyOf((Map<?, ?>) stack.inspector().audit(requestId).get("audit"), "MEMORY");
        assertEquals(true, stack.inspector().preview(requestId, List.of(memoryKey)).get(0).get("available"));

        // 会话切回 GLOBAL 之后：旧计划里的 NOTEBOOK 事实不能再借检查器读出来（§5.2）
        ChatTurnService.Lease after = wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "换回全局", "", List.of(),
                Map.of())).lease();
        wiring.turnService().finalizeTurn(after, ChatTurnService.UpstreamReply.of("好", null, "stop"), null);
        wiring.metadata().changeScope(sessionId, "GLOBAL", null, after.turnId());

        Map<String, Object> drifted = stack.inspector().preview(requestId, List.of(memoryKey)).get(0);
        assertEquals(false, drifted.get("available"), "作用域已经不含这条事实，不能继续预览");
        assertEquals("OUTSIDE_SCOPE", drifted.get("reason"));
    }

    private void enableSummaryConsent() throws Exception {
        long revision = stack.policy().current().revision();
        assertEquals(200, stack.policy().update(revision, null, null, true, null, null).status());
        MemoryPolicyService.Target target = stack.policy().targetOf(MemoryPolicyService.PURPOSE_MAINTENANCE);
        assertEquals(200, stack.policy().grantConsent(MemoryPolicyService.PURPOSE_MAINTENANCE, target.fingerprint(),
                MemoryPolicyService.POLICY_VERSION).status(), target.toString());
    }

    private void insertSummaryCheckpoint(long revision, String summaryText) throws Exception {
        String payload = new ObjectMapper().writeValueAsString(Map.of("schemaVersion", 2,
                "modelSummary", summaryText,
                "serverCoverage", List.of(Map.of("firstTurnId", 1, "lastTurnId", 1))));
        MemoryCryptoService.MemoryKey key = stack.vault().requireKey();
        MemoryCryptoService.EncryptedValue encrypted = stack.crypto().encrypt(key, "ai_session_context",
                String.valueOf(sessionId), revision, payload);
        long epoch = jdbc.queryForObject("SELECT epoch FROM ai_memory_vault WHERE singleton = 1", Long.class);
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                        + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                        + " VALUES (?, ?, 1, 1, ?, ?, ?, 40, '2026-10-12T00:00:00.000Z')",
                sessionId, revision, epoch, encrypted.cipher(), encrypted.cryptoMeta());
    }

    private static String keyOf(Map<?, ?> audit, String kind) {
        for (Object row : (List<?>) audit.get("selected")) {
            Map<?, ?> map = (Map<?, ?>) row;
            if (kind.equals(map.get("kind"))) return String.valueOf(map.get("sourceKey"));
        }
        throw new AssertionError("本轮没有 " + kind + " 来源：" + audit);
    }

    private Map<String, Object> v2(String userText) {
        return Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "input", Map.of("userText", userText));
    }

    private Map<String, Object> withImage(String userText, String base64) {
        return Map.of("protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "input", Map.of("userText", userText, "attachments",
                        List.of(Map.of("clientId", "c1", "kind", "image", "name", "截图.png",
                                "mimeType", "image/png", "dataBase64", base64))));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static int occurrences(String haystack, String needle) {
        int total = 0;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) return total;
            total++;
            from = index + needle.length();
        }
    }
}
