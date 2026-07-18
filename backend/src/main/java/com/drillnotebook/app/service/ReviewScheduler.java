package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class ReviewScheduler {

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static class ScheduleResult {
        public double ef;
        public double interval;
        public int repetitions;
        public String nextReview;
        public String status;
    }

    public ScheduleResult schedule(int quality, ReviewSchedule state,
                                    SpacedRepetitionConfig config) {
        ScheduleResult result = new ScheduleResult();

        double newEf = state.ef;
        double newInterval;
        int newRepetitions;

        if (quality >= 3) {
            newInterval = calculatePassedInterval(state.repetitions, state.ef, config);
            newRepetitions = state.repetitions + 1;
            newEf = calculateEaseFactor(state.ef, quality);
        } else {
            newRepetitions = 0;
            newInterval = applyWrongStrategy(state.interval, config);
            newEf = state.ef;
        }

        newEf = Math.max(newEf, config.minimumEf);
        newInterval = Math.min(newInterval, config.maxIntervalDays);

        double minInterval = config.initialEf < 2.0 ? 0.125 : 0.25;
        newInterval = Math.max(newInterval, minInterval);

        LocalDateTime nextReview = LocalDateTime.now().plusMinutes((long) (newInterval * 24 * 60));

        result.ef = Math.round(newEf * 100.0) / 100.0;
        result.interval = Math.round(newInterval * 100.0) / 100.0;
        result.repetitions = newRepetitions;
        result.nextReview = nextReview.format(ISO_DATETIME);
        result.status = determineStatus(result.interval, result.repetitions);

        return result;
    }

    private double calculatePassedInterval(int repetitions, double ef,
                                           SpacedRepetitionConfig config) {
        int stage = repetitions + 1;
        String stageKey = String.valueOf(stage);

        if (config.intervals.containsKey(stageKey)) {
            return config.intervals.get(stageKey);
        }

        if (repetitions == 0) return 1.0;
        if (repetitions == 1) return 6.0;

        double interval = 6.0 * ef;
        for (int i = 2; i < repetitions; i++) {
            interval *= ef;
        }
        return interval;
    }

    private double calculateEaseFactor(double oldEf, int quality) {
        return oldEf + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
    }

    private double applyWrongStrategy(double currentInterval,
                                      SpacedRepetitionConfig config) {
        return switch (config.wrongStrategy) {
            case "reset"          -> config.wrongFixedDays;
            case "reduce_half"    -> Math.max(currentInterval / 2.0, 0.25);
            case "reduce_quarter" -> Math.max(currentInterval * 0.75, 0.25);
            case "fixed"          -> config.wrongFixedDays;
            default               -> 1.0;
        };
    }

    private String determineStatus(double interval, int repetitions) {
        if (interval >= 90 && repetitions >= 5) return "mastered";
        if (interval >= 21) return "review";
        if (repetitions > 0) return "learning";
        return "new";
    }

    public double calculateActualInterval(String lastReview, String reviewedAt) {
        if (lastReview == null || lastReview.isBlank()) return 0;
        try {
            LocalDateTime last = LocalDateTime.parse(lastReview, ISO_DATETIME);
            LocalDateTime now = LocalDateTime.parse(reviewedAt, ISO_DATETIME);
            double days = ChronoUnit.MINUTES.between(last, now) / (24.0 * 60.0);
            return Math.round(days * 100.0) / 100.0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int autoScoreFromQuiz(boolean isCorrect, Integer timeSpent, int medianTimeSeconds) {
        if (!isCorrect) return 0;
        if (timeSpent == null) return 3;
        if (timeSpent <= medianTimeSeconds / 3) return 5;
        if (timeSpent <= medianTimeSeconds) return 4;
        return 3;
    }
}
