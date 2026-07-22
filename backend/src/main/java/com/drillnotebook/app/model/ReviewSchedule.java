package com.drillnotebook.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReviewSchedule {
    public long id;
    public String itemType;
    public long itemId;
    public Long configId;
    public double ef;
    public double interval;
    public int repetitions;
    public String nextReview;
    public String lastReview;
    public Integer lastQuality;
    public int totalReviews;
    public int totalWrong;
    public int streakCorrect;
    public String status;
    public String createdAt;
    public String updatedAt;

    public static ReviewSchedule from(ResultSet result, ObjectMapper mapper) throws SQLException {
        ReviewSchedule schedule = new ReviewSchedule();
        schedule.id = result.getLong("id");
        schedule.itemType = result.getString("item_type");
        schedule.itemId = result.getLong("item_id");
        Object configObj = result.getObject("config_id");
        schedule.configId = configObj == null ? null : ((Number) configObj).longValue();
        schedule.ef = result.getDouble("ef");
        schedule.interval = result.getDouble("interval");
        schedule.repetitions = result.getInt("repetitions");
        schedule.nextReview = result.getString("next_review");
        schedule.lastReview = result.getString("last_review");
        Object lastQuality = result.getObject("last_quality");
        schedule.lastQuality = lastQuality == null ? null : ((Number) lastQuality).intValue();
        schedule.totalReviews = result.getInt("total_reviews");
        schedule.totalWrong = result.getInt("total_wrong");
        schedule.streakCorrect = result.getInt("streak_correct");
        schedule.status = result.getString("status");
        schedule.createdAt = result.getString("created_at");
        schedule.updatedAt = result.getString("updated_at");
        return schedule;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("itemType", itemType);
        result.put("itemId", itemId);
        result.put("configId", configId);
        result.put("ef", ef);
        result.put("interval", interval);
        result.put("repetitions", repetitions);
        result.put("nextReview", nextReview);
        result.put("lastReview", lastReview);
        result.put("lastQuality", lastQuality);
        result.put("totalReviews", totalReviews);
        result.put("totalWrong", totalWrong);
        result.put("streakCorrect", streakCorrect);
        result.put("status", status);
        result.put("createdAt", createdAt);
        result.put("updatedAt", updatedAt);
        return result;
    }
}
