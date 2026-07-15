package com.drillnotebook.app.service;

import java.util.List;

/**
 * 规则/AI 策略的统一产出。
 * parsed 为解析出的题目列表；lowQuality 为规则自评（仅 PDF 用到）。
 */
public record RuleResult(
        List<MarkdownQuestionParser.ParsedQuestion> parsed,
        List<String> errors,
        String strategy,
        boolean lowQuality
) {}
