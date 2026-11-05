package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 估算口径（执行方案 §9.2 尾段，验收 T05）：中文按码点算且宁高不低，emoji 不能被 UTF-16 单元数翻倍，
 * 计数含序列化结构字符，图像不参与文本估算而是每张固定预留。
 */
class TokenEstimatorTest {
    private final TokenEstimator estimator = new TokenEstimator(new ObjectMapper());

    @Test
    void asciiIsQuarterTokenAndSerializationStructureCounts() {
        assertEquals(1, estimator.estimateText("abcd"), "4 个 ASCII = 1 估算 Token");
        assertEquals(0, estimator.estimateText(""));
        assertEquals(0, estimator.estimateText(null));
        assertEquals(3, estimator.estimateSerialized(Map.of("k", "aaaa")), "{\"k\":\"aaaa\"} 共 12 个 ASCII");
        assertEquals(4, estimator.estimateSerialized(List.of("aaaa", "aaaa")));
    }

    @Test
    void chineseIsNeverCountedAsFourCharactersOneToken() {
        assertEquals(6, estimator.estimateText("汉字汉字"), "4 个汉字按 1.5 Token/字算，不是 1 Token");
        assertTrue(estimator.estimateText("解释垃圾回收的三代算法") > "解释垃圾回收的三代算法".length() / 4,
                "方案明令禁止 4 字符=1 Token 的低估口径");
        assertEquals(estimator.estimateText("汉字"), estimator.estimateText("漢字"), "繁体与简体同一档");
        assertEquals(estimator.estimateText("，。"), estimator.estimateText("汉字"), "全角标点走 CJK 档");
    }

    @Test
    void emojiCountsByCodePointNotUtf16Units() {
        String grin = new String(Character.toChars(0x1F600));
        assertEquals(2, estimator.estimateText(grin), "单个 emoji = 2 估算 Token，且不因代理对翻倍");
        assertEquals(estimator.estimateText(grin), estimator.estimateText("😀"));
        String family = "👨‍👩‍👧";
        assertEquals(5, Character.codePointCount(family, 0, family.length()), "连写序列本身是 5 个码点");
        assertEquals(10, estimator.estimateText(family), "emoji 序列按码点累计，比单个字更贵");
        assertEquals(5, estimator.estimateText(grin + "a" + grin), "混排时 ASCII 仍按 1/4 Token，向上取整");
    }

    @Test
    void latinAccentsAndOtherNonAsciiGetTheirOwnBand() {
        assertEquals(1, estimator.estimateText("aaaa"), "纯 ASCII 每字符 1/4 Token");
        assertEquals(2, estimator.estimateText("café"), "é 属其它非 ASCII 档：1 字符 1 Token");
    }

    @Test
    void constantsAreTheContractTheBudgeterReliesOn() {
        assertEquals("context-estimate-v1", TokenEstimator.VERSION, "换权重就是换口径，审计要能区分");
        assertEquals(8, TokenEstimator.MESSAGE_OVERHEAD_TOKENS);
        assertEquals(4096, TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS);
        assertEquals(3, "汉".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(10, estimator.serializedBytes(List.of("汉字")), "字节数是客观量，不参与预算判定");
    }
}
