package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Edit text in place, retaining every unrelated editor node, mark and attachment. */
final class MaterialNoteEdits {
    private record Leaf(ObjectNode owner, String field) { }
    static JsonNode apply(String raw, Map<String,Object> changes, ObjectMapper json) {
        try {
            JsonNode doc = json.readTree(raw);
            if (doc == null || !doc.isObject() || !doc.path("type").asText().equals("doc") || !doc.path("content").isArray()) throw new IllegalArgumentException();
            if (changes.get("edits") instanceof List<?> edits) for (Object entry : edits) {
                Map<?,?> edit = (Map<?,?>) entry;
                String old = (String) edit.get("oldText"), replacement = (String) edit.get("newText");
                var leaves = new ArrayList<Leaf>(); collect(doc, leaves, 0);
                Leaf selected = null; int start = -1; int matches = 0;
                for (Leaf leaf : leaves) {
                    String value = leaf.owner().path(leaf.field()).asText();
                    for (int pos = value.indexOf(old); pos >= 0; pos = value.indexOf(old, pos + 1)) {
                        selected = leaf; start = pos; if (++matches > 1) throw new Conflict();
                    }
                }
                if (matches != 1) throw new Conflict();
                String value = selected.owner().path(selected.field()).asText();
                selected.owner().put(selected.field(), value.substring(0, start) + replacement + value.substring(start + old.length()));
            }
            if (changes.get("append") instanceof String append) {
                var children = ((ObjectNode) doc).withArray("content");
                children.add(json.valueToTree(Map.of("type", "markdownBlock", "attrs", Map.of("markdown", append))));
                children.add(json.valueToTree(Map.of("type", "paragraph")));
            }
            pruneEmptyText(doc);
            return doc;
        } catch (Conflict error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("笔记内容无法安全编辑", error); }
    }
    private static void pruneEmptyText(JsonNode node) {
        if (!node.path("content").isArray()) return;
        var children = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("content");
        for (int i = children.size() - 1; i >= 0; i--) {
            var child = children.get(i);
            if (child.path("type").asText().equals("text") && child.path("text").asText().isEmpty()) children.remove(i);
            else pruneEmptyText(child);
        }
    }
    private static void collect(JsonNode node, List<Leaf> leaves, int depth) {
        if (depth > 100 || !node.isObject()) throw new IllegalArgumentException();
        String type = node.path("type").asText();
        if (type.equals("text") && node.path("text").isTextual()) leaves.add(new Leaf((ObjectNode)node, "text"));
        String field = switch(type) { case "markdownBlock" -> "markdown"; case "mathInline", "mathBlock" -> "latex"; case "mermaidBlock" -> "code"; default -> null; };
        if (field != null && node.path("attrs").isObject() && node.path("attrs").path(field).isTextual())
            leaves.add(new Leaf((ObjectNode)node.path("attrs"), field));
        // questionBlock snapshots, files and image metadata are not ordinary editable text.
        for (JsonNode child : node.path("content")) collect(child, leaves, depth + 1);
    }
    static final class Conflict extends RuntimeException { }
}
