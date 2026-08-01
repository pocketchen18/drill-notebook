package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Chunk;
import com.drillnotebook.app.model.NormalizedUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NoteChunker} — deterministic 2600/300/200 chunking.
 */
class NoteChunkerTest {

    private final NoteChunker chunker = new NoteChunker();

    // ── Empty / edge ───────────────────────────────────────────────────────

    @Test
    void emptyInput() {
        assertTrue(chunker.chunk(null, "title").isEmpty());
        assertTrue(chunker.chunk(List.of(), "title").isEmpty());
    }

    @Test
    void nullTitle() {
        var units = List.of(unit("hello"));
        List<Chunk> chunks = chunker.chunk(units, null);
        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0).title());
    }

    // ── Single unit ────────────────────────────────────────────────────────

    @Test
    void singleUnitProducesOneChunk() {
        var units = List.of(unit("Hello world"));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals("Hello world", chunks.get(0).text());
        assertEquals(0, chunks.get(0).startOffset());
        assertEquals("Hello world".length(), chunks.get(0).endOffset());
        assertFalse(chunks.get(0).contentHash().isBlank());
    }

    // ── Multiple units within BASE_MAX ─────────────────────────────────────

    @Test
    void multipleUnitsWithinBaseMax() {
        var units = List.of(unit("A short paragraph."), unit("Another one."));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(1, chunks.size());
        String expected = "A short paragraph.\n\nAnother one.";
        assertEquals(expected, chunks.get(0).text());
    }

    // ── Segment splitting ──────────────────────────────────────────────────

    @Test
    void unitsSplitAcrossSegments() {
        // Create units that exceed BASE_MAX together but individually fit
        String a = "x".repeat(1800);
        String b = "y".repeat(1800); // combined a+"\n\n"+b > 2600
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().startsWith("x"));
        assertTrue(chunks.get(1).text().contains("y"));
    }

    // ── Tail merge ─────────────────────────────────────────────────────────

    @Test
    void smallTailMerged() {
        String a = "x".repeat(2000);
        String b = "y".repeat(200); // < TAIL_MIN=300 and fits with prev
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        // b merged into a's segment
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().contains("y"));
    }

    @Test
    void smallTailNotMergedWhenExceedsLimit() {
        String a = "x".repeat(2500); // cannot fit another 299 + separator
        String b = "y".repeat(299);  // <300, but merging would exceed BASE_MAX
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(2, chunks.size());
        // Second chunk has overlap from first
        assertTrue(chunks.get(1).text().endsWith(b));
    }

    @Test
    void tailNotMergedWhenNotSmall() {
        String a = "x".repeat(2500);
        String b = "y".repeat(300); // >= TAIL_MIN and combined exceeds BASE_MAX
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(2, chunks.size());
    }

    // ── Overlap ────────────────────────────────────────────────────────────

    @Test
    void overlapAddedToSecondChunk() {
        String a = "x".repeat(1500);
        String b = "y".repeat(1500);
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(2, chunks.size());
        // Second chunk should have overlap (last 200 chars of first segment)
        Chunk c1 = chunks.get(1);
        assertTrue(c1.text().startsWith("x".repeat(200)), "should include 200-char overlap from previous base");
        assertTrue(c1.text().contains("y"), "should include current segment text");
    }

    @Test
    void overlapCappedByPreviousLength() {
        // First unit short (100), second unit long enough to force split
        String a = "x".repeat(100);
        String b = "y".repeat(2600); // 100 + 2 + 2600 > 2600 → forces split
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(2, chunks.size());
        // overlap = min(200, 100) = 100, entire first base prepended
        Chunk c1 = chunks.get(1);
        assertTrue(c1.text().startsWith("x".repeat(100)), "overlap should be entire short previous base");
    }

    // ── Heading path tracking ──────────────────────────────────────────────

    // ── Realistic probes ────────────────────────────────────────────────────

    @Test
    void integrationProbe_fullPipelineMixedDoc() {
        // Realistic TipTap doc with headings, lists, code, math, and paragraphs
        String json = """
                {"type":"doc","content":[
                  {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Drill Notebook Guide"}]},
                  {"type":"paragraph","content":[{"type":"text","text":"This document covers the core features."}]},
                  {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Installation"}]},
                  {"type":"codeBlock","attrs":{"language":"bash"},"content":[{"type":"text","text":"npm install\\nnpm run build"}]},
                  {"type":"paragraph","content":[{"type":"text","text":"Follow these steps to get started."}]},
                  {"type":"bulletList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Clone repo"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Install deps"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Run dev server"}]}]}
                  ]},
                  {"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"Configuration"}]},
                  {"type":"paragraph","content":[{"type":"text","text":"Edit the config file at "},{"type":"text","marks":[{"type":"code"}],"text":"config.json"},{"type":"text","text":"."}]}
                ]}
                """;
        var normalizer = new NoteNormalizer(new ObjectMapper());
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertTrue(units.size() >= 5, "should extract 5+ units, got " + units.size());

        List<Chunk> chunks = chunker.chunk(units, "Guide");
        assertFalse(chunks.isEmpty(), "should produce at least one chunk");

        // All chunks must have identical page-level contentHash
        String firstHash = chunks.get(0).contentHash();
        assertTrue(firstHash.matches("[0-9a-f]{64}"), "valid SHA-256 hex");

        // Verify each chunk has required fields
        for (Chunk c : chunks) {
            assertEquals("Guide", c.title());
            assertNotNull(c.headingPath());
            assertNotNull(c.text());
            assertFalse(c.text().isBlank());
            // All chunks from same page have identical contentHash
            assertEquals(firstHash, c.contentHash(), "all chunks must share page-level hash");
            // Each chunk ≤ 2800 chars (2600 + 200 overlap)
            assertTrue(c.text().length() <= 2800,
                    "chunk %d too long: %d".formatted(c.chunkIndex(), c.text().length()));
            assertTrue(c.text().length() > 0, "chunk text empty");
        }

        // Heading path for first chunk should include the h1 "Drill Notebook Guide"
        assertTrue(chunks.get(0).headingPath().contains("Drill Notebook Guide"),
                "first chunk must include top-level heading");
    }

    @Test
    void headingPathInChunk() {
        var units = List.of(
                new NormalizedUnit("# Title", 1, "Title"),
                new NormalizedUnit("Para", 0, ""),
                new NormalizedUnit("## Sec1", 2, "Sec1"),
                new NormalizedUnit("Body", 0, "")
        );
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(1, chunks.size()); // all fits in one segment
        List<String> hp = chunks.get(0).headingPath();
        // heading path at chunk start (position 0) only has "Title" — "Sec1" appears later
        assertEquals(List.of("Title"), hp);
    }

    @Test
    void headingPathUpdatesOnNewSection() {
        // Build enough units so chunks split
        String longBody = "x".repeat(1500);
        var units = List.of(
                new NormalizedUnit("# H1", 1, "H1"),
                new NormalizedUnit(longBody, 0, ""),
                new NormalizedUnit("## H2", 2, "H2"),
                new NormalizedUnit(longBody, 0, "")
        );
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertTrue(chunks.size() >= 2);
        // First chunk heading path should have H1
        assertTrue(chunks.get(0).headingPath().contains("H1"));
        // Last chunk heading path should have H1, H2 (if it contains the H2 area)
        List<String> lastHp = chunks.get(chunks.size() - 1).headingPath();
        assertTrue(lastHp.contains("H1"), "H1 should still be in path");
    }

    // ── Deterministic output ───────────────────────────────────────────────

    @Test
    void deterministicRoundTrip() {
        var units = List.of(
                new NormalizedUnit("# Title", 1, "Title"),
                new NormalizedUnit("First paragraph.", 0, ""),
                new NormalizedUnit("## Section A", 2, "Section A"),
                new NormalizedUnit("Content of section A.", 0, ""),
                new NormalizedUnit("### Subsection", 3, "Subsection"),
                new NormalizedUnit("More detailed content here.", 0, ""),
                new NormalizedUnit("## Section B", 2, "Section B"),
                new NormalizedUnit("Conclusion.", 0, "")
        );
        List<Chunk> first = chunker.chunk(units, "TestPage");
        List<Chunk> second = chunker.chunk(units, "TestPage");
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            Chunk a = first.get(i);
            Chunk b = second.get(i);
            assertEquals(a.text(), b.text());
            assertEquals(a.startOffset(), b.startOffset());
            assertEquals(a.endOffset(), b.endOffset());
            assertEquals(a.contentHash(), b.contentHash());
            assertEquals(a.headingPath(), b.headingPath());
        }
    }

    // ── Chunk size constraint ──────────────────────────────────────────────

    @Test
    void allChunksUnderMaxSize() {
        // 10 units of 500 chars each = 5000 + separators, will make multiple chunks
        var units = new java.util.ArrayList<NormalizedUnit>();
        for (int i = 0; i < 10; i++) {
            units.add(unit("x".repeat(500)));
        }
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertTrue(chunks.size() >= 2);
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 2800,
                    "Chunk " + c.chunkIndex() + " length " + c.text().length() + " exceeds 2800");
        }
    }

    // ── Hard-window overlong unit ──────────────────────────────────────────

    @Test
    void singleOverlongUnitWindowed() {
        String veryLong = "x".repeat(5000);
        var units = List.of(unit(veryLong));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        // 5000 / 2600 = ~2 segments, but first has no overlap, second has overlap
        assertTrue(chunks.size() >= 2);
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 2800,
                    "Windowed chunk " + c.chunkIndex() + " length " + c.text().length() + " exceeds 2800");
        }
    }

    // ── Content hash: page-level identity ───────────────────────────────────

    @Test
    void contentHashIsPageLevelIdenticalAcrossChunks() {
        // Multiple chunks from the same page must share the same contentHash
        // (SHA-256 of the complete normalized page text).
        String a = "x".repeat(1800);
        String b = "y".repeat(1800);
        var units = List.of(unit(a), unit(b));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertTrue(chunks.size() >= 2, "should produce at least 2 chunks");

        String fullText = "x".repeat(1800) + "\n\n" + "y".repeat(1800);
        String expectedHash = NoteChunker.sha256(fullText);
        for (Chunk c : chunks) {
            assertEquals(expectedHash, c.contentHash(),
                    "all chunks from same page must have identical contentHash");
        }
    }

    @Test
    void contentHashIsSha256OfFullTextNotPerChunkText() {
        // Single unit: contentHash = sha256(fullText) = sha256(unit text)
        var units = List.of(unit("Hello"));
        List<Chunk> chunks = chunker.chunk(units, "Page");
        String expectedHash = NoteChunker.sha256("Hello");
        assertEquals(expectedHash, chunks.get(0).contentHash());
    }

    // ── Blank / image-only pages ────────────────────────────────────────────

    @Test
    void blankUnitsProduceNoChunks() {
        // Units with blank text are filtered out by the normalizer,
        // so the chunker receives an empty list.
        var units = List.of(
                new NormalizedUnit("", 0, ""),
                new NormalizedUnit("", 0, "")
        );
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertTrue(chunks.isEmpty(), "blank units should produce zero chunks");
    }

    @Test
    void allBlankUnitsProduceNoChunks() {
        // Mixed blank and non-blank — only non-blank survive.
        var units = List.of(
                new NormalizedUnit("", 0, ""),
                new NormalizedUnit("Some text", 0, ""),
                new NormalizedUnit("", 0, "")
        );
        List<Chunk> chunks = chunker.chunk(units, "Page");
        assertEquals(1, chunks.size(), "only non-blank units produce chunks");
    }

    // ── Heading path: at base start, not overlap prefix ─────────────────────

    @Test
    void headingPathUsesBaseStartNotOverlapStart() {
        // Build a doc where a heading appears in the overlap region of chunk 1 but
        // not at the base start of chunk 1. The heading path for chunk 1 should
        // reflect what is at the base segment's own start, not the overlap prefix.
        String longBody = "x".repeat(1500);
        var units = List.of(
                new NormalizedUnit("# Main", 1, "Main"),
                new NormalizedUnit(longBody, 0, ""),
                new NormalizedUnit("## Sec2", 2, "Sec2"),   // this heading is after the split boundary
                new NormalizedUnit(longBody, 0, "")
        );
        List<Chunk> chunks = chunker.chunk(units, "Page");
        // We expect at least 2 chunks: first has "Main", second has "Main" + "Sec2"
        assertTrue(chunks.size() >= 2, "should produce at least 2 chunks, got " + chunks.size());

        // First chunk covers up to just before "## Sec2" → heading path = ["Main"]
        List<String> hp0 = chunks.get(0).headingPath();
        assertEquals(List.of("Main"), hp0, "first chunk should have only Main in path");

        // Second chunk starts at "longBody" (after "## Sec2").
        // h2 "Sec2" appears at the base start of chunk 1, so path = ["Main", "Sec2"].
        List<String> hp1 = chunks.get(1).headingPath();
        assertTrue(hp1.contains("Main"), "second chunk should inherit Main heading");
        assertTrue(hp1.contains("Sec2"), "second chunk should have Sec2 in path");
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private static NormalizedUnit unit(String text) {
        return new NormalizedUnit(text, 0, "");
    }
}
