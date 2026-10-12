package com.drillnotebook.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 聊天附件的每轮密钥与封装载荷（contracts §4.3）：一轮一把随机 DEK，
 * AAD 绑 {@code chat|requestId|attachmentId|blockId|version}，所以把某行的密文搬到另一行必然解不开。
 *
 * <p>与记忆库完全无关：DEK 只用会话侧 fingerprint/password 材料包装（复用 {@link ChatMessageCipher}），
 * 包装结果放进 {@code request_meta}；未建 vault、vault 锁定时附件照常可写，读取才需要相应材料。
 * 加密失败只抛 {@link ChatCryptoException}，绝不落明文。</p>
 */
@Component
public class ChatAttachmentCipher {
    /** 附件载荷的加密版本，同时进 AAD 与 crypto_meta。 */
    public static final int PAYLOAD_VERSION = 1;
    private static final int DEK_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** 本轮的明文 DEK：只在一次开始事务的生命周期内存在，用完必须 {@link #zero()}。 */
    public static final class TurnKey {
        private final byte[] dek;

        TurnKey(byte[] dek) {
            if (dek == null || dek.length != DEK_BYTES) throw new ChatCryptoException("附件轮密钥长度不合法");
            this.dek = dek.clone();
        }

        public void zero() {
            Arrays.fill(dek, (byte) 0);
        }

        byte[] raw() {
            return dek.clone();
        }
    }

    /** 落库材料：密文 BLOB + 写进 crypto_meta 的 JSON。 */
    public record Sealed(byte[] payloadCipher, String cryptoMeta) {
    }

    private final ChatMessageCipher messageCipher;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public ChatAttachmentCipher(ChatMessageCipher messageCipher, ObjectMapper mapper) {
        this.messageCipher = messageCipher;
        this.mapper = mapper;
    }

    /** 每轮一把新的随机 DEK；同一轮的多个附件共用，避免对每个大 BLOB 重跑 Argon2（§4.3）。 */
    public TurnKey randomTurnKey() {
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        return new TurnKey(dek);
    }

    /** DEK 的包装形态：能进 request_meta 的只有密文与材料，明文密钥不外流。 */
    public ChatMessageCipher.Encrypted wrapKey(TurnKey key, String masterPassword) {
        return messageCipher.encrypt(Base64.getEncoder().encodeToString(key.raw()), masterPassword);
    }

    /** 从 request_meta 的包装材料还原 DEK；需要主密码或解不开时按聊天侧同样的语义抛错。 */
    public TurnKey unwrapKey(String wrappedCipher, String wrappedMeta, String masterPassword) {
        ChatMessageCipher.Read read = messageCipher.read(wrappedCipher, wrappedMeta, masterPassword);
        switch (read.status()) {
            case NEEDS_PASSWORD -> throw new ChatCryptoException("读取附件需要主密码");
            case EMPTY, UNREADABLE -> throw new ChatCryptoException("附件轮密钥不可解");
            default -> {
            }
        }
        return new TurnKey(Base64.getDecoder().decode(read.value()));
    }

    /** 加密一份附件载荷；AAD 由调用方给的三段身份决定，缺一位都解不开。 */
    public Sealed seal(TurnKey key, String requestId, String attachmentId, String blockId, byte[] payload) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.raw(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(requestId, attachmentId, blockId));
            return new Sealed(cipher.doFinal(payload), cryptoMeta(iv, requestId, attachmentId, blockId));
        } catch (Exception error) {
            throw new ChatCryptoException("附件加密失败，未写入任何明文", error);
        }
    }

    /**
     * 解密一份附件载荷。{@code cryptoMeta} 里记的 AAD 只是给人看的参考：
     * 真实 AAD 一律按本行的 requestId/attachmentId/blockId 重算，所以换行、换块都会认证失败。
     */
    public byte[] unseal(TurnKey key, String requestId, String attachmentId, String blockId,
                         byte[] payloadCipher, String cryptoMeta) {
        try {
            Map<String, Object> meta = mapper.readValue(cryptoMeta, new TypeReference<>() {
            });
            byte[] iv = Base64.getDecoder().decode(String.valueOf(meta.get("iv")));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.raw(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(requestId, attachmentId, blockId));
            return cipher.doFinal(payloadCipher);
        } catch (ChatCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new ChatCryptoException("附件解密失败", error);
        }
    }

    private static byte[] aad(String requestId, String attachmentId, String blockId) {
        return ("chat|" + requestId + "|" + attachmentId + "|" + blockId + "|" + PAYLOAD_VERSION)
                .getBytes(StandardCharsets.UTF_8);
    }

    private String cryptoMeta(byte[] iv, String requestId, String attachmentId, String blockId) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("iv", Base64.getEncoder().encodeToString(iv));
        meta.put("algorithm", "AES-256-GCM");
        meta.put("keyWrap", "request_meta.attachmentKey");
        meta.put("aad", "chat|" + requestId + "|" + attachmentId + "|" + blockId + "|" + PAYLOAD_VERSION);
        meta.put("version", PAYLOAD_VERSION);
        return mapper.writeValueAsString(meta);
    }
}
