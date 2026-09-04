package com.drillnotebook.app.service;

import com.drillnotebook.app.model.NormalizedUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NoteNormalizer} — TipTap JSON → ordered text units.
 */
class NoteNormalizerTest {

    private NoteNormalizer normalizer;

    @BeforeEach
    void setup() {
        normalizer = new NoteNormalizer(new ObjectMapper());
    }

    @Test
    void emptyAndNullInput() {
        assertTrue(normalizer.normalize(null).isEmpty(), "null input");
        assertTrue(normalizer.normalize("").isEmpty(), "empty string");
        assertTrue(normalizer.normalize("   ").isEmpty(), "blank string");
    }

    @Test
    void simpleParagraph() {
        String json = """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Hello world"}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("Hello world", units.get(0).text());
        assertEquals(0, units.get(0).headingLevel());
    }

    @Test
    void chineseText() {
        String json = """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"你好世界"}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("你好世界", units.get(0).text());
    }

    @Test
    void headingLevels() {
        String json = """
                {"type":"doc","content":[
                    {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Title"}]},
                    {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Section"}]},
                    {"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"Sub"}]}
                ]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(3, units.size());
        assertEquals("# Title", units.get(0).text());
        assertEquals(1, units.get(0).headingLevel());
        assertEquals("Title", units.get(0).headingText());
        assertEquals("## Section", units.get(1).text());
        assertEquals(2, units.get(1).headingLevel());
        assertEquals("### Sub", units.get(2).text());
        assertEquals(3, units.get(2).headingLevel());
    }

    @Test
    void headingDefaultLevel() {
        // No level attr → defaults to 2
        String json = """
                {"type":"doc","content":[{"type":"heading","content":[{"type":"text","text":"Default"}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("## Default", units.get(0).text());
        assertEquals(2, units.get(0).headingLevel());
    }

    @Test
    void headingLevelClamped() {
        String json = """
                {"type":"doc","content":[{"type":"heading","attrs":{"level":0},"content":[{"type":"text","text":"Low"}]},{"type":"heading","attrs":{"level":99},"content":[{"type":"text","text":"High"}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(2, units.size());
        assertEquals("# Low", units.get(0).text());
        assertEquals(1, units.get(0).headingLevel());
        assertEquals("###### High", units.get(1).text());
        assertEquals(6, units.get(1).headingLevel());
    }

    @Test
    void bulletList() {
        String json = """
                {"type":"doc","content":[{"type":"bulletList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item A"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item B"}]}]}
                ]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("- Item A\n- Item B", units.get(0).text());
    }

    @Test
    void orderedList() {
        String json = """
                {"type":"doc","content":[{"type":"orderedList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"First"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Second"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Third"}]}]}
                ]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("1. First\n2. Second\n3. Third", units.get(0).text());
    }

    @Test
    void codeBlock() {
        String json = """
                {"type":"doc","content":[{"type":"codeBlock","attrs":{"language":"java"},"content":[{"type":"text","text":"public class Hello {}"}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("```java\npublic class Hello {}\n```", units.get(0).text());
    }

    @Test
    void mathBlock() {
        String json = """
                {"type":"doc","content":[{"type":"mathBlock","attrs":{"latex":"E=mc^2"}}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("$$\nE=mc^2\n$$", units.get(0).text());
    }

    @Test
    void mathInline() {
        String json = """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Formula: "},{"type":"mathInline","attrs":{"latex":"x^2"}}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("Formula: $x^2$", units.get(0).text());
    }

    @Test
    void mermaidBlock() {
        String json = """
                {"type":"doc","content":[{"type":"mermaidBlock","attrs":{"code":"graph TD; A-->B;"}}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("```mermaid\ngraph TD; A-->B;\n```", units.get(0).text());
    }

    @Test
    void markdownBlock() {
        String json = """
                {"type":"doc","content":[{"type":"markdownBlock","attrs":{"markdown":"**bold** and _italic_"}}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("**bold** and _italic_", units.get(0).text());
    }

    @Test
    void blockquoteWrapsChildren() {
        String json = """
                {"type":"doc","content":[{"type":"blockquote","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"Cited text"}]}
                ]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("> Cited text", units.get(0).text());
    }

    @Test
    void questionBlockIsSkipped() {
        String json = """
                {"type":"doc","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"Before"}]},
                    {"type":"questionBlock","attrs":{"questionId":1,"snapshot":{"type":"single","stem":"Q?"}}},
                    {"type":"paragraph","content":[{"type":"text","text":"After"}]}
                ]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(2, units.size());
        assertEquals("Before", units.get(0).text());
        assertEquals("After", units.get(1).text());
    }

    @Test
    void hardBreakWithinParagraph() {
        String json = """
                {"type":"doc","content":[{"type":"paragraph","content":[
                    {"type":"text","text":"Line1"},
                    {"type":"hardBreak"},
                    {"type":"text","text":"Line2"}
                ]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("Line1\nLine2", units.get(0).text());
    }

    @Test
    void mixedContent() {
        String json = """
                {"type":"doc","content":[
                    {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Introduction"}]},
                    {"type":"paragraph","content":[{"type":"text","text":"Some intro text."}]},
                    {"type":"bulletList","content":[
                        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Point 1"}]}]},
                        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Point 2"}]}]}
                    ]},
                    {"type":"codeBlock","attrs":{"language":"rust"},"content":[{"type":"text","text":"fn main() {}"}]}
                ]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(4, units.size());
        assertEquals("## Introduction", units.get(0).text());
        assertEquals(2, units.get(0).headingLevel());
        assertEquals("Some intro text.", units.get(1).text());
        assertEquals("- Point 1\n- Point 2", units.get(2).text());
        assertEquals("```rust\nfn main() {}\n```", units.get(3).text());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("{invalid json}"));
    }

    @Test
    void emptyDocReturnsEmptyList() {
        String json = """
                {"type":"doc","content":[]}
                """;
        assertTrue(normalizer.normalize(json).isEmpty());
    }

    @Test
    void emptyParagraphFilteredOut() {
        // Empty paragraphs produce blank-text units that are filtered out.
        String json = """
                {"type":"doc","content":[
                    {"type":"paragraph","content":[{"type":"text","text":""}]},
                    {"type":"paragraph","content":[{"type":"text","text":"Real content"}]}
                ]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("Real content", units.get(0).text());
    }

    @Test
    void imageOnlyContentProducesNoUnits() {
        // Image nodes (unknown type with no text children) produce empty units → filtered out.
        String json = """
                {"type":"doc","content":[
                    {"type":"paragraph","content":[
                        {"type":"image","attrs":{"src":"diagram.png","alt":"Diagram"}}
                    ]}
                ]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertTrue(units.isEmpty(), "image-only content should produce zero units");
    }

    @Test
    void chineseScalarCharCount() {
        // Verify Chinese characters are treated as individual scalar characters
        String json = """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"你好世界"}]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("你好世界", units.get(0).text());
        assertEquals(4, units.get(0).text().length(), "4 Unicode scalar chars");
    }

    @Test
    void onlyQuestionBlockReturnsEmpty() {
        String json = """
                {"type":"doc","content":[{"type":"questionBlock","attrs":{"questionId":1}}]}
                """;
        assertTrue(normalizer.normalize(json).isEmpty());
    }

    @Test
    void videoBlockIndexesTitleAndUrl() {
        String json = """
                {"type":"doc","content":[{"type":"videoBlock","attrs":{
                    "videoType":"url","url":"https://example.com/v.mp4",
                    "title":"线性代数第 3 讲","view":"link"}}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("视频：线性代数第 3 讲 https://example.com/v.mp4", units.get(0).text());
    }

    @Test
    void fileBlockIndexesFileName() {
        String json = """
                {"type":"doc","content":[{"type":"fileBlock","attrs":{
                    "attachmentId":7,"fileName":"考纲.pdf",
                    "mimeType":"application/pdf","fileSize":1024}}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("附件：考纲.pdf", units.get(0).text());
    }

    @Test
    void emptyVideoAndFileBlocksProduceNoUnits() {
        String json = """
                {"type":"doc","content":[
                    {"type":"videoBlock","attrs":{"title":"","url":null}},
                    {"type":"fileBlock","attrs":{"fileName":""}}
                ]}
                """;
        assertTrue(normalizer.normalize(json).isEmpty());
    }

    @Test
    void unknownBlockNodeRecursesIntoChildren() {
        // unknown container nodes should recurse into their content children
        String json = """
                {"type":"doc","content":[{"type":"unknownWrapper","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"Nested"}]}
                ]}]}
                """;
        List<NormalizedUnit> units = normalizer.normalize(json);
        assertEquals(1, units.size());
        assertEquals("Nested", units.get(0).text());
    }
}
