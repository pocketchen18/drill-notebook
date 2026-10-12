package com.drillnotebook.app.service;

import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatTurnRepository.AttachmentPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 聊天附件的有界校验与封装（contracts §4.3、执行方案 §7.2-2）：在开始事务之前把客户端给的
 * {@code attachments[]} 变成落库材料与 {@code request_meta} 里的有序块身份。
 *
 * <p>只接受 {@code text}/{@code dataBase64} 两条通道，任何 URL、文件路径都拒绝；类型以文件签名为准，
 * 不把扩展名或客户端声明当可信。全部拒绝都发生在写库和外发之前，失败不留半轮。</p>
 */
@Component
public class ChatAttachmentService {
    static final int MAX_ITEMS = 4;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_IMAGES_TOTAL_BYTES = 12L * 1024 * 1024;
    static final int MAX_TEXT_CODEPOINTS = 200_000;
    static final long MAX_PIXELS = 25_000_000L;
    private static final int MAX_CLIENT_ID_CODEPOINTS = 64;
    private static final int MAX_NAME_CODEPOINTS = 255;

    /** 本轮附件的产出：落库材料，以及 {@code request_meta} 里按请求顺序排的块身份。 */
    public record Prepared(List<AttachmentPayload> payloads, List<Map<String, Object>> blocks) {
    }

    private final ChatAttachmentCipher cipher;
    private final ObjectMapper mapper;

    public ChatAttachmentService(ChatAttachmentCipher cipher, ObjectMapper mapper) {
        this.cipher = cipher;
        this.mapper = mapper;
    }

    /** 本轮随机 DEK；包装与封装都用它，用完由调用方立即 {@code zero()}。 */
    public ChatAttachmentCipher.TurnKey newTurnKey() {
        return cipher.randomTurnKey();
    }

    /** DEK 的包装形态，只有密文可以进 request_meta。 */
    public ChatMessageCipher.Encrypted wrapKey(ChatAttachmentCipher.TurnKey key, String masterPassword) {
        return cipher.wrapKey(key, masterPassword);
    }

    /**
     * 校验并封装本轮附件。
     *
     * @param key 本轮的随机 DEK，由调用方在事务结束后 {@code zero()}
     */
    public Prepared prepare(ChatAttachmentCipher.TurnKey key, String requestId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return new Prepared(List.of(), List.of());
        if (items.size() > MAX_ITEMS) {
            throw reject(413, "ATTACHMENT_TOO_MANY", "一次最多附带 " + MAX_ITEMS + " 项",
                    Map.of("limit", MAX_ITEMS, "received", items.size()));
        }
        List<AttachmentPayload> payloads = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        long imageBytesTotal = 0;
        long textCodePoints = 0;

        for (int position = 0; position < items.size(); position++) {
            Map<String, Object> item = items.get(position);
            String kind = normalizedKind(item.get("kind"));
            String clientId = boundedString(item.get("clientId"), MAX_CLIENT_ID_CODEPOINTS,
                    "ATTACHMENT_CLIENT_ID_INVALID");
            if (clientId.isBlank()) {
                throw reject(422, "ATTACHMENT_CLIENT_ID_INVALID", "每项附件都要带 clientId 用于本次关联", Map.of());
            }
            rejectForcedChannels(item, position);
            String name = boundedString(item.get("name"), MAX_NAME_CODEPOINTS, "ATTACHMENT_NAME_INVALID");
            String mime = boundedString(item.get("mimeType"), MAX_NAME_CODEPOINTS, "ATTACHMENT_MIME_INVALID")
                    .toLowerCase(java.util.Locale.ROOT);
            String attachmentId = UuidV7.generate(System.currentTimeMillis());
            // 块号按请求顺序统一编号：分类型各起计数会让同轮的图片与文本撞出同一个 a0（UNIQUE(turn_id,block_id)）
            String blockId = "a" + position;

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("name", name);
            envelope.put("mimeType", mime);
            String digest;

            if ("image".equals(kind)) {
                byte[] decoded = decodeImage(mime, item.get("dataBase64"), position);
                if (imageBytesTotal + decoded.length > MAX_IMAGES_TOTAL_BYTES) {
                    throw reject(413, "ATTACHMENT_IMAGES_TOO_LARGE", "本轮图片合计超过 12 MiB",
                            Map.of("limitBytes", MAX_IMAGES_TOTAL_BYTES));
                }
                imageBytesTotal += decoded.length;
                envelope.put("byteLength", decoded.length);
                envelope.put("data", java.util.Base64.getEncoder().encodeToString(decoded));
                digest = digest(decoded);
            } else {
                String text = textOf(item.get("text"), position);
                int codePoints = text.codePointCount(0, text.length());
                if (textCodePoints + codePoints > MAX_TEXT_CODEPOINTS) {
                    throw reject(413, "ATTACHMENT_TEXT_TOO_LARGE", "文本附件合计超过 200,000 码点",
                            Map.of("limitCodePoints", MAX_TEXT_CODEPOINTS));
                }
                textCodePoints += codePoints;
                envelope.put("byteLength", text.getBytes(StandardCharsets.UTF_8).length);
                envelope.put("text", text);
                digest = digest(text.getBytes(StandardCharsets.UTF_8));
            }

            byte[] plain;
            try {
                plain = mapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw reject(422, "ATTACHMENT_UNENCODABLE", "附件载荷无法封装", Map.of());
            }
            ChatAttachmentCipher.Sealed sealed = cipher.seal(key, requestId, attachmentId, blockId, plain);
            payloads.add(new AttachmentPayload(attachmentId, blockId, "image".equals(kind) ? "IMAGE" : "TEXT",
                    sealed.payloadCipher(), sealed.cryptoMeta()));
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("blockId", blockId);
            // 契约用 origin 表达来源通道；附件永远拿不到 USER_AUTHORED（§4.3）
            block.put("origin", "image".equals(kind) ? "ATTACHMENT_IMAGE" : "ATTACHMENT_TEXT");
            block.put("attachmentId", attachmentId);
            block.put("clientId", clientId);
            // §2.2 要求 request_meta 带"有序附件内容指纹"，直接挂在块上，省掉一份会跑偏的平行数组
            block.put("digest", digest);
            blocks.add(block);
        }
        return new Prepared(payloads, blocks);
    }

    /**
     * 块顺序即身份顺序：u0 恒为服务端创建的用户正文块，且按契约自带正文
     * （§4.3"USER_AUTHORED含用户正文"、§2.2"blockId/origin/text或attachmentId"），
     * 这样 P4/P7 的 quote 块内连续匹配不必再回表解密；附件块只带 ID 与内容指纹。
     */
    public static List<Map<String, Object>> orderedBlocks(String userText, List<Map<String, Object>> attachmentBlocks) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("blockId", "u0");
        user.put("origin", "USER_AUTHORED");
        user.put("text", userText == null ? "" : userText);
        blocks.add(user);
        blocks.addAll(attachmentBlocks);
        return blocks;
    }

    /**
     * 输入指纹要覆盖附件，但不能把几 MiB 的 base64 直接拼进待哈希字符串，
     * 所以压成"身份字段 + 收到的内容摘要"。摘要按收到的原串算，同 ID 换一张图就不算同一个请求。
     */
    public static String identity(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> item : items) {
            builder.append(String.valueOf(item.get("clientId"))).append('/')
                    .append(String.valueOf(item.get("kind"))).append('/')
                    .append(String.valueOf(item.get("mimeType"))).append('/')
                    .append(digest(String.valueOf(item.get("text") != null ? item.get("text")
                            : String.valueOf(item.get("dataBase64"))).getBytes(StandardCharsets.UTF_8))).append(';');
        }
        return builder.toString();
    }

    private byte[] decodeImage(String mime, Object rawBase64, int position) {
        if (rawBase64 == null) {
            throw reject(422, "ATTACHMENT_FIELDS_INVALID", "第 " + (position + 1) + " 项图片缺少 dataBase64", Map.of());
        }
        if (!(rawBase64 instanceof String value)) {
            throw reject(422, "ATTACHMENT_FIELDS_INVALID", "dataBase64 必须是字符串", Map.of());
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("data:") || trimmed.contains(",")) {
            throw reject(422, "ATTACHMENT_URL_FORBIDDEN", "图片只接受纯 base64，不接受 data URL", Map.of());
        }
        if (trimmed.length() * 3L / 4 > MAX_IMAGE_BYTES) {
            throw reject(413, "ATTACHMENT_TOO_LARGE", "单张图片解码后不能超过 5 MiB",
                    Map.of("limitBytes", MAX_IMAGE_BYTES));
        }
        byte[] decoded;
        try {
            decoded = java.util.Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException error) {
            throw reject(422, "ATTACHMENT_BASE64_INVALID", "第 " + (position + 1) + " 项图片不是合法 base64",
                    Map.of());
        }
        if (!WHITELIST_MIMES.contains(mime)) {
            throw reject(422, "ATTACHMENT_TYPE_REJECTED", "图片类型只支持 PNG/JPEG/WebP", Map.of("mimeType", mime));
        }
        if (!matchesSignature(mime, decoded)) {
            throw reject(422, "ATTACHMENT_TYPE_REJECTED", "声明的类型与文件签名不符",
                    Map.of("declared", mime, "detected", detectMime(decoded)));
        }
        int[] size = dimensions(mime, decoded);
        if (size == null) {
            throw reject(422, "ATTACHMENT_DECODE_FAILED", "无法解析图片尺寸（文件可能已损坏）", Map.of());
        }
        if ((long) size[0] * size[1] > MAX_PIXELS) {
            throw reject(413, "ATTACHMENT_IMAGE_TOO_LARGE", "像素面积超过 25MP",
                    Map.of("width", size[0], "height", size[1], "limitPixels", MAX_PIXELS));
        }
        return decoded;
    }

    private static final List<String> WHITELIST_MIMES = List.of("image/png", "image/jpeg", "image/webp");

    private String textOf(Object rawText, int position) {
        if (rawText == null) {
            throw reject(422, "ATTACHMENT_FIELDS_INVALID", "第 " + (position + 1) + " 项文本附件缺少 text", Map.of());
        }
        if (!(rawText instanceof String value)) {
            throw reject(422, "ATTACHMENT_FIELDS_INVALID", "text 必须是字符串", Map.of());
        }
        return value;
    }

    /** 客户端不得把附件写成用户来源，也不得同时给两条通道或改用 URL/路径通道。 */
    private static void rejectForcedChannels(Map<String, Object> item, int position) {
        for (String forbidden : List.of("url", "path", "file", "filePath", "source")) {
            if (item.get(forbidden) != null) {
                throw reject(422, "ATTACHMENT_URL_FORBIDDEN",
                        "第 " + (position + 1) + " 项带了 " + forbidden + "：服务器不取 URL/文件路径", Map.of());
            }
        }
        if (item.get("origin") != null || item.get("userAuthored") != null) {
            throw reject(422, "ATTACHMENT_ORIGIN_FORBIDDEN", "附件来源由服务端判定，客户端不能自称用户正文", Map.of());
        }
        boolean hasText = item.get("text") != null;
        boolean hasBase64 = item.get("dataBase64") != null;
        if (hasText && hasBase64) {
            throw reject(422, "ATTACHMENT_FIELDS_INVALID", "text 与 dataBase64 只能给一个", Map.of());
        }
    }

    private static String normalizedKind(Object raw) {
        String kind = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (!"image".equals(kind) && !"text".equals(kind)) {
            throw reject(422, "ATTACHMENT_KIND_INVALID", "kind 只能是 text 或 image", Map.of());
        }
        return kind;
    }

    private static String boundedString(Object raw, int maxCodePoints, String code) {
        if (raw == null) return "";
        if (!(raw instanceof String value)) {
            throw reject(422, code, "字段必须是字符串", Map.of());
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw reject(422, code, "字段长度超过 " + maxCodePoints, Map.of("limit", maxCodePoints));
        }
        return value.strip();
    }

    private static boolean matchesSignature(String mime, byte[] bytes) {
        return switch (mime) {
            case "image/png" -> matchesAt(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> matchesAt(bytes, 0, 0xFF, 0xD8, 0xFF);
            case "image/webp" -> matchesAt(bytes, 0, 'R', 'I', 'F', 'F') && matchesAt(bytes, 8, 'W', 'E', 'B', 'P');
            default -> false;
        };
    }

    private static String detectMime(byte[] bytes) {
        if (matchesAt(bytes, 0, 0x89, 0x50, 0x4E, 0x47)) return "image/png";
        if (matchesAt(bytes, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (matchesAt(bytes, 0, 'R', 'I', 'F', 'F') && matchesAt(bytes, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        return "unknown";
    }

    /**
     * 只读头部取宽高：真解码整幅图在这里既没必要（WebP 在 Java ImageIO 里根本没有解码器），
     * 也会让一次上传吃满内存；解析不出头部即按损坏拒绝。
     */
    private static int[] dimensions(String mime, byte[] bytes) {
        return switch (mime) {
            case "image/png" -> pngSize(bytes);
            case "image/jpeg" -> jpegSize(bytes);
            default -> webpSize(bytes);
        };
    }

    private static int[] pngSize(byte[] bytes) {
        if (bytes.length < 24 || !matchesAt(bytes, 12, 'I', 'H', 'D', 'R')) return null;
        int width = be32(bytes, 16);
        int height = be32(bytes, 20);
        return width <= 0 || height <= 0 ? null : new int[]{width, height};
    }

    private static int[] jpegSize(byte[] bytes) {
        int index = 2;
        while (index + 9 < bytes.length) {
            if ((bytes[index] & 0xFF) != 0xFF) return null;
            int marker = bytes[index + 1] & 0xFF;
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int height = be16(bytes, index + 5);
                int width = be16(bytes, index + 7);
                return width <= 0 || height <= 0 ? null : new int[]{width, height};
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                index += 2;
                continue;
            }
            int length = be16(bytes, index + 2);
            if (length < 2) return null;
            index += 2 + length;
        }
        return null;
    }

    private static int[] webpSize(byte[] bytes) {
        if (bytes.length < 20) return null;
        String fourCC = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        switch (fourCC) {
            case "VP8L" -> {
                if (bytes.length < 25 || (bytes[20] & 0xFF) != 0x2F) return null;
                long bits = (bytes[21] & 0xFFL) | ((bytes[22] & 0xFFL) << 8)
                        | ((bytes[23] & 0xFFL) << 16) | ((bytes[24] & 0xFFL) << 24);
                return new int[]{(int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1};
            }
            case "VP8X" -> {
                if (bytes.length < 30) return null;
                return new int[]{le24(bytes, 24) + 1, le24(bytes, 27) + 1};
            }
            case "VP8 " -> {
                for (int index = 20; index + 9 < bytes.length; index++) {
                    if (matchesAt(bytes, index, 0x9D, 0x2B, 0x80, 0xE0)) {
                        int width = le16(bytes, index + 4) & 0x3FFF;
                        int height = le16(bytes, index + 6) & 0x3FFF;
                        return width <= 0 || height <= 0 ? null : new int[]{width, height};
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean matchesAt(byte[] bytes, int offset, int... expected) {
        if (offset + expected.length > bytes.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[offset + index] & 0xFF) != (expected[index] & 0xFF)) return false;
        }
        return true;
    }

    private static int be16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int be32(byte[] bytes, int offset) {
        return (be16(bytes, offset) << 16) | be16(bytes, offset + 2);
    }

    private static int le16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8) | ((bytes[offset + 2] & 0xFF) << 16);
    }

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static ChatTurnRejectException reject(int status, String code, String message, Map<String, Object> details) {
        return new ChatTurnRejectException(status, code, message, details);
    }
}
