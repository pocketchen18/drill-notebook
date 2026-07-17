package com.drillnotebook.app.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PDF 导入的 AI 兜底策略。
 * 流程：PdfTextExtractor 提文本 → AiService.parseQuestionsFromText 拿 JSON → JsonQuestionParser 解析。
 * AI 失败时根据 looksLikeMarkdownArtifact 抛带不同提示的异常，Orchestrator 透传。
 */
@Component
public class PdfAiStrategy implements AiFallbackStrategy {

    private final PdfTextExtractor textExtractor;
    private final AiService aiService;
    private final JsonQuestionParser jsonParser;

    public PdfAiStrategy(PdfTextExtractor textExtractor, AiService aiService, JsonQuestionParser jsonParser) {
        this.textExtractor = textExtractor;
        this.aiService = aiService;
        this.jsonParser = jsonParser;
    }

    @Override
    public RuleResult attempt(ImportRequest req) {
        String rawText = textExtractor.extract(req.rawBytes());
        try {
            String json = aiService.parseQuestionsFromText(rawText, req.masterPassword());
            List<MarkdownQuestionParser.ParsedQuestion> parsed = jsonParser.parse(json);
            return new RuleResult(parsed, List.of(), "ai-fallback", false);
        } catch (IllegalArgumentException aiError) {
            String hint = looksLikeMarkdownArtifact(rawText)
                    ? "此 PDF 的版式需 AI 解析但未配置 AI API Key，请在设置中配置后再试"
                    : "规则解析失败且 AI 兜底不可用："
                            + (aiError.getMessage() == null ? "未知错误" : aiError.getMessage());
            throw new IllegalArgumentException(hint, aiError);
        }
    }

    /**
     * 识别 PDF 提取出的文本是否含"规整 Markdown 转 PDF 再提取"的残迹。
     * 命中说明此 PDF 本不该走 Adapter（Adapter 是给版式 Y/X/Z 题目文本用的），最该走 AI。
     */
    private static boolean looksLikeMarkdownArtifact(String rawText) {
        if (rawText == null) return false;
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        long fences = normalized.lines().filter(l -> l.trim().equals("===")).count();
        long typeLines = normalized.lines().filter(l -> l.startsWith("type:") && l.contains("answer:")).count();
        return fences >= 2 && typeLines >= 2;
    }
}
