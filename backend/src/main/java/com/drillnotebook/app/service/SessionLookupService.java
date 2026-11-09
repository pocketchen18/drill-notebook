package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.repository.SessionLookupRepository;
import com.drillnotebook.app.repository.SessionLookupRepository.Session;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按需解密历史正文，无记忆索引、授权刷新或新写入。 */
@Service
public class SessionLookupService {
    public static final String LINK_PREFIX = "drill-notebook://session/";
    public static final int PAGE_CODE_POINTS = 6000;
    private final SessionLookupRepository repository;
    private final ChatMessageCipher cipher;
    private final MemoryVaultService vault;

    public SessionLookupService(SessionLookupRepository repository, ChatMessageCipher cipher, MemoryVaultService vault) {
        this.repository = repository;
        this.cipher = cipher;
        this.vault = vault;
    }

    public Map<String, Object> list(ChatContextPlan plan, String query, long beforeId, int limit, boolean archived) {
        try {
            return repository.read(snapshot -> {
                if (!callerCurrent(snapshot, plan)) return error("STALE", "items");
                List<Session> found = snapshot.list(query, beforeId, plan.sessionId(), archived, limit + 1);
                boolean more = found.size() > limit;
                var items = found.stream().limit(limit).map(SessionLookupService::view).toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", items.isEmpty() ? "NO_MATCH" : "OK");
                result.put("items", items);
                result.put("hasMore", more);
                result.put("incomplete", more || beforeId != Long.MAX_VALUE);
                result.put("nextBeforeSessionId", more ? found.get(limit - 1).id() : null);
                return result;
            });
        } catch (SQLException error) { return error("UNAVAILABLE", "items"); }
    }

    public Map<String, Object> read(ChatContextPlan plan, String selector, String rawCursor, int limit) {
        Cursor cursor = rawCursor == null ? null : Cursor.parse(rawCursor);
        if (rawCursor != null && cursor == null) return error("INVALID_ARGUMENT", "messages");
        try {
            Map<String, Object> result = repository.read(snapshot -> {
                if (!callerCurrent(snapshot, plan)) return error("STALE", "messages");
                Session target;
                if (selector.matches("[a-f0-9]{32}") || selector.startsWith(LINK_PREFIX)) {
                    String ref = selector.startsWith(LINK_PREFIX) ? selector.substring(LINK_PREFIX.length()) : selector;
                    if (!ref.matches("[a-f0-9]{32}")) return error("INVALID_ARGUMENT", "messages");
                    target = snapshot.byRef(ref);
                } else if (selector.matches("[0-9]+")) {
                    try { target = snapshot.byId(Long.parseLong(selector)); }
                    catch (NumberFormatException error) { return error("INVALID_ARGUMENT", "messages"); }
                } else {
                    // 只接受标题，绝不把外部 URL/文件路径作为标题兜底，更不进行解引用。
                    if (selector.contains("://") || selector.matches("^[A-Za-z]:[\\\\/].*")
                            || selector.startsWith("/") || selector.startsWith("\\"))
                        return error("INVALID_ARGUMENT", "messages");
                    List<Session> candidates = snapshot.list(selector, Long.MAX_VALUE, plan.sessionId(), true, 11);
                    if (candidates.isEmpty()) return error("NO_MATCH", "messages");
                    if (candidates.size() > 1) {
                        Map<String, Object> resultBody = new LinkedHashMap<>(error("AMBIGUOUS", "messages"));
                        resultBody.put("candidates", candidates.stream().limit(10).map(SessionLookupService::view).toList());
                        resultBody.put("hasMoreCandidates", candidates.size() > 10);
                        return resultBody;
                    }
                    target = candidates.get(0);
                }
                if (target == null) return error("NOT_FOUND", "messages");
                if (cursor != null && !cursor.ref().equals(target.ref())) return error("INVALID_ARGUMENT", "messages");
                if (cursor != null && (cursor.revision() != target.revision() || cursor.generation() != target.generation()))
                    return error("STALE", "messages");
                return page(snapshot, target, cursor, limit);
            });
            // 解密期间可能发生删除/清理/换代，不能把旧快照冒充当前可读结果。
            if (result.get("session") instanceof Map<?, ?> target) {
                Session current = repository.read(snapshot -> snapshot.byId(((Number) target.get("sessionId")).longValue()));
                if (current == null) return error("NOT_FOUND", "messages");
                if (current.revision() != ((Number) target.get("historyRevision")).longValue()
                        || current.generation() != ((Number) target.get("contextGeneration")).longValue())
                    return error("STALE", "messages");
            }
            return result;
        } catch (SQLException error) { return error("UNAVAILABLE", "messages"); }
    }

    private Map<String, Object> page(SessionLookupRepository.Snapshot snapshot, Session target, Cursor cursor, int limit)
            throws SQLException {
        var range = snapshot.range(target.id());
        long through = cursor == null ? range.throughMessageId() : cursor.through();
        long nextId = cursor == null ? 0 : cursor.messageId();
        int offset = cursor == null ? 0 : cursor.offset();
        if (through > range.throughMessageId()) return error("INVALID_ARGUMENT", "messages");
        var rows = snapshot.messages(target.id(), nextId, through, limit + 1);
        if (cursor != null && (rows.isEmpty() || rows.get(0).id() != nextId)) return error("INVALID_ARGUMENT", "messages");
        String password = vault.sessionPassword(target.id()).orElse("");
        List<Map<String, Object>> messages = new ArrayList<>();
        int remaining = PAGE_CODE_POINTS;
        int unreadable = 0;
        boolean locked = false;
        Cursor next = null;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (messages.size() == limit || remaining == 0) {
                next = Cursor.at(target, through, row.id(), 0);
                break;
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", row.id());
            message.put("role", row.role());
            message.put("createdAt", row.createdAt());
            message.put("turnStatus", row.legacy() ? "LEGACY" : row.turnStatus());
            ChatMessageCipher.Read read = cipher.readLegacy(row.content(), row.cipher(), row.crypto(), password);
            message.put("readStatus", read.status().name());
            if (read.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD
                    || read.status() == ChatMessageCipher.ReadStatus.UNREADABLE) {
                unreadable++;
                locked |= read.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD;
                if (offset != 0) return error("UNAVAILABLE", "messages");
            } else {
                String text = read.value() == null ? "" : read.value();
                if("assistant".equals(row.role()))text=ChatSupplement.transcript(text,snapshot.supplements(row.id(),cipher,password));
                int length = text.codePointCount(0, text.length());
                if (offset > length) return error("INVALID_ARGUMENT", "messages");
                int count = Math.min(remaining, length - offset);
                int start = text.offsetByCodePoints(0, offset);
                int end = text.offsetByCodePoints(start, count);
                message.put("content", text.substring(start, end));
                message.put("offset", offset);
                message.put("messageComplete", offset + count == length);
                remaining -= count;
                if (offset + count < length) next = Cursor.at(target, through, row.id(), offset + count);
            }
            messages.add(message);
            if (next != null) break;
            offset = 0;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(view(target));
        metadata.put("historyRevision", target.revision());
        metadata.put("contextGeneration", target.generation());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", !messages.isEmpty() && unreadable == messages.size() ? "UNAVAILABLE" : "OK");
        if (unreadable > 0) result.put("availability", locked ? "LOCKED" : "UNREADABLE");
        result.put("session", metadata);
        result.put("messages", messages);
        result.put("hasMore", next != null);
        result.put("nextCursor", next == null ? null : next.encode());
        result.put("incomplete", next != null || cursor != null || unreadable > 0 || range.availableMessages() < range.storedMessages());
        result.put("storedMessages", range.storedMessages());
        result.put("availableMessages", range.availableMessages());
        result.put("excludedMessages", range.storedMessages() - range.availableMessages());
        result.put("unreadableMessages", unreadable);
        result.put("throughMessageId", through);
        result.put("omittedContent", List.of("reasoning", "attachment_payloads", "system_messages", "tool_payloads",
                "running_turns", "deleted_or_previous_generation"));
        return result;
    }

    private static boolean callerCurrent(SessionLookupRepository.Snapshot snapshot, ChatContextPlan plan) throws SQLException {
        if (plan == null) return false;
        Session caller = snapshot.byId(plan.sessionId());
        return caller != null && caller.generation() == plan.contextGeneration();
    }

    private static Map<String, Object> view(Session session) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", session.id());
        String title = session.title();
        int length = title.codePointCount(0, title.length());
        row.put("title", title.substring(0, title.offsetByCodePoints(0, Math.min(length, 200))));
        row.put("titleTruncated", length > 200);
        row.put("sessionLink", LINK_PREFIX + session.ref());
        row.put("updatedAt", session.updatedAt());
        row.put("archived", session.archived());
        row.put("scope", session.scope());
        row.put("notebookId", session.notebookId());
        return row;
    }

    static Map<String, Object> error(String status, String key) {
        return Map.of("status", status, key, List.of(), "hasMore", false, "incomplete", !"NO_MATCH".equals(status));
    }

    /** 不包含正文或密钥；绑定目标与版本，不能跨目标复用。 */
    private record Cursor(String ref, long revision, long generation, long through, long messageId, int offset) {
        static Cursor at(Session session, long through, long id, int offset) {
            return new Cursor(session.ref(), session.revision(), session.generation(), through, id, offset);
        }
        String encode() { return ref + ":" + revision + ":" + generation + ":" + through + ":" + messageId + ":" + offset; }
        static Cursor parse(String value) {
            if (!value.matches("[a-f0-9]{32}(:[0-9]{1,19}){5}")) return null;
            String[] parts = value.split(":");
            try {
                Cursor cursor = new Cursor(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                        Long.parseLong(parts[3]), Long.parseLong(parts[4]), Integer.parseInt(parts[5]));
                return cursor.generation > 0 && cursor.messageId > 0 && cursor.messageId <= cursor.through ? cursor : null;
            } catch (NumberFormatException error) { return null; }
        }
    }
}
