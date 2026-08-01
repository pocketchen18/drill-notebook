package com.drillnotebook.app.model;

import java.util.List;

/**
 * A deterministic chunk produced by NoteChunker from ordered text units.
 *
 * @param chunkIndex  0-based index within the page.
 * @param title       The notebook page title.
 * @param headingPath Ordered list of active heading texts at this chunk's position.
 * @param text        The concatenated chunk text (includes overlap prefix for i>0), ≤2800 UTF-16 code units.
 * @param startOffset UTF-16 offset of this chunk in the fully normalized page text (includes overlap start).
 * @param endOffset   UTF-16 offset of the end of this chunk's non-overlap content in the fully normalized page text.
 * @param contentHash SHA-256 hex of the complete normalized page text (identical for every chunk from the same page).
 */
public record Chunk(
        int chunkIndex,
        String title,
        List<String> headingPath,
        String text,
        int startOffset,
        int endOffset,
        String contentHash
) {
    public Chunk {
        if (title == null) title = "";
        if (headingPath == null) headingPath = List.of();
        if (text == null) text = "";
        if (contentHash == null) contentHash = "";
    }
}
