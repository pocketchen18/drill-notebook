package com.drillnotebook.app.model;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolActivityTest {
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
