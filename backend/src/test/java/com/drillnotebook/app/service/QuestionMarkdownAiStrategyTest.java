package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QuestionMarkdownAiStrategyTest {

    @Test
    void callsAiAndParsesJson() {
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenReturn("{\"questions\":[{\"type\":\"single\",\"stem\":\"q\",\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"}],\"answer\":\"A\"}]}");
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        QuestionMarkdownAiStrategy strategy = new QuestionMarkdownAiStrategy(aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, "broken md", null, "pw", false);
        RuleResult result = strategy.attempt(req);

        assertEquals(1, result.parsed().size());
        assertEquals("ai-fallback", result.strategy());
    }
}
