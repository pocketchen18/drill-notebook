package com.drillnotebook.app.service;

/**
 * 规则解析策略。attempt 抛异常时由 Orchestrator 触发 AI 兜底。
 */
public interface RuleStrategy {
    RuleResult attempt(ImportRequest req) throws Exception;
}
