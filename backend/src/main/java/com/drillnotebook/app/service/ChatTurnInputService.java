package com.drillnotebook.app.service;

import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 重新生成前只读取原提问和附件；不调用模型、不改写历史，也不返回配置或密钥。 */
@Service
public class ChatTurnInputService {
    private final ChatTurnRepository turns;
    private final ChatMessageCipher cipher;
    private final ChatAttachmentReader attachments;
    private final MemoryVaultService vault;
    private final ObjectMapper mapper;

    public ChatTurnInputService(ChatTurnRepository turns, ChatMessageCipher cipher, ChatAttachmentReader attachments,
                                MemoryVaultService vault, ObjectMapper mapper) {
        this.turns = turns;
        this.cipher = cipher;
        this.attachments = attachments;
        this.vault = vault;
        this.mapper = mapper;
    }

    public Map<String, Object> input(String requestId) throws SQLException {
        if (!UuidV7.isCanonical(requestId)) {
            throw reject(400, "RETRY_ID_INVALID", "重新生成的轮次标识无效");
        }
        var turn = turns.findByRequestId(requestId)
                .orElseThrow(() -> reject(404, "TURN_NOT_FOUND", "要重新生成的轮次不存在"));
        if (turn.contentDeletedAt() != null) {
            throw reject(410, "TURN_CONTENT_DELETED", "原提问和附件已清理，无法重新生成");
        }
        if (ChatTurnRepository.STATUS_RUNNING.equals(turn.status())) {
            throw reject(409, "RETRY_TARGET_ACTIVE", "这一轮还在回复，结束后可重新生成");
        }
        String password = vault.sessionPassword(turn.sessionId()).orElse("");
        var message = turn.userMessageId() == null ? null : turns.findMessage(turn.userMessageId()).orElse(null);
        if (message == null) throw unavailable();
        String userText = readable(cipher.readLegacy(message.content(), message.contentCipher(),
                message.contentMeta(), password));
        if (userText.isBlank()) throw unavailable();

        var meta = turns.findRequestMeta(requestId).orElseThrow(ChatTurnInputService::unavailable);
        String requestMeta = readable(cipher.read(meta.cipher(), meta.crypto(), password));
        int expected;
        try {
            expected = mapper.readTree(requestMeta).path("attachmentCount").asInt(-1);
        } catch (JsonProcessingException error) {
            throw unavailable();
        }
        if (expected < 0 || expected > ChatAttachmentService.MAX_ITEMS) throw unavailable();
        var original = attachments.readTurn(requestId, turn.id(), password);
        // 部分附件解不开时，不能把一个不同的问题悄悄当成「重新生成」。
        if (original.size() != expected) throw unavailable();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < original.size(); index++) {
            var attachment = original.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("clientId", "c" + index);
            item.put("kind", attachment.kind());
            item.put("name", attachment.name());
            item.put("mimeType", attachment.mimeType());
            item.put(attachment.image() ? "dataBase64" : "text",
                    attachment.image() ? attachment.base64() : attachment.text());
            items.add(item);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("role", "user");
        input.put("userText", userText);
        if (!items.isEmpty()) input.put("attachments", items);
        return Map.of("requestId", requestId, "sessionId", turn.sessionId(), "input", input);
    }

    private static String readable(ChatMessageCipher.Read read) {
        if (read.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD) {
            throw reject(423, "CHAT_PASSWORD_REQUIRED", "请先解锁会话，再重新生成");
        }
        if (read.status() != ChatMessageCipher.ReadStatus.DECRYPTED) throw unavailable();
        return read.value();
    }

    private static ChatTurnRejectException unavailable() {
        return reject(409, "RETRY_INPUT_UNAVAILABLE", "原提问或附件无法完整读取，无法重新生成");
    }

    private static ChatTurnRejectException reject(int status, String code, String message) {
        return new ChatTurnRejectException(status, code, message, Map.of());
    }
}
