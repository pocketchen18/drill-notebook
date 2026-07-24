package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class StudyPlanRepositoryTest {
    @Test
    void createsGroupItemsDeletesEmptyGroupAndSkipsDetection() throws Exception {
        JdbcTemplate jdbc = openDb("study-plan-repository-test");
        StudyPlanRepository repo = new StudyPlanRepository(jdbc);

        long groupId = repo.insertGroup("2026-07-19", "手动添加", "备注", "manual");
        Map<String, Object> group = repo.findGroup(groupId);
        assertEquals(groupId, group.get("id"));
        assertEquals("2026-07-19", group.get("planDate"));
        assertEquals("手动添加", group.get("title"));
        assertEquals("备注", group.get("note"));
        assertEquals("manual", group.get("source"));
        assertNotNull(group.get("createdAt"));
        assertNotNull(group.get("updatedAt"));

        long itemId = repo.insertItem(groupId, "2026-07-19", "question", 1L, "题干", "");
        Map<String, Object> item = repo.findItem(itemId);
        assertEquals(itemId, item.get("id"));
        assertEquals(groupId, item.get("groupId"));
        assertEquals("2026-07-19", item.get("planDate"));
        assertEquals("question", item.get("resourceType"));
        assertEquals(1L, item.get("resourceId"));
        assertEquals("题干", item.get("title"));
        assertEquals("", item.get("note"));
        assertEquals("todo", item.get("status"));
        assertNull(item.get("completedAt"));

        assertTrue(repo.existsTodo("2026-07-19", "question", 1L));
        repo.updateItem(itemId, null, null, "done");
        Map<String, Object> done = repo.findItem(itemId);
        assertEquals("done", done.get("status"));
        assertNotNull(done.get("completedAt"));
        assertFalse(repo.existsTodo("2026-07-19", "question", 1L));

        repo.updateItem(itemId, null, null, "todo");
        Map<String, Object> todo = repo.findItem(itemId);
        assertEquals("todo", todo.get("status"));
        assertNull(todo.get("completedAt"));
        assertTrue(repo.existsTodo("2026-07-19", "question", 1L));

        repo.deleteItem(itemId);
        assertEquals(0, repo.countItems(groupId));
        repo.deleteGroupIfEmpty(groupId);
        assertThrows(EmptyResultDataAccessException.class, () -> repo.findGroup(groupId));
    }

    @Test
    void updatesGroupAndItemFieldsAndSyncsTodoDates() throws Exception {
        JdbcTemplate jdbc = openDb("study-plan-sync-test");
        StudyPlanRepository repo = new StudyPlanRepository(jdbc);

        long groupId = repo.insertGroup("2026-07-19", "组", "n1", "manual");
        long todoId = repo.insertItem(groupId, "2026-07-19", "question", 2L, "todo题", "a");
        long doneId = repo.insertItem(groupId, "2026-07-19", "knowledge", 3L, "done点", "b");
        repo.updateItem(doneId, null, null, "done");

        repo.updateGroup(groupId, "新组", "新备注", "2026-07-20");
        Map<String, Object> group = repo.findGroup(groupId);
        assertEquals("新组", group.get("title"));
        assertEquals("新备注", group.get("note"));
        assertEquals("2026-07-20", group.get("planDate"));

        repo.updateItem(todoId, "2026-07-21", "新note", null);
        Map<String, Object> item = repo.findItem(todoId);
        assertEquals("2026-07-21", item.get("planDate"));
        assertEquals("新note", item.get("note"));
        assertEquals("todo", item.get("status"));

        repo.syncTodoItemDates(groupId, "2026-07-22");
        assertEquals("2026-07-22", repo.findItem(todoId).get("planDate"));
        assertEquals("2026-07-19", repo.findItem(doneId).get("planDate"));
        assertEquals("done", repo.findItem(doneId).get("status"));

        assertEquals(2, repo.countItems(groupId));
        repo.deleteGroupIfEmpty(groupId);
        assertNotNull(repo.findGroup(groupId));

        repo.deleteGroup(groupId);
        assertThrows(EmptyResultDataAccessException.class, () -> repo.findGroup(groupId));
        assertThrows(EmptyResultDataAccessException.class, () -> repo.findItem(todoId));
    }

    @Test
    void findsGroupsBetweenAndItemsForGroups() throws Exception {
        JdbcTemplate jdbc = openDb("study-plan-range-test");
        StudyPlanRepository repo = new StudyPlanRepository(jdbc);

        long g1 = repo.insertGroup("2026-07-18", "A", null, "manual");
        long g2 = repo.insertGroup("2026-07-19", "B", null, "ai");
        long g3 = repo.insertGroup("2026-07-21", "C", null, "manual");
        long i1 = repo.insertItem(g1, "2026-07-18", "question", 10L, "q1", null);
        long i2 = repo.insertItem(g2, "2026-07-19", "question", 11L, "q2", null);
        repo.insertItem(g3, "2026-07-21", "question", 12L, "q3", null);

        List<Map<String, Object>> groups = repo.findGroupsBetween("2026-07-18", "2026-07-19");
        assertEquals(2, groups.size());
        assertEquals(g1, groups.get(0).get("id"));
        assertEquals(g2, groups.get(1).get("id"));
        assertEquals("A", groups.get(0).get("title"));
        assertEquals("ai", groups.get(1).get("source"));

        List<Map<String, Object>> items = repo.findItemsForGroups(List.of(g1, g2));
        assertEquals(2, items.size());
        assertEquals(i1, items.get(0).get("id"));
        assertEquals(i2, items.get(1).get("id"));
        assertEquals(g1, items.get(0).get("groupId"));

        assertTrue(repo.findItemsForGroups(List.of()).isEmpty());
        assertTrue(repo.findGroupsBetween("2026-08-01", "2026-08-02").isEmpty());
    }

    private static JdbcTemplate openDb(String prefix) throws Exception {
        var root = Files.createTempDirectory(prefix);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }
}
