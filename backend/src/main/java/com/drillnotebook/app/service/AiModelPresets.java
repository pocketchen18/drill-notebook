package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConnectionsStore.Provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** 随应用分发的参考资料；匹配只读，刷新列表绝不改写已有模型。 */
public final class AiModelPresets {
    public record Preset(String id, String name, String vendor, String apiFormat, List<String> aliases,
                         Integer contextWindow, Integer maxOutputTokens, String reasoningControl,
                         List<String> reasoningEfforts) {
        boolean matches(String modelId) { return id.equals(modelId) || aliases.contains(modelId); }
    }
    private record Catalog(String version, List<String> sources, String note, List<Preset> models) { }
    private static final Catalog CATALOG = load();
    private static final Map<String, String> OFFICIAL = Map.ofEntries(
            Map.entry("api.openai.com", "openai"), Map.entry("api.anthropic.com", "anthropic"),
            Map.entry("api.deepseek.com", "deepseek"), Map.entry("api.x.ai", "xai"),
            Map.entry("generativelanguage.googleapis.com", "google"), Map.entry("api.moonshot.cn", "moonshot"),
            Map.entry("api.moonshot.ai", "moonshot"), Map.entry("api.minimax.io", "minimax"),
            Map.entry("api.minimaxi.com", "minimax"), Map.entry("open.bigmodel.cn", "zhipu"));

    private static Catalog load() {
        try (var input = AiModelPresets.class.getResourceAsStream("/ai-model-presets.json")) {
            if (input == null) throw new IllegalStateException("缺少预设模型资料");
            return new ObjectMapper().readValue(input, Catalog.class);
        } catch (Exception error) { throw new IllegalStateException("预设模型资料无法读取", error); }
    }
    public static String version() { return CATALOG.version(); }
    public static List<Preset> forProvider(Provider provider) {
        String vendor = null;
        try { vendor = OFFICIAL.get(URI.create(provider.endpoint()).getHost()); } catch (RuntimeException ignored) { }
        final String expected = vendor;
        return CATALOG.models().stream().filter(p -> expected == null || expected.equals(p.vendor())).map(p -> {
            if (provider.apiFormat().equals(p.apiFormat())) return p;
            // 兼容网关未核验原生 SDK 的映射，只引用名称/容量，不猜测 thinking 参数。
            return new Preset(p.id(), p.name(), p.vendor(), provider.apiFormat(), p.aliases(), p.contextWindow(),
                    p.maxOutputTokens(), "effort", List.of());
        }).toList();
    }
    public static Preset match(Provider provider, String modelId) {
        List<Preset> matches = forProvider(provider).stream().filter(p -> p.matches(modelId)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private AiModelPresets() { }
}
