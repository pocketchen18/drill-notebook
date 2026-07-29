package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Safe, deterministic lexical retrieval over notebook chunks. */
@Service
public class RetrievalService {
    static final int TOP_K = 40;
    static final int MAX_SHINGLES = 24;
    static final int MAX_SNIPPET_CODEPOINTS = 240;
    private static final int SNIPPET_CONTEXT_BEFORE = 80;
    private static final List<String> BM25_MATCH_TYPES = List.of("bm25");

    private final RetrievalIndexRepository retrievalIndex;
    private final NotebookRepository notebooks;

    public RetrievalService(
            RetrievalIndexRepository retrievalIndex,
            NotebookRepository notebooks) {
        this.retrievalIndex = retrievalIndex;
        this.notebooks = notebooks;
    }

    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        if (query == null) throw new IllegalArgumentException("retrieval query is required");
        if (query.scope() == null) throw new IllegalArgumentException("retrieval scope is required");
        if (query.corpus() == null) throw new IllegalArgumentException("retrieval corpus is required");

        Long corpusId = null;
        if (query.scope() == RetrievalQuery.Scope.CURRENT) {
            if (query.notebookId() == null || query.notebookId() <= 0
                    || !notebooks.exists(query.notebookId())) {
                throw new IllegalArgumentException("当前笔记本不存在");
            }
            corpusId = query.notebookId();
        }

        String normalized = normalizeQuery(query.text());
        if (normalized.isEmpty()) return List.of();

        int count = normalized.codePointCount(0, normalized.length());
        List<RetrievalIndexRepository.LexicalRow> rows;
        if (count >= 3) {
            rows = retrievalIndex.searchBm25(
                    query.corpus().name(), corpusId, buildMatchExpression(normalized), TOP_K);
        } else {
            rows = retrievalIndex.searchLikeFallback(
                    query.corpus().name(), corpusId, buildLikePattern(normalized), TOP_K);
        }

        List<RetrievalHit> hits = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            RetrievalIndexRepository.LexicalRow row = rows.get(i);
            Citation citation = new Citation(
                    row.corpusType(), row.corpusId(), row.sourceId(), row.chunkId(),
                    row.title(), row.headingPath(), buildSnippet(row.text(), normalized),
                    BM25_MATCH_TYPES, i + 1, null, null);
            hits.add(new RetrievalHit(citation, row.text(), row.sourceId(), row.chunkIndex()));
        }
        return List.copyOf(hits);
    }

    static String normalizeQuery(String raw) {
        if (raw == null) return "";
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(value.length());
        boolean separator = false;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            if (isSeparator(cp)) {
                separator = result.length() > 0;
            } else {
                if (separator) result.append(' ');
                result.appendCodePoint(cp);
                separator = false;
            }
            offset += Character.charCount(cp);
        }
        return result.toString();
    }

    private static boolean isSeparator(int cp) {
        if (Character.isWhitespace(cp)) return true;
        return switch (Character.getType(cp)) {
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    static String buildMatchExpression(String normalized) {
        int[] points = normalized.codePoints().toArray();
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i + 3 <= points.length && unique.size() < MAX_SHINGLES; i++) {
            unique.add(new String(points, i, 3));
        }
        List<String> quoted = unique.stream().map(RetrievalService::quoteFtsLiteral).toList();
        return String.join(" OR ", quoted);
    }

    static String quoteFtsLiteral(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String buildLikePattern(String normalized) {
        return "%" + escapeLike(normalized) + "%";
    }

    static String buildSnippet(String text, String normalizedQuery) {
        if (text == null || text.isBlank()) return "";
        String display = collapseDisplayWhitespace(Normalizer.normalize(text, Normalizer.Form.NFKC));
        int total = display.codePointCount(0, display.length());
        if (total <= MAX_SNIPPET_CODEPOINTS) return display;

        String search = display.toLowerCase(Locale.ROOT);
        String needle = normalizedQuery;
        int match = search.indexOf(needle);
        if (match < 0 && normalizedQuery.codePointCount(0, normalizedQuery.length()) >= 3) {
            int[] queryPoints = normalizedQuery.codePoints().limit(3).toArray();
            needle = new String(queryPoints, 0, queryPoints.length);
            match = search.indexOf(needle);
        }
        int matchCp = match < 0 ? 0 : search.codePointCount(0, match);
        int start = Math.max(0, matchCp - SNIPPET_CONTEXT_BEFORE);
        boolean leading = start > 0;
        int reserved = leading ? 1 : 0;
        int bodyBudget = MAX_SNIPPET_CODEPOINTS - reserved;
        int end = Math.min(total, start + bodyBudget);
        boolean trailing = end < total;
        if (trailing) {
            bodyBudget--;
            end = Math.min(total, start + bodyBudget);
        }
        int startOffset = display.offsetByCodePoints(0, start);
        int endOffset = display.offsetByCodePoints(0, end);
        return (leading ? "…" : "") + display.substring(startOffset, endOffset)
                + (trailing ? "…" : "");
    }

    private static String collapseDisplayWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean whitespace = false;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            if (Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR) {
                whitespace = result.length() > 0;
            } else {
                if (whitespace) result.append(' ');
                result.appendCodePoint(cp);
                whitespace = false;
            }
            offset += Character.charCount(cp);
        }
        return result.toString();
    }
}
