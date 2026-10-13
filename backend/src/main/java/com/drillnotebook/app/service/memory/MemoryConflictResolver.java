package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.service.memory.MemoryValidator.ValidatedCandidate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 确定性冲突判定（执行方案 §8.3 判定表、§8.2 第 9 条）。
 *
 * <p>输入只有候选、同族当前值和几个布尔；<b>没有模型话语权</b>：
 * 模型自报的 {@code needsReview=false} 在这里不作数，高风险语义一律只能进 REVIEW/IGNORE，
 * 自动生效被限制在"明确低风险单值偏好 + 来源更新 + 旧值是自动且无人工保护"这一格里。</p>
 *
 * <p>本类是纯函数，不读库、不写库、不碰时钟以外的全局状态，所以判定表可以逐行直接断言。</p>
 */
public final class MemoryConflictResolver {
    /** 服务端对一条候选的最终动作。 */
    public enum Action {
        /** 同键同值：只追加去重来源与刷新 lastConfirmedAt，不新增 ACTIVE。 */
        CONFIRM,
        /** 明确低风险单值偏好且来源更新：旧版 SUPERSEDED、新版 ACTIVE。 */
        REPLACE,
        /** 直接写 ACTIVE 自动事实（同族当前没有值）。 */
        ADD_ACTIVE,
        /** 写 PENDING 待确认事实，不参与注入。 */
        ADD_PENDING,
        /** 只进 proposal（替换/撤销建议），事实库不动。 */
        ADD_PROPOSAL,
        /** 本次要求/假设/引用他人/已有抑制：长期库里什么都不写。 */
        IGNORE
    }

    /** 判定结论：动作 + 可向 UI 解释的短原因 + 被影响的目标。 */
    public record Decision(Action action, String reason, String targetMemoryId, Integer targetVersion) {
        static Decision of(Action action, String reason) {
            return new Decision(action, reason, null, null);
        }
    }

    /**
     * 同族当前值。{@code sourceMillis} 是<b>原话</b>的时刻，不是入库时刻——
     * "后到任务处理更早来源"要靠它判新旧（§8.3 最后一行）。
     */
    public record ActiveFact(String memoryId, int version, String origin, boolean manualProtected, boolean pinned,
                             String normalizedValue, long sourceMillis, long lastConfirmedMillis) {
        boolean autoUnprotected() {
            return "AUTO".equals(origin) && !manualProtected && !pinned;
        }
    }

    /** 判定所需的外部事实：抑制命中与"这条 predicate 是不是单值偏好"。 */
    public record Context(boolean suppressedByFamily, boolean suppressedByPredicate, boolean suppressedByEvidence,
                          boolean scopeKnown) {
        public static Context none() {
            return new Context(false, false, false, true);
        }
    }

    /** 明确低风险、可自动生效的单值偏好谓词（§8.3"明确低风险单值偏好"那一格）。 */
    private static final Set<String> AUTO_ELIGIBLE_PREDICATES = Set.of("answer.language", "answer.detail",
            "answer.order");
    /** 一次性/假设/引用他人这三类语义标记：命中就<b>不写长期库</b>，绝不因为命中而自动新增。 */
    private static final String[] EPHEMERAL_MARKERS = {"仅这一次", "这一次", "这次只", "本次只", "只是这次",
            "暂时", "这一句", "这一题", "本题"};
    private static final String[] HYPOTHETICAL_MARKERS = {"假设", "假如", "如果我是", "如果我", "要是", "模拟我是"};
    private static final String[] QUOTED_MARKERS = {"引用", "原文", "摘自", "以下是", "下面是", "他说", "她说",
            "他习惯", "她习惯", "我朋友", "同学", "老师建议", "助手建议", "你上次建议"};
    /** 否定改口结构：语义有歧义，只能人工确认，不能自动替换旧值。 */
    private static final String[] AMBIGUOUS_NEGATION = {"不是", "并不是", "不只是", "而非", "而是", "别", "不要"};
    /**
     * 明确长期表达的正向标记；与"喜欢/以后"这类词单独出现时<b>不足以</b>自动生效（§8.3 末段）。
     * 单字"都"太宽（"这道题都做了"也会命中），要配"以后/每次"这类成对说法，而那些本身已在表里。
     */
    private static final String[] LONG_TERM_MARKERS = {"以后", "今后", "长期", "一直", "每次", "习惯", "偏好"};

    private MemoryConflictResolver() {
    }

    /**
     * §8.3 判定表的可执行版本。顺序上有三处刻意保守：
     * 建议类操作先分流（永远只进 proposal）、语义闸门再判（高风险一律 REVIEW/IGNORE）、
     * 最后才是同族比较——同族比较永远不给模型直写权限。
     */
    public static Decision decide(ValidatedCandidate candidate, Optional<ActiveFact> current, Context context) {
        if (!"ADD".equals(candidate.operation())) {
            return new Decision(Action.ADD_PROPOSAL, candidate.reviewReason(), candidate.targetMemoryId(),
                    candidate.targetVersion());
        }
        if (!context.scopeKnown()) {
            return Decision.of(Action.ADD_PENDING, "SCOPE_UNCERTAIN");
        }
        if (context.suppressedByFamily() || context.suppressedByPredicate()) {
            return Decision.of(Action.IGNORE, "SUPPRESSED");
        }
        if (context.suppressedByEvidence()) {
            // 同一来源被拒过：换措辞重提也不自动生效（§2.3.1 的 EVIDENCE 去重）
            return Decision.of(Action.IGNORE, "EVIDENCE_SUPPRESSED");
        }
        Semantic semantic = classifySemantics(candidate.quote());
        if (semantic == Semantic.EPHEMERAL || semantic == Semantic.HYPOTHETICAL || semantic == Semantic.THIRD_PARTY) {
            // 只说这一次 / 假设句 / 转述别人：都不是用户的长期事实，长期库里什么都不写（§5.1、§8.3）
            return Decision.of(Action.IGNORE, semantic.reason());
        }
        if (candidate.reviewRequired()) {
            return Decision.of(Action.ADD_PENDING, candidate.reviewReason());
        }
        if (semantic == Semantic.AMBIGUOUS) {
            return Decision.of(Action.ADD_PENDING, semantic.reason());
        }
        if (current.isEmpty()) {
            // 自动生效还要求"原话里有明确长期表达"：光靠谓词落在低风险表里不够
            return semantic == Semantic.LONG_TERM && isAutoEligible(candidate.predicate())
                    ? Decision.of(Action.ADD_ACTIVE, "LOW_RISK_SINGLE_VALUED_PREFERENCE")
                    : Decision.of(Action.ADD_PENDING, "NO_EXPLICIT_LONG_TERM_EXPRESSION");
        }
        ActiveFact active = current.get();
        if (candidate.normalizedValue().equals(active.normalizedValue())) {
            return new Decision(Action.CONFIRM, "SAME_VALUE", active.memoryId(), active.version());
        }
        if (candidate.sourceMillis() < active.sourceMillis()) {
            // 后到的任务处理更早的源：不覆盖更新的事实，也不追加成第二条待确认
            return new Decision(Action.IGNORE, "OLDER_SOURCE_LOSES", active.memoryId(), active.version());
        }
        if (candidate.sourceMillis() == active.sourceMillis()) {
            // 同刻两条说法分不出谁更新：只能人工确认（§8.3"新旧时间相同 → REVIEW"那一格）
            return new Decision(Action.ADD_PENDING, "SAME_SOURCE_TIME", active.memoryId(), active.version());
        }
        if (semantic != Semantic.LONG_TERM || !isAutoEligible(candidate.predicate()) || !active.autoUnprotected()) {
            // 三个条件里最先不成立的那个才是给用户看的原因，人工保护优先于风险判断
            String reason = !active.autoUnprotected() ? "MANUAL_PROTECTED_TARGET"
                    : !isAutoEligible(candidate.predicate()) ? "NOT_AUTO_ELIGIBLE_PREDICATE"
                    : "NO_EXPLICIT_LONG_TERM_EXPRESSION";
            return new Decision(Action.ADD_PENDING, reason, active.memoryId(), active.version());
        }
        return new Decision(Action.REPLACE, "NEWER_AUTO_PREFERENCE", active.memoryId(), active.version());
    }

    /** 语义闸门：只做"能不能自动生效"的收窄判断，永不因为命中关键词而新增记忆。 */
    static Semantic classifySemantics(String quote) {
        String text = quote == null ? "" : quote.toLowerCase(Locale.ROOT);
        if (containsAny(text, EPHEMERAL_MARKERS)) {
            return Semantic.EPHEMERAL;
        }
        if (containsAny(text, HYPOTHETICAL_MARKERS)) {
            return Semantic.HYPOTHETICAL;
        }
        if (containsAny(text, QUOTED_MARKERS)) {
            return Semantic.THIRD_PARTY;
        }
        if (containsAny(text, AMBIGUOUS_NEGATION)) {
            return Semantic.AMBIGUOUS;
        }
        return containsAny(text, LONG_TERM_MARKERS) ? Semantic.LONG_TERM : Semantic.PLAIN;
    }

    /** 语义分类。{@code PLAIN} 没有正向长期表达，同样不能自动生效。 */
    enum Semantic {
        LONG_TERM(null),
        PLAIN("NO_EXPLICIT_LONG_TERM_EXPRESSION"),
        EPHEMERAL("EPHEMERAL_REQUEST"),
        HYPOTHETICAL("HYPOTHETICAL_STATEMENT"),
        THIRD_PARTY("THIRD_PARTY_STATEMENT"),
        AMBIGUOUS("NEGATION_AMBIGUITY");

        private final String reason;

        Semantic(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason == null ? name() : reason;
        }
    }

    private static boolean isAutoEligible(String predicate) {
        return AUTO_ELIGIBLE_PREDICATES.contains(predicate);
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
