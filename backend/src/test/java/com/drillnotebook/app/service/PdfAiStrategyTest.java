package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aiFails_onMarkdownArtifactAndMissingKey_throwsSpecialHint() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn(
                "===\ntype: single answer: A\n===\ntype: multiple answer: B,C\n===\n");
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("请先配置 AI API Key"));
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        PdfAiStrategy strategy = new PdfAiStrategy(extractor, aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, "pw", false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> strategy.attempt(req));
        assertTrue(ex.getMessage().contains("此 PDF 的版式需 AI 解析"), "应抛环回 Markdown 残迹提示");
    }

    @Test
    void aiFails_timeout_keepsConcreteDetailEvenOnMarkdownArtifact() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn(
                "===\ntype: single answer: A\n===\ntype: multiple answer: B,C\n===\n");
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("AI 请求超时（240 秒）。PDF 解析已关闭模型思考模式"));
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        PdfAiStrategy strategy = new PdfAiStrategy(extractor, aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, "pw", false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> strategy.attempt(req));
        assertTrue(ex.getMessage().contains("超时"), "应保留超时细节，不再误报未配置");
        assertFalse(ex.getMessage().contains("未配置 AI API Key"), "超时不应被包装成未配置");
    }

    @Test
    void aiFails_normalCase_rethrowsConcreteDetail() {
        PdfTextExtractor extractor = Mockito.mock(PdfTextExtractor.class);
        Mockito.when(extractor.extract(Mockito.any())).thenReturn("普通 pdf 文本");
        AiService aiService = Mockito.mock(AiService.class);
        Mockito.when(aiService.parseQuestionsFromText(Mockito.anyString(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("AI 服务暂时不可用"));
        JsonQuestionParser jsonParser = new JsonQuestionParser(new ObjectMapper());

        PdfAiStrategy strategy = new PdfAiStrategy(extractor, aiService, jsonParser);
        ImportRequest req = new ImportRequest(1L, null, new byte[]{1, 2, 3}, "pw", false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> strategy.attempt(req));
        assertTrue(ex.getMessage().contains("AI 服务暂时不可用"), "应透传具体错误");
    }
}
