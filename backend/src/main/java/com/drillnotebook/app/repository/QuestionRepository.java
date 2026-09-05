package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.service.QuestionTypeRules;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {
    /** 最近一次答错后需连续答对满此次数，才自动移出错题本（跨会话累计；避开「重复到对」使最新态恒为对的陷阱）。 */
    private static final int WRONG_MASTER_STREAK = 2;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.RowMapper<QuestionRecord> rowMapper;

    public QuestionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (result, row) -> QuestionRecord.from(result, mapper);
    }

    public List<QuestionRecord> findByBank(long bankId) {
        return jdbc.query("SELECT * FROM question WHERE bank_id = ? ORDER BY COALESCE(order_in_group, 999999), id", rowMapper, bankId);
    }

    public List<QuestionRecord> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String placeholders = ids.stream().map((ignored) -> "?").collect(Collectors.joining(","));
        return jdbc.query("SELECT * FROM question WHERE id IN (" + placeholders + ")", rowMapper, ids.toArray());
    }

    public QuestionRecord findById(long id) {
        return jdbc.queryForObject("SELECT * FROM question WHERE id = ?", rowMapper, id);
    }

    public QuestionRecord findByHash(long bankId, String hash) {
        List<QuestionRecord> results = jdbc.query("SELECT * FROM question WHERE bank_id = ? AND content_hash = ?", rowMapper, bankId, hash);
        return results.isEmpty() ? null : results.get(0);
    }

    public long insert(long bankId, String type, String stem, String options, String answer, String analysis, int difficulty, String tags, String chapter, String groupId, Integer orderInGroup, String contentHash) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO question(bank_id, type, stem, options, answer, analysis, difficulty, tags, chapter, group_id, order_in_group, content_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, bankId); statement.setString(2, type); statement.setString(3, stem); statement.setString(4, options);
            statement.setString(5, answer); statement.setString(6, analysis); statement.setInt(7, difficulty); statement.setString(8, tags);
            statement.setString(9, chapter); statement.setString(10, groupId); if (orderInGroup == null) statement.setObject(11, null); else statement.setInt(11, orderInGroup);
            statement.setString(12, contentHash);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void update(long id, Map<String, Object> body) throws JsonProcessingException {
        QuestionRecord current = findById(id);
        String type = QuestionTypeRules.requireType(body.get("type") == null ? current.type : String.valueOf(body.get("type")));
        String stem = body.get("stem") == null ? current.stem : String.valueOf(body.get("stem")).trim();
        if (stem == null || stem.isBlank()) throw new IllegalArgumentException("题干不能为空");
        String answer = QuestionTypeRules.canonicalAnswer(type, body.get("answer") == null ? current.answer : String.valueOf(body.get("answer")));
        String analysis = body.get("analysis") == null ? current.analysis : String.valueOf(body.get("analysis"));
        List<Map<String, String>> optionValues = body.get("options") == null ? current.options : mapper.convertValue(body.get("options"), new TypeReference<>() {});
        QuestionTypeRules.validate(type, answer, optionValues);
        String options = mapper.writeValueAsString(optionValues);
        int difficulty = difficulty(body.get("difficulty"), current.difficulty == null ? 3 : current.difficulty);
        String tags = body.get("tags") == null ? mapper.writeValueAsString(current.tags) : mapper.writeValueAsString(body.get("tags"));
        String chapter = body.containsKey("chapter") ? text(body.get("chapter")) : current.chapter;
        String groupId = body.containsKey("groupId") ? text(body.get("groupId")) : current.groupId;
        Integer orderInGroup = body.containsKey("orderInGroup") ? integer(body.get("orderInGroup")) : current.orderInGroup;
        jdbc.update("UPDATE question SET type = ?, stem = ?, options = ?, answer = ?, analysis = ?, difficulty = ?, tags = ?, chapter = ?, group_id = ?, order_in_group = ? WHERE id = ?", type, stem, options, answer, analysis, difficulty, tags, chapter, groupId, orderInGroup, id);
        rebuildFts();
    }

    private static String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private static Integer integer(Object value) { try { return value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException error) { return null; } }
    private static int difficulty(Object raw, int fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) return fallback;
        final int value;
        try { value = Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("难度必须是 1 到 5 的整数"); }
        if (value < 1 || value > 5) throw new IllegalArgumentException("难度必须是 1 到 5 的整数");
        return value;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM answer_record WHERE question_id = ?", id);
        // Memory-curve schedules for this question (logs cascade via schedule FK)
        jdbc.update(
                "DELETE FROM review_log WHERE schedule_id IN (SELECT id FROM review_schedule WHERE item_type = 'question' AND item_id = ?)",
                id);
        jdbc.update("DELETE FROM review_schedule WHERE item_type = 'question' AND item_id = ?", id);
        // Calendar plan items + empty groups
        List<Long> groupIds = jdbc.query(
                "SELECT DISTINCT group_id FROM study_plan_item WHERE resource_type = 'question' AND resource_id = ?",
                (rs, row) -> rs.getLong(1),
                id);
        jdbc.update("DELETE FROM study_plan_item WHERE resource_type = 'question' AND resource_id = ?", id);
        for (Long groupId : groupIds) {
            if (groupId == null) continue;
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM study_plan_item WHERE group_id = ?", Integer.class, groupId);
            if (count != null && count == 0) {
                jdbc.update("DELETE FROM study_plan_group WHERE id = ?", groupId);
            }
        }
        jdbc.update("DELETE FROM knowledge_point_question WHERE question_id = ?", id);
        jdbc.update("DELETE FROM question WHERE id = ?", id);
        rebuildFts();
    }

    public void recordAnswer(long questionId, String userAnswer, Boolean correct, int timeSpent, String sessionId, String gradingStatus, Map<String, Object> grading) {
        String gradingJson;
        try { gradingJson = grading == null ? null : mapper.writeValueAsString(grading); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("判题结果无法保存"); }
        jdbc.update("INSERT INTO answer_record(question_id, user_answer, is_correct, time_spent, session_id, grading_status, grading_json) VALUES (?, ?, ?, ?, ?, ?, ?)", questionId, userAnswer, correct == null ? null : (correct ? 1 : 0), timeSpent, sessionId, gradingStatus, gradingJson);
        // 再次答错：撤销手动「移出错题本」，让该题重新计入错题本（错题库应反映真实薄弱点）。
        if (correct != null && !correct) {
            jdbc.update("UPDATE question SET wrong_excluded = 0 WHERE id = ?", questionId);
        }
    }

    /**
     * 错题本成员规则（单一真相源）：
     * 1) 累计答错过（wrongCount > 0）；
     * 2) 未被手动移出（wrong_excluded = 0）；
     * 3) 最近一次答错之后尚未连续答对满 {@link #WRONG_MASTER_STREAK} 次。
     * 排序：错误次数多者优先，其次最近答错时间。
     */
    public List<QuestionRecord> wrongQuestions() {
        return jdbc.query("""
                SELECT q.* FROM question q
                JOIN (
                    SELECT question_id,
                           SUM(CASE WHEN is_correct = 0 THEN 1 ELSE 0 END) AS wrong_count,
                           MAX(CASE WHEN is_correct = 0 THEN id END) AS last_wrong_id,
                           MAX(CASE WHEN is_correct = 0 THEN answered_at END) AS last_wrong_at
                    FROM answer_record GROUP BY question_id
                ) agg ON agg.question_id = q.id
                WHERE agg.wrong_count > 0
                  AND q.wrong_excluded = 0
                   AND (SELECT COUNT(*) FROM answer_record c
                          WHERE c.question_id = q.id AND c.is_correct = 1 AND c.id > agg.last_wrong_id) < """ + WRONG_MASTER_STREAK + """
                 ORDER BY agg.wrong_count DESC, agg.last_wrong_at DESC
                """, rowMapper);
    }

    /** 错题本视图：在成员规则基础上附带错误次数 / 作答次数 / 错误率，供前端展示与排序。 */
    public List<Map<String, Object>> wrongBook() {
        List<QuestionRecord> list = wrongQuestions();
        if (list.isEmpty()) return List.of();
        Map<Long, Map<String, Object>> stats = answerStats(list.stream().map((question) -> question.id).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuestionRecord question : list) {
            Map<String, Object> row = question.toMap(true);
            Map<String, Object> stat = stats.getOrDefault(question.id, Map.of());
            int wrongCount = ((Number) stat.getOrDefault("wrongCount", 0)).intValue();
            int attemptCount = ((Number) stat.getOrDefault("attemptCount", 0)).intValue();
            row.put("wrongCount", wrongCount);
            row.put("attemptCount", attemptCount);
            row.put("errorRate", attemptCount > 0 ? Math.round(wrongCount * 10000.0 / attemptCount) / 10000.0 : 0);
            rows.add(row);
        }
        return rows;
    }

    /** 手动移出/恢复错题本。答错会由 recordAnswer 自动重置为未移出。 */
    public void setWrongExcluded(long questionId, boolean excluded) {
        jdbc.update("UPDATE question SET wrong_excluded = ? WHERE id = ?", excluded ? 1 : 0, questionId);
    }

    /**
     * Aggregate answer stats for AI scheduling context.
     * Keys: attemptCount, wrongCount, correctCount, lastIsCorrect (Boolean|null), lastAnsweredAt
     */
    public Map<Long, Map<String, Object>> answerStats(List<Long> questionIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (questionIds == null || questionIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", Collections.nCopies(questionIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT question_id, "
                        + "COUNT(*) AS attempt_count, "
                        + "SUM(CASE WHEN is_correct = 0 THEN 1 ELSE 0 END) AS wrong_count, "
                        + "SUM(CASE WHEN is_correct = 1 THEN 1 ELSE 0 END) AS correct_count "
                        + "FROM answer_record WHERE question_id IN (" + placeholders + ") "
                        + "GROUP BY question_id",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("questionId", rs.getLong("question_id"));
                    row.put("attemptCount", rs.getInt("attempt_count"));
                    row.put("wrongCount", rs.getInt("wrong_count"));
                    row.put("correctCount", rs.getInt("correct_count"));
                    return row;
                },
                questionIds.toArray());
        for (Map<String, Object> row : rows) {
            long qid = ((Number) row.get("questionId")).longValue();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("attemptCount", row.get("attemptCount"));
            stats.put("wrongCount", row.get("wrongCount"));
            stats.put("correctCount", row.get("correctCount"));
            result.put(qid, stats);
        }
        List<Map<String, Object>> lasts = jdbc.query(
                "SELECT a.question_id, a.is_correct, a.answered_at FROM answer_record a "
                        + "WHERE a.id = (SELECT MAX(a2.id) FROM answer_record a2 WHERE a2.question_id = a.question_id) "
                        + "AND a.question_id IN (" + placeholders + ")",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("questionId", rs.getLong("question_id"));
                    Object raw = rs.getObject("is_correct");
                    row.put("lastIsCorrect", raw == null ? null : ((Number) raw).intValue() == 1);
                    row.put("lastAnsweredAt", rs.getString("answered_at"));
                    return row;
                },
                questionIds.toArray());
        for (Map<String, Object> row : lasts) {
            long qid = ((Number) row.get("questionId")).longValue();
            Map<String, Object> stats = result.computeIfAbsent(qid, id -> new LinkedHashMap<>());
            stats.put("lastIsCorrect", row.get("lastIsCorrect"));
            stats.put("lastAnsweredAt", row.get("lastAnsweredAt"));
        }
        return result;
    }

    public List<Map<String, Object>> sessionAnswers(String sessionId) {
        return jdbc.query("SELECT question_id, user_answer, is_correct, time_spent, grading_status, grading_json, answered_at FROM answer_record WHERE session_id = ? ORDER BY id", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("questionId", result.getLong("question_id")); item.put("userAnswer", result.getString("user_answer"));
            Object rawCorrect = result.getObject("is_correct");
            item.put("isCorrect", rawCorrect == null ? null : ((Number) rawCorrect).intValue() == 1);
            item.put("timeSpent", result.getInt("time_spent")); item.put("gradingStatus", result.getString("grading_status"));
            String gradingJson = result.getString("grading_json");
            if (gradingJson != null && !gradingJson.isBlank()) {
                try { item.put("grading", mapper.readValue(gradingJson, new TypeReference<Map<String, Object>>() {})); }
                catch (JsonProcessingException error) { item.put("grading", Map.of()); }
            }
            item.put("answeredAt", result.getString("answered_at")); return item;
        }, sessionId);
    }

    public void rebuildFts() {
        try { jdbc.update("INSERT INTO question_fts(question_fts) VALUES ('rebuild')"); } catch (Exception ignored) { /* SQLite builds without FTS5 remain usable. */ }
    }

    public String optionsJson(List<Map<String, String>> options) throws JsonProcessingException { return mapper.writeValueAsString(options); }
    public String tagsJson(List<String> tags) throws JsonProcessingException { return mapper.writeValueAsString(tags); }
}
