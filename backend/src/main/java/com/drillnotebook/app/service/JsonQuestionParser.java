package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper mapper;

    public JsonQuestionParser(ObjectMapper mapper) { this.mapper = mapper; }

    public List<MarkdownQuestionParser.ParsedQuestion> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("JSON 内容为空");
        JsonNode root;
        try { root = mapper.readTree(source); }
        catch (Exception error) { throw new IllegalArgumentException("JSON 格式不合法：" + error.getMessage()); }

        JsonNode questionsNode = root.isArray() ? root : root.path("questions");
        if (!questionsNode.isArray() || questionsNode.isEmpty()) throw new IllegalArgumentException("没有找到题目");

        List<MarkdownQuestionParser.ParsedQuestion> result = new ArrayList<>();
        for (int index = 0; index < questionsNode.size(); index++) {
            result.add(parseQuestion(questionsNode.get(index), index + 1));
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

}
