package com.drillnotebook.app.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class QuestionTypeRules {
    private static final Set<String> SUPPORTED = Set.of("single", "multiple", "fill", "true_false", "essay");

    private QuestionTypeRules() {}

    public static String requireType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(type)) throw new IllegalArgumentException("题型必须是 single、multiple、fill、true_false 或 essay");
        return type;
    }

    public static boolean isChoice(String type) { return "single".equals(type) || "multiple".equals(type); }

    public static String canonicalAnswer(String type, String value) {
        String answer = value == null ? "" : value.trim();
        if ("essay".equals(type)) return answer;
        if (answer.isBlank()) throw new IllegalArgumentException("该题型必须提供答案");
        if ("true_false".equals(type)) return canonicalTrueFalse(answer);
        if (isChoice(type)) return answer.toUpperCase(Locale.ROOT);
        return answer;
    }

    public static void validate(String type, String answer, List<? extends Map<?, ?>> options) {
        requireType(type);
        if (isChoice(type)) {
            if (options == null || options.size() < 2) throw new IllegalArgumentException("选择题至少需要两个选项");
            Set<String> keys = new HashSet<>();
            for (Map<?, ?> option : options) {
                Object rawKey = option == null ? null : option.get("key");
                String key = normalizeChoice(rawKey == null ? null : String.valueOf(rawKey));
                Object rawText = option == null ? null : option.get("text");
                String text = rawText == null ? "" : String.valueOf(rawText).trim();
                if (key.isBlank()) throw new IllegalArgumentException("选项键不能为空");
                if (!keys.add(key)) throw new IllegalArgumentException("选项键不能重复");
                if (text.isBlank()) throw new IllegalArgumentException("选项内容不能为空");
            }
            String canonical = "multiple".equals(type) ? normalizeMultiple(answer) : normalizeChoice(answer);
            List<String> answers = java.util.Arrays.stream(canonical.split(","))
                    .map(String::trim).filter(item -> !item.isBlank()).toList();
            if ("single".equals(type) && answers.size() != 1) throw new IllegalArgumentException("单选题只能有一个答案");
            if (answers.isEmpty() || answers.stream().anyMatch(key -> !keys.contains(key))) throw new IllegalArgumentException("答案必须引用已有选项");
        } else if (options != null && !options.isEmpty()) {
            throw new IllegalArgumentException("填空、判断和解答题不能包含选择题选项");
        }
        canonicalAnswer(type, answer);
    }

    public static boolean deterministicCorrect(String type, String userAnswer, String expectedAnswer) {
        return switch (requireType(type)) {
            case "single" -> normalizeChoice(userAnswer).equals(normalizeChoice(expectedAnswer));
            case "multiple" -> normalizeMultiple(userAnswer).equals(normalizeMultiple(expectedAnswer));
            case "fill" -> normalizeFill(userAnswer).equals(normalizeFill(expectedAnswer));
            case "true_false" -> canonicalTrueFalse(userAnswer).equals(canonicalTrueFalse(expectedAnswer));
            case "essay" -> throw new IllegalArgumentException("解答题不能使用确定性判题");
            default -> false;
        };
    }

    private static String normalizeChoice(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    private static String normalizeMultiple(String value) {
        return java.util.Arrays.stream(normalizeChoice(value).split(","))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().sorted().collect(java.util.stream.Collectors.joining(","));
    }

    private static String normalizeFill(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        return normalized.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String canonicalTrueFalse(String value) {
        String normalized = normalizeFill(value);
        if (Set.of("true", "t", "1", "对", "是", "正确").contains(normalized)) return "true";
        if (Set.of("false", "f", "0", "错", "否", "错误").contains(normalized)) return "false";
        throw new IllegalArgumentException("判断题答案必须是 true 或 false");
    }
}
