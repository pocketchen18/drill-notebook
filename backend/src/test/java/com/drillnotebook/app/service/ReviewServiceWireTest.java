package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.ReviewRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/** TDD: autoSubmit skip when not enrolled + submit wires through CompletionSync. */
class ReviewServiceWireTest {
    private JdbcTemplate jdbc;
    private ReviewRepository reviews;
    private StudyPlanRepository plans;
    private ReviewService reviewService;
    private String today;
    private long questionId;
    private long configId;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("review-wire-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        reviews = new ReviewRepository(jdbc, mapper);
        plans = new StudyPlanRepository(jdbc);
        QuestionRepository questions = new QuestionRepository(jdbc, mapper);
        ReviewScheduleApplier applier = new ReviewScheduleApplier(reviews);
        CompletionSyncService sync = new CompletionSyncService(reviews, plans, applier);
        reviewService = new ReviewService(reviews, questions, applier, sync);
        today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        long bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        jdbc.update(
                "INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', 'stem', 'A')",
                bankId);
        questionId = jdbc.queryForObject("SELECT id FROM question ORDER BY id LIMIT 1", Long.class);
        configId = reviews.findDefaultConfig().id;
    }

    @Test
    void autoSubmit_whenNotEnrolled_skips() {
        Map<String, Object> result = reviewService.autoSubmitFromQuiz(questionId, true, 5, configId);

        assertEquals(true, result.get("skipped"));
        assertEquals("not_enrolled", result.get("reason"));
        assertNull(reviews.findScheduleByItem("question", questionId, configId));
        assertNull(reviews.findScheduleByItem("question", questionId, null));
    }

    @Test
    void submit_marksEarliestPlanAndAdvancesSrs() {
        long scheduleId = reviews.createSchedule("question", questionId, configId);
        jdbc.update(
                "UPDATE review_schedule SET status = 'review', interval = 1.0, repetitions = 3, "
                        + "ef = 2.5, next_review = ?, last_review = ? WHERE id = ?",
                today + " 08:00:00",
                today + " 00:00:00",
                scheduleId);
        long groupId = plans.insertGroup(today, "g", null, "manual");
        long itemId = plans.insertItem(groupId, today, "question", questionId, "q", null);
        String nextBefore = reviews.findScheduleById(scheduleId).nextReview;

        Map<String, Object> result = reviewService.submit(scheduleId, 4, 12, "manual");

        assertNotNull(result.get("logId"));
        assertEquals(scheduleId, ((Number) result.get("scheduleId")).longValue());
        assertNotNull(result.get("nextReview"));
        assertNotNull(result.get("ef"));
        assertEquals("done", plans.findItem(itemId).get("status"));
        ReviewSchedule after = reviews.findScheduleById(scheduleId);
        assertTrue(!nextBefore.equals(after.nextReview) || after.totalReviews >= 1);
    }
}
