package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.service.memory.MemoryValidator.ValidatedCandidate;
import java.util.Locale;
import java.util.Optional;

/**
 * 确定性冲突判定（P9d 用户裁决后的简化版）。
 *
 * <p>用户明确要求删除确认机制：自动提取的长期事实直接新增，不再产生待确认事实或替换/撤销建议。
 * 只有两种情况例外：同族同归一值只追加来源（{@link Action#CONFIRM}），以及明显不是用户长期事实的
 * 一次性/假设/转述内容（{@link Action#IGNORE}）。是否重复由 writer 在写库前按归一值再做一次
 * 全库校验；不确定是否重复时一律新增。</p>
 *
 * <p>本类是纯函数，不读库、不写库、不碰时钟以外的全局状态，所以判定表可以逐行直接断言。</p>
 */
public final class MemoryConflictResolver {
    /** 服务端对一条候选的最终动作。 */
    public enum Action {
        /** 同族同值：只追加去重来源与刷新 lastConfirmedAt，不新增 ACTIVE。 */
        CONFIRM,
        /** 同族已有不同值且可更新：旧版 SUPERSEDED、新版 ACTIVE，不询问用户。 */
        REPLACE,
        /** 直接写 ACTIVE 自动事实。 */
        ADD_ACTIVE,
        /** 本次要求/假设/引用他人/已有抑制/明显更旧：长期库里什么都不写。 */
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
     * "后到任务处理更早来源"要靠它判新旧。
     */
    public record ActiveFact(String memoryId, int version, String origin, boolean manualProtected, boolean pinned,
                             String normalizedValue, long sourceMillis, long lastConfirmedMillis) {
    }

    /** 判定所需的外部事实：三类抑制命中。{@code scopeKnown} 仅为兼容旧调用保留。 */
    public record Context(boolean suppressedByFamily, boolean suppressedByPredicate, boolean suppressedByEvidence,
                          boolean scopeKnown) {
        public static Context none() {
            return new Context(false, false, false, true);
        }
    }

    /** 一次性/假设/引用他人这三类语义标记：命中就<b>不写长期库</b>。 */
    private static final String[] EPHEMERAL_MARKERS = {"仅这一次", "这一次", "这次只", "本次只", "只是这次",
            "暂时", "这一句", "这一题", "本题"};
    private static final String[] HYPOTHETICAL_MARKERS = {"假设", "假如", "如果我是", "如果我", "要是", "模拟我是"};
    private static final String[] QUOTED_MARKERS = {"引用", "原文", "摘自", "以下是", "下面是", "他说", "她说",
            "他习惯", "她习惯", "我朋友", "同学", "老师建议", "助手建议", "你上次建议"};

    private MemoryConflictResolver() {
    }

    /**
     * 简化判定：只保留“同值确认 / 明显非事实忽略 / 其余直接新增”。
     * 语义闸门只拦一次性、假设和转述；否定、歧义、人工保护、同刻来源都不再进待确认，一律新增。
     */
    public static Decision decide(ValidatedCandidate candidate, Optional<ActiveFact> current, Context context) {
        if ("RETRACT_PROPOSAL".equals(candidate.operation())) {
            return Decision.of(Action.IGNORE, "RETRACTION_DISABLED");
        }
        if ("REPLACE_PROPOSAL".equals(candidate.operation())) {
            if (candidate.targetMemoryId() == null || candidate.targetVersion() == null) {
                return Decision.of(Action.IGNORE, "TARGET_REQUIRED");
            }
            return new Decision(Action.REPLACE, "DIRECT_REPLACE", candidate.targetMemoryId(),
                    candidate.targetVersion());
        }
        if (context.suppressedByFamily() || context.suppressedByPredicate()) {
            return Decision.of(Action.IGNORE, "SUPPRESSED");
        }
        if (context.suppressedByEvidence()) {
            // 同一来源被拒过：换措辞重提也不自动生效（§2.3.1 的 EVIDENCE 去重）
            return Decision.of(Action.IGNORE, "EVIDENCE_SUPPRESSED");
        }
        Semantic semantic = classifySemantics(candidate.quote());
        if (semantic == Semantic.EPHEMERAL || semantic == Semantic.HYPOTHETICAL
                || semantic == Semantic.THIRD_PARTY) {
            // 只说这一次 / 假设句 / 转述别人：都不是用户的长期事实，长期库里什么都不写（§5.1）
            return Decision.of(Action.IGNORE, semantic.reason());
        }
        if (current.isPresent()) {
            ActiveFact active = current.get();
            if (candidate.normalizedValue().equals(active.normalizedValue())) {
                return new Decision(Action.CONFIRM, "SAME_VALUE", active.memoryId(), active.version());
            }
            if (candidate.sourceMillis() < active.sourceMillis()) {
                // 后到的任务处理更早的源：不覆盖更新的事实，也不追加成第二条
                return new Decision(Action.IGNORE, "OLDER_SOURCE_LOSES", active.memoryId(), active.version());
            }
            if (active.manualProtected() || active.pinned()) {
                return new Decision(Action.IGNORE, "MANUAL_PROTECTED", active.memoryId(), active.version());
            }
            return new Decision(Action.REPLACE, "DIRECT_REPLACE", active.memoryId(), active.version());
        }
        return Decision.of(Action.ADD_ACTIVE, "DIRECT_ADD");
    }

    /** 语义闸门：只做“能不能进长期库”的排除判断，永不因为命中关键词而拒绝新增。 */
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
        return Semantic.OTHER;
    }

    /** 语义分类。 */
    enum Semantic {
        EPHEMERAL("EPHEMERAL_REQUEST"),
        HYPOTHETICAL("HYPOTHETICAL_STATEMENT"),
        THIRD_PARTY("THIRD_PARTY_STATEMENT"),
        OTHER(null);

        private final String reason;

        Semantic(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason == null ? name() : reason;
        }
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
