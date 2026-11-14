package com.drillnotebook.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 聊天正文/思考链/请求元数据的加密材料（执行方案 §6.1、contracts §2.2）。
 *
 * <p>与记忆库密钥无关：用会话侧的 fingerprint/password 材料，未开启记忆库时同样工作。
 * 写入一律加密，<b>失败就抛错</b>——绝不因为加密出错而退回明文落库（§7.2「任何写路径都不得把个人正文写成明文」）。</p>
 */
@Component
public class ChatMessageCipher {
    /** 读取结果：需要主密码与解密失败要分开告诉用户，前者换个密码就能读，后者是数据真的坏了。 */
    public enum ReadStatus { DECRYPTED, NEEDS_PASSWORD, UNREADABLE, EMPTY }

    public record Read(ReadStatus status, String value) {
        public String orSentinel() {
            return switch (status) {
                case DECRYPTED -> value;
                case NEEDS_PASSWORD -> "[加密消息：需要主密码]";
                case UNREADABLE -> "[加密消息：无法解密]";
                case EMPTY -> "";
            };
        }
    }

    /** 一次加密的落库材料：Base64 密文 + 可直接写进 *_meta 列的 JSON。 */
    public record Encrypted(String cipher, String meta) {
    }

    private final ApiKeyEncryptor encryptor;
    private final ObjectMapper mapper;
    private final TokenEstimator estimator;
    /** 只缓存旧密文的数值估算，不缓存正文、密码或密钥。 */
    private final Map<String, Integer> tokenEstimates = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) {
                    return size() > 4096;
                }
            });

    public ChatMessageCipher(ApiKeyEncryptor encryptor, ObjectMapper mapper) {
        this.encryptor = encryptor;
        this.mapper = mapper;
        this.estimator = new TokenEstimator(mapper);
    }

    /** 加密一段文本；{@code masterPassword} 为空时走设备指纹模式。 */
    public Encrypted encrypt(String plaintext, String masterPassword) {
        if (plaintext == null) throw new ChatCryptoException("聊天加密输入为空");
        String mode = mode(masterPassword);
        try {
            ApiKeyEncryptor.EncryptedValue value = encryptor.encrypt(plaintext, material(mode, masterPassword), mode);
            return new Encrypted(value.encrypted(), meta(value, estimator.estimateText(plaintext)));
        } catch (Exception error) {
            throw new ChatCryptoException("聊天内容加密失败，未写入任何明文", error);
        }
    }

    /** 密文为空即 EMPTY；有密文却没读到材料就按主密码缺失处理。 */
    public Read read(String cipher, String metaJson, String masterPassword) {
        if (cipher == null || cipher.isBlank()) return new Read(ReadStatus.EMPTY, null);
        if (metaJson == null || metaJson.isBlank()) return new Read(ReadStatus.NEEDS_PASSWORD, null);
        String mode;
        try {
            mode = String.valueOf(readMeta(metaJson).getOrDefault("mode", "fingerprint"));
        } catch (Exception error) {
            return new Read(ReadStatus.UNREADABLE, null);
        }
        String material;
        try {
            material = material(mode, masterPassword);
        } catch (ChatCryptoException error) {
            return new Read(ReadStatus.NEEDS_PASSWORD, null);
        }
        try {
            Map<String, Object> meta = readMeta(metaJson);
            String value = encryptor.decrypt(cipher, String.valueOf(meta.get("salt")),
                    String.valueOf(meta.get("iv")), material);
            tokenEstimates.put(estimateKey(cipher, metaJson), estimator.estimateText(value));
            return new Read(ReadStatus.DECRYPTED, value);
        } catch (Exception error) {
            return new Read(ReadStatus.UNREADABLE, null);
        }
    }

    /** 旧行兼容：没有密文时按明文列返回（v8 遗留消息仍要能看）。 */
    public Read readLegacy(String plaintext, String cipher, String metaJson, String masterPassword) {
        Read read = read(cipher, metaJson, masterPassword);
        if (read.status() == ReadStatus.EMPTY) return new Read(ReadStatus.DECRYPTED, plaintext == null ? "" : plaintext);
        return read;
    }

    /**
     * 当前保留文本的 Token 估算。新消息直接读数值元数据，旧消息只在必要时解密；
     * 无法读取时返回 null，绝不把密文长度、占位文案或供应商累计用量当作正文长度。
     */
    public Integer estimateStoredText(String plaintext, String cipher, String metaJson) {
        if (cipher == null || cipher.isBlank()) return plaintext == null ? null : estimator.estimateText(plaintext);
        try {
            var meta = mapper.readTree(metaJson);
            var count = meta.path("textTokens");
            if (TokenEstimator.VERSION.equals(meta.path("tokenEstimatorVersion").asText())
                    && count.isIntegralNumber() && count.canConvertToInt() && count.intValue() >= 0)
                return count.intValue();
        } catch (Exception ignored) { }
        Integer cached = tokenEstimates.get(estimateKey(cipher, metaJson));
        if (cached != null) return cached;
        Read value = read(cipher, metaJson, null);
        return value.status() == ReadStatus.DECRYPTED ? estimator.estimateText(value.value()) : null;
    }

    static String estimateKey(String cipher, String meta) {
        try {
            byte[] bytes = (cipher + '\n' + meta).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    /** 数值附记沿现有加密材料落库，容量账本仍按最终元数据字节计量。 */
    public Encrypted withContextExtraTokens(Encrypted value, Long tokens) {
        return withTokenCount(value, "contextExtraTokens", tokens);
    }

    public Encrypted withContextExtraTokens(Encrypted value, Long tokens,
                                           Map<com.drillnotebook.app.model.RequestContextAudit.Kind, Long> categories) {
        Encrypted counted = withContextExtraTokens(value, tokens);
        if (tokens == null || categories.isEmpty()) return counted;
        var breakdown = new ContextTokenBreakdown(categories);
        if (breakdown.tokens() != tokens) throw new ChatCryptoException("上下文分类与总量不一致");
        try {
            Map<String, Object> meta = new LinkedHashMap<>(readMeta(counted.meta()));
            meta.put("contextExtraCategories", breakdown.categories());
            return new Encrypted(counted.cipher(), mapper.writeValueAsString(meta));
        } catch (Exception error) { throw new ChatCryptoException("上下文分类无法保存", error); }
    }

    public Encrypted withSupplementTextTokens(Encrypted value, long tokens) {
        return withTokenCount(value, "supplementTextTokens", tokens);
    }

    private Encrypted withTokenCount(Encrypted value, String key, Long tokens) {
        if (tokens == null) return value;
        if (tokens < 0) throw new ChatCryptoException("上下文数值无效");
        try {
            Map<String, Object> meta = new LinkedHashMap<>(readMeta(value.meta()));
            meta.put(key, tokens);
            return new Encrypted(value.cipher(), mapper.writeValueAsString(meta));
        } catch (Exception error) { throw new ChatCryptoException("上下文数值无法保存", error); }
    }

    /** 这份密文用的是哪种材料，供"要不要问密码"的判定。 */
    public String modeOf(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) return "fingerprint";
        try {
            return String.valueOf(readMeta(metaJson).getOrDefault("mode", "fingerprint"));
        } catch (Exception error) {
            return "fingerprint";
        }
    }

    private Map<String, Object> readMeta(String metaJson) throws Exception {
        return mapper.readValue(metaJson, new TypeReference<>() {
        });
    }

    private String meta(ApiKeyEncryptor.EncryptedValue value, int textTokens) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("salt", value.salt());
        meta.put("iv", value.iv());
        meta.put("kdf", "Argon2id");
        meta.put("algorithm", "AES-256-GCM");
        meta.put("mode", value.mode());
        meta.put("version", 1);
        meta.put("tokenEstimatorVersion", TokenEstimator.VERSION);
        meta.put("textTokens", textTokens);
        return mapper.writeValueAsString(meta);
    }

    private static String mode(String masterPassword) {
        return masterPassword == null || masterPassword.isBlank() ? "fingerprint" : "password";
    }

    private String material(String mode, String masterPassword) {
        if (!"password".equals(mode)) return encryptor.fingerprintMaterial();
        if (masterPassword == null || masterPassword.isBlank()) {
            throw new ChatCryptoException("该聊天需要主密码");
        }
        return masterPassword;
    }
}
