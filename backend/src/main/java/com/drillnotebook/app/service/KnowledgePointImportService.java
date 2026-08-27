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
     * 规则解析按"每个标题一张增量卡片"拆分：每个 Markdown 标题（1-6 级）生成一张 Section，
     * content 为该标题下、到下一个任意标题前的直接内容（可为空串），
     * headingPath 为不含自身的祖先标题链，level 为该标题的标题级别。
     * AI 兜底把原文喂给模型，模型返回 [{title,content,category,tags,level}] JSON，再统一入库。
     * AI 不可用时透传错误。
     */
    public Map<String, Object> importMarkdown(Long bankId, String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        ParseOutcome outcome;
        try {
            List<Section> sections = parse(source);
            outcome = new ParseOutcome(sections, "rules");
        } catch (IllegalArgumentException ruleError) {
            List<Section> aiSections = rebuildPaths(aiService.parseKnowledgePointsFromText(source).stream()
                    .map(KnowledgePointImportService::toSection)
                    .toList());
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
        int level = 1;
        Object levelValue = item.get("level");
        if (levelValue instanceof Number number) level = Math.max(1, Math.min(6, number.intValue()));
        return new Section(title.trim(), content.trim(), category, tags, List.of(), level);
    }

    private static String stringOr(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    /**
     * 按 level 重建 AI 兜底结果的 headingPath（不含自身的祖先标题链）。
     * 输入是带 level 的扁平 Section 列表；用栈模拟文档标题层级，
     * 遇到同级或更浅标题时弹出栈顶，新节点的 headingPath = 弹出后的栈内容。
     */
    static List<Section> rebuildPaths(List<Section> sections) {
        List<String> stackTitles = new ArrayList<>();
        List<Integer> stackLevels = new ArrayList<>();
        List<Section> result = new ArrayList<>();
        for (Section section : sections) {
            int level = Math.max(1, Math.min(6, section.level()));
            while (!stackLevels.isEmpty() && stackLevels.get(stackLevels.size() - 1) >= level) {
                stackLevels.remove(stackLevels.size() - 1);
                stackTitles.remove(stackTitles.size() - 1);
            }
            result.add(new Section(section.title(), section.content(), section.category(), section.tags(), new ArrayList<>(stackTitles), level));
            stackLevels.add(level);
            stackTitles.add(section.title());
        }
        return result;
    }

    private record ParseOutcome(List<Section> sections, String strategy) {}

    static List<Section> parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        List<Section> result = new ArrayList<>();
        List<String> stackTitles = new ArrayList<>();
        List<Integer> stackLevels = new ArrayList<>();
        List<String> body = new ArrayList<>();
        List<String> preamble = new ArrayList<>();
        String title = null;
        String sectionCategory = null;
        int sectionLevel = 0;
        for (String line : normalized.split("\n", -1)) {
            int depth = headingDepth(line);
            if (depth > 0) {
                // 关闭上一个标题：headingPath = 栈中除自己外的祖先链
                if (title != null) {
                    List<String> path = stackTitles.size() > 1
                            ? stackTitles.subList(0, stackTitles.size() - 1)
                            : List.of();
                    result.add(section(title, body, sectionCategory, new ArrayList<>(path), sectionLevel));
                }
                // 弹出 >= 当前深度的祖先（结束它们的章节）
                while (!stackLevels.isEmpty() && stackLevels.get(stackLevels.size() - 1) >= depth) {
                    stackLevels.remove(stackLevels.size() - 1);
                    stackTitles.remove(stackTitles.size() - 1);
                }
                // 新章节的继承分类 = 最近的祖先标题
                sectionCategory = stackTitles.isEmpty() ? null : stackTitles.get(stackTitles.size() - 1);
                sectionLevel = depth;
                title = line.replaceFirst("^#+\\s+", "").trim();
                body = new ArrayList<>();
                // 文档前言归入第一个标题
                if (result.isEmpty() && !preamble.isEmpty()) {
                    body.addAll(preamble);
                    preamble.clear();
                }
                stackLevels.add(depth);
                stackTitles.add(title);
            } else if (title != null) {
                body.add(line);
            } else {
                preamble.add(line);
            }
        }
        if (title != null) {
            List<String> path = stackTitles.size() > 1
                    ? stackTitles.subList(0, stackTitles.size() - 1)
                    : List.of();
            result.add(section(title, body, sectionCategory, new ArrayList<>(path), sectionLevel));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("未找到任何 Markdown 标题，请检查格式");
        boolean anyContent = result.stream().anyMatch(section -> !section.content().isBlank());
        if (!anyContent) throw new IllegalArgumentException("知识点内容不能为空：未找到任何正文");
        return result;
    }

    private static int headingDepth(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != '#') return 0;
        int depth = 0;
        while (depth < line.length() && line.charAt(depth) == '#') depth++;
        if (depth > 6 || depth >= line.length() || line.charAt(depth) != ' ') return 0;
        return depth;
    }

    private static Section section(String title, List<String> lines, String inheritedCategory, List<String> headingPath, int level) {
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
        return new Section(title, markdown, category, tags, List.copyOf(headingPath), level);
    }

    private static List<String> splitTags(String value) {
        return Arrays.stream(value.split("[,，]")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    record Section(String title, String content, String category, List<String> tags, List<String> headingPath, int level) {}
}
