package com.drillnotebook.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReviewLog {
    public long id;
    public long scheduleId;
    public int quality;
    public Integer responseTime;
    public Double scheduledInterval;
    public Double actualInterval;
    public String source;
    public String reviewedAt;

    public static ReviewLog from(ResultSet result, ObjectMapper mapper) throws SQLException {
        ReviewLog log = new ReviewLog();
        log.id = result.getLong("id");
        log.scheduleId = result.getLong("schedule_id");
        log.quality = result.getInt("quality");
        Object responseTime = result.getObject("response_time");
        log.responseTime = responseTime == null ? null : ((Number) responseTime).intValue();
        Object scheduledInterval = result.getObject("scheduled_interval");
        log.scheduledInterval = scheduledInterval == null ? null : ((Number) scheduledInterval).doubleValue();
        Object actualInterval = result.getObject("actual_interval");
        log.actualInterval = actualInterval == null ? null : ((Number) actualInterval).doubleValue();
        log.source = result.getString("source");
        log.reviewedAt = result.getString("reviewed_at");
        return log;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("scheduleId", scheduleId);
        result.put("quality", quality);
        result.put("responseTime", responseTime);
        result.put("scheduledInterval", scheduledInterval);
        result.put("actualInterval", actualInterval);
        result.put("source", source);
        result.put("reviewedAt", reviewedAt);
        return result;
    }
}
