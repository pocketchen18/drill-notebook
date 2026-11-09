package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 明确读取笔记时保留嵌入资料；不改变现有笔记检索语料的归一化规则。 */
final class MaterialText {
    private final ObjectMapper json;
    MaterialText(ObjectMapper json) { this.json = json; }
    String note(String raw) {
        try {
            var doc = json.readTree(raw);
            if (doc == null || !"doc".equals(doc.path("type").asText())) throw new IllegalArgumentException();
            return render(doc, 0).strip();
        } catch (Exception error) { throw new IllegalStateException("笔记格式无法读取"); }
    }
    private String render(JsonNode node, int depth) {
        if (depth > 100) throw new IllegalStateException("笔记层级过深");
        var attrs = node.path("attrs");
        var children = new ArrayList<String>();
        for (var child : node.path("content")) children.add(render(child, depth + 1));
        String text = String.join("", children);
        return switch (node.path("type").asText()) {
            case "text" -> {
                String value = node.path("text").asText("");
                for (var mark : node.path("marks")) if ("link".equals(mark.path("type").asText()))
                    value = "[" + value + "](" + mark.path("attrs").path("href").asText("") + ")";
                yield value;
            }
            case "hardBreak" -> "\n";
            case "paragraph" -> text + "\n\n";
            case "heading" -> "#".repeat(Math.min(6, Math.max(1, attrs.path("level").asInt(2)))) + " " + text.strip() + "\n\n";
            case "bulletList", "taskList" -> String.join("", children.stream().map(v -> "- " + v.strip() + "\n").toList()) + "\n";
            case "orderedList" -> {
                var lines = new ArrayList<String>();
                for (int i=0; i<children.size(); i++) lines.add((i+1)+". "+children.get(i).strip());
                yield String.join("\n", lines)+"\n\n";
            }
            case "blockquote" -> "> " + text.strip().replace("\n", "\n> ") + "\n\n";
            case "codeBlock" -> "```" + attrs.path("language").asText("") + "\n" + text + "\n```\n\n";
            case "mathInline" -> "$" + attrs.path("latex").asText("") + "$";
            case "mathBlock" -> "$$\n" + attrs.path("latex").asText("") + "\n$$\n\n";
            case "mermaidBlock" -> "```mermaid\n" + attrs.path("code").asText("") + "\n```\n\n";
            case "markdownBlock" -> attrs.path("markdown").asText("") + "\n\n";
            case "fileBlock" -> "附件引用：" + attrs.path("fileName").asText("") + "（未读取文件正文）\n\n";
            case "videoBlock" -> "视频引用：" + attrs.path("title").asText("") + " " + attrs.path("url").asText("") + "\n\n";
            case "image" -> "图片：" + attrs.path("alt").asText(attrs.path("title").asText("未识别图片内容")) + "\n\n";
            case "questionBlock" -> "嵌入题目快照（questionId=" + attrs.path("questionId").asText("") + "）：\n"
                    + question(attrs.path("snapshot")) + "\n\n";
            case "tableRow" -> "| " + String.join(" | ", children.stream().map(v -> v.strip().replace("\n", " ").replace("|", "\\|")).toList()) + " |\n";
            case "table" -> text + "\n";
            default -> text;
        };
    }
    String question(Map<String, Object> row) { return question(json.<JsonNode>valueToTree(row)); }
    private String question(JsonNode row) {
        var lines = new ArrayList<String>();
        lines.add("题面：" + row.path("stem").asText(""));
        JsonNode options = row.path("options");
        if (options.isTextual()) {
            try { options = json.readTree(options.textValue()); }
            catch (Exception error) { throw new IllegalStateException("题目选项无法读取"); }
        }
        if (options != null && options.isArray()) for (var option : options)
            lines.add(option.path("key").asText("") + ". " + option.path("text").asText(""));
        for (var field : List.of("answer", "analysis")) if (!row.path(field).asText("").isBlank())
            lines.add(("answer".equals(field) ? "答案：" : "解析：") + row.path(field).asText());
        return String.join("\n\n", lines);
    }
}
