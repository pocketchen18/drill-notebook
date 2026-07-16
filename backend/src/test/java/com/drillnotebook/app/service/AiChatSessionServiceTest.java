package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.repository.AiChatSessionRepository;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class AiChatSessionServiceTest {
    @Test
    void createsSessionsPersistsEncryptedChatAndExports() throws Exception {
        var root = Files.createTempDirectory("ai-session-service-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        AiConfigRepository configs = new AiConfigRepository(jdbc);
        AiChatSessionRepository sessions = new AiChatSessionRepository(jdbc);
        AiService service = new AiService(configs, sessions, new ApiKeyEncryptor(), mapper);

        service.saveConfig(Map.of(
                "provider", "mock",
                "endpoint", "mock://local",
                "model", "local-demo",
                "apiKey", "demo-key"
        ));

        Map<String, Object> session = service.createSession(Map.of("title", "JVM 复习"));
        long sessionId = ((Number) session.get("id")).longValue();
        Map<String, Object> chat = service.chat(Map.of(
                "sessionId", sessionId,
                "messages", List.of(Map.of("role", "user", "content", "解释垃圾回收"))
        ));
        assertTrue(String.valueOf(chat.get("reply")).contains("本地演示回复"));

        List<Map<String, Object>> messages = service.sessionMessages(sessionId, "");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));

        Integer cipherCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ? AND content_cipher IS NOT NULL AND content = ''",
                Integer.class,
                sessionId);
        assertEquals(2, cipherCount);

        Map<String, Object> exported = service.exportSession(sessionId, "md", "");
        assertEquals("md", exported.get("format"));
        assertTrue(String.valueOf(exported.get("content")).contains("解释垃圾回收"));
        assertTrue(String.valueOf(exported.get("content")).contains("#### 你:"));

        Map<String, Object> second = service.createSession(Map.of("title", "可删会话"));
        service.deleteSession(((Number) second.get("id")).longValue());
        assertFalse(service.listSessions(true).stream().anyMatch((item) -> "可删会话".equals(item.get("title"))));
        assertTrue(service.listSessions(false).stream().anyMatch((item) ->
                "JVM 复习".equals(item.get("title")) || String.valueOf(item.get("title")).contains("解释")));
    }
}
