package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.service.QuestionTypeRules;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
        jdbc.update("DELETE FROM question WHERE id = ?", id);
        rebuildFts();
    }

    public void recordAnswer(long questionId, String userAnswer, Boolean correct, int timeSpent, String sessionId, String gradingStatus, Map<String, Object> grading) {
        String gradingJson;
        try { gradingJson = grading == null ? null : mapper.writeValueAsString(grading); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("判题结果无法保存"); }
        jdbc.update("INSERT INTO answer_record(question_id, user_answer, is_correct, time_spent, session_id, grading_status, grading_json) VALUES (?, ?, ?, ?, ?, ?, ?)", questionId, userAnswer, correct == null ? null : (correct ? 1 : 0), timeSpent, sessionId, gradingStatus, gradingJson);
    }

    public List<QuestionRecord> wrongQuestions() {
        return jdbc.query("""
                SELECT q.* FROM question q
                JOIN answer_record a ON a.question_id = q.id
                WHERE a.id = (SELECT MAX(a2.id) FROM answer_record a2 WHERE a2.question_id = q.id)
                  AND a.is_correct = 0
                ORDER BY a.answered_at DESC
                """, rowMapper);
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
