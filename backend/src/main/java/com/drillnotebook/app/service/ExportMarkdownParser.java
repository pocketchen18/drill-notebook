package com.drillnotebook.app.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 解析前端 {@code questionMarkdown} 导出格式（叙述式 Markdown）。
 *
 * <p>输入格式特征：
 * <ul>
 *   <li>文件开头可能有 {@code # 标题} 行（不产出题目）</li>
 *   <li>每题以 {@code ### 题干} 开头</li>
 *   <li>题干后跟一个 {@code > } 引用块作为元数据（题型/章节/标签/难度），分号分隔</li>
 *   <li>选项行 {@code A. text} 格式</li>
 *   <li>答案标记 {@code **答案：** X}（选择/填空/判断）或 {@code **参考答案：** ...}（解答）</li>
 *   <li>解析标记 {@code **解析：**} 后续行直到块尾为解析</li>
 *   <li>题与题之间用 {@code ---} 分隔</li>
 * </ul>
 *
 * <p>产出 {@link MarkdownQuestionParser.ParsedQuestion}，与现有导入链路兼容。
 * {@code group_id} 和 {@code order_in_group} 恒为 {@code null}（导出格式未携带）。
 */
@Component
public class ExportMarkdownParser {

    private static final Pattern OPTION = Pattern.compile("^\\s*([A-Za-z])(?:[.]|[)])\\s+(.+?)\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("(?m)^---\\s*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern ANSWER_MARKER = Pattern.compile("^\\*\\*(?:答案|参考答案)[：:]\\*\\*\\s*(.*)$");
    private static final Pattern ANALYSIS_MARKER = Pattern.compile("^\\*\\*解析[：:]\\*\\*\\s*$");

    private static final Map<String, String> TYPE_BY_LABEL = Map.of(
            "单选", "single",
            "多选", "multiple",
            "填空", "fill",
            "判断", "true_false",
            "解答", "essay");

    public List<MarkdownQuestionParser.ParsedQuestion> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<MarkdownQuestionParser.ParsedQuestion> result = new ArrayList<>();
        for (String chunk : SEPARATOR.split(normalized)) {
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) continue;
            // 必须含 ### 题目标题才视为题目块（导出格式每题都以 ### 开头）
            if (!hasQuestionHeading(trimmed)) continue;
            result.add(parseBlock(trimmed));
        }
        return result;
    }

    private static boolean hasQuestionHeading(String trimmed) {
        for (String line : trimmed.split("\n")) {
            if (line.trim().startsWith("###")) return true;
        }
        return false;
    }

    private MarkdownQuestionParser.ParsedQuestion parseBlock(String block) {
        List<String> stemLines = new ArrayList<>();
        List<String> analysisLines = new ArrayList<>();
        List<String> answerLines = new ArrayList<>();
        List<Map<String, String>> options = new ArrayList<>();
        String answer = null;
        String typeLabel = null;
        String chapter = null;
        String tagsRaw = null;
        String difficultyRaw = null;

        boolean inStem = false;
        boolean inAnalysis = false;
        boolean inOptions = false;
        boolean inAnswer = false;
        boolean answerSeen = false;

        String[] lines = block.split("\n");
        for (String line : lines) {
            String clean = line.trim();

            if (clean.startsWith(">")) {
                String metadata = clean.substring(1).trim();
                for (String pair : metadata.split("[；;]")) {
                    int colon = indexOfColon(pair);
                    if (colon <= 0) continue;
                    String key = pair.substring(0, colon).trim();
                    String value = pair.substring(colon + 1).trim();
                    switch (key) {
                        case "题型" -> typeLabel = value;
                        case "章节" -> chapter = value;
                        case "标签" -> tagsRaw = value;
                        case "难度" -> difficultyRaw = value;
                        default -> { /* ignore unknown metadata */ }
                    }
                }
                continue;
            }

            // 跳过文档/章节级标题（# 或 ## 开头但非 ###）
            if (HEADING.matcher(clean).matches() && !clean.startsWith("###")) {
                continue;
            }

            if (clean.startsWith("###")) {
                String heading = clean.substring(3).trim();
                if (heading.contains("题干") || heading.equalsIgnoreCase("stem")) {
                    inStem = true;
                    inAnalysis = false;
                    inOptions = false;
                    stemLines.clear();
                    continue;
                }
                if (heading.contains("解析") || heading.equalsIgnoreCase("analysis")) {
                    inStem = false;
                    inAnalysis = true;
                    inOptions = false;
                    analysisLines.clear();
                    continue;
                }
                // 否则：题干内容直接跟在 ### 后面（导出格式：### <stem>）
                inStem = false;
                inAnalysis = false;
                inOptions = false;
                if (!heading.isEmpty()) stemLines.add(heading);
                continue;
            }

            Matcher answerMatcher = ANSWER_MARKER.matcher(clean);
            if (answerMatcher.matches()) {
                String inline = answerMatcher.group(1).trim();
                if (!inline.isEmpty()) {
                    answer = inline;
                    inAnswer = false;
                } else {
                    // 多行参考答案：标记位打开，后续行收集到 answerLines
                    inAnswer = true;
                }
                inStem = false;
                inAnalysis = false;
                inOptions = false;
                answerSeen = true;
                continue;
            }

            if (ANALYSIS_MARKER.matcher(clean).matches()) {
                inStem = false;
                inAnalysis = true;
                inAnswer = false;
                inOptions = false;
                continue;
            }

            Matcher optionMatcher = OPTION.matcher(line);
            if (optionMatcher.matches()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("key", optionMatcher.group(1).toUpperCase(Locale.ROOT));
                item.put("text", optionMatcher.group(2).trim());
                options.add(item);
                inStem = false;
                inOptions = true;
                inAnswer = false;
                continue;
            }

            if (inAnswer) {
                answerLines.add(line);
            } else if (inAnalysis) {
                analysisLines.add(line);
            } else if (inStem || (!inOptions && !answerSeen && options.isEmpty())) {
                stemLines.add(line);
            }
        }

        String stem = joinTrimmed(stemLines);
        if (stem.isBlank()) throw new IllegalArgumentException("题干不能为空");

        String type = typeLabel != null ? mapTypeLabel(typeLabel) : inferType(options, answer);

        // 多行参考答案回填
        if (answer == null || answer.isEmpty()) {
            answer = joinTrimmed(answerLines);
        }

        answer = answer == null ? "" : answer;
        if ("multiple".equals(type)) {
            answer = Arrays.stream(answer.split("[,，、\\s]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toUpperCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
        } else if (QuestionTypeRules.isChoice(type)) {
            answer = answer.toUpperCase(Locale.ROOT);
        }

        QuestionTypeRules.validate(type, answer, options);

        int difficulty = parseDifficulty(difficultyRaw);
        List<String> tags = parseTags(tagsRaw);

        return new MarkdownQuestionParser.ParsedQuestion(
                type, stem, options, answer, joinTrimmed(analysisLines),
                difficulty, tags, chapter, null, null);
    }

    private static int indexOfColon(String pair) {
        int cjk = pair.indexOf('：');
        int ascii = pair.indexOf(':');
        if (cjk < 0) return ascii;
        if (ascii < 0) return cjk;
        return Math.min(cjk, ascii);
    }

    private static String mapTypeLabel(String label) {
        String normalized = label.trim();
        String type = TYPE_BY_LABEL.get(normalized);
        if (type != null) return type;
        return QuestionTypeRules.requireType(normalized);
    }

    private static String inferType(List<Map<String, String>> options, String answer) {
        if (options != null && options.size() >= 2) {
            if (answer != null && answer.matches("[A-Za-z,，、\\s]+")) {
                long letterCount = answer.chars().filter(Character::isLetter).count();
                return letterCount > 1 ? "multiple" : "single";
            }
            return "single";
        }
        if (answer == null || answer.isBlank()) return "essay";
        String lower = answer.trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "false", "对", "错", "是", "否", "t", "f").contains(lower)) {
            return "true_false";
        }
        return "fill";
    }

    private static String joinTrimmed(List<String> lines) {
        return String.join("\n", lines).replaceAll("(?m)^[ \\t]+|[ \\t]+$", "").trim();
    }

    private static int parseDifficulty(String value) {
        if (value == null || value.isBlank()) return 3;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > 5) throw new IllegalArgumentException("difficulty 必须是 1 到 5 的整数");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("difficulty 必须是 1 到 5 的整数");
        }
    }

    private static List<String> parseTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[、,，\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
