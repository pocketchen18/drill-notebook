package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryCryptoService.EncryptedValue;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.drillnotebook.app.service.memory.MemoryVaultService.SessionUnlock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆库生命周期（执行方案 §6.1、contracts §3 的 vault 行）：创建不覆盖旧 DEK、模式不降级、
 * 错误密码既不落盘也不外发、改密只重包装不动事实、闲置自动锁定，忘记密码只能显式重置并连带删派生记录。
 */
class MemoryVaultServiceTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String OTHER = "wrong horse battery";
    private static final String SECRET = "我的住址是某某路 1 号";
    private static final String ROW = "row-home-1";

    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryCryptoService crypto;
    private MemoryRepository memory;
    private MemoryVaultService vault;
    private long sessionId;
    private String now;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p2-vault-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        ImmediateTransaction immediate = new ImmediateTransaction(dataSource);
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
        memory = new MemoryRepository(dataSource, immediate);
        vault = new MemoryVaultService(crypto, memory, new MemoryOperationRepository(dataSource, immediate),
                new ChatSessionMetadataRepository(dataSource, immediate));
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        now = MemoryTimes.now();
    }

    @AfterEach
    void removeDatabase() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    // ---- 创建 ----

    @Test
    void vaultIsCreatedOnceAndTheSecondAttemptNeverOverwritesTheDek() throws Exception {
        assertEquals(0L, count("ai_memory_vault"), "迁移与启动都不自动建库");

        MemoryApiResult created = vault.create("FINGERPRINT", null, null);
        assertEquals(201, created.status());
        assertEquals(Boolean.TRUE, created.body().get("initialized"));
        assertEquals(Boolean.TRUE, created.body().get("unlocked"));
        assertEquals("fingerprint", created.body().get("mode"));
        assertEquals(1L, created.body().get("epoch"));
        assertFalse(created.body().containsKey("wrappedDek"), "响应不回包装层");
        assertNotNull(vault.requireKey(), "创建即解锁，向导能立刻加第一条记忆");

        String wrapper = envelope().wrappedDek();
        MemoryApiResult again = vault.create("password", PASSWORD, null);
        assertEquals(409, again.status());
        assertEquals("MEMORY_VAULT_EXISTS", again.body().get("code"));
        assertEquals(wrapper, envelope().wrappedDek(), "已有库的 DEK 一个字节都不能变");
        assertEquals(1L, count("ai_memory_vault"));
    }

    @Test
    void masterPasswordNeverReachesTheDatabaseFilesOrTheResponse() throws Exception {
        MemoryApiResult created = vault.create("password", PASSWORD, null);
        assertEquals(201, created.status());
        assertFalse(created.body().toString().contains(PASSWORD), "响应里没有主密码");
        assertFalse(envelope().wrapMeta().contains(PASSWORD), "wrap_meta 只存 salt/iv/kdf 名，不存材料");
        assertNotEquals(PASSWORD, envelope().wrappedDek());

        jdbc.execute("PRAGMA wal_checkpoint(FULL)");
        byte[] db = Files.readAllBytes(appRoot.resolve("data/study.db"));
        Path walPath = appRoot.resolve("data/study.db-wal");
        byte[] wal = Files.exists(walPath) ? Files.readAllBytes(walPath) : new byte[0];
        assertFalse(contains(db, PASSWORD), "主密码不得出现在主库文件里");
        assertFalse(contains(wal, PASSWORD), "主密码不得出现在 WAL 里");
    }

    @Test
    void badModeOrMissingPasswordWritesNothingAndUnlocksNothing() throws Exception {
        MemoryApiResult missingPassword = vault.create("password", "   ", null);
        assertEquals(400, missingPassword.status());
        assertEquals("MEMORY_PASSWORD_REQUIRED", missingPassword.body().get("code"));

        MemoryApiResult badMode = vault.create("sha1", PASSWORD, null);
        assertEquals(400, badMode.status());
        assertEquals("MEMORY_MODE_INVALID", badMode.body().get("code"));

        assertTrue(memory.readEnvelope().isEmpty());
        assertTrue(vault.currentKey().isEmpty());
        MemoryApiResult noVault = vault.unlock(PASSWORD, List.of());
        assertEquals(409, noVault.status());
        assertEquals("MEMORY_VAULT_NOT_INITIALIZED", noVault.body().get("code"));
    }

    // ---- 解锁 / 锁定 ----

    @Test
    void wrongPasswordGives401AndSaysNothingAboutTheSecret() throws Exception {
        vault.create("password", PASSWORD, null);
        assertEquals(204, vault.lock().status());
        String wrapper = envelope().wrappedDek();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            MemoryApiResult failure = vault.unlock(OTHER, List.of());
            assertEquals(401, failure.status());
            assertEquals("MEMORY_UNLOCK_FAILED", failure.body().get("code"));
            assertFalse(failure.body().toString().contains(OTHER), "错误响应不回显提交的材料");
            assertFalse(failure.body().toString().contains(PASSWORD));
            assertFalse(appender.list.stream().anyMatch(event ->
                            event.getFormattedMessage().contains(PASSWORD)
                                    || event.getFormattedMessage().contains(OTHER)),
                    "日志不得出现主密码");
            assertTrue(vault.currentKey().isEmpty(), "解不开就没有任何钥匙");
            assertEquals("MEMORY_LOCKED", assertThrows(MemoryCryptoException.class, vault::requireKey).code());
            assertEquals(wrapper, envelope().wrappedDek(), "失败的解锁不动包装层");

            assertEquals(200, vault.unlock(PASSWORD, List.of()).status());
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void passwordVaultCannotBeUnlockedAsFingerprintEvenIfTheRowIsTampered() throws Exception {
        vault.create("password", PASSWORD, null);
        vault.lock();
        jdbc.update("UPDATE ai_memory_vault SET wrap_meta ="
                + " replace(wrap_meta, '\"mode\":\"password\"', '\"mode\":\"fingerprint\"')");

        MemoryApiResult refusal = vault.unlock(PASSWORD, List.of());
        assertEquals(409, refusal.status());
        assertEquals("MEMORY_SECURITY_MODE_MISMATCH", refusal.body().get("code"), "§6.1.5 禁止偷偷降级");
        assertTrue(vault.currentKey().isEmpty());
    }

    @Test
    void lockErasesTheKeyClearsSessionMaterialAndNotifiesCaches() throws Exception {
        AtomicBoolean cleared = new AtomicBoolean();
        vault.onLock(() -> cleared.set(true));
        vault.create("password", PASSWORD, null);
        assertEquals(200, vault.unlock(PASSWORD, List.of(new SessionUnlock(sessionId, "s3cret-pass"))).status());
        assertTrue(vault.sessionPassword(sessionId).isPresent());
        MemoryKey key = vault.requireKey();

        assertEquals(204, vault.lock().status());
        assertTrue(cleared.get(), "锁定要通知缓存/维护侧（§6.1.7）");
        assertTrue(key.erased());
        assertTrue(vault.currentKey().isEmpty());
        assertTrue(vault.sessionPassword(sessionId).isEmpty(), "会话材料随锁定清除");
        assertEquals(204, vault.lock().status(), "重复锁定不报错");
    }

    @Test
    void idleKeyAutoLocksButEveryUseExtendsTheWindow() throws Exception {
        vault.create("fingerprint", null, null);
        MemoryKey handed = vault.requireKey();

        assertFalse(vault.lockIfIdle(System.nanoTime() + Duration.ofMinutes(14).toNanos()));
        assertFalse(handed.erased());

        // 第 14 分钟仍在用：取用钥匙即续期，之后再来 14 分钟也不算超时
        assertNotNull(vault.requireKey());
        assertFalse(vault.lockIfIdle(System.nanoTime() + Duration.ofMinutes(14).toNanos()));

        assertTrue(vault.lockIfIdle(System.nanoTime()
                + MemoryVaultService.IDLE_LOCK_AFTER.plusMillis(1).toNanos()));
        assertTrue(handed.erased(), "自动锁定要清零交出去的钥匙");
        assertEquals("MEMORY_LOCKED", assertThrows(MemoryCryptoException.class, vault::requireKey).code());
    }

    // ---- 改密 ----

    @Test
    void passwordChangeOnlyRewrapsTheDekAndLeavesEveryFactAlone() throws Exception {
        vault.create("password", PASSWORD, null);
        MemoryKey before = vault.requireKey();
        EncryptedValue payload = crypto.encrypt(before, "ai_memory_item", ROW, 1, SECRET);
        memory.add(fact(before, payload));
        long epoch = envelope().epoch();
        long revision = envelope().revision();
        String wrapper = envelope().wrappedDek();
        String cipher = jdbc.queryForObject("SELECT payload_cipher FROM ai_memory_item WHERE row_id = ?",
                String.class, ROW);

        MemoryApiResult changed = vault.changePassword(PASSWORD, "新的主密码");
        assertEquals(200, changed.status());
        assertNotEquals(wrapper, envelope().wrappedDek(), "包装层换了材料");
        assertEquals("password", envelope().mode());
        assertEquals(epoch, envelope().epoch());
        assertEquals(revision, envelope().revision(), "换密码不是遗忘边界，也不推进内容计数");
        assertEquals(cipher, jdbc.queryForObject("SELECT payload_cipher FROM ai_memory_item WHERE row_id = ?",
                String.class, ROW), "事实密文一行都没重写");
        assertTrue(before.erased(), "旧钥匙随换密作废，锁定后不该还有可用副本");

        MemoryKey after = vault.requireKey();
        assertEquals(SECRET, crypto.decrypt(after, "ai_memory_item", ROW, 1, cipher,
                jdbc.queryForObject("SELECT crypto_meta FROM ai_memory_item WHERE row_id = ?", String.class, ROW)));
        assertEquals("MEMORY_UNLOCK_FAILED",
                assertCode(vault.unlock(PASSWORD, List.of())), "旧密码从此解不开");
        assertEquals(200, vault.unlock("新的主密码", List.of()).status());
    }

    @Test
    void failedPasswordChangeKeepsTheOldWrapperAndKey() throws Exception {
        vault.create("password", PASSWORD, null);
        String wrapper = envelope().wrappedDek();
        String meta = envelope().wrapMeta();

        MemoryApiResult failure = vault.changePassword(OTHER, "新密码");
        assertEquals(401, failure.status());
        assertEquals(wrapper, envelope().wrappedDek(), "§6.1.9 失败不清空旧值");
        assertEquals(meta, envelope().wrapMeta());
        assertNotNull(vault.requireKey(), "改密失败不影响原本已解锁的会话");
        assertEquals(400, vault.changePassword(PASSWORD, " ").status());
    }

    @Test
    void fingerprintVaultCanOnlyBeUpgradedToPassword() throws Exception {
        vault.create("fingerprint", null, null);
        MemoryApiResult upgraded = vault.changePassword(null, "补设的主密码");
        assertEquals(200, upgraded.status());
        assertEquals("password", envelope().mode());
        assertEquals(400, vault.unlock(null, List.of()).status(), "升级后必须给密码，不再走指纹兜底");
        assertEquals(200, vault.unlock("补设的主密码", List.of()).status());
    }

    // ---- 重置 ----

    @Test
    void resetNeedsExplicitConfirmationAndWipesEveryDerivedRow() throws Exception {
        vault.create("password", PASSWORD, null);
        String oldVaultId = envelope().vaultId();
        MemoryKey key = vault.requireKey();
        EncryptedValue payload = crypto.encrypt(key, "ai_memory_item", ROW, 1, SECRET);
        String memoryId = memory.add(fact(key, payload)).memoryId();
        seedSources(oldVaultId, memoryId);
        assertEquals(1L, count("ai_memory_item"));
        assertEquals(1L, count("ai_memory_evidence"));
        assertEquals(1L, count("ai_memory_proposal"));
        assertEquals(1L, count("ai_memory_suppression"));
        assertEquals(1L, count("ai_memory_job"));
        assertEquals(1L, count("ai_session_context"));

        MemoryApiResult unconfirmed = vault.reset(false, "fingerprint", null, null);
        assertEquals(409, unconfirmed.status());
        assertEquals("MEMORY_RESET_CONFIRMATION_REQUIRED", unconfirmed.body().get("code"));
        assertEquals(1L, count("ai_memory_item"), "没确认就一条都不删");
        assertEquals(oldVaultId, envelope().vaultId());

        MemoryApiResult reset = vault.reset(true, "fingerprint", null, "op-reset-1");
        assertEquals(200, reset.status());
        String newVaultId = envelope().vaultId();
        assertNotEquals(oldVaultId, newVaultId, "重置换新 ID");
        assertEquals("fingerprint", envelope().mode());
        assertEquals(1L, envelope().epoch());
        assertEquals(1L, envelope().revision());
        for (String table : List.of("ai_memory_item", "ai_memory_evidence", "ai_memory_item_evidence",
                "ai_memory_proposal", "ai_memory_suppression", "ai_memory_job", "ai_session_context")) {
            assertEquals(0L, count(table), table + " 的旧派生记录必须删净");
        }
        assertTrue(key.erased(), "旧库的钥匙随重置作废，旧密文从此无从可解");
        assertEquals(newVaultId, vault.requireKey().vaultId());
    }

    // ---- operationId ----

    @Test
    void sameOperationIdReplaysTheFirstResultInsteadOfTouchingTheVault() throws Exception {
        MemoryApiResult created = vault.create("fingerprint", null, "op-create-1");
        assertEquals(201, created.status());
        String wrapper = envelope().wrappedDek();

        MemoryApiResult replay = vault.create("password", PASSWORD, "op-create-1");
        assertEquals(201, replay.status(), "同 operationId 的已完成动作回吐原结果");
        assertEquals(created.body().get("vaultId"), replay.body().get("vaultId"));
        assertEquals(wrapper, envelope().wrappedDek());
        assertEquals(1L, count("ai_memory_vault"));

        MemoryApiResult conflict = vault.reset(true, "fingerprint", null, "op-create-1");
        assertEquals(409, conflict.status());
        assertEquals("MEMORY_OPERATION_ID_CONFLICT", conflict.body().get("code"));
        assertEquals(wrapper, envelope().wrappedDek(), "冲突时旧库不动");
    }

    @Test
    void rejectedCreateFreesItsOperationIdSoTheUserCanRetry() throws Exception {
        vault.create("fingerprint", null, null);
        vault.lock();

        MemoryApiResult conflict = vault.create("fingerprint", null, "op-create-2");
        assertEquals(409, conflict.status());
        assertTrue(new MemoryOperationRepository(dataSource, new ImmediateTransaction(dataSource))
                        .find("op-create-2").isEmpty(),
                "失败的动作不留登记，否则同一个 ID 永远重试不了");

        jdbc.execute("DELETE FROM ai_memory_vault");
        assertEquals(201, vault.create("fingerprint", null, "op-create-2").status());
    }

    // ---- 会话解锁 ----

    @Test
    void sessionUnlocksAreValidatedKeptInMemoryAndNeverPersisted() throws Exception {
        vault.create("fingerprint", null, null);
        vault.lock();

        MemoryApiResult unknown = vault.unlock(null, List.of(new SessionUnlock(sessionId + 9999, "会话密码")));
        assertEquals(400, unknown.status());
        assertEquals("MEMORY_SESSION_NOT_FOUND", unknown.body().get("code"));
        assertTrue(vault.currentKey().isEmpty(), "会话校验失败时连库都不该解锁");

        MemoryApiResult unlocked = vault.unlock(null, List.of(new SessionUnlock(sessionId, "s3cret-pass")));
        assertEquals(200, unlocked.status());
        assertEquals(1, unlocked.body().get("sessionUnlockedCount"));
        assertFalse(unlocked.body().toString().contains("s3cret-pass"));
        assertEquals("s3cret-pass", vault.sessionPassword(sessionId).orElseThrow());

        jdbc.execute("PRAGMA wal_checkpoint(FULL)");
        byte[] db = Files.readAllBytes(appRoot.resolve("data/study.db"));
        assertFalse(contains(db, "s3cret-pass"), "会话密码绝不落库");

        vault.lock();
        assertTrue(vault.sessionPassword(sessionId).isEmpty());
    }

    // ---- helpers ----

    private MemoryRepository.NewMemory fact(MemoryKey key, EncryptedValue payload) throws MemoryCryptoException {
        return new MemoryRepository.NewMemory(ROW, key.vaultId(), "GLOBAL", 0, "BACKGROUND",
                crypto.hmac(key, "background.home"), crypto.hmac(key, "family-home"), crypto.hmac(key, SECRET),
                MemoryRepository.STATUS_ACTIVE, payload.cipher(), payload.cryptoMeta(), "MANUAL", true, false,
                null, null, now, now, now);
    }

    /** 给旧库塞满各类派生记录，用来验证重置的连带删除。 */
    private void seedSources(String vaultId, String memoryId) throws Exception {
        memory.insertEvidence(new MemoryRepository.EvidenceDraft("ev-1", vaultId, sessionId, null, null,
                "block-1", "src-hmac-1", "quote-cipher", "{}", now));
        memory.attachEvidence(new MemoryRepository.EvidenceDraft("ev-1", vaultId, sessionId, null, null,
                "block-1", "src-hmac-1", "quote-cipher", "{}", now), ROW);
        new MemoryProposalRepository(dataSource, new ImmediateTransaction(dataSource)).submit(
                new MemoryProposalRepository.ProposalDraft("prop-1", vaultId, sessionId, null, "ev-1", memoryId, 1,
                        "REPLACE_PROPOSAL", "payload-cipher", "{}", "dedup-1", now, now));
        new MemorySuppressionRepository(dataSource, new ImmediateTransaction(dataSource)).record(vaultId,
                new MemorySuppressionRepository.Suppression("GLOBAL", 0, "FAMILY", "fam-1", null, now));
        new MemoryJobRepository(dataSource, new ImmediateTransaction(dataSource)).enqueue(
                new MemoryJobRepository.JobDraft(MemoryJobRepository.Kind.EXTRACT, sessionId, 1, 2, 1, "GLOBAL", 0,
                        "UTC", 1, 1, "fp", "extract-v1", "input-1", now));
        jdbc.update("INSERT INTO ai_session_context(session_id, revision, through_turn_id, context_generation,"
                + " vault_epoch, payload_cipher, crypto_meta, estimated_tokens, updated_at)"
                + " VALUES (?, 1, 2, 1, 1, 'summary-cipher', '{}', 12, ?)", sessionId, now);
    }

    private MemoryRepository.VaultEnvelope envelope() throws Exception {
        return memory.readEnvelope().orElseThrow();
    }

    private static String assertCode(MemoryApiResult result) {
        return String.valueOf(result.body().get("code"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int start = 0; start + target.length <= haystack.length; start++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (haystack[start + offset] != target[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
