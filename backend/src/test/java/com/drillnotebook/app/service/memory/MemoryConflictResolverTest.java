package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.drillnotebook.app.service.memory.MemoryConflictResolver.Action;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.ActiveFact;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.Context;
import com.drillnotebook.app.service.memory.MemoryConflictResolver.Decision;
import com.drillnotebook.app.service.memory.MemoryValidator.ValidatedCandidate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * §8.3 冲突判定表的可执行版本（执行方案 §8.2 第 9 条、§14 P7「高风险语义只能 REVIEW/IGNORE」）。
 *
 * <p>纯函数用例：这一层决定<b>模型能不能越过人直接改库</b>，所以每一格都要单独证伪一次。</p>
 */
class MemoryConflictResolverTest {
    private static final long NOW = 1_760_000_000_000L;

    @Test
    void sameNormalizedValueOnlyConfirmsAndDoesNotCreateAnotherActiveFact() {
        Decision decision = MemoryConflictResolver.decide(candidate("answer.detail", "detailed",
                "以后都讲详细些。", NOW), Optional.of(active("detailed", "AUTO", NOW - 1000)), Context.none());
        assertEquals(Action.CONFIRM, decision.action(), "同键同值只追加来源与刷新确认时间（§8.3）");
        assertEquals("m-1", decision.targetMemoryId());
    }

    @Test
    void newerConflictingFactReplacesTheCurrentVersionDirectly() {
        Decision decision = MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.of(active("detailed", "AUTO", NOW - 60_000)), Context.none());
        assertEquals(Action.REPLACE, decision.action());
        assertEquals("DIRECT_REPLACE", decision.reason());
        assertEquals("m-1", decision.targetMemoryId());
        assertEquals(3, decision.targetVersion());
    }

    @Test
    void manualOrPinnedFactsAreNotOverwrittenByAutomaticExtraction() {
        Decision manual = MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.of(active("detailed", "MANUAL", NOW - 60_000)), Context.none());
        assertEquals(Action.IGNORE, manual.action());
        assertEquals("MANUAL_PROTECTED", manual.reason());
        ActiveFact pinned = new ActiveFact("m-1", 4, "AUTO", false, true, "detailed", NOW - 60_000, NOW);
        assertEquals(Action.IGNORE, MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.of(pinned), Context.none()).action());
    }

    @Test
    void aLateJobCarryingOlderSourceNeverWins() {
        Decision decision = MemoryConflictResolver.decide(candidate("answer.detail", "detailed",
                "以后讲详细些。", NOW - 120_000), Optional.of(active("concise", "AUTO", NOW)), Context.none());
        assertEquals(Action.IGNORE, decision.action(), "后到任务处理更早来源：丢弃候选，不覆盖更新事实（§8.3 末行）");
        assertEquals("OLDER_SOURCE_LOSES", decision.reason());
        Decision sameTime = MemoryConflictResolver.decide(candidate("answer.detail", "detailed",
                "以后讲详细些。", NOW), Optional.of(active("concise", "AUTO", NOW)), Context.none());
        assertEquals(Action.REPLACE, sameTime.action());
        assertEquals("DIRECT_REPLACE", sameTime.reason());
    }

    @Test
    void oneOffHypotheticalAndThirdPartyStatementsAreIgnored() {
        assertEquals("EPHEMERAL_REQUEST", MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "这次只说答案。", NOW), Optional.empty(), Context.none()).reason(), "本次要求不入长期库（§5.1）");
        assertEquals("HYPOTHETICAL_STATEMENT", MemoryConflictResolver.decide(candidate("study.goal", "备考雅思",
                "假设我正在备考雅思。", NOW), Optional.empty(), Context.none()).reason());
        assertEquals("THIRD_PARTY_STATEMENT", MemoryConflictResolver.decide(candidate("answer.order",
                "example_first", "我朋友习惯先做例题。", NOW), Optional.empty(), Context.none()).reason());
    }

    @Test
    void negationIsAddedDirectlyInsteadOfWaitingForConfirmation() {
        Decision decision = MemoryConflictResolver.decide(candidate("answer.order", "concept_first",
                "不是要先给例子，而是先讲原理。", NOW), Optional.empty(), Context.none());
        assertEquals(Action.ADD_ACTIVE, decision.action());
        assertEquals("DIRECT_ADD", decision.reason());
    }

    @Test
    void aFirstFactIsAddedDirectlyWithoutAnExplicitLongTermSignal() {
        Decision decision = MemoryConflictResolver.decide(candidate("answer.language", "zh", "用中文。", NOW),
                Optional.empty(), Context.none());
        assertEquals(Action.ADD_ACTIVE, decision.action(),
                "没有同族当前值时直接入库，不再为「没说以后」单独攒待确认（P9d 用户裁决）");
        assertEquals(Action.ADD_ACTIVE, MemoryConflictResolver.decide(candidate("answer.language", "zh",
                "以后都用中文解释。", NOW), Optional.empty(), Context.none()).action());
    }

    @Test
    void suppressedKeysAreIgnoredEvenWhenTheModelIsConfident() {
        Decision decision = MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.empty(), new Context(true, false, false, true));
        assertEquals(Action.IGNORE, decision.action(), "已有 suppression 命中就不重提（§2.3.1）");
        assertEquals("SUPPRESSED", decision.reason(), "族/谓词抑制与来源抑制要给出不同的原因码");
        Decision byPredicate = MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.empty(), new Context(false, true, false, true));
        assertEquals("SUPPRESSED", byPredicate.reason());
        Decision byEvidence = MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.empty(), new Context(false, false, true, true));
        assertEquals(Action.IGNORE, byEvidence.action(), "同一来源被拒过就不再自动收");
        assertEquals("EVIDENCE_SUPPRESSED", byEvidence.reason(),
                "三种抑制键压成一个布尔时，这条原因码在生产里永不出现（D75）");
    }

    @Test
    void unknownScopeStillAddsDirectly() {
        assertEquals(Action.ADD_ACTIVE, MemoryConflictResolver.decide(candidate("answer.detail", "concise",
                "以后回答简洁一点。", NOW), Optional.empty(), new Context(false, false, false, false)).action());
    }

    @Test
    void replacementSuggestionsReplaceTheTargetAndRetractionsAreIgnored() {
        ValidatedCandidate replace = new ValidatedCandidate("REPLACE_PROPOSAL", "m-ielts", 2, "GOAL", "study.goal",
                "study.goal:准备托福", "准备托福", "准备托福", "我不准备雅思了，改准备托福。", 51L, 101L, "u0", null, NOW, true,
                "PROPOSAL_REQUIRES_CONFIRMATION");
        Decision decision = MemoryConflictResolver.decide(replace, Optional.empty(), Context.none());
        assertEquals(Action.REPLACE, decision.action());
        assertEquals("DIRECT_REPLACE", decision.reason());
        assertEquals("m-ielts", decision.targetMemoryId());
        assertEquals(2, decision.targetVersion());
        ValidatedCandidate retract = new ValidatedCandidate("RETRACT_PROPOSAL", "m-ielts", 2, "GOAL", "study.goal",
                "study.goal", null, null, "忘记我准备雅思这件事。", 51L, 101L, "u0", null, NOW, true,
                "PROPOSAL_REQUIRES_CONFIRMATION");
        Decision ignored = MemoryConflictResolver.decide(retract, Optional.empty(), Context.none());
        assertEquals(Action.IGNORE, ignored.action());
        assertEquals("RETRACTION_DISABLED", ignored.reason());
    }

    // ---- 夹具 ----

    private static ValidatedCandidate candidate(String predicate, String normalizedValue, String quote,
                                                long sourceMillis) {
        String kind = predicate.startsWith("answer.") ? "PREFERENCE"
                : predicate.startsWith("study.goal") ? "GOAL" : "BACKGROUND";
        return new ValidatedCandidate("ADD", null, null, kind, predicate, predicate + ":" + normalizedValue,
                normalizedValue, normalizedValue, quote, 51L, 101L, "u0", null, sourceMillis, false, null);
    }

    private static ActiveFact active(String normalizedValue, String origin, long sourceMillis) {
        return new ActiveFact("m-1", 3, origin, "MANUAL".equals(origin), false, normalizedValue, sourceMillis,
                sourceMillis);
    }
}
