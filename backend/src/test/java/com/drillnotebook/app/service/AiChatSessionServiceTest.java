package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.repository.AiChatSessionRepository;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class AiChatSessionServiceTest {
    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbc;
    private AiService service;

    @BeforeEach
    void setup() throws Exception {
        var root = Files.createTempDirectory("ai-session-service-test");
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        AiConfigRepository configs = new AiConfigRepository(jdbc);
        ChatLegacyWiring.Wiring wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        RetrievalService retrieval = new RetrievalService(
                new RetrievalIndexRepository(jdbc), new NotebookRepository(jdbc, mapper));
        service = new AiService(configs, wiring.sessions(), new ApiKeyEncryptor(), mapper, retrieval,
                wiring.turnService(), wiring.cipher(), null);

        service.saveConfig(Map.of(
                "provider", "mock",
                "endpoint", "mock://local",
                "model", "local-demo",
                "apiKey", "demo-key"
        ));
    }

    @Test
    void emptyListsReadsAndRestartsNeverCreatePlaceholderSessions() throws Exception {
        assertTrue(service.listSessions(false).isEmpty());
        assertTrue(service.listSessions(true).isEmpty());
        assertTrue(service.messages().isEmpty());
        new DatabaseInitializer(dataSource).initialize();
        assertTrue(service.listSessions(false).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session", Integer.class));
    }

    @Test
    void deletesLastSessionAndKeepsTheListEmptyAfterRestart() throws Exception {
        long id = ((Number) service.createSession(Map.of()).get("id")).longValue();
        service.deleteSession(id);
        assertTrue(service.listSessions(false).isEmpty());
        new DatabaseInitializer(dataSource).initialize();
        assertTrue(service.listSessions(false).isEmpty());
        assertTrue(service.messages().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session", Integer.class));
    }

    @Test
    void legacyClientCreatesDefaultSessionOnlyWhenSendingItsFirstMessage() {
        assertTrue(service.listSessions(false).isEmpty());
        var response = service.chat(Map.of("messages", List.of(Map.of("role", "user", "content", "第一次提问"))));
        assertTrue(String.valueOf(response.get("reply")).contains("本地演示回复"));
        assertEquals(1, service.listSessions(false).size());
        assertEquals(2, service.messages().size());
    }

    @Test
    void createsSessionsPersistsEncryptedChatAndExports() throws Exception {

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
