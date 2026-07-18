package com.drillnotebook.app.service;

import org.springframework.stereotype.Component;

/**
 * 题库 Markdown 导入的中间兜底策略：解析前端 {@code questionMarkdown} 导出格式。
 *
 * <p>当旧 {@link QuestionMarkdownRuleStrategy}（要求 YAML frontmatter + {@code ===} 分隔）失败时，
 * 由 {@link ImportOrchestrator#run(RuleStrategy, RuleStrategy, AiFallbackStrategy, ImportRequest)}
 * 三步链路触发本策略。产出 strategy 标记 {@code "export-rules"}，与旧 {@code "rules"} 区分。
 */
@Component
public class ExportMarkdownRuleStrategy implements RuleStrategy {

    private final ExportMarkdownParser parser;

    public ExportMarkdownRuleStrategy(ExportMarkdownParser parser) {
        this.parser = parser;
    }

    @Override
    public RuleResult attempt(ImportRequest req) {
        return new RuleResult(parser.parse(req.rawText()), java.util.List.of(), "export-rules", false);
    }
}
