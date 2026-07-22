package com.drillnotebook.app.service;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.ReviewRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read model: merge plan todos + SRS due into a single today queue.
 * Truncates pure virtual due by {@code daily_review_limit}; never truncates plan rows.
 * Skips (and purges) resources that no longer exist in the bank / notebook.
 */
@Service
public class TodayQueueService {

    private final StudyPlanRepository planRepo;
    private final ReviewRepository reviewRepo;
    private final QuestionRepository questionRepo;
    private final KnowledgePointRepository pointRepo;
    private final NotebookRepository notebookRepo;

    public TodayQueueService(
            StudyPlanRepository planRepo,
            ReviewRepository reviewRepo,
            QuestionRepository questionRepo,
            KnowledgePointRepository pointRepo,
            NotebookRepository notebookRepo) {
        this.planRepo = planRepo;
        this.reviewRepo = reviewRepo;
        this.questionRepo = questionRepo;
        this.pointRepo = pointRepo;
        this.notebookRepo = notebookRepo;
    }

    public Map<String, Object> getToday(String dateYmd, Long configIdOrNull) {
        String date = (dateYmd == null || dateYmd.isBlank())
                ? LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : dateYmd.trim();

        SpacedRepetitionConfig config = resolveConfig(configIdOrNull);
        Long configFilter = configIdOrNull;
        int reviewLimit = config != null && config.dailyReviewLimit > 0
                ? config.dailyReviewLimit
                : Integer.MAX_VALUE;

        // ---- A. plan todos (drop + purge missing resources) ----
        List<Map<String, Object>> planTodos = planRepo.findTodosOnDate(date);
        Map<Long, String> groupTitles = new HashMap<>();
        List<Map<String, Object>> planRows = new ArrayList<>();
        // resource key -> list of plan row indices for due badge merge
        Map<String, List<Integer>> planIndexByResource = new HashMap<>();

        for (Map<String, Object> todo : planTodos) {
            String resourceType = String.valueOf(todo.get("resourceType"));
            long resourceId = ((Number) todo.get("resourceId")).longValue();
            long planItemId = ((Number) todo.get("id")).longValue();
            long groupId = ((Number) todo.get("groupId")).longValue();
            if (!resourceExists(resourceType, resourceId)) {
                // Auto-remove stale plan rows so the queue never shows deleted bank items.
                try {
                    planRepo.deleteItem(planItemId);
                    if (groupId > 0) {
                        planRepo.deleteGroupIfEmpty(groupId);
                    }
                } catch (Exception ignored) {
                    // still skip from response
                }
                continue;
            }
            Map<String, Object> row = buildPlanRow(todo, groupTitles);
            int idx = planRows.size();
            planRows.add(row);
            String key = resourceKey(row.get("resourceType"), row.get("resourceId"));
            planIndexByResource.computeIfAbsent(key, k -> new ArrayList<>()).add(idx);
        }

        // ---- B. due schedules (next_review <= date) + legacy new with NULL next_review ----
        List<ReviewSchedule> dueSchedules = new ArrayList<>(
                reviewRepo.findDueOnOrBefore(date, null, configFilter));
        // New enrolls used to leave next_review NULL and never appeared in the queue.
        // Include them for the real-today view (and when selected day is today).
        String realToday = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (date.equals(realToday)) {
            List<ReviewSchedule> legacyNew = reviewRepo.findNewWithoutNextReview(null, configFilter);
            Set<Long> seenIds = new HashSet<>();
            for (ReviewSchedule s : dueSchedules) {
                seenIds.add(s.id);
            }
            for (ReviewSchedule s : legacyNew) {
                if (seenIds.add(s.id)) {
                    dueSchedules.add(s);
                }
            }
        }

        List<Map<String, Object>> pureDueCandidates = new ArrayList<>();
        int newIncluded = 0;

        for (ReviewSchedule schedule : dueSchedules) {
            if (!resourceExists(schedule.itemType, schedule.itemId)) {
                // Orphan schedule from before delete-cascade; remove so it never reappears.
                try {
                    reviewRepo.deleteSchedulesByItems(schedule.itemType, List.of(schedule.itemId));
                } catch (Exception ignored) {
                    // still skip from response
                }
                continue;
            }
            String key = resourceKey(schedule.itemType, schedule.itemId);
            boolean isNewCard = schedule.nextReview == null
                    || schedule.nextReview.isBlank()
                    || "new".equals(schedule.status);
            // Overdue = missed relative to real today (user is late now).
            boolean overdue = !isNewCard && isOverdue(schedule.nextReview, realToday);
            String nextReviewDay = dayPart(schedule.nextReview);
            List<Integer> planIdxs = planIndexByResource.get(key);
            if (planIdxs != null && !planIdxs.isEmpty()) {
                for (int i : planIdxs) {
                    Map<String, Object> planRow = planRows.get(i);
                    planRow.put("kind", "plan_and_due");
                    planRow.put("due", true);
                    planRow.put("overdue", overdue || Boolean.TRUE.equals(planRow.get("overdue")));
                    planRow.put("scheduleId", schedule.id);
                    planRow.put("srsStatus", schedule.status);
                    planRow.put("nextReview", nextReviewDay);
                    if (isNewCard) {
                        planRow.put("isNew", true);
                    }
                }
            } else {
                Map<String, Object> dueRow = buildDueRow(schedule, overdue, date);
                if (isNewCard) {
                    dueRow.put("isNew", true);
                    newIncluded++;
                }
                pureDueCandidates.add(dueRow);
            }
        }

        // ---- C. truncate pure due (overdue first), never plan ----
        pureDueCandidates.sort(dueTruncateComparator());
        int pureTotal = pureDueCandidates.size();
        int keep = Math.min(reviewLimit, pureTotal);
        List<Map<String, Object>> pureDueKept =
                new ArrayList<>(pureDueCandidates.subList(0, keep));
        int dueTruncated = pureTotal - keep;

        // ---- D. sort final: overdue → plan → pure due ----
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(planRows);
        items.addAll(pureDueKept);
        items.sort(queueSortComparator());
        for (Map<String, Object> row : items) {
            row.remove("_ef");
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("planTodo", planRows.size());
        stats.put("dueIncluded", pureDueKept.size());
        stats.put("dueTruncated", dueTruncated);
        stats.put("newIncluded", newIncluded);
        stats.put("newTruncated", 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("items", items);
        result.put("stats", stats);
        return result;
    }

    private SpacedRepetitionConfig resolveConfig(Long configIdOrNull) {
        if (configIdOrNull != null) {
            try {
                return reviewRepo.findConfigById(configIdOrNull);
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        return reviewRepo.findDefaultConfig();
    }

    private Map<String, Object> buildPlanRow(Map<String, Object> todo, Map<Long, String> groupTitles) {
        long planItemId = ((Number) todo.get("id")).longValue();
        long groupId = ((Number) todo.get("groupId")).longValue();
        String resourceType = String.valueOf(todo.get("resourceType"));
        long resourceId = ((Number) todo.get("resourceId")).longValue();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "plan:" + planItemId);
        row.put("kind", "plan");
        row.put("resourceType", resourceType);
        row.put("resourceId", resourceId);
        row.put("title", todo.get("title"));
        row.put("planItemId", planItemId);
        row.put("scheduleId", null);
        row.put("due", false);
        row.put("overdue", false);
        row.put("srsStatus", null);
        row.put("groupId", groupId);
        row.put("groupTitle", resolveGroupTitle(groupId, groupTitles));
        row.put("note", todo.get("note"));
        return row;
    }

    private String resolveGroupTitle(long groupId, Map<Long, String> cache) {
        if (cache.containsKey(groupId)) {
            return cache.get(groupId);
        }
        String title = null;
        try {
            Map<String, Object> group = planRepo.findGroup(groupId);
            if (group != null) {
                title = group.get("title") == null ? null : String.valueOf(group.get("title"));
            }
        } catch (Exception ignored) {
            title = null;
        }
        cache.put(groupId, title);
        return title;
    }

    private Map<String, Object> buildDueRow(ReviewSchedule schedule, boolean overdue, String date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "due:" + schedule.id);
        row.put("kind", "due");
        row.put("resourceType", schedule.itemType);
        row.put("resourceId", schedule.itemId);
        row.put("title", resolveDueTitle(schedule));
        row.put("planItemId", null);
        row.put("scheduleId", schedule.id);
        row.put("due", true);
        row.put("overdue", overdue);
        row.put("srsStatus", schedule.status);
        // YYYY-MM-DD for client urgency labels (到期 vs 提前 when browsing future days)
        row.put("nextReview", dayPart(schedule.nextReview));
        row.put("groupId", null);
        row.put("groupTitle", null);
        row.put("note", null);
        row.put("_ef", schedule.ef);
        return row;
    }

    private String resolveDueTitle(ReviewSchedule schedule) {
        if ("question".equals(schedule.itemType) && questionRepo != null) {
            try {
                QuestionRecord q = questionRepo.findById(schedule.itemId);
                if (q != null && q.stem != null && !q.stem.isBlank()) {
                    String stem = q.stem.trim();
                    return stem.length() > 40 ? stem.substring(0, 40) + "…" : stem;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        if ("knowledge_point".equals(schedule.itemType) && pointRepo != null) {
            try {
                var point = pointRepo.findById(schedule.itemId);
                if (point != null && point.title != null && !point.title.isBlank()) {
                    String title = point.title.trim();
                    return title.length() > 40 ? title.substring(0, 40) + "…" : title;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return schedule.itemType + "#" + schedule.itemId;
    }

    private boolean resourceExists(String resourceType, long resourceId) {
        if (resourceId <= 0 || resourceType == null || resourceType.isBlank()) {
            return false;
        }
        try {
            return switch (resourceType) {
                case "question" -> {
                    questionRepo.findById(resourceId);
                    yield true;
                }
                case "knowledge_point" -> {
                    if (pointRepo == null) {
                        yield false;
                    }
                    pointRepo.findById(resourceId);
                    yield true;
                }
                case "note_page" -> {
                    if (notebookRepo == null) {
                        yield false;
                    }
                    notebookRepo.findPage(resourceId);
                    yield true;
                }
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    /** Prefer overdue, then earlier next_review, then lower ef when worst_first is default for truncate. */
    private Comparator<Map<String, Object>> dueTruncateComparator() {
        return Comparator
                .comparing((Map<String, Object> r) -> !Boolean.TRUE.equals(r.get("overdue")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("nextReview", "9999")))
                .thenComparing(r -> {
                    Object ef = r.get("_ef");
                    return ef instanceof Number n ? n.doubleValue() : 2.5;
                });
    }

    /**
     * Sort: overdue first, then plan rows (plan / plan_and_due), then pure due.
     * Within plan: groupId, planItemId. Within pure due: next_review.
     */
    private Comparator<Map<String, Object>> queueSortComparator() {
        return Comparator
                .comparingInt((Map<String, Object> r) -> sortBucket(r))
                .thenComparingLong(r -> {
                    Object g = r.get("groupId");
                    return g instanceof Number n ? n.longValue() : Long.MAX_VALUE;
                })
                .thenComparingLong(r -> {
                    Object p = r.get("planItemId");
                    if (p instanceof Number n) {
                        return n.longValue();
                    }
                    Object s = r.get("scheduleId");
                    return s instanceof Number n ? n.longValue() : 0L;
                });
    }

    /** 0=overdue, 1=plan/plan_and_due not overdue, 2=pure due not overdue */
    private int sortBucket(Map<String, Object> r) {
        if (Boolean.TRUE.equals(r.get("overdue"))) {
            return 0;
        }
        String kind = String.valueOf(r.get("kind"));
        if ("plan".equals(kind) || "plan_and_due".equals(kind)) {
            return 1;
        }
        return 2;
    }

    private static boolean isOverdue(String nextReview, String dateYmd) {
        String day = dayPart(nextReview);
        if (day == null) {
            return false;
        }
        return day.compareTo(dateYmd) < 0;
    }

    /** Normalize next_review timestamps to calendar day (YYYY-MM-DD). */
    private static String dayPart(String nextReview) {
        if (nextReview == null || nextReview.isBlank()) {
            return null;
        }
        return nextReview.length() >= 10 ? nextReview.substring(0, 10) : nextReview;
    }

    private static String resourceKey(Object type, Object id) {
        return String.valueOf(type) + ":" + String.valueOf(id);
    }
}
