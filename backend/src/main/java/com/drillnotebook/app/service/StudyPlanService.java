package com.drillnotebook.app.service;

import com.drillnotebook.app.model.KnowledgePointRecord;
import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.repository.StudyPlanRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

@Service
public class StudyPlanService {
    private static final Set<String> RESOURCE_TYPES = Set.of("question", "knowledge_point", "note_page");
    private static final Set<String> SOURCES = Set.of("manual", "session_recommend");
    private static final int TITLE_MAX = 80;
    private static final int RECOMMEND_MAX = 30;
    private static final int WRONG_BOOK_LIMIT = 20;
    public static final int DEFAULT_SPAN_DAYS = 5;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final StudyPlanRepository plans;
    private final QuestionRepository questions;
    private final KnowledgePointRepository points;
    private final NotebookRepository notebooks;
    private final AiService ai;

    public StudyPlanService(
            StudyPlanRepository plans,
            QuestionRepository questions,
            KnowledgePointRepository points,
            NotebookRepository notebooks,
            AiService ai) {
        this.plans = plans;
        this.questions = questions;
        this.points = points;
        this.notebooks = notebooks;
        this.ai = ai;
    }

    public Map<String, Object> createGroup(Map<String, Object> body) {
        String planDate = requireDate(body.get("planDate"));
        String source = string(body.get("source"));
        if (source.isBlank()) {
            source = "manual";
        }
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("source 必须是 manual 或 session_recommend");
        }
        String title = string(body.get("title"));
        if (title.isBlank()) {
            title = "手动添加";
        }
        String note = body.containsKey("note") ? string(body.get("note")) : null;
        List<Map<String, Object>> rawItems = asMapList(body.get("items"));
        if (rawItems.isEmpty()) {
            throw new IllegalArgumentException("items 不能为空");
        }

        List<PreparedItem> prepared = new ArrayList<>();
        // 允许同一天、同一资源出现多条待办（一天可复习多次）；不再因 existsTodo / 批内重复而 skip。
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (Map<String, Object> raw : rawItems) {
            String resourceType = string(raw.get("resourceType"));
            long resourceId = longValue(raw.get("resourceId"), -1);
            if (!RESOURCE_TYPES.contains(resourceType) || resourceId <= 0) {
                failed.add(failEntry(resourceType.isBlank() ? "unknown" : resourceType, resourceId, "资源类型或 ID 无效"));
                continue;
            }
            if (!resourceExists(resourceType, resourceId)) {
                failed.add(failEntry(resourceType, resourceId, "资源不存在"));
                continue;
            }
            String itemTitle = resolveTitle(resourceType, resourceId, string(raw.get("title")));
            String itemNote = raw.containsKey("note") ? string(raw.get("note")) : "";
            prepared.add(new PreparedItem(resourceType, resourceId, itemTitle, itemNote));
        }

        if (prepared.isEmpty()) {
            throw new IllegalArgumentException("没有可添加的计划条目");
        }

        long groupId = plans.insertGroup(planDate, title, note, source);
        List<Map<String, Object>> createdItems = new ArrayList<>();
        for (PreparedItem item : prepared) {
            long itemId = plans.insertItem(groupId, planDate, item.resourceType, item.resourceId, item.title, item.note);
            createdItems.add(toItemMap(plans.findItem(itemId), false));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("group", toGroupMap(plans.findGroup(groupId), createdItems));
        response.put("items", createdItems);
        response.put("skipped", skipped);
        response.put("failed", failed);
        return response;
    }

    public Map<String, Object> listRange(String from, String to) {
        requireDate(from);
        requireDate(to);
        return assembleDays(plans.findGroupsBetween(from, to));
    }

    public Map<String, Object> listDay(String date) {
        requireDate(date);
        return assembleDays(plans.findGroupsBetween(date, date));
    }

    public Map<String, Object> updateGroup(long id, Map<String, Object> body) {
        Map<String, Object> current = plans.findGroup(id);
        String title = body.containsKey("title") ? string(body.get("title")) : null;
        String note = body.containsKey("note") ? string(body.get("note")) : null;
        String planDate = null;
        if (body.containsKey("planDate")) {
            planDate = requireDate(body.get("planDate"));
        }
        plans.updateGroup(id, blankToNull(title), note, planDate);

        boolean syncTodoItems = true;
        if (body.containsKey("syncTodoItems")) {
            Object raw = body.get("syncTodoItems");
            syncTodoItems = !(Boolean.FALSE.equals(raw) || "false".equalsIgnoreCase(String.valueOf(raw)));
        }
        if (planDate != null && syncTodoItems) {
            plans.syncTodoItemDates(id, planDate);
        }

        List<Map<String, Object>> items = plans.findItemsForGroups(List.of(id)).stream()
                .map(item -> toItemMap(item, isResourceMissing(string(item.get("resourceType")), longValue(item.get("resourceId"), -1))))
                .toList();
        return toGroupMap(plans.findGroup(id), items);
    }

    public void deleteGroup(long id) {
        plans.findGroup(id);
        plans.deleteGroup(id);
    }

    public Map<String, Object> updateItem(long id, Map<String, Object> body) {
        plans.findItem(id);
        String planDate = body.containsKey("planDate") ? requireDate(body.get("planDate")) : null;
        String note = body.containsKey("note") ? string(body.get("note")) : null;
        String status = body.containsKey("status") ? string(body.get("status")) : null;
        if (status != null && !status.isBlank() && !"todo".equals(status) && !"done".equals(status)) {
            throw new IllegalArgumentException("status 必须是 todo 或 done");
        }
        plans.updateItem(id, planDate, note, blankToNull(status));
        Map<String, Object> item = plans.findItem(id);
        return toItemMap(item, isResourceMissing(string(item.get("resourceType")), longValue(item.get("resourceId"), -1)));
    }

    public void deleteItem(long id) {
        Map<String, Object> item = plans.findItem(id);
        long groupId = longValue(item.get("groupId"), -1);
        plans.deleteItem(id);
        if (groupId > 0) {
            plans.deleteGroupIfEmpty(groupId);
        }
    }

    /**
     * Mark plan items done by resource. Used when the user studies from a plan session
     * (including mid-session exit): answered questions / viewed knowledge points complete
     * matching todo rows immediately.
     *
     * Body: resourceType, resourceIds (list), optional planDate, optional groupId
     */
    public Map<String, Object> completeByResources(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String resourceType = string(body.get("resourceType"));
        if (!RESOURCE_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("resourceType 无效");
        }
        List<Long> resourceIds = toIds(body.get("resourceIds"));
        if (resourceIds.isEmpty()) {
            return Map.of("updated", 0);
        }
        String planDate = null;
        if (body.containsKey("planDate") && body.get("planDate") != null && !string(body.get("planDate")).isBlank()) {
            planDate = requireDate(body.get("planDate"));
        }
        Long groupId = null;
        if (body.containsKey("groupId") && body.get("groupId") != null && !string(body.get("groupId")).isBlank()) {
            long gid = longValue(body.get("groupId"), -1);
            if (gid > 0) {
                groupId = gid;
            }
        }
        int updated = plans.markTodoDoneByResources(resourceType, resourceIds, planDate, groupId);
        return Map.of("updated", updated);
    }

    public Map<String, Object> recommend(Map<String, Object> body) {
        String sessionType = string(body == null ? null : body.get("sessionType"));
        List<Map<String, Object>> candidates = new ArrayList<>();
        String title;

        if ("quiz".equals(sessionType)) {
            title = "本轮刷题错题";
            Set<Long> ids = new LinkedHashSet<>();
            ids.addAll(toIds(body.get("wrongQuestionIds")));
            for (Map<String, Object> answered : asMapList(body.get("answered"))) {
                Object correct = answered.get("isCorrect");
                if (Boolean.FALSE.equals(correct) || "false".equalsIgnoreCase(String.valueOf(correct))) {
                    long qid = longValue(answered.get("questionId"), -1);
                    if (qid > 0) {
                        ids.add(qid);
                    }
                }
            }
            for (Long id : ids) {
                addCandidate(candidates, "question", id);
                if (candidates.size() >= RECOMMEND_MAX) {
                    break;
                }
            }
        } else if ("memorize".equals(sessionType)) {
            title = "本轮需再看";
            for (Long id : toIds(body.get("reviewAgainIds"))) {
                addCandidate(candidates, "question", id);
                if (candidates.size() >= RECOMMEND_MAX) {
                    break;
                }
            }
        } else if ("knowledge".equals(sessionType)) {
            title = "本轮知识点";
            for (Long id : toIds(body.get("pointIds"))) {
                addCandidate(candidates, "knowledge_point", id);
                if (candidates.size() >= RECOMMEND_MAX) {
                    break;
                }
            }
        } else {
            title = "错题本推荐";
            List<QuestionRecord> wrong = questions.wrongQuestions();
            int limit = Math.min(WRONG_BOOK_LIMIT, wrong.size());
            for (int i = 0; i < limit && candidates.size() < RECOMMEND_MAX; i++) {
                QuestionRecord q = wrong.get(i);
                addCandidate(candidates, "question", q.id);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("title", title);
        response.put("candidates", candidates);
        return response;
    }

    /**
     * AI 安排背诵/复习计划（预览，不写库）。
     * 流程：候选池（显式 candidates 或规则 recommend）→ AI 在池内分天 → 校验 → 失败规则降级。
     * body: candidates? | session 字段同 recommend + startDate? + endDate? + defaultTitle? + masterPassword?
     */
    public Map<String, Object> aiSchedule(Map<String, Object> body) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        List<Map<String, Object>> candidates = resolveScheduleCandidates(safe);
        String defaultTitle = string(safe.get("defaultTitle"));
        if (defaultTitle.isBlank()) {
            defaultTitle = string(safe.get("title"));
        }
        if (defaultTitle.isBlank()) {
            defaultTitle = "复习计划";
        }
        String startDate = string(safe.get("startDate"));
        if (startDate.isBlank()) {
            startDate = LocalDate.now().plusDays(1).format(ISO_DATE);
        } else {
            startDate = requireDate(startDate);
        }

        String endInput = string(safe.get("endDate"));
        Map<String, Object> window = resolveScheduleWindow(startDate, endInput);
        String endDate = string(window.get("endDate"));
        int spanDays = ((Number) window.get("spanDays")).intValue();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("startDate", window.get("startDate"));
        response.put("endDate", endDate);
        response.put("spanDays", spanDays);
        response.put("window", window);
        response.put("candidates", candidates);
        response.put("defaultTitle", defaultTitle);

        if (candidates.isEmpty()) {
            response.put("mode", "empty");
            response.put("groups", List.of());
            response.put("message", "没有可安排的候选内容");
            return response;
        }

        Set<String> pool = new LinkedHashSet<>();
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> c : candidates) {
            String rt = string(c.get("resourceType"));
            long rid = longValue(c.get("resourceId"), -1);
            if (rid <= 0 || rt.isBlank()) continue;
            String key = rt + ":" + rid;
            pool.add(key);
            byKey.put(key, c);
        }

        try {
            if (ai == null) {
                throw new IllegalStateException("AI 服务未配置");
            }
            // Compact payload for model: keep scheduling-relevant fields only
            List<Map<String, Object>> compact = candidates.stream().map(c -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("resourceType", c.get("resourceType"));
                row.put("resourceId", c.get("resourceId"));
                row.put("title", c.get("title"));
                if (c.containsKey("difficulty")) row.put("difficulty", c.get("difficulty"));
                if (c.containsKey("tags")) row.put("tags", c.get("tags"));
                if (c.containsKey("chapter")) row.put("chapter", c.get("chapter"));
                if (c.containsKey("questionType")) row.put("questionType", c.get("questionType"));
                if (c.containsKey("attemptCount")) row.put("attemptCount", c.get("attemptCount"));
                if (c.containsKey("wrongCount")) row.put("wrongCount", c.get("wrongCount"));
                if (c.containsKey("correctCount")) row.put("correctCount", c.get("correctCount"));
                if (c.containsKey("lastIsCorrect")) row.put("lastIsCorrect", c.get("lastIsCorrect"));
                if (c.containsKey("isRecentWrong")) row.put("isRecentWrong", c.get("isRecentWrong"));
                if (c.containsKey("weaknessScore")) row.put("weaknessScore", c.get("weaknessScore"));
                if (c.containsKey("category")) row.put("category", c.get("category"));
                return row;
            }).toList();
            Map<String, Object> aiBody = new LinkedHashMap<>();
            aiBody.put("sessionType", string(safe.get("sessionType")));
            aiBody.put("startDate", startDate);
            aiBody.put("endDate", endDate);
            aiBody.put("spanDays", spanDays);
            aiBody.put("candidates", compact);
            String userPrompt = string(safe.get("userPrompt"));
            if (!userPrompt.isBlank()) {
                aiBody.put("userPrompt", userPrompt);
            }
            if (safe.containsKey("masterPassword")) {
                aiBody.put("masterPassword", safe.get("masterPassword"));
            }
            Map<String, Object> scheduled = ai.scheduleStudyPlan(aiBody);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawGroups = (List<Map<String, Object>>) scheduled.getOrDefault("groups", List.of());
            List<Map<String, Object>> groups = materializeAiGroups(rawGroups, startDate, pool, byKey, defaultTitle, spanDays);
            if (groups.isEmpty()) {
                throw new IllegalArgumentException("AI 方案校验后为空");
            }
            response.put("mode", "ai");
            response.put("groups", groups);
            return response;
        } catch (Exception error) {
            // 降级：全部候选放到 startDate 一组
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> c : candidates) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resourceType", c.get("resourceType"));
                item.put("resourceId", c.get("resourceId"));
                item.put("title", c.get("title"));
                items.add(item);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("planDate", startDate);
            group.put("title", defaultTitle);
            group.put("note", "AI 不可用，已按规则将全部候选安排到同一天。");
            group.put("items", items);
            response.put("mode", "rule_fallback");
            response.put("groups", List.of(group));
            response.put("message", error.getMessage() == null ? "AI 不可用" : error.getMessage());
            return response;
        }
    }

    /**
     * Resolve schedule date window from required start and optional end.
     * Default span is DEFAULT_SPAN_DAYS when end is blank. No hard max on long ranges.
     */
    Map<String, Object> resolveScheduleWindow(String startDate, String endDateOrBlank) {
        String start = requireDate(startDate);
        LocalDate startLd = LocalDate.parse(start, ISO_DATE);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("defaultSpanDays", DEFAULT_SPAN_DAYS);
        window.put("message", null);
        if (endDateOrBlank == null || endDateOrBlank.isBlank()) {
            LocalDate endLd = startLd.plusDays(DEFAULT_SPAN_DAYS - 1L);
            String end = endLd.format(ISO_DATE);
            window.put("startDate", start);
            window.put("endDate", end);
            window.put("spanDays", DEFAULT_SPAN_DAYS);
            window.put("endDateProvided", false);
            return window;
        }
        String end = requireDate(endDateOrBlank);
        LocalDate endLd = LocalDate.parse(end, ISO_DATE);
        if (endLd.isBefore(startLd)) {
            throw new IllegalArgumentException("终止日不能早于起始日");
        }
        int span = (int) (endLd.toEpochDay() - startLd.toEpochDay()) + 1;
        window.put("startDate", start);
        window.put("endDate", end);
        window.put("spanDays", span);
        window.put("endDateProvided", true);
        return window;
    }

    /**
     * 一键写入 AI/规则生成的多日计划。
     * body: groups: [{ planDate, title, note, items: [{resourceType, resourceId, title?}] }]
     */
    public Map<String, Object> applySchedule(Map<String, Object> body) {
        List<Map<String, Object>> rawGroups = asMapList(body == null ? null : body.get("groups"));
        if (rawGroups.isEmpty()) {
            throw new IllegalArgumentException("计划分组不能为空");
        }
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        int totalItems = 0;
        for (Map<String, Object> g : rawGroups) {
            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("planDate", g.get("planDate"));
                req.put("title", g.get("title"));
                req.put("note", g.get("note"));
                req.put("source", "session_recommend");
                req.put("items", g.get("items"));
                Map<String, Object> result = createGroup(req);
                created.add(result);
                @SuppressWarnings("unchecked")
                List<?> items = (List<?>) result.getOrDefault("items", List.of());
                totalItems += items.size();
            } catch (Exception error) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("planDate", g.get("planDate"));
                fail.put("title", g.get("title"));
                fail.put("reason", error.getMessage() == null ? "创建失败" : error.getMessage());
                failed.add(fail);
            }
        }
        if (created.isEmpty()) {
            throw new IllegalArgumentException(failed.isEmpty()
                    ? "没有可写入的计划"
                    : string(failed.get(0).get("reason")));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("createdGroups", created.size());
        response.put("createdItems", totalItems);
        response.put("results", created);
        response.put("failed", failed);
        return response;
    }

    /**
     * Prefer explicit candidates from the client (加入计划); otherwise use session recommend rules.
     * Enriches each candidate with difficulty / wrong counts / tags for AI scheduling.
     */
    private List<Map<String, Object>> resolveScheduleCandidates(Map<String, Object> safe) {
        List<Map<String, Object>> base = new ArrayList<>();
        List<Map<String, Object>> explicit = asMapList(safe.get("candidates"));
        if (!explicit.isEmpty()) {
            for (Map<String, Object> raw : explicit) {
                if (base.size() >= RECOMMEND_MAX) {
                    break;
                }
                String rt = string(raw.get("resourceType"));
                long rid = longValue(raw.get("resourceId"), -1);
                if (rid <= 0 || !RESOURCE_TYPES.contains(rt)) {
                    continue;
                }
                String title = string(raw.get("title"));
                if (title.isBlank()) {
                    title = resolveTitle(rt, rid, "");
                }
                if (!resourceExists(rt, rid)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("resourceType", rt);
                row.put("resourceId", rid);
                row.put("title", title);
                String key = rt + ":" + rid;
                boolean dup = base.stream()
                        .anyMatch(c -> key.equals(string(c.get("resourceType")) + ":" + longValue(c.get("resourceId"), -1)));
                if (!dup) {
                    base.add(row);
                }
            }
        } else {
            Map<String, Object> recommendResult = recommend(safe);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fromSession =
                    (List<Map<String, Object>>) recommendResult.getOrDefault("candidates", List.of());
            base.addAll(fromSession);
        }
        return enrichCandidatesForAi(base);
    }

    /** Attach difficulty, tags, chapter, wrongCount, etc. for AI prompts. */
    private List<Map<String, Object>> enrichCandidatesForAi(List<Map<String, Object>> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<Long> questionIds = candidates.stream()
                .filter(c -> "question".equals(string(c.get("resourceType"))))
                .map(c -> longValue(c.get("resourceId"), -1))
                .filter(id -> id > 0)
                .distinct()
                .toList();
        Map<Long, Map<String, Object>> stats =
                questionIds.isEmpty() ? Map.of() : questions.answerStats(questionIds);

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            Map<String, Object> row = new LinkedHashMap<>(c);
            String rt = string(c.get("resourceType"));
            long rid = longValue(c.get("resourceId"), -1);
            try {
                if ("question".equals(rt)) {
                    QuestionRecord q = questions.findById(rid);
                    row.put("title", truncate(q.stem == null ? string(c.get("title")) : q.stem, 80));
                    row.put("difficulty", q.difficulty == null ? 3 : q.difficulty);
                    row.put("tags", q.tags == null ? List.of() : q.tags);
                    row.put("chapter", q.chapter == null ? "" : q.chapter);
                    row.put("questionType", q.type == null ? "" : q.type);
                    Map<String, Object> st = stats.getOrDefault(rid, Map.of());
                    row.put("attemptCount", st.getOrDefault("attemptCount", 0));
                    row.put("wrongCount", st.getOrDefault("wrongCount", 0));
                    row.put("correctCount", st.getOrDefault("correctCount", 0));
                    row.put("lastIsCorrect", st.get("lastIsCorrect"));
                    row.put("lastAnsweredAt", st.get("lastAnsweredAt"));
                    int wrong = ((Number) row.get("wrongCount")).intValue();
                    Boolean lastOk = row.get("lastIsCorrect") instanceof Boolean b ? b : null;
                    row.put("isRecentWrong", lastOk != null && !lastOk);
                    row.put("weaknessScore", wrong * 2 + (lastOk != null && !lastOk ? 3 : 0)
                            + (q.difficulty == null ? 3 : q.difficulty));
                } else if ("knowledge_point".equals(rt)) {
                    KnowledgePointRecord p = points.findById(rid);
                    row.put("title", truncate(p.title == null ? string(c.get("title")) : p.title, 80));
                    row.put("tags", p.tags == null ? List.of() : p.tags);
                    row.put("category", p.category == null ? "" : p.category);
                } else if ("note_page".equals(rt)) {
                    Map<String, Object> page = notebooks.findPage(rid);
                    row.put("title", truncate(string(page.get("title")).isBlank()
                            ? string(c.get("title"))
                            : string(page.get("title")), 80));
                    row.put("notebookId", page.get("notebookId"));
                }
            } catch (Exception ignored) {
                // keep base title if resource lookup fails mid-way
            }
            enriched.add(row);
        }
        return enriched;
    }

    private List<Map<String, Object>> materializeAiGroups(
            List<Map<String, Object>> rawGroups,
            String startDate,
            Set<String> pool,
            Map<String, Map<String, Object>> byKey,
            String defaultTitle,
            int spanDays) {
        LocalDate base = LocalDate.parse(startDate, ISO_DATE);
        List<Map<String, Object>> groups = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Map<String, Object> g : rawGroups) {
            int dayOffset = (int) longValue(g.get("dayOffset"), 0);
            if (dayOffset < 0 || dayOffset >= spanDays) {
                continue; // skip groups with offsets outside the schedule window
            }
            String planDate = base.plusDays(dayOffset).format(ISO_DATE);
            String title = string(g.get("title"));
            if (title.isBlank()) title = defaultTitle;
            String note = string(g.get("note"));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> it : asMapList(g.get("items"))) {
                String rt = string(it.get("resourceType"));
                long rid = longValue(it.get("resourceId"), -1);
                String key = rt + ":" + rid;
                if (!pool.contains(key) || used.contains(key)) {
                    continue;
                }
                used.add(key);
                Map<String, Object> candidate = byKey.get(key);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resourceType", rt);
                item.put("resourceId", rid);
                item.put("title", candidate != null ? candidate.get("title") : resolveTitle(rt, rid, ""));
                items.add(item);
            }
            if (items.isEmpty()) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("planDate", planDate);
            group.put("title", title);
            group.put("note", note);
            group.put("items", items);
            groups.add(group);
        }
        // 池中未被 AI 选中的项：并入 startDate 的一组（或新建）
        List<Map<String, Object>> leftovers = new ArrayList<>();
        for (String key : pool) {
            if (used.contains(key)) continue;
            Map<String, Object> candidate = byKey.get(key);
            if (candidate == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceType", candidate.get("resourceType"));
            item.put("resourceId", candidate.get("resourceId"));
            item.put("title", candidate.get("title"));
            leftovers.add(item);
        }
        if (!leftovers.isEmpty()) {
            Map<String, Object> attach = null;
            for (Map<String, Object> g : groups) {
                if (startDate.equals(string(g.get("planDate")))) {
                    attach = g;
                    break;
                }
            }
            if (attach == null) {
                attach = new LinkedHashMap<>();
                attach.put("planDate", startDate);
                attach.put("title", defaultTitle);
                attach.put("note", "补充未排入的候选");
                attach.put("items", new ArrayList<Map<String, Object>>());
                groups.add(0, attach);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) attach.get("items");
            items.addAll(leftovers);
        }
        return groups;
    }

    private Map<String, Object> assembleDays(List<Map<String, Object>> groups) {
        List<Long> groupIds = groups.stream().map(g -> longValue(g.get("id"), -1)).filter(id -> id > 0).toList();
        Map<Long, List<Map<String, Object>>> itemsByGroup = new LinkedHashMap<>();
        for (Map<String, Object> item : plans.findItemsForGroups(groupIds)) {
            long groupId = longValue(item.get("groupId"), -1);
            Map<String, Object> mapped = toItemMap(
                    item,
                    isResourceMissing(string(item.get("resourceType")), longValue(item.get("resourceId"), -1)));
            itemsByGroup.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(mapped);
        }

        Map<String, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> group : groups) {
            long groupId = longValue(group.get("id"), -1);
            List<Map<String, Object>> items = itemsByGroup.getOrDefault(groupId, List.of());
            Map<String, Object> groupMap = toGroupMap(group, items);
            String date = string(group.get("planDate"));
            byDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(groupMap);
        }

        List<Map<String, Object>> days = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("date", entry.getKey());
                    day.put("groups", entry.getValue());
                    return day;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("days", days);
        return response;
    }

    private void addCandidate(List<Map<String, Object>> candidates, String resourceType, long resourceId) {
        if (resourceId <= 0) {
            return;
        }
        String key = resourceType + ":" + resourceId;
        for (Map<String, Object> existing : candidates) {
            if (key.equals(existing.get("resourceType") + ":" + existing.get("resourceId"))) {
                return;
            }
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("resourceType", resourceType);
        candidate.put("resourceId", resourceId);
        candidate.put("title", resolveTitle(resourceType, resourceId, ""));
        candidates.add(candidate);
    }

    private String requireDate(Object value) {
        String date = string(value);
        if (!date.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            throw new IllegalArgumentException("日期格式必须是 YYYY-MM-DD");
        }
        return date;
    }

    private boolean resourceExists(String resourceType, long resourceId) {
        try {
            return switch (resourceType) {
                case "question" -> {
                    questions.findById(resourceId);
                    yield true;
                }
                case "knowledge_point" -> {
                    points.findById(resourceId);
                    yield true;
                }
                case "note_page" -> {
                    notebooks.findPage(resourceId);
                    yield true;
                }
                default -> false;
            };
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean isResourceMissing(String resourceType, long resourceId) {
        return !resourceExists(resourceType, resourceId);
    }

    private String resolveTitle(String resourceType, long resourceId, String clientTitle) {
        if (clientTitle != null && !clientTitle.isBlank()) {
            return truncate(clientTitle.trim(), TITLE_MAX);
        }
        try {
            return switch (resourceType) {
                case "question" -> {
                    QuestionRecord q = questions.findById(resourceId);
                    yield truncate(q.stem == null ? "题目" : q.stem, TITLE_MAX);
                }
                case "knowledge_point" -> {
                    KnowledgePointRecord p = points.findById(resourceId);
                    yield truncate(p.title == null || p.title.isBlank() ? "知识点" : p.title, TITLE_MAX);
                }
                case "note_page" -> {
                    Map<String, Object> page = notebooks.findPage(resourceId);
                    String pageTitle = string(page.get("title"));
                    yield truncate(pageTitle.isBlank() ? "笔记页" : pageTitle, TITLE_MAX);
                }
                default -> "资源 " + resourceId;
            };
        } catch (RuntimeException ignored) {
            return "资源 " + resourceId;
        }
    }

    private Map<String, Object> toGroupMap(Map<String, Object> group, List<Map<String, Object>> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.get("id"));
        map.put("planDate", group.get("planDate"));
        map.put("title", group.get("title"));
        map.put("note", group.get("note"));
        map.put("source", group.get("source"));
        map.put("createdAt", group.get("createdAt"));
        map.put("updatedAt", group.get("updatedAt"));
        int doneCount = 0;
        for (Map<String, Object> item : items) {
            if ("done".equals(item.get("status"))) {
                doneCount++;
            }
        }
        map.put("doneCount", doneCount);
        map.put("totalCount", items.size());
        map.put("items", items);
        return map;
    }

    private Map<String, Object> toItemMap(Map<String, Object> item, boolean resourceMissing) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.get("id"));
        map.put("groupId", item.get("groupId"));
        map.put("planDate", item.get("planDate"));
        map.put("resourceType", item.get("resourceType"));
        map.put("resourceId", item.get("resourceId"));
        map.put("title", item.get("title"));
        map.put("note", item.get("note"));
        map.put("status", item.get("status"));
        map.put("completedAt", item.get("completedAt"));
        map.put("createdAt", item.get("createdAt"));
        map.put("updatedAt", item.get("updatedAt"));
        map.put("resourceMissing", resourceMissing);
        return map;
    }

    private static Map<String, Object> failEntry(String resourceType, long resourceId, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("resourceType", resourceType);
        entry.put("resourceId", resourceId);
        entry.put("reason", reason);
        return entry;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long longValue(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                result.add(copy);
            }
        }
        return result;
    }

    private static List<Long> toIds(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Object item : list) {
            long id = longValue(item, -1);
            if (id > 0 && seen.add(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private record PreparedItem(String resourceType, long resourceId, String title, String note) {}
}
