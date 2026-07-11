package com.drillnotebook.app;

import com.drillnotebook.app.service.MarkdownQuestionParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarkdownQuestionParserTest {
    private final MarkdownQuestionParser parser = new MarkdownQuestionParser();

    @Test
    void parsesSingleAndMultipleQuestions() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/sample-bank.md")) {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var result = parser.parse(source);
            assertEquals(2, result.size());
            assertEquals("single", result.get(0).type());
            assertEquals("B", result.get(0).answer());
            assertEquals(4, result.get(0).options().size());
            assertEquals("A,C", result.get(1).answer());
        }
    }

    @Test
    void rejectsMissingAnswer() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                ---
                type: single
                ---
                ### 题干
                A question
                A. option
                """));
    }
}
