package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PdfAiStrategyTest {

    @Test
    void callsAiAndParsesJson() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn("raw pdf text");
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenReturn("{\"questions\":[{\"type\":\"single\",\"stem\":\"q\",\"options\":[{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"}],\"answer\":\"A\"}]}");
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        PdfAiStrategy strategy = new PdfAiStrategy(extractor, aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, "pw", false);
        RuleResult result = strategy.attempt(req);

        assertEquals(1, result.parsed().size());
        assertEquals("ai-fallback", result.strategy());
    }

    @Test
    void aiFails_onMarkdownArtifact_throwsSpecialHint() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn(
                "===\ntype: single answer: A\n===\ntype: multiple answer: B,C\n===\n");
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("AI 服务暂时不可用"));
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        PdfAiStrategy strategy = new PdfAiStrategy(extractor, aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, "pw", false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> strategy.attempt(req));
        assertTrue(ex.getMessage().contains("此 PDF 的版式需 AI 解析"), "应抛环回 Markdown 残迹提示");
    }

    @Test
    void aiFails_normalCase_throwsGenericHint() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn("普通 pdf 文本");
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("AI 服务暂时不可用"));
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        PdfAiStrategy strategy = new PdfAiStrategy(extractor, aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, "pw", false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> strategy.attempt(req));
        assertTrue(ex.getMessage().contains("规则解析失败且 AI 兜底不可用"), "应抛通用兜底失败提示");
    }
}
