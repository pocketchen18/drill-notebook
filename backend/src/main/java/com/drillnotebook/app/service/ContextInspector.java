package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.TurnHistory;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.ChatTurnRepository.MetaColumns;
import com.drillnotebook.app.repository.ChatTurnRepository.TurnRow;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 上下文检查器的只读后端（contracts §4.5、§3）：点开检查器只读<b>已保存</b>的审计，
 * 不建计划、不触发 RAG/维护/重放/新聊天，也不因为"想解释省略原因"就全库解密。
 *
 * <p>正文预览另算：最多 5 个、每个 ≤160 码点，并且必须先确认这个 sourceKey 属于本轮 selected，
 * 再确认它<b>现在</b>仍合法（版本没变、scope 还对、仍能解密）。任何一条不成立都只给占位，
 * 绝不从旧缓存把已删或已改版的内容恢复出来。</p>
 */
@Service
public class ContextInspector {
    /** §3：preview 一次最多 5 个来源键。 */
    public static final int MAX_PREVIEW_KEYS = 5;
    /** §4.5：每个片段最多 160 码点。 */
    public static final int MAX_PREVIEW_CODEPOINTS = 160;

    private static final String PASSWORD_SOURCE = "需要主密码或记忆库解锁";

    private final ChatTurnRepository turns;
    private final ChatHistoryRepository history;
    private final ChatSessionMetadataRepository sessions;
    private final ChatMessageCipher cipher;
    private final ChatAttachmentReader attachments;
    private final MemoryRepository memory;
    private final MemoryCryptoService crypto;
    private final MemoryVaultService vault;
    private final SessionContextRepository checkpoints;
    private final RetrievalIndexRepository index;
    private final NoteNormalizer normalizer;
    private final ObjectMapper mapper;

    public ContextInspector(ChatTurnRepository turns, ChatHistoryRepository history,
                            ChatSessionMetadataRepository sessions, ChatMessageCipher cipher,
                            ChatAttachmentReader attachments, MemoryRepository memory, MemoryCryptoService crypto,
                            MemoryVaultService vault, SessionContextRepository checkpoints,
                            RetrievalIndexRepository index, ObjectMapper mapper) {
        this.turns = turns;
        this.history = history;
        this.sessions = sessions;
        this.cipher = cipher;
        this.attachments = attachments;
        this.memory = memory;
        this.crypto = crypto;
        this.vault = vault;
        this.checkpoints = checkpoints;
        this.index = index;
        this.normalizer = new NoteNormalizer(mapper);
        this.mapper = mapper;
    }

    /** {@code GET /turns/{requestId}/context}：已保存的有界审计，没有快照就明确说没有。 */
    public Map<String, Object> audit(String requestId) throws SQLException {
        TurnRow turn = requireTurn(requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", turn.requestId());
        body.put("sessionId", turn.sessionId());
        body.put("status", turn.status());
        Optional<MetaColumns> meta = turns.findResultMeta(requestId);
        if (meta.isEmpty() || blank(meta.get().cipher())) {
            body.put("available", false);
            body.put("reason", "NO_AUDIT_SAVED");
            return body;
        }
        String password = sessionPassword(turn.sessionId());
        ChatMessageCipher.Read read = cipher.read(meta.get().cipher(), meta.get().crypto(), password);
        switch (read.status()) {
            case NEEDS_PASSWORD -> throw new ChatTurnRejectException(423, "CHAT_PASSWORD_REQUIRED",
                    PASSWORD_SOURCE, Map.of());
            case UNREADABLE -> {
                body.put("available", false);
                body.put("reason", "AUDIT_UNREADABLE");
                return body;
            }
            default -> {
            }
        }
        JsonNode audit;
        try {
            audit = mapper.readTree(read.value()).path("audit");
        } catch (Exception unreadable) {
            // result_meta 解得开但不是合法 JSON：只能说"读不出审计"，不能拿半截内容当账本
            body.put("available", false);
            body.put("reason", "AUDIT_UNREADABLE");
            return body;
        }
        if (audit.isMissingNode() || audit.isNull()) {
            body.put("available", false);
            body.put("reason", "LEGACY_TURN_WITHOUT_CONTEXT_PLAN");
            return body;
        }
        boolean hasPlan = audit.has("planId");
        body.put("available", hasPlan);
        if (!hasPlan) {
            // v1 只落检索账本，没有统一计划：检查器要说清楚"这轮没有上下文计划"，而不是留个空壳
            body.put("reason", "LEGACY_TURN_WITHOUT_CONTEXT_PLAN");
            return body;
        }
        body.put("planId", audit.path("planId").asText(null));
        body.put("dispatchState", audit.path("dispatchState").asText(null));
        body.put("audit", mapper.convertValue(audit, new TypeReference<LinkedHashMap<String, Object>>() {
        }));
        return body;
    }

    /** {@code POST /turns/{requestId}/context/preview}：只给本轮真发过、且现在仍合法的来源片段。 */
    public List<Map<String, Object>> preview(String requestId, List<String> sourceKeys) throws SQLException {
        TurnRow turn = requireTurn(requestId);
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            throw new ChatTurnRejectException(400, "CONTEXT_PREVIEW_KEYS_EMPTY", "要预览就得给出来源键", Map.of());
        }
        if (sourceKeys.size() > MAX_PREVIEW_KEYS) {
            throw new ChatTurnRejectException(400, "CONTEXT_PREVIEW_TOO_MANY",
                    "一次最多预览 " + MAX_PREVIEW_KEYS + " 个来源", Map.of("limit", MAX_PREVIEW_KEYS));
        }
        Map<String, Object> audit = auditBody(turn);
        Map<String, ContextAudit.Selected> selected = selectedIndex(audit);
        String password = sessionPassword(turn.sessionId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : sourceKeys) {
            ContextAudit.Selected item = selected.get(key);
            if (item == null) {
                // 不属于本轮 selected 的键一律拒绝：不能借检查器读任意内容
                throw new ChatTurnRejectException(400, "CONTEXT_SOURCE_UNKNOWN", "来源键不属于本轮上下文",
                        Map.of("sourceKey", key));
            }
            rows.add(previewOne(turn, item, password));
        }
        return rows;
    }

    private Map<String, Object> previewOne(TurnRow turn, ContextAudit.Selected item, String password)
            throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceKey", item.sourceKey());
        row.put("kind", item.kind());
        row.put("id", item.id());
        row.put("version", item.version());
        switch (item.kind()) {
            case "MEMORY" -> row.putAll(memoryPreview(turn, item));
            case "SUMMARY" -> row.putAll(summaryPreview(turn.sessionId(), item));
            case "TURN", "LEGACY_MESSAGE" -> row.putAll(turnPreview(turn, item, password));
            case "TEXT_ATTACHMENT", "IMAGE" -> row.putAll(attachmentPreview(turn, item, password));
            case "NOTEBOOK_RAG" -> row.putAll(chunkPreview(item));
            case "PAGE" -> row.putAll(pagePreview(item));
            default -> row.put("available", false);
        }
        return row;
    }

    private Map<String, Object> memoryPreview(TurnRow audited, ContextAudit.Selected item) throws SQLException {
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        if (vault.currentKey().isEmpty() || envelope.isEmpty()) {
            return placeholder("LOCKED");
        }
        Optional<MemoryRepository.FactRow> row = memory.findVersion(envelope.get().vaultId(), item.id(),
                Integer.parseInt(item.version()));
        if (row.isEmpty() || !MemoryRepository.STATUS_ACTIVE.equals(row.get().status())) {
            return placeholder("SOURCE_CHANGED");
        }
        // 用会话的当前作用域比较，而不是旧轮次快照：切过 scope 后不能借旧计划读别的笔记本的事实（§5.2）
        ChatSessionMetadataRepository.State state = sessions.read(audited.sessionId()).orElse(null);
        if (state == null) return placeholder("SOURCE_DELETED");
        if ("NOTEBOOK".equals(row.get().scopeType())
                && (!"NOTEBOOK".equals(state.memoryScopeType()) || state.memoryNotebookId() == null
                || state.memoryNotebookId() != row.get().scopeId())) {
            return placeholder("OUTSIDE_SCOPE");
        }
        Map<String, Object> payload = decryptMemory(row.get());
        if (payload == null) return placeholder("LOCKED");
        return text(String.valueOf(payload.getOrDefault("value", "")));
    }

    private Map<String, Object> decryptMemory(MemoryRepository.FactRow row) throws SQLException {
        try {
            String json = crypto.decrypt(vault.requireKey(), "ai_memory_item", row.rowId(), row.version(),
                    row.payloadCipher(), row.cryptoMeta());
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            return null;
        }
    }

    private Map<String, Object> summaryPreview(long sessionId, ContextAudit.Selected item) throws SQLException {
        // 版本在 selected.version；旧形态的账本把它记在 id 里，这里两种都能读
        String auditedRevision = item.version() == null ? item.id() : item.version();
        Optional<SessionContextRepository.Checkpoint> checkpoint = checkpoints.read(sessionId);
        if (checkpoint.isEmpty() || !String.valueOf(checkpoint.get().revision()).equals(auditedRevision)) {
            // 检查点已被更新的摘要替换：历史版本的摘要不提供预览（§4.5）
            return placeholder("CHECKPOINT_REPLACED");
        }
        if (vault.currentKey().isEmpty()) return placeholder("LOCKED");
        try {
            String payload = crypto.decrypt(vault.requireKey(), "ai_session_context", String.valueOf(sessionId),
                    checkpoint.get().revision(), checkpoint.get().payloadCipher(),
                    checkpoint.get().cryptoMeta());
            Map<String, Object> parsed = mapper.readValue(payload,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return text(String.valueOf(parsed.getOrDefault("modelSummary", "")));
        } catch (Exception error) {
            return placeholder("LOCKED");
        }
    }

    private Map<String, Object> turnPreview(TurnRow audited, ContextAudit.Selected item, String password)
            throws SQLException {
        if ("LEGACY_MESSAGE".equals(item.kind())) {
            Optional<ChatTurnRepository.MessageColumns> message = turns.findMessage(
                    Long.parseLong(item.id()));
            if (message.isEmpty()) return placeholder("SOURCE_DELETED");
            return text(cipher.readLegacy(message.get().content(), message.get().contentCipher(),
                    message.get().contentMeta(), password).orSentinel());
        }
        long turnId = Long.parseLong(item.id());
        List<TurnHistory> rows = history.turnBatch(audited.sessionId(), turnId + 1, 1);
        if (rows.isEmpty() || rows.get(0).turnId() != turnId) return placeholder("SOURCE_DELETED");
        TurnHistory row = rows.get(0);
        if (row.contextGeneration() != audited.contextGeneration()) return placeholder("WRONG_GENERATION");
        if (row.contentDeletedAt() != null) return placeholder("SOURCE_DELETED");
        String user = cipher.readLegacy(row.userContent(), row.userContentCipher(), row.userContentMeta(), password)
                .orSentinel();
        String assistant = cipher.readLegacy(row.assistantContent(), row.assistantContentCipher(),
                row.assistantContentMeta(), password).orSentinel();
        return text((user == null ? "" : user) + "\n" + (assistant == null ? "" : assistant));
    }

    private Map<String, Object> attachmentPreview(TurnRow audited, ContextAudit.Selected item, String password)
            throws SQLException {
        long turnId = item.sourceTurnId() == null ? audited.id() : item.sourceTurnId();
        String requestId = audited.requestId();
        if (turnId != audited.id()) {
            List<TurnHistory> rows = history.turnBatch(audited.sessionId(), turnId + 1, 1);
            if (rows.isEmpty() || rows.get(0).turnId() != turnId) return placeholder("SOURCE_DELETED");
            requestId = rows.get(0).requestId();
        }
        for (ChatAttachmentService.Sourced attachment : attachments.readTurn(requestId, turnId, password)) {
            if (!attachment.attachmentId().equals(item.id())) continue;
            if (attachment.image()) {
                // 图像不提供"文本预览"：像素内容不是文字，也不做 OCR
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("available", true);
                row.put("kind", item.kind());
                row.put("text", null);
                row.put("note", "IMAGE_WITHOUT_TEXT_PREVIEW");
                return row;
            }
            return text(attachment.text());
        }
        return placeholder("SOURCE_CHANGED");
    }

    private Map<String, Object> chunkPreview(ContextAudit.Selected item) {
        long chunkId = chunkIdOf(item.version());
        String expectedHash = contentHashOf(item.version());
        List<RetrievalIndexRepository.LexicalRow> rows = index.findChunksByIds(List.of(chunkId));
        if (rows.isEmpty()) return placeholder("SOURCE_DELETED");
        if (expectedHash != null && !expectedHash.equals(rows.get(0).contentHash())) {
            // 片段还在但正文换过：不能把当前正文冒充成当时提供的片段（§4.5）
            return placeholder("SOURCE_CHANGED");
        }
        return text(rows.get(0).text());
    }

    /** RAG 版本形如 {@code chunkId@contentHash}；没有哈希时的旧账本只按存在性校验。 */
    private static long chunkIdOf(String version) {
        int at = version.indexOf('@');
        return Long.parseLong(at < 0 ? version : version.substring(0, at));
    }

    private static String contentHashOf(String version) {
        int at = version.indexOf('@');
        return at < 0 || at + 1 >= version.length() ? null : version.substring(at + 1);
    }

    private Map<String, Object> pagePreview(ContextAudit.Selected item) throws SQLException {
        long pageId = Long.parseLong(item.id());
        Optional<String> content = history.pageContent(pageId);
        if (content.isEmpty()) return placeholder("SOURCE_DELETED");
        Optional<String> currentHash = history.pageContentHash(pageId);
        if (item.version() == null || !item.version().equals(currentHash.orElse(null))) {
            // 页面改版（或当时没有可核验的版本）：当前正文不能冒充"当时提供的内容"（§4.5）
            return placeholder("SOURCE_CHANGED");
        }
        StringBuilder joined = new StringBuilder();
        for (var unit : normalizer.normalize(content.get())) {
            if (unit.text().isBlank()) continue;
            if (!joined.isEmpty()) joined.append('\n');
            joined.append(unit.text());
        }
        return text(joined.toString());
    }

    private Map<String, Object> auditBody(TurnRow turn) throws SQLException {
        Map<String, Object> body = audit(turn.requestId());
        Object audit = body.get("audit");
        if (!(audit instanceof Map<?, ?> map)) {
            throw new ChatTurnRejectException(409, "CONTEXT_AUDIT_MISSING", "本轮没有可预览的上下文记录", Map.of());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) map;
        return cast;
    }

    private Map<String, ContextAudit.Selected> selectedIndex(Map<String, Object> audit) {
        Map<String, ContextAudit.Selected> index = new LinkedHashMap<>();
        if (!(audit.get("selected") instanceof List<?> rows)) return index;
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> row)) continue;
            String key = String.valueOf(row.get("sourceKey"));
            index.put(key, new ContextAudit.Selected(key, String.valueOf(row.get("kind")),
                    stringOrNull(row.get("id")), stringOrNull(row.get("version")),
                    row.get("sourceTurnId") instanceof Number number ? number.longValue() : null));
        }
        return index;
    }

    private TurnRow requireTurn(String requestId) throws SQLException {
        Optional<TurnRow> found = turns.findByRequestId(requestId);
        if (found.isEmpty()) {
            throw new ChatTurnRejectException(404, "TURN_NOT_FOUND", "没有这个轮次", Map.of());
        }
        TurnRow turn = found.get();
        if (turn.contentDeletedAt() != null) {
            throw new ChatTurnRejectException(410, "RESULT_EXPIRED", "该轮次原文已清理，上下文记录不再提供", Map.of());
        }
        return turn;
    }

    private String sessionPassword(long sessionId) {
        return vault.sessionPassword(sessionId).orElse("");
    }

    private static Map<String, Object> text(String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("available", true);
        row.put("text", truncate(value));
        row.put("truncated", value != null && value.codePointCount(0, value.length()) > MAX_PREVIEW_CODEPOINTS);
        return row;
    }

    private static Map<String, Object> placeholder(String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("available", false);
        row.put("reason", reason);
        return row;
    }

    /** 按码点截断，绝不切开代理对（emoji 半个字符会让下游直接坏掉）。 */
    private static String truncate(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.codePointCount(0, value.length()) <= MAX_PREVIEW_CODEPOINTS) return value;
        int end = value.offsetByCodePoints(0, MAX_PREVIEW_CODEPOINTS);
        return value.substring(0, end);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
