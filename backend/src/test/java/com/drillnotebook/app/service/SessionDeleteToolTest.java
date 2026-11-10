package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.SessionLookupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionDeleteToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private SessionDeleteTool tool;
    private long source;
    private long target;
    private ChatContextPlan plan;
    private AiService.ToolInvocation invocation;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("session-delete-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); wiring = ChatLegacyWiring.wire(ds, jdbc);
        source = wiring.sessions().create("当前会话", "fixture");
        target = wiring.sessions().create("旧讨论", "fixture");
        jdbc.update("INSERT INTO ai_chat_message(session_id,role,content) VALUES(?,'user','旧消息')", target);
        tool = new SessionDeleteTool(new SessionLookupRepository(ds), wiring.sessions(), wiring.turnService(), wiring.handles());
        plan = new ChatContextPlan("plan", source, 1, 0, null, null, null, 0, List.of(), List.of(), List.of(), null);
        var lease = wiring.turnService().begin(new ChatTurnService.BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, source,
                "删除旧讨论", "", List.of(), Map.of(), null)).lease();
        invocation = new AiService.ToolInvocation(lease.requestId(), "c", lease.turnId(), lease.userMessageId(), lease.handle());
    }
    @AfterEach void cleanup() throws Exception { SessionExecution.CURRENT.remove(); TestWorkspace.deleteRecursively(root); }
    private String ref(long id) { return jdbc.queryForObject("SELECT session_ref FROM ai_chat_session WHERE id=?", String.class, id); }
    private Map<String,Object> delete(String selector) throws Exception {
        return tool.execute(plan, json.writeValueAsString(Map.of("session", selector)), false, "删除会话", invocation);
    }
    private int sessions() { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session", Integer.class); }

    @Test void deletesAnExactTargetWithItsMessagesAndNeverReturnsADeadLink() throws Exception {
        String link = "drill-notebook://session/" + ref(target);
        var result = delete(link);
        assertEquals("DELETED", result.get("status"), result.toString());
        assertEquals(List.of(Map.of("title", "旧讨论")), result.get("items"));
        assertFalse(result.toString().contains("drill-notebook://"));
        assertEquals(1, sessions());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE session_id=?", Integer.class, target));
        assertEquals("NOT_FOUND", delete(link).get("status"), "重复调用如实说明目标已不存在");
    }
    @Test void acceptsTheBareReferenceIncludingArchivedSessions() throws Exception {
        jdbc.update("UPDATE ai_chat_session SET archived=1 WHERE id=?", target);
        assertEquals("DELETED", delete(ref(target)).get("status"));
    }
    @Test void forgedOrFinishedSourceTurnsCannotDelete() throws Exception {
        var real = invocation;
        invocation = new AiService.ToolInvocation(real.requestId(), "c", real.turnId(), real.userMessageId(),
                new ActiveTurnRegistry.Handle(real.requestId(), source));
        assertEquals("REJECTED", delete(ref(target)).get("status"));
        invocation = new AiService.ToolInvocation(real.requestId(), "c", real.turnId(), real.userMessageId() + 100, real.handle());
        assertEquals("STALE", delete(ref(target)).get("status"));
        invocation = real;
        jdbc.update("UPDATE ai_chat_turn SET status='COMPLETED' WHERE request_id=?", real.requestId());
        assertEquals("STALE", delete(ref(target)).get("status"));
        assertEquals(2, sessions());
    }
    @Test void refusesTheCurrentSessionAndItsUpstreamSessions() throws Exception {
        assertEquals("CYCLE_BLOCKED", delete(ref(source)).get("status"));
        SessionExecution.CURRENT.set(new SessionExecution(new AiTurnBudget(com.drillnotebook.app.model.AiRuntimeSettings.DEFAULTS), Set.of(source, target)));
        assertEquals("CYCLE_BLOCKED", delete(ref(target)).get("status"));
        assertEquals(2, sessions());
    }
    @Test void aTargetThatIsAnsweringOrHasPendingMessagesIsInUse() throws Exception {
        var lease = wiring.turnService().begin(new ChatTurnService.BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, target,
                "进行中的提问", "", List.of(), Map.of(), null)).lease();
        assertEquals("IN_USE", delete(ref(target)).get("status"));
        wiring.turnService().abandon(lease, "test");
        jdbc.update("INSERT INTO ai_session_dispatch(request_id,session_id,fingerprint,state) VALUES('queued',?,'f','QUEUED')", target);
        assertEquals("IN_USE", delete(ref(target)).get("status"));
        assertEquals(2, sessions());
        jdbc.update("UPDATE ai_session_dispatch SET state='COMPLETED'");
        assertEquals("DELETED", delete(ref(target)).get("status"));
    }
    @Test void titlesNumbersUnknownReferencesAndStaleCallersAreNotGuessed() throws Exception {
        for (String selector : List.of("旧讨论", String.valueOf(target), "drill-notebook://session/xyz", "https://example.com/" + ref(target)))
            assertEquals("INVALID_ARGUMENT", delete(selector).get("status"), selector);
        assertEquals("NOT_FOUND", delete("0".repeat(32)).get("status"));
        for (String raw : List.of("{}", "{\"session\":1}", "{\"session\":\"" + ref(target) + "\",\"force\":true}", "null"))
            assertEquals("INVALID_ARGUMENT", tool.execute(plan, raw, false, "", invocation).get("status"), raw);
        assertEquals("REJECTED", tool.execute(plan, "{\"session\":\"" + ref(target) + "\"}", false, "").get("status"));
        invocation.handle().requestCancel();
        assertEquals("CANCELLED", delete(ref(target)).get("status"));
        wiring.handles().release(invocation.requestId(), invocation.handle());
        invocation = new AiService.ToolInvocation(invocation.requestId(), "c", invocation.turnId(), invocation.userMessageId(),
                wiring.handles().acquire(invocation.requestId(), source).orElseThrow());
        jdbc.update("UPDATE ai_chat_session SET context_generation=2 WHERE id=?", source);
        assertEquals("STALE", delete(ref(target)).get("status"));
        assertEquals(2, sessions());
    }
}
