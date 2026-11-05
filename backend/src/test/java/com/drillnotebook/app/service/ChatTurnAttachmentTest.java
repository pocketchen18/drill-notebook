package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatLegacyWiring;
import com.drillnotebook.app.repository.ChatStorageLedger;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.ChatTurnRepository.AttachmentRow;
import com.drillnotebook.app.service.ChatTurnService.BeginInput;
import com.drillnotebook.app.service.ChatTurnService.BeginResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 加密附件与 source block 身份（contracts §4.3、执行方案 §7.2-2）：有界校验都在写库和外发之前，
 * 落库只有密文，块身份与轮密钥都在 request_meta 里，跨行搬运密文必然认证失败。
 */
class ChatTurnAttachmentTest {
    private Path appRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatTurnService service;
    private ChatTurnRepository turns;
    private ChatStorageLedger ledger;
    private ObjectMapper mapper;
    private long sessionId;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p3c-attach-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        jdbc = new JdbcTemplate(dataSource);
        wiring = ChatLegacyWiring.wire(dataSource, jdbc);
        service = wiring.turnService();
        turns = wiring.turns();
        ledger = wiring.ledger();
        mapper = new ObjectMapper();
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE ai_chat_session SET memory_learn_enabled = 1, title = '我的复习' WHERE id = ?", sessionId);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void attachmentsWriteOnlyCiphertextAndAccountForAttachmentBytes() throws Exception {
        String png = base64(png(4, 3));
        BeginResult begun = service.begin(input("看图说话", List.of(
                item("c1", "image", "图.png", "image/png", png, null),
                item("c2", "text", "笔记.txt", "text/plain", null, "正文片段"))));

        List<AttachmentRow> rows = turns.findAttachments(begun.lease().turnId());
        assertEquals(2, rows.size());
        assertEquals("IMAGE", rows.get(0).kind());
        assertEquals("TEXT", rows.get(1).kind());
        assertEquals(List.of("a0", "a1"), rows.stream().map(AttachmentRow::blockId).toList());
        for (AttachmentRow row : rows) {
            assertTrue(row.payloadCipher().length > 0);
            // byte_length 由服务端算：密文 BLOB + crypto_meta 的 UTF-8 长度，客户端声明一概不算
            assertEquals(row.payloadCipher().length + row.cryptoMeta().getBytes(StandardCharsets.UTF_8).length,
                    row.byteLength());
        }
        assertEquals(rows.stream().mapToLong(AttachmentRow::byteLength).sum(),
                ledger.read().attachmentBytes(), "附件密文进 attachment_bytes，不混进历史额度");
        assertFalse(dbContains(png), "base64 原串不得落进库文件");
        assertFalse(dbContains("正文片段"), "附件正文不得落明文");
        assertFalse(dbContains("看图说话"), "用户原话也不落明文");

        Map<String, Object> meta = requestMeta(begun.lease().requestId());
        assertEquals(2, meta.get("schemaVersion"), "契约 §2.2 明写 request_meta 必须 schemaVersion=2");
        assertEquals(2, meta.get("attachmentCount"));
        List<Map<String, Object>> blocks = blocks(meta);
        assertEquals(List.of("u0", "a0", "a1"), blocks.stream().map(b -> String.valueOf(b.get("blockId"))).toList());
        // u0 自带正文（§4.3"USER_AUTHORED含用户正文"），P4/P7 的 quote 块内匹配才不需要回表解密
        assertEquals("USER_AUTHORED", blocks.get(0).get("origin"));
        assertEquals("看图说话", blocks.get(0).get("text"));
        assertEquals("ATTACHMENT_IMAGE", blocks.get(1).get("origin"));
        assertEquals("ATTACHMENT_TEXT", blocks.get(2).get("origin"));
        assertEquals("c1", blocks.get(1).get("clientId"));
        assertNotNull(blocks.get(1).get("digest"), "§2.2 要 request_meta 带有序附件内容指纹");
        // 附件块只带 ID，正文/图片内容绝不进 metadata
        assertFalse(blocks.get(1).containsKey("data"));
        assertFalse(blocks.get(1).containsKey("text"));
        assertNotNull(meta.get("attachmentKey"));
    }

    @Test
    void attachmentRoundTripReadsBackTheOriginalEnvelope() throws Exception {
        byte[] image = png(8, 8);
        BeginResult begun = service.begin(input("原图", List.of(
                item("c1", "image", "截图.png", "image/png", base64(image), null))));
        String requestId = begun.lease().requestId();

        AttachmentRow row = turns.findAttachments(begun.lease().turnId()).get(0);
        ChatAttachmentCipher.TurnKey key = unwrapKey(requestId);
        String envelope = new String(wiring.attachmentCipher().unseal(key, requestId, row.id(), row.blockId(),
                row.payloadCipher(), row.cryptoMeta()), StandardCharsets.UTF_8);
        Map<String, Object> parsed = mapper.readValue(envelope, new TypeReference<>() {
        });

        assertEquals("截图.png", parsed.get("name"));
        assertEquals("image/png", parsed.get("mimeType"));
        assertEquals(image.length, parsed.get("byteLength"));
        assertArrayEqualsEquivalent(image, (String) parsed.get("data"));
        key.zero();
    }

    @Test
    void movingCiphertextToAnotherRowFailsAuthentication() throws Exception {
        BeginResult begun = service.begin(input("两份附件", List.of(
                item("c1", "image", "一.png", "image/png", base64(png(2, 2)), null),
                item("c2", "image", "二.png", "image/png", base64(png(3, 3)), null))));
        List<AttachmentRow> rows = turns.findAttachments(begun.lease().turnId());
        ChatAttachmentCipher cipher = wiring.attachmentCipher();
        ChatAttachmentCipher.TurnKey key = unwrapKey(begun.lease().requestId());

        // 同一把轮密钥、同一条 requestId：只把 A 的密文当成 B 来解，AAD 不一致必须认证失败
        assertThrows(ChatCryptoException.class, () -> cipher.unseal(key, begun.lease().requestId(),
                rows.get(1).id(), rows.get(1).blockId(), rows.get(0).payloadCipher(), rows.get(0).cryptoMeta()));
        // 换 requestId 也一样解不开（AAD 绑到请求身份）
        assertThrows(ChatCryptoException.class, () -> cipher.unseal(key, UuidV7.generate(System.currentTimeMillis()),
                rows.get(0).id(), rows.get(0).blockId(), rows.get(0).payloadCipher(), rows.get(0).cryptoMeta()));
        key.zero();
    }

    @Test
    void duplicateRequestIdWithDifferentAttachmentIsConflict() throws Exception {
        BeginInput first = input("同一请求", List.of(
                item("c1", "image", "一.png", "image/png", base64(png(2, 2)), null)));
        BeginResult begun = service.begin(first);
        service.finalizeTurn(begun.lease(), ChatTurnService.UpstreamReply.of("好", null, "stop"), null);

        // 同 ID 换一张图：输入指纹覆盖附件，必须算冲突而不是重放
        BeginInput second = sameId(first, List.of(
                item("c1", "image", "一.png", "image/png", base64(png(5, 5)), null)));
        ChatTurnRejectException conflict = assertThrows(ChatTurnRejectException.class, () -> service.begin(second));
        assertEquals(409, conflict.status());
        assertEquals("REQUEST_CONFLICT", conflict.code());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_chat_attachment"));
    }

    @Test
    void v1AttachmentChannelIsRefusedBeforeAnyWrite() {
        BeginInput legacy = new BeginInput(UuidV7.generate(System.currentTimeMillis()), 1, sessionId, "旧客户端",
                "", List.of(item("c1", "text", "n", "text/plain", null, "内容")), Map.of());

        ChatTurnRejectException refused = assertThrows(ChatTurnRejectException.class, () -> service.begin(legacy));
        assertEquals(422, refused.status());
        assertEquals("ATTACHMENTS_REQUIRE_V2", refused.code());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"));
    }

    @Test
    void oversizedAttachmentsAreRejectedWithoutWritingOrDispatching() throws Exception {
        assertRejected(413, "ATTACHMENT_TOO_MANY", List.of(
                textItem("c1"), textItem("c2"), textItem("c3"), textItem("c4"), textItem("c5")));
        assertRejected(413, "ATTACHMENT_TOO_LARGE", List.of(
                item("c1", "image", "大图.png", "image/png", "A".repeat(7_000_000), null)));
        assertRejected(413, "ATTACHMENT_IMAGE_TOO_LARGE", List.of(
                item("c1", "image", "超大.png", "image/png", base64(png(5001, 5000)), null)));
        assertRejected(413, "ATTACHMENT_TEXT_TOO_LARGE", List.of(
                item("c1", "text", "长文.txt", "text/plain", null, "字".repeat(200_001))));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_attachment"));
        assertEquals(0, ledger.read().attachmentBytes());
        assertEquals(0, ledger.read().reservedHistoryBytes(), "被拒的请求不能占走历史预留");
    }

    @Test
    void urlPathsAndSpoofedOriginsAreRejected() throws Exception {
        assertRejected(422, "ATTACHMENT_URL_FORBIDDEN", List.of(Map.of(
                "clientId", "c1", "kind", "image", "url", "https://example.com/a.png")));
        assertRejected(422, "ATTACHMENT_URL_FORBIDDEN", List.of(Map.of(
                "clientId", "c1", "kind", "image", "mimeType", "image/png",
                "dataBase64", "data:image/png;base64," + base64(png(2, 2)))));
        assertRejected(422, "ATTACHMENT_ORIGIN_FORBIDDEN", List.of(Map.of(
                "clientId", "c1", "kind", "text", "text", "我自称用户正文", "origin", "USER_AUTHORED")));
        assertRejected(422, "ATTACHMENT_FIELDS_INVALID", List.of(Map.of(
                "clientId", "c1", "kind", "text", "text", "有正文", "dataBase64", base64(png(2, 2)))));
        assertRejected(422, "ATTACHMENT_TYPE_REJECTED", List.of(
                item("c1", "image", "伪装.png", "image/jpeg", base64(png(2, 2)), null)));
        assertRejected(422, "ATTACHMENT_TYPE_REJECTED", List.of(
                item("c1", "image", "动图.gif", "image/gif", base64(png(2, 2)), null)));
        assertRejected(422, "ATTACHMENT_DECODE_FAILED", List.of(
                item("c1", "image", "坏图.png", "image/png", base64(truncatedPng()), null)));
        assertRejected(422, "ATTACHMENT_CLIENT_ID_INVALID", List.of(Map.of(
                "kind", "text", "text", "没有 clientId")));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"));
    }

    @Test
    void attachmentQuotaFullStartsNothing() throws Exception {
        jdbc.update("UPDATE ai_chat_storage SET attachment_limit_bytes = attachment_bytes + 200");

        ChatTurnRejectException refused = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(input("额度不够", List.of(
                        item("c1", "image", "图.png", "image/png", base64(png(64, 64)), null)))));

        assertEquals(409, refused.status());
        assertEquals("ATTACHMENT_STORAGE_FULL", refused.code());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_message"));
        assertEquals(0, ledger.read().reservedHistoryBytes(), "附件额度不足时历史预留也不许占");
    }

    @Test
    void oversizedOptionsAreRejectedInsteadOfEatingHistoryQuota() {
        Map<String, Object> huge = Map.of("retrievalOptions", Map.of("scope", "x".repeat(5_000)));
        BeginInput input = new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId, "元数据灌水",
                "", List.of(), huge);

        ChatTurnRejectException refused = assertThrows(ChatTurnRejectException.class, () -> service.begin(input));

        assertEquals(413, refused.status());
        assertEquals("REQUEST_OPTIONS_TOO_LARGE", refused.code());
        assertEquals(0, count("SELECT COUNT(*) FROM ai_chat_turn"));
    }

    @Test
    void longInputWithAttachmentsKeepsWholeRequestMeta() throws Exception {
        // 正文进 u0 后 request_meta 会随输入变长：这里证明它绝不被静默截断（丢了 attachmentKey 附件就是死数据）
        String longText = "长".repeat(9_000);
        BeginResult begun = service.begin(new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId,
                longText, "", List.of(item("c1", "image", "图.png", "image/png", base64(png(4, 4)), null)), Map.of()));

        Map<String, Object> meta = requestMeta(begun.lease().requestId());
        assertEquals(longText, blocks(meta).get(0).get("text"));
        assertNotNull(meta.get("attachmentKey"));
        assertFalse(Boolean.TRUE.equals(meta.get("truncatedMeta")));
        service.finalizeTurn(begun.lease(), ChatTurnService.UpstreamReply.of("收到", null, "stop"), null);
        assertTrue(ledger.read().historyBytes() > longText.getBytes(StandardCharsets.UTF_8).length,
                "正文与 u0 块都按确切密文计入历史，不是免费的");
    }

    // ---- 辅助 ----

    private BeginInput input(String text, List<Map<String, Object>> attachments) {
        return new BeginInput(UuidV7.generate(System.currentTimeMillis()), 2, sessionId, text, "",
                attachments, Map.of());
    }

    private static BeginInput sameId(BeginInput source, List<Map<String, Object>> attachments) {
        return new BeginInput(source.requestId(), source.protocolVersion(), source.sessionId(), source.userText(),
                source.masterPassword(), attachments, source.requestOptions());
    }

    private static Map<String, Object> item(String clientId, String kind, String name, String mime,
                                            String dataBase64, String text) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("clientId", clientId);
        map.put("kind", kind);
        map.put("name", name);
        map.put("mimeType", mime);
        if (dataBase64 != null) map.put("dataBase64", dataBase64);
        if (text != null) map.put("text", text);
        return map;
    }

    private static Map<String, Object> textItem(String clientId) {
        return item(clientId, "text", "n" + clientId, "text/plain", null, "一小段文本");
    }

    private void assertRejected(int status, String code, List<Map<String, Object>> attachments) {
        ChatTurnRejectException error = assertThrows(ChatTurnRejectException.class,
                () -> service.begin(input("校验失败也不写库", attachments)));
        assertEquals(status, error.status(), code + " 实际 " + error.code() + "/" + error.status());
        assertEquals(code, error.code());
    }

    private Map<String, Object> requestMeta(String requestId) throws Exception {
        ChatTurnRepository.MetaColumns columns = turns.findRequestMeta(requestId).orElseThrow();
        ChatMessageCipher.Read read = wiring.cipher().read(columns.cipher(), columns.crypto(), "");
        assertEquals(ChatMessageCipher.ReadStatus.DECRYPTED, read.status());
        return mapper.readValue(read.value(), new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> blocks(Map<String, Object> meta) {
        return (List<Map<String, Object>>) meta.get("sourceBlocks");
    }

    private ChatAttachmentCipher.TurnKey unwrapKey(String requestId) throws Exception {
        Map<String, Object> key = (Map<String, Object>) requestMeta(requestId).get("attachmentKey");
        return wiring.attachmentCipher().unwrapKey(String.valueOf(key.get("cipher")),
                String.valueOf(key.get("meta")), "");
    }

    private void assertArrayEqualsEquivalent(byte[] expected, String base64) {
        assertTrue(java.util.Arrays.equals(expected, Base64.getDecoder().decode(base64)));
    }

    /** 最小合法 PNG 头：签名 + IHDR 宽高，够签名与尺寸校验用，不需要真像素。 */
    private static byte[] png(int width, int height) {
        byte[] bytes = new byte[41];
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int index = 0; index < 8; index++) bytes[index] = (byte) signature[index];
        bytes[11] = 13;
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        bytes[16] = (byte) (width >> 24);
        bytes[17] = (byte) (width >> 16);
        bytes[18] = (byte) (width >> 8);
        bytes[19] = (byte) width;
        bytes[20] = (byte) (height >> 24);
        bytes[21] = (byte) (height >> 16);
        bytes[22] = (byte) (height >> 8);
        bytes[23] = (byte) height;
        bytes[24] = 8;
        return bytes;
    }

    /** 签名正确但头部被截断：过了类型关，尺寸解析必须失败。 */
    private static byte[] truncatedPng() {
        byte[] bytes = new byte[18];
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int index = 0; index < 8; index++) bytes[index] = (byte) signature[index];
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private boolean dbContains(String text) throws Exception {
        byte[] raw = Files.readAllBytes(appRoot.resolve("data/study.db"));
        String asLatin = new String(raw, StandardCharsets.ISO_8859_1);
        return asLatin.contains(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }
}
