package com.drillnotebook.app.service;

/**
 * AI 兜底策略。attempt 抛异常时由 Orchestrator 决定回退规则结果或抛错。
 */
public interface AiFallbackStrategy {
    RuleResult attempt(ImportRequest req) throws Exception;
}
