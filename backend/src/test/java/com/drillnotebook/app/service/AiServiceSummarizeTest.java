package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiChatSessionRepository;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.AiConfigRepository.ConfigRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiServiceSummarizeTest {

    private AiConfigRepository configs;
    private AiChatSessionRepository sessions;
    private ApiKeyEncryptor encryptor;
    private ObjectMapper mapper;
    private AiService ai;

    @BeforeEach
    void setup() {
        configs = mock(AiConfigRepository.class);
        sessions = mock(AiChatSessionRepository.class);
        encryptor = mock(ApiKeyEncryptor.class);
        mapper = new ObjectMapper();
        ai = new AiService(configs, sessions, encryptor, mapper);
    }

    @Test
    void summarizeKnowledgePointThrowsWhenNoKey() throws Exception {
        // configs.find() 返回 null → requireConfig 抛 IllegalArgumentException
        when(configs.find()).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> ai.summarizeKnowledgePoint("# 标题\n正文"));
    }

    @Test
    void summarizeKnowledgePointReturnsTrimmedMarkdownViaMockEndpoint() throws Exception {
        // 用 mock://local endpoint 走 mockReply 分支
        ConfigRow config = new ConfigRow(
                "custom",
                "mock://local",
                "mock-model",
                "fake-encrypted-key",
                "{\"mode\":\"fingerprint\",\"salt\":\"s\",\"iv\":\"i\"}",
                "{}");
        when(configs.find()).thenReturn(config);
        // encryptor.decrypt 返回任意非空 key 即可（4 参数：encrypted, salt, iv, material）
        when(encryptor.decrypt(anyString(), anyString(), anyString(), anyString())).thenReturn("fake-api-key");
        when(encryptor.fingerprintMaterial()).thenReturn("fake-fingerprint");

        String summary = ai.summarizeKnowledgePoint("# JVM\n内存模型详解...");

        // mockReply 对带 system role 的 messages 走通用分支，返回固定文本
        assertNotNull(summary, "summarizeKnowledgePoint 应返回非空字符串");
        assertFalse(summary.isBlank(), "summarizeKnowledgePoint 返回值不能为空");
        // 验证返回值已被 .trim() 处理（无首尾空白）
        assertEquals(summary.strip(), summary, "summarizeKnowledgePoint 返回值应已 trim");
    }

    @Test
    void summarizeMarkdownReturnsStandardFormatMarkdown() throws Exception {
        // 用 mock://local endpoint 走 mockReply 分支
        ConfigRow config = new ConfigRow(
                "custom",
                "mock://local",
                "mock-model",
                "fake-encrypted-key",
                "{\"mode\":\"fingerprint\",\"salt\":\"s\",\"iv\":\"i\"}",
                "{}");
        when(configs.find()).thenReturn(config);
        // encryptor.decrypt 返回任意非空 key 即可（4 参数：encrypted, salt, iv, material）
        when(encryptor.decrypt(anyString(), anyString(), anyString(), anyString())).thenReturn("fake-api-key");
        when(encryptor.fingerprintMaterial()).thenReturn("fake-fingerprint");

        String out = ai.summarizeMarkdown("# 第一章 JVM\n## 内存结构\n...长文...");

        // mockReply 对带 system role 的 messages 走通用分支，返回固定文本（不以 "## " 开头）
        assertNotNull(out, "summarizeMarkdown 应返回非空字符串");
        assertFalse(out.isBlank(), "summarizeMarkdown 返回值不能为空");
        // 验证返回值已被 .trim() 处理（无首尾空白）
        assertEquals(out.strip(), out, "summarizeMarkdown 返回值应已 trim");
    }
}
