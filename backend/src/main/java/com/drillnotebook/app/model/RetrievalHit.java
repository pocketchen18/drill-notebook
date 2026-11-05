package com.drillnotebook.app.model;

/** A ranked retrieval result and its bounded public citation; {@code contentHash} is the chunk version. */
public record RetrievalHit(
        Citation citation,
        String text,
        long sourceId,
        int chunkIndex,
        String contentHash
) {
    public RetrievalHit {
        if (citation == null) throw new IllegalArgumentException("citation is required");
        if (text == null) text = "";
    }
}
