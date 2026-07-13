package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionTypeRulesTest {
    private static final List<Map<String, String>> OPTIONS = List.of(
            Map.of("key", "A", "text", "甲"),
            Map.of("key", "B", "text", "乙"));

    @Test
    void acceptsAnswersThatReferenceExistingOptions() {
        assertDoesNotThrow(() -> QuestionTypeRules.validate("single", "A", OPTIONS));
        assertDoesNotThrow(() -> QuestionTypeRules.validate("multiple", "B,A", OPTIONS));
    }

    @Test
    void rejectsMalformedChoiceStructures() {
        assertThrows(IllegalArgumentException.class, () -> QuestionTypeRules.validate("single", "Z", OPTIONS));
        assertThrows(IllegalArgumentException.class, () -> QuestionTypeRules.validate("single", "A,B", OPTIONS));
        assertThrows(IllegalArgumentException.class, () -> QuestionTypeRules.validate("single", "A", List.of(
                Map.of("key", "A", "text", "甲"), Map.of("key", "A", "text", "乙"))));
        assertThrows(IllegalArgumentException.class, () -> QuestionTypeRules.validate("single", "A", List.of(
                Map.of("key", "A", "text", ""), Map.of("key", "B", "text", "乙"))));
    }
}
