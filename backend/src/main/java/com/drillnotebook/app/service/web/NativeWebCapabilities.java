package com.drillnotebook.app.service.web;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Official, documented model/protocol combinations only. Function-tool support is not native Web support. */
public record NativeWebCapabilities(boolean search, boolean fetch, String protocol) {
    private static final Set<String> CLAUDE = Set.of("claude-fable-5-2", "claude-mythos-5-2", "claude-fable-5-1", "claude-mythos-5-1",
            "claude-fable-5", "claude-mythos-5", "claude-mythos-preview", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5",
            "claude-sonnet-5", "claude-haiku-5-5", "claude-haiku-4-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
            "claude-sonnet-4-6", "claude-opus-4-5", "claude-sonnet-4-5");
    private static final Set<String> OPENAI = Set.of("gpt-5", "gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-6-astra");
    public static final NativeWebCapabilities NONE = new NativeWebCapabilities(false, false, "none");

    public static NativeWebCapabilities of(String endpoint, String format, String model) {
        try {
            URI url = URI.create(endpoint == null ? "" : endpoint.trim());
            if (!"https".equals(url.getScheme()) || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                    || url.getPort() != -1 && url.getPort() != 443 || model == null) return NONE;
            String name = model.toLowerCase(Locale.ROOT);
            String path = url.getPath().replaceFirst("/$", "");
            if ("anthropic".equals(format) && "api.anthropic.com".equalsIgnoreCase(url.getHost())
                    && Set.of("", "/v1", "/v1/messages").contains(path) && CLAUDE.contains(name.replaceFirst("-20[0-9]{6}$", ""))) {
                return new NativeWebCapabilities(true, true, "anthropic_messages");
            }
            if ("chat_completions".equals(format) && "api.openai.com".equalsIgnoreCase(url.getHost())
                    && Set.of("", "/v1", "/v1/chat/completions").contains(path) && OPENAI.contains(name.replaceFirst("-20[0-9]{2}-[0-9]{2}-[0-9]{2}$", ""))) {
                // An isolated Responses request uses the same accepted model, not a substitute search model.
                return new NativeWebCapabilities(true, false, "openai_responses");
            }
            return NONE;
        } catch (RuntimeException error) { return NONE; }
    }
}
