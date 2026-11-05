package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 悬挂响应体的端到端用例（执行方案 §7.2.2、F11，P4 交接清单 A）。
 *
 * <p>P0a 的原型只证明"机制能不能做到"；这里要证明生产路径真的做得到：总期限覆盖到响应体读取，
 * 到期后连接被关闭、读取线程退出，一轮只留一个终态，预留与用量都结算干净。</p>
 */
class HangingStreamTurnTest {
    private static final int DEADLINE_SECONDS = 1;

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private UpstreamFixtureServer server;
    private AiUpstreamClient client;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p4-hang-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        server = new UpstreamFixtureServer();
        client = new AiUpstreamClient(java.net.http.HttpClient.newHttpClient(), new ObjectMapper());
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void teardown() throws Exception {
        server.close();
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void deadlineInAHungBodyClosesTheStreamAndLeavesExactlyOneTerminalState() throws Exception {
        server.hangAfterFirstDelta();
        ChatTurnService.Lease lease = wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "讲长一点", "", List.of(),
                Map.of())).lease();
        String attempt = wiring.turnService().startUsage(lease, "fp-hang", "plan-hang");

        List<String> deltas = new ArrayList<>();
        long startedAt = System.nanoTime();
        AiUpstreamClient.Result result = client.call(
                new AiUpstreamClient.Upstream(server.baseUrl(), "k", "stub", AiUpstreamClient.FORMAT_CHAT_COMPLETIONS,
                        1024, false),
                List.of(Map.of("role", "user", "content", "讲长一点")), true,
                (type, text) -> {
                    deltas.add(text);
                    return true;
                }, DEADLINE_SECONDS, lease.handle());
        long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(server.isHung(), "夹具确实停在\"头已发、正文不结束\"的位置");
        assertTrue(waitedMillis < 8_000, "到期就要退出读取，不能把线程挂到超时上限：" + waitedMillis + "ms");
        assertFalse(result.terminalObserved(), "没有终止帧就不能算正常结束");
        assertTrue(result.downstreamGone(), "期限到期按中断处理");
        assertEquals(List.of("悬挂前的半句"), deltas, "已经吐给前端的增量不撤回");

        wiring.turnService().settleUsage(attempt, result.usage());
        ChatTurnService.FinalResult done = wiring.turnService().finalizeTurn(lease,
                ChatTurnService.UpstreamReply.of(result), null);

        assertEquals(ChatTurnRepository.STATUS_ABORTED, done.status());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_turn WHERE request_id = '" + lease.requestId() + "'"),
                "一轮只留一行");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn WHERE status = 'RUNNING'"),
                "到期后不能把轮次留在 RUNNING");
        assertEquals("SETTLED", jdbc.queryForObject("SELECT state FROM ai_usage_attempt WHERE id = ?", String.class,
                attempt));
        assertEquals(0, jdbc.queryForObject("SELECT reserved_history_bytes FROM ai_chat_storage WHERE id = 1",
                Long.class).longValue(), "预留与终态同事务结算（§7.2.1）");

        // 晚到的"完整正文"绝不能把这一轮二次改写成 COMPLETED：终态只认第一次 claim
        ChatTurnService.FinalResult late = wiring.turnService().finalizeTurn(lease,
                ChatTurnService.UpstreamReply.of("晚到的完整正文", null, "stop"), null);
        assertEquals(ChatTurnRepository.STATUS_ABORTED, late.status(), "只有一个终态（§7.2.2）");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_message WHERE turn_id = (SELECT id FROM ai_chat_turn"
                + " WHERE request_id = '" + lease.requestId() + "') AND role = 'assistant'"),
                "半句正文照样落库，不假装什么都没发生："
                        + jdbc.queryForList("SELECT role, turn_role, turn_id, length(content_cipher) FROM"
                                + " ai_chat_message").toString());

        // 句柄释放后同一会话还能继续开新的一轮：悬挂没有占住会话或注册表
        ChatTurnService.Lease next = wiring.turnService().begin(new ChatTurnService.BeginInput(
                UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "换一句", "", List.of(),
                Map.of())).lease();
        assertTrue(next.turnId() > lease.turnId());
        wiring.turnService().abandon(next, "test-cleanup");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
