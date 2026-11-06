package com.drillnotebook.app.service;

import java.net.URI;
import java.util.Locale;

/**
 * 已验证目标的显式能力表；未知网关一律按基础协议处理。
 *
 * <p>缓存断点只在 Anthropic 官方 Messages 端点开启，不能因为“apiFormat=anthropic”就推断任意
 * 兼容网关支持 {@code cache_control}。流式 usage 同样只发给已知会返回标准 usage 的官方端点。</p>
 *
 * <p>工具调用能力同样不能从协议格式推断：只对已验证的官方端点默认开启；自定义目标必须在配置中
 * 显式声明 {@code tools=true} 才会使用。未声明时保持纯文本（含词法记忆预注入）路径。</p>
 */
public record ProviderCapabilities(boolean cacheBreakpoints, boolean streamUsage, boolean tools) {

    public ProviderCapabilities(boolean cacheBreakpoints, boolean streamUsage) {
        this(cacheBreakpoints, streamUsage, false);
    }

    public static ProviderCapabilities of(String baseUrl, String apiFormat, String model) {
        return of(baseUrl, apiFormat, model, null);
    }

    /**
     * @param toolsOverride 配置中的显式开关；null 表示按已验证端点推断，非 null 时优先。
     */
    public static ProviderCapabilities of(String baseUrl, String apiFormat, String model, Boolean toolsOverride) {
        String host = hostOf(baseUrl);
        boolean anthropic = AiUpstreamClient.FORMAT_ANTHROPIC.equals(apiFormat)
                && "api.anthropic.com".equals(host)
                && model != null && model.toLowerCase(Locale.ROOT).startsWith("claude");
        boolean openAiUsage = "api.openai.com".equals(host) || "api.deepseek.com".equals(host);
        boolean verifiedTools = anthropic || openAiUsage;
        return new ProviderCapabilities(anthropic, anthropic || openAiUsage,
                toolsOverride == null ? verifiedTools : toolsOverride);
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
