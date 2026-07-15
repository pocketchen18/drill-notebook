package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PdfRuleStrategyTest {

    @Test
    void marksLowQualityWhenConfidenceBelowThreshold() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn("1. q\nA. a\nB. b\n答案：A\n");
        PdfTextAdapter adapter = Mockito.mock(PdfTextAdapter.class);
        Mockito.when(adapter.adapt(Mockito.anyString()))
                .thenReturn(new PdfTextAdapter.AdapterResult(
                        "---\ntype: single\nanswer: A\n---\n### 题干\nq\nA. a\nB. b\n===",
                        1, 0, 1, 0.3));
        MarkdownQuestionParser parser = new MarkdownQuestionParser();

        PdfRuleStrategy strategy = new PdfRuleStrategy(extractor, adapter, parser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, null, false);
        RuleResult result = strategy.attempt(req);

        assertEquals(1, result.parsed().size());
        assertTrue(result.lowQuality(), "confidence < 0.5 应标记 lowQuality");
    }

    @Test
    void marksLowQualityWhenTotalQuestionsBelowThreshold() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn("1. q\nA. a\nB. b\n答案：A\n");
        PdfTextAdapter adapter = Mockito.mock(PdfTextAdapter.class);
        Mockito.when(adapter.adapt(Mockito.anyString()))
                .thenReturn(new PdfTextAdapter.AdapterResult(
                        "---\ntype: single\nanswer: A\n---\nq\nA. a\nB. b\n===",
                        2, 2, 2, 1.0));
        MarkdownQuestionParser parser = new MarkdownQuestionParser();

        PdfRuleStrategy strategy = new PdfRuleStrategy(extractor, adapter, parser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, null, false);
        RuleResult result = strategy.attempt(req);

        assertTrue(result.lowQuality(), "totalQuestions < 3 应标记 lowQuality");
    }

    @Test
    void noLowQualityWhenConfidenceAndCountBothAdequate() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn("1. q\nA. a\nB. b\n答案：A\n");
        PdfTextAdapter adapter = Mockito.mock(PdfTextAdapter.class);
        Mockito.when(adapter.adapt(Mockito.anyString()))
                .thenReturn(new PdfTextAdapter.AdapterResult(
                        "---\ntype: single\nanswer: A\n---\nq\nA. a\nB. b\n===",
                        3, 3, 3, 1.0));
        MarkdownQuestionParser parser = new MarkdownQuestionParser();

        PdfRuleStrategy strategy = new PdfRuleStrategy(extractor, adapter, parser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, null, false);
        RuleResult result = strategy.attempt(req);

        assertFalse(result.lowQuality(), "confidence >= 0.5 且 totalQuestions >= 3 不应标记 lowQuality");
    }
}
