package com.drillnotebook.app.service.memory;

import java.util.Locale;
import java.util.Map;

/**
 * 「为什么这条要你先确认」的用户可读说法（执行方案 §12：候选审阅必须显示为什么待确认）。
 *
 * <p>判定表在 {@link MemoryConflictResolver} 与 {@link MemoryValidator} 里，产出的都是稳定的原因码；
 * 界面不许再抄一份枚举—文案的对应表，所以翻译留在这里，两边（建议列表与待确认候选）共用。</p>
 */
final class MemoryReviewReasons {
    private static final Map<String, String> TEXTS = Map.ofEntries(
            Map.entry("SCOPE_UNCERTAIN", "当时没确定要记在哪个范围，先给你看一眼"),
            Map.entry("SAME_SOURCE_TIME", "同一时刻有另一种说法，服务端分不出谁更新"),
            Map.entry("MANUAL_PROTECTED_TARGET", "旧值是你亲手改过的，后台不得自行覆盖"),
            Map.entry("NOT_AUTO_ELIGIBLE_PREDICATE", "这类内容风险不低，需要你确认"),
            Map.entry("NO_EXPLICIT_LONG_TERM_EXPRESSION", "原话里没说要长期生效，先存着待确认"),
            Map.entry("OLDER_SOURCE_LOSES", "这条来自更早的一句话，已被更新的说法盖过"),
            Map.entry("NEWER_AUTO_PREFERENCE", "更新的一句话与旧值不同，等你点头"),
            Map.entry("NEGATION_AMBIGUITY", "原话里有否定转折，改口对象可能是别的"),
            Map.entry("MODEL_REQUESTED_REVIEW", "模型自己标注了要你确认"),
            Map.entry("PREDICATE_OTHER", "归到「其它」类别，值得你亲眼看一次"),
            Map.entry("SAME_FAMILY_DIFFERENT_VALUE", "同一类里已有别的值，需要你选一个"),
            Map.entry("CREDENTIAL_VALUE", "看起来含凭据，确认后才写入"));

    private MemoryReviewReasons() {
    }

    /** 未知原因码不猜含义：把码本身交出去，界面上仍然可读、也不谎报原因。 */
    static String text(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        String known = TEXTS.get(normalized);
        return known == null ? "这条需要你确认（" + normalized + "）" : known;
    }
}
