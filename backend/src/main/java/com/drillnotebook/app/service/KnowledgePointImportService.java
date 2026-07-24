package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.KnowledgePointRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgePointImportService {
    private final KnowledgePointRepository points;
    private final AiService aiService;

    public KnowledgePointImportService(KnowledgePointRepository points, AiService aiService) {
        this.points = points;
        this.aiService = aiService;
    }

    /**
     * 知识点 Markdown 导入：先走规则解析，规则失败时由 AI 兜底。
     * 自动按文档里实际出现的最深一级标题作为知识点边界（1-6），
     * 更浅级别的标题行并入正文作 inheritedCategory，更深的并入正文并记入 headingPath。
     * AI 兜底把原文喂给模型，模型返回 [{title,content,category,tags}] JSON，再统一入库。
     * AI 不可用时透传错误。
     */
    public Map<String, Object> importMarkdown(Long bankId, String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        ParseOutcome outcome;
        try {
            List<Section> sections = parse(source);
            outcome = new ParseOutcome(sections, "rules");
        } catch (IllegalArgumentException ruleError) {
            List<Section> aiSections = aiService.parseKnowledgePointsFromText(source).stream()
                    .map(KnowledgePointImportService::toSection)
                    .toList();
            if (aiSections.isEmpty()) {
                throw new IllegalArgumentException(
                        "规则解析失败且 AI 兜底不可用："
                                + (ruleError.getMessage() == null ? "未知错误" : ruleError.getMessage()));
            }
            outcome = new ParseOutcome(aiSections, "ai-fallback");
        }
        List<Section> sections = outcome.sections;
        int imported = 0;
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            try {
                points.insert(bankId, section.title(), section.content(), section.category(), section.tags(), section.headingPath(), List.of());
                imported++;
            } catch (Exception error) {
                errors.add("第 " + (index + 1) + " 个知识点：" + (error.getMessage() == null ? "导入失败" : error.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("failed", errors.size());
        result.put("errors", errors);
        result.put("strategy", outcome.strategy);
        return result;
    }

    private static Section toSection(Map<String, Object> item) {
        String title = stringOr(item.get("title"), null);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("AI 返回的知识点缺少 title");
        String content = stringOr(item.get("content"), "");
        if (content.isBlank()) throw new IllegalArgumentException("AI 返回的知识点内容为空：" + title);
        String category = stringOr(item.get("category"), null);
        List<String> tags = item.get("tags") instanceof List<?> list
                ? list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).toList()
                : List.of();
        return new Section(title.trim(), content.trim(), category, tags, List.of());
    }

    private static String stringOr(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private record ParseOutcome(List<Section> sections, String strategy) {}

    static List<Section> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        // 先扫一遍找出文档里实际出现的最深一级标题（1-6），用它作为知识点边界
        int headingLevel = 6;
        boolean found = false;
        for (String line : normalized.split("\n", -1)) {
            int depth = headingDepth(line);
            if (depth > 0 && depth < headingLevel) headingLevel = depth;
            if (depth > 0) found = true;
        }
        if (!found) throw new IllegalArgumentException("未找到任何 Markdown 标题，请检查格式");
        String prefix = "#".repeat(headingLevel);
        String headingPattern = "^" + prefix + "\\s+.+";
        String stripPattern = "^" + prefix + "\\s+";
        List<Section> result = new ArrayList<>();
        String title = null;
        String inheritedCategory = null;
        List<String> headingPath = new ArrayList<>();
        List<String> body = new ArrayList<>();
        List<String> preamble = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            int headingDepth = headingDepth(line);
            if (line.matches(headingPattern)) {
                if (title != null) result.add(section(title, body, inheritedCategory, headingPath));
                title = line.replaceFirst(stripPattern, "").trim();
                headingPath = new ArrayList<>();
                body = new ArrayList<>(preamble);
                preamble.clear();
            } else if (headingDepth > 0 && headingDepth < headingLevel) {
                inheritedCategory = line.replaceFirst("^#+\\s+", "").trim();
                if (title != null) body.add(line);
                else preamble.add(line);
            } else if (headingDepth >= headingLevel && title != null) {
                String deeperTitle = line.replaceFirst("^#+\\s+", "").trim();
                headingPath.add(deeperTitle);
                body.add(line);
            } else if (title != null) body.add(line);
            else preamble.add(line);
        }
        if (title != null) result.add(section(title, body, inheritedCategory, headingPath));
        if (result.isEmpty()) throw new IllegalArgumentException("未找到 " + headingLevel + " 级标题，请检查标题级别或改用其他级别");
        return result;
    }

    private static int headingDepth(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != '#') return 0;
        int depth = 0;
        while (depth < line.length() && line.charAt(depth) == '#') depth++;
        if (depth > 6 || depth >= line.length() || line.charAt(depth) != ' ') return 0;
        return depth;
    }

    private static Section section(String title, List<String> lines, String inheritedCategory, List<String> headingPath) {
        String category = null;
        List<String> tags = List.of();
        List<String> content = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("分类：")) category = trimmed.substring(3).trim();
            else if (trimmed.toLowerCase().startsWith("category:")) category = trimmed.substring("category:".length()).trim();
            else if (trimmed.startsWith("标签：")) tags = splitTags(trimmed.substring(3));
            else if (trimmed.toLowerCase().startsWith("tags:")) tags = splitTags(trimmed.substring("tags:".length()));
            else content.add(line);
        }
        if (category == null) category = inheritedCategory;
        String markdown = String.join("\n", content).trim();
        if (markdown.isBlank()) throw new IllegalArgumentException("知识点内容不能为空：" + title);
        return new Section(title, markdown, category, tags, List.copyOf(headingPath));
    }

    private static List<String> splitTags(String value) {
        return Arrays.stream(value.split("[,，]")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    record Section(String title, String content, String category, List<String> tags, List<String> headingPath) {}
}
