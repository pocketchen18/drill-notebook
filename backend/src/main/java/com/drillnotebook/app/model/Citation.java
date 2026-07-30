package com.drillnotebook.app.model;

import java.util.List;

/**
 * Deterministic citation returned by {@code RetrievalService}.
 *
 * <p>All fields are populated for every BM25 hit. The snippet is body-derived
 * and capped at 240 Unicode codepoints (including any ellipses), so a full
 * chunk body is never leaked to the caller.
 *
 * @param corpusType  Always {@code "NOTEBOOK"} for the notebook RAG feature.
 * @param notebookId  Owning notebook id.
 * @param pageId      Source page id ({@code note_page.id}).
 * @param chunkId     {@code retrieval_chunk.id}.
 * @param title       Page title at index time.
 * @param headingPath Joined heading path ({@code " / "} separator).
 * @param snippet     Deterministic, length-capped body excerpt (≤240
 *                    codepoints including ellipses).
 * @param matchTypes  Immutable list; {@code ["bm25"]}, {@code ["vector"]} or
 *                    {@code ["bm25","vector"]} depending on which rankings hit.
 * @param ftsRank     1-based lexical rank, or {@code null} for vector-only hits.
 * @param vectorRank   1-based vector rank, or {@code null} when the hit did not
 *                     appear in the vector top-K (or vector search was skipped).
 * @param rrfScore     RRF fusion score {@code 1/(60+ftsRank)+1/(60+vectorRank)};
 *                     {@code null} in BM25-only mode.
 */
public record Citation(
        String corpusType,
        long notebookId,
        long pageId,
        long chunkId,
        String title,
        String headingPath,
        String snippet,
        List<String> matchTypes,
        Integer ftsRank,
        Integer vectorRank,
        Double rrfScore
) {
    public Citation {
        if (corpusType == null || corpusType.isBlank()) {
            throw new IllegalArgumentException("corpusType is required");
        }
        if (title == null) title = "";
        if (headingPath == null) headingPath = "";
        if (snippet == null) snippet = "";
        if (matchTypes == null) matchTypes = List.of();
        matchTypes = List.copyOf(matchTypes);
    }
}
