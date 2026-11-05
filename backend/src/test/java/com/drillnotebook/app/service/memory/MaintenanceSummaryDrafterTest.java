package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.DraftResult;
import com.drillnotebook.app.service.memory.MaintenancePorts.PreviousSummary;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.Reference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 真实摘要端口"发出去的是什么"（执行方案 §8.4 第 2-4 条；contracts §6.1、§5.3、§6.3 第 1、4 条）。
 *
 * <p>上游客户端在这里换成记录请求体的假件：这一层能被证伪的只有<b>发送内容</b>与<b>那条不外发的线</b>，
 * 所以用例都钉在请求体和调用次数上。结构判定细则归 {@code SessionSummaryValidatorTest}，
 * 预算裁剪归 {@code SessionSummaryRendererTest}，排队、账本与落库归 {@code MemoryMaintenanceWorkerTest}。</p>
 */
class MaintenanceSummaryDrafterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenEstimator ESTIMATOR = new TokenEstimator(MAPPER);
    private static final long TURN_ID = 51L;
    private static final long MESSAGE_ID = 7001L;
    private static final String CREATED_AT = "2026-10-11T00:00:00.000Z";

    private MaintenanceHarness harness;
    private CapturingClient client;
    private MaintenanceSummaryDrafter drafter;

    @BeforeEach
    void setUp() throws Exception {
        harness = MaintenanceHarness.open("p8-drafter-");
        harness.createVaultAndUnlock();
        harness.grantMaintenanceConsent();
        client = new CapturingClient();
        drafter = new MaintenanceSummaryDrafter(new MaintenanceUpstream(client, harness.policy,
                new AiConfigRepository(harness.jdbc), new ApiKeyEncryptor(), MAPPER), ESTIMATOR, MAPPER);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    @Test
    void actualTemplateStillLeavesRoomForTheMinimumSourceBatch() {
        // P0a 那条"必须用实际模板做装包验证"搬到摘要侧：模板加每轮骨架之后还得装得下最小可摘要增量
        int framing = drafter.framingTokens();
        assertTrue(framing < MaintenanceRules.INPUT_TOKEN_BUDGET, "模板自己就把预算吃光了：" + framing);
        assertTrue(MaintenanceRules.INPUT_TOKEN_BUDGET - framing >= MaintenanceRules.SUMMARY_MIN_NEW_TOKENS,
                "扣掉模板与每轮骨架只剩 " + (MaintenanceRules.INPUT_TOKEN_BUDGET - framing)
                        + " 估算 Token，装不下 §8.4 第 1 条要求的 1,024 新增量");
    }

    @Test
    void sendsTheFixedPromptWithTheInputShapeOfSectionSixOne() throws Exception {
        client.reply = validBody("先背再练");

        drafter.draft(batch(1, 40), null);

        assertEquals(MaintenanceSummaryDrafter.systemPrompt(), client.system(), "系统提示词逐字固定（§6）");
        assertTrue(client.system().contains("禁止verified"), "工作状态的门要在提示词里就说清（§6.2）");
        assertTrue(client.system().contains(MaintenanceSummaryDrafter.OUTPUT_SHAPE),
                "输出结构必须随提示词发送，否则模型只能自己猜字段");
        assertTrue(client.lastJsonMode(), "维护调用要求 JSON 模式，思考链不能混进正文");
        Map<String, Object> input = MAPPER.readValue(client.user(), new TypeReference<>() {
        });
        assertEquals(2, ((Number) input.get("schemaVersion")).intValue());
        assertEquals(List.of(), input.get("allowedReferences"));
        List<?> turns = (List<?>) input.get("sourceTurns");
        assertEquals(1, turns.size(), "来源就是本批那一条轮次");
        Map<?, ?> turn = (Map<?, ?>) turns.get(0);
        assertEquals(5, turn.size(), "§6.1 要求的五格一个都不能少：" + turn.keySet());
        assertEquals(TURN_ID, ((Number) turn.get("turnId")).longValue());
        assertEquals(MESSAGE_ID, ((Number) turn.get("userMessageId")).longValue());
        assertEquals(MaintenanceHarness.pad("问题0 ", 40), turn.get("userText"));
        assertEquals(MaintenanceHarness.pad("回答0 ", 40), turn.get("assistantText"));
        assertEquals(CREATED_AT, turn.get("createdAt"));
    }

    @Test
    void previousSummaryAndAllowedReferencesRideAlong() throws Exception {
        client.reply = validBody("先背再练");

        drafter.draft(batch(1, 40, List.of(new Reference(9L, "fixture-v3", TURN_ID))), null);

        Map<String, Object> input = MAPPER.readValue(client.user(), new TypeReference<>() {
        });
        assertEquals("上一版：主题：二分查找复习", input.get("previousSummary"),
                "旧摘要原样带上，模型才谈得上\"用户纠正替换旧相应项\"（§6.1）");
        List<?> references = (List<?>) input.get("allowedReferences");
        assertEquals(1, references.size());
        Map<?, ?> reference = (Map<?, ?>) references.get(0);
        assertEquals(9L, ((Number) reference.get("pageId")).longValue());
        assertEquals("fixture-v3", reference.get("contentVersion"));
        assertEquals(TURN_ID, ((Number) reference.get("sourceTurnId")).longValue());
    }

    @Test
    void credentialsBecomeAPlaceholderOnlyOnTheSentCopy() throws Exception {
        client.reply = validBody("先背再练");
        String secret = "我的密码：supersecret123 帮我重做这道题";
        Batch batch = batchWith(secret);

        drafter.draft(batch, null);

        assertFalse(client.user().contains("supersecret123"), "凭据原文绝不进请求体（§5.3）");
        assertTrue(client.user().contains(MemorySensitivity.CREDENTIAL_PLACEHOLDER),
                "换占位符而不是整轮丢弃，coverage 不会因此挖洞（§5.3、D79）");
        assertTrue(client.user().contains("帮我重做这道题"), "只有命中的那段被换掉，其余正文照旧送");
        assertEquals(secret, batch.turns().get(0).userText(), "发送副本与来源记录是两件事，原文不动");
    }

    @Test
    void overBudgetPackageIsNotSentAtAll() throws Exception {
        client.reply = validBody("先背再练");

        DraftResult result = drafter.draft(batch(8, 900), null);

        assertEquals(0, client.calls(), "发送前复核整包：超线就一个字都不发（§6.3 第 4 条）");
        assertFalse(result.published(), "复核不过就本轮不发，而不是交一份空检查点");
        assertEquals(List.of("PACKAGE_OVER_BUDGET"), result.rejections());
    }

    @Test
    void overBudgetPackageDropsReferencesBeforeGivingUp() throws Exception {
        client.reply = validBody("先背再练");
        List<Reference> oversized = List.of(new Reference(9L, "x".repeat(3_000), TURN_ID),
                new Reference(10L, "y".repeat(3_000), TURN_ID), new Reference(11L, "z".repeat(3_000), TURN_ID));

        DraftResult result = drafter.draft(batch(1, 40, oversized), null);

        assertEquals(1, client.calls(), "引用是这份包里唯一\"丢了也不撒谎\"的数据，先扔它再谈发不发");
        assertTrue(result.published());
        assertFalse(client.user().contains("xxx"), "扔完引用的那次请求里不该再有允许清单");
    }

    @Test
    void repairRequestCarriesTheRejectedBodyAndNothingNew() throws Exception {
        client.reply = validBody("先背再练");
        String rejected = "{\"schemaVersion\":2,\"topic\":\"跑题了\"";

        drafter.draft(batch(1, 40, List.of(new Reference(9L, "fixture-v3", TURN_ID))), rejected);

        assertTrue(client.user().contains(rejected), "修复请求要拿得到那份坏原文（§8.2 第 7 条）");
        assertTrue(client.user().contains("<untrusted>"), "坏原文按不可信数据框起来，不当成指令");
        Map<String, Object> input = MAPPER.readValue(client.user(), new TypeReference<>() {
        });
        assertEquals(1, ((List<?>) input.get("sourceTurns")).size(), "修复不授予更多数据：来源还是同一批");
        assertEquals(1, ((List<?>) input.get("allowedReferences")).size(), "允许清单也不扩大");
    }

    @Test
    void malformedReplyEndsAsFormatFailureSoTheWorkerCanRepairOnce() throws Exception {
        client.reply = "这根本不是JSON";

        DraftResult result = drafter.draft(batch(1, 40), null);

        assertEquals(1, client.calls());
        assertFalse(result.formatOk(), "整份结构不合格是格式判定，不是外发失败");
        assertEquals(SessionSummaryValidator.FormatStatus.NOT_JSON, result.format());
        assertEquals(client.reply, result.rawText(), "worker 要把这份原文交给修复请求");
        assertFalse(result.published());
    }

    @Test
    void emptyStructurePublishesNothingInsteadOfAnEmptyCheckpoint() throws Exception {
        client.reply = "{\"schemaVersion\":2,\"topic\":\"\",\"taskGoal\":null,"
                + "\"confirmedUserConstraints\":[],\"confirmedDecisions\":[],\"discussionNotes\":[],"
                + "\"workItems\":[],\"openQuestions\":[],\"nextSteps\":[],\"references\":[]}";

        DraftResult result = drafter.draft(batch(1, 40), null);

        assertTrue(result.formatOk(), "结构本身合法，只是什么都没剩下");
        assertFalse(result.published(), "没有内容就不创建伪有效空检查点（§6.3 第 5 条）");
    }

    @Test
    void forgedReferenceNeverReachesTheDraftLedger() throws Exception {
        client.reply = validBody("先背再练").replace("\"references\":[]",
                "\"references\":[{\"pageId\":404,\"contentVersion\":\"从没提供过\",\"sourceTurnId\":" + TURN_ID + "}]");

        DraftResult result = drafter.draft(batch(1, 40, List.of(new Reference(9L, "fixture-v3", TURN_ID))), null);

        assertTrue(result.published());
        assertEquals(List.of(), result.draft().references(), "模型自造的页码进不了台账（§6.1 第 3 段）");
        assertTrue(result.rejections().contains("REFERENCE_NOT_ALLOWED"), result.rejections().toString());
    }

    @Test
    void reportedUsageComesBackFromTheVendorNotFromTheEstimate() throws Exception {
        client.reply = validBody("先背再练");
        client.inputTokens = 1_234;
        client.outputTokens = 210;

        DraftResult result = drafter.draft(batch(1, 40), null);

        assertEquals(1_234L, result.usage().inputTokens());
        assertEquals(210L, result.usage().outputTokens());
    }

    // ---- 夹具 ----

    /** 一份能过 §6.1 全部字段门的草稿；{@code topic} 是唯一进发布视图的内容。 */
    private static String validBody(String topic) {
        return "{\"schemaVersion\":2,\"topic\":\"" + topic + "\",\"taskGoal\":"
                + "{\"text\":\"复习二分查找\",\"sourceRole\":\"user\",\"sourceTurnIds\":[" + TURN_ID + "]},"
                + "\"confirmedUserConstraints\":[],\"confirmedDecisions\":[],\"discussionNotes\":[],"
                + "\"workItems\":[],\"openQuestions\":[],\"nextSteps\":[],\"references\":[]}";
    }

    private static Batch batch(int turns, int characters) {
        return batch(turns, characters, List.of());
    }

    /** {@code allowedReferences} 由装载器算好交进来（§6.1），这里照同一条口径直接给。 */
    private static Batch batch(int turns, int characters, List<Reference> allowedReferences) {
        List<SourceTurn> items = new ArrayList<>();
        for (int index = 0; index < turns; index++) {
            String user = MaintenanceHarness.pad("问题" + index + " ", characters);
            String reply = MaintenanceHarness.pad("回答" + index + " ", characters);
            items.add(new SourceTurn(TURN_ID + index, 1L, MESSAGE_ID + index, MESSAGE_ID + 500 + index, user, reply,
                    ESTIMATOR.estimateText(user) + ESTIMATOR.estimateText(reply),
                    1_760_000_000_000L + index * 60_000L, List.of(), CREATED_AT));
        }
        return new Batch(Kind.SUMMARY, 900L, TURN_ID, TURN_ID + turns - 1L, 1L, "GLOBAL", 0L, "Asia/Shanghai",
                1L, 1L, previous(), List.copyOf(items), framing(), total(items), "revision",
                1_760_000_060_000L, allowedReferences);
    }

    private static Batch batchWith(String userText) {
        String reply = "回答：好的";
        SourceTurn turn = new SourceTurn(TURN_ID, 1L, MESSAGE_ID, MESSAGE_ID + 1L, userText, reply,
                ESTIMATOR.estimateText(userText) + ESTIMATOR.estimateText(reply), 1_760_000_000_000L, List.of(),
                CREATED_AT);
        return new Batch(Kind.SUMMARY, 900L, TURN_ID, TURN_ID, 1L, "GLOBAL", 0L, "Asia/Shanghai", 1L, 1L,
                previous(), List.of(turn), framing(), total(List.of(turn)), "revision", 1_760_000_000_000L,
                List.of());
    }

    private static PreviousSummary previous() {
        return new PreviousSummary(1L, "上一版：主题：二分查找复习", List.of(), List.of());
    }

    private static int framing() {
        return ESTIMATOR.estimateText(MaintenanceSummaryDrafter.SYSTEM_PROMPT)
                + ESTIMATOR.estimateText(MaintenanceSummaryDrafter.OUTPUT_SHAPE);
    }

    private static int total(List<SourceTurn> turns) {
        return turns.stream().mapToInt(SourceTurn::estimatedTokens).sum();
    }

    /** 记录请求体的假客户端：只换外发那一层，端口其余逻辑跑的都是真代码。 */
    private static final class CapturingClient extends AiUpstreamClient {
        private final List<String> systems = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<Boolean> jsonModes = new ArrayList<>();
        private String reply = "{}";
        private int calls;
        private Integer inputTokens;
        private Integer outputTokens;

        private CapturingClient() {
            super(HttpClient.newHttpClient(), MAPPER);
        }

        @Override
        public Result call(Upstream upstream, List<Map<String, Object>> messages, boolean stream,
                           StreamListener listener, int timeoutSeconds) {
            calls++;
            systems.add(String.valueOf(messages.get(0).get("content")));
            bodies.add(String.valueOf(messages.get(1).get("content")));
            jsonModes.add(upstream.jsonMode());
            return new Result(reply, null, "stop", true, false, false,
                    new Usage(inputTokens == null && outputTokens == null ? "unknown" : "provider",
                            inputTokens, outputTokens, null, null));
        }

        int calls() {
            return calls;
        }

        String system() {
            return systems.get(systems.size() - 1);
        }

        String user() {
            return bodies.get(bodies.size() - 1);
        }

        boolean lastJsonMode() {
            return jsonModes.get(jsonModes.size() - 1);
        }
    }
}
