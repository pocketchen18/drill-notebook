package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemoryRepository.AddOutcome;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.repository.MemoryRepository.AddWithEvidenceResult;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceDraft;
import com.drillnotebook.app.repository.MemoryRepository.NewMemory;
import com.drillnotebook.app.service.memory.MemoryCryptoService.EncryptedValue;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 对话内 {@code create_memory} 的写路径：复用归一化、敏感规则、加密载荷与证据写入，
 * 但来源不是人工录入，也不产生待确认提案。
 *
 * <p>来源列沿用 {@code AUTO}：v9 的 CHECK 只允许 {@code AUTO/MANUAL}，本轮不为此改表；
 * 载荷里的 {@code createdBy=AI_TOOL} 保留真实来源。这类事实是用户当轮明确要求保存的，
 * 因此按受保护事实落库：删除来源会话时只断证据、不把长期记忆一起清掉。</p>
 */
@Service
public class MemoryAiWriter {
    public static final String PREDICATE = "other";
    public static final String CREATED_BY = "AI_TOOL";
    public static final String SOURCE_BLOCK_ID = "user-message";
    private static final int MAX_KEYWORDS = 8;

    public enum Status { CREATED, DUPLICATE, SUPPRESSED, REJECTED_CREDENTIAL, REJECTED_SENSITIVE, FAMILY_CONFLICT }

    public record Input(String scopeType, long scopeId, String kind, String value, String validUntil,
                        long sessionId, long turnId, long userMessageId, String quote, String asOf,
                        boolean reallowSuppressed) {
    }

    public record Outcome(Status status, String memoryId, Integer version, boolean releasedSuppression) {
        static Outcome created(String memoryId, int version, boolean released) {
            return new Outcome(Status.CREATED, memoryId, version, released);
        }

        static Outcome duplicate(String memoryId, int version, boolean released) {
            return new Outcome(Status.DUPLICATE, memoryId, version, released);
        }

        static Outcome rejected(Status status) {
            return rejected(status, false);
        }

        static Outcome rejected(Status status, boolean released) {
            return new Outcome(status, null, null, released);
        }
    }

    private final MemoryRepository memory;
    private final MemoryCryptoService crypto;
    private final MemorySuppressionRepository suppressions;
    private final ObjectMapper mapper;

    public MemoryAiWriter(MemoryRepository memory, MemoryCryptoService crypto,
                          MemorySuppressionRepository suppressions, ObjectMapper mapper) {
        this.memory = memory;
        this.crypto = crypto;
        this.suppressions = suppressions;
        this.mapper = mapper;
    }

    public Outcome create(MemoryKey key, Input input) throws SQLException, MemoryCryptoException {
        MemorySensitivity.Level sensitivity = MemorySensitivity.classify(input.value());
        if (sensitivity == MemorySensitivity.Level.CREDENTIAL) {
            return Outcome.rejected(Status.REJECTED_CREDENTIAL);
        }
        // 对话工具没有"用户逐条确认敏感内容"的通道，非凭据的敏感事实同样不落库。
        if (sensitivity == MemorySensitivity.Level.SENSITIVE) {
            return Outcome.rejected(Status.REJECTED_SENSITIVE);
        }
        String normalized = MemoryValidator.normalizeValueText(input.value());
        if (normalized == null) {
            throw new SQLException("记忆正文归一后为空");
        }
        String displayValue = input.value().trim().replaceAll("\\s+", " ");
        String family = MemoryValidator.familyKey(PREDICATE, normalized);
        String familyHmac = crypto.hmac(key, "family:" + family);
        boolean suppressed = suppressions.isSuppressed(key.vaultId(), input.scopeType(), input.scopeId(),
                MemorySuppressionRepository.KIND_FAMILY, familyHmac);
        if (suppressed && !input.reallowSuppressed()) {
            // 用户没在当轮明确要求记住：不写一条被同一家族抑制、读路径永远看不到的“幽灵记忆”
            return Outcome.rejected(Status.SUPPRESSED);
        }
        if (suppressed) {
            // 用户明确说“记住”才解除这一族的抑制；PREDICATE/EVIDENCE 仍保留，自动提取不会因此被放开
            suppressions.release(key.vaultId(), input.scopeType(), input.scopeId(),
                    MemorySuppressionRepository.KIND_FAMILY, familyHmac);
        }
        String rowId = UUID.randomUUID().toString();
        EncryptedValue payload = crypto.encrypt(key, "ai_memory_item", rowId, 1,
                payloadOf(input.kind(), displayValue, normalized, family, sensitivity));
        NewMemory draft = new NewMemory(rowId, key.vaultId(), input.scopeType(), input.scopeId(), input.kind(),
                crypto.hmac(key, "predicate:" + PREDICATE), familyHmac,
                crypto.hmac(key, "value:" + normalized), MemoryRepository.STATUS_ACTIVE, payload.cipher(),
                payload.cryptoMeta(), "AUTO", true, false, null, input.validUntil(), input.asOf(), input.asOf(),
                input.asOf());
        EvidenceDraft evidence = evidenceOf(key, input);
        AddWithEvidenceResult result = memory.addWithEvidence(draft, evidence);
        return switch (result.outcome()) {
            case ADDED -> Outcome.created(result.memoryId(), result.version(), suppressed);
            case DUPLICATE -> Outcome.duplicate(result.duplicateMemoryId(), result.duplicateVersion(), suppressed);
            case FAMILY_CONFLICT -> Outcome.rejected(Status.FAMILY_CONFLICT, suppressed);
            case PENDING_QUOTA_FULL -> throw new SQLException("AI 新增不应进入待确认队列");
        };
    }

    private String payloadOf(String kind, String value, String normalized, String family,
                             MemorySensitivity.Level sensitivity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("subject", "self");
        payload.put("kind", kind);
        payload.put("predicate", PREDICATE);
        payload.put("family", family);
        payload.put("value", value);
        payload.put("normalizedValue", normalized);
        payload.put("displayText", "其他偏好：" + value);
        payload.put("keywords", MemoryLexicon.tokens(normalized).stream().limit(MAX_KEYWORDS).toList());
        payload.put("sensitivity", sensitivity.name());
        payload.put("createdBy", CREATED_BY);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("AI 记忆载荷无法序列化", error);
        }
    }

    private EvidenceDraft evidenceOf(MemoryKey key, Input input) throws MemoryCryptoException {
        String evidenceId = UUID.randomUUID().toString();
        String quote = quoteOf(input.quote(), input.value());
        EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_evidence", evidenceId, 1, quote);
        String sourceHmac = crypto.hmac(key, "source:" + input.sessionId() + ":" + input.turnId() + ":"
                + SOURCE_BLOCK_ID + ":" + normalizeQuote(quote));
        return new EvidenceDraft(evidenceId, key.vaultId(), input.sessionId(), input.turnId(),
                input.userMessageId(), SOURCE_BLOCK_ID, sourceHmac, encrypted.cipher(), encrypted.cryptoMeta(),
                input.asOf());
    }

    private static String quoteOf(String quote, String fallback) {
        String text = quote == null || quote.isBlank() ? fallback : quote;
        String trimmed = text.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints <= MemoryCryptoService.MAX_QUOTE_CODE_POINTS) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MemoryCryptoService.MAX_QUOTE_CODE_POINTS));
    }

    private static String normalizeQuote(String quote) {
        return quote == null ? "" : quote.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
