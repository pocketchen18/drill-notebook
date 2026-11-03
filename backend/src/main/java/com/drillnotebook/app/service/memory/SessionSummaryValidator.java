package com.drillnotebook.app.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code SESSION_SUMMARY_V2} 的服务端校验（contracts §6.1 精确字段约束、§6.2 正反例、§6.3 第 1 条；
 * 执行方案 §8.4 第 2、3 条）。
 *
 * <p>模型对续接状态只有<b>建议权</b>：这里把它的 JSON 变成不可变的 {@link SessionSummary}，
 * 全程不写库、不调模型。判定顺序固定为「整份能不能用 → 逐条字段 → 逐条来源与角色 → 引用与凭据」，
 * 整份不合格才交给一次格式修复，单条不合格只删那一条并报告短代码（§6.2 尾段"拒绝或删除非法项并报告"）。</p>
 *
 * <p>服务端能证明的只有 ID、角色声明与长度：文本是不是真的表达了用户的采纳，属于评测口径而不是本地校验，
 * 所以这里绝不因为 ID 对得上就宣称语义正确（§6.1 第 3 段）。</p>
 */
public final class SessionSummaryValidator {
    /** §6.1：topic ≤40 码点。 */
    static final int MAX_TOPIC_CODE_POINTS = 40;
    /** §6.1：每条 text ≤80 码点。 */
    static final int MAX_TEXT_CODE_POINTS = 80;
    /** §6.1：每个文本项的来源 ≤2 个。 */
    static final int MAX_SOURCES_PER_ITEM = 2;
    /** §6.1：约束上限 4 条。 */
    static final int MAX_CONSTRAINT_ITEMS = 4;
    /** §6.1：其余数组各 ≤3 条。 */
    static final int MAX_ARRAY_ITEMS = 3;
    /** §6.1 输入侧：allowedReferences ≤3，产出同样不可能超过它。 */
    static final int MAX_REFERENCES = 3;
    /** §6.1：contentVersion 与 pageId 的长度上限（服务端派生值，超长就是伪造结构）。 */
    static final int MAX_VERSION_CODE_POINTS = 64;
    /** §6.3 第 1 条：响应体先按 32 KiB 判，超出根本不解析。 */
    static final int MAX_BODY_BYTES = 32 * 1024;

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("schemaVersion", "topic", "taskGoal",
            "confirmedUserConstraints", "confirmedDecisions", "discussionNotes", "workItems", "openQuestions",
            "nextSteps", "references");
    private static final List<String> REQUIRED_FIELDS = List.of("schemaVersion", "topic", "taskGoal",
            "confirmedUserConstraints", "confirmedDecisions", "discussionNotes", "workItems", "openQuestions",
            "nextSteps", "references");
    private static final Set<String> SOURCED_FIELDS = Set.of("text", "sourceRole", "sourceTurnIds");
    private static final Set<String> WORK_ITEM_FIELDS = Set.of("text", "sourceRole", "sourceTurnIds", "state");
    private static final Set<String> REFERENCE_FIELDS = Set.of("pageId", "contentVersion", "sourceTurnId");
    private static final Set<String> ROLES = Set.of("user", "assistant");
    /** §6.2：工作状态只有这四格，<b>没有</b>由模型宣告的 verified。 */
    private static final Set<String> WORK_STATES = Set.of("planned", "in_progress", "user_reported_done",
            "unverified");
    /** §6.1 第 3 段：这几栏强制 sourceRole=user，助手的话不能替用户确认。 */
    private static final String USER_ONLY_PREFIX = "USER_ONLY_FIELD:";

    private SessionSummaryValidator() {
    }

    /** 一条带来源的文本项。{@code sourceTurnIds} 已保证非空、≤2 且都落在允许来源内。 */
    public record SourcedText(String text, String sourceRole, List<Long> sourceTurnIds) {
        public SourcedText {
            sourceTurnIds = sourceTurnIds == null ? List.of() : List.copyOf(sourceTurnIds);
        }
    }

    /** 工作项：文本 + 状态。状态集里没有 {@code verified}，助手自述完成只能是 {@code unverified}。 */
    public record WorkItem(SourcedText source, String state) {
    }

    /** 引用：只允许"本会话实际提供过"的笔记版本，全部由服务端派生与复验（§8.4 第 7 条）。 */
    public record Reference(long pageId, String contentVersion, long sourceTurnId) {
    }

    /** 整份返回的可用性。与提取侧同一条纪律：这些是"这份草稿没法用"，不是"某条不好"。 */
    public enum FormatStatus {
        OK,
        OVER_BYTE_LIMIT,
        NOT_JSON,
        NOT_OBJECT,
        WRONG_SCHEMA_VERSION,
        UNEXPECTED_FIELD,
        MISSING_FIELD,
        FIELD_NOT_ARRAY;

        public boolean needsRepair() {
            return this != OK;
        }
    }

    /** 校验产出：可用的摘要结构 + 逐条拒绝短代码（只有代码，不带正文，跳过统计不留敏感原文）。 */
    public record Outcome(FormatStatus format, SessionSummary summary, List<String> rejections) {
        public Outcome {
            rejections = rejections == null ? List.of() : List.copyOf(rejections);
        }

        public boolean formatOk() {
            return format == FormatStatus.OK;
        }

        static Outcome of(FormatStatus format) {
            return new Outcome(format, null, List.of());
        }
    }

    /** 校验后的摘要结构；§8.4 第 2 条的九栏，空缺一律是 null / 空数组，不编造。 */
    public record SessionSummary(String topic, SourcedText taskGoal, List<SourcedText> confirmedUserConstraints,
                                 List<SourcedText> confirmedDecisions, List<SourcedText> discussionNotes,
                                 List<WorkItem> workItems, List<SourcedText> openQuestions,
                                 List<SourcedText> nextSteps, List<Reference> references) {
        public SessionSummary {
            confirmedUserConstraints = copy(confirmedUserConstraints);
            confirmedDecisions = copy(confirmedDecisions);
            discussionNotes = copy(discussionNotes);
            workItems = copy(workItems);
            openQuestions = copy(openQuestions);
            nextSteps = copy(nextSteps);
            references = copy(references);
        }

        /** 整份都没剩下东西：发布方据此走"没有可发布的摘要"而不是交一份空检查点（§6.3 第 5 条）。 */
        public boolean empty() {
            return (topic == null || topic.isBlank()) && taskGoal == null && confirmedUserConstraints.isEmpty()
                    && confirmedDecisions.isEmpty() && discussionNotes.isEmpty() && workItems.isEmpty()
                    && openQuestions.isEmpty() && nextSteps.isEmpty() && references.isEmpty();
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    /**
     * @param body 上游原文（允许一层包住整份返回的 Markdown 围栏，复用提取侧那条剥离规则）
     * @param allowedTurnIds 本批<b>合格完成</b>轮次的 ID 集合：引用 ABORTED/停止轮次在这一步就被挡（§6.2）
     * @param allowedReferences 服务端派生的允许引用；模型只能抄，不能自己造（§6.1 第 3 段）
     */
    public static Outcome validate(String body, Set<Long> allowedTurnIds, List<Reference> allowedReferences,
                                   ObjectMapper mapper) {
        FormatStatus envelope = envelope(body, mapper);
        if (envelope != FormatStatus.OK) {
            return Outcome.of(envelope);
        }
        JsonNode root;
        List<String> rejections = new ArrayList<>();
        try {
            root = mapper.readTree(MemoryValidator.stripFence(body));
        } catch (Exception error) {
            return Outcome.of(FormatStatus.NOT_JSON);
        }
        String topic = topic(root.get("topic"), rejections);
        SourcedText goal = sourced(root.get("taskGoal"), "taskGoal", allowedTurnIds, rejections);
        List<SourcedText> constraints = sourcedArray(root.get("confirmedUserConstraints"),
                "confirmedUserConstraints", MAX_CONSTRAINT_ITEMS, allowedTurnIds, rejections);
        List<SourcedText> decisions = sourcedArray(root.get("confirmedDecisions"), "confirmedDecisions",
                MAX_ARRAY_ITEMS, allowedTurnIds, rejections);
        List<SourcedText> notes = sourcedArray(root.get("discussionNotes"), "discussionNotes", MAX_ARRAY_ITEMS,
                allowedTurnIds, rejections);
        List<WorkItem> workItems = workItems(root.get("workItems"), allowedTurnIds, rejections);
        List<SourcedText> questions = sourcedArray(root.get("openQuestions"), "openQuestions", MAX_ARRAY_ITEMS,
                allowedTurnIds, rejections);
        List<SourcedText> nextSteps = sourcedArray(root.get("nextSteps"), "nextSteps", MAX_ARRAY_ITEMS,
                allowedTurnIds, rejections);
        List<Reference> references = references(root.get("references"), allowedReferences, rejections);
        return new Outcome(FormatStatus.OK, new SessionSummary(topic, goal, constraints, decisions, notes, workItems,
                questions, nextSteps, references), rejections);
    }

    // ---- 整份能不能用 ----

    private static FormatStatus envelope(String body, ObjectMapper mapper) {
        if (body == null) {
            return FormatStatus.NOT_JSON;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return FormatStatus.OVER_BYTE_LIMIT;
        }
        JsonNode root;
        try {
            root = mapper.readTree(MemoryValidator.stripFence(body));
        } catch (Exception error) {
            return FormatStatus.NOT_JSON;
        }
        if (root == null || !root.isObject()) {
            return FormatStatus.NOT_OBJECT;
        }
        if (!"2".equals(root.path("schemaVersion").asText(""))) {
            return FormatStatus.WRONG_SCHEMA_VERSION;
        }
        for (Iterator<String> fields = root.fieldNames(); fields.hasNext(); ) {
            if (!TOP_LEVEL_FIELDS.contains(fields.next())) {
                return FormatStatus.UNEXPECTED_FIELD;
            }
        }
        for (String field : REQUIRED_FIELDS) {
            if (!root.has(field)) {
                return FormatStatus.MISSING_FIELD;
            }
        }
        // 九栏里除 topic/taskGoal/schemaVersion 外都必须是数组：写成字符串或对象就是结构错误
        for (String array : List.of("confirmedUserConstraints", "confirmedDecisions", "discussionNotes", "workItems",
                "openQuestions", "nextSteps", "references")) {
            if (!root.path(array).isArray()) {
                return FormatStatus.FIELD_NOT_ARRAY;
            }
        }
        return FormatStatus.OK;
    }

    // ---- 逐字段 ----

    private static String topic(JsonNode node, List<String> rejections) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || exceeds(node.asText(), MAX_TOPIC_CODE_POINTS)) {
            rejections.add("TOPIC_INVALID");
            return null;
        }
        String text = normalize(node.asText());
        if (text.isEmpty()) {
            return null;
        }
        if (isCredential(text)) {
            rejections.add("CREDENTIAL_TOPIC");
            return null;
        }
        return text;
    }

    /** {@code taskGoal} 与数组元素共用：null 就是"这一项本来没有"，不算违规。 */
    private static SourcedText sourced(JsonNode node, String field, Set<Long> allowedTurnIds,
                                       List<String> rejections) {
        if (node == null || node.isNull()) {
            return null;
        }
        String problem = sourcedProblem(node, field, allowedTurnIds);
        if (problem != null) {
            rejections.add(problem);
            return null;
        }
        return toSourced(node);
    }

    private static List<SourcedText> sourcedArray(JsonNode node, String field, int cap, Set<Long> allowedTurnIds,
                                                  List<String> rejections) {
        List<SourcedText> items = new ArrayList<>();
        for (JsonNode element : node) {
            if (items.size() >= cap) {
                // 超出上限不是整份作废：按数组原顺序留下前 N 条，其余如实报告（§6.2 尾段）
                rejections.add("ARRAY_OVER_LIMIT:" + field);
                break;
            }
            String problem = sourcedProblem(element, field, allowedTurnIds);
            if (problem != null) {
                rejections.add(problem);
                continue;
            }
            items.add(toSourced(element));
        }
        return items;
    }

    private static List<WorkItem> workItems(JsonNode node, Set<Long> allowedTurnIds, List<String> rejections) {
        List<WorkItem> items = new ArrayList<>();
        for (JsonNode element : node) {
            if (items.size() >= MAX_ARRAY_ITEMS) {
                rejections.add("ARRAY_OVER_LIMIT:workItems");
                break;
            }
            if (!element.isObject()) {
                rejections.add("ITEM_NOT_OBJECT:workItems");
                continue;
            }
            Set<String> names = fieldNames(element);
            if (!WORK_ITEM_FIELDS.equals(names)) {
                rejections.add(names.containsAll(SOURCED_FIELDS) && names.contains("state")
                        ? "ITEM_UNEXPECTED_FIELD:workItems" : "ITEM_FIELD_MISSING:workItems");
                continue;
            }
            String problem = sourcedProblem(element, "workItems", allowedTurnIds);
            if (problem != null) {
                rejections.add(problem);
                continue;
            }
            String state = element.path("state").asText("").trim().toLowerCase(Locale.ROOT);
            if (!WORK_STATES.contains(state)) {
                // verified 以及任何表外状态都在这格挡下：模型不能给自己发"已验证完成"（§6.2）
                rejections.add("WORK_STATE_NOT_ALLOWED");
                continue;
            }
            if ("user_reported_done".equals(state) && !"user".equals(element.path("sourceRole").asText(""))) {
                rejections.add("USER_REPORTED_DONE_MUST_BE_USER");
                continue;
            }
            items.add(new WorkItem(toSourced(element), state));
        }
        return items;
    }

    private static List<Reference> references(JsonNode node, List<Reference> allowedReferences,
                                              List<String> rejections) {
        List<Reference> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Reference> allowed = allowedReferences == null ? List.of() : allowedReferences;
        for (JsonNode element : node) {
            if (kept.size() >= MAX_REFERENCES) {
                rejections.add("ARRAY_OVER_LIMIT:references");
                break;
            }
            if (!element.isObject() || !REFERENCE_FIELDS.equals(fieldNames(element))) {
                rejections.add("REFERENCE_SHAPE_INVALID");
                continue;
            }
            if (!element.path("pageId").isNumber() || element.path("pageId").asLong() <= 0
                    || !element.path("sourceTurnId").isNumber() || element.path("sourceTurnId").asLong() <= 0) {
                rejections.add("REFERENCE_ID_INVALID");
                continue;
            }
            JsonNode version = element.path("contentVersion");
            if (!version.isTextual() || version.asText().isBlank() || exceeds(version.asText(),
                    MAX_VERSION_CODE_POINTS)) {
                rejections.add("REFERENCE_VERSION_INVALID");
                continue;
            }
            long pageId = element.path("pageId").asLong();
            String contentVersion = version.asText().trim();
            long sourceTurnId = element.path("sourceTurnId").asLong();
            // 只允许"本会话实际提供过的那个版本"：改了版本号就是伪造引用（§6.1 第 3 段）
            boolean known = allowed.stream().anyMatch(candidate -> candidate.pageId() == pageId
                    && candidate.contentVersion().equals(contentVersion)
                    && candidate.sourceTurnId() == sourceTurnId);
            if (!known) {
                rejections.add("REFERENCE_NOT_ALLOWED");
                continue;
            }
            if (seen.add(pageId + ":" + contentVersion + ":" + sourceTurnId)) {
                kept.add(new Reference(pageId, contentVersion, sourceTurnId));
            }
        }
        return kept;
    }

    /** 一个文本项自己的合规性：字段集合、长度、角色与来源 ID。返回 null 表示没问题。 */
    private static String sourcedProblem(JsonNode node, String field, Set<Long> allowedTurnIds) {
        if (!node.isObject()) {
            return "ITEM_NOT_OBJECT:" + field;
        }
        // §6.1 把 SourcedText 定死为三字段，只有 workItems 才多一个 state：别处出现 state 就是多余字段，
        // 不能因为形状"能兼容"就静默忽略——整条链的口径是闭集（复核 F1）
        boolean workItemShape = "workItems".equals(field) && WORK_ITEM_FIELDS.equals(fieldNames(node));
        if (!SOURCED_FIELDS.equals(fieldNames(node)) && !workItemShape) {
            return "ITEM_FIELDS_INVALID:" + field;
        }
        JsonNode text = node.path("text");
        if (!text.isTextual() || text.asText().isBlank()) {
            return "ITEM_TEXT_REQUIRED:" + field;
        }
        if (exceeds(text.asText(), MAX_TEXT_CODE_POINTS)) {
            return "ITEM_TEXT_TOO_LONG:" + field;
        }
        if (isCredential(normalize(text.asText()))) {
            return "CREDENTIAL_ITEM:" + field;
        }
        String role = node.path("sourceRole").asText("").trim().toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            return "SOURCE_ROLE_INVALID:" + field;
        }
        // 助手不能替用户表态：目标、约束与"已确认决定"必须是 user 来源（§6.1 第 3 段）
        if (("taskGoal".equals(field) || "confirmedUserConstraints".equals(field)
                || "confirmedDecisions".equals(field)) && !"user".equals(role)) {
            return USER_ONLY_PREFIX + field;
        }
        JsonNode ids = node.path("sourceTurnIds");
        if (!ids.isArray() || ids.isEmpty() || ids.size() > MAX_SOURCES_PER_ITEM) {
            return "SOURCE_TURN_IDS_INVALID:" + field;
        }
        List<Long> turnIds = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isNumber() || id.asLong() <= 0) {
                return "SOURCE_TURN_IDS_INVALID:" + field;
            }
            if (!allowedTurnIds.contains(id.asLong())) {
                // 不在本批合格来源里：可能是编的，也可能是被跳过/停止/删除的那一轮（§6.2）
                return "SOURCE_NOT_IN_BATCH:" + field;
            }
            turnIds.add(id.asLong());
        }
        return null;
    }

    private static SourcedText toSourced(JsonNode node) {
        List<Long> turnIds = new ArrayList<>();
        for (JsonNode id : node.path("sourceTurnIds")) {
            turnIds.add(id.asLong());
        }
        return new SourcedText(normalize(node.path("text").asText()),
                node.path("sourceRole").asText("").trim().toLowerCase(Locale.ROOT), turnIds);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        for (Iterator<String> iterator = node.fieldNames(); iterator.hasNext(); ) {
            names.add(iterator.next());
        }
        return names;
    }

    /**
     * 只折叠空白：摘要文本要原样保留否定、数值与期限（§6 prompt"保留否定、数值和期限"），
     * 所以这里绝不改标点、不截断、不做任何"看起来更干净"的改写。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean isCredential(String value) {
        return MemorySensitivity.classify(value) == MemorySensitivity.Level.CREDENTIAL;
    }

    private static boolean exceeds(String value, int codePoints) {
        return value != null && value.codePointCount(0, value.length()) > codePoints;
    }

    /** 输入侧的允许引用：由调用方从"本批实际提供的 citation metadata"派生，模型只能抄。 */
    public static Set<Long> turnIdsOf(List<MaintenancePorts.SourceTurn> turns) {
        Set<Long> ids = new LinkedHashSet<>();
        for (MaintenancePorts.SourceTurn turn : turns) {
            ids.add(turn.turnId());
        }
        return ids;
    }
}
