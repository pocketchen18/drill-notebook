package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class StudyPlanServiceTest {
    private JdbcTemplate jdbc;
    private StudyPlanRepository plans;
    private QuestionRepository questions;
    private KnowledgePointRepository points;
    private NotebookRepository notebooks;
    private StudyPlanService service;
    private long bankId;
    private long questionId;
    private long questionId2;
    private long pointId;
    private long pageId;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("study-plan-service-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        plans = new StudyPlanRepository(jdbc);
        questions = new QuestionRepository(jdbc, mapper);
        points = new KnowledgePointRepository(jdbc, mapper);
        notebooks = new NotebookRepository(jdbc, mapper);
        service = new StudyPlanService(plans, questions, points, notebooks, null);

        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        jdbc.update("INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', '题干甲很长很长很长', 'A')", bankId);
        questionId = jdbc.queryForObject("SELECT id FROM question ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', '题干乙', 'B')", bankId);
        questionId2 = jdbc.queryForObject("SELECT id FROM question ORDER BY id DESC LIMIT 1", Long.class);
        pointId = points.insert(bankId, "知识点甲", "内容", "分类", List.of(), List.of());
        long notebookId = notebooks.insert("本子");
        pageId = notebooks.insertPage(notebookId, "笔记页", null);
    }

    @Test
    void createGroup_allowsSameDayDuplicateTodo_andFailsMissing() {
        Map<String, Object> first = service.createGroup(createBody(
                "2026-07-19",
                "手动添加",
                "manual",
                List.of(item("question", questionId, "自定义标题", ""))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) first.get("items");
        assertEquals(1, firstItems.size());
        assertEquals("自定义标题", firstItems.get(0).get("title"));
        assertTrue(((List<?>) first.get("skipped")).isEmpty());
        assertTrue(((List<?>) first.get("failed")).isEmpty());

        // 同一天同一题允许再次添加（一天可复习多次）
        Map<String, Object> second = service.createGroup(createBody(
                "2026-07-19",
                "第二批",
                "manual",
                List.of(
                        item("question", questionId, "再练一遍", ""),
                        item("question", 999999L, "", ""),
                        item("question", questionId2, "", ""))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondItems = (List<Map<String, Object>>) second.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) second.get("skipped");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) second.get("failed");
        assertEquals(2, secondItems.size());
        assertEquals(questionId, ((Number) secondItems.get(0).get("resourceId")).longValue());
        assertEquals("再练一遍", secondItems.get(0).get("title"));
        assertEquals(questionId2, ((Number) secondItems.get(1).get("resourceId")).longValue());
        assertTrue(skipped.isEmpty());
        assertEquals(1, failed.size());
        assertEquals(999999L, ((Number) failed.get(0).get("resourceId")).longValue());
        assertNotNull(failed.get(0).get("reason"));
    }

    @Test
    void createGroup_allowsDuplicateWithinSameBatch() {
        Map<String, Object> result = service.createGroup(createBody(
                "2026-07-19",
                "同批重复",
                "manual",
                List.of(
                        item("question", questionId, "第一份", ""),
                        item("question", questionId, "第二份", ""))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) result.get("skipped");
        assertEquals(2, items.size());
        assertEquals(questionId, ((Number) items.get(0).get("resourceId")).longValue());
        assertEquals("第一份", items.get(0).get("title"));
        assertEquals(questionId, ((Number) items.get(1).get("resourceId")).longValue());
        assertEquals("第二份", items.get(1).get("title"));
        assertTrue(skipped.isEmpty());
    }

    @Test
    void createGroup_allFailed_throws() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createGroup(createBody(
                        "2026-07-19",
                        "全失败",
                        "manual",
                        List.of(item("question", 888888L, "", "")))));
        assertEquals("没有可添加的计划条目", error.getMessage());
    }

    @Test
    void recommend_quizUsesWrongIds() {
        Map<String, Object> result = service.recommend(Map.of(
                "sessionType", "quiz",
                "wrongQuestionIds", List.of(questionId, questionId2),
                "answered", List.of(Map.of("questionId", questionId, "isCorrect", false))));
        assertEquals("本轮刷题错题", result.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertEquals(2, candidates.size());
        assertEquals("question", candidates.get(0).get("resourceType"));
        assertEquals(questionId, ((Number) candidates.get(0).get("resourceId")).longValue());
        assertTrue(String.valueOf(candidates.get(0).get("title")).contains("题干"));
    }

    @Test
    void recommend_memorizeUsesReviewAgain() {
        Map<String, Object> result = service.recommend(Map.of(
                "sessionType", "memorize",
                "reviewAgainIds", List.of(questionId2)));
        assertEquals("本轮需再看", result.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals(questionId2, ((Number) candidates.get(0).get("resourceId")).longValue());
        assertEquals("题干乙", candidates.get(0).get("title"));
    }

    @Test
    void recommend_fallbackWrongBook() {
        questions.recordAnswer(questionId, "x", false, 1, "s1", "deterministic", null);
        questions.recordAnswer(questionId2, "y", true, 1, "s2", "deterministic", null);
        Map<String, Object> result = service.recommend(Map.of());
        assertEquals("错题本推荐", result.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals(questionId, ((Number) candidates.get(0).get("resourceId")).longValue());
        assertEquals("question", candidates.get(0).get("resourceType"));
    }

    @Test
    void deleteItem_removesEmptyGroup() {
        Map<String, Object> created = service.createGroup(createBody(
                "2026-07-20",
                "单条",
                "manual",
                List.of(item("question", questionId, "t", ""))));
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) created.get("group");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) created.get("items");
        long groupId = ((Number) group.get("id")).longValue();
        long itemId = ((Number) items.get(0).get("id")).longValue();

        service.deleteItem(itemId);
        assertThrows(EmptyResultDataAccessException.class, () -> plans.findItem(itemId));
        assertThrows(EmptyResultDataAccessException.class, () -> plans.findGroup(groupId));
    }

    @Test
    void updateGroup_syncsTodoDates() {
        Map<String, Object> created = service.createGroup(createBody(
                "2026-07-19",
                "组",
                "manual",
                List.of(
                        item("question", questionId, "a", ""),
                        item("question", questionId2, "b", ""))));
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) created.get("group");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) created.get("items");
        long groupId = ((Number) group.get("id")).longValue();
        long todoId = ((Number) items.get(0).get("id")).longValue();
        long doneId = ((Number) items.get(1).get("id")).longValue();
        service.updateItem(doneId, Map.of("status", "done"));

        Map<String, Object> updated = service.updateGroup(groupId, Map.of(
                "title", "新标题",
                "planDate", "2026-07-22"));
        assertEquals("新标题", updated.get("title"));
        assertEquals("2026-07-22", updated.get("planDate"));
        assertEquals("2026-07-22", plans.findItem(todoId).get("planDate"));
        assertEquals("2026-07-19", plans.findItem(doneId).get("planDate"));
    }

    @Test
    void listDay_marksResourceMissing() {
        Map<String, Object> created = service.createGroup(createBody(
                "2026-07-21",
                "含失效",
                "manual",
                List.of(
                        item("question", questionId, "活", ""),
                        item("knowledge_point", pointId, "点", ""),
                        item("note_page", pageId, "页", ""))));
        questions.delete(questionId);

        Map<String, Object> day = service.listDay("2026-07-21");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) day.get("days");
        assertEquals(1, days.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) days.get(0).get("groups");
        assertEquals(1, groups.size());
        assertEquals(0, groups.get(0).get("doneCount"));
        assertEquals(3, groups.get(0).get("totalCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) groups.get(0).get("items");
        Map<String, Boolean> missingByKey = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String key = item.get("resourceType") + ":" + ((Number) item.get("resourceId")).longValue();
            missingByKey.put(key, Boolean.TRUE.equals(item.get("resourceMissing")));
        }
        assertTrue(missingByKey.get("question:" + questionId));
        assertFalse(missingByKey.get("knowledge_point:" + pointId));
        assertFalse(missingByKey.get("note_page:" + pageId));
    }

    @Test
    void completeByResources_marksMatchingTodos() {
        service.createGroup(createBody(
                "2026-07-19",
                "待学",
                "manual",
                List.of(
                        item("question", questionId, "甲", ""),
                        item("question", questionId2, "乙", ""),
                        item("knowledge_point", pointId, "知", ""))));

        Map<String, Object> result = service.completeByResources(Map.of(
                "resourceType", "question",
                "resourceIds", List.of(questionId),
                "planDate", "2026-07-19"));
        assertEquals(1, ((Number) result.get("updated")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> day = service.listDay("2026-07-19");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) day.get("days");
        assertEquals(1, days.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) days.get(0).get("groups");
        assertEquals(1, groups.get(0).get("doneCount"));
        assertEquals(3, groups.get(0).get("totalCount"));

        // second complete same resource is no-op
        Map<String, Object> again = service.completeByResources(Map.of(
                "resourceType", "question",
                "resourceIds", List.of(questionId),
                "planDate", "2026-07-19"));
        assertEquals(0, ((Number) again.get("updated")).intValue());

        Map<String, Object> kp = service.completeByResources(Map.of(
                "resourceType", "knowledge_point",
                "resourceIds", List.of(pointId)));
        assertEquals(1, ((Number) kp.get("updated")).intValue());
    }

    @Test
    void aiSchedule_withoutAiFallsBackToRule() {
        Map<String, Object> result = service.aiSchedule(Map.of(
                "sessionType", "quiz",
                "wrongQuestionIds", List.of(questionId, questionId2),
                "startDate", "2026-07-20"));
        assertEquals("rule_fallback", result.get("mode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        assertEquals(1, groups.size());
        assertEquals("2026-07-20", groups.get(0).get("planDate"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) groups.get(0).get("items");
        assertEquals(2, items.size());

        Map<String, Object> applied = service.applySchedule(Map.of("groups", groups));
        assertEquals(1, ((Number) applied.get("createdGroups")).intValue());
        assertEquals(2, ((Number) applied.get("createdItems")).intValue());
    }

    @Test
    void aiSchedule_acceptsExplicitCandidates() {
        Map<String, Object> result = service.aiSchedule(Map.of(
                "startDate", "2026-07-21",
                "defaultTitle", "手动候选",
                "candidates", List.of(
                        Map.of("resourceType", "question", "resourceId", questionId, "title", "甲"),
                        Map.of("resourceType", "knowledge_point", "resourceId", pointId, "title", "知"))));
        assertEquals("rule_fallback", result.get("mode")); // ai null in unit test
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        assertEquals(1, groups.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) groups.get(0).get("items");
        assertEquals(2, items.size());
    }

    @Test
    void aiSchedule_window_defaultFiveDaysWithoutEnd() {
        Map<String, Object> result = service.aiSchedule(Map.of(
                "startDate", "2026-07-20",
                "candidates", List.of(
                        Map.of("resourceType", "question", "resourceId", questionId, "title", "甲"))));
        assertEquals(5, ((Number) result.get("spanDays")).intValue());
        assertEquals("2026-07-24", result.get("endDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> window = (Map<String, Object>) result.get("window");
        assertEquals(false, window.get("endDateProvided"));
        assertEquals(5, ((Number) window.get("defaultSpanDays")).intValue());
    }

    @Test
    void aiSchedule_window_respectsUserEndWithoutClamp() {
        Map<String, Object> result = service.aiSchedule(Map.of(
                "startDate", "2026-07-01",
                "endDate", "2026-07-20",
                "candidates", List.of(
                        Map.of("resourceType", "question", "resourceId", questionId, "title", "甲"))));
        assertEquals(20, ((Number) result.get("spanDays")).intValue());
        assertEquals("2026-07-20", result.get("endDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> window = (Map<String, Object>) result.get("window");
        assertEquals(true, window.get("endDateProvided"));
    }

    @Test
    void aiSchedule_window_rejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class, () -> service.aiSchedule(Map.of(
                "startDate", "2026-07-20",
                "endDate", "2026-07-19",
                "candidates", List.of(
                        Map.of("resourceType", "question", "resourceId", questionId, "title", "甲")))));
    }

    private static Map<String, Object> createBody(String planDate, String title, String source, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planDate", planDate);
        body.put("title", title);
        body.put("note", "");
        body.put("source", source);
        body.put("items", items);
        return body;
    }

    private static Map<String, Object> item(String resourceType, long resourceId, String title, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resourceType", resourceType);
        item.put("resourceId", resourceId);
        item.put("title", title);
        item.put("note", note);
        return item;
    }
}
