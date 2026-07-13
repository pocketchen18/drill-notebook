package com.drillnotebook.app.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgePointRecord {
    public long id;
    public Long bankId;
    public String title;
    public String content;
    public String category;
    public List<String> tags = new ArrayList<>();
    public String createdAt;
    public String updatedAt;

    public static KnowledgePointRecord from(ResultSet result, ObjectMapper mapper) throws SQLException {
        KnowledgePointRecord point = new KnowledgePointRecord();
        point.id = result.getLong("id");
        Object bank = result.getObject("bank_id");
        point.bankId = bank == null ? null : ((Number) bank).longValue();
        point.title = result.getString("title");
        point.content = result.getString("content");
        point.category = result.getString("category");
        point.createdAt = result.getString("created_at");
        point.updatedAt = result.getString("updated_at");
        try {
            String tagsJson = result.getString("tags");
            if (tagsJson != null && !tagsJson.isBlank()) point.tags = mapper.readValue(tagsJson, new TypeReference<>() {});
        } catch (Exception error) {
            throw new SQLException("Invalid knowledge-point tags JSON", error);
        }
        return point;
    }

    public Map<String, Object> toMap(List<Long> questionIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("bankId", bankId);
        result.put("title", title);
        result.put("content", content);
        result.put("category", category);
        result.put("tags", tags);
        result.put("questionIds", questionIds);
        result.put("createdAt", createdAt);
        result.put("updatedAt", updatedAt);
        return result;
    }
}
