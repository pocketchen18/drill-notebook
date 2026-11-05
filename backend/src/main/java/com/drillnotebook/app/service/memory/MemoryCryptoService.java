package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.service.ApiKeyEncryptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.springframework.stereotype.Service;

/**
 * 记忆库加密原语（执行方案 §6.1）：内容只用 AES-256-GCM 保护，DEK 只被 Argon2id 派生材料包装一次。
 *
 * <p>三把钥匙分工明确，绝不混用：</p>
 * <ul>
 *   <li><b>DEK</b>（32 字节随机）：加解密 payload/quote，每次写入都用新的随机 12 字节 IV；
 *       GCM 的 AAD 绑定 {@code vaultId|table|rowId|version}，把 A 行的密文搬到 B 行必然认证失败。</li>
 *   <li><b>主材料</b>（fingerprint 或 password）：只用于包装 DEK。改密码只是 rewrap，不重写任何事实，
 *       所以也不给每条记忆重复跑 32 MiB 的 Argon2。</li>
 *   <li><b>HMAC key</b>：从 DEK 经 HKDF-SHA256 用独立 info 标签派生，只做查找键；
 *       DEK 本身不参与 HMAC，也不用未加盐 SHA256 存个人事实键。</li>
 * </ul>
 *
 * <p>失败一律 fail-closed：解不开就报错误码，不回退明文、不返回半成品、不清空旧值（§6.1.9）。
 * 本类不打日志；Java 字符串无法保证完整清零，因此只宣称轻量保护，
 * fingerprint 模式不防同机用户与恶意软件（§6.1.4）。</p>
 */
@Service
public class MemoryCryptoService {
    public static final int DEK_BYTES = 32;
    public static final int IV_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final String MODE_FINGERPRINT = "fingerprint";
    public static final String MODE_PASSWORD = "password";
    /** HKDF 的独立 info 标签（§6.1.3）：换标签会让所有已存查找键失效。 */
    public static final String HMAC_INFO_LABEL = "memory-lookup-v1";
    /** 每条证据原话的明文码点上限（§2.3）：加密前把关，超长说明调用方没裁到最短原话。 */
    public static final int MAX_QUOTE_CODE_POINTS = 400;

    private static final String AES_GCM = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final ApiKeyEncryptor envelope;

    public MemoryCryptoService(ApiKeyEncryptor envelope) {
        this.envelope = envelope;
    }

    /** 解锁后驻内存的钥匙对：DEK 加解密内容，HMAC key 只做查找键。 */
    public static final class MemoryKey implements AutoCloseable {
        private final byte[] dek;
        private final byte[] hmacKey;
        private final String vaultId;
        private final String mode;
        private boolean erased;

        MemoryKey(byte[] dek, byte[] hmacKey, String vaultId, String mode) {
            this.dek = dek;
            this.hmacKey = hmacKey;
            this.vaultId = vaultId;
            this.mode = mode;
        }

        public String vaultId() {
            return vaultId;
        }

        public String mode() {
            return mode;
        }

        public boolean erased() {
            return erased;
        }

        /** 锁定即清零；之后任何使用都抛 MEMORY_LOCKED，而不是静默拿全零钥匙。 */
        @Override
        public void close() {
            erase();
        }

        public void erase() {
            if (!erased) {
                Arrays.fill(dek, (byte) 0);
                Arrays.fill(hmacKey, (byte) 0);
                erased = true;
            }
        }

        byte[] dek() throws MemoryCryptoException {
            requireLive();
            return dek;
        }

        byte[] hmacKey() throws MemoryCryptoException {
            requireLive();
            return hmacKey;
        }

        private void requireLive() throws MemoryCryptoException {
            if (erased) {
                throw new MemoryCryptoException("MEMORY_LOCKED", "记忆钥匙已随锁定清除，需要重新解锁");
            }
        }
    }

    /** 新建库的一次性产物：内存钥匙 + 落库的 wrapped DEK 与包装元数据。 */
    public record GeneratedVaultKey(MemoryKey key, String wrappedDek, String wrapMeta) {
    }

    /** 重新包装的结果：DEK 没变，只换主材料。 */
    public record Rewrapped(String wrappedDek, String wrapMeta) {
    }

    /** 一次落库的加密产物：Base64 密文 + 该行自己的 crypto_meta（含随机 IV）。 */
    public record EncryptedValue(String cipher, String cryptoMeta) {
    }

    /** 统一错误载体：code 是给前端的短码，message 已脱敏，绝不含明文内容或密钥材料。 */
    public static class MemoryCryptoException extends Exception {
        private final String code;

        public MemoryCryptoException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        /** 稳定的机器可读错误码，控制器据此映射 HTTP 状态；message 只做脱敏描述。 */
        public String code() {
            return code;
        }
    }

    /** 与聊天侧同一套指纹材料（用户名 + 机器名 + OS），保证跨功能解锁材料一致。 */
    public String fingerprintMaterial() {
        return envelope.fingerprintMaterial();
    }

    /** 生成随机 DEK 并用主材料包装一次；{@code mode} 只接受 fingerprint / password。 */
    public GeneratedVaultKey createVaultKey(String vaultId, String material, String mode)
            throws MemoryCryptoException {
        requireMode(mode);
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        try {
            ApiKeyEncryptor.EncryptedValue wrapped = envelope.encrypt(encode(dek), material, mode);
            return new GeneratedVaultKey(assemble(vaultId, dek, mode), wrapped.encrypted(),
                    writeMeta(Map.of("salt", wrapped.salt(), "iv", wrapped.iv(), "kdf", "Argon2id",
                            "algorithm", "AES-256-GCM", "mode", wrapped.mode(), "version", 1)));
        } catch (Exception error) {
            Arrays.fill(dek, (byte) 0);
            throw new MemoryCryptoException("MEMORY_WRAPPING_FAILED", "DEK 包装失败，未写入任何新值");
        }
    }

    /**
     * 用主材料解开 DEK。模式不符直接拒（不偷偷降级成指纹，§6.1.5）；
     * 密码错误表现为解锁失败，不给任何半成品钥匙。
     */
    public MemoryKey unlock(String vaultId, String wrappedDek, String wrapMeta, String material, String expectedMode)
            throws MemoryCryptoException {
        requireMode(expectedMode);
        String storedMode = modeOf(wrapMeta);
        if (!storedMode.equals(expectedMode)) {
            throw new MemoryCryptoException("MEMORY_SECURITY_MODE_MISMATCH",
                    "记忆库按 " + storedMode + " 模式保护，不能用 " + expectedMode + " 方式解锁");
        }
        byte[] dek = unwrap(wrappedDek, wrapMeta, material);
        try {
            return assemble(vaultId, dek, storedMode);
        } catch (MemoryCryptoException | RuntimeException error) {
            Arrays.fill(dek, (byte) 0);
            throw error;
        }
    }

    /**
     * 换密码/换模式只做 rewrap：先用旧材料证明调用方持有它，再用新材料包装同一个 DEK。
     * 事实行一行都不动（§6.1.8），忘记密码时这里只会失败，不会给出"恢复"路径。
     */
    public Rewrapped rewrap(String wrappedDek, String wrapMeta, String oldMaterial, String newMaterial,
                            String newMode) throws MemoryCryptoException {
        requireMode(newMode);
        byte[] dek = unwrap(wrappedDek, wrapMeta, oldMaterial);
        try {
            ApiKeyEncryptor.EncryptedValue wrapped = envelope.encrypt(encode(dek), newMaterial, newMode);
            return new Rewrapped(wrapped.encrypted(), writeMeta(Map.of("salt", wrapped.salt(), "iv", wrapped.iv(),
                    "kdf", "Argon2id", "algorithm", "AES-256-GCM", "mode", wrapped.mode(), "version", 1)));
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_WRAPPING_FAILED", "重新包装失败，旧包装保持不变");
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** 用包装元数据 + 主材料还原 DEK；任何认证失败都归为解锁失败。 */
    public byte[] unwrap(String wrappedDek, String wrapMeta, String material) throws MemoryCryptoException {
        Map<String, String> meta = readMeta(wrapMeta);
        try {
            byte[] dek = decode(envelope.decrypt(wrappedDek, meta.get("salt"), meta.get("iv"), material));
            if (dek.length != DEK_BYTES) {
                Arrays.fill(dek, (byte) 0);
                throw new MemoryCryptoException("MEMORY_UNLOCK_FAILED", "解出的 DEK 长度不合法");
            }
            return dek;
        } catch (MemoryCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_UNLOCK_FAILED", "主材料不对或包装密文已损坏，未解锁任何内容");
        }
    }

    /** 加密一行的内容；AAD 绑定行身份，密文跨行或跨版本替换必然解不开。 */
    public EncryptedValue encrypt(MemoryKey key, String table, String rowId, long version, String plaintext)
            throws MemoryCryptoException {
        if (plaintext == null) {
            throw new MemoryCryptoException("MEMORY_ENCRYPT_FAILED", "没有内容可加密");
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.dek(), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(key.vaultId(), table, rowId, version));
            String body = encode(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
            return new EncryptedValue(body, writeMeta(Map.of("algorithm", "AES-256-GCM",
                    "iv", encode(iv), "binding", "vault-table-row-version/v1", "version", 1)));
        } catch (MemoryCryptoException error) {
            throw error;
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new MemoryCryptoException("MEMORY_ENCRYPT_FAILED", "加密失败，旧值保持不变且不回退明文");
        }
    }

    /** 解密一行的内容；失败只给错误码，绝不返回明文片段或空串占位。 */
    public String decrypt(MemoryKey key, String table, String rowId, long version, String cipherText, String cryptoMeta)
            throws MemoryCryptoException {
        String iv = readMeta(cryptoMeta).get("iv");
        if (iv == null) {
            throw new MemoryCryptoException("MEMORY_CIPHER_CORRUPT", "该行缺少可用的 crypto_meta");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.dek(), "AES"),
                    new GCMParameterSpec(TAG_BITS, decode(iv)));
            cipher.updateAAD(aad(key.vaultId(), table, rowId, version));
            return new String(cipher.doFinal(decode(cipherText)), StandardCharsets.UTF_8);
        } catch (MemoryCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_CIPHER_CORRUPT",
                    "该行密文与身份绑定不匹配或已损坏，按不可用隔离");
        }
    }

    /** 查找键：predicate/family/value 的等值比较走 HMAC-SHA256，密钥由 DEK 另行派生。 */
    public String hmac(MemoryKey key, String value) throws MemoryCryptoException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.hmacKey(), "HmacSHA256"));
            return encode(mac.doFinal(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8)));
        } catch (MemoryCryptoException error) {
            throw error;
        } catch (GeneralSecurityException error) {
            throw new MemoryCryptoException("MEMORY_LOOKUP_KEY_FAILED", "查找键计算失败");
        }
    }

    /** 证据原话必须在加密前裁到最短原话，超长直接拒绝（§2.3）。 */
    public static void assertWithinQuoteCap(String quote) {
        int codePoints = quote == null ? 0 : quote.codePointCount(0, quote.length());
        if (codePoints > MAX_QUOTE_CODE_POINTS) {
            throw new IllegalArgumentException("证据原话 " + codePoints + " 码点，超过 "
                    + MAX_QUOTE_CODE_POINTS + " 上限：必须先裁到最短原话");
        }
    }

    public static String modeOf(String wrapMeta) throws MemoryCryptoException {
        String mode = readMeta(wrapMeta).get("mode");
        requireMode(mode);
        return mode;
    }

    private MemoryKey assemble(String vaultId, byte[] dek, String mode) throws MemoryCryptoException {
        return new MemoryKey(dek, hkdf(dek, vaultId.getBytes(StandardCharsets.UTF_8),
                HMAC_INFO_LABEL.getBytes(StandardCharsets.UTF_8)), vaultId, mode);
    }

    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) throws MemoryCryptoException {
        try {
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(ikm, salt, info));
            byte[] out = new byte[DEK_BYTES];
            hkdf.generateBytes(out, 0, out.length);
            return out;
        } catch (RuntimeException error) {
            throw new MemoryCryptoException("MEMORY_LOOKUP_KEY_FAILED", "HKDF 派生失败");
        }
    }

    private static byte[] aad(String vaultId, String table, String rowId, long version) {
        return (vaultId + "|" + table + "|" + rowId + "|" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireMode(String mode) throws MemoryCryptoException {
        if (!MODE_FINGERPRINT.equals(mode) && !MODE_PASSWORD.equals(mode)) {
            throw new MemoryCryptoException("MEMORY_SECURITY_MODE_MISMATCH", "未知的记忆库保护模式: " + mode);
        }
    }

    private String writeMeta(Map<String, Object> values) throws MemoryCryptoException {
        try {
            return sharedMapper.writeValueAsString(values);
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_META_FAILED", "元数据序列化失败");
        }
    }

    private static Map<String, String> readMeta(String json) throws MemoryCryptoException {
        try {
            JsonNode node = sharedMapper.readTree(json == null ? "{}" : json);
            Map<String, String> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            return values;
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_META_FAILED", "元数据无法解析，该行按不可用隔离");
        }
    }

    private static final ObjectMapper sharedMapper = new ObjectMapper();

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
