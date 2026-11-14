package com.drillnotebook.app.service;

import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.MaterialReadRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Page-level, bounded search for the on-demand material tool. */
@Service
public class MaterialSearchService {
    static final int MAX_MATERIALS = 80;

    private final MaterialReadRepository materials;
    private final HybridRetrievalService retrieval;

    public MaterialSearchService(MaterialReadRepository materials, HybridRetrievalService retrieval) {
        this.materials = materials;
        this.retrieval = retrieval;
    }

    public record Page(List<Map<String, Object>> rows, boolean hasMore, boolean limited,
                       Map<String, Object> retrievalNotice) {}

    public Page search(String type, String query, Long notebookId, int offset, int limit) {
        var exact = materials.list(type, query, notebookId, null, null, 0, MAX_MATERIALS + 1);
        var hybrid = retrieval.retrieveCandidates(new RetrievalQuery(query,
                notebookId == null ? RetrievalQuery.Scope.ALL : RetrievalQuery.Scope.CURRENT,
                notebookId, RetrievalQuery.Corpus.NOTEBOOK));
        var targets = new LinkedHashMap<Long, Map<String, Object>>();
        for (var row : materials.noteSearchTargets(hybrid.hits().stream().map(hit -> hit.citation().chunkId()).toList(), notebookId))
            targets.put(((Number) row.get("chunkId")).longValue(), row);

        var candidates = new LinkedHashMap<String, Map<String, Object>>();
        for (var raw : exact.stream().limit(MAX_MATERIALS).toList()) {
            var row = new LinkedHashMap<>(raw);
            row.put("snippet", MaterialReadService.searchSnippet(Objects.toString(row.get("snippet"), ""), query));
            if ("note".equals(row.get("type"))) {
                addNoteIds(row);
                row.put("matchTypes", List.of("keyword"));
            }
            candidates.put(key(row), row);
        }

        boolean limited = exact.size() > MAX_MATERIALS || hybrid.candidateLimitReached();
        // RRF ranks chunks; use the best surviving chunk for each page, preserving literal matches first.
        for (var hit : hybrid.hits()) {
            var citation = hit.citation();
            var target = targets.get(citation.chunkId());
            if (target == null || !Objects.equals(hit.contentHash(), target.get("chunkHash"))) continue;
            String key = "note/" + target.get("id");
            var existing = candidates.get(key);
            if (existing != null) {
                @SuppressWarnings("unchecked")
                var matches = new LinkedHashSet<>((List<String>) existing.get("matchTypes"));
                matches.addAll(citation.matchTypes());
                existing.put("matchTypes", List.copyOf(matches));
                continue;
            }
            if (candidates.size() >= MAX_MATERIALS) { limited = true; continue; }
            var row = new LinkedHashMap<>(target);
            row.remove("chunkHash");
            row.put("type", "note");
            addNoteIds(row);
            row.put("snippet", MaterialReadService.clip(citation.snippet(), RetrievalService.MAX_SNIPPET_CODEPOINTS));
            row.put("headingPath", MaterialReadService.clip(citation.headingPath(), 200));
            row.put("matchTypes", citation.matchTypes());
            candidates.put(key, row);
        }

        var rows = new ArrayList<Map<String, Object>>(candidates.values().stream().skip(offset).limit(limit).toList());
        // Identical excerpts retain every source locator, but the text appears only once per call.
        var snippets = new LinkedHashMap<String, String>();
        for (var row : rows) {
            if (!"note".equals(row.get("type"))) continue;
            String snippet = Objects.toString(row.get("snippet"), "");
            if (snippet.isBlank()) continue;
            String source = MaterialReadService.link("note", ((Number) row.get("id")).longValue());
            String previous = snippets.putIfAbsent(snippet, source);
            if (previous != null) { row.put("snippet", ""); row.put("snippetDuplicateOf", previous); }
        }
        return new Page(List.copyOf(rows), offset + rows.size() < candidates.size(), limited, hybrid.notice());
    }

    private static String key(Map<String, Object> row) { return row.get("type") + "/" + row.get("id"); }

    private static void addNoteIds(Map<String, Object> row) {
        row.put("notebookId", row.get("parentId"));
        row.put("pageId", row.get("id"));
    }
}
