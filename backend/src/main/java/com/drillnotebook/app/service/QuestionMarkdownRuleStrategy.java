package com.drillnotebook.app.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 题库 Markdown 导入的规则策略。直接用 MarkdownQuestionParser 解析。
 * Markdown 没有半成功态，lowQuality 恒为 false；解析失败抛异常由 Orchestrator 兜底。
 */
@Component
public class QuestionMarkdownRuleStrategy implements RuleStrategy {

    private final MarkdownQuestionParser parser;

    public QuestionMarkdownRuleStrategy(MarkdownQuestionParser parser) {
        this.parser = parser;
    }

    @Override
    public RuleResult attempt(ImportRequest req) {
        List<MarkdownQuestionParser.ParsedQuestion> parsed = parser.parse(req.rawText());
        return new RuleResult(parsed, List.of(), "rules", false);
    }
}
