package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.KnowledgePointRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KnowledgePointRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.RowMapper<KnowledgePointRecord> rowMapper;

    public KnowledgePointRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (result, row) -> KnowledgePointRecord.from(result, mapper);
    }

    public List<KnowledgePointRecord> findAll(Long bankId) {
        if (bankId == null) return jdbc.query("SELECT * FROM knowledge_point ORDER BY COALESCE(category, ''), title, id", rowMapper);
        return jdbc.query("SELECT * FROM knowledge_point WHERE bank_id = ? ORDER BY COALESCE(category, ''), title, id", rowMapper, bankId);
    }

    public KnowledgePointRecord findById(long id) {
        return jdbc.queryForObject("SELECT * FROM knowledge_point WHERE id = ?", rowMapper, id);
    }

    public List<Long> questionIds(long id) {
        return jdbc.query("SELECT question_id FROM knowledge_point_question WHERE knowledge_point_id = ? ORDER BY question_id", (result, row) -> result.getLong(1), id);
    }

    @Transactional
    public long insert(Long bankId, String title, String content, String category, List<String> tags, List<Long> questionIds) throws JsonProcessingException {
        List<Long> validatedQuestionIds = validateQuestionIds(bankId, questionIds);
        KeyHolder holder = new GeneratedKeyHolder();
        String tagsJson = mapper.writeValueAsString(tags == null ? List.of() : tags);
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO knowledge_point(bank_id, title, content, category, tags) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            if (bankId == null) statement.setObject(1, null); else statement.setLong(1, bankId);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.setString(4, category);
            statement.setString(5, tagsJson);
            return statement;
        }, holder);
        Number key = holder.getKey();
        long id = key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
        replaceQuestions(id, validatedQuestionIds);
        return id;
    }

    @Transactional
    public void update(long id, String title, String content, String category, List<String> tags, List<Long> questionIds) throws JsonProcessingException {
        List<Long> validatedQuestionIds = validateQuestionIds(findById(id).bankId, questionIds);
        jdbc.update("UPDATE knowledge_point SET title = ?, content = ?, category = ?, tags = ?, updated_at = datetime('now') WHERE id = ?", title, content, category, mapper.writeValueAsString(tags == null ? List.of() : tags), id);
        replaceQuestions(id, validatedQuestionIds);
    }

    @Transactional
    public void delete(long id) {
        jdbc.update("DELETE FROM knowledge_point_question WHERE knowledge_point_id = ?", id);
        jdbc.update("DELETE FROM knowledge_point WHERE id = ?", id);
    }

    private void replaceQuestions(long id, List<Long> questionIds) {
        jdbc.update("DELETE FROM knowledge_point_question WHERE knowledge_point_id = ?", id);
        for (Long questionId : questionIds == null ? Collections.<Long>emptyList() : questionIds) {
            jdbc.update("INSERT OR IGNORE INTO knowledge_point_question(knowledge_point_id, question_id) VALUES (?, ?)", id, questionId);
        }
    }

    private List<Long> validateQuestionIds(Long bankId, List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return List.of();
        Set<Long> unique = new LinkedHashSet<>();
        for (Long questionId : questionIds) {
            if (questionId == null || questionId <= 0) throw new IllegalArgumentException("关联题目无效");
            unique.add(questionId);
        }
        Set<Long> matched = new LinkedHashSet<>();
        List<Long> ordered = new ArrayList<>(unique);
        for (int start = 0; start < ordered.size(); start += 500) {
            List<Long> batch = ordered.subList(start, Math.min(start + 500, ordered.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            List<Object> arguments = new ArrayList<>(batch);
            String sql = "SELECT id FROM question WHERE id IN (" + placeholders + ")";
            if (bankId != null) {
                sql += " AND bank_id = ?";
                arguments.add(bankId);
            }
            matched.addAll(jdbc.query(sql, (result, row) -> result.getLong(1), arguments.toArray()));
        }
        if (matched.size() != unique.size()) throw new IllegalArgumentException("关联题目不存在或不属于当前题库");
        return ordered;
    }
}
