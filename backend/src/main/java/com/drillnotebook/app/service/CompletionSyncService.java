package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.repository.ReviewRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bidirectional completion engine: mark one plan todo + apply SRS same-day policy.
 * Does not auto-enroll. note_page never touches SRS.
 * Depends on ReviewScheduleApplier (not ReviewService) to avoid circular DI.
 */
@Service
public class CompletionSyncService {

    private final ReviewRepository reviewRepo;
    private final StudyPlanRepository planRepo;
    private final ReviewScheduleApplier applier;
    private final ReviewScheduler scheduler = new ReviewScheduler();

    public CompletionSyncService(
            ReviewRepository reviewRepo,
            StudyPlanRepository planRepo,
            ReviewScheduleApplier applier) {
        this.reviewRepo = reviewRepo;
        this.planRepo = planRepo;
        this.applier = applier;
    }

    /**
     * Complete by schedule id (review session submit path). Marks earliest plan todo for the
     * schedule's item, applies same-day main/extra policy, returns SRS map compatible with
     * {@code /api/review/submit}.
     */
    @Transactional
    public Map<String, Object> completeByScheduleId(
            long scheduleId, int quality, Integer responseTime, String source) {
        ReviewSchedule schedule = reviewRepo.findScheduleById(scheduleId);
        if (schedule == null) {
            throw new IllegalArgumentException("schedule not found: " + scheduleId);
        }
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be 0-5");
        }
        String src = source == null || source.isBlank() ? "manual" : source;
        String planDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceType", schedule.itemType);
        body.put("resourceId", schedule.itemId);
        body.put("quality", quality);
        body.put("responseTime", responseTime);
        body.put("source", src);
        body.put("planDate", planDate);
        if (schedule.configId != null) {
            body.put("configId", schedule.configId);
        }

        Map<String, Object> full = onItemCompleted(body);
        return toSubmitResponse(scheduleId, full);
    }

    /**
     * Flatten engine result to the historical {@code /api/review/submit} shape.
     * Extra-practice path still returns nextReview/status without a full schedule advance.
     */
    private Map<String, Object> toSubmitResponse(long scheduleId, Map<String, Object> full) {
        @SuppressWarnings("unchecked")
        Map<String, Object> srs = full.get("srs") instanceof Map
                ? (Map<String, Object>) full.get("srs")
                : null;
        if (srs != null && srs.containsKey("logId")) {
            // Full advance already shaped correctly
            Map<String, Object> out = new LinkedHashMap<>(srs);
            if (Boolean.TRUE.equals(full.get("extraPractice"))) {
                out.put("extra", true);
            }
            return out;
        }
        // Extra practice or missing fields: build compat map from live schedule
        ReviewSchedule schedule = reviewRepo.findScheduleById(scheduleId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (srs != null && srs.get("logId") != null) {
            out.put("logId", srs.get("logId"));
        } else {
            // Extra path inserts a log but does not put logId in srs today — read newest
            var logs = reviewRepo.findLogsBySchedule(scheduleId, 1);
            if (!logs.isEmpty()) {
                out.put("logId", logs.get(0).id);
            }
        }
        out.put("scheduleId", scheduleId);
        out.put("ef", schedule.ef);
        out.put("interval", schedule.interval);
        out.put("repetitions", schedule.repetitions);
        out.put("nextReview", schedule.nextReview);
        out.put("status", schedule.status);
        if (Boolean.TRUE.equals(full.get("extraPractice"))) {
            out.put("extra", true);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> onItemCompleted(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        String resourceType = stringVal(body.get("resourceType"));
        Long resourceId = longVal(body.get("resourceId"));
        if (resourceType == null || resourceType.isBlank() || resourceId == null) {
            throw new IllegalArgumentException("resourceType and resourceId are required");
        }

        String planDate = stringVal(body.get("planDate"));
        if (planDate == null || planDate.isBlank()) {
            planDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        Integer quality = resolveQuality(body);
        Integer responseTime = intVal(body.get("responseTime"));
        String source = stringVal(body.get("source"));
        if (source == null || source.isBlank()) {
            source = "manual";
        }
        Long configId = longVal(body.get("configId"));
        Long planItemId = longVal(body.get("planItemId"));
        Long scheduleId = longVal(body.get("scheduleId"));
        boolean forceAdvance = booleanVal(body.get("forceAdvance"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("extraPractice", false);

        // ---- Plan branch ----
        Map<String, Object> planItem = markPlanItem(planDate, resourceType, resourceId, planItemId);
        if (planItem != null) {
            result.put("planItem", planItem);
        }

        // ---- SRS branch ----
        if ("note_page".equals(resourceType)) {
            result.put("skippedSrs", "note_page");
            return result;
        }
        if (quality == null) {
            result.put("skippedSrs", "no_quality");
            return result;
        }

        ReviewSchedule schedule = null;
        if (scheduleId != null) {
            schedule = reviewRepo.findScheduleById(scheduleId);
            // Ignore mismatched scheduleId (wrong resource)
            if (schedule != null
                    && (!resourceType.equals(schedule.itemType) || schedule.itemId != resourceId)) {
                schedule = null;
            }
        }
        if (schedule == null) {
            schedule = findSchedule(resourceType, resourceId, configId);
        }
        if (schedule == null) {
            result.put("skippedSrs", "not_enrolled");
            return result;
        }

        // Same-day main/extra policy keys off review day (now), not planDate.
        // forceAdvance skips the extra short-circuit so a second correct also advances SRS.
        String reviewDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        boolean allowMultipleMain = forceAdvance || allowsMultipleMainAdvances(schedule);
        boolean alreadyMain = reviewRepo.hasMainAdvanceOnDate(schedule.id, reviewDate);

        if (!allowMultipleMain && alreadyMain && quality >= 3) {
            // Extra practice only — do not change next_review
            long logId = reviewRepo.insertLog(
                    schedule.id,
                    quality,
                    responseTime,
                    schedule.interval,
                    0.0,
                    "extra");
            result.put("extraPractice", true);
            Map<String, Object> srs = new LinkedHashMap<>();
            srs.put("logId", logId);
            srs.put("scheduleId", schedule.id);
            srs.put("nextReview", schedule.nextReview);
            srs.put("status", schedule.status);
            srs.put("ef", schedule.ef);
            srs.put("interval", schedule.interval);
            srs.put("repetitions", schedule.repetitions);
            srs.put("extra", true);
            result.put("srs", srs);
            return result;
        }

        Map<String, Object> srs = applier.applyQuality(schedule.id, quality, responseTime, source);
        // After a pass from the day-queue / calendar view: guarantee the card leaves
        // "today's due list". Stage-1 intervals are often 1 day, so next_review can still
        // fall on today and the UI keeps showing 提前/到期 after the user already studied.
        // Anchor = max(viewed planDate, real today) so both "today" and future-day views clear.
        if (quality >= 3) {
            String realToday = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String clearDay = realToday;
            if (planDate != null && !planDate.isBlank() && planDate.compareTo(realToday) > 0) {
                clearDay = planDate;
            }
            srs = ensureNextReviewAfterViewDay(schedule.id, srs, clearDay);
        }
        result.put("srs", srs);
        result.put("extraPractice", false);
        return result;
    }

    /**
     * If next_review is still on or before {@code viewDateYmd}, bump it to 08:00 the day after
     * and adjust interval so the card leaves that day's due queue.
     */
    private Map<String, Object> ensureNextReviewAfterViewDay(
            long scheduleId, Map<String, Object> srs, String viewDateYmd) {
        if (srs == null) {
            return srs;
        }
        Object nrObj = srs.get("nextReview");
        if (nrObj == null) {
            return srs;
        }
        String nextReview = String.valueOf(nrObj);
        String nextDay = nextReview.length() >= 10 ? nextReview.substring(0, 10) : nextReview;
        if (nextDay.compareTo(viewDateYmd) > 0) {
            return srs; // already after the viewed day
        }
        try {
            LocalDate view = LocalDate.parse(viewDateYmd, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDateTime bumped = LocalDateTime.of(view.plusDays(1), LocalTime.of(8, 0));
            // Never schedule in the past relative to now (clock skew / same-day edge).
            LocalDateTime minBump = LocalDateTime.now().plusHours(12);
            if (bumped.isBefore(minBump)) {
                bumped = minBump;
            }
            String bumpedStr = bumped.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            double days = ChronoUnit.MINUTES.between(LocalDateTime.now(), bumped) / (24.0 * 60.0);
            days = Math.max(days, 0.5);
            days = Math.round(days * 100.0) / 100.0;

            ReviewSchedule live = reviewRepo.findScheduleById(scheduleId);
            if (live == null) {
                return srs;
            }
            reviewRepo.updateNextReviewOnly(scheduleId, bumpedStr, days);

            Map<String, Object> out = new LinkedHashMap<>(srs);
            out.put("nextReview", bumpedStr);
            out.put("interval", days);
            out.put("clearedViewDay", viewDateYmd);
            if (srs.get("status") == null && live.status != null) {
                out.put("status", live.status);
            }
            return out;
        } catch (Exception e) {
            return srs;
        }
    }

    private Map<String, Object> markPlanItem(
            String planDate, String resourceType, long resourceId, Long planItemId) {
        if (planItemId != null) {
            Map<String, Object> item;
            try {
                item = planRepo.findItem(planItemId);
            } catch (Exception e) {
                return null;
            }
            if (item == null) {
                return null;
            }
            String type = stringVal(item.get("resourceType"));
            Long id = longVal(item.get("resourceId"));
            String status = stringVal(item.get("status"));
            if (!resourceType.equals(type) || id == null || id != resourceId) {
                return null;
            }
            if ("todo".equals(status)) {
                planRepo.markItemDone(planItemId);
                return planRepo.findItem(planItemId);
            }
            // already done → no-op; still return snapshot so caller sees it
            return item;
        }

        Map<String, Object> earliest = planRepo.findEarliestTodo(planDate, resourceType, resourceId);
        if (earliest == null) {
            return null;
        }
        long id = ((Number) earliest.get("id")).longValue();
        if (planRepo.markItemDone(id) > 0) {
            return planRepo.findItem(id);
        }
        return null;
    }

    private ReviewSchedule findSchedule(String resourceType, long resourceId, Long configId) {
        if (configId != null) {
            ReviewSchedule byConfig = reviewRepo.findScheduleByItem(resourceType, resourceId, configId);
            if (byConfig != null) {
                return byConfig;
            }
        }
        return reviewRepo.findScheduleByItem(resourceType, resourceId, null);
    }

    /**
     * Learning / short-interval schedules may fully advance multiple times per day.
     */
    private boolean allowsMultipleMainAdvances(ReviewSchedule schedule) {
        if (schedule.interval < 1.0) {
            return true;
        }
        String status = schedule.status == null ? "" : schedule.status;
        return ("new".equals(status) || "learning".equals(status)) && schedule.interval < 1.0;
    }

    private Integer resolveQuality(Map<String, Object> body) {
        if (body.containsKey("quality") && body.get("quality") != null) {
            Integer parsed = intVal(body.get("quality"));
            if (parsed == null) {
                throw new IllegalArgumentException("quality must be a number");
            }
            int q = parsed;
            if (q < 0) {
                q = 0;
            }
            if (q > 5) {
                q = 5;
            }
            return q;
        }
        if (body.containsKey("isCorrect") && body.get("isCorrect") != null) {
            boolean correct = booleanVal(body.get("isCorrect"));
            Integer responseTime = intVal(body.get("responseTime"));
            // Use quiz auto-score with a reasonable default median (30s)
            return scheduler.autoScoreFromQuiz(correct, responseTime, 30);
        }
        return null;
    }

    private static String stringVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long longVal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intVal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean booleanVal(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
