package com.drillnotebook.app.service;

import org.springframework.stereotype.Service;

/**
 * 导入编排骨架：跑规则 → 判断 → 兜底；AI 也失败时回退规则或抛错。
 * 不含任何类型特定知识，所有差异由 RuleStrategy / AiFallbackStrategy 表达。
 */
@Service
public class ImportOrchestrator {

    public RuleResult run(RuleStrategy rule, AiFallbackStrategy ai, ImportRequest req) {
        RuleResult ruleResult;
        try {
            ruleResult = rule.attempt(req);
        } catch (Exception ruleError) {
            return fallbackOrRethrow(ai, req, null, ruleError);
        }
        if (ruleResult.parsed().isEmpty() || ruleResult.lowQuality()) {
            return fallbackOrRethrow(ai, req, ruleResult, null);
        }
        return ruleResult;
    }

    private RuleResult fallbackOrRethrow(AiFallbackStrategy ai, ImportRequest req,
                                          RuleResult ruleResult, Throwable ruleError) {
        try {
            return ai.attempt(req);
        } catch (Exception aiError) {
            if (ruleResult != null && !ruleResult.parsed().isEmpty()) {
                return ruleResult;
            }
            String message = aiError.getMessage() == null ? "未知错误" : aiError.getMessage();
            throw new IllegalArgumentException("规则解析失败且 AI 兜底不可用：" + message, aiError);
        }
    }
}
