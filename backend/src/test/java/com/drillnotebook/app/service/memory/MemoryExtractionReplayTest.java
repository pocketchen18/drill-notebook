package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.JobRow;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 提取链的端到端回放（执行方案 §14 P7、§8.2 第 6-9 条、§8.3 判定表；contracts §5、§7）。
 *
 * <p>与 {@link MemoryValidatorTest}／{@link MemoryConflictResolverTest} 的纯函数用例不同，这里喂给 worker 的是
 * 上游<b>原文</b>：假提取器把原文交给真实的 {@link MemoryValidator}，再通过 {@link MemoryExtractionWriter}
 * 落库。所以来源 ID、quote 块内连续、凭据、时间新旧、operation 一致性这五项，以及"模型没有直写权限"
 * 这条退出条件，都是在真库上被证伪的，而不是在函数边界上自证。</p>
 *
 * <p>轮次正文由 {@link MaintenanceHarness#seedTurn} 按 §4.3 写进 {@code request_meta.sourceBlocks}
 * 的 {@code u0} 块，quote 必须是那段正文里的连续子串——伪造来源、指错块、贴造出来的话都会在这里断掉。</p>
 */
class MemoryExtractionReplayTest {
    private static final long ONE_HOUR = 3_600_000L;

    private MaintenanceHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = MaintenanceHarness.open("p7-replay-");
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    // ---- §8.3：自动生效只发生在"明确长期 + 低风险单值 + 来源更新 + 目标无保护"这一格 ----

    @Test
    void longTermSingleValuedPreferenceBecomesActiveFactWithEvidence() throws Exception {
        harness.readyForExtraction();
        long turn = harness.seedTurn("以后每次回答都详细一些", "好的，我讲细一点");
        long revision = harness.vaultRevision();

        JobRow job = extract(turn, body(add("answer.detail", "detailed", "以后每次回答都详细一些",
                harness.userMessageId(turn))));

        assertEquals(MemoryJobRepository.STATUS_COMPLETED, job.status());
        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        Map<String, Object> row = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertEquals("AUTO", row.get("origin"));
        assertEquals(0, number(row.get("manual_protected")));
        assertEquals(0, number(row.get("pinned")), "自动事实默认不带人工保护");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_evidence"), "原话作为证据一起落库（§2.3）");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item_evidence"));
        assertEquals(revision + 1, harness.vaultRevision(), "确实写了事实才涨内容代次");
        assertEquals(turn, harness.memoryWatermark(), "已扫描水位推进（§8.2 第 9 条）");
        assertTrue(payloadOf(row).contains("\"displayText\":\"回答详略：详细\""),
                "展示句是服务端模板，不是模型造句");
        assertEquals(List.of("SETTLED"), harness.attemptStates());
        assertEquals(1_400L, harness.spend().chargedTokens());
    }

    @Test
    void sameValueRefreshesConfirmationWithoutNewVersion() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        long revision = harness.vaultRevision();
        harness.advance(ONE_HOUR);
        long second = harness.seedTurn("详细一些更好，以后都这样讲", "明白");

        JobRow job = extract(second, body(add("answer.detail", "detailed", "以后都这样讲",
                harness.userMessageId(second))));

        assertEquals(MemoryJobRepository.STATUS_COMPLETED, job.status());
        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "同值确认不新增版本（§8.3 CONFIRM）");
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_evidence"), "新来源照样挂成证据");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item_evidence"), "两个来源都关联到同一条事实");
        Map<String, Object> row = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertEquals(harness.stamp(harness.now()), row.get("last_confirmed_at"), "只刷新确认时刻");
        assertEquals(1, number(row.get("version")));
        assertEquals(revision + 1, harness.vaultRevision());
        assertEquals(second, harness.memoryWatermark());
    }

    @Test
    void exactDuplicateValueInAnotherFamilyIsRejectedWithoutANewItem() throws Exception {
        harness.readyForExtraction();
        apply("我常住杭州", "study.background", "杭州", "我常住杭州");
        long revision = harness.vaultRevision();
        harness.advance(ONE_HOUR);
        long second = harness.seedTurn("我住在杭州", "好的");

        JobRow job = extract(second, body(add("task.constraint", "杭州", "我住在杭州",
                harness.userMessageId(second))));

        assertEquals(MemoryJobRepository.STATUS_COMPLETED, job.status());
        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode(), "值完全相同就不写第二条");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"));
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        assertEquals(revision, harness.vaultRevision(), "拒绝重复不涨内容代次");
        assertEquals(second, harness.memoryWatermark(), "来源仍被消费");
    }

    @Test
    void newerAutoPreferenceSupersedesTheOlderVersion() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        harness.advance(ONE_HOUR);
        long turn = harness.seedTurn("以后讲简洁些，别每次都铺开", "收到");

        JobRow job = extract(turn, body(add("answer.detail", "concise", "以后讲简洁些",
                harness.userMessageId(turn))));

        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item"));
        Map<String, Object> active = onlyItem(MemoryRepository.STATUS_ACTIVE);
        Map<String, Object> old = onlyItem(MemoryRepository.STATUS_SUPERSEDED);
        assertEquals(2, number(active.get("version")));
        assertEquals(old.get("memory_id"), active.get("memory_id"), "替换是同一 memory_id 的新版本");
        assertTrue(payloadOf(active).contains("\"normalizedValue\":\"concise\""));
        assertEquals(0, itemCount(MemoryRepository.STATUS_PENDING), "自动替换不顺便留一条待确认");
    }

    // ---- §14 P7 回放用例：五种高风险语义的区分度 ----

    @Test
    void negatedCorrectionReplacesTheCurrentVersionDirectly() throws Exception {
        harness.readyForExtraction();
        apply("以后每次讲解都先讲原理", "answer.order", "concept_first", "以后每次讲解都先讲原理");
        harness.advance(ONE_HOUR);
        long turn = harness.seedTurn("回答不是先讲原理，而是先给例子", "明白了");

        JobRow job = extract(turn, body(add("answer.order", "example_first", "回答不是先讲原理，而是先给例子",
                harness.userMessageId(turn))));

        assertEquals("APPLIED", job.outcomeCode());
        Map<String, Object> active = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertEquals(2, number(active.get("version")), "改口直接覆盖旧版本");
        assertTrue(payloadOf(active).contains("\"normalizedValue\":\"example_first\""));
        assertEquals(0, itemCount(MemoryRepository.STATUS_PENDING), "不再产生待确认");
        assertEquals(1, itemCount(MemoryRepository.STATUS_SUPERSEDED));
    }

    @Test
    void oneOffRequestWritesNothingButStillSettlesTheBatch() throws Exception {
        harness.readyForExtraction();
        long turn = harness.seedTurn("仅这一次把表格列名换成英文，别长期记着", "这次改好了");
        long revision = harness.vaultRevision();

        JobRow job = extract(turn, body(add("task.constraint", "把表格列名换成英文", "仅这一次把表格列名换成英文",
                harness.userMessageId(turn))));

        assertEquals(MemoryJobRepository.STATUS_COMPLETED, job.status());
        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode(), "候选过了校验但判定为 IGNORE，不能报 APPLIED");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item"), "一次性的要求不进长期库（§8.3）");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence"));
        assertEquals(turn, harness.memoryWatermark(), "什么都不写也照样消费这批来源，不再重扫");
        assertEquals(List.of("SETTLED"), harness.attemptStates(), "外发确实花了钱，账要记");
        assertEquals(revision, harness.vaultRevision(), "库里没动就不涨内容代次");
    }

    @Test
    void thirdPartyStatementWritesNothingEvenAsPending() throws Exception {
        harness.readyForExtraction();
        long turn = harness.seedTurn("以下是我同学的习惯：先看例子再看原理", "记下了");
        long revision = harness.vaultRevision();

        JobRow job = extract(turn, body(add("answer.order", "example_first", "以下是我同学的习惯",
                harness.userMessageId(turn))));

        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item"), "别人的习惯连待确认都不该进");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence"));
        assertEquals(revision, harness.vaultRevision());
    }

    @Test
    void laterJobCarryingOlderSourceKeepsTheNewerFact() throws Exception {
        harness.readyForExtraction();
        long older = apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        harness.advance(ONE_HOUR);
        long newer = apply("以后讲简洁些，别每次都铺开", "answer.detail", "concise", "以后讲简洁些");
        long revision = harness.vaultRevision();
        long watermark = harness.memoryWatermark();

        // 重扫把更旧的那轮又带回这批：来源时间更早的候选赢不了已生效的新事实
        JobRow job = extract(older, newer, body(add("answer.detail", "detailed", "以后每次回答都详细一些",
                harness.userMessageId(older))), Map.of());

        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode());
        Map<String, Object> active = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertTrue(payloadOf(active).contains("\"normalizedValue\":\"concise\""), "更新的事实赢（§8.3 来源时间优先）");
        assertEquals(2, number(active.get("version")));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item"), "更早的期望不会被补写成新版本");
        assertEquals(revision, harness.vaultRevision(), "什么都没写就不涨代次");
        assertEquals(watermark, harness.memoryWatermark(), "水位绝不回退到更旧的轮次");
    }

    @Test
    void suppressedEvidenceSourceIsIgnored() throws Exception {
        harness.readyForExtraction();
        long older = apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        harness.advance(ONE_HOUR);
        long filler = harness.seedTurn("再讲一遍这道题的第二问", "好的");

        JobRow before = extract(older, filler, body(add("answer.detail", "concise", "以后每次回答都详细一些",
                harness.userMessageId(older))), Map.of());
        assertEquals("APPLIED", before.outcomeCode(), "没抑制时这条候选应生效");
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));

        MemoryKey key = harness.vault.currentKey().orElseThrow();
        String sourceHmac = harness.jdbc.queryForObject(
                "SELECT source_hmac FROM ai_memory_evidence WHERE turn_id = " + older, String.class);
        harness.suppressions.record(key.vaultId(), new MemorySuppressionRepository.Suppression("GLOBAL", 0,
                MemorySuppressionRepository.KIND_EVIDENCE, sourceHmac, older, harness.stamp(harness.now())));
        long wider = harness.seedTurn("再讲一遍第三问", "好的");

        JobRow after = extract(older, wider, body(add("answer.detail", "concise", "以后每次回答都详细一些",
                harness.userMessageId(older))), Map.of());

        assertEquals("NO_VALID_CANDIDATES", after.outcomeCode());
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE), "EVIDENCE 抑制不再写任何事实");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_evidence"), "被抑制的来源不追加新证据");
    }

    @Test
    void manuallyProtectedFactIsNotOverwrittenAutomatically() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        harness.jdbc.update("UPDATE ai_memory_item SET origin = 'MANUAL', manual_protected = 1"
                + " WHERE status = 'ACTIVE'");
        harness.advance(ONE_HOUR);
        long turn = harness.seedTurn("以后讲简洁些，别每次都铺开", "收到");

        JobRow job = extract(turn, body(add("answer.detail", "concise", "以后讲简洁些",
                harness.userMessageId(turn))));

        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode());
        Map<String, Object> active = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertEquals("MANUAL", active.get("origin"));
        assertEquals(1, number(active.get("manual_protected")), "人工保护的事实不被自动覆盖（§8.3）");
        assertTrue(payloadOf(active).contains("\"normalizedValue\":\"detailed\""));
        assertEquals(0, itemCount(MemoryRepository.STATUS_PENDING), "不再产生待确认建议");
        assertEquals(0, itemCount(MemoryRepository.STATUS_SUPERSEDED));
    }

    @Test
    void suppressedFamilyWritesNothingForTheSamePredicate() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        MemoryKey key = harness.vault.currentKey().orElseThrow();
        harness.suppressions.record(key.vaultId(), new MemorySuppressionRepository.Suppression("GLOBAL", 0,
                MemorySuppressionRepository.KIND_FAMILY, harness.crypto.hmac(key, "family:answer.detail"),
                harness.now(), harness.stamp(harness.now())));
        harness.advance(ONE_HOUR);
        long turn = harness.seedTurn("以后讲简洁些，别每次都铺开", "收到");

        JobRow job = extract(turn, body(add("answer.detail", "concise", "以后讲简洁些",
                harness.userMessageId(turn))));

        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode(), "被抑制的同族再次提出就当没说过（§5.4）");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"));
        assertEquals(0, itemCount(MemoryRepository.STATUS_PENDING));
        assertTrue(payloadOf(onlyItem(MemoryRepository.STATUS_ACTIVE))
                .contains("\"normalizedValue\":\"detailed\""));
    }

    @Test
    void credentialBearingTurnIsNeverSentToTheModel() throws Exception {
        harness.readyForExtraction();
        long secret = harness.seedTurn("以后回答用中文，另外我的密码：Alpha9-hunter2-token", "好的");
        harness.advance(60_000L);
        long clean = harness.seedTurn("以后每次回答都详细一些", "好的");

        JobRow job = extract(secret, clean, body(add("answer.detail", "detailed", "以后每次回答都详细一些",
                harness.userMessageId(clean))), Map.of());

        assertEquals("APPLIED", job.outcomeCode());
        assertFalse(harness.extractor.lastBatch().turns().stream()
                        .anyMatch(turn -> turn.turnId() == secret),
                "命中凭据格式的轮次在送模型之前就被跳过（§5.3）");
        assertEquals(1, harness.extractor.lastBatch().turns().size());
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence WHERE turn_id = " + secret));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_item WHERE payload_cipher LIKE '%hunter2%'"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_evidence WHERE quote_cipher LIKE '%hunter2%'"),
                "凭据原文既不入库也不进证据");
        assertEquals(clean, harness.memoryWatermark(), "被跳过的那轮不挡住这批的水位");
    }

    // ---- §5：伪造目标与恶意结构 ----

    @Test
    void forgedTargetIdIsRejectedAndTheValidCandidateStillLands() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        long turn = harness.seedTurn("以后我的学习目标一直是整理错题本", "好");

        JobRow job = extract(turn, body(
                candidate("REPLACE_PROPOSAL", "answer.detail", "concise", "以后我的学习目标一直是整理错题本",
                        harness.userMessageId(turn), "01a109ca-1100-7000-8000-00000000dead", 1, false),
                add("study.goal", "整理错题本", "以后我的学习目标一直是整理错题本", harness.userMessageId(turn))),
                harness.activeTargets());

        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal"), "没随请求发过的 ID 就是伪造（§5）");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item"), "另一条候选不受牵连");
        assertEquals(2, itemCount(MemoryRepository.STATUS_ACTIVE));
        assertEquals(0, itemCount(MemoryRepository.STATUS_PENDING), "第一条事实直接生效，不再留待确认");
        assertTrue(payloadOf(itemWithPayload(MemoryRepository.STATUS_ACTIVE, "整理错题本"))
                .contains("整理错题本"));
    }

    @Test
    void staleTargetVersionIsRejectedInsteadOfRetargeted() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        long turn = harness.seedTurn("以后回答别那么啰嗦，简洁就行", "好");
        Map<String, Integer> known = harness.activeTargets();
        String memoryId = known.keySet().iterator().next();

        JobRow job = extract(turn, body(candidate("REPLACE_PROPOSAL", "answer.detail", "concise",
                "以后回答别那么啰嗦", harness.userMessageId(turn), memoryId, known.get(memoryId) + 1, false)), known);

        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal"), "版本对不上不猜目标（§5）");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"));
    }

    @Test
    void userEditDuringOutboundRejectsStaleReplacementAndKeepsNewerVersion() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答详细一些", "answer.detail", "detailed", "以后每次回答详细一些");
        long turn = harness.seedTurn("以后回答怎么舒服，简洁点", "好");
        Map<String, Integer> known = harness.activeTargets();
        String memoryId = known.keySet().iterator().next();
        int version = known.get(memoryId);
        MemoryItemService items = new MemoryItemService(harness.crypto, harness.memory, harness.vault,
                harness.policy, new MemoryOperationRepository(harness.dataSource, harness.immediate),
                harness.proposals, harness.suppressions, harness.jobs, harness.metadata);
        AtomicReference<MemoryApiResult> edit = new AtomicReference<>();

        harness.extractor.invokeDuringExtract(() -> edit.set(items.edit(memoryId,
                new MemoryItemService.EditInput(version, null, null, "concise", null, null))));
        JobRow job = extract(turn, body(candidate("REPLACE_PROPOSAL", "answer.detail", "very concise",
                "以后回答怎么舒服", harness.userMessageId(turn), memoryId, version, false)), known);

        assertEquals(200, edit.get().status(), "并发编辑先成功");
        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode(), "v1 的结果不能覆盖 v2");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_memory_item"), "v1 SUPERSEDED + v2 ACTIVE");
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        assertEquals(2, number(onlyItem(MemoryRepository.STATUS_ACTIVE).get("version")));
    }

    @Test
    void validReplacementSuggestionReplacesTheTargetDirectly() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        long turn = harness.seedTurn("以后回答别那么啰嗦，简洁就行", "好");
        Map<String, Integer> known = harness.activeTargets();

        JobRow job = extract(turn, body(candidate("REPLACE_PROPOSAL", "answer.detail", "concise",
                "以后回答别那么啰嗦", harness.userMessageId(turn), known.keySet().iterator().next(),
                known.values().iterator().next(), false)), known);

        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal"), "不再创建提案");
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        Map<String, Object> active = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertEquals(2, number(active.get("version")));
        assertTrue(payloadOf(active).contains("\"normalizedValue\":\"concise\""));
        assertEquals(1, itemCount(MemoryRepository.STATUS_SUPERSEDED));
    }

    @Test
    void overlongValueIsRejectedWithoutTruncatingAnotherCandidate() throws Exception {
        harness.readyForExtraction();
        long turn = harness.seedTurn("以后回答用中文，我的目标是" + "详".repeat(MemoryValidator.MAX_VALUE_CODE_POINTS + 1),
                "好的");
        String tooLong = "详".repeat(MemoryValidator.MAX_VALUE_CODE_POINTS + 1);

        JobRow job = extract(turn, body(
                add("study.goal", tooLong, "以后回答用中文", harness.userMessageId(turn)),
                add("answer.language", "中文", "以后回答用中文", harness.userMessageId(turn))));

        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "超长值整条拒收，不截断保存（§5）");
        assertFalse(payloadOf(onlyItem(MemoryRepository.STATUS_ACTIVE)).contains("详详详"));
    }

    @Test
    void unmappedLanguageIsAddedDirectly() throws Exception {
        harness.readyForExtraction();
        long turn = harness.seedTurn("以后回答用法语，术语保留英文", "好的");

        JobRow job = extract(turn, body(add("answer.language", "法语", "以后回答用法语",
                harness.userMessageId(turn))));

        assertEquals("APPLIED", job.outcomeCode());
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        Map<String, Object> active = onlyItem(MemoryRepository.STATUS_ACTIVE);
        assertTrue(payloadOf(active).contains("\"value\":\"法语\""), "原话直接入库，不再等待映射确认");
        assertEquals(0, itemCount(MemoryRepository.STATUS_PENDING));
    }

    @Test
    void naturalLanguageRetractionIsIgnoredWithoutTouchingFacts() throws Exception {
        harness.readyForExtraction();
        apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        long revision = harness.vaultRevision();
        long turn = harness.seedTurn("以后回答别那么啰嗦，那条详细偏好作废吧", "好");
        Map<String, Integer> known = harness.activeTargets();

        JobRow job = extract(turn, body(candidate("RETRACT_PROPOSAL", "answer.detail", null,
                "那条详细偏好作废吧", harness.userMessageId(turn), known.keySet().iterator().next(),
                known.values().iterator().next(), false)), known);

        assertEquals("NO_VALID_CANDIDATES", job.outcomeCode(), "撤销建议被忽略，不产生新写入");
        assertEquals(0, count("SELECT COUNT(*) FROM ai_memory_proposal"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "事实库一条都不动");
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
        assertTrue(payloadOf(onlyItem(MemoryRepository.STATUS_ACTIVE)).contains("\"normalizedValue\":\"detailed\""));
        assertEquals(revision, harness.vaultRevision(), "没有写入就不涨内容代次");
    }

    // ---- §8.2 第 7 条：格式失败最多修一次，且不清空既有记忆 ----

    @Test
    void unexpectedTopLevelFieldFailsAfterExactlyOneRepairAndKeepsMemory() throws Exception {
        harness.readyForExtraction();
        long applied = apply("以后每次回答都详细一些", "answer.detail", "detailed", "以后每次回答都详细一些");
        harness.advance(ONE_HOUR);
        long turn = harness.seedTurn("以后讲简洁些，别每次都铺开", "收到");
        String withExtraField = "{\"schemaVersion\":2,\"candidates\":[],\"note\":\"顺便把上文总结一下吧\"}";
        harness.extractor.resetCalls();

        JobRow job = extractWithTwoBodies(turn, withExtraField, withExtraField);

        assertEquals(2, harness.extractor.calls(), "只修一次，不循环（§8.2 第 7 条）");
        assertEquals(MemoryJobRepository.STATUS_FAILED, job.status());
        assertEquals(List.of("MEMORY_EXTRACT", "MEMORY_EXTRACT", "FORMAT_REPAIR"), attemptPurposes(),
                "修复是第二次真实外发，自己记一笔账（§2.6）");
        assertEquals(withExtraField, harness.extractor.lastRepairOf(), "修复请求带上被修的那份原文");
        assertEquals(1, count("SELECT COUNT(*) FROM ai_memory_item"), "格式失败不删既有记忆");
        assertEquals(applied, harness.memoryWatermark(), "失败的批次不推进水位");
        assertEquals(0, harness.activeJobCount(Kind.EXTRACT), "终态任务不再被认领");
    }

    @Test
    void malformedResponseIsRepairedOnceThenApplied() throws Exception {
        harness.readyForExtraction();
        long turn = harness.seedTurn("以后每次回答都详细一些", "好的");
        String prose = "根据您的要求，我认为应当把回答写得详细一些。";
        harness.extractor.willReturnBody(prose);
        harness.extractor.willReturnBody(body(add("answer.detail", "detailed", "以后每次回答都详细一些",
                harness.userMessageId(turn))));
        String jobId = harness.enqueueManualJob(Kind.EXTRACT, harness.session(), turn, turn);

        harness.drainJobWhenDue(jobId);

        assertEquals(MemoryJobRepository.STATUS_COMPLETED, harness.job(jobId).status());
        assertEquals("APPLIED", harness.job(jobId).outcomeCode());
        assertEquals(2, harness.extractor.calls());
        assertEquals(List.of("MEMORY_EXTRACT", "FORMAT_REPAIR"), attemptPurposes());
        assertEquals(prose, harness.extractor.lastRepairOf());
        assertEquals(1, itemCount(MemoryRepository.STATUS_ACTIVE));
    }

    // ---- §5.1：后台没有主密码就解不出维护 Key，不能代解也不能烧外发 ----

    @Test
    void passwordModeKeyStopsTheJobBeforeAnyOutboundCall() throws Exception {
        harness.createVaultAndUnlock();
        harness.unlockSessions();
        harness.grantMaintenanceConsent();
        new AiConfigRepository(harness.jdbc).upsertChat("custom", MaintenanceHarness.CHAT_ENDPOINT, "stub-model",
                "enc", "{\"mode\":\"password\"}", "{}");
        harness.enableExtract();
        long turn = harness.seedTurn("以后每次回答都详细一些", "好的");
        harness.extractor.willReturnBody(body(add("answer.detail", "detailed", "以后每次回答都详细一些",
                harness.userMessageId(turn))));
        String jobId = harness.enqueueManualJob(Kind.EXTRACT, harness.session(), turn, turn);

        assertEquals(1, harness.drainJobWhenDue(jobId).waiting());

        JobRow job = harness.job(jobId);
        assertEquals(MemoryJobRepository.STATUS_WAITING_CONSENT, job.status());
        assertEquals("TARGET_KEY_NEEDS_PASSWORD", job.errorCode());
        assertEquals(0, harness.extractor.calls(), "解不出 Key 就连一次都不发（§5.1）");
        assertTrue(harness.attemptStates().isEmpty(), "没发出去就不占额度（§2.6）");
        assertEquals(0L, harness.memoryWatermark());
    }

    // ---- 装配辅助 ----

    /** 让一条偏好在真库上生效，返回它的轮次 ID（多批用例的前置状态）。 */
    private long apply(String userText, String predicate, String value, String quote) throws Exception {
        long turn = harness.seedTurn(userText, "好的");
        extract(turn, body(add(predicate, value, quote, harness.userMessageId(turn))));
        return turn;
    }

    private JobRow extract(long turnId, String body) throws Exception {
        return extract(turnId, body, Map.of());
    }

    private JobRow extract(long turnId, String body, Map<String, Integer> knownTargets) throws Exception {
        harness.extractor.willReturnBody(body, knownTargets);
        return run(turnId);
    }

    /** 指定批次范围：凭据跳过与"旧任务晚回来"都要精确点到某一段轮次。 */
    private JobRow extract(long fromTurnId, long throughTurnId, String body, Map<String, Integer> knownTargets)
            throws Exception {
        harness.extractor.willReturnBody(body, knownTargets);
        return run(fromTurnId, throughTurnId);
    }

    /** 脚本化两次原文：第一次驱动提取，第二次驱动那一次格式修复。 */
    private JobRow extractWithTwoBodies(long turnId, String first, String second) throws Exception {
        harness.extractor.willReturnBody(first);
        harness.extractor.willReturnBody(second);
        return run(turnId);
    }

    private JobRow run(long turnId) throws Exception {
        return run(turnId, turnId);
    }

    private JobRow run(long fromTurnId, long throughTurnId) throws Exception {
        String jobId = harness.enqueueManualJob(Kind.EXTRACT, harness.session(), fromTurnId, throughTurnId);
        harness.drainJobWhenDue(jobId);
        return harness.job(jobId);
    }

    // ---- 断言辅助 ----

    private int itemCount(String status) {
        return count("SELECT COUNT(*) FROM ai_memory_item WHERE status = '" + status + "'");
    }

    private Map<String, Object> onlyItem(String status) {
        return harness.jdbc.queryForMap("SELECT * FROM ai_memory_item WHERE status = '" + status + "'");
    }

    private Map<String, Object> itemWithPayload(String status, String needle) throws Exception {
        for (Map<String, Object> row : harness.jdbc.queryForList(
                "SELECT * FROM ai_memory_item WHERE status = '" + status + "' ORDER BY row_id")) {
            if (payloadOf(row).contains(needle)) {
                return row;
            }
        }
        throw new AssertionError("没有找到状态为 " + status + " 且包含「" + needle + "」的记忆");
    }

    private int count(String sql) {
        Integer value = harness.jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private List<String> attemptPurposes() {
        return harness.jdbc.queryForList("SELECT purpose FROM ai_usage_attempt ORDER BY rowid", String.class);
    }

    private String payloadOf(Map<String, Object> row) throws Exception {
        MemoryKey key = harness.vault.currentKey().orElseThrow();
        return harness.crypto.decrypt(key, "ai_memory_item", (String) row.get("row_id"),
                number(row.get("version")), (String) row.get("payload_cipher"), (String) row.get("crypto_meta"));
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    // ---- §5 请求体拼装：只允许"看着就像模型会输出的"形状，缺陷按用例逐个注入 ----

    private static String body(String... candidates) {
        return "{\"schemaVersion\":2,\"candidates\":[" + String.join(",", candidates) + "]}";
    }

    private static String add(String predicate, String value, String quote, long userMessageId) {
        return candidate("ADD", predicate, value, quote, userMessageId, null, null, false);
    }

    private static String candidate(String operation, String predicate, String value, String quote,
                                    long userMessageId, String targetMemoryId, Integer targetVersion,
                                    boolean needsReview) {
        return "{\"operation\":\"" + operation + "\""
                + ",\"targetMemoryId\":" + (targetMemoryId == null ? "null" : "\"" + targetMemoryId + "\"")
                + ",\"targetVersion\":" + (targetVersion == null ? "null" : targetVersion)
                + ",\"kind\":\"" + kindOf(predicate) + "\""
                + ",\"predicate\":\"" + predicate + "\""
                + ",\"value\":" + (value == null ? "null" : "\"" + value + "\"")
                + ",\"quote\":\"" + quote + "\""
                + ",\"userMessageId\":" + userMessageId
                + ",\"sourceBlockId\":\"u0\""
                + ",\"temporalExpression\":null"
                + ",\"needsReview\":" + needsReview + "}";
    }

    private static String kindOf(String predicate) {
        return switch (predicate) {
            case "study.goal" -> "GOAL";
            case "study.background" -> "BACKGROUND";
            case "task.constraint" -> "TASK";
            default -> "PREFERENCE";
        };
    }
}
