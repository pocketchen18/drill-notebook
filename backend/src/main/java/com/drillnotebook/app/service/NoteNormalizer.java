package com.drillnotebook.app.service;

import com.drillnotebook.app.model.NormalizedUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function normalizer that converts TipTap editor JSON into ordered
 * {@link NormalizedUnit} text units.
 *
 * <p>This is the Java-side equivalent of the frontend's {@code nodeMarkdown()}
 * in {@code aiContext.ts}. It handles all current TipTap node types, extracts
 * plain text with structural markers, and tracks heading levels for
 * downstream chunking.</p>
 *
 * <p>Node types handled: text, hardBreak, paragraph, heading, bulletList,
 * orderedList, codeBlock, blockquote, mathInline, mathBlock, mermaidBlock,
 * markdownBlock, videoBlock, fileBlock. The questionBlock node type is
 * <em>skipped</em> — it is not part of the v0.5 NOTEBOOK corpus.</p>
 */
public class NoteNormalizer {

    private final ObjectMapper mapper;

    public NoteNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Normalize a TipTap JSON string into an ordered list of {@link NormalizedUnit}s.
     * Blank-text units (e.g. from empty paragraphs or image-only content) are filtered out.
     *
     * @param tipTapJson the TipTap JSON (a {@code doc} node with a {@code content} array)
     * @return ordered text units, one per block-level node (blank units removed)
     * @throws IllegalArgumentException if the JSON is malformed or cannot be parsed
     */
    public List<NormalizedUnit> normalize(String tipTapJson) {
        if (tipTapJson == null || tipTapJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(tipTapJson);
            if (root == null || root.isNull()) return List.of();
            // Validate that the root is a TipTap document with type "doc" and a content array
            if (!root.isObject() || !root.has("type")
                    || !"doc".equals(root.get("type").asText())) {
                throw new IllegalArgumentException(
                        "NORMALIZATION_ERROR: root must be a TipTap doc node");
            }
            if (!root.has("content") || !root.get("content").isArray()) {
                // Empty doc with no content array → zero units, no error
                return List.of();
            }
            List<NormalizedUnit> all = normalizeBlockChildren(root);
            // Filter out blank-text units (empty paragraphs, image-only nodes, etc.)
            return all.stream()
                    .filter(u -> u.text() != null && !u.text().isBlank())
                    .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException("NORMALIZATION_ERROR: " + e.getMessage(), e);
        }
    }

    // ── Block-level node dispatch ──────────────────────────────────────────

    private List<NormalizedUnit> normalizeBlockChildren(JsonNode node) {
        List<NormalizedUnit> result = new ArrayList<>();
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode child : content) {
                result.addAll(normalizeBlockNode(child));
            }
        }
        return result;
    }

    private List<NormalizedUnit> normalizeBlockNode(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        String type = node.path("type").asText("");
        return switch (type) {
            case "heading" -> List.of(normalizeHeading(node));
            case "paragraph" -> List.of(normalizeParagraph(node));
            case "bulletList" -> List.of(normalizeBulletList(node));
            case "orderedList" -> List.of(normalizeOrderedList(node));
            case "codeBlock" -> List.of(normalizeCodeBlock(node));
            case "blockquote" -> normalizeBlockquote(node);
            case "mathBlock" -> List.of(normalizeMathBlock(node));
            case "mermaidBlock" -> List.of(normalizeMermaidBlock(node));
            case "markdownBlock" -> List.of(normalizeMarkdownBlock(node));
            case "videoBlock" -> List.of(normalizeVideoBlock(node));
            case "fileBlock" -> List.of(normalizeFileBlock(node));
            case "questionBlock" -> List.of(); // excluded from v0.5 corpus
            default -> normalizeBlockChildren(node); // unknown container → recurse
        };
    }

    // ── Inline rendering (plain text from inline nodes) ────────────────────

    private String renderInline(JsonNode node) {
        if (node == null || node.isNull()) return "";
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> node.path("text").asText("");
            case "hardBreak" -> "\n";
            case "mathInline" -> "$" + node.path("attrs").path("latex").asText("") + "$";
            default -> {
                // Recurse into children for unknown inline containers
                StringBuilder sb = new StringBuilder();
                JsonNode content = node.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode child : content) {
                        sb.append(renderInline(child));
                    }
                }
                yield sb.toString();
            }
        };
    }

    private String renderInlineChildren(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode child : content) {
                sb.append(renderInline(child));
            }
        }
        return sb.toString();
    }

    // ── Per-node-type rendering ────────────────────────────────────────────

    private NormalizedUnit normalizeHeading(JsonNode node) {
        int level = Math.min(6, Math.max(1, node.path("attrs").path("level").asInt(2)));
        String text = renderInlineChildren(node);
        return new NormalizedUnit("#".repeat(level) + " " + text, level, text);
    }

    private NormalizedUnit normalizeParagraph(JsonNode node) {
        String text = renderInlineChildren(node);
        return new NormalizedUnit(text, 0, "");
    }

    private NormalizedUnit normalizeBulletList(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (int i = 0; i < content.size(); i++) {
                JsonNode item = content.get(i);
                if (i > 0) sb.append("\n");
                sb.append("- ").append(renderInlineChildren(item));
            }
        }
        return new NormalizedUnit(sb.toString(), 0, "");
    }

    private NormalizedUnit normalizeOrderedList(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (int i = 0; i < content.size(); i++) {
                JsonNode item = content.get(i);
                if (i > 0) sb.append("\n");
                sb.append(i + 1).append(". ").append(renderInlineChildren(item));
            }
        }
        return new NormalizedUnit(sb.toString(), 0, "");
    }

    private NormalizedUnit normalizeCodeBlock(JsonNode node) {
        String language = node.path("attrs").path("language").asText("");
        String code = renderInlineChildren(node);
        String fence = "```" + language + "\n" + code + "\n```";
        return new NormalizedUnit(fence, 0, "");
    }

    private List<NormalizedUnit> normalizeBlockquote(JsonNode node) {
        // Blockquote children are block-level nodes rendered with "> " prefix
        List<NormalizedUnit> children = normalizeBlockChildren(node);
        if (children.isEmpty()) return List.of();
        List<NormalizedUnit> result = new ArrayList<>();
        for (NormalizedUnit child : children) {
            String quoted = "> " + child.text().replace("\n", "\n> ");
            // Preserve heading tracking from children
            result.add(new NormalizedUnit(quoted, child.headingLevel(), child.headingText()));
        }
        return result;
    }

    private NormalizedUnit normalizeMathBlock(JsonNode node) {
        String latex = node.path("attrs").path("latex").asText("");
        return new NormalizedUnit("$$\n" + latex + "\n$$", 0, "");
    }

    private NormalizedUnit normalizeMermaidBlock(JsonNode node) {
        String code = node.path("attrs").path("code").asText("");
        return new NormalizedUnit("```mermaid\n" + code + "\n```", 0, "");
    }

    private NormalizedUnit normalizeMarkdownBlock(JsonNode node) {
        String markdown = node.path("attrs").path("markdown").asText("");
        return new NormalizedUnit(markdown, 0, "");
    }

    /**
     * Video embeds are atom nodes: their title/URL only live in attrs, so
     * without this they would contribute nothing to the index and a page whose
     * content is a single video would be invisible to search.
     */
    private NormalizedUnit normalizeVideoBlock(JsonNode node) {
        JsonNode attrs = node.path("attrs");
        String text = joinNonBlank(
                attrs.path("title").asText(""), attrs.path("url").asText(""));
        return new NormalizedUnit(text.isBlank() ? "" : "视频：" + text, 0, "");
    }

    /** File attachments are atom nodes too; the file name is the searchable part. */
    private NormalizedUnit normalizeFileBlock(JsonNode node) {
        String fileName = node.path("attrs").path("fileName").asText("");
        return new NormalizedUnit(fileName.isBlank() ? "" : "附件：" + fileName, 0, "");
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(part.trim());
        }
        return sb.toString();
    }
}
