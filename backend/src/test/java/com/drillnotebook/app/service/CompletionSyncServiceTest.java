package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.ReviewLog;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.ReviewRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class CompletionSyncServiceTest {
    private JdbcTemplate jdbc;
    private ReviewRepository reviews;
    private StudyPlanRepository plans;
    private CompletionSyncService sync;
    private String today;
    private long questionId;
    private long configId;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("completion-sync-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        reviews = new ReviewRepository(jdbc, mapper);
        plans = new StudyPlanRepository(jdbc);
        ReviewScheduleApplier applier = new ReviewScheduleApplier(reviews);
        sync = new CompletionSyncService(reviews, plans, applier);
        today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        long bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        jdbc.update(
                "INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', 'stem', 'A')",
                bankId);
        questionId = jdbc.queryForObject("SELECT id FROM question ORDER BY id LIMIT 1", Long.class);
        configId = reviews.findDefaultConfig().id;
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Put schedule into stable review (interval >= 1) so same-day main-advance policy applies. */
    private void forceStableSchedule(long scheduleId, double interval) {
        jdbc.update(
                "UPDATE review_schedule SET status = 'review', interval = ?, repetitions = 3, "
                        + "ef = 2.5, next_review = ?, last_review = ? WHERE id = ?",
                interval,
                today + " 08:00:00",
                today + " 00:00:00",
                scheduleId);
    }

    @Test
    void planOnly_noEnroll_onlyMarksPlan() {
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long itemId = plans.insertItem(groupId, today, "question", questionId, "q", null);

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "calendar"));

        assertEquals("done", plans.findItem(itemId).get("status"));
        assertNotNull(result.get("planItem"));
        assertNull(result.get("srs"), "not enrolled → no srs");
        assertEquals("not_enrolled", result.get("skippedSrs"));
        assertEquals(false, result.get("extraPractice"));
        assertTrue(reviews.findDueOnOrBefore(today, "question", null).isEmpty()
                || reviews.findScheduleByItem("question", questionId, null) == null);
        assertNull(reviews.findScheduleByItem("question", questionId, configId));
    }

    @Test
    void srsOnly_noPlan_advancesSchedule() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 1.0);
        ReviewSchedule before = reviews.findScheduleById(scheduleId);
        String nextBefore = before.nextReview;

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "quiz"));

        assertNull(result.get("planItem"));
        assertNotNull(result.get("srs"));
        assertEquals(false, result.get("extraPractice"));
        ReviewSchedule after = reviews.findScheduleById(scheduleId);
        assertNotEquals(nextBefore, after.nextReview);
        assertTrue(after.totalReviews >= 1);
        List<ReviewLog> logs = reviews.findLogsBySchedule(scheduleId, 10);
        assertEquals(1, logs.size());
        assertNotEquals("extra", logs.get(0).source);
    }

    @Test
    void both_advancesAndMarksEarliest() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 1.0);
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long first = plans.insertItem(groupId, today, "question", questionId, "a", null);
        long second = plans.insertItem(groupId, today, "question", questionId, "b", null);

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4));

        assertEquals("done", plans.findItem(first).get("status"));
        assertEquals("todo", plans.findItem(second).get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> planItem = (Map<String, Object>) result.get("planItem");
        assertEquals(first, ((Number) planItem.get("id")).longValue());
        assertNotNull(result.get("srs"));
        assertEquals(false, result.get("extraPractice"));
    }

    @Test
    void threePlanTodos_threeCompletes() {
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long a = plans.insertItem(groupId, today, "question", questionId, "1", null);
        long b = plans.insertItem(groupId, today, "question", questionId, "2", null);
        long c = plans.insertItem(groupId, today, "question", questionId, "3", null);

        Map<String, Object> r1 = sync.onItemCompleted(body(
                "resourceType", "question", "resourceId", questionId, "quality", 3));
        Map<String, Object> r2 = sync.onItemCompleted(body(
                "resourceType", "question", "resourceId", questionId, "quality", 3));
        Map<String, Object> r3 = sync.onItemCompleted(body(
                "resourceType", "question", "resourceId", questionId, "quality", 3));

        assertEquals(a, ((Number) ((Map<?, ?>) r1.get("planItem")).get("id")).longValue());
        assertEquals(b, ((Number) ((Map<?, ?>) r2.get("planItem")).get("id")).longValue());
        assertEquals(c, ((Number) ((Map<?, ?>) r3.get("planItem")).get("id")).longValue());
        assertEquals("done", plans.findItem(a).get("status"));
        assertEquals("done", plans.findItem(b).get("status"));
        assertEquals("done", plans.findItem(c).get("status"));
        assertTrue(plans.findTodosOnDate(today).isEmpty());
    }

    @Test
    void secondCorrectSameDay_isExtra_doesNotChangeNextReview() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 6.0);

        Map<String, Object> first = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "quiz"));
        assertEquals(false, first.get("extraPractice"));
        String nextAfterMain = reviews.findScheduleById(scheduleId).nextReview;
        int reviewsAfterMain = reviews.findScheduleById(scheduleId).totalReviews;

        Map<String, Object> second = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 5,
                "source", "quiz"));
        assertEquals(true, second.get("extraPractice"));
        assertEquals(nextAfterMain, reviews.findScheduleById(scheduleId).nextReview);
        assertEquals(reviewsAfterMain, reviews.findScheduleById(scheduleId).totalReviews);

        List<ReviewLog> logs = reviews.findLogsBySchedule(scheduleId, 10);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().anyMatch(l -> "extra".equals(l.source)));
        assertTrue(logs.stream().anyMatch(l -> "quiz".equals(l.source)));
    }

    @Test
    void forceAdvance_secondCorrectSameDay_advancesAgain() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 6.0);

        Map<String, Object> first = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "quiz"));
        assertEquals(false, first.get("extraPractice"));
        String nextAfterMain = reviews.findScheduleById(scheduleId).nextReview;
        int reviewsAfterMain = reviews.findScheduleById(scheduleId).totalReviews;

        Map<String, Object> second = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 5,
                "source", "today",
                "forceAdvance", true));
        assertEquals(false, second.get("extraPractice"));
        assertNotEquals(nextAfterMain, reviews.findScheduleById(scheduleId).nextReview);
        assertEquals(reviewsAfterMain + 1, reviews.findScheduleById(scheduleId).totalReviews);
        assertFalse(reviews.findLogsBySchedule(scheduleId, 10).stream()
                .anyMatch(l -> "extra".equals(l.source)));
    }

    @Test
    void secondWrongSameDay_reschedules() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 6.0);

        sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "quiz"));
        String nextAfterMain = reviews.findScheduleById(scheduleId).nextReview;
        int reviewsAfterMain = reviews.findScheduleById(scheduleId).totalReviews;

        Map<String, Object> second = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 1,
                "source", "quiz"));
        assertEquals(false, second.get("extraPractice"));
        assertNotEquals(nextAfterMain, reviews.findScheduleById(scheduleId).nextReview);
        assertEquals(reviewsAfterMain + 1, reviews.findScheduleById(scheduleId).totalReviews);

        List<ReviewLog> logs = reviews.findLogsBySchedule(scheduleId, 10);
        assertEquals(2, logs.size());
        assertNotEquals("extra", logs.get(0).source);
    }

    @Test
    void notePage_neverTouchesSrs() {
        long pageId = 99L;
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long itemId = plans.insertItem(groupId, today, "note_page", pageId, "note", null);
        // Even if a schedule existed, note_page must not touch SRS — but we also never auto-enroll
        long bogusSchedule = reviews.createSchedule("note_page", pageId, configId);
        forceStableSchedule(bogusSchedule, 1.0);
        String nextBefore = reviews.findScheduleById(bogusSchedule).nextReview;
        int logCountBefore = reviews.findLogsBySchedule(bogusSchedule, 100).size();

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "note_page",
                "resourceId", pageId,
                "quality", 5,
                "source", "calendar"));

        assertEquals("done", plans.findItem(itemId).get("status"));
        assertNull(result.get("srs"));
        assertEquals(false, result.get("extraPractice"));
        // skippedSrs may be set for note; either way SRS unchanged
        assertEquals(nextBefore, reviews.findScheduleById(bogusSchedule).nextReview);
        assertEquals(logCountBefore, reviews.findLogsBySchedule(bogusSchedule, 100).size());
    }

    @Test
    void noQuality_noIsCorrect_planOnly() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 1.0);
        String nextBefore = reviews.findScheduleById(scheduleId).nextReview;
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long itemId = plans.insertItem(groupId, today, "question", questionId, "q", null);

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "source", "calendar"));

        assertEquals("done", plans.findItem(itemId).get("status"));
        assertNotNull(result.get("planItem"));
        assertNull(result.get("srs"));
        assertEquals(nextBefore, reviews.findScheduleById(scheduleId).nextReview);
        assertEquals(0, reviews.findLogsBySchedule(scheduleId, 10).size());
    }

    @Test
    void shortInterval_allowsMultipleMainAdvancesSameDay() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        // learning with interval < 1 day
        jdbc.update(
                "UPDATE review_schedule SET status = 'learning', interval = 0.25, repetitions = 1, "
                        + "ef = 2.5, next_review = ?, last_review = ? WHERE id = ?",
                today + " 08:00:00",
                today + " 00:00:00",
                scheduleId);

        Map<String, Object> first = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "quiz"));
        assertEquals(false, first.get("extraPractice"));
        String next1 = reviews.findScheduleById(scheduleId).nextReview;

        // force short interval again after first advance
        jdbc.update("UPDATE review_schedule SET interval = 0.25, status = 'learning' WHERE id = ?", scheduleId);

        Map<String, Object> second = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "quiz"));
        assertEquals(false, second.get("extraPractice"));
        assertNotEquals(next1, reviews.findScheduleById(scheduleId).nextReview);
        assertEquals(2, reviews.findLogsBySchedule(scheduleId, 10).size());
        assertFalse(reviews.findLogsBySchedule(scheduleId, 10).stream()
                .anyMatch(l -> "extra".equals(l.source)));
    }

    @Test
    void planItemId_marksSpecificItem() {
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long first = plans.insertItem(groupId, today, "question", questionId, "a", null);
        long second = plans.insertItem(groupId, today, "question", questionId, "b", null);

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "planItemId", second,
                "quality", 3));

        assertEquals("todo", plans.findItem(first).get("status"));
        assertEquals("done", plans.findItem(second).get("status"));
        assertEquals(second, ((Number) ((Map<?, ?>) result.get("planItem")).get("id")).longValue());
    }

    @Test
    void planDateYesterday_withMainToday_secondCorrectIsExtra() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        forceStableSchedule(scheduleId, 6.0);
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        long groupId = plans.insertGroup(yesterday, "g", null, "manual");
        long itemId = plans.insertItem(groupId, yesterday, "question", questionId, "q", null);

        Map<String, Object> first = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "planDate", yesterday,
                "quality", 4,
                "source", "quiz"));
        assertEquals(false, first.get("extraPractice"));
        assertEquals("done", plans.findItem(itemId).get("status"));
        String nextAfterMain = reviews.findScheduleById(scheduleId).nextReview;
        int reviewsAfterMain = reviews.findScheduleById(scheduleId).totalReviews;

        // Completing again with planDate=yesterday; main already happened today → extra
        Map<String, Object> second = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "planDate", yesterday,
                "quality", 5,
                "source", "quiz"));
        assertEquals(true, second.get("extraPractice"));
        assertEquals(nextAfterMain, reviews.findScheduleById(scheduleId).nextReview);
        assertEquals(reviewsAfterMain, reviews.findScheduleById(scheduleId).totalReviews);
        assertTrue(reviews.findLogsBySchedule(scheduleId, 10).stream()
                .anyMatch(l -> "extra".equals(l.source)));
    }

    @Test
    void nonNumericQuality_throwsIllegalArgument() {
        try {
            sync.onItemCompleted(body(
                    "resourceType", "question",
                    "resourceId", questionId,
                    "quality", "not-a-number"));
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("quality"));
        }
    }

    @Test
    void completeOnFutureViewDay_pass_clearsThatDayFromQueue() {
        // Viewing a future day (today+3): stage-1 interval would leave next_review before that day.
        // After pass from that planDate, next_review must be AFTER the view day.
        String future = LocalDate.now().plusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE);
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        // new card: first pass → typically 1 day interval from config
        jdbc.update(
                "UPDATE review_schedule SET status = 'new', interval = 0, repetitions = 0, "
                        + "ef = 2.5, next_review = ?, last_review = NULL WHERE id = ?",
                today + " 00:00:00",
                scheduleId);

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "today_queue",
                "planDate", future,
                "forceAdvance", true,
                "scheduleId", scheduleId));

        assertEquals(false, result.get("extraPractice"));
        @SuppressWarnings("unchecked")
        Map<String, Object> srs = (Map<String, Object>) result.get("srs");
        assertNotNull(srs);
        String next = String.valueOf(srs.get("nextReview"));
        assertTrue(next.length() >= 10, next);
        String nextDay = next.substring(0, 10);
        assertTrue(nextDay.compareTo(future) > 0,
                "next_review " + nextDay + " should be after view day " + future);
        assertEquals(future, srs.get("clearedViewDay"));
        assertEquals(nextDay, reviews.findScheduleById(scheduleId).nextReview.substring(0, 10));
    }

    @Test
    void completePassOnToday_clearsTodayDueQueue() {
        // First pass often schedules +1 day which still falls on "today" if computed in hours,
        // or next calendar day — for due filter date(next_review) <= today, next must be > today.
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        jdbc.update(
                "UPDATE review_schedule SET status = 'learning', interval = 0, repetitions = 0, "
                        + "ef = 2.5, next_review = ?, last_review = NULL WHERE id = ?",
                today + " 00:00:00",
                scheduleId);

        Map<String, Object> result = sync.onItemCompleted(body(
                "resourceType", "question",
                "resourceId", questionId,
                "quality", 4,
                "source", "today_queue",
                "planDate", today,
                "forceAdvance", true,
                "scheduleId", scheduleId));

        assertEquals(false, result.get("extraPractice"));
        String next = reviews.findScheduleById(scheduleId).nextReview;
        assertNotNull(next);
        String nextDay = next.substring(0, 10);
        assertTrue(nextDay.compareTo(today) > 0,
                "after pass from today's queue, next_review " + nextDay + " must be after today " + today);
    }
}
