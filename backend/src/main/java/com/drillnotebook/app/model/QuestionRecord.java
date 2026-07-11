package com.drillnotebook.app.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QuestionRecord {
    public long id;
    public long bankId;
    public String type;
    public String stem;
    public List<Map<String, String>> options = new ArrayList<>();
    public String answer;
    public String analysis;
    public Integer difficulty;
    public List<String> tags = new ArrayList<>();
    public String chapter;
    public String groupId;
    public Integer orderInGroup;
    public String contentHash;

    public static QuestionRecord from(ResultSet result, ObjectMapper mapper) throws SQLException {
        QuestionRecord question = new QuestionRecord();
        question.id = result.getLong("id");
        question.bankId = result.getLong("bank_id");
        question.type = result.getString("type");
        question.stem = result.getString("stem");
        question.answer = result.getString("answer");
        question.analysis = result.getString("analysis");
        Object difficulty = result.getObject("difficulty");
        question.difficulty = difficulty == null ? null : ((Number) difficulty).intValue();
        question.chapter = result.getString("chapter");
        question.groupId = result.getString("group_id");
        Object order = result.getObject("order_in_group");
        question.orderInGroup = order == null ? null : ((Number) order).intValue();
        question.contentHash = result.getString("content_hash");
        try {
            String optionsJson = result.getString("options");
            if (optionsJson != null && !optionsJson.isBlank()) question.options = mapper.readValue(optionsJson, new TypeReference<>() {});
            String tagsJson = result.getString("tags");
            if (tagsJson != null && !tagsJson.isBlank()) question.tags = mapper.readValue(tagsJson, new TypeReference<>() {});
        } catch (Exception error) {
            throw new SQLException("Invalid question JSON", error);
        }
        return question;
    }

    public Map<String, Object> toMap(boolean includeAnswer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("bankId", bankId);
        result.put("type", type);
        result.put("stem", stem);
        result.put("options", options);
        if (includeAnswer) {
            result.put("answer", answer);
            result.put("analysis", analysis);
        }
        result.put("difficulty", difficulty);
        result.put("tags", tags);
        result.put("chapter", chapter);
        result.put("groupId", groupId);
        result.put("orderInGroup", orderInGroup);
        result.put("contentHash", contentHash);
        return result;
    }

    public Map<String, Object> snapshot() { return toMap(true); }

    public static List<Map<String, String>> optionsFromJson(JsonNode options) {
        List<Map<String, String>> result = new ArrayList<>();
        if (options == null || !options.isArray()) return result;
        for (JsonNode option : options) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("key", option.path("key").asText());
            item.put("text", option.path("text").asText());
            result.add(item);
        }
        return result;
    }
}
