package com.drillnotebook.app.model;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolActivityTest {
    @Test void materialCreationKeepsOnlyValidatedLocalSourceAcrossHistoryRestore() throws Exception {
        var created = ToolActivity.started(1, "create_material").finished("""
                {"status":"CREATED","sourceLink":"drill-notebook://material/note/17",
                 "items":[{"title":"PRIVATE","content":"PRIVATE"}]}
                """, 5);
        var meta = new ObjectMapper().writeValueAsString(Map.of("toolActivities", List.of(created)));
        assertFalse(meta.contains("PRIVATE"));
        assertEquals(created, ToolActivity.readMeta(meta, false).get(0));
        assertEquals("drill-notebook://material/note/17", created.sourceLink());
        assertNull(ToolActivity.started(1, "create_material").finished(
                "{\"status\":\"CREATED\",\"sourceLink\":\"file:///C:/private\"}", 1).sourceLink());
    }

    @Test void deletionActivitiesKeepOutcomeAndValidatedSourceButNoTitlesOrDeadSessionLinks() throws Exception {
        var deleted = ToolActivity.started(1, "delete_material").finished("""
                {"status":"DELETED","sourceLink":"drill-notebook://material/note/17",
                 "items":[{"title":"PRIVATE","deleted":{"pages":3}}]}
                """, 5);
        assertEquals("DELETED", deleted.outcome()); assertEquals(1, deleted.count());
        assertEquals("drill-notebook://material/note/17", deleted.sourceLink());
        var removed = ToolActivity.started(2, "remove_wrong_question").finished(
                "{\"status\":\"REMOVED\",\"sourceLink\":\"drill-notebook://material/question/4\",\"items\":[{\"title\":\"PRIVATE\"}]}", 3);
        assertEquals("REMOVED", removed.outcome()); assertEquals(1, removed.count());
        var session = ToolActivity.started(3, "delete_session").finished("{\"status\":\"DELETED\",\"items\":[{\"title\":\"PRIVATE\"}]}", 2);
        assertNull(session.sessionLink()); assertEquals(1, session.count());
        var memory = ToolActivity.started(4, "delete_memory").finished("{\"status\":\"DELETED\",\"memoryId\":\"PRIVATE\",\"items\":[]}", 2);
        assertEquals("delete_memory", memory.toolName()); assertEquals(1, memory.count());
        var meta = new ObjectMapper().writeValueAsString(Map.of("toolActivities", List.of(deleted, removed, session, memory)));
        assertFalse(meta.contains("PRIVATE"));
        assertEquals(List.of(deleted, removed, session, memory), ToolActivity.readMeta(meta, false));
        for (String status : List.of("NOT_EMPTY", "IN_USE", "DISABLED"))
            assertEquals(status, ToolActivity.started(1, "delete_material").finished("{\"status\":\"" + status + "\",\"items\":[]}", 1).outcome());
        assertEquals(0, ToolActivity.started(1, "delete_material").finished("{\"status\":\"NOT_EMPTY\",\"items\":[]}", 1).count());
    }

    @Test void sessionActivitiesKeepCountsButNeverExposeHistoryOrSessionIdentifiers() throws Exception {
        var read = ToolActivity.started(1, "read_session").finished("""
                {"status":"OK","messages":[{"content":"PRIVATE","messageId":71}],
                 "session":{"title":"PRIVATE","sessionLink":"PRIVATE"},"nextCursor":"PRIVATE","incomplete":true}
                """, 10);
        assertEquals("read_session", read.toolName());
        assertEquals(1, read.count());
        assertTrue(read.incomplete());
        assertFalse(new ObjectMapper().writeValueAsString(read).contains("PRIVATE"));
        assertEquals(0, ToolActivity.started(2, "read_session").finished(
                "{\"status\":\"UNAVAILABLE\",\"messages\":[{\"readStatus\":\"NEEDS_PASSWORD\"}]}", 1).count());
        assertEquals("list_sessions", ToolActivity.started(2, "list_sessions").toolName());
    }
    @Test
    void projectsOnlySafeFieldsAndDistinguishesEmptyFromUnavailable() throws Exception {
        ToolActivity start = ToolActivity.started(1, "search_memories");
        ToolActivity done = start.finished("""
                {"status":"OK","availability":"READY","facts":[{"text":"PRIVATE","memoryId":"PRIVATE"}],"debug":"PRIVATE"}
                """, 230);
        assertEquals(1, done.count());
        assertEquals("FINISHED", done.state());
        assertEquals(230L, done.elapsedMs());
        assertFalse(new ObjectMapper().writeValueAsString(done).contains("PRIVATE"));
        assertEquals("NO_MATCH", start.finished("{\"status\":\"NO_MATCH\",\"facts\":[]}", 30).outcome());
        assertEquals("UNAVAILABLE", start.finished("{\"status\":\"UNAVAILABLE\",\"availability\":\"TIMEOUT\"}", 40).outcome());
        assertEquals("UNKNOWN", start.finished("not-json", 1).state());
    }

    @Test
    void reopeningUnfinishedActivitiesStopsSpinnersWithoutOverwritingConfirmedWrites() throws Exception {
        ToolActivity created = ToolActivity.started(1, "create_memory").finished("{\"status\":\"CREATED\"}", 12);
        ToolActivity pending = ToolActivity.started(2, "update_memory");
        String meta = new ObjectMapper().writeValueAsString(Map.of("toolActivities", List.of(created, pending)));
        var restored = ToolActivity.readMeta(meta, false);
        assertEquals("CREATED", restored.get(0).outcome());
        assertEquals(1, restored.get(0).count());
        assertEquals("UNKNOWN", restored.get(1).state());
        assertNull(restored.get(1).elapsedMs());
        assertEquals("RUNNING", ToolActivity.readMeta(meta, true).get(1).state());
        assertEquals("unknown_tool", ToolActivity.started(1, "private-external-name").toolName());
        assertTrue(ToolActivity.readMeta("corrupt", false).isEmpty());
    }
}
