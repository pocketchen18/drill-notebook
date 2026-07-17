package com.drillnotebook.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.service.JsonQuestionParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonQuestionParserSalvageTest {
    private final JsonQuestionParser parser = new JsonQuestionParser(new ObjectMapper());

    @Test
    void salvagesTruncatedQuestionsArrayAndSkipsBrokenTail() {
        String truncated = """
                {"questions":[
                  {"type":"single","stem":"题1","options":[{"key":"A","text":"a"},{"key":"B","text":"b"}],"answer":"A"},
                  {"type":"fill","stem":"题2","answer":"JVM"},
                  {"type":"single","stem":"半截题","options":[{"key":"A","text":"
                """;
        var parsed = parser.parse(truncated);
        assertEquals(2, parsed.size());
        assertEquals("题1", parsed.get(0).stem());
        assertEquals("题2", parsed.get(1).stem());
    }

    @Test
    void salvageHelperClosesTruncatedJson() {
        String recovered = JsonQuestionParser.salvageTruncatedQuestionsJson(
                "{\"questions\":[{\"type\":\"fill\",\"stem\":\"s\",\"answer\":\"a\"},{\"type\":\"fill\",\"stem\":\"broken");
        assertTrue(recovered != null && recovered.contains("questions"));
        assertTrue(recovered.trim().endsWith("}") || recovered.trim().endsWith("]"));
    }
}
