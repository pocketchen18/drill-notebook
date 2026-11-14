package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.RequestContextAudit;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.UsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 全局保留上下文与单请求、累计账本独立；只在隔离数据库中验证。 */
class SessionContextOccupancyTest {
    private Path root;
    private DataSource source;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private SessionContextOccupancyService occupancy;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sessionId;
    private int sequence;
    private static final long PREFIX = 100;
    private record Turn(long id, String requestId, long user, long assistant) { }

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("context-occupancy-");
        source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        occupancy = new SessionContextOccupancyService(source, wiring.cipher(), null, wiring.turnService(), mapper);
        sessionId = wiring.sessions().create("全局上下文", null);
    }

    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }

    private String counts(long extra) throws Exception {
        return mapper.writeValueAsString(Map.of("tokenEstimatorVersion", TokenEstimator.VERSION, "contextExtraTokens", extra));
    }

    private Turn turn(long session, long generation, String user, String answer) throws Exception {
        String request = UuidV7.generate(System.currentTimeMillis() + ++sequence);
        String now = Instant.parse("2026-11-15T12:00:00Z").plusSeconds(sequence).toString();
        jdbc.update("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,
                    context_generation, source_scope_type, source_scope_id, source_timezone, runtime_generation,
                    started_at, finished_at, persistence_status, result_meta_crypto)
                VALUES (?, ?, 'COMPLETED', 'fixture', '{}', ?, 'GLOBAL', 0, 'Asia/Singapore', 'test', ?, ?, 'saved', ?)
                """, request, session, generation, now, now, counts(0));
        long id = jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE request_id = ?", Long.class, request);
        long userId = message(session, id, "user", user);
        long assistantId = message(session, id, "assistant", answer);
        jdbc.update("UPDATE ai_chat_turn SET user_message_id = ?, assistant_message_id = ? WHERE id = ?", userId, assistantId, id);
        return new Turn(id, request, userId, assistantId);
    }

    private long message(long session, Long turn, String role, String text) {
        jdbc.update("INSERT INTO ai_chat_message(session_id, turn_id, role, turn_role, content) VALUES (?, ?, ?, ?, ?)",
                session, turn, role, role.toUpperCase(java.util.Locale.ROOT), text);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_chat_message", Long.class);
    }

    private Map<String, Object> read() { return occupancy.read(sessionId, PREFIX); }
    private static long tokens(Map<?, ?> value) { return ((Number) value.get("tokens")).longValue(); }
    private static Map<String, Long> categories(Map<String, Object> value) {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (Object raw : (List<?>) value.get("categories")) {
            var category = (Map<?, ?>) raw;
            result.put((String) category.get("kind"), ((Number) category.get("tokens")).longValue());
        }
        assertEquals(tokens(value), result.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    @Test void currentCategoriesComeFromTheSameRetainedContextRatherThanHistoricalRequestOrBilling() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "efgh");
        var extra = Map.of(RequestContextAudit.Kind.TOOL_CALLS, 20L, RequestContextAudit.Kind.TOOL_RESULTS, 110L,
                RequestContextAudit.Kind.IMAGE, 4096L, RequestContextAudit.Kind.OTHER, 14L);
        var result = wiring.cipher().withContextExtraTokens(wiring.cipher().encrypt("protected result", "test-password"), 4240L, extra);
        jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?", result.meta(), current.id());
        wiring.sessions().recordTurnContext(current.requestId(), 100000, 128000, 90000, 1000);
        var historical = new RequestContextAudit.Category(RequestContextAudit.Kind.TOOL_RESULTS, 9999, 1,
                List.of(new RequestContextAudit.Item("result:1", "search_web", 9999)));
        context(current, new RequestContextAudit(1, TokenEstimator.VERSION, "fixture", "CHAT", 9999, 128000,
                List.of(historical), false));
        var prefix = new ContextTokenBreakdown(Map.of(RequestContextAudit.Kind.SYSTEM, 60L,
                RequestContextAudit.Kind.TOOL_DEFINITIONS, 30L, RequestContextAudit.Kind.OTHER, 10L));
        var counted = occupancy.read(sessionId, prefix);
        assertEquals(4358L, tokens(counted));
        assertEquals(Map.of("SYSTEM", 60L, "TOOL_DEFINITIONS", 30L, "USER", 1L, "ASSISTANT", 1L,
                "TOOL_CALLS", 20L, "TOOL_RESULTS", 110L, "IMAGE", 4096L, "OTHER", 40L), categories(counted));
        assertEquals(true, counted.get("complete"));
        assertEquals(counted, occupancy.read(sessionId, prefix), "只读刷新不会多累计一份工具结果");
    }

    @Test void liveAndStoredCategorySnapshotsRemainEqualWhenOutputAndToolEvidenceTransfer() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "旧回答".repeat(1000));
        jdbc.update("UPDATE ai_chat_turn SET status = 'RUNNING', finished_at = NULL WHERE id = ?", current.id());
        var handle = wiring.handles().acquire(current.requestId(), sessionId).orElseThrow();
        var extra = new ContextTokenBreakdown(Map.of(RequestContextAudit.Kind.TOOL_RESULTS, 2000L));
        handle.contextTokens(500, extra);
        handle.contextOutputTokens(700);
        var live = read();
        assertEquals(2817L, tokens(live));
        assertEquals(700L, categories(live).get("ASSISTANT"));
        assertEquals(2000L, categories(live).get("TOOL_RESULTS"));
        var result = wiring.cipher().withContextExtraTokens(wiring.cipher().encrypt("result", ""), extra.tokens(), extra.categories());
        jdbc.update("UPDATE ai_chat_message SET content = ? WHERE id = ?", "abcd".repeat(700), current.assistant());
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED', finished_at = ?, result_meta_crypto = ? WHERE id = ?",
                "2026-11-15T14:00:00Z", result.meta(), current.id());
        assertEquals(categories(live), categories(read()), "存储正文替代活跃输出，分类和总量不重复计算");
        wiring.handles().release(current.requestId(), handle);
        assertEquals(categories(live), categories(read()));
    }

    @Test void invalidOrAbsentOldCategoryMetadataKeepsTheKnownTotalAsOtherContent() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "efgh");
        for (Map<String, Object> parts : List.of(Map.<String, Object>of(), Map.<String, Object>of("TOOL_RESULTS", 99),
                Map.<String, Object>of("TOOL_RESULTS", -100), Map.<String, Object>of("TOOL_RESULTS", "100"),
                Map.<String, Object>of("FUTURE_KIND", 100))) {
            String meta = mapper.writeValueAsString(Map.of("tokenEstimatorVersion", TokenEstimator.VERSION,
                    "contextExtraTokens", 100L, "contextExtraCategories", parts));
            jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?", meta, current.id());
            var counted = read();
            assertEquals(218L, tokens(counted));
            assertEquals(116L, categories(counted).get("OTHER"));
            assertFalse(categories(counted).containsKey("TOOL_RESULTS"));
            assertEquals(true, counted.get("complete"), "已知总量不因旧分类缺失变成未知占用");
        }
    }

    @Test void retainedQuestionAndAnswerCountOnceEvenWhenLatestInputGrowsOrShrinks() throws Exception {
        Turn first = turn(sessionId, 1, "abcd", "efgh");
        Turn second = turn(sessionId, 1, "ijkl", "mnop");
        var storageBefore = wiring.ledger().read();
        wiring.sessions().recordTurnContext(first.requestId(), 90000, 128000, 70000, 1000);
        wiring.sessions().recordTurnContext(second.requestId(), 120000, 128000, 100000, 1000);
        assertEquals(136L, tokens(read()), "每条正文 1 Token，四条各有 8 Token 封装，固定前缀只算一次");
        wiring.sessions().recordTurnContext(second.requestId(), 50, 128000, 20, 1);
        assertEquals(136L, tokens(read()), "最近请求被裁小不会把会话全局内容一起裁小");
        assertEquals(true, read().get("complete"));
        assertEquals(storageBefore, wiring.ledger().read(), "统计不写容量账本或触发维护");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt", Integer.class));
    }

    @Test void allSixHundredMessagesCountBeyondHistoryScanAndModelWindowLimits() throws Exception {
        String text = "abcd".repeat(256);
        for (int round = 0; round < 300; round++) turn(sessionId, 1, text, text);
        assertEquals(600, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message", Integer.class));
        assertEquals(158500L, tokens(read()), "既不限制 500 条 / 32 轮，也不封顶到 128k 窗口");
        assertEquals(76800L, categories(read()).get("USER"));
        assertEquals(76800L, categories(read()).get("ASSISTANT"));
        var restarted = new SessionContextOccupancyService(Schema9Fixture.dataSource(root), wiring.cipher(), null,
                wiring.turnService(), mapper);
        assertEquals(read(), restarted.read(sessionId, PREFIX));
    }

    @Test void cleanupAndGenerationChangeRemoveOnlyTheirOwnContextAndLegacyStaysInGenerationOne() throws Exception {
        Turn removed = turn(sessionId, 1, "abcd", "efgh");
        turn(sessionId, 1, "ijkl", "mnop");
        turn(sessionId, 2, "qrst", "uvwx");
        message(sessionId, null, "user", "yz12");
        message(sessionId, null, "assistant", "3456");
        long other = wiring.sessions().create("其它会话", null);
        turn(other, 1, "abcd".repeat(2000), "efgh".repeat(2000));
        assertEquals(154L, tokens(read()));
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = ? WHERE id = ?", "2026-11-15T13:00:00Z", removed.id());
        assertEquals(136L, tokens(read()));
        wiring.metadata().resetContextGeneration(sessionId);
        assertEquals(118L, tokens(read()));
        assertEquals(2L, read().get("contextGeneration"));
    }

    @Test void liveOutputReplacesTheOldAssistantAndTransfersToStoredTextWithoutDoubleCounting() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "旧回答".repeat(1000));
        jdbc.update("UPDATE ai_chat_turn SET status = 'RUNNING', finished_at = NULL WHERE id = ?", current.id());
        var handle = wiring.handles().acquire(current.requestId(), sessionId).orElseThrow();
        handle.contextTokens(500, 2000);
        assertEquals(2617L, tokens(read()));
        assertEquals(true, read().get("active"));
        handle.contextOutputTokens(700);
        assertEquals(2817L, tokens(read()));
        jdbc.update("UPDATE ai_chat_message SET content = ? WHERE id = ?", "abcd".repeat(700), current.assistant());
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED', finished_at = ?, result_meta_crypto = ? WHERE id = ?",
                "2026-11-15T14:00:00Z", counts(2000), current.id());
        assertEquals(2817L, tokens(read()), "终态正文与尚在注册表的流式计数不能相加");
        wiring.handles().release(current.requestId(), handle);
        assertEquals(2817L, tokens(read()));
    }

    @Test void anOlderTurnRetriedInPlaceSuppliesTheCurrentExtrasAndInspectorTarget() throws Exception {
        Turn first = turn(sessionId, 1, "abcd", "efgh");
        Turn later = turn(sessionId, 1, "ijkl", "mnop");
        jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?", counts(300), later.id());
        assertEquals(436L, tokens(read()));
        jdbc.update("UPDATE ai_chat_turn SET status = 'RUNNING', finished_at = NULL WHERE id = ?", first.id());
        var handle = wiring.handles().acquire(first.requestId(), sessionId).orElseThrow();
        handle.contextTokens(1, 200);
        assertEquals(first.requestId(), read().get("requestId"));
        assertEquals(336L, tokens(read()));
        jdbc.update("UPDATE ai_chat_turn SET status = 'COMPLETED', finished_at = ?, result_meta_crypto = ? WHERE id = ?",
                "2026-11-16T12:00:00Z", counts(200), first.id());
        wiring.handles().release(first.requestId(), handle);
        assertEquals(first.requestId(), read().get("requestId"));
        assertEquals(336L, tokens(read()), "保留原始轮次顺序，但最新完成的重试提供当前工作集");
    }

    @Test void numericMessageMetadataWorksWhenLockedAndGlobalAdditionUsesLongs() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "efgh");
        var encrypted = wiring.cipher().encrypt("abcd汉字", "test-password");
        jdbc.update("UPDATE ai_chat_message SET content = '', content_cipher = ?, content_meta = ? WHERE id = ?",
                encrypted.cipher(), encrypted.meta(), current.user());
        assertEquals(121L, tokens(read()), "锁定状态可读取长度数值，不需要读取正文");
        String largest = mapper.writeValueAsString(Map.of("tokenEstimatorVersion", TokenEstimator.VERSION,
                "textTokens", Integer.MAX_VALUE));
        jdbc.update("UPDATE ai_chat_message SET content = '', content_cipher = 'fixture', content_meta = ? WHERE turn_id = ?",
                largest, current.id());
        assertEquals(4294967410L, tokens(read()), "加封装开销前必须转 long");
        jdbc.update("UPDATE ai_chat_message SET content_meta = '{}' WHERE id = ?", current.user());
        assertEquals(false, read().get("complete"));
        assertEquals(2147483755L, tokens(read()), "损坏旧内容只保留已知下界");
    }

    private void pending(Turn parent, String state, String text, boolean numeric) throws Exception {
        String request = UuidV7.generate(System.currentTimeMillis() + ++sequence);
        var input = text == null ? Map.of() : Map.of("userText", text);
        var payload = wiring.cipher().encrypt(mapper.writeValueAsString(Map.of("body", Map.of("input", input))), "");
        if (numeric) payload = wiring.cipher().withSupplementTextTokens(payload, new TokenEstimator(mapper).estimateText(text));
        jdbc.update("INSERT INTO ai_session_dispatch(request_id, session_id, fingerprint, state) VALUES (?, ?, 'fixture', 'QUEUED')",
                request, sessionId);
        jdbc.update("""
                INSERT INTO ai_chat_pending(request_id, session_id, state, parent_request_id, payload_cipher, payload_meta, payload_bytes)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, request, sessionId, state, parent.requestId(), payload.cipher(), payload.meta());
    }

    @Test void onlyReceivedSupplementsBelongToTheGlobalContextAndMissingTextRemainsUnknown() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "efgh");
        pending(current, "RECEIVED", "abcdefgh", true);
        pending(current, "PENDING", "不能提前算入".repeat(200), true);
        pending(current, "SENDING", "尚未确认".repeat(200), true);
        pending(current, "CONSUMED", "已移除".repeat(200), true);
        assertEquals(128L, tokens(read()));
        assertEquals(true, read().get("complete"));
        pending(current, "RECEIVED", null, false);
        assertEquals(128L, tokens(read()));
        assertEquals(false, read().get("complete"), "缺字段不是空提问，更不能当已知 0");
    }

    private void context(Turn turn, RequestContextAudit audit) throws Exception {
        String attempt = UuidV7.generate(System.currentTimeMillis() + ++sequence);
        String now = Instant.parse("2026-11-15T12:00:00Z").plusSeconds(sequence).toString();
        wiring.usage().reserve(new UsageRepository.Attempt(attempt, turn.requestId(), "CHAT", "fixture",
                "2026-11-15", "Asia/Singapore", 0, now, null));
        wiring.usage().markSent(attempt);
        assertTrue(wiring.usage().recordContextBreakdown(attempt, mapper.writeValueAsString(audit)));
    }

    @Test void legacyExtrasUseOneLatestMainRequestAndSkipNativeWebSubrequests() throws Exception {
        Turn current = turn(sessionId, 1, "abcd", "efgh");
        jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = '{}' WHERE id = ?", current.id());
        var category = new RequestContextAudit.Category(RequestContextAudit.Kind.TOOL_RESULTS, 110, 1,
                List.of(new RequestContextAudit.Item("result:1", "search_web", 110)));
        context(current, new RequestContextAudit(1, TokenEstimator.VERSION, "fixture", "CHAT", 110, 128000,
                List.of(category), false));
        context(current, new RequestContextAudit(1, TokenEstimator.VERSION, "fixture", "NATIVE_WEB", 110, 128000,
                List.of(category), false));
        assertEquals(228L, tokens(read()), "同一份工具结果不能随请求次数相加，也不能被原生子请求顶替");
        assertEquals(false, read().get("complete"));
        assertEquals(228L, tokens(read()), "只读刷新幂等");
        jdbc.update("UPDATE ai_chat_turn SET result_meta_crypto = ? WHERE id = ?", counts(1111), current.id());
        assertEquals(1229L, tokens(read()));
        assertEquals(true, read().get("complete"));
    }
}
