package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class QuestionMarkdownRuleStrategyTest {

    private final MarkdownQuestionParser parser = new MarkdownQuestionParser();
    private final QuestionMarkdownRuleStrategy strategy = new QuestionMarkdownRuleStrategy(parser);

    @Test
    void parsesValidMarkdown() {
        ImportRequest req = new ImportRequest(1L,
                "---\ntype: single\nanswer: B\n---\n### 题干\nJava 关键字？\nA. function\nB. class\n",
                null, null, false);
        RuleResult result = strategy.attempt(req);
        assertEquals(1, result.parsed().size());
        assertEquals("rules", result.strategy());
        assertFalse(result.lowQuality());
    }
}
