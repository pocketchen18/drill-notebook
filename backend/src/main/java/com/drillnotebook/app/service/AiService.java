package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private final AiConfigRepository configs;
    private final ApiKeyEncryptor encryptor;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AiService(AiConfigRepository configs, ApiKeyEncryptor encryptor, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.configs = configs; this.encryptor = encryptor; this.mapper = mapper; this.jdbc = jdbc;
    }

    public Map<String, Object> redactedConfig() {
        AiConfigRepository.ConfigRow row = configs.find();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", row == null || row.provider() == null ? "custom" : row.provider());
        result.put("endpoint", row == null ? "" : row.endpoint());
        result.put("model", row == null ? "" : row.model());
        result.put("hasKey", row != null && row.encryptedKey() != null && !row.encryptedKey().isBlank());
        return result;
    }

    public Map<String, Object> saveConfig(Map<String, Object> body) {
        String provider = string(body, "provider", "custom");
        String endpoint = string(body, "endpoint", "");
        String model = string(body, "model", "");
        String apiKey = string(body, "apiKey", "");
        String masterPassword = string(body, "masterPassword", "");
        String encrypted = null;
        String metadata = null;
        if (!apiKey.isBlank()) {
            try {
                String mode = masterPassword.isBlank() ? "fingerprint" : "password";
                String material = masterPassword.isBlank() ? encryptor.fingerprintMaterial() : masterPassword;
                ApiKeyEncryptor.EncryptedValue value = encryptor.encrypt(apiKey, material, mode);
                encrypted = value.encrypted();
                metadata = mapper.writeValueAsString(Map.of("salt", value.salt(), "iv", value.iv(), "kdf", "Argon2id", "algorithm", "AES-256-GCM", "mode", value.mode()));
            } catch (Exception error) { throw new IllegalArgumentException("API Key 加密失败"); }
        }
        configs.upsert(provider, endpoint, model, encrypted, metadata, "{}");
        return redactedConfig();
    }

    public Map<String, Object> chat(Map<String, Object> body) {
        AiConfigRepository.ConfigRow config = requireConfig();
        List<Map<String, Object>> messages = messages(body.get("messages"));
        if (messages.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        String reply = call(config, messages, string(body, "masterPassword", ""));
        persist(messages.get(messages.size() - 1).get("role"), messages.get(messages.size() - 1).get("content"));
        persist("assistant", reply);
        return Map.of("reply", reply);
    }

    public Map<String, Object> summarize(Map<String, Object> body) {
        AiConfigRepository.ConfigRow config = requireConfig();
        String text = string(body, "text", "");
        if (text.isBlank()) throw new IllegalArgumentException("总结内容不能为空");
        String reply = call(config, List.of(Map.of("role", "system", "content", "总结以下学习内容"), Map.of("role", "user", "content", text)), string(body, "masterPassword", ""));
        return Map.of("summary", reply);
    }

    public List<Map<String, Object>> messages() {
        List<Map<String, Object>> result = jdbc.query("SELECT role, content, created_at FROM ai_chat_message ORDER BY id DESC LIMIT 100", (row, index) -> Map.of("role", row.getString("role"), "content", row.getString("content"), "createdAt", row.getString("created_at")));
        Collections.reverse(result);
        return result;
    }

    private AiConfigRepository.ConfigRow requireConfig() {
        AiConfigRepository.ConfigRow config = configs.find();
        if (config == null || config.encryptedKey() == null || config.encryptedKey().isBlank()) throw new IllegalArgumentException("请先配置 AI API Key");
        if (config.endpoint() == null || config.endpoint().isBlank()) throw new IllegalArgumentException("请先配置 AI Endpoint");
        return config;
    }

    private String call(AiConfigRepository.ConfigRow config, List<Map<String, Object>> messages, String masterPassword) {
        try {
            Map<String, Object> metadata = mapper.readValue(config.keyMeta(), new TypeReference<>() {});
            String mode = String.valueOf(metadata.getOrDefault("mode", "fingerprint"));
            String material = mode.equals("password") ? masterPassword : encryptor.fingerprintMaterial();
            if (material.isBlank()) throw new IllegalArgumentException("该 AI 配置需要主密码");
            String apiKey = encryptor.decrypt(config.encryptedKey(), String.valueOf(metadata.get("salt")), String.valueOf(metadata.get("iv")), material);
            try {
                if (config.endpoint().equalsIgnoreCase("mock://local")) return mockReply(messages);
                String endpoint = config.endpoint().replaceAll("/+$", "");
                String target = endpoint.endsWith("/chat/completions") ? endpoint : endpoint + "/chat/completions";
                Map<String, Object> request = new LinkedHashMap<>(); request.put("model", config.model()); request.put("messages", messages); request.put("stream", false);
                HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalArgumentException("AI 服务请求失败（HTTP " + response.statusCode() + "）");
                JsonNode root = mapper.readTree(response.body());
                String reply = root.path("choices").path(0).path("message").path("content").asText(null);
                if (reply == null || reply.isBlank()) throw new IllegalArgumentException("AI 服务返回内容为空");
                return reply;
            } finally { apiKey = null; }
        } catch (IllegalArgumentException error) { throw error; } catch (Exception error) { throw new IllegalArgumentException("AI 服务暂时不可用"); }
    }

    private String mockReply(List<Map<String, Object>> messages) {
        Object last = messages.get(messages.size() - 1).get("content");
        String text = contentText(last);
        if (messages.stream().anyMatch((message) -> "system".equals(message.get("role")))) return "本地演示总结：已提炼输入内容的主题、关键概念和下一步复习建议。\n\n**关键概念**\n\n- 先整理定义，再验证结论。\n- 公式示例：$E=mc^2$。\n\n原文长度：" + text.length() + " 字符。";
        return "本地演示回复：已收到“" + text + "”。你可以在设置中替换为 OpenAI 兼容 Endpoint。";
    }

    private void persist(Object role, Object content) {
        if (role == null || content == null) return;
        jdbc.update("INSERT INTO ai_chat_message(role, content) VALUES (?, ?)", String.valueOf(role), contentText(content));
        jdbc.update("DELETE FROM ai_chat_message WHERE id NOT IN (SELECT id FROM ai_chat_message ORDER BY id DESC LIMIT 100)");
    }

    private static List<Map<String, Object>> messages(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", map.get("role"));
            message.put("content", map.get("content"));
            result.add(message);
        }
        return result;
    }

    private String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String text) return text;
        try {
            JsonNode node = mapper.valueToTree(content);
            if (node.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : node) {
                    if (part.path("type").asText().equals("text")) text.append(part.path("text").asText()).append('\n');
                    if (part.path("type").asText().equals("image_url")) text.append("[图片附件]\n");
                }
                return text.toString().trim();
            }
        } catch (IllegalArgumentException ignored) { }
        return String.valueOf(content);
    }

    private static String string(Map<String, Object> body, String key, String fallback) { Object value = body.get(key); return value == null ? fallback : String.valueOf(value).trim(); }
}
