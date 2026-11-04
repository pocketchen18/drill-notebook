package com.drillnotebook.app.service;

import java.net.URI;
import java.util.Locale;

/**
 * 已验证目标的显式能力表；未知网关一律按基础协议处理。
 *
 * <p>缓存断点只在 Anthropic 官方 Messages 端点开启，不能因为“apiFormat=anthropic”就推断任意
 * 兼容网关支持 {@code cache_control}。流式 usage 同样只发给已知会返回标准 usage 的官方端点。</p>
 */
public record ProviderCapabilities(boolean cacheBreakpoints, boolean streamUsage) {

    public static ProviderCapabilities of(String baseUrl, String apiFormat, String model) {
        String host = hostOf(baseUrl);
        boolean anthropic = AiUpstreamClient.FORMAT_ANTHROPIC.equals(apiFormat)
                && "api.anthropic.com".equals(host)
                && model != null && model.toLowerCase(Locale.ROOT).startsWith("claude");
        boolean openAiUsage = "api.openai.com".equals(host) || "api.deepseek.com".equals(host);
        return new ProviderCapabilities(anthropic, anthropic || openAiUsage);
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl == null ? "" : baseUrl.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException error) {
            return "";
        }
    }
}
