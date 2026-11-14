package com.drillnotebook.app.model;

import com.drillnotebook.app.service.ApiKeyEncryptor;
import java.util.Map;

/** Provider choices are separate from the global search/fetch permissions. Never expose keys in a view. */
public record WebSearchSettings(Provider searchProvider, Provider fetchProvider, boolean preferNative,
                                Map<Provider, ApiKeyEncryptor.EncryptedValue> keys) {
    public enum Provider { auto, tavily, tinyfish }
    public static final WebSearchSettings DEFAULTS = new WebSearchSettings(Provider.auto, Provider.auto, false, Map.of());

    public WebSearchSettings {
        if (searchProvider == null || fetchProvider == null || keys == null || keys.containsKey(Provider.auto)) {
            throw new IllegalArgumentException("联网服务配置无效");
        }
        keys = Map.copyOf(keys);
    }

    public boolean hasKey(Provider provider) { return keys.containsKey(provider); }
}
