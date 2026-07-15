package com.drillnotebook.app.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PDF 导入的规则策略。
 * 流程：PdfTextExtractor 提文本 → PdfTextAdapter 适配版式 → MarkdownQuestionParser 解析。
 * lowQuality 判断沿用旧阈值：totalQuestions < 3 或 confidence < 0.5。
 */
@Component
public class PdfRuleStrategy implements RuleStrategy {

    private static final double CONFIDENCE_THRESHOLD = 0.5;
    private static final int MIN_QUESTIONS_THRESHOLD = 3;

    private final PdfTextExtractor textExtractor;
    private final PdfTextAdapter textAdapter;
    private final MarkdownQuestionParser markdownParser;

    public PdfRuleStrategy(PdfTextExtractor textExtractor, PdfTextAdapter textAdapter,
                           MarkdownQuestionParser markdownParser) {
        this.textExtractor = textExtractor;
        this.textAdapter = textAdapter;
        this.markdownParser = markdownParser;
    }

    @Override
    public RuleResult attempt(ImportRequest req) {
        String rawText = textExtractor.extract(req.rawBytes());
        PdfTextAdapter.AdapterResult adapted = textAdapter.adapt(rawText);
        boolean lowQuality = adapted.totalQuestions() < MIN_QUESTIONS_THRESHOLD
                || adapted.confidence() < CONFIDENCE_THRESHOLD;
        List<MarkdownQuestionParser.ParsedQuestion> parsed = markdownParser.parse(adapted.markdown());
        return new RuleResult(parsed, List.of(), "rules", lowQuality);
    }
}
