package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
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
