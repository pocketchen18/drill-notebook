package com.drillnotebook.app;

import com.drillnotebook.app.service.JsonQuestionParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

class JsonQuestionParserTest {
    private final JsonQuestionParser parser = new JsonQuestionParser(new ObjectMapper());

    @Test
    void parsesObjectWrapperWithAllQuestionTypes() throws IOException {
        String source = readResource("/fixtures/questions.json");
        var result = parser.parse(source);
        assertEquals(5, result.size());

        assertEquals("single", result.get(0).type());
        assertEquals("B", result.get(0).answer());
        assertEquals(4, result.get(0).options().size());
        assertEquals(2, result.get(0).difficulty());
        assertEquals("intro", result.get(0).groupId());

        assertEquals("multiple", result.get(1).type());
        assertEquals("A,C", result.get(1).answer());

        assertEquals("fill", result.get(2).type());
        assertEquals("Java Virtual Machine", result.get(2).answer());

        assertEquals("true_false", result.get(3).type());
        assertEquals("true", result.get(3).answer());

        assertEquals("essay", result.get(4).type());
        assertEquals("", result.get(4).answer());
        assertTrue(result.get(4).options().isEmpty());
    }

    @Test
    void parsesTopLevelArray() throws IOException {
        String source = readResource("/fixtures/questions-array.json");
        var result = parser.parse(source);
        assertEquals(2, result.size());
        assertEquals("single", result.get(0).type());
        assertEquals("multiple", result.get(1).type());
        assertEquals("A,B", result.get(1).answer());
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{not json"));
    }

    @Test
    void rejectsMissingQuestionsField() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"meta\": \"x\"}"));
    }

    @Test
    void rejectsEmptyQuestionsArray() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"questions\": []}"));
    }

    @Test
    void rejectsMissingStem() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions": [{"type": "fill", "answer": "x"}]}
                """));
    }

    @Test
    void rejectsInvalidType() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions": [{"type": "magic", "stem": "q", "answer": "y"}]}
                """));
    }

    @Test
    void rejectsChoiceWithoutOptions() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions": [{"type": "single", "stem": "q", "answer": "A"}]}
                """));
    }

    @Test
    void rejectsInvalidDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions": [{"type": "fill", "stem": "q", "answer": "a", "difficulty": 9}]}
                """));
    }

    @Test
    void rejectsNonNumericDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions": [{"type": "fill", "stem": "q", "answer": "a", "difficulty": "hard"}]}
                """));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = JsonQuestionParserTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
