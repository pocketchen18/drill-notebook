package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportOrchestratorTest {

    private final ImportOrchestrator orchestrator = new ImportOrchestrator();

    private static ImportRequest req() {
        return new ImportRequest(1L, "raw", null, "pw", false);
    }

    private static RuleResult result(int parsedCount, String strategy, boolean lowQuality) {
        List<MarkdownQuestionParser.ParsedQuestion> list =
                parsedCount == 0 ? List.of() : stubQuestions(parsedCount);
        return new RuleResult(list, List.of(), strategy, lowQuality);
    }

    private static List<MarkdownQuestionParser.ParsedQuestion> stubQuestions(int n) {
        List<MarkdownQuestionParser.ParsedQuestion> result = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(new MarkdownQuestionParser.ParsedQuestion(
                    "single", "stub stem " + i,
                    List.of(Map.of("key", "A", "text", "a"), Map.of("key", "B", "text", "b")),
                    "A", "", 3, List.of(), null, null, null));
        }
        return result;
    }

    @Test
    void ruleSuccess_returnsRuleResult() {
        RuleResult rule = result(2, "rules", false);
        RuleResult got = orchestrator.run(
                (r) -> rule,
                (r) -> { throw new IllegalStateException("AI should not be called"); },
                req());
        assertSame(rule, got);
    }

    @Test
    void ruleThrowsException_fallsBackToAi() {
        RuleResult ai = result(3, "ai-fallback", false);
        RuleResult got = orchestrator.run(
                (r) -> { throw new IllegalArgumentException("rule broken"); },
                (r) -> ai,
                req());
        assertSame(ai, got);
    }

    @Test
    void ruleEmptyParsed_fallsBackToAi() {
        RuleResult ai = result(3, "ai-fallback", false);
        RuleResult got = orchestrator.run(
                (r) -> result(0, "rules", false),
                (r) -> ai,
                req());
        assertSame(ai, got);
    }

    @Test
    void ruleLowQuality_fallsBackToAi() {
        RuleResult ai = result(3, "ai-fallback", false);
        RuleResult got = orchestrator.run(
                (r) -> result(2, "rules", true),
                (r) -> ai,
                req());
        assertSame(ai, got);
    }

    @Test
    void aiFails_ruleHasParsed_returnsRuleResult() {
        RuleResult rule = result(2, "rules", true);
        RuleResult got = orchestrator.run(
                (r) -> rule,
                (r) -> { throw new IllegalArgumentException("AI broken"); },
                req());
        assertSame(rule, got);
    }

    @Test
    void aiFails_ruleEmpty_throwsWithAiMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            orchestrator.run(
                    (r) -> result(0, "rules", false),
                    (r) -> { throw new IllegalArgumentException("AI broken"); },
                    req()));
        assertTrue(ex.getMessage().contains("AI broken"));
    }

    @Test
    void aiFails_ruleThrew_throwsWithAiMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            orchestrator.run(
                    (r) -> { throw new IllegalArgumentException("rule broken"); },
                    (r) -> { throw new IllegalArgumentException("AI broken"); },
                    req()));
        assertTrue(ex.getMessage().contains("AI broken"));
    }
}
