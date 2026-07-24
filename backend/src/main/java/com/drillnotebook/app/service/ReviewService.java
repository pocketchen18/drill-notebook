package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ReviewLog;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.ReviewRepository;
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
    private final ReviewScheduleApplier applier;
    private final CompletionSyncService completionSync;

    public ReviewService(
            ReviewRepository reviewRepo,
            QuestionRepository questionRepo,
            ReviewScheduleApplier applier,
            CompletionSyncService completionSync) {
        this.reviewRepo = reviewRepo;
        this.questionRepo = questionRepo;
        this.applier = applier;
        this.completionSync = completionSync;
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

    /**
     * Review session submit: advances SRS (with same-day extra policy) and marks one plan todo
     * for the schedule's item when present. Response fields stay compatible with /api/review/submit.
     */
    @Transactional
    public Map<String, Object> submit(long scheduleId, int quality, Integer responseTime,
                                       String source) {
        return completionSync.completeByScheduleId(scheduleId, quality, responseTime, source);
    }

    /**
     * Full schedule advance + review log (no plan hook). Kept for direct SRS-only advances.
     */
    @Transactional
    public Map<String, Object> applyQuality(long scheduleId, int quality, Integer responseTime,
                                            String source) {
        return applier.applyQuality(scheduleId, quality, responseTime, source);
    }

    /**
     * Quiz auto-submit: only if already enrolled. Never auto-enrolls.
     *
     * @return SRS submit map when enrolled; otherwise {@code {skipped:true, reason:"not_enrolled"}}.
     */
    @Transactional
    public Map<String, Object> autoSubmitFromQuiz(long questionId, boolean isCorrect,
                                                    Integer timeSpent, Long configId) {
        ReviewSchedule schedule = reviewRepo.findScheduleByItem("question", questionId, configId);
        if (schedule == null && configId != null) {
            schedule = reviewRepo.findScheduleByItem("question", questionId, null);
        }
        if (schedule == null) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("skipped", true);
            skipped.put("reason", "not_enrolled");
            return skipped;
        }

        int quality;
        if (isCorrect) {
            quality = timeSpent != null && timeSpent <= 10 ? 5 : 3;
        } else {
            quality = 0;
        }

        return submit(schedule.id, quality, timeSpent, "quiz");
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

    /**
     * Calendar overlay: count SRS schedules due on each day in [from, to],
     * plus those already overdue relative to real today. Returned as two
     * parallel lists so the frontend can render a due marker and a red
     * overdue marker independently.
     */
    public Map<String, Object> calendarStats(String from, String to, Long configIdOrNull) {
        String realToday = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        List<Map<String, Object>> due = reviewRepo.countDueByDay(from, to, configIdOrNull);
        List<Map<String, Object>> overdue = reviewRepo.countOverdueByDay(from, to, realToday, configIdOrNull);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("realToday", realToday);
        result.put("due", due);
        result.put("overdue", overdue);
        return result;
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
