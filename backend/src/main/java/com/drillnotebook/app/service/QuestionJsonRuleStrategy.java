package com.drillnotebook.app.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 题库 JSON 导入的规则策略。直接用 JsonQuestionParser 解析。
 * JSON 没有半成功态，lowQuality 恒为 false；解析失败抛异常由 Orchestrator 兜底。
 */
@Component
public class QuestionJsonRuleStrategy implements RuleStrategy {

    private final JsonQuestionParser parser;

    public QuestionJsonRuleStrategy(JsonQuestionParser parser) {
        this.parser = parser;
    }

    @Override
    public RuleResult attempt(ImportRequest req) {
        List<MarkdownQuestionParser.ParsedQuestion> parsed = parser.parse(req.rawText());
        return new RuleResult(parsed, List.of(), "rules", false);
    }
}
