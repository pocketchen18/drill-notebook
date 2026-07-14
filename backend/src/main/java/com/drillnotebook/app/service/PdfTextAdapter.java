package com.drillnotebook.app.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将 PDF 提取的原始文本转换为规整 Markdown。
 * 处理 Z 场景混合版式：版式 Y（题答案一体）、版式 X（题答案分离）、混合。
 */
@Component
public class PdfTextAdapter {
    private static final Pattern QUESTION_NUMBER = Pattern.compile("^\\s*(\\d+)[.、．]\\s*");
    private static final Pattern OPTION = Pattern.compile("^\\s*([A-Da-d])[.、\\)）]\\s*(.*)$");
    private static final Pattern ANSWER_MARKER =
            Pattern.compile("(?:答案|正确答案|答)[：:]\\s*([A-Da-d、,\\s]+)|【答案】\\s*([A-Da-d、,\\s]+)");
    /** 答案前缀（答案：/正确答案：/答：），用于 ANSWER_MARKER 不命中时回退捕获整段答案文本。 */
    private static final Pattern ANSWER_PREFIX =
            Pattern.compile("^\\s*(?:答案|正确答案|答)[：:]\\s*(.*)$");
    /** 解析前缀（解析：/【解析】/【分析】），命中时既进入解析状态又保留同行内容。 */
    private static final Pattern ANALYSIS_PREFIX =
            Pattern.compile("^\\s*(?:解析|【解析】|【分析】)[：:]?\\s*(.*)$");
    private static final Pattern REFERENCE_SECTION =
            Pattern.compile("参考答案|答案及解析|答案与解析|参考答案及解析|参考答案$", Pattern.CASE_INSENSITIVE);

    public record AdapterResult(
            String markdown,
            int totalQuestions,
            int completeFrontmatter,
            int validOptions,
            double confidence
    ) {}

    public AdapterResult adapt(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new AdapterResult("", 0, 0, 0, 0.0);
        }
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        Map<Integer, String> referenceAnswers = scanReferenceAnswers(normalized);
        List<ParsedBlock> blocks = splitIntoBlocks(normalized, referenceAnswers);
        if (blocks.isEmpty()) {
            return new AdapterResult("", 0, 0, 0, 0.0);
        }
        List<String> markdownParts = new ArrayList<>();
        int completeFrontmatter = 0;
        int validOptions = 0;
        for (ParsedBlock block : blocks) {
            String answer = block.inlineAnswer != null ? block.inlineAnswer : referenceAnswers.get(block.number);
            String type = inferType(block.options, answer);
            if (type != null && answer != null && !answer.isBlank()) completeFrontmatter++;
            if (block.options.size() >= 2) validOptions++;
            markdownParts.add(formatQuestion(block, type, answer));
        }
        String markdown = String.join("\n", markdownParts);
        double confidence = computeConfidence(blocks.size(), completeFrontmatter, validOptions);
        return new AdapterResult(markdown, blocks.size(), completeFrontmatter, validOptions, confidence);
    }

    private List<ParsedBlock> splitIntoBlocks(String text, Map<Integer, String> referenceAnswers) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n");
        ParsedBlock current = null;
        boolean inReferenceSection = false;
        for (String line : lines) {
            if (REFERENCE_SECTION.matcher(line).find()) {
                inReferenceSection = true;
                if (current != null) { blocks.add(current); current = null; }
                continue;
            }
            if (inReferenceSection) continue;
            Matcher numberMatcher = QUESTION_NUMBER.matcher(line);
            if (numberMatcher.find()) {
                if (current != null) blocks.add(current);
                int number = Integer.parseInt(numberMatcher.group(1));
                String stem = line.substring(numberMatcher.end()).trim();
                current = new ParsedBlock(number, stem);
            } else if (current != null) {
                Matcher answerMatcher = ANSWER_MARKER.matcher(line);
                if (answerMatcher.find()) {
                    String answer = answerMatcher.group(1) != null ? answerMatcher.group(1) : answerMatcher.group(2);
                    current.inlineAnswer = cleanAnswer(answer);
                } else {
                    Matcher prefixMatcher = ANSWER_PREFIX.matcher(line);
                    if (prefixMatcher.find()) {
                        String answer = prefixMatcher.group(1).trim();
                        if (!answer.isBlank()) current.inlineAnswer = answer;
                    } else if (line.contains("解析") || line.contains("【解析】") || line.contains("【分析】")) {
                        current.inAnalysis = true;
                        Matcher analysisMatcher = ANALYSIS_PREFIX.matcher(line);
                        if (analysisMatcher.find()) {
                            String content = analysisMatcher.group(1).trim();
                            if (!content.isBlank()) current.analysisLines.add(content);
                        }
                    } else if (current.inAnalysis) {
                        if (!line.isBlank()) current.analysisLines.add(line.trim());
                    } else {
                        Matcher optionMatcher = OPTION.matcher(line);
                        if (optionMatcher.find()) {
                            Map<String, String> option = new LinkedHashMap<>();
                            option.put("key", optionMatcher.group(1).toUpperCase());
                            option.put("text", optionMatcher.group(2).trim());
                            current.options.add(option);
                        } else if (!line.isBlank()) {
                            if (current.options.isEmpty()) {
                                current.stem += "\n" + line.trim();
                            } else {
                                Map<String, String> last = current.options.get(current.options.size() - 1);
                                last.put("text", last.get("text") + " " + line.trim());
                            }
                        }
                    }
                }
            }
        }
        if (current != null) blocks.add(current);
        return blocks;
    }

    private Map<Integer, String> scanReferenceAnswers(String text) {
        Map<Integer, String> answerMap = new LinkedHashMap<>();
        String[] lines = text.split("\n");
        boolean inReferenceSection = false;
        for (String line : lines) {
            if (REFERENCE_SECTION.matcher(line).find()) {
                inReferenceSection = true;
                continue;
            }
            if (inReferenceSection) {
                Matcher numberMatcher = QUESTION_NUMBER.matcher(line);
                if (numberMatcher.find()) {
                    int number = Integer.parseInt(numberMatcher.group(1));
                    String rest = line.substring(numberMatcher.end()).trim();
                    Matcher answerMatcher = ANSWER_MARKER.matcher(rest);
                    if (answerMatcher.find()) {
                        String answer = answerMatcher.group(1) != null ? answerMatcher.group(1) : answerMatcher.group(2);
                        answerMap.put(number, cleanAnswer(answer));
                    } else {
                        Matcher letterMatcher = Pattern.compile("^([A-Da-d])").matcher(rest);
                        if (letterMatcher.find()) {
                            answerMap.put(number, letterMatcher.group(1).toUpperCase());
                        }
                    }
                }
            }
        }
        return answerMap;
    }

    private String inferType(List<Map<String, String>> options, String answer) {
        if (options != null && options.size() >= 2) {
            if (answer == null) return "single";
            if (answer.contains(",") || answer.contains("、")) return "multiple";
            return "single";
        }
        if (answer == null || answer.isBlank()) return "essay";
        String lower = answer.toLowerCase();
        if (lower.equals("true") || lower.equals("false") || lower.equals("t") || lower.equals("f")
                || answer.equals("对") || answer.equals("错") || answer.equals("是") || answer.equals("否")) {
            return "true_false";
        }
        return "fill";
    }

    private String formatQuestion(ParsedBlock block, String type, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (type != null) sb.append("type: ").append(type).append("\n");
        if (answer != null && !answer.isBlank()) sb.append("answer: ").append(answer).append("\n");
        sb.append("---\n");
        sb.append("### 题干\n");
        sb.append(block.stem.trim()).append("\n");
        for (Map<String, String> option : block.options) {
            sb.append(option.get("key")).append(". ").append(option.get("text")).append("\n");
        }
        if (!block.analysisLines.isEmpty()) {
            sb.append("### 解析\n");
            for (String line : block.analysisLines) {
                if (!line.isBlank()) sb.append(line).append("\n");
            }
        }
        sb.append("===");
        return sb.toString();
    }

    private static String cleanAnswer(String answer) {
        if (answer == null) return null;
        return answer.replaceAll("[\\s、]", ",").replaceAll(",,+", ",").toUpperCase();
    }

    private static double computeConfidence(int total, int completeFrontmatter, int validOptions) {
        if (total == 0) return 0.0;
        double frontmatterScore = (double) completeFrontmatter / total;
        double optionScore = (double) validOptions / Math.max(1, total);
        return (frontmatterScore + optionScore) / 2;
    }

    private static class ParsedBlock {
        final int number;
        String stem;
        final List<Map<String, String>> options = new ArrayList<>();
        String inlineAnswer;
        final List<String> analysisLines = new ArrayList<>();
        boolean inAnalysis;

        ParsedBlock(int number, String stem) {
            this.number = number;
            this.stem = stem;
        }
    }
}
