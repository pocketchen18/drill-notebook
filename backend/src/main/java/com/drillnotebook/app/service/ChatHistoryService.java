package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.HistoryRow;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 只读历史分页（contracts §3 的 {@code GET /sessions/{id}/history}）：
 * 展示真实保存过的消息，失败与 legacy 都如实标注，但<b>展示资格不等于注入或学习资格</b>（§4.5）。
 *
 * <p>分页按稳定 message ID seek，末尾新增消息不会让更早的页丢失；主密码只从进程内的会话解锁状态取，
 * 不进 URL、不进 querystring（§3 尾段）。</p>
 */
@Service
public class ChatHistoryService {
    /** §7.4：历史 UI 每页最多 50 条。 */
    public static final int MAX_PAGE_SIZE = 50;

    private final ChatHistoryRepository history;
    private final ChatSessionMetadataRepository sessions;
    private final ChatMessageCipher cipher;
    private final MemoryVaultService vault;

    public ChatHistoryService(ChatHistoryRepository history, ChatSessionMetadataRepository sessions,
                              ChatMessageCipher cipher, MemoryVaultService vault) {
        this.history = history;
        this.sessions = sessions;
        this.cipher = cipher;
        this.vault = vault;
    }

    /** 取一页历史；{@code beforeMessageId} 与 {@code afterMessageId} 二选一。 */
    public Map<String, Object> page(long sessionId, Long beforeMessageId, Long afterMessageId, int limit)
            throws SQLException {
        if (beforeMessageId != null && afterMessageId != null) {
            throw new ChatTurnRejectException(400, "HISTORY_CURSOR_CONFLICT",
                    "beforeMessageId 与 afterMessageId 只能给一个", Map.of());
        }
        int bounded = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        // 多取一条只用来判断还有没有下一页，不返回给调用方
        List<HistoryRow> rows = history.page(sessionId, beforeMessageId, afterMessageId, bounded + 1);
        boolean hasMore = rows.size() > bounded;
        boolean backwards = afterMessageId == null;
        // 往前翻时多取的那条是最旧的哨兵，得从尾部留够 limit 条，否则每页都会漏掉最新的一条
        int from = backwards && hasMore ? rows.size() - bounded : 0;
        List<Map<String, Object>> messages = new ArrayList<>();
        String password = sessionPassword(sessionId);
        for (int index = from; index < rows.size() && messages.size() < bounded; index++) {
            messages.add(view(rows.get(index), password));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("messages", messages);
        result.put("historyRevision", revision(sessionId));
        result.put("hasMore", hasMore);
        result.put("limit", bounded);
        return result;
    }

    private Map<String, Object> view(HistoryRow row, String password) {
        ChatMessageCipher.Read content = cipher.readLegacy(row.content(), row.contentCipher(), row.contentMeta(),
                password);
        ChatMessageCipher.Read reasoning = cipher.readLegacy(null, row.reasoningCipher(), row.reasoningMeta(),
                password);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.messageId());
        item.put("role", row.role());
        item.put("content", content.orSentinel());
        if (reasoning.status() != ChatMessageCipher.ReadStatus.EMPTY) {
            item.put("reasoning", reasoning.orSentinel());
        }
        item.put("createdAt", row.createdAt());
        item.put("turnId", row.turnId());
        item.put("turnRole", row.turnRole());
        // 失败/中断的轮次也要能看见原文，但必须带着它的真实终态
        item.put("turnStatus", row.turnStatus());
        item.put("contentDeleted", row.contentDeleted());
        item.put("legacy", row.legacy());
        item.put("readable", content.status() == ChatMessageCipher.ReadStatus.DECRYPTED
                || content.status() == ChatMessageCipher.ReadStatus.EMPTY);
        return item;
    }

    /** 会话解锁状态里的材料只在本进程内存活；没有解锁就是空串，由密文读取按"需要主密码"处理。 */
    private String sessionPassword(long sessionId) {
        Optional<String> password = vault.sessionPassword(sessionId);
        return password.orElse("");
    }

    private long revision(long sessionId) throws SQLException {
        return sessions.read(sessionId)
                .map(ChatSessionMetadataRepository.State::historyRevision).orElse(0L);
    }
}
