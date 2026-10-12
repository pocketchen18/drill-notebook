package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 聊天内容加密材料（执行方案 §6.1）：写入只有密文一条路，读取要能区分「需要主密码」与「解不开」。
 * 前者换个密码就能读，后者是数据真坏了，文案不能混。
 */
class ChatMessageCipherTest {
    private final ChatMessageCipher cipher = new ChatMessageCipher(new ApiKeyEncryptor(), new ObjectMapper());

    @Test
    void fingerprintModeRoundTripsWithoutPassword() {
        ChatMessageCipher.Encrypted written = cipher.encrypt("解释垃圾回收", "");

        assertTrue(written.cipher().length() > 0);
        assertTrue(written.meta().contains("\"mode\":\"fingerprint\""), "无主密码时按设备指纹模式");
        assertEquals("解释垃圾回收", cipher.read(written.cipher(), written.meta(), "").value());
        assertNotEquals(written.cipher(), cipher.encrypt("解释垃圾回收", "").cipher(), "每次写都换 IV 与 salt");
    }

    @Test
    void passwordModeNeedsTheSamePasswordBack() {
        ChatMessageCipher.Encrypted written = cipher.encrypt("机密正文", "主密码A");

        assertTrue(written.meta().contains("\"mode\":\"password\""));
        assertEquals("机密正文", cipher.read(written.cipher(), written.meta(), "主密码A").value());
        assertEquals(ChatMessageCipher.ReadStatus.UNREADABLE,
                cipher.read(written.cipher(), written.meta(), "主密码B").status(), "错密码不能冒充空内容");
        assertEquals(ChatMessageCipher.ReadStatus.NEEDS_PASSWORD,
                cipher.read(written.cipher(), written.meta(), "").status(), "缺密码要提示问密码");
        assertEquals("[加密消息：需要主密码]",
                cipher.read(written.cipher(), written.meta(), "").orSentinel());
    }

    @Test
    void legacyPlaintextRowStillReadsAndEmptyStaysEmpty() {
        ChatMessageCipher.Read legacy = cipher.readLegacy("v8 时代的明文", null, null, "");

        assertEquals(ChatMessageCipher.ReadStatus.DECRYPTED, legacy.status());
        assertEquals("v8 时代的明文", legacy.value());
        assertEquals(ChatMessageCipher.ReadStatus.EMPTY, cipher.read(null, null, "").status());
    }

    @Test
    void brokenMetaIsUnreadableInsteadOfThrowingAwayTheHistory() {
        assertEquals(ChatMessageCipher.ReadStatus.UNREADABLE, cipher.read("AAAA", "不是 JSON", "").status());
    }

    @Test
    void encryptFailureFailsClosedInsteadOfReturningPlaintext() {
        ChatMessageCipher broken = new ChatMessageCipher(new ApiKeyEncryptor() {
            @Override
            public EncryptedValue encrypt(String plaintext, String material, String mode) throws Exception {
                throw new IllegalStateException("KDF 不可用");
            }
        }, new ObjectMapper());

        ChatCryptoException error = assertThrows(ChatCryptoException.class, () -> broken.encrypt("原话", ""));
        assertTrue(error.getMessage().contains("未写入任何明文"));
    }
}
