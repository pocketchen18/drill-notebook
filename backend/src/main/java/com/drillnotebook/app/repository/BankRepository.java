package com.drillnotebook.app.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BankRepository {
    private final JdbcTemplate jdbc;

    public BankRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findAll() {
        return jdbc.query("""
                SELECT b.id, b.name, b.description, b.source_type, b.created_at, COUNT(q.id) AS question_count
                FROM question_bank b LEFT JOIN question q ON q.bank_id = b.id
                GROUP BY b.id ORDER BY b.created_at, b.id
                """, (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id")); item.put("name", result.getString("name")); item.put("description", result.getString("description"));
            item.put("sourceType", result.getString("source_type")); item.put("createdAt", result.getString("created_at")); item.put("questionCount", result.getLong("question_count"));
            return item;
        });
    }

    public Map<String, Object> find(long id) {
        return jdbc.queryForObject("SELECT id, name, description, source_type, created_at FROM question_bank WHERE id = ?", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("id", result.getLong("id")); item.put("name", result.getString("name"));
            item.put("description", result.getString("description")); item.put("sourceType", result.getString("source_type")); item.put("createdAt", result.getString("created_at")); return item;
        }, id);
    }

    public long insert(String name, String description, String sourceType) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO question_bank(name, description, source_type) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setString(3, sourceType);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void update(long id, String name, String description, String sourceType) {
        jdbc.update("UPDATE question_bank SET name = COALESCE(?, name), description = COALESCE(?, description), source_type = COALESCE(?, source_type) WHERE id = ?", name, description, sourceType, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM answer_record WHERE question_id IN (SELECT id FROM question WHERE bank_id = ?)", id);
        jdbc.update("DELETE FROM question WHERE bank_id = ?", id);
        jdbc.update("DELETE FROM question_bank WHERE id = ?", id);
        try { jdbc.update("INSERT INTO question_fts(question_fts) VALUES ('rebuild')"); } catch (Exception ignored) { }
    }
}
