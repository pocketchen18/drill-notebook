package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.ChatTurnRepository.AttachmentRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 历史轮次附件的读取（contracts §4.3）：附件密钥只在该轮的 {@code request_meta} 里，
 * 所以重放一张旧图必须走"解 request_meta → 取包装的轮密钥 → 按块解封"这一条路。
 *
 * <p>读不出来就是<b>不提供</b>，不是提供占位：需要主密码、材料不符、行已清理都只返回空表，
 * 由调用方如实标进省略原因（§4 不变量：未解锁不得解密，也不得回退明文）。</p>
 */
@Component
public class ChatAttachmentReader {
    private final ChatTurnRepository turns;
    private final ChatMessageCipher cipher;
    private final ChatAttachmentCipher attachments;
    private final ObjectMapper mapper;

    public ChatAttachmentReader(ChatTurnRepository turns, ChatMessageCipher cipher, ChatAttachmentCipher attachments,
                                ObjectMapper mapper) {
        this.turns = turns;
        this.cipher = cipher;
        this.attachments = attachments;
        this.mapper = mapper;
    }

    /** 取回某轮全部附件的明文形态，按落库顺序；任何一步读不到都只给空表。 */
    public List<ChatAttachmentService.Sourced> readTurn(String requestId, long turnId, String masterPassword)
            throws SQLException {
        Optional<ChatTurnRepository.MetaColumns> meta = turns.findRequestMeta(requestId);
        if (meta.isEmpty() || meta.get().cipher() == null || meta.get().cipher().isBlank()) return List.of();
        ChatMessageCipher.Read read = cipher.read(meta.get().cipher(), meta.get().crypto(), masterPassword);
        if (read.status() != ChatMessageCipher.ReadStatus.DECRYPTED) return List.of();
        JsonNode wrapped;
        try {
            wrapped = mapper.readTree(read.value()).path("attachmentKey");
        } catch (Exception error) {
            return List.of();
        }
        if (wrapped.isMissingNode() || wrapped.isNull()) return List.of();
        List<AttachmentRow> rows = turns.findAttachments(turnId);
        if (rows.isEmpty()) return List.of();
        List<ChatAttachmentService.Sourced> result = new ArrayList<>();
        ChatAttachmentCipher.TurnKey key = null;
        try {
            key = attachments.unwrapKey(wrapped.path("cipher").asText(), wrapped.path("meta").asText(),
                    masterPassword);
            for (AttachmentRow row : rows) {
                byte[] plain = attachments.unseal(key, requestId, row.id(), row.blockId(), row.payloadCipher(),
                        row.cryptoMeta());
                Map<String, Object> envelope;
                try {
                    envelope = mapper.readValue(new String(plain, StandardCharsets.UTF_8),
                            new TypeReference<>() {
                            });
                } catch (Exception corrupt) {
                    // 单块载荷损坏只少这一份附件，不把整轮的历史图片一起判死
                    continue;
                }
                result.add(new ChatAttachmentService.Sourced(row.id(), row.blockId(),
                        "IMAGE".equals(row.kind()) ? "image" : "text",
                        String.valueOf(envelope.getOrDefault("name", "")),
                        String.valueOf(envelope.getOrDefault("mimeType", "")),
                        String.valueOf(envelope.getOrDefault("text", "")),
                        String.valueOf(envelope.getOrDefault("data", ""))));
            }
            return result;
        } catch (ChatCryptoException error) {
            // 密钥包装解不开（换了密码/材料）就当作"这张图本轮没提供"，绝不猜、也不落占位
            return List.of();
        } finally {
            if (key != null) key.zero();
        }
    }
}
