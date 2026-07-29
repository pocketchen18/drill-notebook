package com.drillnotebook.app.model;

/** A ranked retrieval result and its bounded public citation. */
public record RetrievalHit(
        Citation citation,
        String text,
        long sourceId,
        int chunkIndex
) {
    public RetrievalHit {
        if (citation == null) throw new IllegalArgumentException("citation is required");
        if (text == null) text = "";
    }
}
