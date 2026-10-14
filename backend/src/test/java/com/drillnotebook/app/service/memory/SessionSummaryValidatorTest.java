package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.service.memory.SessionSummaryValidator.FormatStatus;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.Outcome;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.Reference;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.SessionSummary;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.SourcedText;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code SESSION_SUMMARY_V2} 的服务端校验（contracts §6.1 字段约束、§6.2 正反例、§6.3 第 1 条；执行方案 §8.4 第 2-3 条）。
 *
 * <p>纯函数用例：整份能不能用与单条能不能留必须能分别证伪，所以每个用例只换一栏，
 * 并断言"删的是这一条而不是整份"。§6.2 的合成正例按原文照抄，必须一次通过——
 * 正例过不了就说明校验器在替模型猜内容。</p>
 */
class SessionSummaryValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<Long> BATCH = Set.of(51L, 52L);
    private static final List<Reference> ALLOWED_REFERENCES = List.of(new Reference(9, "fixture-v3", 52));

    private static final String REFERENCE_ONCE = "{\"pageId\":9,\"contentVersion\":\"fixture-v3\","
            + "\"sourceTurnId\":52}";
    private static final String TOPIC = "\"topic\":\"二分查找复习\"";
    private static final String GOAL = "\"taskGoal\":" + item("复习二分查找", "user", 51);
    private static final String CONSTRAINTS = section("confirmedUserConstraints", item("只给方案，先不改笔记", "user", 51));
    private static final String DECISIONS = section("confirmedDecisions",
            item("先检查边界条件，卡片暂不做", "user", 52));
    private static final String NOTES = section("discussionNotes", "");
    private static final String WORK = section("workItems",
            workItem("助手声称完成边界检查，尚未核验", "assistant", 52, "unverified"));
    private static final String QUESTIONS = section("openQuestions", "");
    private static final String NEXT_STEPS = section("nextSteps", item("可用例题核验边界", "assistant", 52));
    private static final String REFERENCES = section("references", REFERENCE_ONCE);

    // ---- §6.2 正例 ----

    @Test
    void contractsExamplePassesTheWholeChain() {
        Outcome outcome = validate(example());
        assertEquals(FormatStatus.OK, outcome.format(), outcome.rejections().toString());
        assertTrue(outcome.rejections().isEmpty(), outcome.rejections().toString());
        SessionSummary summary = outcome.summary();
        assertEquals("二分查找复习", summary.topic());
        assertEquals("复习二分查找", summary.taskGoal().text());
        assertEquals(List.of(51L), summary.taskGoal().sourceTurnIds());
        assertEquals(List.of("只给方案，先不改笔记"), texts(summary.confirmedUserConstraints()));
        assertEquals("先检查边界条件，卡片暂不做", summary.confirmedDecisions().get(0).text());
        assertTrue(summary.discussionNotes().isEmpty(), "空栏目保持空，不编造内容（§6.1）");
        assertEquals(1, summary.workItems().size());
        assertEquals("unverified", summary.workItems().get(0).state(), "助手自述完成只能是未验证（§6.2）");
        assertEquals("assistant", summary.workItems().get(0).source().sourceRole());
        assertEquals("可用例题核验边界", summary.nextSteps().get(0).text());
        assertEquals(ALLOWED_REFERENCES, summary.references());
    }

    @Test
    void emptyDraftIsValidButCarriesNothing() {
        Outcome outcome = validate("{\"schemaVersion\":2,\"topic\":\"\",\"taskGoal\":null,"
                + "\"confirmedUserConstraints\":[],\"confirmedDecisions\":[],\"discussionNotes\":[],"
                + "\"workItems\":[],\"openQuestions\":[],\"nextSteps\":[],\"references\":[]}");
        assertEquals(FormatStatus.OK, outcome.format());
        assertTrue(outcome.summary().empty(), "没有可记内容就是空结构，校验器不能替它补一栏（§6.1）");
    }

    @Test
    void onlyOneWrappingFenceIsStripped() {
        assertEquals(FormatStatus.OK, validate("```json\n" + example() + "\n```").format());
    }

    // ---- 角色与来源：模型不能替用户表态，也不能指向没发过的轮次 ----

    @Test
    void assistantCannotConfirmGoalConstraintsOrDecisions() {
        Outcome goal = validate(swap(GOAL, "\"taskGoal\":" + item("复习线代", "assistant", 51)));
        assertEquals(List.of("USER_ONLY_FIELD:taskGoal"), goal.rejections());
        assertNull(goal.summary().taskGoal());

        Outcome constraint = validate(swap(CONSTRAINTS,
                section("confirmedUserConstraints", item("只看讲义", "assistant", 51))));
        assertEquals(List.of("USER_ONLY_FIELD:confirmedUserConstraints"), constraint.rejections());
        assertTrue(constraint.summary().confirmedUserConstraints().isEmpty());

        Outcome decision = validate(swap(DECISIONS, section("confirmedDecisions", item("用卡片背", "assistant", 52))));
        assertEquals(List.of("USER_ONLY_FIELD:confirmedDecisions"), decision.rejections(),
                "未采纳的助手建议不能升级为决定（§8.4 第 2 条）");
    }

    @Test
    void userReportedDoneMustBeSaidByTheUser() {
        Outcome accepted = validate(swap(WORK,
                section("workItems", workItem("边界我已验完", "user", 52, "user_reported_done"))));
        assertTrue(accepted.rejections().isEmpty(), accepted.rejections().toString());

        Outcome claimed = validate(swap(WORK,
                section("workItems", workItem("边界我已验完", "assistant", 52, "user_reported_done"))));
        assertEquals(List.of("USER_REPORTED_DONE_MUST_BE_USER"), claimed.rejections());
    }

    @Test
    void verifiedIsNotAStateTheModelMayDeclare() {
        Outcome outcome = validate(swap(WORK,
                section("workItems", workItem("已完成边界检查", "assistant", 52, "verified"))));
        assertEquals(List.of("WORK_STATE_NOT_ALLOWED"), outcome.rejections(), "禁止由 LLM 宣告 verified（§6.2）");
        assertTrue(outcome.summary().workItems().isEmpty());
    }

    @Test
    void sourcesMustBeTurnsActuallySentAndNoMoreThanTwo() {
        Outcome outside = validate(swap(QUESTIONS, section("openQuestions", item("要不要加练", "user", 99))));
        assertEquals(List.of("SOURCE_NOT_IN_BATCH:openQuestions"), outside.rejections(),
                "指向没发过的轮次（停止、中断或删除的都算）就是伪造来源");

        Outcome threeSources = validate(swap(QUESTIONS,
                section("openQuestions", item("要不要加练", "user", 51, 52, 51))));
        assertEquals(List.of("SOURCE_TURN_IDS_INVALID:openQuestions"), threeSources.rejections());

        Outcome noSource = validate(swap(QUESTIONS, section("openQuestions", item("要不要加练", "user"))));
        assertEquals(List.of("SOURCE_TURN_IDS_INVALID:openQuestions"), noSource.rejections(),
                "没有来源的句子不留进摘要（§6.1）");
    }

    @Test
    void stateIsOnlyAWorkItemField() {
        // §6.1 把 SourcedText 定死成三字段闭集，只有 workItems 多一个 state。四字段形状放进别的栏目，
        // 不能因为"看起来能兼容"就把 state 静默丢掉（外审 F1）
        Outcome constraint = validate(swap(CONSTRAINTS,
                section("confirmedUserConstraints", workItem("卡片先不归档", "user", 51, "verified"))));
        assertEquals(List.of("ITEM_FIELDS_INVALID:confirmedUserConstraints"), constraint.rejections(),
                constraint.rejections().toString());
        assertTrue(constraint.summary().confirmedUserConstraints().isEmpty(), "拒掉的项不能留在发布结构里");

        Outcome goal = validate(swap(GOAL, "\"taskGoal\":" + workItem("复习二分查找", "user", 51, "planned")));
        assertEquals(List.of("ITEM_FIELDS_INVALID:taskGoal"), goal.rejections());
        assertNull(goal.summary().taskGoal());

        // 同一个形状放进 workItems 仍算合规形状：词表门与字段门是两道不同的闸
        Outcome legal = validate(swap(WORK, section("workItems", workItem("边界已验完", "user", 52, "verified"))));
        assertEquals(List.of("WORK_STATE_NOT_ALLOWED"), legal.rejections(), legal.rejections().toString());
    }

    @Test
    void missingOrBlankTextAndBadRoleAreRejectedPerItem() {
        assertEquals(List.of("ITEM_TEXT_REQUIRED:discussionNotes"),
                validate(swap(NOTES, section("discussionNotes",
                        "{\"text\":\"\",\"sourceRole\":\"assistant\",\"sourceTurnIds\":[52]}"))).rejections());
        assertEquals(List.of("SOURCE_ROLE_INVALID:discussionNotes"),
                validate(swap(NOTES, section("discussionNotes", item("先做例题", "system", 52)))).rejections(),
                "注入指令那一路的角色不存在（§4.3）");
    }

    // ---- 引用：只许抄服务端实际提供过的版本 ----

    @Test
    void referencesMustMatchWhatTheSessionActuallyProvided() {
        assertEquals(List.of("REFERENCE_NOT_ALLOWED"),
                validate(swap(REFERENCES, section("references", "{\"pageId\":9,\"contentVersion\":\"fixture-v9\","
                        + "\"sourceTurnId\":52}"))).rejections(),
                "版本号由服务端读取时决定，不采信模型或前端随填（§6.1 第 3 段）");
        assertEquals(List.of("REFERENCE_NOT_ALLOWED"),
                validate(swap(REFERENCES, section("references", "{\"pageId\":10,"
                        + "\"contentVersion\":\"fixture-v3\",\"sourceTurnId\":52}"))).rejections());
        assertEquals(List.of("REFERENCE_NOT_ALLOWED"),
                validate(swap(REFERENCES, section("references", "{\"pageId\":9,\"contentVersion\":\"fixture-v3\","
                        + "\"sourceTurnId\":77}"))).rejections(), "引用的来源轮次也要落在本批合格来源里");
        assertEquals(List.of("REFERENCE_SHAPE_INVALID"),
                validate(swap(REFERENCES, section("references",
                        "{\"pageId\":9,\"contentVersion\":\"fixture-v3\"}"))).rejections());
        assertEquals(List.of("REFERENCE_ID_INVALID"),
                validate(swap(REFERENCES, section("references", "{\"pageId\":\"9\","
                        + "\"contentVersion\":\"fixture-v3\",\"sourceTurnId\":52}"))).rejections());
    }

    @Test
    void duplicateReferencesCollapseAndOverLimitTailIsReported() {
        Outcome duplicates = validate(swap(REFERENCES, section("references", REFERENCE_ONCE + "," + REFERENCE_ONCE)));
        assertTrue(duplicates.rejections().isEmpty(), duplicates.rejections().toString());
        assertEquals(1, duplicates.summary().references().size(), "同一版本重复引用只算一条（§6.3 第 2 条先去重）");

        // 四条都"真的提供过"时才轮到上限判定：第三条之后整条截掉并报告，不静默丢
        String four = "{\"pageId\":9,\"contentVersion\":\"v1\",\"sourceTurnId\":51},"
                + "{\"pageId\":9,\"contentVersion\":\"v2\",\"sourceTurnId\":51},"
                + "{\"pageId\":9,\"contentVersion\":\"v3\",\"sourceTurnId\":52},"
                + "{\"pageId\":10,\"contentVersion\":\"v4\",\"sourceTurnId\":52}";
        Outcome overCap = SessionSummaryValidator.validate(swap(REFERENCES, section("references", four)), BATCH,
                List.of(new Reference(9, "v1", 51), new Reference(9, "v2", 51), new Reference(9, "v3", 52),
                        new Reference(10, "v4", 52)), MAPPER);
        assertEquals(SessionSummaryValidator.MAX_REFERENCES, overCap.summary().references().size());
        assertEquals(List.of("ARRAY_OVER_LIMIT:references"), overCap.rejections());
    }

    // ---- 整份结构：额外字段、缺字段、版本、类型与长度 ----

    @Test
    void structuralProblemsFailTheWholeDraftNotOneItem() {
        assertEquals(FormatStatus.UNEXPECTED_FIELD,
                validate(example().substring(0, example().length() - 1) + ",\"summaryNote\":\"顺便总结\"}").format());
        assertEquals(FormatStatus.MISSING_FIELD,
                validate("{\"schemaVersion\":2,\"topic\":\"\",\"taskGoal\":null,"
                        + "\"confirmedUserConstraints\":[],\"confirmedDecisions\":[],\"discussionNotes\":[],"
                        + "\"workItems\":[],\"openQuestions\":[],\"nextSteps\":[]}").format(),
                "references 缺失就是缺字段，不能当\"没有引用\"放过（§6.1）");
        assertEquals(FormatStatus.WRONG_SCHEMA_VERSION,
                validate(example().replace("\"schemaVersion\":2", "\"schemaVersion\":1")).format(),
                "V1 摘要不按 V2 解释（§6.3 第 1 条）");
        assertEquals(FormatStatus.FIELD_NOT_ARRAY, validate(swap(WORK, "\"workItems\":{}")).format());
        assertEquals(FormatStatus.NOT_OBJECT, validate("[1,2]").format());
        assertEquals(FormatStatus.NOT_JSON, validate("这段草稿不是 JSON").format());
        assertEquals(FormatStatus.OVER_BYTE_LIMIT,
                validate("{\"schemaVersion\":2,\"padding\":\"" + "字".repeat(40_000) + "\"}").format(),
                "先看字节数再解析（§6.3 第 1 条）");
    }

    @Test
    void lengthsAreCountedInCodePointsAndNeverTruncated() {
        Outcome topic = validate(swap(TOPIC, "\"topic\":\""
                + "长".repeat(SessionSummaryValidator.MAX_TOPIC_CODE_POINTS + 1) + "\""));
        assertEquals(List.of("TOPIC_INVALID"), topic.rejections());
        assertNull(topic.summary().topic());

        Outcome text = validate(swap(CONSTRAINTS, section("confirmedUserConstraints",
                item("长".repeat(SessionSummaryValidator.MAX_TEXT_CODE_POINTS + 1), "user", 51))));
        assertEquals(List.of("ITEM_TEXT_TOO_LONG:confirmedUserConstraints"), text.rejections(),
                "超长整条作废，不裁到 80 码点冒充原文");
    }

    @Test
    void credentialItemsAreDroppedWhileEverythingElseSurvives() {
        Outcome outcome = validate(swap(CONSTRAINTS, section("confirmedUserConstraints",
                item("密码：Alpha9-hunter2-token", "user", 51) + "," + item("先给方案再动手", "user", 51))));
        assertEquals(List.of("CREDENTIAL_ITEM:confirmedUserConstraints"), outcome.rejections());
        assertEquals(1, outcome.summary().confirmedUserConstraints().size(),
                "凭据那条删掉，其余继续发布——§5.3 拒的是这条内容，不是整份摘要");

        Outcome topic = validate(swap(TOPIC, "\"topic\":\"我的 API Key 是 sk-live-abcdef0123456789\""));
        assertEquals(List.of("CREDENTIAL_TOPIC"), topic.rejections());
    }

    @Test
    void arrayCapsKeepTheHeadAndReportTheTail() {
        StringBuilder five = new StringBuilder();
        for (int index = 0; index < 5; index++) {
            five.append(index == 0 ? "" : ",").append(item("约束" + index, "user", 51));
        }
        Outcome outcome = validate(swap(CONSTRAINTS, section("confirmedUserConstraints", five.toString())));
        assertEquals(SessionSummaryValidator.MAX_CONSTRAINT_ITEMS,
                outcome.summary().confirmedUserConstraints().size());
        assertEquals(List.of("ARRAY_OVER_LIMIT:confirmedUserConstraints"), outcome.rejections());
    }

    // ---- 辅助 ----

    private static Outcome validate(String body) {
        return SessionSummaryValidator.validate(body, BATCH, ALLOWED_REFERENCES, MAPPER);
    }

    private static List<String> texts(List<SourcedText> items) {
        return items.stream().map(SourcedText::text).toList();
    }

    /** §6.2 的合成正例草稿：九栏齐全，除被换掉的那一栏外一律不动。 */
    private static String example() {
        return "{\"schemaVersion\":2," + TOPIC + "," + GOAL + "," + CONSTRAINTS + "," + DECISIONS + "," + NOTES + ","
                + WORK + "," + QUESTIONS + "," + NEXT_STEPS + "," + REFERENCES + "}";
    }

    /** 整栏替换：先确认原栏确实存在，避免草稿形状被改坏却悄悄通过。 */
    private static String swap(String oldSection, String newSection) {
        String body = example();
        assertTrue(body.contains(oldSection), "草稿里没有这一栏: " + oldSection);
        return body.replace(oldSection, newSection);
    }

    private static String section(String name, String content) {
        return "\"" + name + "\":[" + content + "]";
    }

    private static String item(String text, String role, long... turnIds) {
        StringBuilder ids = new StringBuilder();
        for (long turnId : turnIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(turnId);
        }
        return "{\"text\":\"" + text + "\",\"sourceRole\":\"" + role + "\",\"sourceTurnIds\":[" + ids + "]}";
    }

    private static String workItem(String text, String role, long turnId, String state) {
        return "{\"text\":\"" + text + "\",\"sourceRole\":\"" + role + "\",\"sourceTurnIds\":[" + turnId
                + "],\"state\":\"" + state + "\"}";
    }
}
