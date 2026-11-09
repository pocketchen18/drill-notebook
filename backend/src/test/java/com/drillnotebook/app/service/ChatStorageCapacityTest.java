package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 容量面（执行方案 §7.4、contracts §2.7 与 §3 的 {@code /history/storage}）：
 * 读数只报逻辑落库字节、读取不得重建账本；改额度必须带 CAS 基线，而且<b>一条记录都不清</b>——
 * 想腾地方得走清理，不能把额度调到存量之下。
 */
class ChatStorageCapacityTest {
    private static final Map<String, Object> MOCK_CONFIG = Map.of(
            "provider", "mock", "endpoint", "mock://local", "model", "local-demo", "apiKey", "demo-key");
    private static final long DEFAULT_LIMIT = 268435456L;

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p9c6-cap-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        stack = ChatContextWiring.wire(dataSource, jdbc, wiring);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), stack.contexts());
        ai.saveConfig(TestModelLimits.config(MOCK_CONFIG));
        sessionId = com.drillnotebook.app.repository.ChatLegacyWiring.ensureSession(jdbc);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void snapshotReportsLedgerWithoutRebuildingIt() throws Exception {
        Map<String, Object> fresh = stack.storage().snapshot();
        assertEquals(0L, number(fresh, "historyBytes"));
        assertEquals(0L, number(fresh, "reservedHistoryBytes"));
        assertEquals(0L, number(fresh, "usedHistoryBytes"));
        assertEquals(DEFAULT_LIMIT, number(fresh, "historyLimitBytes"), "默认额度来自 schema，快照不许自己编一个");
        assertEquals(false, fresh.get("historyWarning"));
        assertEquals(1L, number(fresh, "revision"));

        chat("这一问要落进容量账本");
        Map<String, Object> after = stack.storage().snapshot();
        long history = number(after, "historyBytes");
        assertTrue(history > 0, "落库字节随消息增长，但它数的不是条数");
        assertEquals(0L, number(after, "reservedHistoryBytes"), "收尾已结算，预留不该留尾巴");
        assertEquals(history, number(after, "usedHistoryBytes"));

        // 预留必须计入 used：80% 预警与 409 判定都按 used+reserved 算（§2.7 尾段）
        jdbc.update("UPDATE ai_chat_storage SET reserved_history_bytes = 2097152 WHERE id = 1");
        assertEquals(history + 2_097_152L, number(stack.storage().snapshot(), "usedHistoryBytes"));

        // 故意让账本与实测漂移：读一次不得把计数拉平，那是对账门禁要报的东西
        jdbc.update("UPDATE ai_chat_storage SET history_bytes = ?, reserved_history_bytes = 0 WHERE id = 1",
                DEFAULT_LIMIT);
        Map<String, Object> full = stack.storage().snapshot();
        assertEquals(DEFAULT_LIMIT, number(full, "historyBytes"), "读取不得重建计数");
        assertEquals(true, full.get("historyWarning"), "到 80% 就该预警");
        assertEquals(Set.of("historyBytes", "reservedHistoryBytes", "usedHistoryBytes", "historyLimitBytes",
                "attachmentBytes", "attachmentLimitBytes", "historyWarning", "attachmentWarning", "revision"),
                full.keySet(), "只给契约 §3 那几项：物理 DB 大小不属于这个口径");
    }

    @Test
    void raisingLimitsTouchesNoRecord() throws Exception {
        chat("先落一轮，账本才有得比");
        Map<String, Object> before = stack.storage().snapshot();
        long revision = number(before, "revision");
        long history = number(before, "historyBytes");
        int messages = count("SELECT COUNT(*) FROM ai_chat_message");
        int turns = count("SELECT COUNT(*) FROM ai_chat_turn");

        Map<String, Object> applied = stack.storage().updateLimits(Map.of(
                "expectedRevision", revision,
                "historyLimitBytes", DEFAULT_LIMIT * 2,
                "attachmentLimitBytes", DEFAULT_LIMIT));

        assertEquals(DEFAULT_LIMIT * 2, number(applied, "historyLimitBytes"), "历史额度按新值生效");
        assertEquals(DEFAULT_LIMIT, number(applied, "attachmentLimitBytes"), "没改的那一档原样留着");
        assertEquals(revision + 1, number(applied, "revision"), "改额度要推进 CAS 基线");
        assertEquals(history, number(stack.storage().snapshot(), "historyBytes"), "改额度不得动任何字节");
        assertEquals(messages, count("SELECT COUNT(*) FROM ai_chat_message"), "改额度不清任何记录");
        assertEquals(turns, count("SELECT COUNT(*) FROM ai_chat_turn"), "改额度不清任何记录");
    }

    @Test
    void staleCasBaselineIsRefusedAndCarriesTheCurrentRevision() throws Exception {
        long revision = number(stack.storage().snapshot(), "revision");
        stack.storage().updateLimits(Map.of("expectedRevision", revision,
                "historyLimitBytes", DEFAULT_LIMIT * 2, "attachmentLimitBytes", DEFAULT_LIMIT));

        ChatTurnRejectException conflict = assertThrows(ChatTurnRejectException.class,
                () -> stack.storage().updateLimits(Map.of("expectedRevision", revision,
                        "historyLimitBytes", DEFAULT_LIMIT * 3, "attachmentLimitBytes", DEFAULT_LIMIT)));
        assertEquals(409, conflict.status());
        assertEquals("HISTORY_LIMIT_REVISION_CONFLICT", conflict.code());
        // 带回最新基线：前端不重开抽屉也能换 CAS 起点（P9a 的 D93 教训）
        assertEquals(revision + 1, number(conflict.details(), "revision"));
    }

    @Test
    void limitsCannotBeTunedBelowWhatIsAlreadyStored() throws Exception {
        chat("这一问的字节就是下限");
        Map<String, Object> before = stack.storage().snapshot();
        long used = number(before, "usedHistoryBytes");
        int messages = count("SELECT COUNT(*) FROM ai_chat_message");

        ChatTurnRejectException below = assertThrows(ChatTurnRejectException.class,
                () -> stack.storage().updateLimits(Map.of(
                        "expectedRevision", number(before, "revision"),
                        "historyLimitBytes", used - 1,
                        "attachmentLimitBytes", DEFAULT_LIMIT)));
        assertEquals(409, below.status());
        assertEquals("HISTORY_LIMIT_BELOW_USED", below.code());
        assertEquals(DEFAULT_LIMIT, number(stack.storage().snapshot(), "historyLimitBytes"), "拒绝就不能改一个字");
        assertEquals(messages, count("SELECT COUNT(*) FROM ai_chat_message"), "拒绝也不能顺手清掉记录");
    }

    @Test
    void nonsenseAndMissingFieldsAreBothFourHundred() throws Exception {
        long revision = number(stack.storage().snapshot(), "revision");

        ChatTurnRejectException nonsense = assertThrows(ChatTurnRejectException.class,
                () -> stack.storage().updateLimits(Map.of("expectedRevision", revision,
                        "historyLimitBytes", 0, "attachmentLimitBytes", DEFAULT_LIMIT)));
        assertEquals(400, nonsense.status());
        assertEquals("HISTORY_LIMIT_INVALID", nonsense.code());

        ChatTurnRejectException missing = assertThrows(ChatTurnRejectException.class,
                () -> stack.storage().updateLimits(Map.of("expectedRevision", revision,
                        "historyLimitBytes", DEFAULT_LIMIT)));
        assertEquals(400, missing.status());
        assertEquals("HISTORY_LIMIT_FIELD_MISSING", missing.code());
        assertEquals("attachmentLimitBytes", missing.details().get("field"));
        // 两次拒绝都不许推进账本
        assertEquals(revision, number(stack.storage().snapshot(), "revision"));
        assertNotEquals(0L, revision);
    }

    // ---------- 夹具 ----------

    private Map<String, Object> chat(String userText) {
        return ai.chat(Map.of("protocolVersion", 2,
                "requestId", UuidV7.generate(System.currentTimeMillis()),
                "sessionId", sessionId,
                "input", Map.of("userText", userText)));
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertTrue(value instanceof Number, key + " 必须是数字，实际：" + value);
        return ((Number) value).longValue();
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
