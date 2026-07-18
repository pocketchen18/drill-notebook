package com.drillnotebook.app.service;

/**
 * 导入请求值对象。携带原始输入和上下文，供 RuleStrategy / AiFallbackStrategy 使用。
 * rawText 用于文本类导入（MD、JSON），rawBytes 用于二进制类导入（PDF）。
 */
public record ImportRequest(
        long bankId,
        String rawText,
        byte[] rawBytes,
        String masterPassword,
        boolean forceAi
) {}
