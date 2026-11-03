package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.SessionSummaryRenderer.Published;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.SessionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 发布视图的确定性渲染与裁剪（contracts §6.3 第 2-3 条；执行方案 §8.4 第 4 条）。
 *
 * <p>这里只钉三件事：删的是整条而不是半句、删除顺序固定（同输入必同输出，§8.5 的"相同输入不再调用"靠它）、
 * 最小必要项装不下时宁可不发。预算一律按估算器现算，不写死数字。</p>
 */
class SessionSummaryRendererTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenEstimator ESTIMATOR = new TokenEstimator(MAPPER);
    private static final Set<Long> BATCH = Set.of(51L, 52L);

    private static final String FULL = """
            主题：二分查找复习
            目标：复习二分查找（user#51）
            约束：只给方案，先不改笔记（user#51）
            决定：先检查边界条件，卡片暂不做（user#52）
            工作：助手声称完成边界检查，尚未核验〔unverified〕（assistant#52）
            下一步：可用例题核验边界（assistant#52）
            待回答：要不要加练（user#52）
            讨论：助手建议先检查边界（assistant#51）
            引用：page9@fixture-v3（turn52）""";

    @Test
    void publishesEverySectionInFixedPriorityOrder() throws Exception {
        Published published = SessionSummaryRenderer.render(summary(richBody()), 4_000, ESTIMATOR);

        assertFalse(published.unavailable());
        assertEquals(FULL, published.modelSummary());
        assertEquals(0, published.omittedItems(), "预算够就不该删任何东西");
        assertEquals(ESTIMATOR.estimateText(FULL), published.estimatedTokens());
    }

    @Test
    void dropsReferencesAndDiscussionBeforeTheUserConfirmedItems() throws Exception {
        String[] lines = FULL.split("\n");
        String required = String.join("\n", List.of(lines).subList(0, 5));
        SessionSummary summary = summary(richBody());

        Published minimal = SessionSummaryRenderer.render(summary, ESTIMATOR.estimateText(required), ESTIMATOR);
        assertEquals(required, minimal.modelSummary(),
                "预算紧到只剩最小必要项：引用、讨论、待回答、下一步都整条删掉（§6.3 第 2 条顺序）");
        assertEquals(4, minimal.omittedItems());

        String withNextStep = required + "\n" + lines[5] + "\n" + lines[6];
        Published partial = SessionSummaryRenderer.render(summary, ESTIMATOR.estimateText(withNextStep), ESTIMATOR);
        assertEquals(withNextStep, partial.modelSummary(), "下一步与待回答排在讨论之前保留");
        assertFalse(partial.modelSummary().contains("讨论："));
        assertFalse(partial.modelSummary().contains("引用："));
    }

    @Test
    void neverTruncatesInsideAnItem() throws Exception {
        // 预算只够最小必要项：留下的每一行必须是完整草稿里的整行，不能被切半句
        Published published = SessionSummaryRenderer.render(summary(richBody()),
                ESTIMATOR.estimateText(expectedProtected()) + 1, ESTIMATOR);
        for (String line : published.modelSummary().split("\n")) {
            assertTrue(List.of(FULL.split("\n")).contains(line), "发布出去的整行必须是完整草稿的一行: " + line);
        }
        assertEquals(expectedProtected(), published.modelSummary());
    }

    @Test
    void unavailableWhenEvenTheRequiredItemsDoNotFit() throws Exception {
        Published published = SessionSummaryRenderer.render(summary(richBody()), 4, ESTIMATOR);

        assertTrue(published.unavailable(), "最小必要项仍过大就不给摘要，而不是临时加配额（§6.3 第 2 条）");
        assertEquals("", published.modelSummary());
        assertEquals(0, published.estimatedTokens());
    }

    @Test
    void emptyStructureIsNotPublishedAsAnEmptyCheckpoint() throws Exception {
        SessionSummary empty = summary("{\"schemaVersion\":2,\"topic\":\"\",\"taskGoal\":null,"
                + "\"confirmedUserConstraints\":[],\"confirmedDecisions\":[],\"discussionNotes\":[],"
                + "\"workItems\":[],\"openQuestions\":[],\"nextSteps\":[],\"references\":[]}");

        assertTrue(empty.empty());
        assertTrue(SessionSummaryRenderer.render(empty, 600, ESTIMATOR).unavailable(),
                "没有旧摘要时不创建伪有效空检查点（§6.3 第 5 条）");
    }

    @Test
    void duplicateSentencesAreRenderedOnce() throws Exception {
        Published published = SessionSummaryRenderer.render(summary(replace(FULL_BODY,
                "\"discussionNotes\":[]",
                "\"discussionNotes\":[" + item("可用例题核验边界", "assistant", 52) + "]")), 4_000, ESTIMATOR);

        assertEquals(1, occurrences(published.modelSummary(), "可用例题核验边界"),
                "同一句话跨栏目重复时只留优先级更高的那条（§6.3 第 2 条第一步）");
        assertTrue(published.modelSummary().contains("下一步：可用例题核验边界"));
        assertFalse(published.modelSummary().contains("讨论：可用例题核验边界"));
    }

    @Test
    void renderingIsDeterministicForTheSameStructure() throws Exception {
        SessionSummary summary = summary(richBody());
        assertEquals(SessionSummaryRenderer.render(summary, 40, ESTIMATOR).modelSummary(),
                SessionSummaryRenderer.render(summary, 40, ESTIMATOR).modelSummary(),
                "同结构必须同文本，否则相同输入会被反复重发（§8.5）");
    }

    @Test
    void reduceDropsWholeTailLinesAndKeepsRequiredItems() throws Exception {
        int protectedTokens = ESTIMATOR.estimateText(expectedProtected());

        assertEquals(expectedProtected(), SessionSummaryRenderer.reduce(FULL, protectedTokens, ESTIMATOR));
        assertEquals(FULL, SessionSummaryRenderer.reduce(FULL, 4_000, ESTIMATOR), "预算够就原样用");
        assertEquals("", SessionSummaryRenderer.reduce(FULL, 2, ESTIMATOR),
                "连最小必要项都挤不下就整份不用，不截半句（§6.3 第 3 条）");
    }

    // ---- 辅助 ----

    private static SessionSummary summary(String body) {
        return SessionSummaryValidator.validate(body, BATCH,
                List.of(new SessionSummaryValidator.Reference(9, "fixture-v3", 52)), MAPPER).summary();
    }

    private static String expectedProtected() {
        String[] lines = FULL.split("\n");
        return String.join("\n", List.of(lines).subList(0, 5));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    private static String replace(String body, String target, String replacement) {
        assertTrue(body.contains(target), body);
        return body.replace(target, replacement);
    }

    /** 覆盖全部九栏的草稿；§6.2 的合成输入之外再补待回答与讨论两栏，好测删除顺序。 */
    private static String richBody() {
        return FULL_BODY.replace("\"discussionNotes\":[]",
                        "\"discussionNotes\":[" + item("助手建议先检查边界", "assistant", 51) + "]")
                .replace("\"openQuestions\":[]", "\"openQuestions\":[" + item("要不要加练", "user", 52) + "]");
    }

    private static final String FULL_BODY = "{\"schemaVersion\":2,\"topic\":\"二分查找复习\","
            + "\"taskGoal\":" + item("复习二分查找", "user", 51) + ","
            + "\"confirmedUserConstraints\":[" + item("只给方案，先不改笔记", "user", 51) + "],"
            + "\"confirmedDecisions\":[" + item("先检查边界条件，卡片暂不做", "user", 52) + "],"
            + "\"discussionNotes\":[],\"workItems\":["
            + "{\"text\":\"助手声称完成边界检查，尚未核验\",\"sourceRole\":\"assistant\",\"sourceTurnIds\":[52],"
            + "\"state\":\"unverified\"}],"
            + "\"openQuestions\":[],\"nextSteps\":[" + item("可用例题核验边界", "assistant", 52) + "],"
            + "\"references\":[{\"pageId\":9,\"contentVersion\":\"fixture-v3\",\"sourceTurnId\":52}]}";

    private static String item(String text, String role, long turnId) {
        return "{\"text\":\"" + text + "\",\"sourceRole\":\"" + role + "\",\"sourceTurnIds\":[" + turnId + "]}";
    }
}
