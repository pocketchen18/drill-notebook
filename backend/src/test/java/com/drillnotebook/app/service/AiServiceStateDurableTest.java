package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.config.ProcessIdentity;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatStorageLedger;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * stateDurable=false 臂（执行方案 §7.2 第 6 条、验收 F08）：finalize 与失败收尾都写不进时，
 * v2 普通响应与流式 StreamFailure 都必须如实回 stateDurable=false，并打开存储降级门闩。
 *
 * <p>这里只有"写收尾失败"这一条路径能证伪谎报，其余用例断言的都是 stateDurable=true，
 * 把 v2 的 stateDurable 直接写死成 true 也能全绿——本类就是那层的门禁。</p>
 */
class AiServiceStateDurableTest {

    /** 只让 finalize/failFinalization 抛错，其余读写照常走真库。 */
    private static final class FailableTurnRepository extends ChatTurnRepository {
        volatile boolean failWrites = false;

        FailableTurnRepository(DataSource dataSource, ImmediateTransaction immediate, ChatStorageLedger ledger,
                               ChatSessionMetadataRepository metadata, MemoryRepository memory) {
            super(dataSource, immediate, ledger, metadata, memory);
        }

        @Override
        public TerminalOutcome finalizeTurn(String requestId, Terminal terminal) throws SQLException {
            if (failWrites) throw new SQLException("probe: 磁盘写失败（finalize）");
            return super.finalizeTurn(requestId, terminal);
        }

        @Override
        public TerminalOutcome failFinalization(String requestId, String finishReason, String finishedAt)
                throws SQLException {
            if (failWrites) throw new SQLException("probe: 磁盘写失败（failFinalization）");
            return super.failFinalization(requestId, finishReason, finishedAt);
        }
    }

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private UpstreamFixtureServer server;
    private FailableTurnRepository failableTurns;
    private ChatTurnService turnService;
    private AiService ai;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p9c-durable-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        failableTurns = new FailableTurnRepository(dataSource, wiring.immediate(), wiring.ledger(),
                wiring.metadata(), wiring.memory());
        turnService = new ChatTurnService(failableTurns, wiring.metadata(), wiring.cipher(), wiring.handles(),
                new ProcessIdentity(), new ObjectMapper(), wiring.usage(), wiring.attachments());
        server = new UpstreamFixtureServer();
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, turnService, wiring.cipher(), stack.contexts());
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", server.baseUrl(), "model", "stub-model",
                "apiKey", "stub-key"));
        sessionId = com.drillnotebook.app.repository.ChatLegacyWiring.ensureSession(jdbc);
    }

    @AfterEach
    void teardown() throws Exception {
        server.close();
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void v2ChatReportsStateDurableFalseWhenNeitherFinalizeNorFailFinalizationWrites() throws Exception {
        failableTurns.failWrites = true;
        String requestId = UuidV7.generate(System.currentTimeMillis());

        Map<String, Object> response = ai.chat(Map.of(
                "protocolVersion", 2, "requestId", requestId, "sessionId", sessionId,
                "input", Map.of("role", "user", "userText", "存储坏了也要如实上报")));

        assertEquals("FAILED", response.get("status"));
        assertEquals("failed", response.get("persistenceStatus"));
        assertEquals(false, response.get("stateDurable"), "失败收尾也没写进就绝不能谎称已持久（F08）");
        assertEquals(1, server.bodies().size(), "上游只被调用一次");
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_chat_turn WHERE request_id = ?",
                String.class, requestId), "收尾没写进，行仍是 RUNNING（stateDurable=false 对应的事实）");
        assertTrue(turnService.storageDegraded(), "写入口必须进入存储降级");

        ChatTurnRejectException rejected = assertThrows(ChatTurnRejectException.class, () -> ai.chat(Map.of(
                "protocolVersion", 2, "requestId", UuidV7.generate(System.currentTimeMillis()), "sessionId", sessionId,
                "input", Map.of("role", "user", "userText", "降级后不许再发"))));
        assertEquals(503, rejected.status(), "降级门闩开启后新请求 503，不再外发");
        assertEquals("STORAGE_UNAVAILABLE", rejected.code());
        assertEquals(1, server.bodies().size(), "被拒的请求绝不外发");
    }

    @Test
    void v2StreamFailureCarriesStateDurableFalse() throws Exception {
        failableTurns.failWrites = true;
        String requestId = UuidV7.generate(System.currentTimeMillis());

        AiService.StreamFailure failure = assertThrows(AiService.StreamFailure.class, () -> ai.chatStream(Map.of(
                "protocolVersion", 2, "requestId", requestId, "sessionId", sessionId,
                "input", Map.of("role", "user", "userText", "流式也要如实上报")), (type, text) -> true));

        assertEquals(requestId, failure.requestId());
        assertEquals("failed", failure.persistenceStatus());
        assertFalse(failure.stateDurable(), "error 帧要带 stateDurable=false（§4.2 末段）");
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_chat_turn WHERE request_id = ?",
                String.class, requestId));
        assertTrue(turnService.storageDegraded());
    }
}
