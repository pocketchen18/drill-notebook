package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 解析 JSON 格式的题目集合，输出与 {@link MarkdownQuestionParser.ParsedQuestion} 兼容的结果，
 * 便于 {@link QuestionImportService} 复用同一套去重、校验、持久化逻辑。
 *
 * 支持两种顶层结构：
 * <ul>
 *   <li>对象 {@code {"questions": [...]}}</li>
 *   <li>数组 {@code [{...}, {...}]}</li>
 * </ul>
 *
 * 单题字段对齐 Markdown 解析器的 frontmatter：
 * <pre>
 * {
 *   "type": "single",
 *   "stem": "题干",
 *   "options": [{"key": "A", "text": "选项"}],
 *   "answer": "A",
 *   "analysis": "解析",
 *   "difficulty": 3,
 *   "tags": ["标签"],
 *   "chapter": "章节",
 *   "groupId": "组ID",
 *   "orderInGroup": 1
 * }
 * </pre>
 */
@Component
public class JsonQuestionParser {
    private static final Logger log = LoggerFactory.getLogger(JsonQuestionParser.class);
    private final ObjectMapper mapper;

    public JsonQuestionParser(ObjectMapper mapper) { this.mapper = mapper; }

    public List<MarkdownQuestionParser.ParsedQuestion> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("JSON 内容为空");
        String cleaned = stripMarkdownFence(source);
        JsonNode root = null;
        Exception lastError = null;
        List<String> candidates = new ArrayList<>();
        candidates.add(cleaned);
        candidates.add(repairUnescapedQuotes(cleaned));
        String salvaged = salvageTruncatedQuestionsJson(cleaned);
        if (salvaged != null && !salvaged.isBlank()) candidates.add(salvaged);
        String salvagedRepaired = salvageTruncatedQuestionsJson(repairUnescapedQuotes(cleaned));
        if (salvagedRepaired != null && !salvagedRepaired.isBlank()) candidates.add(salvagedRepaired);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            try {
                root = mapper.readTree(candidate);
                break;
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (root == null) {
            String preview = cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned;
            log.error("JSON 解析失败 last={} preview={}", lastError == null ? "unknown" : lastError.getMessage(), preview);
            throw new IllegalArgumentException("题目 JSON 解析失败，请检查 AI 返回格式");
        }

        JsonNode questionsNode = root.isArray() ? root : root.path("questions");
        if (!questionsNode.isArray() || questionsNode.isEmpty()) throw new IllegalArgumentException("没有找到题目");

        List<MarkdownQuestionParser.ParsedQuestion> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < questionsNode.size(); index++) {
            try {
                result.add(parseQuestion(questionsNode.get(index), index + 1));
            } catch (IllegalArgumentException error) {
                // 截断 JSON 尾部常有半截题目：跳过坏题，保留已完整解析的题
                errors.add(error.getMessage() == null ? ("第 " + (index + 1) + " 题无效") : error.getMessage());
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(errors.isEmpty() ? "没有找到有效题目" : errors.get(0));
        }
        if (!errors.isEmpty()) {
            log.warn("JSON 导入跳过 {} 道坏题，成功 {} 道：{}", errors.size(), result.size(), String.join("；", errors.stream().limit(3).toList()));
        }
        return result;
    }

    private MarkdownQuestionParser.ParsedQuestion parseQuestion(JsonNode node, int ordinal) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("第 " + ordinal + " 题不是 JSON 对象");

        String type = QuestionTypeRules.requireType(text(node, "type"));
        String stem = text(node, "stem");
        if (stem == null || stem.isBlank()) throw new IllegalArgumentException("第 " + ordinal + " 题题干不能为空");
        stem = stem.trim();

        List<Map<String, String>> options = parseOptions(node.path("options"), ordinal);
        String rawAnswer = text(node, "answer");
        String answer = QuestionTypeRules.canonicalAnswer(type, rawAnswer);
        QuestionTypeRules.validate(type, answer, options);

        String analysis = text(node, "analysis");
        int difficulty = parseDifficulty(node.path("difficulty"), ordinal);
        List<String> tags = parseTags(node.path("tags"));
        String chapter = text(node, "chapter");
        String groupId = text(node, "groupId");
        Integer orderInGroup = parseInteger(node.path("orderInGroup"));

        return new MarkdownQuestionParser.ParsedQuestion(
                type, stem, options, answer, analysis, difficulty, tags, chapter, groupId, orderInGroup);
    }

    private static List<Map<String, String>> parseOptions(JsonNode options, int ordinal) {
        List<Map<String, String>> result = new ArrayList<>();
        if (options == null || options.isMissingNode() || options.isNull()) return result;
        if (!options.isArray()) throw new IllegalArgumentException("第 " + ordinal + " 题的 options 必须是数组");
        for (JsonNode option : options) {
            if (option == null || !option.isObject()) throw new IllegalArgumentException("第 " + ordinal + " 题的选项格式不合法");
            Map<String, String> item = new LinkedHashMap<>();
            item.put("key", text(option, "key"));
            item.put("text", text(option, "text"));
            result.add(item);
        }
        return result;
    }

    private static List<String> parseTags(JsonNode tags) {
        List<String> result = new ArrayList<>();
        if (tags == null || tags.isNull() || !tags.isArray()) return result;
        for (JsonNode tag : tags) {
            if (tag == null) continue;
            String value = tag.asText("").trim();
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private static int parseDifficulty(JsonNode node, int ordinal) {
        if (node == null || node.isNull() || node.asText("").isBlank()) return 3;
        if (!node.isNumber()) throw new IllegalArgumentException("第 " + ordinal + " 题难度必须是 1 到 5 的整数");
        int value = node.asInt();
        if (value < 1 || value > 5) throw new IllegalArgumentException("第 " + ordinal + " 题难度必须是 1 到 5 的整数");
        return value;
    }

    private static Integer parseInteger(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isBlank()) return null;
        if (node.isNumber()) return node.asInt();
        try { return Integer.valueOf(node.asText().trim()); }
        catch (NumberFormatException error) { return null; }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText();
        return text == null ? null : text.trim();
    }

    /**
     * 去掉 AI 返回中常见的 Markdown 代码围栏（```json ... ```），以及首尾多余空白。
     */
    private static String stripMarkdownFence(String source) {
        String trimmed = source.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            else trimmed = trimmed.substring(3);
            trimmed = trimmed.trim();
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    /**
     * 尝试修复 JSON 字符串值里未转义的双引号。
     * <p>
     * AI 模型经常把题干/选项里的双引号直接写进 JSON 字符串而不转义成 {@code \"}，
     * 导致 Jackson 解析失败。这里用一个简单的状态机扫描 JSON 文本：
     * 在字符串值内部遇到双引号时，判断它是否"真的"是字符串结束符——
     * 如果后面紧跟的是 JSON 结构字符（逗号、冒号、括号、空白），认为是结束符；
     * 否则认为是未转义的内容字符，前面补一个反斜杠。
     * <p>
     * 这不是完美的修复方案（无法处理嵌套引号的所有边界情况），但能覆盖
     * AI 返回 JSON 时最常见的双引号转义缺失问题，让导入流程继续往前走。
     */
    private static String repairUnescapedQuotes(String source) {
        if (source == null || source.isEmpty()) return source;
        StringBuilder out = new StringBuilder(source.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                out.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inString) {
                    inString = true;
                    out.append(c);
                    continue;
                }
                // 在字符串内部遇到双引号：判断是否为字符串结束符
                int next = peekNonWhitespace(source, i + 1);
                // 字符串结束符后面应该跟 JSON 结构字符：, : } ] 或行尾
                if (next < 0 || next == ',' || next == ':' || next == '}' || next == ']') {
                    inString = false;
                    out.append(c);
                } else {
                    // 认为是未转义的内容双引号，补一个反斜杠
                    out.append('\\').append('"');
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int peekNonWhitespace(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            return c;
        }
        return -1;
    }

    /**
     * 抢救被 max_tokens 截断的 questions JSON：截到最后一个完整对象，补齐闭合括号。
     * 只用于 AI 兜底，失败时返回 null。
     */
    public static String salvageTruncatedQuestionsJson(String source) {
        if (source == null || source.isBlank()) return null;
        String text = stripMarkdownFence(source);
        int arrayStart = text.indexOf('[');
        if (arrayStart < 0) return null;
        int lastCompleteObjectEnd = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = arrayStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) lastCompleteObjectEnd = i;
            }
        }
        if (lastCompleteObjectEnd < 0) return null;
        StringBuilder recovered = new StringBuilder();
        // 若原文是 {"questions":[... 则保留前缀
        int questionsKey = text.lastIndexOf("\"questions\"", arrayStart);
        if (questionsKey >= 0) {
            int brace = text.lastIndexOf('{', questionsKey);
            if (brace >= 0) recovered.append(text, brace, arrayStart);
            else recovered.append("{\"questions\":");
        } else {
            // 纯数组
        }
        recovered.append(text, arrayStart, lastCompleteObjectEnd + 1);
        // 去掉末尾多余逗号
        int end = recovered.length() - 1;
        while (end > 0 && Character.isWhitespace(recovered.charAt(end))) end--;
        int cursor = end;
        // 从最后一个 } 往前扫，确保数组闭合
        if (recovered.charAt(cursor) == '}') {
            // ok
        }
        // 补齐 ] 与可能的 }
        if (questionsKey >= 0) {
            recovered.append("]}");
        } else {
            recovered.append(']');
        }
        // 清理 `},]` / `,]`
        String normalized = recovered.toString().replaceAll(",\\s*]", "]").replaceAll(",\\s*}", "}");
        return normalized;
    }

}
