package com.drillnotebook.app.service.memory;

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
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySettingsRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryPolicyService.Gate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 开关、外发授权与写入资格（执行方案 §5.1、§5.2、§6.2）。核心口径：
 * 改开关不等于授权；授权凭的是当时解析出的真实目标，目标一变即失效；
 * dedicated 模式缺配置就暂停，绝不悄悄换回主模型；手动录入只要解锁，自动新增受上限约束。
 */
class MemoryPolicyServiceTest {
    private static final String CHAT_ENDPOINT = "https://api.example.com/v1";
    private static final String OTHER_ENDPOINT = "https://api.other.example/v1";
    private static final String CHAT_MODEL = "gpt-test";
    private static final String USE = MemoryPolicyService.PURPOSE_USE;
    private static final String MAINT = MemoryPolicyService.PURPOSE_MAINTENANCE;
    private static final String VERSION = MemoryPolicyService.POLICY_VERSION;

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ImmediateTransaction immediate;
    private MemoryRepository memory;
    private MemoryJobRepository jobs;
    private MemoryVaultService vault;
    private MemoryPolicyService policy;
    private String vaultId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-policy-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        immediate = new ImmediateTransaction(dataSource);
        memory = new MemoryRepository(dataSource, immediate);
        jobs = new MemoryJobRepository(dataSource, immediate);
        MemoryCryptoService crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        vault = new MemoryVaultService(crypto, memory, new MemoryOperationRepository(dataSource, immediate),
                new ChatSessionMetadataRepository(dataSource, immediate));
        policy = new MemoryPolicyService(new MemorySettingsRepository(dataSource, immediate), memory, jobs,
                new AiConfigRepository(jdbc), new NotebookRepository(jdbc, new ObjectMapper()), vault);
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    // ---- 初始状态 ----

    @Test
    void freshSettingsAreOffAndUnconsentedEvenWithAChatModel() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);

        Map<String, Object> body = policy.snapshot().body();
        assertEquals(200, policy.snapshot().status());
        assertEquals(1L, body.get("revision"));
        assertEquals(Boolean.FALSE, body.get("enabled"));
        assertEquals(Boolean.FALSE, body.get("autoExtract"));
        assertEquals(Boolean.FALSE, body.get("compressSession"));
        assertEquals(Boolean.TRUE, body.get("autoApplyLowRisk"));
        assertEquals(VERSION, body.get("policyVersion"));
        assertEquals("chat", body.get("maintenanceModelMode"));
        assertEquals(20, body.get("dailyCallLimit"));
        assertEquals(30000, body.get("dailyTokenLimit"));
        assertEquals(Boolean.FALSE, body.get("initialized"), "设置行不替用户建库");
        assertFalse(consent(USE, body).granted(), "复用聊天设置不自动继承外发授权（§5.1）");
        assertFalse(consent(MAINT, body).granted());
        assertEquals("MISSING", consent(USE, body).state());
        assertEquals("api.example.com", consent(USE, body).target().host());
        assertEquals(2000, ((Map<?, ?>) body.get("limits")).get("activeFacts"));
    }

    @Test
    void targetMissingUntilTheSlotHasEndpointAndModel() throws Exception {
        MemoryPolicyService.ConsentStatus status = policy.consentStatus(USE);
        assertEquals("TARGET_MISSING", status.state());
        assertFalse(status.target().configured());
        assertEquals(200, policy.snapshot().status());

        seedChat(CHAT_ENDPOINT, "  ", null);
        assertFalse(policy.consentStatus(USE).target().configured(), "空模型名不算配好");
    }

    // ---- 开关与版本 ----

    @Test
    void switchWritesNeverGrantConsent() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        long revision = policy.current().revision();

        assertEquals(200, policy.update(revision, true, true, null, null, null).status());

        Map<String, Object> body = policy.snapshot().body();
        assertEquals(Boolean.TRUE, body.get("enabled"));
        assertEquals(Boolean.TRUE, body.get("autoExtract"));
        assertFalse(consent(USE, body).granted());
        assertFalse(consent(MAINT, body).granted());
        assertNull(consent(USE, body).storedFingerprint());
    }

    @Test
    void staleRevisionIsRefusedWithTheCurrentOne() throws Exception {
        long revision = policy.current().revision();
        assertEquals(200, policy.update(revision, true, null, null, null, null).status());

        MemoryApiResult conflict = policy.update(revision, false, null, null, null, null);
        assertEquals(409, conflict.status());
        assertEquals("MEMORY_SETTINGS_CONFLICT", conflict.body().get("code"));
        assertEquals(revision + 1, ((Map<?, ?>) conflict.body().get("details")).get("revision"));
        assertEquals(Boolean.TRUE, policy.current().enabled(), "撞版本的写入没生效");

        assertEquals(400, policy.update(null, true, null, null, null, null).status());
    }

    @Test
    void policyKeysAreWhitelistedAndBounded() throws Exception {
        long revision = policy.current().revision();

        for (String key : List.of("memoryHistoryFloor", "policyVersion", "autoExtract")) {
            MemoryApiResult refusal = policy.update(revision, null, null, null, null, Map.of(key, 1));
            assertEquals(400, refusal.status(), key + " 不该能被客户端改");
            assertEquals("MEMORY_POLICY_KEY_READONLY", refusal.body().get("code"));
        }
        assertEquals("MEMORY_MAINTENANCE_MODE_INVALID",
                policy.update(revision, null, null, null, null, Map.of("maintenanceModelMode", "import"))
                        .body().get("code"));
        assertEquals("MEMORY_DAILY_CALL_LIMIT_INVALID",
                policy.update(revision, null, null, null, null, Map.of("dailyCallLimit", 0)).body().get("code"));
        assertEquals("MEMORY_DAILY_TOKEN_LIMIT_INVALID",
                policy.update(revision, null, null, null, null, Map.of("dailyTokenLimit", 999)).body().get("code"));
        assertEquals("MEMORY_TIMEZONE_INVALID",
                policy.update(revision, null, null, null, null, Map.of("budgetTimezone", "Not/AZone"))
                        .body().get("code"));
        assertEquals(revision, policy.current().revision(), "被拒的写入不推版本");

        assertEquals(200, policy.update(revision, null, null, null, null,
                Map.of("maintenanceModelMode", "dedicated", "dailyCallLimit", 5, "budgetTimezone",
                        "Europe/Berlin")).status());
        MemoryPolicyService.Policy updated = policy.policyOf(policy.current());
        assertEquals("dedicated", updated.maintenanceModelMode());
        assertEquals(5, updated.dailyCallLimit());
        assertEquals("Europe/Berlin", updated.budgetTimezone());
        assertEquals(30000, updated.dailyTokenLimit(), "没在补丁里的键保持原值");
    }

    // ---- 授权 ----

    @Test
    void consentNeedsTheRealTargetFingerprint() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);

        MemoryApiResult wrong = policy.grantConsent(USE, "deadbeef", VERSION);
        assertEquals(409, wrong.status());
        assertEquals("MEMORY_CONSENT_TARGET_MISMATCH", wrong.body().get("code"));
        Map<?, ?> details = (Map<?, ?>) wrong.body().get("details");
        assertEquals("api.example.com", details.get("host"));
        assertEquals(CHAT_MODEL, details.get("model"));
        assertEquals(VERSION, details.get("policyVersion"));
        assertFalse(policy.consentStatus(USE).granted());

        String real = policy.targetOf(USE).fingerprint();
        assertEquals(409, policy.grantConsent(USE, real, "memory-policy-v0").status());
        assertEquals("MEMORY_POLICY_VERSION_STALE",
                policy.grantConsent(USE, real, "memory-policy-v0").body().get("code"));
        assertEquals(400, policy.grantConsent(USE, " ", VERSION).status());
        assertEquals(400, policy.grantConsent("MEMORY_EXPORT", real, VERSION).status());

        MemoryApiResult granted = policy.grantConsent(USE, real, VERSION);
        assertEquals(200, granted.status());
        assertTrue(policy.consentStatus(USE).granted());
        assertEquals("GRANTED", policy.consentStatus(USE).state());
        assertFalse(policy.consentStatus(MAINT).granted(), "两种授权各自独立");
    }

    @Test
    void consentExpiresWhenTheTargetOrFormatChanges() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        assertEquals(201, vault.create("fingerprint", null, null).status(), "注入侧要求记忆库已解锁");
        grantUseConsent();
        switches(true, false, false);
        assertTrue(policy.useEligibility().allowed());

        seedChat(OTHER_ENDPOINT, CHAT_MODEL, null);
        assertEquals("STALE", policy.consentStatus(USE).state());
        assertEquals(Gate.CONSENT_STALE, policy.useEligibility().gate());

        seedChat(CHAT_ENDPOINT, "other-model", null);
        assertEquals("STALE", policy.consentStatus(USE).state(), "换模型即失效");

        seedChat(CHAT_ENDPOINT, CHAT_MODEL, "{\"apiFormat\":\"anthropic\",\"streaming\":true}");
        assertEquals("STALE", policy.consentStatus(USE).state(), "换 API 格式也换接收方式");
        assertEquals("anthropic", policy.targetOf(USE).apiFormat());

        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        grantUseConsent();
        seedChat(CHAT_ENDPOINT + "/", CHAT_MODEL, null);
        assertTrue(policy.consentStatus(USE).granted(), "归一化后同一个目标不该因为尾部斜杠就失效");

        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        jdbc.update("UPDATE ai_config SET encrypted_key = 'another-cipher' WHERE purpose = 'chat'");
        assertTrue(policy.consentStatus(USE).granted(), "换同 endpoint 的 key 不改变接收方（§5.1）");
    }

    @Test
    void dedicatedModeNeverFallsBackToTheChatModel() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        setMaintenanceMode("dedicated");

        MemoryPolicyService.Target target = policy.targetOf(MAINT);
        assertFalse(target.configured(), "dedicated 只认 memory 槽位，不能拿主模型顶上");
        assertEquals("memory", target.slot());
        assertEquals("TARGET_MISSING", policy.consentStatus(MAINT).state());
        assertTrue(policy.targetOf(USE).configured(), "使用侧仍按主模型解析");

        jdbc.update("INSERT OR REPLACE INTO ai_config(purpose, provider, endpoint, model, encrypted_key, key_meta,"
                + " params) VALUES ('memory','custom',?,'small-model','cipher','{}',NULL)", OTHER_ENDPOINT);
        MemoryPolicyService.ConsentStatus status = policy.consentStatus(MAINT);
        assertEquals("MISSING", status.state());
        assertEquals("small-model", status.target().model());

        grantMaintConsent();
        assertTrue(policy.consentStatus(MAINT).granted());

        setMaintenanceMode("chat");
        assertEquals("STALE", policy.consentStatus(MAINT).state(), "切回主模型要重新确认实际目标");
    }

    @Test
    void revokingMaintenanceConsentSupersedesQueuedJobs() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        grantMaintConsent();
        long revision = policy.current().revision();
        String jobId = jobs.enqueue(new MemoryJobRepository.JobDraft(MemoryJobRepository.Kind.EXTRACT, session(),
                1, 2, 1, "GLOBAL", 0, "UTC", 1, (int) revision, "fp", "extract-v1", "input-1",
                MemoryTimes.now())).jobId();

        assertEquals(204, policy.revokeConsent(MAINT).status());
        assertEquals("SUPERSEDED", jobStatus(jobId), "撤销授权要取消按旧策略排队的任务");
        assertFalse(policy.consentStatus(MAINT).granted());

        long fresh = policy.current().revision();
        String kept = jobs.enqueue(new MemoryJobRepository.JobDraft(MemoryJobRepository.Kind.EXTRACT, session(),
                3, 4, 1, "GLOBAL", 0, "UTC", 1, (int) fresh, "fp", "extract-v1", "input-2",
                MemoryTimes.now())).jobId();
        assertEquals(204, policy.revokeConsent(USE).status());
        assertEquals("QUEUED", jobStatus(kept), "撤使用授权不该动维护任务");
    }

    // ---- 资格 ----

    @Test
    void extractionGatesInDocumentedOrder() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);

        assertEquals(Gate.MEMORY_DISABLED, policy.extractionEligibility("GLOBAL", 0).gate());
        switches(true, false, false);
        assertEquals(Gate.AUTO_EXTRACT_DISABLED, policy.extractionEligibility("GLOBAL", 0).gate());
        switches(true, true, false);
        assertEquals(Gate.VAULT_MISSING, policy.extractionEligibility("GLOBAL", 0).gate());

        createVaultAndLock();
        assertEquals(Gate.LOCKED, policy.extractionEligibility("GLOBAL", 0).gate());
        assertEquals(423, policy.extractionEligibility("GLOBAL", 0).toResult().status());

        vault.unlock(null, List.of());
        assertEquals(Gate.CONSENT_MISSING, policy.extractionEligibility("GLOBAL", 0).gate());
        grantMaintConsent();
        assertEquals(Gate.ALLOWED, policy.extractionEligibility("GLOBAL", 0).gate());
        assertEquals(Gate.SCOPE_INVALID, policy.extractionEligibility("NOTEBOOK", 424242).gate());
    }

    @Test
    void manualWritesNeedUnlockButNeitherSwitchNorConsent() throws Exception {
        assertEquals(Gate.VAULT_MISSING, policy.manualWriteEligibility("GLOBAL", 0).gate());

        createVaultAndLock();
        assertEquals(Gate.LOCKED, policy.manualWriteEligibility("GLOBAL", 0).gate());

        vault.unlock(null, List.of());
        assertTrue(policy.manualWriteEligibility("GLOBAL", 0).allowed(), "首次手动添加发生在开启记忆之前（§3 405 行）");
        assertFalse(policy.current().enabled());
        assertFalse(policy.consentStatus(USE).granted());
    }

    @Test
    void notebookScopesAreValidatedOnTheBackend() throws Exception {
        createVaultAndLock();
        vault.unlock(null, List.of());
        jdbc.update("INSERT INTO notebook(id, title) VALUES (7, '雅思')");

        assertTrue(policy.manualWriteEligibility("GLOBAL", 0).allowed());
        assertEquals(Gate.SCOPE_INVALID, policy.manualWriteEligibility("GLOBAL", 7).gate());
        assertEquals(Gate.SCOPE_INVALID, policy.manualWriteEligibility("NOTEBOOK", 0).gate());
        assertEquals(Gate.SCOPE_INVALID, policy.manualWriteEligibility("NOTEBOOK", -3).gate());
        assertEquals(Gate.SCOPE_INVALID, policy.manualWriteEligibility("NOTEBOOK", 424242).gate());
        assertEquals(Gate.SCOPE_INVALID, policy.manualWriteEligibility("SPACE", 0).gate());
        assertTrue(policy.manualWriteEligibility("NOTEBOOK", 7).allowed());
    }

    @Test
    void summaryRunsOnItsOwnSwitchButStillNeedsVaultAndConsent() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        assertEquals(Gate.COMPRESS_DISABLED, policy.summaryEligibility().gate());

        switches(false, false, true);
        assertEquals(Gate.VAULT_MISSING, policy.summaryEligibility().gate(), "只开压缩也要本地库保护摘要");

        createVaultAndLock();
        vault.unlock(null, List.of());
        assertEquals(Gate.CONSENT_MISSING, policy.summaryEligibility().gate());

        grantMaintConsent();
        assertTrue(policy.summaryEligibility().allowed(), "长期记忆关闭时压缩仍可跑（§2.1）");
    }

    @Test
    void quotasStopAutoAddsButNeverManualOnes() throws Exception {
        seedChat(CHAT_ENDPOINT, CHAT_MODEL, null);
        switches(true, true, false);
        vault.create("fingerprint", null, null);
        vaultId = memory.readEnvelope().orElseThrow().vaultId();
        grantMaintConsent();

        seedFacts(MemoryPolicyService.ACTIVE_FACT_LIMIT, MemoryRepository.STATUS_ACTIVE);
        assertEquals(Gate.QUOTA_ACTIVE_FULL, policy.extractionEligibility("GLOBAL", 0).gate());
        assertTrue(policy.manualWriteEligibility("GLOBAL", 0).allowed(), "上限不暗中拦人工录入（§6.2）");

        jdbc.execute("DELETE FROM ai_memory_item");
        seedFacts(MemoryRepository.PENDING_QUOTA, MemoryRepository.STATUS_PENDING);
        assertEquals(Gate.QUOTA_PENDING_FULL, policy.extractionEligibility("GLOBAL", 0).gate());
    }

    // ---- 遗忘边界 ----

    @Test
    void historyFloorOnlyMovesForwardAndIsServerOnly() throws Exception {
        long revision = policy.current().revision();
        assertEquals(0L, policy.policyOf(policy.current()).memoryHistoryFloor());

        assertEquals(400, policy.update(revision, null, null, null, null, Map.of("memoryHistoryFloor", 99)).status());

        MemorySettingsRepository.Settings advanced = policy.advanceHistoryFloor(10);
        assertEquals(10L, policy.policyOf(advanced).memoryHistoryFloor());
        assertEquals(revision + 1, advanced.revision());

        MemorySettingsRepository.Settings unchanged = policy.advanceHistoryFloor(5);
        assertEquals(10L, policy.policyOf(unchanged).memoryHistoryFloor());
        assertEquals(revision + 1, unchanged.revision(), "往回退不推版本");
    }

    // ---- helpers ----

    private long session() {
        return jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
    }

    private void seedChat(String endpoint, String model, String params) {
        jdbc.update("INSERT OR REPLACE INTO ai_config(purpose, provider, endpoint, model, encrypted_key, key_meta,"
                        + " params) VALUES ('chat','custom',?,?, 'cipher','{\"mode\":\"fingerprint\"}',?)",
                endpoint, model, params);
    }

    private void createVaultAndLock() {
        assertEquals(201, vault.create("fingerprint", null, null).status());
        assertEquals(204, vault.lock().status());
    }

    private void switches(boolean enabled, boolean autoExtract, boolean compress) throws Exception {
        MemoryApiResult result = policy.update(policy.current().revision(), enabled, autoExtract, compress, null,
                null);
        assertEquals(200, result.status(), String.valueOf(result.body()));
    }

    private void setMaintenanceMode(String mode) throws Exception {
        assertEquals(200, policy.update(policy.current().revision(), null, null, null, null,
                Map.of("maintenanceModelMode", mode)).status());
    }

    private void grantUseConsent() throws Exception {
        assertEquals(200, policy.grantConsent(USE, policy.targetOf(USE).fingerprint(), VERSION).status());
    }

    private void grantMaintConsent() throws Exception {
        assertEquals(200, policy.grantConsent(MAINT, policy.targetOf(MAINT).fingerprint(), VERSION).status());
    }

    private String jobStatus(String jobId) throws Exception {
        return jdbc.queryForObject("SELECT status FROM ai_memory_job WHERE id = ?", String.class, jobId);
    }

    private void seedFacts(int count, String status) {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(new Object[]{"row-" + status + "-" + index, "mem-" + status + "-" + index, vaultId, 1, "GLOBAL",
                    0, "PREFERENCE", "p-" + index, "f-" + index, "v-" + index, status, "cipher", "{}", "MANUAL", 1,
                    0, null, null, MemoryTimes.now(), MemoryTimes.now(), MemoryTimes.now()});
        }
        jdbc.batchUpdate("INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id,"
                + " kind, predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta,"
                + " origin, manual_protected, pinned, valid_from, valid_until, observed_at, recorded_at,"
                + " last_confirmed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows);
    }

    @SuppressWarnings("unchecked")
    private static MemoryPolicyService.ConsentStatus consent(String purpose, Map<String, Object> body) {
        return ((Map<String, MemoryPolicyService.ConsentStatus>) body.get("consents")).get(purpose);
    }
}
