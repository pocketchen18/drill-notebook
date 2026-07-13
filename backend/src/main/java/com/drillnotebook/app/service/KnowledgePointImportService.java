package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.KnowledgePointRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgePointImportService {
    private final KnowledgePointRepository points;

    public KnowledgePointImportService(KnowledgePointRepository points) { this.points = points; }

    public Map<String, Object> importMarkdown(Long bankId, String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Markdown 内容为空");
        List<Section> sections = parse(source);
        int imported = 0;
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            try {
                points.insert(bankId, section.title(), section.content(), section.category(), section.tags(), List.of());
                imported++;
            } catch (Exception error) {
                errors.add("第 " + (index + 1) + " 个知识点：" + (error.getMessage() == null ? "导入失败" : error.getMessage()));
            }
        }
        return Map.of("imported", imported, "failed", errors.size(), "errors", errors);
    }

    static List<Section> parse(String source) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        List<Section> result = new ArrayList<>();
        String title = null;
        List<String> body = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            if (line.matches("^#{1,2}\\s+.+")) {
                if (title != null) result.add(section(title, body));
                title = line.replaceFirst("^#{1,2}\\s+", "").trim();
                body = new ArrayList<>();
            } else if (title != null) body.add(line);
        }
        if (title != null) result.add(section(title, body));
        if (result.isEmpty()) throw new IllegalArgumentException("请使用 # 或 ## 标题分隔知识点");
        return result;
    }

    private static Section section(String title, List<String> lines) {
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
        String markdown = String.join("\n", content).trim();
        if (markdown.isBlank()) throw new IllegalArgumentException("知识点内容不能为空：" + title);
        return new Section(title, markdown, category, tags);
    }

    private static List<String> splitTags(String value) {
        return Arrays.stream(value.split("[,，]")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    record Section(String title, String content, String category, List<String> tags) {}
}
