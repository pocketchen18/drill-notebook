package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.ReviewLog;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.drillnotebook.app.service.ReviewService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService review;

    public ReviewController(ReviewService review) { this.review = review; }

    @PostMapping("/enroll")
    public List<Map<String, Object>> enroll(@RequestBody Map<String, Object> body) {
        String itemType = (String) body.get("itemType");
        List<?> rawIds = (List<?>) body.get("itemIds");
        List<Long> itemIds = rawIds.stream()
                .map(id -> id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString()))
                .collect(Collectors.toList());
        Object configIdObj = body.get("configId");
        Long configId = configIdObj instanceof Number ? ((Number) configIdObj).longValue() : null;
        return review.enroll(itemType, itemIds, configId);
    }

    @PostMapping("/unenroll")
    public Map<String, Object> unenroll(@RequestBody Map<String, Object> body) {
        String itemType = (String) body.get("itemType");
        List<?> rawIds = (List<?>) body.get("itemIds");
        List<Long> itemIds = rawIds.stream()
                .map(id -> id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString()))
                .collect(Collectors.toList());
        review.unenroll(itemType, itemIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    @GetMapping("/due")
    public List<Map<String, Object>> due(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long configId,
            @RequestParam(defaultValue = "0") int newLimit,
            @RequestParam(defaultValue = "0") int reviewLimit,
            @RequestParam(required = false) String priority) {
        return review.getDueItems(type, configId, newLimit, reviewLimit, priority);
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        long scheduleId = ((Number) body.get("scheduleId")).longValue();
        int quality = ((Number) body.get("quality")).intValue();
        Object rt = body.get("responseTime");
        Integer responseTime = rt instanceof Number ? ((Number) rt).intValue() : null;
        Object src = body.get("source");
        String source = src != null ? src.toString() : "manual";
        return review.submit(scheduleId, quality, responseTime, source);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long configId) {
        return review.getStats(type, configId);
    }

    /**
     * Calendar overlay: per-day SRS due counts + overdue counts for a date range.
     * Used by the calendar view to mark days with memory-curve due items and red
     * overdue indicators.
     */
    @GetMapping("/calendar-stats")
    public Map<String, Object> calendarStats(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) Long configId) {
        return review.calendarStats(from, to, configId);
    }

    @GetMapping("/schedule/{type}/{id}")
    public Map<String, Object> getSchedule(@PathVariable String type, @PathVariable long id) {
        ReviewSchedule schedule = review.getSchedule(type, id);
        if (schedule == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("enrolled", false);
            return empty;
        }
        Map<String, Object> result = new LinkedHashMap<>(schedule.toMap());
        result.put("enrolled", true);
        List<ReviewLog> logs = review.getLogs(schedule.id, 20);
        result.put("recentLogs", logs.stream().map(ReviewLog::toMap).collect(Collectors.toList()));
        return result;
    }

    @PostMapping("/reset/{type}/{id}")
    public Map<String, Object> reset(@PathVariable String type, @PathVariable long id) {
        review.reset(type, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    // ===================== 配置管理 =====================

    @GetMapping("/configs")
    public List<Map<String, Object>> listConfigs() {
        return review.listConfigs().stream()
                .map(SpacedRepetitionConfig::toMap)
                .collect(Collectors.toList());
    }

    @GetMapping("/configs/{id}")
    public Map<String, Object> getConfig(@PathVariable long id) {
        return review.getConfig(id).toMap();
    }

    @PostMapping("/configs")
    public Map<String, Object> createConfig(@RequestBody Map<String, Object> body) {
        SpacedRepetitionConfig config = mapToConfig(body);
        long id = review.createConfig(config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("ok", true);
        return result;
    }

    @PutMapping("/configs/{id}")
    public Map<String, Object> updateConfig(@PathVariable long id, @RequestBody Map<String, Object> body) {
        SpacedRepetitionConfig config = mapToConfig(body);
        review.updateConfig(id, config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    @DeleteMapping("/configs/{id}")
    public Map<String, Object> deleteConfig(@PathVariable long id) {
        review.deleteConfig(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private SpacedRepetitionConfig mapToConfig(Map<String, Object> body) {
        SpacedRepetitionConfig config = new SpacedRepetitionConfig();
        if (body.containsKey("name")) config.name = (String) body.get("name");
        if (body.containsKey("isDefault")) config.isDefault = Boolean.TRUE.equals(body.get("isDefault"));
        if (body.containsKey("intervals")) {
            Map<String, Object> intervalsMap = (Map<String, Object>) body.get("intervals");
            config.intervals = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : intervalsMap.entrySet()) {
                config.intervals.put(entry.getKey(),
                        entry.getValue() instanceof Number ? ((Number) entry.getValue()).longValue() : Long.parseLong(entry.getValue().toString()));
            }
        }
        if (body.containsKey("initialEf")) config.initialEf = ((Number) body.get("initialEf")).doubleValue();
        if (body.containsKey("minimumEf")) config.minimumEf = ((Number) body.get("minimumEf")).doubleValue();
        if (body.containsKey("maxIntervalDays")) config.maxIntervalDays = ((Number) body.get("maxIntervalDays")).intValue();
        if (body.containsKey("wrongStrategy")) config.wrongStrategy = (String) body.get("wrongStrategy");
        if (body.containsKey("wrongFixedDays")) config.wrongFixedDays = ((Number) body.get("wrongFixedDays")).doubleValue();
        if (body.containsKey("dailyNewLimit")) config.dailyNewLimit = ((Number) body.get("dailyNewLimit")).intValue();
        if (body.containsKey("dailyReviewLimit")) config.dailyReviewLimit = ((Number) body.get("dailyReviewLimit")).intValue();
        if (body.containsKey("priorityMode")) config.priorityMode = (String) body.get("priorityMode");
        return config;
    }
}
