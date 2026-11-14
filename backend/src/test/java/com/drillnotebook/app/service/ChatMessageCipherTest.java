package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void newEncryptedTextCarriesOnlyNumericEstimatesAndCountsEvenWhenLocked() throws Exception {
        String text = "锁定正文abcd";
        var written = cipher.encrypt(text, "test-password");
        var mapper = new ObjectMapper();
        var meta = mapper.readTree(written.meta());
        assertEquals(TokenEstimator.VERSION, meta.path("tokenEstimatorVersion").asText());
        assertEquals(new TokenEstimator(mapper).estimateText(text), meta.path("textTokens").intValue());
        assertFalse(written.meta().contains(text));
        assertFalse(written.meta().contains("test-password"));
        assertEquals(ChatMessageCipher.ReadStatus.NEEDS_PASSWORD, cipher.read(written.cipher(), written.meta(), null).status());
        assertEquals(meta.path("textTokens").intValue(), cipher.estimateStoredText(null, written.cipher(), written.meta()));
        var extras = cipher.withContextExtraTokens(written, 123L);
        assertEquals(123L, mapper.readTree(extras.meta()).path("contextExtraTokens").longValue());
        assertEquals(text, cipher.read(extras.cipher(), extras.meta(), "test-password").value());
        assertEquals(12L, mapper.readTree(cipher.withSupplementTextTokens(written, 12).meta()).path("supplementTextTokens").longValue());
        assertThrows(ChatCryptoException.class, () -> cipher.withContextExtraTokens(written, -1L));
        var classified = cipher.withContextExtraTokens(written, 123L,
                java.util.Map.of(com.drillnotebook.app.model.RequestContextAudit.Kind.TOOL_RESULTS, 123L));
        assertEquals(123L, mapper.readTree(classified.meta()).path("contextExtraCategories").path("TOOL_RESULTS").longValue());
        assertFalse(classified.meta().contains(text));
        assertEquals(written.cipher(), classified.cipher(), "分类只存数字，不重写受保护正文");
        assertThrows(ChatCryptoException.class, () -> cipher.withContextExtraTokens(written, 124L,
                java.util.Map.of(com.drillnotebook.app.model.RequestContextAudit.Kind.TOOL_RESULTS, 123L)));
    }

    @Test
    void oldCiphertextUsesNumericCacheAfterDecryptAndUnknownIsNeverAZero() throws Exception {
        var mapper = new ObjectMapper();
        var written = cipher.encrypt("abcd汉字", "test-password");
        var meta = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(written.meta());
        meta.remove("textTokens"); meta.remove("tokenEstimatorVersion");
        String oldMeta = mapper.writeValueAsString(meta);
        assertNull(cipher.estimateStoredText(null, written.cipher(), oldMeta));
        assertEquals("abcd汉字", cipher.read(written.cipher(), oldMeta, "test-password").value());
        assertEquals(4, cipher.estimateStoredText(null, written.cipher(), oldMeta));
        assertEquals(4, cipher.estimateStoredText(null, written.cipher(), oldMeta));
        assertNull(cipher.estimateStoredText(null, "AAAA", "not-json"));
        assertNull(cipher.estimateStoredText(null, null, null));
        assertEquals(0, cipher.estimateStoredText("", null, null));
        assertEquals(4, cipher.estimateStoredText("abcd汉字", null, null));
    }

    @Test
    void incompatibleOrInvalidNumericMetadataDoesNotPretendToBeKnown() throws Exception {
        var mapper = new ObjectMapper();
        var written = cipher.encrypt("private", "test-password");
        for (Object count : java.util.List.of(-1, 1.5, "12", (long) Integer.MAX_VALUE + 1)) {
            var meta = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(written.meta());
            meta.set("textTokens", mapper.valueToTree(count));
            assertNull(cipher.estimateStoredText(null, written.cipher(), mapper.writeValueAsString(meta)));
        }
        var meta = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(written.meta());
        meta.put("tokenEstimatorVersion", "future-version");
        assertNull(cipher.estimateStoredText(null, written.cipher(), mapper.writeValueAsString(meta)));
    }
}
