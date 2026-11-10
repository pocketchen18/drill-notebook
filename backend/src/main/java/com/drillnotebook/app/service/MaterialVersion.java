package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Hash the complete editable snapshot, not its truncated rendering or answer statistics. */
final class MaterialVersion {
    private static final ObjectMapper JSON = new ObjectMapper();
    static String of(String type, Map<String, Object> row) {
        String canonicalType = "wrong".equals(type) ? "question" : type;
        List<String> fields = switch (canonicalType) {
            case "notebook" -> List.of("id", "title");
            case "bank" -> List.of("id", "title", "description");
            case "note" -> List.of("id", "notebookId", "parentId", "title", "content");
            case "knowledge" -> List.of("id", "bankId", "title", "content", "category", "tags", "headingPath", "questionIds");
            case "question" -> List.of("id", "bankId", "questionType", "stem", "options", "answer", "analysis", "difficulty", "tags", "chapter");
            default -> throw new IllegalArgumentException();
        };
        var values = new LinkedHashMap<String, Object>();
        values.put("type", canonicalType);
        for (String key : fields) values.put(key, row.get(key));
        try { return PageSnapshot.hash(JSON.writeValueAsString(values)); }
        catch (Exception error) { throw new IllegalStateException("资料版本无法读取", error); }
    }
}
