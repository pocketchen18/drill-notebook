package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.drillnotebook.app.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pure schedule advance + review log write. Shared by ReviewService and CompletionSyncService
 * to avoid circular DI.
 */
@Service
public class ReviewScheduleApplier {

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewRepository reviewRepo;
    private final ReviewScheduler scheduler = new ReviewScheduler();

    public ReviewScheduleApplier(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
    }

    /**
     * Full schedule advance + review log (no plan hook).
     */
    @Transactional
    public Map<String, Object> applyQuality(long scheduleId, int quality, Integer responseTime, String source) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be 0-5");
        }

        ReviewSchedule schedule = reviewRepo.findScheduleById(scheduleId);
        if (schedule == null) {
            throw new IllegalArgumentException("schedule not found: " + scheduleId);
        }

        SpacedRepetitionConfig config;
        if (schedule.configId != null) {
            config = reviewRepo.findConfigById(schedule.configId);
        } else {
            config = reviewRepo.findDefaultConfig();
        }
        if (config == null) {
            throw new IllegalStateException("no config found");
        }

        int isCorrect = quality >= 3 ? 1 : 0;

        String now = LocalDateTime.now().format(ISO_DATETIME);
        double actualInterval = scheduler.calculateActualInterval(schedule.lastReview, now);

        ReviewScheduler.ScheduleResult next = scheduler.schedule(quality, schedule, config);

        reviewRepo.updateSchedule(
                scheduleId,
                next.ef,
                next.interval,
                next.repetitions,
                next.nextReview,
                now,
                quality,
                isCorrect,
                next.status);

        String logSource = source == null || source.isBlank() ? "manual" : source;
        long logId = reviewRepo.insertLog(
                scheduleId, quality, responseTime, schedule.interval, actualInterval, logSource);

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
}
