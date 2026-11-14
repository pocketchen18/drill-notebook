package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatUsageRepository;
import com.drillnotebook.app.repository.UsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 会话累计来自请求账本，当前全局窗口占用与最近请求实测分别读取。 */
class SessionContextUsageTest {
    private Path root;
    private DataSource source;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private AiService ai;
    private long sessionId;
    private static final String NOW = "2026-11-15T12:00:00Z";

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("session-usage-");
        source = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(source);
        wiring = ChatLegacyWiring.wire(source, jdbc);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(),
                new ObjectMapper(), null, wiring.turnService(), wiring.cipher(), null);
        ai.setContextOccupancy(new SessionContextOccupancyService(source, wiring.cipher(), null,
                wiring.turnService(), new ObjectMapper()));
        ai.saveConfig(TestModelLimits.config("provider", "mock", "endpoint", "mock://local", "model", "local-demo", "apiKey", "test-only"));
        sessionId = wiring.sessions().create("累计用量", null);
    }

    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }

    private String turn(long session) {
        String id = UuidV7.generate(System.currentTimeMillis());
        jdbc.update("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,
                    context_generation, source_scope_type, source_scope_id, source_timezone, runtime_generation,
                    started_at, finished_at, persistence_status)
                VALUES (?, ?, 'COMPLETED', 'fixture', '{}', 1, 'GLOBAL', 0, 'Asia/Singapore', 'test', ?, ?, 'saved')
                """, id, session, NOW, NOW);
        return id;
    }

    private String observe(String operation, String target, String purpose, UsageRepository.ActualUsage actual) throws Exception {
        String id = UuidV7.generate(System.currentTimeMillis());
        wiring.usage().reserve(new UsageRepository.Attempt(id, operation, purpose, target,
                "2026-11-15", "Asia/Singapore", 0, NOW, null));
        wiring.usage().markSent(id);
        wiring.usage().settle(id, actual, NOW);
        return id;
    }

    private String observe(String operation, int input, int hit, int output) throws Exception {
        return observe(operation, "openai", "CHAT", new UsageRepository.ActualUsage("provider", input, output, hit, 0, true));
    }

    private Map<?, ?> cumulative() { return (Map<?, ?>) ai.sessionContextUsage(sessionId).get("cumulative"); }
    private static long number(Map<?, ?> values, String key) { return ((Number) values.get(key)).longValue(); }

    @Test void sumsMultipleTurnsAndToolRequestsButKeepsLatestContextIndependent() throws Exception {
        long prefix = number((Map<?, ?>) ai.sessionContextUsage(sessionId).get("current"), "tokens");
        String first = turn(sessionId);
        observe(first, 10000, 6000, 300);
        observe(first, 12400, 10000, 400);
        String second = turn(sessionId);
        for (String request : List.of(first, second)) {
            long turnId = jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE request_id = ?", Long.class, request);
            jdbc.update("INSERT INTO ai_chat_message(session_id, turn_id, role, content) VALUES (?, ?, 'user', 'abcd')", sessionId, turnId);
            jdbc.update("INSERT INTO ai_chat_message(session_id, turn_id, role, content) VALUES (?, ?, 'assistant', 'efgh')", sessionId, turnId);
        }
        observe(second, 14400, 12900, 979);
        wiring.sessions().recordTurnContext(second, 14400, 1000000, 12900, 979);
        Map<String, Object> read = ai.sessionContextUsage(sessionId);
        assertEquals(14400, ((Map<?, ?>) read.get("latest")).get("inputTokens"));
        assertEquals(prefix + 36, number((Map<?, ?>) read.get("current"), "tokens"));
        Map<?, ?> usage = (Map<?, ?>) read.get("cumulative");
        assertEquals(3, number(usage, "requests"));
        assertEquals(36800, number(usage, "inputTokens"));
        assertEquals(28900, number(usage, "cacheReadTokens"));
        assertEquals(7900, number(usage, "cacheMissTokens"));
        assertEquals(1679, number(usage, "outputTokens"));
        assertEquals(38479, number(usage, "totalTokens"));
        assertEquals(true, usage.get("totalComplete"));
        assertEquals(true, usage.get("cacheReadComplete"));
        var perTurn = new ChatHistoryRepository(source).turnFacts(sessionId, Set.of(first, second));
        assertEquals(22400, number((Map<?, ?>) perTurn.get(first).get("usage"), "inputTokens"));
    }

    @Test void failedOccupancyReadKeepsCurrentModelWindowAndMeasuredCumulativeUsage() throws Exception {
        String request = turn(sessionId); observe(request, 100, 60, 10);
        var before = ai.sessionContextUsage(sessionId);
        var failed = org.mockito.Mockito.mock(SessionContextOccupancyService.class);
        org.mockito.Mockito.when(failed.read(org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.any(ContextTokenBreakdown.class)))
                .thenThrow(new IllegalStateException("fixture read failure"));
        ai.setContextOccupancy(failed);
        var after = ai.sessionContextUsage(sessionId);
        assertNull(after.get("current"));
        assertEquals(before.get("model"), after.get("model"));
        assertEquals(before.get("contextWindow"), after.get("contextWindow"));
        assertEquals(before.get("cumulative"), after.get("cumulative"));
    }

    @Test void normalizesOpenAiAndAnthropicCachesWithoutChangingChargedLedger() throws Exception {
        String first = turn(sessionId);
        String openai = observe(first, "openai", "CHAT", new UsageRepository.ActualUsage("provider", 2000, 100, 1000, 0, true));
        String second = turn(sessionId);
        String anthropic = observe(second, "anthropic", "CHAT", new UsageRepository.ActualUsage("provider", 100, 200, 1000, 500, false));
        assertEquals(1, jdbc.queryForObject("SELECT cache_read_included_in_input FROM ai_usage_attempt WHERE id = ?", Integer.class, openai));
        assertEquals(0, jdbc.queryForObject("SELECT cache_read_included_in_input FROM ai_usage_attempt WHERE id = ?", Integer.class, anthropic));
        assertEquals(3600, number(cumulative(), "inputTokens"));
        assertEquals(2000, number(cumulative(), "cacheReadTokens"));
        assertEquals(1600, number(cumulative(), "cacheMissTokens"));
        assertEquals(3900, number(cumulative(), "totalTokens"));
        assertEquals(3900L, jdbc.queryForObject("SELECT SUM(charged_tokens) FROM ai_usage_daily", Long.class));
        assertEquals(100, jdbc.queryForObject("SELECT input_tokens FROM ai_usage_attempt WHERE id = ?", Integer.class, anthropic));
        var perTurn = new ChatHistoryRepository(source).turnFacts(sessionId, Set.of(second));
        assertEquals(1600, number((Map<?, ?>) perTurn.get(second).get("usage"), "inputTokens"));
    }

    @Test void oldRetryReceiptsBelongToOriginalTurnAndAreCountedExactlyOnce() throws Exception {
        String old = turn(sessionId);
        observe(old, 100, 40, 10);
        String current = UuidV7.generate(System.currentTimeMillis());
        long turnId = jdbc.queryForObject("SELECT id FROM ai_chat_turn WHERE request_id = ?", Long.class, old);
        jdbc.update("INSERT INTO ai_chat_retry_receipt(request_id, turn_id, replacement_request_id, replaced_at) VALUES(?,?,?,?)",
                old, turnId, current, NOW);
        jdbc.update("UPDATE ai_chat_turn SET request_id = ?, retry_of = ? WHERE id = ?", current, old, turnId);
        observe(current, 200, 140, 20);
        observe(current, 300, 240, 30);
        assertEquals(3, number(cumulative(), "requests"));
        assertEquals(600, number(cumulative(), "inputTokens"));
        var perTurn = (Map<?, ?>) new ChatHistoryRepository(source).turnFacts(sessionId, Set.of(current)).get(current).get("usage");
        assertEquals(cumulative(), perTurn);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn", Integer.class));
    }

    @Test void ignoresOtherSessionsTitlesAndMaintenanceAsWellAsUnsentReservations() throws Exception {
        String first = turn(sessionId);
        observe(first, 20, 10, 2);
        long otherSession = wiring.sessions().create("其他会话", null);
        observe(turn(otherSession), 999, 900, 99);
        observe(first, "openai", "MEMORY_EXTRACT", new UsageRepository.ActualUsage("provider", 888, 88, 0, 0, true));
        observe("session-title:" + first, 777, 0, 77);
        String reserved = UuidV7.generate(System.currentTimeMillis());
        wiring.usage().reserve(new UsageRepository.Attempt(reserved, first, "CHAT", "openai", "2026-11-15", "Asia/Singapore", 0, NOW, null));
        assertEquals(1, number(cumulative(), "requests"));
        wiring.usage().releaseUnsent(reserved, NOW);
        assertEquals(1, number(cumulative(), "requests"));
        assertEquals(22, number(cumulative(), "totalTokens"));
    }

    @Test void survivesMoreThanFiveHundredMessagesContextCleanupAndRepositoryRestart() throws Exception {
        String oldest = turn(sessionId);
        observe(oldest, 1500, 1000, 100);
        for (int round = 0; round < 300; round++) {
            String id = turn(sessionId);
            observe(id, 10, 5, 1);
            jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'user', '历史提问')", sessionId);
            jdbc.update("INSERT INTO ai_chat_message(session_id, role, content) VALUES (?, 'assistant', '历史回答')", sessionId);
        }
        Map<?, ?> before = cumulative();
        assertEquals(301, number(before, "requests"));
        assertEquals(4500, number(before, "inputTokens"));
        jdbc.update("UPDATE ai_chat_turn SET content_deleted_at = ? WHERE request_id = ?", NOW, oldest);
        jdbc.update("UPDATE ai_chat_session SET context_generation = 2 WHERE id = ?", sessionId);
        assertEquals(before, cumulative());
        assertNull(ai.sessionContextUsage(sessionId).get("latest"));
        assertEquals(before, new ChatUsageRepository(Schema9Fixture.dataSource(root)).session(sessionId));
    }

    @Test void distinguishesZeroUnknownAndPartialReportsWithoutInventingHitRates() throws Exception {
        assertNull(cumulative().get("inputTokens"));
        String first = turn(sessionId);
        observe(first, 0, 0, 0);
        assertEquals(0, number(cumulative(), "totalTokens"));
        assertEquals(true, cumulative().get("totalComplete"));
        observe(first, "openai", "CHAT", new UsageRepository.ActualUsage("provider", 50, null, null, null, true));
        assertEquals(50, number(cumulative(), "inputTokens"));
        assertEquals(0, number(cumulative(), "outputTokens"));
        assertEquals(false, cumulative().get("outputComplete"));
        assertEquals(false, cumulative().get("cacheReadComplete"));
        assertEquals(0, number(cumulative(), "cacheMissTokens"));
        String unreported = observe(first, "openai", "CHAT", UsageRepository.ActualUsage.unknown());
        assertNull(jdbc.queryForObject("SELECT cache_read_included_in_input FROM ai_usage_attempt WHERE id = ?", Integer.class, unreported));
        assertEquals(3, number(cumulative(), "requests"));
        assertEquals(false, cumulative().get("inputComplete"));
        assertEquals(false, cumulative().get("totalComplete"));
    }

    @Test void aggregateUsesLongsAndClampsCacheHitsPerRequestRatherThanAcrossSession() throws Exception {
        String first = turn(sessionId);
        observe(first, 100, 150, 0);
        observe(first, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
        observe(first, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
        assertEquals(100, number(cumulative(), "cacheReadTokens"));
        assertEquals(4294967294L, number(cumulative(), "cacheMissTokens"));
        assertEquals(8589934688L, number(cumulative(), "totalTokens"));
    }

    @Test void independentCacheBucketsWithoutFullReportingRemainMeasuredLowerBounds() throws Exception {
        String operation = turn(sessionId);
        String first = observe(operation, "anthropic", "CHAT",
                new UsageRepository.ActualUsage("provider", 100, 20, 600, null, false));
        assertEquals(700, number(cumulative(), "inputTokens"));
        assertEquals(100, number(cumulative(), "cacheMissTokens"));
        assertEquals(false, cumulative().get("inputComplete"));
        assertEquals(false, cumulative().get("cacheMissComplete"));
        assertEquals(true, cumulative().get("cacheReadComplete"));
        var entries = (List<?>) new ChatUsageRepository(source).requestPage(sessionId, operation, null).get("items");
        var detail = (Map<?, ?>) entries.get(0);
        assertEquals(first, detail.get("attemptId"));
        assertEquals(700, number(detail, "inputTokens"));
        assertEquals(false, detail.get("inputComplete"));
        assertEquals(false, detail.get("totalComplete"));
        observe(operation, "anthropic", "CHAT", new UsageRepository.ActualUsage("provider", 0, 0, null, 30, false));
        assertEquals(730, number(cumulative(), "inputTokens"));
        assertEquals(750, number(cumulative(), "totalTokens"));
        assertEquals(false, cumulative().get("cacheReadComplete"));
        assertEquals(false, cumulative().get("totalComplete"));
    }

    @Test void schema20MigrationVerifiesOldBucketSemanticsAgainstActualChargedLedger() throws Exception {
        String first = turn(sessionId);
        observe(first, 2000, 1000, 100);
        observe(first, 3000, 2000, 150);
        String second = turn(sessionId);
        observe(second, "anthropic", "CHAT", new UsageRepository.ActualUsage("provider", 100, 200, 1000, 500, false));
        var expected = cumulative();
        jdbc.execute("ALTER TABLE ai_usage_attempt DROP COLUMN cache_read_included_in_input");
        jdbc.update("UPDATE schema_version SET version = 20");
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        assertEquals(21, jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(expected, cumulative());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_attempt WHERE cache_read_included_in_input IS NULL", Integer.class));
        try (var backups = Files.list(root.resolve("backups"))) {
            assertTrue(backups.anyMatch(path -> path.getFileName().toString().startsWith("pre-schema21-from-v20-")));
        }
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        assertEquals(expected, cumulative(), "再次启动迁移幂等，不重复计量");
    }

    @Test void legacyAmbiguousOrMismatchedLedgerIsLeftUnknownInsteadOfGuessed() throws Exception {
        String first = turn(sessionId);
        observe(first, 2000, 1000, 100);
        jdbc.update("UPDATE ai_usage_daily SET charged_tokens = charged_tokens + 1");
        jdbc.execute("ALTER TABLE ai_usage_attempt DROP COLUMN cache_read_included_in_input");
        jdbc.update("UPDATE schema_version SET version = 20");
        new DatabaseInitializer(source, Schema9Fixture.resolver(root)).initialize();
        assertNull(cumulative().get("inputTokens"));
        assertEquals(100, number(cumulative(), "outputTokens"));
        assertEquals(1000, number(cumulative(), "cacheReadTokens"));
        assertNull(cumulative().get("cacheMissTokens"));
        assertNull(cumulative().get("totalTokens"));
        assertEquals(false, cumulative().get("inputComplete"));
    }

    @Test void requestPagesReachOlderToolCallsWithoutRepeatingRowsOrReadingAnotherTurnsCursor() throws Exception {
        String first = turn(sessionId);
        for (int request = 1; request <= 81; request++) observe(first, request, 0, 1);
        var repository = new ChatUsageRepository(source);
        var latest = repository.requestPage(sessionId, first, null);
        assertEquals(81L, latest.get("totalRequests"));
        assertEquals(true, latest.get("hasMore"));
        var latestRows = (List<?>) latest.get("items");
        assertEquals(50, latestRows.size());
        assertEquals(32L, ((Map<?, ?>) latestRows.get(0)).get("ordinal"));
        assertEquals(81L, ((Map<?, ?>) latestRows.get(49)).get("ordinal"));
        var older = repository.requestPage(sessionId, first, (String) latest.get("nextBeforeAttemptId"));
        assertEquals(false, older.get("hasMore"));
        assertEquals(31, ((List<?>) older.get("items")).size());
        assertEquals(31L, ((Map<?, ?>) ((List<?>) older.get("items")).get(30)).get("ordinal"));
        String foreign = observe(turn(wiring.sessions().create("其它会话", null)), 9999, 0, 1);
        assertEquals("CONTEXT_REQUEST_CURSOR_INVALID", assertThrows(ChatTurnRejectException.class,
                () -> repository.requestPage(sessionId, first, foreign)).code());
        assertEquals(81, number(cumulative(), "requests"), "详情查询不新建请求或计量");
    }

    @Test void compositionSnapshotIsImmutableAndBoundedAndMissingOrInvalidVersionsStayUnavailable() throws Exception {
        String operation = turn(sessionId);
        String attempt = UuidV7.generate(System.currentTimeMillis());
        wiring.usage().reserve(new UsageRepository.Attempt(attempt, operation, "CHAT", "openai", "2026-11-15", "Asia/Singapore", 0, NOW, null));
        var snapshot = new RequestContextAnalyzer(new ObjectMapper()).analyze(Map.of("model", "test", "messages",
                List.of(Map.of("role", "user", "content", "这段输入不能保存在用量摘要里"))), 32000, "CHAT", Set.of());
        String saved = new ObjectMapper().writeValueAsString(snapshot);
        assertFalse(wiring.usage().recordContextBreakdown(attempt, saved));
        wiring.usage().markSent(attempt);
        assertTrue(wiring.usage().recordContextBreakdown(attempt, saved));
        assertFalse(wiring.usage().recordContextBreakdown(attempt, "{}"));
        wiring.usage().settle(attempt, new UsageRepository.ActualUsage("provider", 100, 10, 0, 0, true), NOW);
        assertFalse(wiring.usage().recordContextBreakdown(attempt, "{}"));
        assertThrows(IllegalArgumentException.class, () -> wiring.usage().recordContextBreakdown(attempt, "x".repeat(32769)));
        var page = new ChatUsageRepository(source).requestPage(sessionId, operation, null);
        assertNotNull(((Map<?, ?>) ((List<?>) page.get("items")).get(0)).get("context"));
        assertEquals(saved, jdbc.queryForObject("SELECT context_breakdown FROM ai_usage_attempt WHERE id = ?", String.class, attempt));
        jdbc.update("UPDATE ai_usage_attempt SET context_breakdown = ? WHERE id = ?", saved.replace("\"schemaVersion\":1", "\"schemaVersion\":99"), attempt);
        page = new ChatUsageRepository(source).requestPage(sessionId, operation, null);
        assertNull(((Map<?, ?>) ((List<?>) page.get("items")).get(0)).get("context"));
        assertEquals(110, number(cumulative(), "totalTokens"), "组成明细不可读不影响实际用量累计");
    }
}
