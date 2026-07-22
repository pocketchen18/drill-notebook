package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ReviewLog;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepo;
    private final QuestionRepository questionRepo;
    private final ReviewScheduler scheduler;

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ReviewService(ReviewRepository reviewRepo, QuestionRepository questionRepo) {
        this.reviewRepo = reviewRepo;
        this.questionRepo = questionRepo;
        this.scheduler = new ReviewScheduler();
    }

    @Transactional
    public List<Map<String, Object>> enroll(String itemType, List<Long> itemIds, Long configId) {
        if (configId == null) {
            SpacedRepetitionConfig defaultConfig = reviewRepo.findDefaultConfig();
            if (defaultConfig != null) configId = defaultConfig.id;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Long itemId : itemIds) {
            ReviewSchedule existing = reviewRepo.findScheduleByItem(itemType, itemId, configId);
            if (existing != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("itemId", itemId);
                entry.put("scheduleId", existing.id);
                entry.put("status", "already_enrolled");
                results.add(entry);
                continue;
            }
            long scheduleId = reviewRepo.createSchedule(itemType, itemId, configId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("itemId", itemId);
            entry.put("scheduleId", scheduleId);
            entry.put("status", "enrolled");
            results.add(entry);
        }
        return results;
    }

    @Transactional
    public void unenroll(String itemType, List<Long> itemIds) {
        reviewRepo.deleteSchedulesByItems(itemType, itemIds);
    }

    @Transactional
    public Map<String, Object> submit(long scheduleId, int quality, Integer responseTime,
                                       String source) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be 0-5");
        }

        ReviewSchedule schedule = reviewRepo.findScheduleById(scheduleId);
        if (schedule == null) throw new IllegalArgumentException("schedule not found: " + scheduleId);

        SpacedRepetitionConfig config;
        if (schedule.configId != null) {
            config = reviewRepo.findConfigById(schedule.configId);
        } else {
            config = reviewRepo.findDefaultConfig();
        }
        if (config == null) throw new IllegalStateException("no config found");

        int isCorrect = quality >= 3 ? 1 : 0;

        String now = LocalDateTime.now().format(ISO_DATETIME);
        double actualInterval = scheduler.calculateActualInterval(schedule.lastReview, now);

        ReviewScheduler.ScheduleResult next = scheduler.schedule(quality, schedule, config);

        reviewRepo.updateSchedule(scheduleId, next.ef, next.interval, next.repetitions,
                next.nextReview, now, quality, isCorrect, next.status);

        long logId = reviewRepo.insertLog(scheduleId, quality, responseTime,
                schedule.interval, actualInterval, source);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logId", logId);
        result.put("scheduleId", scheduleId);
        result.put("ef", next.ef);
        result.put("interval", next.interval);
        result.put("repetitions", next.repetitions);
        result.put("nextReview", next.nextReview);
        result.put("status", next.status);
        return result;
    }

    @Transactional
    public Map<String, Object> autoSubmitFromQuiz(long questionId, boolean isCorrect,
                                                    Integer timeSpent, Long configId) {
        ReviewSchedule schedule = reviewRepo.findScheduleByItem("question", questionId, configId);
        if (schedule == null) {
            schedule = findOrCreateSchedule("question", questionId, configId);
        }

        int quality;
        if (isCorrect) {
            quality = timeSpent != null && timeSpent <= 10 ? 5 : 3;
        } else {
            quality = 0;
        }

        return submit(schedule.id, quality, timeSpent, "quiz");
    }

    private ReviewSchedule findOrCreateSchedule(String itemType, long itemId, Long configId) {
        ReviewSchedule existing = reviewRepo.findScheduleByItem(itemType, itemId, configId);
        if (existing != null) return existing;
        long id = reviewRepo.createSchedule(itemType, itemId, configId);
        return reviewRepo.findScheduleById(id);
    }

    public List<Map<String, Object>> getDueItems(String itemType, Long configId,
                                                   int newLimit, int reviewLimit,
                                                   String priorityMode) {
        if (configId == null) {
            SpacedRepetitionConfig defaultConfig = reviewRepo.findDefaultConfig();
            if (defaultConfig != null) configId = defaultConfig.id;
        }

        // 返回所有活跃条目（new/learning/review），前端自行按 status/nextReview 筛选
        List<ReviewSchedule> allActive = reviewRepo.findAllActiveItems(itemType, configId);

        List<Map<String, Object>> combined = new ArrayList<>();
        for (ReviewSchedule item : allActive) {
            combined.add(enrichSchedule(item));
        }
        return combined;
    }

    private Map<String, Object> enrichSchedule(ReviewSchedule schedule) {
        Map<String, Object> entry = new LinkedHashMap<>(schedule.toMap());

        if ("question".equals(schedule.itemType)) {
            try {
                var question = questionRepo.findById(schedule.itemId);
                if (question != null) {
                    entry.put("question", question.toMap(true));
                } else {
                    log.warn("复习计划中题目 {} 在数据库中不存在，scheduleId={}", schedule.itemId, schedule.id);
                }
            } catch (Exception e) {
                log.error("加载复习题目失败: itemId={}, scheduleId={}", schedule.itemId, schedule.id, e);
            }
        }

        return entry;
    }

    public Map<String, Object> getStats(String itemType, Long configId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalEnrolled",
                reviewRepo.countByStatus(itemType, configId, "new") +
                reviewRepo.countByStatus(itemType, configId, "learning") +
                reviewRepo.countByStatus(itemType, configId, "review") +
                reviewRepo.countByStatus(itemType, configId, "mastered"));
        stats.put("newCount", reviewRepo.countByStatus(itemType, configId, "new"));
        stats.put("learningCount", reviewRepo.countByStatus(itemType, configId, "learning"));
        stats.put("reviewCount", reviewRepo.countByStatus(itemType, configId, "review"));
        stats.put("masteredCount", reviewRepo.countByStatus(itemType, configId, "mastered"));
        stats.put("dueToday", reviewRepo.countDueToday(itemType, configId));
        stats.put("newToday", reviewRepo.countNewToday(itemType, configId));
        stats.put("dailyStats", reviewRepo.dailyStats(itemType, configId, 30));
        return stats;
    }

    public ReviewSchedule getSchedule(String itemType, long itemId) {
        return reviewRepo.findScheduleByItem(itemType, itemId, null);
    }

    @Transactional
    public void reset(String itemType, long itemId) {
        ReviewSchedule schedule = reviewRepo.findScheduleByItem(itemType, itemId, null);
        if (schedule != null) {
            reviewRepo.resetSchedule(schedule.id);
        }
    }

    public List<ReviewLog> getLogs(long scheduleId, int limit) {
        return reviewRepo.findLogsBySchedule(scheduleId, limit);
    }

    // ===================== 配置管理 =====================

    public List<SpacedRepetitionConfig> listConfigs() {
        return reviewRepo.findAllConfigs();
    }

    public SpacedRepetitionConfig getConfig(long id) {
        return reviewRepo.findConfigById(id);
    }

    @Transactional
    public long createConfig(SpacedRepetitionConfig config) {
        return reviewRepo.insertConfig(config);
    }

    @Transactional
    public void updateConfig(long id, SpacedRepetitionConfig config) {
        reviewRepo.updateConfig(id, config);
    }

    @Transactional
    public void deleteConfig(long id) {
        reviewRepo.deleteConfig(id);
    }
}
