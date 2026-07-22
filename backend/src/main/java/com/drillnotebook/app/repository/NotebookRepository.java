package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.QuestionRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class NotebookRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotebookRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public List<Map<String, Object>> findAll() {
        return jdbc.query("SELECT id, title, created_at, updated_at FROM notebook ORDER BY created_at, id", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id")); item.put("title", result.getString("title")); item.put("createdAt", result.getString("created_at")); item.put("updatedAt", result.getString("updated_at"));
            return item;
        });
    }

    public Map<String, Object> find(long id) {
        return jdbc.queryForObject("SELECT id, title, created_at, updated_at FROM notebook WHERE id = ?", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id")); item.put("title", result.getString("title")); item.put("createdAt", result.getString("created_at")); item.put("updatedAt", result.getString("updated_at"));
            return item;
        }, id);
    }

    public long insert(String title) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO notebook(title) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title); return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void update(long id, String title) { jdbc.update("UPDATE notebook SET title = ?, updated_at = datetime('now') WHERE id = ?", title, id); }
    public void delete(long id) { jdbc.update("DELETE FROM notebook WHERE id = ?", id); }

    public List<Map<String, Object>> findPages(long notebookId) {
        return jdbc.query("SELECT id, notebook_id, parent_id, title, sort_order, updated_at FROM note_page WHERE notebook_id = ? ORDER BY sort_order, id", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id")); item.put("notebookId", result.getLong("notebook_id")); item.put("parentId", result.getObject("parent_id")); item.put("title", result.getString("title")); item.put("sortOrder", result.getInt("sort_order")); item.put("updatedAt", result.getString("updated_at"));
            return item;
        }, notebookId);
    }

    public Map<String, Object> findPage(long pageId) {
        return jdbc.queryForObject("SELECT id, notebook_id, parent_id, title, sort_order, content, created_at, updated_at FROM note_page WHERE id = ?", (result, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", result.getLong("id")); item.put("notebookId", result.getLong("notebook_id")); item.put("parentId", result.getObject("parent_id")); item.put("title", result.getString("title")); item.put("sortOrder", result.getInt("sort_order"));
            item.put("content", readContent(result.getString("content"))); item.put("createdAt", result.getString("created_at")); item.put("updatedAt", result.getString("updated_at"));
            return item;
        }, pageId);
    }

    private Map<String, Object> readContent(String raw) {
        if (raw == null || raw.isBlank()) return defaultDocument();
        try { return mapper.readValue(raw, new TypeReference<>() {}); } catch (Exception ignored) { return defaultDocument(); }
    }

    public long insertPage(long notebookId, String title, Object content) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO note_page(notebook_id, title, content) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, notebookId); statement.setString(2, title); statement.setString(3, content == null ? write(defaultDocument()) : write(content)); return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void updatePage(long pageId, String title, Object content) {
        if (title != null) jdbc.update("UPDATE note_page SET title = ?, updated_at = datetime('now') WHERE id = ?", title, pageId);
        if (content != null) jdbc.update("UPDATE note_page SET content = ?, updated_at = datetime('now') WHERE id = ?", write(content), pageId);
    }

    public void deletePage(long pageId) {
        List<Long> groupIds = jdbc.query(
                "SELECT DISTINCT group_id FROM study_plan_item WHERE resource_type = 'note_page' AND resource_id = ?",
                (rs, row) -> rs.getLong(1),
                pageId);
        jdbc.update("DELETE FROM study_plan_item WHERE resource_type = 'note_page' AND resource_id = ?", pageId);
        for (Long groupId : groupIds) {
            if (groupId == null) continue;
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM study_plan_item WHERE group_id = ?", Integer.class, groupId);
            if (count != null && count == 0) {
                jdbc.update("DELETE FROM study_plan_group WHERE id = ?", groupId);
            }
        }
        jdbc.update("DELETE FROM note_question_ref WHERE note_id = ?", pageId);
        jdbc.update("DELETE FROM note_page WHERE id = ?", pageId);
    }

    public Map<String, Object> addQuestionSnapshot(long pageId, QuestionRecord question) {
        Map<String, Object> page = findPage(pageId);
        String raw = write(page.get("content"));
        try {
            ObjectNode document = (ObjectNode) mapper.readTree(raw);
            ArrayNode content = document.withArray("content");
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM note_question_ref WHERE note_id = ? AND question_id = ?", Integer.class, pageId, question.id);
            if (existing == null || existing == 0) {
                ObjectNode block = mapper.createObjectNode();
                block.put("type", "questionBlock");
                ObjectNode attrs = mapper.createObjectNode(); attrs.put("questionId", question.id); attrs.set("snapshot", mapper.valueToTree(question.snapshot()));
                block.set("attrs", attrs); content.add(block);
            }
            jdbc.update("INSERT INTO note_question_ref(note_id, question_id, snapshot_json) VALUES (?, ?, ?) ON CONFLICT(note_id, question_id) DO UPDATE SET snapshot_json = excluded.snapshot_json", pageId, question.id, mapper.writeValueAsString(question.snapshot()));
            jdbc.update("UPDATE note_page SET content = ?, updated_at = datetime('now') WHERE id = ?", mapper.writeValueAsString(document), pageId);
            return findPage(pageId);
        } catch (Exception error) { throw new IllegalArgumentException("题块保存失败"); }
    }

    public static Map<String, Object> defaultDocument() {
        Map<String, Object> paragraph = new LinkedHashMap<>(); paragraph.put("type", "paragraph");
        Map<String, Object> document = new LinkedHashMap<>(); document.put("type", "doc"); document.put("content", new ArrayList<>(List.of(paragraph))); return document;
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception error) { throw new IllegalArgumentException("笔记内容无效"); }
    }
}
