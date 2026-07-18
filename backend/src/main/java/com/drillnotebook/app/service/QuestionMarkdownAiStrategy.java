package com.drillnotebook.app.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 题库 Markdown 导入的 AI 兜底策略。
 * 把原始 Markdown 文本喂给 AI，AI 返回 JSON，再用 JsonQuestionParser 解析。
 */
@Component
public class QuestionMarkdownAiStrategy implements AiFallbackStrategy {

    private final AiService aiService;
    private final JsonQuestionParser jsonParser;

    public QuestionMarkdownAiStrategy(AiService aiService, JsonQuestionParser jsonParser) {
        this.aiService = aiService;
        this.jsonParser = jsonParser;
    }

    @Override
    public RuleResult attempt(ImportRequest req) {
        String json = aiService.parseQuestionsFromText(req.rawText(), req.masterPassword());
        List<MarkdownQuestionParser.ParsedQuestion> parsed = jsonParser.parse(json);
        return new RuleResult(parsed, List.of(), "ai-fallback", false);
    }
}
