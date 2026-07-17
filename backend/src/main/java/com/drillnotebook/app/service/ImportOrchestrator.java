package com.drillnotebook.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 导入编排骨架：跑规则 → 判断 → 兜底；AI 也失败时回退规则或抛错。
 * 不含任何类型特定知识，所有差异由 RuleStrategy / AiFallbackStrategy 表达。
 */
@Service
public class ImportOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ImportOrchestrator.class);

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

    /**
     * 三步链路：规则 → 中间兜底 → AI。
     * 规则成功（≥1 题、非 lowQuality）直接返回；否则尝试中间兜底；
     * 中间兜底成功（≥1 题）返回；否则尝试 AI。
     * 全部失败时抛聚合错误。
     */
    public RuleResult run(RuleStrategy rule, RuleStrategy fallback, AiFallbackStrategy ai, ImportRequest req) {
        RuleResult ruleResult;
        try {
            ruleResult = rule.attempt(req);
        } catch (Exception ruleError) {
            return runFallbackThenAi(fallback, ai, req, null);
        }
        if (ruleResult.parsed().isEmpty() || ruleResult.lowQuality()) {
            return runFallbackThenAi(fallback, ai, req, ruleResult);
        }
        return ruleResult;
    }

    private RuleResult runFallbackThenAi(RuleStrategy fallback, AiFallbackStrategy ai, ImportRequest req,
                                          RuleResult ruleResult) {
        RuleResult fallbackResult;
        try {
            fallbackResult = fallback.attempt(req);
        } catch (Exception fallbackError) {
            return finalAiFallback(ai, req, ruleResult);
        }
        if (fallbackResult.parsed().isEmpty()) {
            return finalAiFallback(ai, req, fallbackResult);
        }
        return fallbackResult;
    }

    private RuleResult finalAiFallback(AiFallbackStrategy ai, ImportRequest req, RuleResult priorResult) {
        try {
            return ai.attempt(req);
        } catch (Exception aiError) {
            if (priorResult != null && !priorResult.parsed().isEmpty()) {
                return priorResult;
            }
            String message = aiError.getMessage() == null ? "未知错误" : aiError.getMessage();
            throw new IllegalArgumentException("规则解析失败且 AI 兜底不可用：" + message, aiError);
        }
    }
}
