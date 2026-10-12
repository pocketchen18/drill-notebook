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
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 聊天两条路径共用轮次生命周期（执行方案 §7.1、§7.2）：
 * v1 旧契约照旧返回，v2 带统一结果字段，流式与非流式都只写一份密文、都不再按条数删库。
 */
class AiServiceTurnPathTest {
    private static final Map<String, Object> MOCK_CONFIG = Map.of(
            "provider", "mock",
            "endpoint", "mock://local",
            "model", "local-demo",
            "apiKey", "demo-key");

    /** 只带合法 PNG 签名与 IHDR 宽高的最小图片：够过有界校验，不需要真像素。 */
    private static final String PING_BASE64 = java.util.Base64.getEncoder().encodeToString(new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R',
            0, 0, 0, 2, 0, 0, 0, 2, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private AiService ai;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p3a-ai-path-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher());
        ai.saveConfig(MOCK_CONFIG);
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE ai_chat_session SET memory_learn_enabled = 1 WHERE id = ?", sessionId);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void v1ChatWritesOneTurnOfCiphertextAndKeepsLegacyResponseShape() throws Exception {
        // 首问自动取标题会把用户原话写进 ai_chat_session.title（v0.6 既有行为，是否收进加密面留给 P9/P11 复核），
        // 这里先把标题定下来，让扫库只考察正文与轮次元数据这两条写路径
        jdbc.update("UPDATE ai_chat_session SET title = '我的复习' WHERE id = ?", sessionId);

        Map<String, Object> response = ai.chat(Map.of(
                "sessionId", sessionId,
                "messages", List.of(Map.of("role", "user", "content", "解释垃圾回收的三代"))));

        assertEquals(2, response.size(), "v1 检索关闭时仍只有 reply/sessionId 两个键");
        assertEquals(sessionId, ((Number) response.get("sessionId")).longValue());
        assertTrue(String.valueOf(response.get("reply")).contains("本地演示回复"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED'"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE memory_eligible = 1"),
                "v1 不得自动生成记忆来源（§7.1）");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"
                + " WHERE content_cipher IS NOT NULL AND content = ''"));
        assertFalse(dbContains("解释垃圾回收的三代"), "旧客户端的原话也不能落明文");
        assertEquals(0, ledgerReserved(), "普通路径同样把回复预留结算干净");
    }

    @Test
    void streamChatPersistsThroughTheSameTurnLifecycle() {
        List<String> deltas = new ArrayList<>();
        AiService.ChatResult result = ai.chatStream(Map.of(
                "sessionId", sessionId,
                "messages", List.of(Map.of("role", "user", "content", "流式也要落库"))),
                (type, text) -> {
                    deltas.add(type + ":" + text);
                    return true;
                });

        assertEquals(ChatTurnRepository.STATUS_COMPLETED, result.status());
        assertEquals("saved", result.persistenceStatus());
        assertTrue(result.stateDurable());
        assertFalse(deltas.isEmpty(), "mock 端点也必须逐 delta 回调，不能提前返回绕过记账");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'COMPLETED'"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"), "两条路径各自都只写一轮");
        assertEquals(0, ledgerReserved());
    }

    @Test
    void v2ChatReturnsUnifiedResultAndIsLearnableSource() {
        String requestId = UuidV7.generate(System.currentTimeMillis());
        Map<String, Object> response = ai.chat(Map.of(
                "protocolVersion", 2,
                "requestId", requestId,
                "sessionId", sessionId,
                "input", Map.of("role", "user", "userText", "请继续解释二分查找")));

        assertEquals(2, response.get("protocolVersion"));
        assertEquals(requestId, response.get("requestId"));
        assertEquals(ChatTurnRepository.STATUS_COMPLETED, response.get("status"));
        assertEquals(true, response.get("stateDurable"));
        assertEquals("saved", response.get("persistenceStatus"));
        assertEquals(false, response.get("replayed"));
        assertNotNull(response.get("reply"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id = '" + requestId
                + "' AND memory_eligible = 1"), "v2 且会话开启学习才是可学习来源");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE role = 'user'"),
                "v2 只发本轮输入，不再回传历史");
    }

    @Test
    void v2RejectsLegacyContentFieldAndReplaysSameRequestId() {
        ChatTurnRejectException conflict = assertThrows(ChatTurnRejectException.class, () -> ai.chat(Map.of(
                "protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", "两个都给", "content", "旧字段"))));
        assertEquals(422, conflict.status());
        assertEquals("INPUT_FIELDS_CONFLICT", conflict.code());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"), "协议冲突不该留下残轮");

        Map<String, Object> body = Map.of("protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", "同一个请求发两次"));
        Map<String, Object> first = ai.chat(body);
        Map<String, Object> second = ai.chat(body);

        assertEquals(true, second.get("replayed"), "同 ID 重发只回放");
        assertEquals(first.get("reply"), second.get("reply"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_chat_message"));
    }

    @Test
    void eachUpstreamDispatchMetersExactlyOneChatAttempt() {
        String requestId = UuidV7.generate(System.currentTimeMillis());
        Map<String, Object> body = Map.of("protocolVersion", 2, "requestId", requestId,
                "sessionId", sessionId, "input", Map.of("userText", "计量一次就好"));
        ai.chat(body);
        ai.chat(body);

        assertEquals(1, count("SELECT COUNT(*) FROM ai_usage_attempt WHERE operation_id = '" + requestId + "'"),
                "同 ID 重放不再外发，也就不该多出一次计量");
        assertEquals("SETTLED", jdbc.queryForObject("SELECT state FROM ai_usage_attempt", String.class));
        assertEquals("CHAT", jdbc.queryForObject("SELECT purpose FROM ai_usage_attempt", String.class));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_usage_daily WHERE purpose = 'CHAT' AND attempts = 1"
                + " AND reserved_tokens = 0"), "聊天只计量，不占后台 20 次/30K 预留（§2.6）");

        ai.chatStream(Map.of("protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId, "input", Map.of("userText", "流式同样计量")),
                (type, text) -> true);
        assertEquals(2, count("SELECT COUNT(*) FROM ai_usage_attempt WHERE state = 'SETTLED'"),
                "两条路径共用同一套用量结算");
    }

    @Test
    void v2AttachmentsPersistAsCiphertextThroughTheChatPath() throws Exception {
        Map<String, Object> response = ai.chat(Map.of(
                "protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", "看这张图", "attachments", List.of(Map.of(
                        "clientId", "c1", "kind", "image", "name", "截图.png",
                        "mimeType", "image/png", "dataBase64", PING_BASE64)))));

        assertEquals(ChatTurnRepository.STATUS_COMPLETED, response.get("status"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_attachment"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE memory_eligible = 1"));
        assertTrue(attachmentBytes() > 0, "附件密文进 attachment_bytes");
        assertFalse(dbContains(PING_BASE64), "base64 原串不得落进库文件");
        assertFalse(dbContains("截图.png"), "文件名也只在密文里");
    }

    @Test
    void sameRequestIdAcrossBothPathsCreatesOneSourceTurn() {
        Map<String, Object> body = Map.of("protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", "两条路径发同一个请求"));

        ai.chat(body);
        AiService.ChatResult replayed = ai.chatStream(body, (type, text) -> true);

        assertEquals(ChatTurnRepository.STATUS_COMPLETED, replayed.status());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE memory_eligible = 1"),
                "P3 退出条件：普通与流式共用同一身份，不会重复创建记忆来源（§7.2-7）");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE role = 'assistant'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_usage_attempt"), "重放不再外发，也不再计量第二次");
        assertEquals(0, ledgerReserved());
    }

    @Test
    void v1ClientIsToldAttachmentsAndRetryAreV2Only() {
        ChatTurnRejectException attachments = assertThrows(ChatTurnRejectException.class, () -> ai.chat(Map.of(
                "sessionId", sessionId,
                "attachments", List.of(Map.of("clientId", "c1", "kind", "text", "text", "旧通道附件")),
                "messages", List.of(Map.of("role", "user", "content", "旧客户端带附件")))));
        assertEquals(422, attachments.status());
        assertEquals("ATTACHMENTS_REQUIRE_V2", attachments.code());

        ChatTurnRejectException retry = assertThrows(ChatTurnRejectException.class, () -> ai.chat(Map.of(
                "sessionId", sessionId, "retryOf", UuidV7.generate(System.currentTimeMillis()),
                "messages", List.of(Map.of("role", "user", "content", "旧客户端带 retryOf")))));
        assertEquals(422, retry.status());
        assertEquals("RETRY_OF_REQUIRES_V2", retry.code());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"), "拒绝在开始之前，不留残轮");
    }

    @Test
    void missingModelConfigStopsBeforeStartingTurn() {
        jdbc.update("DELETE FROM ai_config WHERE purpose = 'chat'");

        assertThrows(IllegalArgumentException.class, () -> ai.chat(Map.of(
                "sessionId", sessionId,
                "messages", List.of(Map.of("role", "user", "content", "没有配置也要拒")))));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"), "配置缺失时在开始之前就停住");
        assertEquals(0, ledgerReserved());
    }

    private long ledgerReserved() {
        return jdbc.queryForObject("SELECT reserved_history_bytes FROM ai_chat_storage WHERE id = 1", Long.class);
    }

    private long attachmentBytes() {
        return jdbc.queryForObject("SELECT attachment_bytes FROM ai_chat_storage WHERE id = 1", Long.class);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** 扫库文件与 WAL：写路径一旦降级成明文，这里立刻暴露（§4 不变量：任何写路径都不得把正文写成明文）。 */
    private boolean dbContains(String text) throws Exception {
        StringBuilder raw = new StringBuilder();
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path file = appRoot.resolve("data/study.db" + suffix);
            if (Files.exists(file)) {
                raw.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return raw.toString().contains(
                new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }
}
