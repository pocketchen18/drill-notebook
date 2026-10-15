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
import com.drillnotebook.app.service.memory.MemoryClearService;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryReviewService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        MemoryReviewService reviews = new MemoryReviewService(crypto, memory,
                new MemoryProposalRepository(dataSource, immediate),
                new MemorySuppressionRepository(dataSource, immediate), operations, vault, policy,
                new MemoryJobRepository(dataSource, immediate), immediate, new ObjectMapper());
        controller = new MemoryController(vault, policy, items, reviews,
                new MemoryClearService(memory, policy, metadata, operations, new ObjectMapper()));
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    /**
     * §3 把建议列表定成 GET，筛选条件只能挂在查询串上；能进 URL 的只有<b>非内容</b>参数。
     * 密码、会话密码、搜索词、正文一律只走请求体——这条纪律由下面的白名单守，而不是靠"一个 @RequestParam 都不许有"。
     */
    private static final Set<String> ALLOWED_QUERY_PARAMETERS = Set.of("scopeType", "scopeId", "limit", "cursor");

    @Test
    void onlyNonContentFiltersMayTravelInTheQueryString() {
        for (Method method : MemoryController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                if (annotation == null) {
                    continue;
                }
                // @RequestParam 的 value/name 是一对别名，但必须显式写一个：靠反射参数名猜，白名单就审不动了
                String name = annotation.value().isEmpty() ? annotation.name() : annotation.value();
                assertFalse(name.isEmpty(), method.getName() + " 的查询参数必须显式命名");
                assertTrue(ALLOWED_QUERY_PARAMETERS.contains(name),
                        method.getName() + " 把 " + name + " 放进 URL：只有非内容筛选条件可以");
            }
        }
    }

    @Test
    void reviewRouteTakesItsDecisionAndVersionsFromTheBody() {
        ResponseEntity<Map<String, Object>> missing = controller.reviewProposal("prop-1",
                Map.of("decision", "accept"));
        assertEquals(400, missing.getStatusCode().value(), "缺 expectedVersion 直接拒（§3）");
        assertEquals("MEMORY_VERSION_REQUIRED", missing.getBody().get("code"));

        assertEquals(400, controller.reviewProposal("prop-1", Map.of("expectedVersion", 1, "decision", "accept"))
                .getStatusCode().value(), "采纳必须带上你看到的当前值版本");

        ResponseEntity<Map<String, Object>> unknown = controller.reviewProposal("prop-1",
                Map.of("expectedVersion", 1, "expectedTargetVersion", 1, "decision", "accept"));
        assertEquals(404, unknown.getStatusCode().value());
        assertEquals("MEMORY_PROPOSAL_NOT_FOUND", unknown.getBody().get("code"));

        assertEquals(400, controller.reviewProposal("prop-1", Map.of("expectedVersion", 1,
                "expectedTargetVersion", 1, "decision", "approve")).getStatusCode().value(),
                "词表外的决定不进事务");
    }

    @Test
    void itemReviewRouteRefusesBeforeItHasAVersionOrATarget() {
        ResponseEntity<Map<String, Object>> missing = controller.reviewItem("mem-1", Map.of("decision", "accept"));
        assertEquals(400, missing.getStatusCode().value(), "候选审阅同样必须带 expectedVersion（§3）");
        assertEquals("MEMORY_VERSION_REQUIRED", missing.getBody().get("code"));

        assertEquals("MEMORY_DECISION_INVALID", controller.reviewItem("mem-1", Map.of("expectedVersion", 1))
                .getBody().get("code"), "没有决定就没有可执行的动作");

        ResponseEntity<Map<String, Object>> replace = controller.reviewItem("mem-1",
                Map.of("expectedVersion", 1, "decision", "replace"));
        assertEquals(400, replace.getStatusCode().value());
        assertEquals("MEMORY_TARGET_REQUIRED", replace.getBody().get("code"),
                "替换必须点名旧目标，不能由服务端猜");
    }

    @Test
    void proposalsListRefusesToServeWithoutUnlockAndRejectsBogusFilters() {
        assertEquals(400, controller.proposals("SESSION", 3L, null, null).getStatusCode().value(),
                "作用域只认 GLOBAL/NOTEBOOK，不给隐式当前会话留后门");
        assertEquals("MEMORY_SCOPE_INVALID", controller.proposals("SESSION", 3L, null, null).getBody().get("code"));
        assertEquals("MEMORY_SCOPE_INVALID", controller.proposals("NOTEBOOK", null, null, null).getBody().get("code"));

        ResponseEntity<Map<String, Object>> locked = controller.proposals(null, null, null, null);
        assertEquals(423, locked.getStatusCode().value(), "没建库/没解锁时列表要 423，不能回一个看起来像"
                + "“没有建议”的空数组");
        assertEquals("MEMORY_LOCKED", locked.getBody().get("code"));
        assertEquals(true, locked.getBody().get("retryable"));
    }

    @Test
    void clearRouteNeedsConfirmationAndTheEpochItSaw() {
        ResponseEntity<Map<String, Object>> unconfirmed = controller.clear(Map.of());
        assertEquals(409, unconfirmed.getStatusCode().value(), "缺确认不得放行（§12：清空要确认）");
        assertEquals("MEMORY_CLEAR_CONFIRMATION_REQUIRED", unconfirmed.getBody().get("code"));

        assertEquals("MEMORY_EPOCH_REQUIRED",
                controller.clear(Map.of("confirm", true)).getBody().get("code"),
                "确认与 expectedVaultEpoch 都要在请求体里");

        ResponseEntity<Map<String, Object>> noVault = controller.clear(Map.of("confirm", true,
                "expectedVaultEpoch", 1));
        assertEquals(409, noVault.getStatusCode().value());
        assertEquals("MEMORY_VAULT_NOT_INITIALIZED", noVault.getBody().get("code"),
                "还没有库就说没有可清空的内容，不报 500");
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
