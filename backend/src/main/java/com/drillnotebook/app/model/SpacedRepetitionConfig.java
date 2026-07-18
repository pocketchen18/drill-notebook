package com.drillnotebook.app.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public class SpacedRepetitionConfig {
    public long id;
    public String name;
    public boolean isDefault;
    public Map<String, Long> intervals = new LinkedHashMap<>();
    public double initialEf;
    public double minimumEf;
    public int maxIntervalDays;
    public String wrongStrategy;
    public double wrongFixedDays;
    public int dailyNewLimit;
    public int dailyReviewLimit;
    public String priorityMode;
    public String createdAt;

    public static SpacedRepetitionConfig from(ResultSet result, ObjectMapper mapper) throws SQLException {
        SpacedRepetitionConfig config = new SpacedRepetitionConfig();
        config.id = result.getLong("id");
        config.name = result.getString("name");
        config.isDefault = result.getInt("is_default") != 0;
        config.initialEf = result.getDouble("initial_ef");
        config.minimumEf = result.getDouble("minimum_ef");
        config.maxIntervalDays = result.getInt("max_interval_days");
        config.wrongStrategy = result.getString("wrong_strategy");
        config.wrongFixedDays = result.getDouble("wrong_fixed_days");
        config.dailyNewLimit = result.getInt("daily_new_limit");
        config.dailyReviewLimit = result.getInt("daily_review_limit");
        config.priorityMode = result.getString("priority_mode");
        config.createdAt = result.getString("created_at");
        try {
            String intervalsJson = result.getString("intervals_json");
            if (intervalsJson != null && !intervalsJson.isBlank()) {
                config.intervals = mapper.readValue(intervalsJson, new TypeReference<>() {});
            }
        } catch (Exception error) {
            throw new SQLException("Invalid intervals JSON", error);
        }
        return config;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("isDefault", isDefault);
        result.put("intervals", intervals);
        result.put("initialEf", initialEf);
        result.put("minimumEf", minimumEf);
        result.put("maxIntervalDays", maxIntervalDays);
        result.put("wrongStrategy", wrongStrategy);
        result.put("wrongFixedDays", wrongFixedDays);
        result.put("dailyNewLimit", dailyNewLimit);
        result.put("dailyReviewLimit", dailyReviewLimit);
        result.put("priorityMode", priorityMode);
        result.put("createdAt", createdAt);
        return result;
    }
}
