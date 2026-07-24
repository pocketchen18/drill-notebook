package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.ReviewRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class TodayQueueServiceTest {
    private JdbcTemplate jdbc;
    private ReviewRepository reviews;
    private StudyPlanRepository plans;
    private TodayQueueService queue;
    private long bankId;
    private long q1;
    private long q2;
    private long q3;
    private long q4;
    private long q5;
    private long q6;
    private long configId;
    private static final String DAY = "2026-07-21";

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("today-queue-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        reviews = new ReviewRepository(jdbc, mapper);
        plans = new StudyPlanRepository(jdbc);
        QuestionRepository questions = new QuestionRepository(jdbc, mapper);
        KnowledgePointRepository points = new KnowledgePointRepository(jdbc, mapper);
        NotebookRepository notebooks = new NotebookRepository(jdbc, mapper);
        queue = new TodayQueueService(plans, reviews, questions, points, notebooks);

        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        q1 = insertQuestion("题干一");
        q2 = insertQuestion("题干二");
        q3 = insertQuestion("题干三");
        q4 = insertQuestion("题干四");
        q5 = insertQuestion("题干五");
        q6 = insertQuestion("题干六");
        configId = reviews.findDefaultConfig().id;
    }

    private long insertQuestion(String stem) {
        jdbc.update(
                "INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', ?, 'A')",
                bankId,
                stem);
        return jdbc.queryForObject("SELECT id FROM question ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long enrollDue(long questionId, String nextReviewYmdHms) {
        long sid = reviews.createSchedule("question", questionId, configId);
        jdbc.update(
                "UPDATE review_schedule SET status = 'learning', interval = 1, next_review = ? WHERE id = ?",
                nextReviewYmdHms,
                sid);
        return sid;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(Map<String, Object> result) {
        return (Map<String, Object>) result.get("stats");
    }

    @Test
    void planAndDue_merge_noDoubleVirtualRow() {
        long groupId = plans.insertGroup(DAY, "组A", null, "manual");
        long planItemId = plans.insertItem(groupId, DAY, "question", q1, "计划题一", null);
        long scheduleId = enrollDue(q1, DAY + " 09:00:00");
        // also a pure due not on plan
        long pureSchedule = enrollDue(q2, DAY + " 10:00:00");

        Map<String, Object> result = queue.getToday(DAY, configId);
        assertEquals(DAY, result.get("date"));
        List<Map<String, Object>> rows = items(result);

        assertEquals(2, rows.size());
        Map<String, Object> planRow = rows.stream()
                .filter(r -> ("plan:" + planItemId).equals(r.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("plan_and_due", planRow.get("kind"));
        assertEquals(true, planRow.get("due"));
        assertEquals(false, planRow.get("overdue"));
        assertEquals(scheduleId, ((Number) planRow.get("scheduleId")).longValue());
        assertEquals("learning", planRow.get("srsStatus"));
        assertEquals(planItemId, ((Number) planRow.get("planItemId")).longValue());
        assertEquals(groupId, ((Number) planRow.get("groupId")).longValue());
        assertEquals("组A", planRow.get("groupTitle"));

        Map<String, Object> dueRow = rows.stream()
                .filter(r -> ("due:" + pureSchedule).equals(r.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("due", dueRow.get("kind"));
        assertEquals(true, dueRow.get("due"));
        assertNull(dueRow.get("planItemId"));
        assertEquals(q2, ((Number) dueRow.get("resourceId")).longValue());
        // nextReview exposed as YYYY-MM-DD for client urgency labels (到期 vs 提前)
        assertEquals(DAY, dueRow.get("nextReview"));
        assertEquals(DAY, planRow.get("nextReview"));

        // no second virtual for q1
        long virtualForQ1 = rows.stream()
                .filter(r -> "due".equals(r.get("kind"))
                        && ((Number) r.get("resourceId")).longValue() == q1)
                .count();
        assertEquals(0, virtualForQ1);

        Map<String, Object> s = stats(result);
        assertEquals(1, ((Number) s.get("planTodo")).intValue());
        assertEquals(1, ((Number) s.get("dueIncluded")).intValue());
        assertEquals(0, ((Number) s.get("dueTruncated")).intValue());
        assertEquals(0, ((Number) s.get("newIncluded")).intValue());
        assertEquals(0, ((Number) s.get("newTruncated")).intValue());
    }

    @Test
    void allPlanRowsGetDueBadge_whenMultipleTodosSameResource() {
        long g = plans.insertGroup(DAY, "g", null, "manual");
        long a = plans.insertItem(g, DAY, "question", q1, "a", null);
        long b = plans.insertItem(g, DAY, "question", q1, "b", null);
        long sid = enrollDue(q1, DAY + " 08:00:00");

        List<Map<String, Object>> rows = items(queue.getToday(DAY, null));
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals("plan_and_due", row.get("kind"));
            assertEquals(true, row.get("due"));
            assertEquals(sid, ((Number) row.get("scheduleId")).longValue());
            assertTrue(row.get("id").toString().startsWith("plan:"));
        }
        assertEquals(a, ((Number) rows.get(0).get("planItemId")).longValue());
        assertEquals(b, ((Number) rows.get(1).get("planItemId")).longValue());
        // no pure due virtual
        assertTrue(rows.stream().noneMatch(r -> "due".equals(r.get("kind"))));
    }

    @Test
    void planNeverTruncated_pureDueTruncatedByDailyReviewLimit() {
        // limit pure due to 2
        SpacedRepetitionConfig cfg = reviews.findDefaultConfig();
        cfg.dailyReviewLimit = 2;
        reviews.updateConfig(cfg.id, cfg);

        long g = plans.insertGroup(DAY, "g", null, "manual");
        plans.insertItem(g, DAY, "question", q1, "plan-1", null);
        plans.insertItem(g, DAY, "question", q2, "plan-2", null);
        plans.insertItem(g, DAY, "question", q3, "plan-3", null);

        // 4 pure dues (not on plan)
        enrollDue(q4, DAY + " 01:00:00");
        enrollDue(q5, DAY + " 02:00:00");
        enrollDue(q6, "2026-07-20 03:00:00"); // overdue pure due — prefer keep
        // one more would need another question — use re-enroll on new id
        long q7 = insertQuestion("题干七");
        enrollDue(q7, DAY + " 04:00:00");

        Map<String, Object> result = queue.getToday(DAY, configId);
        List<Map<String, Object>> rows = items(result);
        Map<String, Object> s = stats(result);

        long planCount = rows.stream().filter(r -> {
            String k = (String) r.get("kind");
            return "plan".equals(k) || "plan_and_due".equals(k);
        }).count();
        assertEquals(3, planCount, "plan rows never truncated");
        assertEquals(3, ((Number) s.get("planTodo")).intValue());

        long pureDue = rows.stream().filter(r -> "due".equals(r.get("kind"))).count();
        assertEquals(2, pureDue, "pure due limited to daily_review_limit=2");
        assertEquals(2, ((Number) s.get("dueIncluded")).intValue());
        assertEquals(2, ((Number) s.get("dueTruncated")).intValue());

        // overdue kept preferentially
        List<Map<String, Object>> pureRows = rows.stream()
                .filter(r -> "due".equals(r.get("kind")))
                .collect(Collectors.toList());
        assertTrue(pureRows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("overdue"))),
                "overdue pure due preferred when truncating");
    }

    @Test
    void overdueSortsBeforePlanAndPureDue() {
        long g = plans.insertGroup(DAY, "g", null, "manual");
        plans.insertItem(g, DAY, "question", q1, "today-plan", null);
        enrollDue(q2, DAY + " 12:00:00"); // pure due same day
        enrollDue(q3, "2026-07-19 08:00:00"); // pure overdue

        List<Map<String, Object>> rows = items(queue.getToday(DAY, null));
        assertEquals(3, rows.size());
        assertEquals(true, rows.get(0).get("overdue"));
        assertEquals(q3, ((Number) rows.get(0).get("resourceId")).longValue());
        // next: plan (not overdue)
        assertTrue(rows.get(1).get("id").toString().startsWith("plan:"));
        // then pure due
        assertEquals("due", rows.get(2).get("kind"));
        assertEquals(q2, ((Number) rows.get(2).get("resourceId")).longValue());
    }

    @Test
    void planOnly_withoutSrs_kindIsPlan() {
        long g = plans.insertGroup(DAY, "g", null, "manual");
        long id = plans.insertItem(g, DAY, "question", q1, "only-plan", "n");
        Map<String, Object> result = queue.getToday(DAY, null);
        List<Map<String, Object>> rows = items(result);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("plan", row.get("kind"));
        assertEquals("plan:" + id, row.get("id"));
        assertEquals(false, row.get("due"));
        assertEquals(false, row.get("overdue"));
        assertNull(row.get("scheduleId"));
        assertEquals("n", row.get("note"));
        assertEquals(0, ((Number) stats(result).get("dueIncluded")).intValue());
    }

    @Test
    void newEnroll_hasNextReviewAndAppearsOnRealToday() {
        long sid = reviews.createSchedule("question", q1, configId);
        ReviewSchedule created = reviews.findScheduleById(sid);
        assertNotNull(created.nextReview, "enroll must set next_review so queue can show the card");
        assertEquals("new", created.status);

        // Use real today so legacy/new path and date filter both apply
        String realToday = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        // Ensure due: set next_review to real today
        jdbc.update("UPDATE review_schedule SET next_review = ? WHERE id = ?", realToday + " 00:00:00", sid);

        Map<String, Object> result = queue.getToday(realToday, configId);
        List<Map<String, Object>> rows = items(result);
        assertFalse(rows.isEmpty());
        Map<String, Object> hit = rows.stream()
                .filter(r -> q1 == ((Number) r.get("resourceId")).longValue())
                .findFirst()
                .orElseThrow();
        assertTrue(
                Boolean.TRUE.equals(hit.get("due"))
                        || "due".equals(hit.get("kind"))
                        || "new".equals(hit.get("srsStatus"))
                        || Boolean.TRUE.equals(hit.get("isNew"))
                        || hit.get("scheduleId") != null);
    }

    @Test
    void legacyNullNextReview_appearsOnRealToday() {
        long sid = reviews.createSchedule("question", q1, configId);
        jdbc.update(
                "UPDATE review_schedule SET next_review = NULL, status = 'new' WHERE id = ?",
                sid);
        String realToday = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, Object> result = queue.getToday(realToday, configId);
        List<Map<String, Object>> rows = items(result);
        assertTrue(rows.stream().anyMatch(r -> q1 == ((Number) r.get("resourceId")).longValue()),
                "legacy NULL next_review new cards must show on real today");
        assertTrue(((Number) stats(result).get("newIncluded")).intValue() >= 1
                || rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("isNew"))));
    }

    @Test
    void deletedQuestion_scheduleAndPlanPurgedFromQueue() {
        String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        long g = plans.insertGroup(day, "g", null, "manual");
        plans.insertItem(g, day, "question", q1, "将删", null);
        long sid = enrollDue(q1, day + " 08:00:00");
        long sid2 = enrollDue(q2, day + " 09:00:00");

        // Simulate bank delete leaving orphans (or pre-cascade data)
        jdbc.update("DELETE FROM question WHERE id = ?", q1);

        Map<String, Object> result = queue.getToday(day, configId);
        List<Map<String, Object>> rows = items(result);
        assertTrue(rows.stream().noneMatch(r ->
                "question".equals(r.get("resourceType"))
                        && ((Number) r.get("resourceId")).longValue() == q1),
                "deleted question must not appear in today queue");
        assertTrue(rows.stream().anyMatch(r ->
                ((Number) r.get("resourceId")).longValue() == q2),
                "other due questions still shown");

        // schedule for deleted q purged
        Integer schedules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_schedule WHERE item_type = 'question' AND item_id = ?",
                Integer.class,
                q1);
        assertEquals(0, schedules);
        Integer planItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_item WHERE resource_type = 'question' AND resource_id = ?",
                Integer.class,
                q1);
        assertEquals(0, planItems);
        // silence unused
        assertTrue(sid > 0 && sid2 > 0);
    }

    @Test
    void emptyDateDefaultsToToday_shape() {
        Map<String, Object> result = queue.getToday(null, null);
        assertNotNull(result.get("date"));
        assertNotNull(result.get("items"));
        assertNotNull(result.get("stats"));
        assertTrue(((String) result.get("date")).matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void planAndDue_overdueOnlyRelativeToRealToday_notSelectedFutureDay() {
        // Viewing a future calendar day (DAY+3) still lists cards due by that day.
        // Overdue flag uses LocalDate.now(), not the selected day — so a card due
        // "yesterday relative to selected day" is NOT overdue if real today is before that.
        String future = "2099-06-15";
        String earlier = "2099-06-10 00:00:00"; // before future day, after any real "today" in 2026
        long g = plans.insertGroup(future, "g", null, "manual");
        plans.insertItem(g, future, "question", q1, "p", null);
        enrollDue(q1, earlier);

        Map<String, Object> row = items(queue.getToday(future, null)).get(0);
        assertEquals("plan_and_due", row.get("kind"));
        assertEquals(true, row.get("due"));
        // next_review 2099-06-10 is still in the future vs real today → not overdue
        assertEquals(false, row.get("overdue"));
    }

    @Test
    void pureDue_overdueWhenNextReviewBeforeRealToday() {
        // past relative to any realistic test run date (2026+)
        enrollDue(q1, "2020-01-01 00:00:00");
        Map<String, Object> row = items(queue.getToday("2026-07-21", null)).get(0);
        assertEquals("due", row.get("kind"));
        assertEquals(true, row.get("overdue"));
    }

    @Test
    void configFilter_limitsDueToConfigId() {
        SpacedRepetitionConfig other = reviews.findDefaultConfig();
        // create second config by copy via insert
        SpacedRepetitionConfig c2 = new SpacedRepetitionConfig();
        c2.name = "other";
        c2.isDefault = false;
        c2.intervals = other.intervals;
        c2.initialEf = other.initialEf;
        c2.minimumEf = other.minimumEf;
        c2.maxIntervalDays = other.maxIntervalDays;
        c2.wrongStrategy = other.wrongStrategy;
        c2.wrongFixedDays = other.wrongFixedDays;
        c2.dailyNewLimit = other.dailyNewLimit;
        c2.dailyReviewLimit = 100;
        c2.priorityMode = other.priorityMode;
        long config2 = reviews.insertConfig(c2);

        long sidDefault = reviews.createSchedule("question", q1, configId);
        jdbc.update(
                "UPDATE review_schedule SET status='learning', interval=1, next_review=? WHERE id=?",
                DAY + " 09:00:00",
                sidDefault);
        long sidOther = reviews.createSchedule("question", q2, config2);
        jdbc.update(
                "UPDATE review_schedule SET status='learning', interval=1, next_review=? WHERE id=?",
                DAY + " 09:00:00",
                sidOther);

        List<Map<String, Object>> filtered = items(queue.getToday(DAY, config2));
        assertEquals(1, filtered.size());
        assertEquals(q2, ((Number) filtered.get(0).get("resourceId")).longValue());

        List<Map<String, Object>> all = items(queue.getToday(DAY, null));
        assertEquals(2, all.size());
    }
}
