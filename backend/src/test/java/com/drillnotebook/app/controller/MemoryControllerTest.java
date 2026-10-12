package com.drillnotebook.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 记忆 REST 的映射层（contracts §3）：状态码与错误形状由服务决定，控制器只负责拆请求。
 * 这里盯住三条不能退化的规矩：密码只能进请求体、204 不带货、错误响应不泄露提交的材料。
 */
class MemoryControllerTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String VALUE = "以后解释算法先给例子";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryRepository memory;
    private MemoryController controller;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-controller-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        memory = new MemoryRepository(dataSource, immediate);
        MemoryCryptoService crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        MemoryOperationRepository operations = new MemoryOperationRepository(dataSource, immediate);
        ChatSessionMetadataRepository metadata = new ChatSessionMetadataRepository(dataSource, immediate);
        MemoryVaultService vault = new MemoryVaultService(crypto, memory, operations, metadata);
        MemoryPolicyService policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate),
                memory, new MemoryJobRepository(dataSource, immediate), new AiConfigRepository(jdbc),
                new NotebookRepository(jdbc, new ObjectMapper()), vault);
        MemoryItemService items = new MemoryItemService(crypto, memory, vault, policy, operations,
                new MemoryProposalRepository(dataSource, immediate),
                new MemorySuppressionRepository(dataSource, immediate),
                new MemoryJobRepository(dataSource, immediate), metadata);
        controller = new MemoryController(vault, policy, items);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void noRouteTakesAnythingFromTheQueryString() {
        for (Method method : MemoryController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                assertFalse(parameter.isAnnotationPresent(RequestParam.class),
                        method.getName() + " 用了查询参数：密码与个人内容只能进请求体（§3）");
            }
        }
    }

    @Test
    void vaultLifecycleMapsToDocumentedStatusCodes() throws Exception {
        ResponseEntity<Map<String, Object>> tooEarly = controller.unlock(Map.of("password", PASSWORD,
                "sessionUnlocks", List.of()));
        assertEquals(409, tooEarly.getStatusCode().value(), "还没建库时解锁只能是未初始化");
        assertEquals("MEMORY_VAULT_NOT_INITIALIZED", tooEarly.getBody().get("code"));

        ResponseEntity<Map<String, Object>> created = controller.createVault(Map.of("mode", "password",
                "password", PASSWORD, "operationId", "op-1"));
        assertEquals(201, created.getStatusCode().value());
        assertFalse(String.valueOf(created.getBody()).contains(PASSWORD), "响应不回主密码");
        assertFalse(created.getBody().containsKey("wrappedDek"));
        assertTrue(memory.readEnvelope().orElseThrow().wrapMeta().contains("Argon2id"));

        ResponseEntity<Map<String, Object>> badUnlock = controller.unlock(Map.of("password", "错的密码"));
        assertEquals(401, badUnlock.getStatusCode().value());
        assertEquals("MEMORY_UNLOCK_FAILED", badUnlock.getBody().get("code"));
        assertFalse(String.valueOf(badUnlock.getBody()).contains("错的密码"));

        assertEquals(200, controller.unlock(Map.of("password", PASSWORD)).getStatusCode().value());
        ResponseEntity<Map<String, Object>> locked = controller.lock();
        assertEquals(204, locked.getStatusCode().value());
        assertNull(locked.getBody(), "204 不带货");
        assertEquals(200, controller.unlock(Map.of("password", PASSWORD)).getStatusCode().value());

        assertEquals(200, controller.changePassword(Map.of("oldPassword", PASSWORD,
                "password", "新的主密码")).getStatusCode().value());
        assertEquals(409, controller.resetVault(Map.of("mode", "fingerprint", "confirm", "false"))
                .getStatusCode().value(), "没确认的重置不能动现有库");
        assertEquals(200, controller.resetVault(Map.of("confirm", true, "mode", "fingerprint"))
                .getStatusCode().value());
        assertEquals("fingerprint", memory.readEnvelope().orElseThrow().mode());
    }

    @Test
    void settingsAndConsentsKeepRevisionAndGrantSeparation() {
        ResponseEntity<Map<String, Object>> settings = controller.settings();
        assertEquals(200, settings.getStatusCode().value());
        assertEquals(1L, settings.getBody().get("revision"));
        assertEquals(400, controller.updateSettings(Map.of("enabled", true)).getStatusCode().value(),
                "缺 revision 直接拒");
        assertEquals(400, controller.updateSettings(Map.of()).getStatusCode().value());

        ResponseEntity<Map<String, Object>> updated = controller.updateSettings(
                Map.of("revision", 1, "enabled", true, "autoExtract", true));
        assertEquals(200, updated.getStatusCode().value());
        assertEquals(2L, updated.getBody().get("revision"));

        assertEquals(400, controller.grantConsent(Map.of("purpose", "MEMORY_USE")).getStatusCode().value());
        assertEquals(409, controller.grantConsent(Map.of("purpose", "MEMORY_USE", "targetFingerprint", "fp",
                "policyVersion", MemoryPolicyService.POLICY_VERSION)).getStatusCode().value(), "主模型没配就不能授权");

        ResponseEntity<Map<String, Object>> revoked = controller.revokeConsent("MEMORY_USE");
        assertEquals(204, revoked.getStatusCode().value());
        assertEquals(400, controller.revokeConsent("MEMORY_EXPORT").getStatusCode().value());
    }

    @Test
    void itemRoutesRoundTripThroughTheController() throws Exception {
        controller.createVault(Map.of("mode", "fingerprint"));

        ResponseEntity<Map<String, Object>> created = controller.addItem(Map.of("kind", "PREFERENCE",
                "scopeType", "GLOBAL", "predicate", "answer.style", "value", VALUE));
        assertEquals(201, created.getStatusCode().value());
        String memoryId = (String) created.getBody().get("memoryId");

        assertEquals(200, controller.getItem(memoryId).getStatusCode().value());
        assertEquals(VALUE, controller.getItem(memoryId).getBody().get("value"));
        assertEquals(422, controller.addItem(Map.of("kind", "BACKGROUND", "scopeType", "GLOBAL",
                "predicate", "contact.key", "value", "api key: sk-abcdefghijklmnopqrstuvwxyz")).getStatusCode()
                .value());
        assertEquals(400, controller.addItem(Map.of("kind", "NOTE", "scopeType", "GLOBAL", "predicate", "p",
                "value", "v")).getStatusCode().value());

        ResponseEntity<Map<String, Object>> patched = controller.editItem(memoryId,
                Map.of("expectedVersion", 1, "value", "先给反例"));
        assertEquals(200, patched.getStatusCode().value());
        assertEquals(2, patched.getBody().get("version"));
        assertEquals(409, controller.editItem(memoryId, Map.of("expectedVersion", 1, "value", "又改一次"))
                .getStatusCode().value());

        assertEquals(1, ((List<?>) controller.search(Map.of("query", "反例", "limit", 10)).getBody().get("items"))
                .size());
        assertEquals(400, controller.search(Map.of("status", "DONE")).getStatusCode().value());

        assertEquals("DISABLED", controller.editItem(memoryId, Map.of("expectedVersion", 2, "status", "DISABLED"))
                .getBody().get("status"));
        assertEquals(1, ((List<?>) controller.search(Map.of("status", "DISABLED", "limit", 10)).getBody()
                .get("items")).size());

        assertEquals(204, controller.deleteItem(memoryId, Map.of("expectedVersion", 2, "forget", true,
                "operationId", "op-x")).getStatusCode().value(), "停用中的记忆也要能删掉");
        assertEquals(0L, count("ai_memory_item"));
        assertTrue(controller.status().getBody().toString().contains("AUTO_EXTRACT_DISABLED")
                || controller.status().getBody().toString().contains("MEMORY_DISABLED"));
    }

    @Test
    void searchMatchesDecryptedTextThroughThePostRoute() {
        controller.createVault(Map.of("mode", "fingerprint"));
        controller.addItem(Map.of("kind", "PREFERENCE", "scopeType", "GLOBAL", "predicate", "answer.style",
                "value", "我在准备雅思"));

        ResponseEntity<Map<String, Object>> found = controller.search(Map.of("query", "雅思", "limit", 10));

        assertEquals(200, found.getStatusCode().value());
        assertEquals(1, ((List<?>) found.getBody().get("items")).size());
        assertEquals("我在准备雅思", ((Map<?, ?>) ((List<?>) found.getBody().get("items")).get(0)).get("value"));
        assertEquals(0, ((List<?>) controller.search(Map.of("query", "游泳", "limit", 10)).getBody().get("items"))
                .size());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
