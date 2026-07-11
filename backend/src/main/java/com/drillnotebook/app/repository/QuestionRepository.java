package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.QuestionRecord;
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
        String type = body.get("type") == null ? current.type : String.valueOf(body.get("type"));
        String stem = body.get("stem") == null ? current.stem : String.valueOf(body.get("stem"));
        String answer = body.get("answer") == null ? current.answer : String.valueOf(body.get("answer"));
        String analysis = body.get("analysis") == null ? current.analysis : String.valueOf(body.get("analysis"));
        String options = body.get("options") == null ? mapper.writeValueAsString(current.options) : mapper.writeValueAsString(body.get("options"));
        jdbc.update("UPDATE question SET type = ?, stem = ?, options = ?, answer = ?, analysis = ? WHERE id = ?", type, stem, options, answer, analysis, id);
        rebuildFts();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM answer_record WHERE question_id = ?", id);
        jdbc.update("DELETE FROM question WHERE id = ?", id);
        rebuildFts();
    }

    public void recordAnswer(long questionId, String userAnswer, boolean correct, int timeSpent, String sessionId) {
        jdbc.update("INSERT INTO answer_record(question_id, user_answer, is_correct, time_spent, session_id) VALUES (?, ?, ?, ?, ?)", questionId, userAnswer, correct ? 1 : 0, timeSpent, sessionId);
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
        return jdbc.query("SELECT question_id, user_answer, is_correct, time_spent, answered_at FROM answer_record WHERE session_id = ? ORDER BY id", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("questionId", result.getLong("question_id")); item.put("userAnswer", result.getString("user_answer"));
            item.put("isCorrect", result.getInt("is_correct")); item.put("timeSpent", result.getInt("time_spent")); item.put("answeredAt", result.getString("answered_at")); return item;
        }, sessionId);
    }

    public void rebuildFts() {
        try { jdbc.update("INSERT INTO question_fts(question_fts) VALUES ('rebuild')"); } catch (Exception ignored) { /* SQLite builds without FTS5 remain usable. */ }
    }

    public String optionsJson(List<Map<String, String>> options) throws JsonProcessingException { return mapper.writeValueAsString(options); }
    public String tagsJson(List<String> tags) throws JsonProcessingException { return mapper.writeValueAsString(tags); }
}
