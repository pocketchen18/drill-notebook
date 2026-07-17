package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class QuestionJsonRuleStrategyTest {

    private final JsonQuestionParser parser = new JsonQuestionParser(new ObjectMapper());
    private final QuestionJsonRuleStrategy strategy = new QuestionJsonRuleStrategy(parser);

    @Test
    void parsesValidJson() {
        ImportRequest req = new ImportRequest(1L,
                "{\"questions\":[{\"type\":\"single\",\"stem\":\"q\",\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"}],\"answer\":\"A\"}]}",
                null, null, false);
        RuleResult result = strategy.attempt(req);
        assertEquals(1, result.parsed().size());
        assertEquals("rules", result.strategy());
        assertFalse(result.lowQuality());
    }
}
