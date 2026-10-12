package com.drillnotebook.app.service.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 记忆检索的词法口径（执行方案 §9.1 第 2 条）：NFKC + Latin 小写、汉字连续段生成相邻二元组和三元组、
 * Latin/数字按词切分、过滤固定的小型停用词表。
 *
 * <p>它刻意不做任何语义扩展：没有同义词表、没有模型改写、没有向量。词法召回的上限由 §14 P0a 实测确认，
 * 唯一允许的补偿是 {@link #predicateLabel(String)} 这份有限词表——P0a 证明只用英文 predicate 名时
 * 中文查询零命中，标签词表是中文词法召回的必要条件，不是可选优化。</p>
 */
public final class MemoryLexicon {
    /** §9.1 第 2 条的"固定小型停用词表"：只放确定不会成为主题词的虚词。 */
    static final Set<String> STOPWORDS = Set.of("的", "了", "是", "请", "帮我", "一下", "吧", "呢", "吗",
            "and", "the", "a", "an", "to", "of", "me", "my", "please");

    /**
     * 有限 predicate 词表的中文标签（contracts §1）。key 用完整 predicate；未收录的写法按前缀兜底，
     * 让 {@code answer.length} 这类手写谓词也能拿到风格标签。
     */
    private static final Map<String, String> PREDICATE_LABELS = Map.of(
            "answer.language", "语言 英文 英语 中文 日语 用语 术语",
            "answer.detail", "简短 简洁 详细 啰嗦 冗长 篇幅 展开 步骤",
            "answer.order", "顺序 例子 举例 原理 概念 结论 代码 证明",
            "study.goal", "目标 备考 考试 学习 计划 章节 重点 复习",
            "study.background", "背景 专业 学生 年级 经历 年限",
            "task.constraint", "约束 要求 限制 避免 不要 格式 表格",
            "other", "习惯 偏好 安排 方式");

    /** 前缀兜底：手写谓词没进有限词表时，至少归到同一大类的标签。 */
    private static final Map<String, String> PREFIX_LABELS = Map.of(
            "answer", "回答 风格 语言 简短 详细 顺序",
            "study", "学习 目标 复习 章节",
            "task", "要求 约束 避免");

    private MemoryLexicon() {
    }

    /** NFKC 归一 + Latin 小写；全角 Ｒｕｓｔ 与半角 rust 必须是同一个 token。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /**
     * 切词结果按出现顺序返回（打分要数词频，所以这里不去重）。
     * 汉字连续段长度 ≥2 才产二元/三元组，长度为 1 时只产该单字——这正是 §9.1 第 2 条"单字查询不跑
     * 全库模糊匹配"的实现：正文里的多字段落永远不会被切成单字 token，单字查询因此只能命中显式 key/标签。
     */
    public static List<String> tokens(String raw) {
        List<String> tokens = new ArrayList<>();
        for (Segment segment : segments(raw)) {
            expand(segment, tokens);
        }
        tokens.removeIf(STOPWORDS::contains);
        return tokens;
    }

    /**
     * 按"概念"分组的 token 集合：一个汉字连续段或一个 Latin/数字词算一个概念。
     *
     * <p>相关性比例必须按概念数算，不能按 n-gram 数算——七个汉字会产出十个 n-gram，拿它当分母就等于
     * 要求用户复述整句原文，实测会把"我做过多久后端"这类正常提问判成无关。</p>
     */
    public static List<Set<String>> concepts(String raw) {
        List<Set<String>> groups = new ArrayList<>();
        for (Segment segment : segments(raw)) {
            List<String> expanded = new ArrayList<>();
            expand(segment, expanded);
            Set<String> group = new LinkedHashSet<>();
            for (String token : expanded) {
                if (!STOPWORDS.contains(token)) {
                    group.add(token);
                }
            }
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        return groups;
    }

    /** 回答风格谓词：kind=PREFERENCE 且 predicate 属于 {@code answer.*}（contracts §1 的三条风格谓词）。 */
    public static boolean isAnswerStyle(String kind, String predicate) {
        return "PREFERENCE".equals(kind) && predicate != null && predicate.startsWith("answer.");
    }

    /** predicate 的中文标签词；未知谓词按前缀兜底，可能返回空串。 */
    public static String predicateLabel(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return "";
        }
        String exact = PREDICATE_LABELS.get(predicate);
        if (exact != null) {
            return exact;
        }
        int split = predicate.indexOf('.');
        return split > 0 ? PREFIX_LABELS.getOrDefault(predicate.substring(0, split), "") : "";
    }

    public static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** 一个连续段：汉字段按 n-gram 展开，Latin/数字段整词。 */
    private record Segment(int[] codePoints, boolean han) {
    }

    private static List<Segment> segments(String raw) {
        String normalized = normalize(raw);
        List<Segment> segments = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        boolean runIsHan = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean han = isHan(codePoint);
            boolean letterOrDigit = Character.isLetterOrDigit(codePoint);
            if (!han && !letterOrDigit) {
                flush(segments, run, runIsHan);
                continue;
            }
            if (!run.isEmpty() && han != runIsHan) {
                flush(segments, run, runIsHan);
            }
            runIsHan = han;
            run.add(codePoint);
        }
        flush(segments, run, runIsHan);
        return segments;
    }

    private static void flush(List<Segment> segments, List<Integer> run, boolean han) {
        if (run.isEmpty()) {
            return;
        }
        int[] codePoints = new int[run.size()];
        for (int index = 0; index < run.size(); index++) {
            codePoints[index] = run.get(index);
        }
        segments.add(new Segment(codePoints, han));
        run.clear();
    }

    private static void expand(Segment segment, List<String> sink) {
        int[] codePoints = segment.codePoints();
        if (!segment.han()) {
            sink.add(new String(codePoints, 0, codePoints.length));
            return;
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            sink.add(new String(codePoints, index, 2));
        }
        for (int index = 0; index + 2 < codePoints.length; index++) {
            sink.add(new String(codePoints, index, 3));
        }
        if (codePoints.length == 1) {
            sink.add(new String(codePoints, 0, 1));
        }
    }
}
