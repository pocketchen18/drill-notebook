package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drillnotebook.app.repository.AiConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiServiceStructuredCallTest {
    private final AiService service = new AiService(null, null, null, new ObjectMapper(), null);

    @Test
    void structuredCallDisablesThinkingForQwenDashScope() {
        AiConfigRepository.ConfigRow config = new AiConfigRepository.ConfigRow(
                "custom",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.6-27b",
                "enc",
                "{}",
                "{}"
        );
        Map<String, Object> request = service.buildChatCompletionRequest(
                config,
                List.of(Map.of("role", "user", "content", "hi")),
                new AiService.CallOptions(300, 32000, true)
        );
        assertEquals(false, request.get("enable_thinking"));
        assertEquals(32000, request.get("max_tokens"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kwargs = (Map<String, Object>) request.get("chat_template_kwargs");
        assertEquals(false, kwargs.get("enable_thinking"));
    }

    @Test
    void defaultChatDoesNotForceThinkingToggle() {
        AiConfigRepository.ConfigRow config = new AiConfigRepository.ConfigRow(
                "custom",
                "https://api.openai.com/v1",
                "gpt-4o-mini",
                "enc",
                "{}",
                "{}"
        );
        Map<String, Object> request = service.buildChatCompletionRequest(
                config,
                List.of(Map.of("role", "user", "content", "hi")),
                new AiService.CallOptions(180, 4096, false)
        );
        assertFalse(request.containsKey("enable_thinking"));
        assertEquals(4096, request.get("max_tokens"));
    }

    @Test
    void supportsThinkingToggleDetectsDashScopeAndQwen() {
        assertTrue(AiService.supportsThinkingToggle(new AiConfigRepository.ConfigRow(
                "c", "https://dashscope.aliyuncs.com/compatible-mode/v1", "x", null, null, null)));
        assertTrue(AiService.supportsThinkingToggle(new AiConfigRepository.ConfigRow(
                "c", "https://api.example.com/v1", "qwen3.6-max-preview", null, null, null)));
        assertFalse(AiService.supportsThinkingToggle(new AiConfigRepository.ConfigRow(
                "c", "https://api.openai.com/v1", "gpt-4o", null, null, null)));
    }

    @Test
    void extractMessageContentIgnoresReasoningOnly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("hello", AiService.extractMessageContent(mapper.readTree("{\"content\":\"hello\",\"reasoning_content\":\"think\"}")));
        assertEquals("", AiService.extractMessageContent(mapper.readTree("{\"content\":\"\",\"reasoning_content\":\"only thinking\"}")));
        assertEquals("part", AiService.extractMessageContent(mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"part\"}]}")));
    }

    @Test
    void httpErrorMessageSurfacesAuthDetails() {
        String message = AiService.httpErrorMessage(403, "{\"code\":\"AccessDenied\",\"message\":\"Model access denied\"}");
        assertTrue(message.contains("403"));
        assertTrue(message.contains("API Key") || message.contains("权限"));
        assertTrue(message.contains("AccessDenied") || message.contains("denied"));
    }

    @Test
    void writePlanNoteRejectsBlankTitles() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.writePlanNote(Map.of("titles", List.of(), "sessionType", "quiz")));
        assertEquals("候选标题不能为空", error.getMessage());

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> service.writePlanNote(Map.of("sessionType", "quiz")));
        assertEquals("候选标题不能为空", missing.getMessage());
    }

    @Test
    void parseKnowledgePointsFromTextForwardsLevelFromJsonReply() throws Exception {
        AiConfigRepository configs = mock(AiConfigRepository.class);
        ApiKeyEncryptor encryptor = mock(ApiKeyEncryptor.class);
        AiService ai = new AiService(configs, null, encryptor, new ObjectMapper(), mock(RetrievalService.class));
        when(configs.find(AiConfigRepository.PURPOSE_IMPORT)).thenReturn(new AiConfigRepository.ConfigRow(
                "custom",
                "mock://local",
                "mock-model",
                "fake-encrypted-key",
                "{\"mode\":\"fingerprint\",\"salt\":\"s\",\"iv\":\"i\"}",
                "{}"));
        when(encryptor.decrypt(anyString(), anyString(), anyString(), anyString())).thenReturn("fake-api-key");
        when(encryptor.fingerprintMaterial()).thenReturn("fake-fingerprint");

        List<Map<String, Object>> points = ai.parseKnowledgePointsFromText("# 父章节\n正文\n## 子章节\n正文");

        assertEquals(2, points.size());
        // 回复含 "level":2 时必须原样透传
        assertEquals(2, points.get(0).get("level"));
        // 缺省 level 时安全回退为 1
        assertEquals(1, points.get(1).get("level"));
    }
}
