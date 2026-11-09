package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.PortablePathResolver;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class AiSessionTitleServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AiService ai;
    private AiSessionTitleService titles;
    private ChatLegacyWiring.Wiring wiring;
    private JdbcTemplate jdbc;
    private SQLiteDataSource source;
    private java.nio.file.Path root;
    private String previousRoot;
    private final AtomicInteger titleRequests = new AtomicInteger();
    private volatile String titleOutput = "标题：“积分换元法与例题解析”";
    private volatile String chatFinish = "stop";
    private volatile CountDownLatch titleEntered = new CountDownLatch(1);
    private volatile CountDownLatch titleRelease = new CountDownLatch(0);
    private volatile String seenPrompt;

    @BeforeEach void setup() throws Exception {
        root = Files.createTempDirectory("session-navigation-test-");
        previousRoot = System.getProperty("app.root");
        System.setProperty("app.root", root.toString());
        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(source, new PortablePathResolver()).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        var configs = new AiConfigRepository(jdbc);
        var vault = mock(MemoryVaultService.class);
        when(vault.sessionPassword(anyLong())).thenReturn(Optional.empty());
        titles = new AiSessionTitleService(wiring.sessions(), configs, wiring.cipher(), new ApiKeyEncryptor(),
                mapper, vault, wiring.usage(), wiring.handles());
        ai = new AiService(configs, wiring.sessions(), new ApiKeyEncryptor(), mapper, null, wiring.turnService(), wiring.cipher(), null);
        ai.setSessionTitles(titles);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean isTitle = body.contains("生成一个会话标题");
            String output = "先选择换元，再计算新积分的上下限。";
            String finish = chatFinish;
            if (isTitle) {
                titleRequests.incrementAndGet(); seenPrompt = body; titleEntered.countDown();
                try { titleRelease.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                output = titleOutput; finish = "stop";
            }
            byte[] response = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message",
                    Map.of("role", "assistant", "content", output), "finish_reason", finish)),
                    "usage", Map.of("prompt_tokens", 21, "completion_tokens", 8)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model", "fixture-model", "apiKey", "fixture-key"));
    }

    @AfterEach void close() throws Exception {
        titleRelease.countDown();
        titles.close();
        // 后台结算退出后再停测试上游，避免把夹具关闭误报成业务失败。
        for (int i = 0; i < 100 && wiring.handles().size() > 0; i++) Thread.sleep(20);
        server.stop(0);
        if (previousRoot == null) System.clearProperty("app.root");
        else System.setProperty("app.root", previousRoot);
    }

    private long create() { return ((Number) ai.createSession(Map.of()).get("id")).longValue(); }
    private void chat(long id, String text) {
        ai.chat(Map.of("sessionId", id, "messages", List.of(Map.of("role", "user", "content", text))));
    }
    private Map<String, Object> session(long id) { return wiring.sessions().find(id); }
    private void awaitStatus(long id, String state) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (state.equals(session(id).get("titleStatus")) && wiring.handles().size() == 0) return;
            Thread.sleep(20);
        }
        fail("未进入 " + state + ": " + session(id));
    }

    @Test void completesChatBeforeTitleAndOnlyUsesFirstSuccessfulPair() throws Exception {
        long id = create(); titleRelease = new CountDownLatch(1);
        chat(id, "怎样学习积分换元法？");
        assertTrue(titleEntered.await(3, TimeUnit.SECONDS));
        assertEquals("generating", session(id).get("titleStatus"));
        assertEquals(2, ai.sessionMessages(id, "").size());
        Object activityTime = session(id).get("updatedAt");
        titleRelease.countDown(); awaitStatus(id, "ready");
        assertEquals("积分换元法与例题解析", session(id).get("title"));
        assertEquals(activityTime, session(id).get("updatedAt"));
        assertTrue(seenPrompt.contains("怎样学习积分换元法"));
        assertTrue(seenPrompt.contains("新积分的上下限"));
        assertFalse(seenPrompt.contains("\"tools\""));
        chat(id, "第二问不要出现在标题素材中");
        assertEquals(1, titleRequests.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE operation_id LIKE 'session-title:%' AND state = 'SETTLED' AND input_tokens = 21", Integer.class));
    }

    @Test void lateTitleCannotOverwriteManualNameOrChangeLinkOrOrdering() throws Exception {
        long id = create(); titleRelease = new CountDownLatch(1);
        Object link = session(id).get("sessionLink");
        chat(id, "积分问题"); assertTrue(titleEntered.await(3, TimeUnit.SECONDS));
        Object updated = session(id).get("updatedAt");
        long other = create();
        ai.updateSession(id, Map.of("title", "  我的很长的手动标题".repeat(8).trim()));
        String manual = String.valueOf(session(id).get("title"));
        assertEquals("TITLE_BUSY", assertThrows(ChatTurnRejectException.class, () -> titles.generate(id,
                Map.of("expectedRevision", session(id).get("titleRevision"), "overwriteManual", true))).code());
        titleRelease.countDown(); awaitStatus(id, "idle");
        assertEquals(manual, session(id).get("title"));
        assertEquals("manual", session(id).get("titleSource"));
        assertEquals(link, session(id).get("sessionLink"));
        assertEquals(updated, session(id).get("updatedAt"));
        assertEquals(other, ((Number) ai.listSessions(false).get(0).get("id")).longValue());
        chat(id, "继续"); assertEquals(1, titleRequests.get());
    }

    @Test void failureKeepsTemporaryTitleAndDoesNotRetryUntilRequested() throws Exception {
        long id = create(); titleOutput = "标题一\n这是一段解释";
        chat(id, "第一个问题"); awaitStatus(id, "failed");
        assertEquals("第一个问题", session(id).get("title"));
        chat(id, "第二个问题"); assertEquals(1, titleRequests.get());
        titleOutput = "恢复的标题";
        titles.generate(id, Map.of("expectedRevision", session(id).get("titleRevision")));
        awaitStatus(id, "ready");
        assertEquals("恢复的标题", session(id).get("title"));
        assertEquals(2, titleRequests.get());
        assertFalse(seenPrompt.contains("第二个问题"));
    }

    @Test void incompleteReplyIsNotEligibleButNextCompleteReplyCanName() throws Exception {
        long id = create(); chatFinish = "length";
        assertThrows(IllegalArgumentException.class, () -> chat(id, "未完成的首问"));
        assertFalse((Boolean) session(id).get("canGenerateTitle"));
        assertEquals(0, titleRequests.get());
        chatFinish = "stop"; chat(id, "首个成功问题"); awaitStatus(id, "ready");
        assertFalse(seenPrompt.contains("未完成的首问"));
        assertTrue(seenPrompt.contains("首个成功问题"));
    }

    @Test void manualGenerateRequiresConfirmationAndFreshRevision() throws Exception {
        long id = create(); ai.updateSession(id, Map.of("title", "人工名")); chat(id, "问题");
        long version = ((Number) session(id).get("titleRevision")).longValue();
        assertEquals("TITLE_CONFIRM_REQUIRED", assertThrows(ChatTurnRejectException.class,
                () -> titles.generate(id, Map.of("expectedRevision", version))).code());
        ai.updateSession(id, Map.of("title", "更晚的人工名"));
        assertEquals("TITLE_CHANGED", assertThrows(ChatTurnRejectException.class,
                () -> titles.generate(id, Map.of("expectedRevision", version, "overwriteManual", true))).code());
        titleRelease = new CountDownLatch(1);
        titles.generate(id, Map.of("expectedRevision", session(id).get("titleRevision"), "overwriteManual", true));
        assertTrue(titleEntered.await(3, TimeUnit.SECONDS));
        assertEquals("TITLE_BUSY", assertThrows(ChatTurnRejectException.class, () -> titles.generate(id,
                Map.of("expectedRevision", session(id).get("titleRevision"), "overwriteManual", true))).code());
        titleRelease.countDown(); awaitStatus(id, "ready");
    }

    @Test void deletingTargetWhileGeneratingDoesNotRecreateIt() throws Exception {
        long id = create(); create(); titleRelease = new CountDownLatch(1);
        chat(id, "问题"); assertTrue(titleEntered.await(3, TimeUnit.SECONDS));
        ai.deleteSession(id); titleRelease.countDown();
        for (int i = 0; i < 200 && wiring.handles().size() > 0; i++) Thread.sleep(20);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE id = ?", Integer.class, id));
        assertEquals(0, wiring.handles().size());
    }

    @Test void restartKeepsReferenceAndStopsStaleGenerationWithoutRetry() throws Exception {
        long id = create(); Object link = session(id).get("sessionLink");
        jdbc.update("UPDATE ai_chat_session SET title_status = 'generating' WHERE id = ?", id);
        new DatabaseInitializer(source, new PortablePathResolver()).initialize();
        assertEquals("failed", session(id).get("titleStatus"));
        assertEquals(link, session(id).get("sessionLink"));
        chat(id, "重启后继续"); assertEquals(0, titleRequests.get());
    }

    @Test void upgradePreservesExistingTitlesAndDoesNotBatchGenerate() throws Exception {
        long id = create(); ai.updateSession(id, Map.of("title", "旧版标题")); chat(id, "旧问题");
        jdbc.execute("DROP INDEX idx_ai_session_ref");
        for (String column : List.of("session_ref", "title_source", "title_status", "title_revision")) {
            jdbc.execute("ALTER TABLE ai_chat_session DROP COLUMN " + column);
        }
        jdbc.update("UPDATE schema_version SET version = 11");
        new DatabaseInitializer(source, new PortablePathResolver()).initialize();
        assertEquals("旧版标题", session(id).get("title"));
        assertEquals("legacy", session(id).get("titleSource"));
        assertTrue(String.valueOf(session(id).get("sessionLink")).matches("drill-notebook://session/[0-9a-f]{32}"));
        chat(id, "旧版继续"); assertEquals(0, titleRequests.get());
    }

    @Test void rejectsEmptySessionAndMalformedTitle() {
        long id = create();
        assertEquals("TITLE_NO_EXCHANGE", assertThrows(ChatTurnRejectException.class,
                () -> titles.generate(id, Map.of("expectedRevision", 0))).code());
        for (String bad : List.of("", "# 标题", "a\nb", "a".repeat(81), "```Title```")) {
            assertThrows(IllegalArgumentException.class, () -> AiSessionTitleService.cleanTitle(bad));
        }
    }
}
