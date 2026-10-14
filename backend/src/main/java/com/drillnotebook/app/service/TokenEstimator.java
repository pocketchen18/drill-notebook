package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * 上下文估算器（执行方案 §9.2 尾段）：v0.7 不为所有兼容网关强配某一家的 tokenizer，
 * 所以一律按"最终厂商序列化形态"做保守估算，并把 {@code estimated=true} 如实带进审计。
 *
 * <p>权重按字符类分档（单位 1/4 token，向上取整）：ASCII 0.25、CJK 与全角 1.5、
 * emoji 2.0、其它非 ASCII 1.0。方案明令不能按"4 个汉字 = 1 Token"算，所以 CJK 取高于
 * 常见真实值（约 1～1.4）的 1.5，宁可多算让上下文少装，也不冒超窗的险。</p>
 */
@Component
public class TokenEstimator {
    /** 写进审计的估算器版本：换权重就是换口径，必须能在事后区分。 */
    public static final String VERSION = "context-estimate-v1";
    /** 每条消息的角色、分隔与封装开销（§9.2"计数含角色/分隔符开销"）。 */
    public static final int MESSAGE_OVERHEAD_TOKENS = 8;
    /** 图像计费未知时的预留估算（contracts §4.3）：不能把 base64 长度当收费 Token。 */
    public static final int IMAGE_UNKNOWN_ESTIMATED_TOKENS = 4096;

    private static final int ASCII_UNITS = 1;
    private static final int CJK_UNITS = 6;
    private static final int EMOJI_UNITS = 8;
    private static final int OTHER_UNITS = 4;

    private final ObjectMapper mapper;

    public TokenEstimator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 一段文本的估算 Token。空串为 0，序列化失败按 UTF-8 字节数兜底（宁高不低）。 */
    public int estimateText(String value) {
        if (value == null || value.isEmpty()) return 0;
        int units = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            units += unitsFor(codePoint);
            index += Character.charCount(codePoint);
        }
        return ceilQuarters(units);
    }

    /** 对最终要发出去的形态整体估算：JSON 的结构字符是 ASCII，个人内容保持自己的字符类权重。 */
    public int estimateSerialized(Object body) {
        return estimateText(serialize(body));
    }

    /** 序列化后的实际 UTF-8 字节数：审计与调试都用得上的客观量，不参与预算判定。 */
    public int serializedBytes(Object body) {
        return serialize(body).getBytes(StandardCharsets.UTF_8).length;
    }

    private String serialize(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception error) {
            return String.valueOf(body);
        }
    }

    private static int unitsFor(int codePoint) {
        if (codePoint < 0x80) return ASCII_UNITS;
        if (isCjk(codePoint)) return CJK_UNITS;
        if (isEmoji(codePoint)) return EMOJI_UNITS;
        return OTHER_UNITS;
    }

    /** CJK 汉字、假名、谚文与中日全角标点：这些是"4 字符=1 Token"会明显低估的区段。 */
    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x1100 && (codePoint <= 0x11FF        // 谚文字母
                || (codePoint >= 0x2E80 && codePoint <= 0x303F)   // CJK 部首、标点
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)   // 平假名、片假名
                || (codePoint >= 0x3100 && codePoint <= 0x312F)   // 注音
                || (codePoint >= 0x3200 && codePoint <= 0x33FF)   // 兼容表意、兼容
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)   // 统一汉字
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)   // 谚文音节
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)   // 兼容汉字
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF)   // 全角形式
                || (codePoint >= 0x20000 && codePoint <= 0x323AF)); // 扩展 B–G
    }

    /** emoji 大多由多个码点拼成，且各家 tokenizer 常按 2 以上计费。 */
    private static boolean isEmoji(int codePoint) {
        return codePoint == 0x200D || codePoint == 0xFE0F
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x1F300 && codePoint <= 0x1F5FF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF);
    }

    private static int ceilQuarters(int units) {
        return (units + 3) / 4;
    }
}
