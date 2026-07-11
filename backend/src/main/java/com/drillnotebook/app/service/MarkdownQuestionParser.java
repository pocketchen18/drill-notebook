package com.drillnotebook.app.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MarkdownQuestionParser {
    private static final Pattern OPTION = Pattern.compile("^\\s*([A-Za-z])(?:[.]|[)])\\s+(.+?)\\s*$");
    private static final Pattern FRONTMATTER_BLOCK = Pattern.compile("(?ms)^\\s*---\\s*\\R(.*?)\\R---\\s*(.*?)(?=\\R\\s*---\\s*\\R|\\z)");

    public List<ParsedQuestion> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<ParsedQuestion> result = new ArrayList<>();
        for (String chunk : normalized.split("(?m)^===\\s*$")) {
            Matcher matcher = FRONTMATTER_BLOCK.matcher(chunk);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                result.add(parseBlock(matcher.group(1), matcher.group(2)));
            }
            if (!found && !chunk.isBlank()) result.add(parseBlockFromText(chunk));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("没有找到题目块");
        return result;
    }

    private ParsedQuestion parseBlockFromText(String block) {
        String trimmed = block.trim();
        if (!trimmed.startsWith("---")) throw new IllegalArgumentException("题目缺少 YAML frontmatter");
        int closing = trimmed.indexOf("\n---", 3);
        if (closing < 0) throw new IllegalArgumentException("YAML frontmatter 没有闭合");
        return parseBlock(trimmed.substring(3, closing), trimmed.substring(closing + 4));
    }

    private ParsedQuestion parseBlock(String frontmatter, String body) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : frontmatter.replace('\r', '\n').split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            metadata.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        String type = metadata.getOrDefault("type", "").toLowerCase();
        if (!type.equals("single") && !type.equals("multiple")) throw new IllegalArgumentException("题型必须是 single 或 multiple");
        String answer = stripValue(metadata.get("answer"));
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("题目缺少 answer");

        List<String> stemLines = new ArrayList<>();
        List<String> analysisLines = new ArrayList<>();
        List<Map<String, String>> options = new ArrayList<>();
        boolean inStem = false;
        boolean inAnalysis = false;
        for (String line : body.replace('\r', '\n').split("\n")) {
            String clean = line.trim();
            if (clean.startsWith("###")) {
                String heading = clean.substring(3).trim();
                inStem = heading.contains("题干") || heading.equalsIgnoreCase("stem");
                inAnalysis = heading.contains("解析") || heading.equalsIgnoreCase("analysis");
                continue;
            }
            Matcher option = OPTION.matcher(line);
            if (option.matches()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("key", option.group(1).toUpperCase());
                item.put("text", option.group(2).trim());
                options.add(item);
                inStem = false;
                continue;
            }
            if (inAnalysis) analysisLines.add(line);
            else if (inStem || options.isEmpty()) stemLines.add(line);
        }
        String stem = joinMeaningful(stemLines);
        if (stem.isBlank()) throw new IllegalArgumentException("题干不能为空");
        if (options.isEmpty()) throw new IllegalArgumentException("题目至少需要一个选项");
        String tags = metadata.get("tags");
        String chapter = stripValue(metadata.get("chapter"));
        String groupId = stripValue(metadata.get("group_id"));
        int difficulty = parseDifficulty(metadata.get("difficulty"));
        return new ParsedQuestion(type, stem, options, answer.toUpperCase(), joinMeaningful(analysisLines), difficulty, parseTags(tags), chapter, groupId, parseInteger(metadata.get("order_in_group")));
    }

    private static String joinMeaningful(List<String> lines) {
        return String.join("\n", lines).replaceAll("(?m)^[ \\t]+|[ \\t]+$", "").trim();
    }

    private static String stripValue(String value) {
        if (value == null) return null;
        String result = value.trim();
        if ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'"))) return result.substring(1, result.length() - 1);
        return result;
    }

    private static List<String> parseTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        String clean = value.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) clean = clean.substring(1, clean.length() - 1);
        return Arrays.stream(clean.split(",")).map(String::trim).map(MarkdownQuestionParser::stripValue).filter(tag -> tag != null && !tag.isBlank()).toList();
    }

    private static int parseDifficulty(String value) {
        int parsed = parseInteger(value) == null ? 3 : parseInteger(value);
        return Math.max(1, Math.min(5, parsed));
    }

    private static Integer parseInteger(String value) {
        try { return value == null ? null : Integer.valueOf(value.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    public record ParsedQuestion(String type, String stem, List<Map<String, String>> options, String answer, String analysis,
                                 int difficulty, List<String> tags, String chapter, String groupId, Integer orderInGroup) {}
}
