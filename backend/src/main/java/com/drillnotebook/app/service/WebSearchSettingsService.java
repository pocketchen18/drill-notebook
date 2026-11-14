package com.drillnotebook.app.service;

import com.drillnotebook.app.model.WebSearchSettings;
import com.drillnotebook.app.model.WebSearchSettings.Provider;
import com.drillnotebook.app.repository.WebSearchSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class WebSearchSettingsService {
    private final WebSearchSettingsRepository repository;
    private final ApiKeyEncryptor encryptor;

    public WebSearchSettingsService(WebSearchSettingsRepository repository, ApiKeyEncryptor encryptor) {
        this.repository = repository;
        this.encryptor = encryptor;
    }

    public WebSearchSettingsRepository.Snapshot read() { return repository.read(); }

    public Map<String, Object> view(WebSearchSettingsRepository.Snapshot snapshot) {
        var value = snapshot.values();
        return Map.of("revision", snapshot.revision(), "searchProvider", value.searchProvider().name(),
                "fetchProvider", value.fetchProvider().name(), "preferNative", value.preferNative(),
                "providers", Map.of("tavily", Map.of("hasKey", value.hasKey(Provider.tavily)),
                        "tinyfish", Map.of("hasKey", value.hasKey(Provider.tinyfish))));
    }

    public Map<String, Object> save(JsonNode body) {
        if (!body.path("revision").isIntegralNumber() || !body.path("revision").canConvertToLong()) {
            throw new IllegalArgumentException("请提供联网设置版本");
        }
        var current = read();
        return view(repository.save(body.path("revision").longValue(), draft(current.values(), body)));
    }

    /** Blank input preserves an existing key. Only an explicit clear action removes it. */
    public WebSearchSettings draft(WebSearchSettings current, JsonNode body) {
        if (!body.isObject()) throw new IllegalArgumentException("联网配置须为对象");
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of("revision", "searchProvider", "fetchProvider", "preferNative", "keys", "capability").contains(name)) {
                throw new IllegalArgumentException("联网配置包含未知字段");
            }
        });
        Provider search = provider(body, "searchProvider", current.searchProvider());
        Provider fetch = provider(body, "fetchProvider", current.fetchProvider());
        boolean nativeFirst = current.preferNative();
        if (body.has("preferNative")) {
            if (!body.path("preferNative").isBoolean()) throw new IllegalArgumentException("内置联网优先须为布尔值");
            nativeFirst = body.path("preferNative").booleanValue();
        }
        var keys = new LinkedHashMap<>(current.keys());
        if (body.has("keys")) {
            if (!body.path("keys").isObject()) throw new IllegalArgumentException("密钥配置须为对象");
            body.path("keys").fields().forEachRemaining(entry -> {
                Provider name;
                try { name = Provider.valueOf(entry.getKey()); }
                catch (RuntimeException error) { throw new IllegalArgumentException("密钥供应商无效"); }
                if (name == Provider.auto || !entry.getValue().isObject()) throw new IllegalArgumentException("密钥配置无效");
                var edit = entry.getValue();
                edit.fieldNames().forEachRemaining(field -> {
                    if (!Set.of("apiKey", "clear").contains(field)) throw new IllegalArgumentException("密钥配置包含未知字段");
                });
                if (edit.has("clear") && !edit.path("clear").isBoolean()) throw new IllegalArgumentException("删除密钥标记无效");
                if (edit.has("apiKey") && !edit.path("apiKey").isTextual()) throw new IllegalArgumentException("API Key 须为文本");
                String plaintext = edit.path("apiKey").asText("").trim();
                if (edit.path("clear").asBoolean(false)) {
                    if (!plaintext.isEmpty()) throw new IllegalArgumentException("不能同时删除和修改密钥");
                    keys.remove(name);
                } else if (!plaintext.isEmpty()) {
                    if (plaintext.length() > 4096 || plaintext.chars().anyMatch(ch -> ch < 33 || ch > 126)) {
                        throw new IllegalArgumentException("API Key 长度或格式无效");
                    }
                    try { keys.put(name, encryptor.encrypt(plaintext, encryptor.fingerprintMaterial(), "fingerprint")); }
                    catch (Exception error) { throw new IllegalStateException("API Key 无法加密保存"); }
                }
            });
        }
        var result = new WebSearchSettings(search, fetch, nativeFirst, keys);
        for (Provider provider : java.util.List.of(search, fetch)) {
            if (provider != Provider.auto && !result.hasKey(provider)) {
                throw new IllegalArgumentException(provider == Provider.tavily ? "请配置 Tavily API Key" : "请配置 TinyFish API Key");
            }
        }
        return result;
    }

    public String key(WebSearchSettings values, Provider provider) {
        var key = values.keys().get(provider);
        if (key == null) throw new IllegalStateException("所选联网服务尚未配置 API Key");
        try { return encryptor.decrypt(key.encrypted(), key.salt(), key.iv(), encryptor.fingerprintMaterial()); }
        catch (Exception error) { throw new IllegalStateException("联网服务密钥无法解锁，请在工具设置中重新配置"); }
    }

    private static Provider provider(JsonNode body, String field, Provider fallback) {
        if (!body.has(field)) return fallback;
        if (!body.path(field).isTextual()) throw new IllegalArgumentException("联网服务选择无效");
        try { return Provider.valueOf(body.path(field).textValue()); }
        catch (RuntimeException error) { throw new IllegalArgumentException("联网服务选择无效"); }
    }
}
