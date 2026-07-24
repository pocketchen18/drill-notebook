package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.ReviewSchedule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class ReviewRepositorySyncTest {
    private JdbcTemplate jdbc;
    private ReviewRepository reviews;
    private StudyPlanRepository plans;
    private String today;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("review-repo-sync-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        reviews = new ReviewRepository(jdbc, mapper);
        plans = new StudyPlanRepository(jdbc);
        today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Test
    void hasMainAdvanceOnDate_ignoresExtraSource() {
        long scheduleId = reviews.createSchedule("question", 1L, null);

        assertFalse(reviews.hasMainAdvanceOnDate(scheduleId, today));

        reviews.insertLog(scheduleId, 4, null, 1.0, 1.0, "extra");
        assertFalse(reviews.hasMainAdvanceOnDate(scheduleId, today), "extra-only should not count as main advance");

        reviews.insertLog(scheduleId, 4, null, 1.0, 1.0, "quiz");
        assertTrue(reviews.hasMainAdvanceOnDate(scheduleId, today), "main source 'quiz' should count");

        // empty / non-extra source counts as main (schema source is NOT NULL; empty default is allowed)
        long schedule2 = reviews.createSchedule("question", 2L, null);
        reviews.insertLog(schedule2, 3, null, 1.0, 1.0, "");
        assertTrue(reviews.hasMainAdvanceOnDate(schedule2, today), "empty source should count as main");

        assertFalse(reviews.hasMainAdvanceOnDate(scheduleId, "2099-01-01"), "other day should be false");
    }

    @Test
    void findEarliestTodo_returnsLowestId() {
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long later = plans.insertItem(groupId, today, "question", 42L, "second", null);
        // Insert an earlier row that becomes higher id; then insert another todo first conceptually
        // by inserting two and verifying ORDER BY id ASC
        long earlier = plans.insertItem(groupId, today, "question", 42L, "first-by-id-wait", null);
        // Actually later has lower id since inserted first — earliest means lowest id
        Map<String, Object> found = plans.findEarliestTodo(today, "question", 42L);
        assertNotNull(found);
        assertEquals(later, ((Number) found.get("id")).longValue());
        assertEquals("todo", found.get("status"));
        assertTrue(((Number) found.get("id")).longValue() < earlier);

        assertNull(plans.findEarliestTodo(today, "question", 999L));
        assertNull(plans.findEarliestTodo("2099-01-01", "question", 42L));
    }

    @Test
    void findDueOnOrBefore_returnsOnlyDueSchedulesWithNextReview() {
        long dueToday = reviews.createSchedule("question", 10L, null);
        long duePast = reviews.createSchedule("question", 11L, null);
        long dueFuture = reviews.createSchedule("question", 12L, null);
        long newNoReview = reviews.createSchedule("question", 13L, null);
        long knowledgeDue = reviews.createSchedule("knowledge", 20L, null);

        jdbc.update("UPDATE review_schedule SET next_review = ?, status = 'review' WHERE id = ?", today + " 08:00:00", dueToday);
        jdbc.update(
                "UPDATE review_schedule SET next_review = ?, status = 'review' WHERE id = ?",
                "2020-01-01 00:00:00",
                duePast);
        jdbc.update(
                "UPDATE review_schedule SET next_review = ?, status = 'review' WHERE id = ?",
                "2099-12-31 00:00:00",
                dueFuture);
        // newNoReview keeps next_review NULL, status new — should NOT appear (strict due rule)
        jdbc.update(
                "UPDATE review_schedule SET next_review = ?, status = 'learning' WHERE id = ?",
                today + " 12:00:00",
                knowledgeDue);

        List<ReviewSchedule> due = reviews.findDueOnOrBefore(today, null, null);
        List<Long> ids = due.stream().map(s -> s.id).sorted().toList();
        assertTrue(ids.contains(dueToday));
        assertTrue(ids.contains(duePast));
        assertTrue(ids.contains(knowledgeDue));
        assertFalse(ids.contains(dueFuture));
        assertFalse(ids.contains(newNoReview));

        List<ReviewSchedule> questionsOnly = reviews.findDueOnOrBefore(today, "question", null);
        List<Long> qIds = questionsOnly.stream().map(s -> s.id).toList();
        assertTrue(qIds.contains(dueToday));
        assertFalse(qIds.contains(knowledgeDue));
    }

    @Test
    void markItemDone_isIdempotentAndFindTodosOnDate() {
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long a = plans.insertItem(groupId, today, "question", 1L, "a", null);
        long b = plans.insertItem(groupId, today, "question", 2L, "b", null);
        long otherDay = plans.insertItem(groupId, "2099-01-01", "question", 3L, "c", null);

        List<Map<String, Object>> todos = plans.findTodosOnDate(today);
        assertEquals(2, todos.size());
        assertEquals(a, ((Number) todos.get(0).get("id")).longValue());
        assertEquals(b, ((Number) todos.get(1).get("id")).longValue());

        assertEquals(1, plans.markItemDone(a));
        assertEquals("done", plans.findItem(a).get("status"));
        assertNotNull(plans.findItem(a).get("completedAt"));

        assertEquals(0, plans.markItemDone(a), "already done should be no-op");
        assertEquals(1, plans.findTodosOnDate(today).size());
        assertEquals(b, ((Number) plans.findTodosOnDate(today).get(0).get("id")).longValue());

        assertEquals(1, plans.markItemDone(otherDay));
        assertTrue(plans.findTodosOnDate("2099-01-01").isEmpty());
    }
}
