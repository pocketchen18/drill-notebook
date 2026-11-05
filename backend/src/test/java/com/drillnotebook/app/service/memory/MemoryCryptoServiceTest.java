package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.ImmediateTransaction;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.drillnotebook.app.service.memory.MemoryCryptoService.EncryptedValue;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 加密原语（执行方案 §6.1 的 P2 门禁）：IV 不复用、AAD 跨行替换失败、模式不降级、
 * 查找键另行派生、rewrap 不重写事实，以及明文绝不落进 DB / WAL / 日志。
 */
class MemoryCryptoServiceTest {
    private static final String VAULT = "vault-1";
    private static final String PASSWORD = "correct horse battery";
    private static final String OTHER_PASSWORD = "wrong horse battery";
    private static final String NOW = "2026-10-12T00:00:00.000Z";

    private MemoryCryptoService crypto;

    @BeforeEach
    void setUp() {
        crypto = new MemoryCryptoService(new ApiKeyEncryptor());
    }

    @Test
    void eachWriteUsesAFreshIvSoIdenticalPlaintextsNeverCollide() throws Exception {
        MemoryKey key = passwordKey();

        EncryptedValue first = crypto.encrypt(key, "ai_memory_item", "row-1", 1, "我准备考雅思");
        EncryptedValue second = crypto.encrypt(key, "ai_memory_item", "row-2", 1, "我准备考雅思");

        assertNotEquals(first.cipher(), second.cipher(), "同一句明文两次写入的密文必须不同");
        assertNotEquals(ivOf(first), ivOf(second), "IV 绝不复用");
        assertEquals("我准备考雅思", crypto.decrypt(key, "ai_memory_item", "row-1", 1,
                first.cipher(), first.cryptoMeta()));
        assertEquals("我准备考雅思", crypto.decrypt(key, "ai_memory_item", "row-2", 1,
                second.cipher(), second.cryptoMeta()));
    }

    @Test
    void cipherIsBoundToRowVersionTableAndOwnBytes() throws Exception {
        MemoryKey key = passwordKey();
        EncryptedValue honest = crypto.encrypt(key, "ai_memory_item", "row-1", 1, "真实内容");

        MemoryCryptoException movedRow = assertThrows(MemoryCryptoException.class, () ->
                crypto.decrypt(key, "ai_memory_item", "row-2", 1, honest.cipher(), honest.cryptoMeta()));
        assertTrue(movedRow.getMessage().contains("MEMORY_CIPHER_CORRUPT"));

        assertThrows(MemoryCryptoException.class, () ->
                crypto.decrypt(key, "ai_memory_item", "row-1", 2, honest.cipher(), honest.cryptoMeta()),
                "跨版本替换同样拒绝");
        assertThrows(MemoryCryptoException.class, () ->
                crypto.decrypt(key, "ai_memory_evidence", "row-1", 1, honest.cipher(), honest.cryptoMeta()),
                "跨表替换同样拒绝");
        assertThrows(MemoryCryptoException.class, () ->
                crypto.decrypt(key, "ai_memory_item", "row-1", 1, honest.cipher() + "AA", honest.cryptoMeta()),
                "改动一个字符也解不开");
        assertThrows(MemoryCryptoException.class, () ->
                crypto.decrypt(key, "ai_memory_item", "row-1", 1, honest.cipher(), "{\"algorithm\":\"AES-256-GCM\"}"),
                "缺 iv 的元数据不能蒙混过关");
    }

    @Test
    void wrongMaterialFailsToUnlockAndMentionsNothingSecret() throws Exception {
        var created = crypto.createVaultKey(VAULT, PASSWORD, MemoryCryptoService.MODE_PASSWORD);

        MemoryCryptoException failure = assertThrows(MemoryCryptoException.class, () ->
                crypto.unlock(VAULT, created.wrappedDek(), created.wrapMeta(), OTHER_PASSWORD,
                        MemoryCryptoService.MODE_PASSWORD));

        assertTrue(failure.getMessage().contains("MEMORY_UNLOCK_FAILED"));
        assertFalse(failure.getMessage().contains(PASSWORD), "错误信息不得回显主材料");
    }

    @Test
    void storedModeIsNeverDowngradedSilently() throws Exception {
        var passwordVault = crypto.createVaultKey(VAULT, PASSWORD, MemoryCryptoService.MODE_PASSWORD);

        MemoryCryptoException mismatch = assertThrows(MemoryCryptoException.class, () ->
                crypto.unlock(VAULT, passwordVault.wrappedDek(), passwordVault.wrapMeta(),
                        crypto.fingerprintMaterial(), MemoryCryptoService.MODE_FINGERPRINT));
        assertTrue(mismatch.getMessage().contains("MEMORY_SECURITY_MODE_MISMATCH"),
                "密码库不能被当成指纹库自动解锁（§6.1.5 禁止降级）");

        var fingerprintVault = crypto.createVaultKey(VAULT, crypto.fingerprintMaterial(),
                MemoryCryptoService.MODE_FINGERPRINT);
        assertThrows(MemoryCryptoException.class, () -> crypto.unlock(VAULT, fingerprintVault.wrappedDek(),
                fingerprintVault.wrapMeta(), PASSWORD, MemoryCryptoService.MODE_PASSWORD));
        assertEquals(MemoryCryptoService.MODE_FINGERPRINT, crypto.unlock(VAULT, fingerprintVault.wrappedDek(),
                fingerprintVault.wrapMeta(), crypto.fingerprintMaterial(),
                MemoryCryptoService.MODE_FINGERPRINT).mode());
        assertThrows(MemoryCryptoException.class, () -> crypto.createVaultKey(VAULT, PASSWORD, "sha1"));
    }

    @Test
    void lookupKeysAreDerivedPerVaultRatherThanReusingTheDek() throws Exception {
        var created = crypto.createVaultKey(VAULT, PASSWORD, MemoryCryptoService.MODE_PASSWORD);
        MemoryKey key = passwordKey();

        assertEquals(crypto.hmac(key, "answer.language"), crypto.hmac(key, "answer.language"),
                "同一库内查找键可等值比较");
        assertNotEquals(crypto.hmac(key, "answer.language"), crypto.hmac(key, "answer.detail"));

        // 同一个 DEK、换个 vaultId 派生（HKDF 的 salt 变）必须得到不同查找键：键不跨库通用
        MemoryKey sameDekOtherVault = crypto.unlock("vault-2", created.wrappedDek(), created.wrapMeta(), PASSWORD,
                MemoryCryptoService.MODE_PASSWORD);
        assertNotEquals(crypto.hmac(created.key(), "answer.language"),
                crypto.hmac(sameDekOtherVault, "answer.language"), "查找键按库隔离");
        assertNotEquals(key.toString(), crypto.hmac(key, "answer.language"), "查找键不是钥匙本身的编码");
    }

    @Test
    void rewrapKeepsTheSameDekSoNoFactNeedsRewriting() throws Exception {
        var created = crypto.createVaultKey(VAULT, PASSWORD, MemoryCryptoService.MODE_PASSWORD);
        EncryptedValue payload = crypto.encrypt(created.key(), "ai_memory_item", "row-1", 1, "以后先给例子");

        var rewrapped = crypto.rewrap(created.wrappedDek(), created.wrapMeta(), PASSWORD, "新的主密码",
                MemoryCryptoService.MODE_PASSWORD);
        assertNotEquals(created.wrappedDek(), rewrapped.wrappedDek(), "包装层确实换了材料");

        MemoryKey afterRewrap = crypto.unlock(VAULT, rewrapped.wrappedDek(), rewrapped.wrapMeta(), "新的主密码",
                MemoryCryptoService.MODE_PASSWORD);
        assertEquals("以后先给例子", crypto.decrypt(afterRewrap, "ai_memory_item", "row-1", 1,
                payload.cipher(), payload.cryptoMeta()), "DEK 不变，旧密文照常可读");
        assertEquals(crypto.hmac(created.key(), "answer.order"), crypto.hmac(afterRewrap, "answer.order"),
                "查找键也不受影响");
        assertThrows(MemoryCryptoException.class, () -> crypto.rewrap(created.wrappedDek(), created.wrapMeta(),
                OTHER_PASSWORD, "新密码", MemoryCryptoService.MODE_PASSWORD), "旧材料不对就不给换包装");
    }

    @Test
    void erasedKeyRefusesWorkInsteadOfUsingZeroBytes() throws Exception {
        MemoryKey key = passwordKey();
        EncryptedValue payload = crypto.encrypt(key, "ai_memory_item", "row-1", 1, "内容");

        key.erase();

        MemoryCryptoException locked = assertThrows(MemoryCryptoException.class, () ->
                crypto.decrypt(key, "ai_memory_item", "row-1", 1, payload.cipher(), payload.cryptoMeta()));
        assertTrue(locked.getMessage().contains("MEMORY_LOCKED"));
        assertThrows(MemoryCryptoException.class, () -> crypto.encrypt(key, "ai_memory_item", "row-1", 1, "新内容"));
        assertThrows(MemoryCryptoException.class, () -> crypto.hmac(key, "answer.language"));
    }

    @Test
    void quoteCapIsCountedInCodePointsAndRefusesWholeRoundDumps() {
        MemoryCryptoService.assertWithinQuoteCap("雅".repeat(400));
        MemoryCryptoService.assertWithinQuoteCap(null);
        assertThrows(IllegalArgumentException.class,
                () -> MemoryCryptoService.assertWithinQuoteCap("雅".repeat(401)));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryCryptoService.assertWithinQuoteCap(new String(Character.toChars(0x1F600)).repeat(401)),
                "emoji 按码点计，不按 UTF-16 长度");
    }

    @Test
    void plaintextNeverReachesTheDatabaseWalOrTheLog() throws Exception {
        Path appRoot = TestWorkspace.scratchDirectory("p2-crypto-db-");
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            DataSource dataSource = Schema9Fixture.dataSource(appRoot);
            new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("INSERT INTO ai_memory_vault(id, wrapped_dek, wrap_meta, mode, created_at)"
                    + " VALUES (?, ?, ?, 'password', ?)", VAULT, "wrapper", "{}", NOW);
            MemoryRepository memory = new MemoryRepository(dataSource, new ImmediateTransaction(dataSource));
            MemoryKey key = passwordKey();
            String secret = "我的住址是某某路 1 号";
            String rowId = "row-home-1";

            // 真实写入顺序：上层先定行身份 → 按该身份绑定加密 → 一次插入，密文与行永远配对
            EncryptedValue payload = crypto.encrypt(key, "ai_memory_item", rowId, 1, secret);
            var row = memory.add(new MemoryRepository.NewMemory(rowId, VAULT, "GLOBAL", 0, "BACKGROUND",
                    crypto.hmac(key, "background.home"), crypto.hmac(key, "family-home"), crypto.hmac(key, secret),
                    MemoryRepository.STATUS_ACTIVE, payload.cipher(), payload.cryptoMeta(), "MANUAL", true, false,
                    null, null, NOW, NOW, NOW));
            assertEquals(row.rowId(), rowId);
            jdbc.execute("PRAGMA wal_checkpoint(FULL)");

            byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
            byte[] db = Files.readAllBytes(appRoot.resolve("data/study.db"));
            Path walPath = appRoot.resolve("data/study.db-wal");
            byte[] wal = Files.exists(walPath) ? Files.readAllBytes(walPath) : new byte[0];
            assertFalse(contains(db, plain), "明文字节不得出现在主库文件里");
            assertFalse(contains(wal, plain), "明文字节不得出现在 WAL 里");
            assertTrue(contains(db, payload.cipher().getBytes(StandardCharsets.US_ASCII)), "密文确实落在库内");
            assertFalse(appender.list.stream()
                            .anyMatch(event -> event.getFormattedMessage().contains(secret)
                                    || event.getFormattedMessage().contains(PASSWORD)),
                    "日志不得出现正文或主材料");
        } finally {
            root.detachAppender(appender);
            TestWorkspace.deleteRecursively(appRoot);
        }
    }

    // ---- helpers ----

    private MemoryKey passwordKey() throws MemoryCryptoException {
        return crypto.createVaultKey(VAULT, PASSWORD, MemoryCryptoService.MODE_PASSWORD).key();
    }

    private static String ivOf(EncryptedValue value) {
        return value.cryptoMeta().replaceAll(".*\"iv\":\"([^\"]+)\".*", "$1");
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
