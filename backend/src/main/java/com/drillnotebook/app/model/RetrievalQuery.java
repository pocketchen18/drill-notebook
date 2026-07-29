package com.drillnotebook.app.model;

/** Immutable input for notebook retrieval. */
public record RetrievalQuery(
        String text,
        Scope scope,
        Long notebookId,
        Corpus corpus
) {
    public enum Scope {
        CURRENT,
        ALL
    }

    public enum Corpus {
        NOTEBOOK
    }
}
