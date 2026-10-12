package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryJobRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryProposalRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemoryRepository.EvidenceRow;
import com.drillnotebook.app.repository.MemoryRepository.FactRow;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService.EncryptedValue;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 手动管理本地加密记忆（执行方案 P2 退出条件：不用 LLM 就能添加/列出/查看/改/停用/删一条记忆）。
 *
 * <p>每条写路径先过 {@link MemoryPolicyService} 的资格判定，再由本类持钥匙加密，仓储只搬字节。
 * {@code predicate/family/value} 的明文一起放进 payload 密文，表上只留按库派生的 HMAC 查找键（§6），
 * 其中 {@code family} 必须进载荷，否则改谓词时无法保持同族身份（表里只有它的摘要）。</p>
 *
 * <p>版本约束由事务保证，不靠前端禁用按钮：改与删都必须带 {@code expectedVersion}，撞上就 409
 * 并回最新 metadata（只回元信息，不回正文）。</p>
 */
@Service
public class MemoryItemService {
    public static final String ACTION_ITEM_ADD = "MEMORY_ITEM_ADD";
    public static final String ACTION_ITEM_DELETE = "MEMORY_ITEM_DELETE";
    public static final int MAX_VALUE_CODE_POINTS = 1000;
    public static final int MAX_PREDICATE_CODE_POINTS = 120;
    public static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> KINDS = Set.of("PREFERENCE", "GOAL", "BACKGROUND", "TASK");
    private static final Set<String> LISTABLE_STATUSES = Set.of("ACTIVE", "PENDING", "DISABLED", "SUPERSEDED",
            "EXPIRED");
    private static final int SCAN_BATCH = 100;

    private static final Logger log = LoggerFactory.getLogger(MemoryItemService.class);

    /** 新增一条人工事实；{@code family} 省略时以 {@code predicate} 作同族键。 */
    public record AddInput(String kind, String scopeType, Long scopeId, String predicate, String family, String value,
                           String validUntil, Boolean sensitiveConfirmed, String operationId) {
    }

    /** 编辑：改内容产生新版本，改状态只改写这一版；两者都必须带 expectedVersion。 */
    public record EditInput(Integer expectedVersion, String predicate, String family, String value, String validUntil,
                            String status) {
    }

    private final MemoryCryptoService crypto;
    private final MemoryRepository memory;
    private final MemoryVaultService vault;
    private final MemoryPolicyService policy;
    private final MemoryOperationRepository operations;
    private final MemoryProposalRepository proposals;
    private final MemorySuppressionRepository suppressions;
    private final MemoryJobRepository jobs;
    private final ChatSessionMetadataRepository metadata;
    private final ObjectMapper mapper = new ObjectMapper();

    public MemoryItemService(MemoryCryptoService crypto, MemoryRepository memory, MemoryVaultService vault,
                             MemoryPolicyService policy, MemoryOperationRepository operations,
                             MemoryProposalRepository proposals, MemorySuppressionRepository suppressions,
                             MemoryJobRepository jobs, ChatSessionMetadataRepository metadata) {
        this.crypto = crypto;
        this.memory = memory;
        this.vault = vault;
        this.policy = policy;
        this.operations = operations;
        this.proposals = proposals;
        this.suppressions = suppressions;
        this.jobs = jobs;
        this.metadata = metadata;
    }

    // ---- 新增 ----

    public MemoryApiResult add(AddInput input) {
        String scopeType = input.scopeType() == null ? "GLOBAL" : input.scopeType().trim().toUpperCase(Locale.ROOT);
        long scopeId = input.scopeId() == null ? 0L : input.scopeId();
        String kind = input.kind() == null ? null : input.kind().trim().toUpperCase(Locale.ROOT);
        String problem = shapeProblem(kind, scopeType, input.predicate(), input.value(), input.validUntil());
        if (problem != null) {
            return badRequest(problem);
        }
        MemorySensitivity.Level level = MemorySensitivity.classify(input.value());
        if (level == MemorySensitivity.Level.CREDENTIAL) {
            return MemoryApiResult.error(422, "MEMORY_SENSITIVE_CREDENTIAL", "凭据类内容不写进记忆，请改用密码管理器");
        }
        if (level == MemorySensitivity.Level.SENSITIVE && !Boolean.TRUE.equals(input.sensitiveConfirmed())) {
            return MemoryApiResult.error(409, "MEMORY_SENSITIVE_CONFIRMATION_REQUIRED",
                    "这条内容属于敏感个人信息，需要你明确勾选确认");
        }
        MemoryKey key;
        try {
            MemoryPolicyService.Eligibility eligibility = policy.manualWriteEligibility(scopeType, scopeId);
            if (!eligibility.allowed()) {
                return eligibility.toResult();
            }
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        } catch (SQLException error) {
            return storageError(error);
        }

        String predicate = clean(input.predicate());
        String value = clean(input.value());
        String family = blank(input.family()) ? predicate : clean(input.family());
        String rowId = UUID.randomUUID().toString();
        String dedupeId = blank(input.operationId()) ? null : input.operationId().trim();
        try {
            String predicateHmac = crypto.hmac(key, "predicate:" + predicate);
            String familyHmac = crypto.hmac(key, "family:" + family);
            String valueHmac = crypto.hmac(key, "value:" + value);
            MemoryApiResult replay = begin(dedupeId, ACTION_ITEM_ADD, valueHmac, 201);
            if (replay != null) {
                return replay;
            }
            EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_item", rowId, 1,
                    writePayload(kind, predicate, family, value));
            String now = MemoryTimes.now();
            MemoryRepository.AddResult added = memory.add(new MemoryRepository.NewMemory(rowId, key.vaultId(),
                    scopeType, scopeId, kind, predicateHmac, familyHmac, valueHmac, MemoryRepository.STATUS_ACTIVE,
                    encrypted.cipher(), encrypted.cryptoMeta(), "MANUAL", true, false, null, input.validUntil(),
                    now, now, now));
            if (added.outcome() != MemoryRepository.AddOutcome.ADDED) {
                recordFailure(dedupeId);
                return addConflict(key, added, scopeType, scopeId, familyHmac);
            }
            Map<String, Object> body = itemBody(key, memory.findCurrent(key.vaultId(), added.memoryId()).orElseThrow(),
                    false);
            recordSuccess(dedupeId, ledgerBody(body));
            log.info("手动新增记忆: memoryId={}, version={}", added.memoryId(), added.version());
            return MemoryApiResult.of(201, body);
        } catch (MemoryCryptoException | SQLException error) {
            recordFailure(dedupeId);
            return error instanceof MemoryCryptoException cryptoFailure ? cryptoFailure(cryptoFailure)
                    : storageError(error);
        }
    }

    private MemoryApiResult addConflict(MemoryKey key, MemoryRepository.AddResult added, String scopeType, long scopeId,
                                        String familyHmac) throws SQLException {
        if (added.outcome() == MemoryRepository.AddOutcome.FAMILY_CONFLICT) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (FactRow row : memory.findActiveInFamily(key.vaultId(), scopeType, scopeId, familyHmac)) {
                candidates.add(factKeys(row));
            }
            return MemoryApiResult.error(409, "MEMORY_FAMILY_CONFLICT",
                    "同一族已经有生效的记忆，请选择替换、并存或先整理", false, Map.of("candidates", candidates));
        }
        return MemoryApiResult.error(409, "MEMORY_PENDING_QUOTA_FULL", "待确认条目已达上限，自动新增暂停");
    }

    // ---- 读 ----

    /** GET /memory/items/{id}：当前版本 + 来源；锁定 423，坏密文只隔离该行。 */
    public MemoryApiResult get(String memoryId) {
        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        try {
            Optional<FactRow> row = memory.findCurrent(key.vaultId(), memoryId);
            if (row.isEmpty()) {
                return MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "没有这条记忆，或它已经没有生效版本");
            }
            return MemoryApiResult.of(200, itemBody(key, row.get(), true));
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /**
     * 列表与本地检索：{@code query} 为空就是纯分页列表。正文只在已解锁时出现；匹配是解密后的子串比对，
     * P5 的词法索引会替换这里的扫描方式，游标语义（recorded_at+row_id 倒序 keyset）保持不变。
     */
    public MemoryApiResult list(String scopeType, Long scopeId, String status, String query, Integer limit,
                                String cursor) {
        String normalizedStatus = blank(status) ? MemoryRepository.STATUS_ACTIVE
                : status.trim().toUpperCase(Locale.ROOT);
        if (!LISTABLE_STATUSES.contains(normalizedStatus)) {
            return MemoryApiResult.error(400, "MEMORY_STATUS_INVALID", "不支持的状态过滤: " + status);
        }
        String normalizedScope = blank(scopeType) ? null : scopeType.trim().toUpperCase(Locale.ROOT);
        if (normalizedScope != null && !"GLOBAL".equals(normalizedScope) && !"NOTEBOOK".equals(normalizedScope)) {
            return MemoryApiResult.error(400, "MEMORY_SCOPE_INVALID", "不支持的作用域过滤: " + scopeType);
        }
        if ("NOTEBOOK".equals(normalizedScope) && (scopeId == null || scopeId <= 0)) {
            return MemoryApiResult.error(400, "MEMORY_SCOPE_INVALID", "按笔记本过滤必须给出正的笔记本 ID");
        }
        int pageSize = limit == null ? 10 : Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        String[] decoded;
        try {
            decoded = decodeCursor(cursor);
        } catch (RuntimeException error) {
            return MemoryApiResult.error(400, "MEMORY_CURSOR_INVALID", "分页游标无法解析，请从头开始");
        }
        String afterRecordedAt = decoded == null ? null : decoded[0];
        String afterRowId = decoded == null ? null : decoded[1];

        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matched = new ArrayList<>();
        boolean hasMore = false;
        try {
            while (matched.size() < pageSize) {
                List<FactRow> batch = memory.findPage(key.vaultId(), normalizedStatus,
                        normalizedScope, normalizedScope == null ? 0L : (scopeId == null ? 0L : scopeId),
                        afterRecordedAt, afterRowId, SCAN_BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                int scanned = 0;
                for (FactRow row : batch) {
                    if (matched.size() >= pageSize) {
                        break;
                    }
                    afterRecordedAt = row.recordedAt();
                    afterRowId = row.rowId();
                    scanned++;
                    Map<String, Object> view = itemBody(key, row, false);
                    if (needle.isEmpty() || matches(view, needle)) {
                        matched.add(view);
                    }
                }
                boolean moreToFetch = batch.size() == SCAN_BATCH;
                if (matched.size() >= pageSize) {
                    hasMore = scanned < batch.size() || moreToFetch;
                }
                if (!moreToFetch) {
                    break;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", matched);
            body.put("limit", pageSize);
            body.put("status", normalizedStatus);
            body.put("hasMore", hasMore);
            if (afterRowId != null) {
                body.put("nextCursor", encodeCursor(afterRecordedAt, afterRowId));
            }
            body.put("vaultRevision", memory.readVault(key.vaultId())
                    .map(MemoryRepository.VaultState::revision).orElse(0L));
            return MemoryApiResult.of(200, body);
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    // ---- 改 ----

    public MemoryApiResult edit(String memoryId, EditInput edit) {
        if (edit.expectedVersion() == null) {
            return MemoryApiResult.error(400, "MEMORY_VERSION_REQUIRED", "编辑必须带上读到的 expectedVersion");
        }
        boolean statusChange = !blank(edit.status());
        boolean contentChange = !blank(edit.value()) || !blank(edit.predicate());
        if (!statusChange && !contentChange) {
            return MemoryApiResult.error(400, "MEMORY_NOTHING_TO_EDIT", "没有要修改的内容");
        }
        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        try {
            return statusChange ? changeStatus(key, memoryId, edit) : changeContent(key, memoryId, edit);
        } catch (SQLException error) {
            return storageError(error);
        } catch (MemoryCryptoException error) {
            return cryptoFailure(error);
        }
    }

    private MemoryApiResult changeStatus(MemoryKey key, String memoryId, EditInput edit) throws SQLException {
        String target = edit.status().trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(target) && !"DISABLED".equals(target)) {
            return MemoryApiResult.error(400, "MEMORY_STATUS_INVALID", "只能停用（DISABLED）或重新启用（ACTIVE）");
        }
        Optional<FactRow> row = memory.findVersion(key.vaultId(), memoryId, edit.expectedVersion());
        if (row.isEmpty()) {
            return MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "没有这一版记忆");
        }
        String from = row.get().status();
        if (from.equals(target)) {
            return MemoryApiResult.of(200, Map.of("memoryId", memoryId, "version", edit.expectedVersion(),
                    "status", target, "changed", false));
        }
        if (!MemoryRepository.STATUS_ACTIVE.equals(from) && !"DISABLED".equals(from)) {
            return MemoryApiResult.error(409, "MEMORY_STATUS_FROZEN",
                    "被替换或已过期的版本不能直接改状态，请编辑成新版本", false, Map.of("status", from));
        }
        MemoryRepository.CasOutcome applied = memory.setStatus(key.vaultId(), memoryId, edit.expectedVersion(),
                from, target);
        return switch (applied) {
            case APPLIED -> MemoryApiResult.of(200, Map.of("memoryId", memoryId, "version", edit.expectedVersion(),
                    "status", target));
            case VERSION_CONFLICT -> conflictWithLatest(key, memoryId);
            case NOT_FOUND -> MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "没有这条记忆");
        };
    }

    private MemoryApiResult changeContent(MemoryKey key, String memoryId, EditInput edit)
            throws SQLException, MemoryCryptoException {
        Optional<FactRow> current = memory.findCurrent(key.vaultId(), memoryId);
        if (current.isEmpty()) {
            return MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "这条记忆已经没有生效版本");
        }
        FactRow active = current.get();
        if (active.version() != edit.expectedVersion()) {
            return conflictWithLatest(key, memoryId);
        }
        Map<String, Object> existing = readPayload(key, active);
        String predicate = blank(edit.predicate()) ? text(existing.get("predicate")) : clean(edit.predicate());
        String family = blank(edit.family()) ? (blank(text(existing.get("family"))) ? predicate
                : text(existing.get("family"))) : clean(edit.family());
        String value = blank(edit.value()) ? text(existing.get("value")) : clean(edit.value());
        String kind = blank(text(existing.get("kind"))) ? "PREFERENCE" : text(existing.get("kind"));
        String validUntil = edit.validUntil() == null ? active.validUntil() : edit.validUntil();
        String problem = shapeProblem(kind, active.scopeType(), predicate, value, validUntil);
        if (problem != null) {
            return badRequest(problem);
        }
        if (MemorySensitivity.classify(value) == MemorySensitivity.Level.CREDENTIAL) {
            return MemoryApiResult.error(422, "MEMORY_SENSITIVE_CREDENTIAL", "凭据类内容不写进记忆");
        }
        String familyHmac = crypto.hmac(key, "family:" + family);
        for (FactRow holder : memory.findActiveInFamily(key.vaultId(), active.scopeType(), active.scopeId(),
                familyHmac)) {
            if (!holder.memoryId().equals(memoryId)) {
                return MemoryApiResult.error(409, "MEMORY_FAMILY_CONFLICT", "同族已经有别的生效记忆，请先整理再改", false,
                        Map.of("blocking", factKeys(holder)));
            }
        }
        String rowId = UUID.randomUUID().toString();
        String now = MemoryTimes.now();
        EncryptedValue encrypted = crypto.encrypt(key, "ai_memory_item", rowId, active.version() + 1,
                writePayload(kind, predicate, family, value));
        MemoryRepository.ReplaceResult replaced = memory.replace(key.vaultId(), memoryId, edit.expectedVersion(),
                new MemoryRepository.NewMemory(rowId, key.vaultId(), active.scopeType(), active.scopeId(), kind,
                        crypto.hmac(key, "predicate:" + predicate), familyHmac, crypto.hmac(key, "value:" + value),
                        MemoryRepository.STATUS_ACTIVE, encrypted.cipher(), encrypted.cryptoMeta(), "MANUAL", true,
                        active.pinned(), active.validFrom(), validUntil, active.observedAt(), now, now));
        if (replaced.outcome() == MemoryRepository.ReplaceOutcome.VERSION_CONFLICT) {
            return conflictWithLatest(key, memoryId);
        }
        if (replaced.outcome() == MemoryRepository.ReplaceOutcome.NOT_FOUND) {
            return MemoryApiResult.error(404, "MEMORY_ITEM_NOT_FOUND", "这条记忆已经没有生效版本");
        }
        log.info("记忆已产生新版本: memoryId={}, version={}", memoryId, replaced.version());
        return MemoryApiResult.of(200, itemBody(key, memory.findCurrent(key.vaultId(), memoryId).orElseThrow(), false));
    }

    private MemoryApiResult conflictWithLatest(MemoryKey key, String memoryId) throws SQLException {
        Optional<FactRow> latest = memory.findCurrent(key.vaultId(), memoryId);
        return MemoryApiResult.error(409, "MEMORY_VERSION_CONFLICT", "这条记忆已经被改过，请重载后再试", false,
                latest.map(MemoryItemService::factKeys).orElse(Map.of("version", 0)));
    }

    // ---- 删 ----

    /**
     * 删除整条版本链（§4.4 deleteMemory）：仓储半边在同一事务里删版本、清失去引用的证据、写抑制、
     * 建议置 STALE 并推 epoch；提交后作废旧 epoch 的任务与摘要检查点。
     * {@code forget=true} 额外把所有会话扫描水位与全局遗忘边界推到当前边界——旧发言从此不再自动提回这条记忆。
     */
    public MemoryApiResult delete(String memoryId, Integer expectedVersion, boolean forget, String operationId) {
        if (expectedVersion == null) {
            return MemoryApiResult.error(400, "MEMORY_VERSION_REQUIRED", "删除必须带上读到的 expectedVersion");
        }
        MemoryKey key;
        try {
            key = vault.requireKey();
        } catch (MemoryCryptoException error) {
            return locked();
        }
        String dedupeId = blank(operationId) ? null : operationId.trim();
        MemoryApiResult replay = begin(dedupeId, ACTION_ITEM_DELETE, memoryId, 204);
        if (replay != null) {
            return replay;
        }
        MemoryRepository.DeleteResult deleted;
        try {
            deleted = memory.deleteVersionsByMemoryId(key.vaultId(), memoryId, expectedVersion);
        } catch (SQLException error) {
            recordFailure(dedupeId);
            return storageError(error);
        }
        if (deleted.outcome() == MemoryRepository.DeleteOutcome.VERSION_CONFLICT) {
            recordFailure(dedupeId);
            try {
                return conflictWithLatest(key, memoryId);
            } catch (SQLException error) {
                return storageError(error);
            }
        }
        try {
            if (deleted.deleted()) {
                int cancelled = jobs.supersedeBeforeEpoch(deleted.vault().epoch());
                int contexts = memory.invalidateSessionContextsBelowEpoch(deleted.vault().epoch());
                if (forget) {
                    metadata.advanceAllScanWatermarks(ChatSessionMetadataRepository.ScanKind.MEMORY);
                    metadata.advanceAllScanWatermarks(ChatSessionMetadataRepository.ScanKind.SUMMARY);
                    policy.advanceHistoryFloor(metadata.globalMaxTurnId());
                }
                log.info("记忆已删除: memoryId={}, versions={}, evidence={}, 建议置 STALE={}, 抑制={}, 作废任务={},"
                                + " 失效检查点={}, forget={}", memoryId, deleted.versionsDeleted(),
                        deleted.evidenceDeleted(), deleted.proposalsStaled(), deleted.suppressionsAdded(),
                        cancelled, contexts, forget);
            }
            recordSuccess(dedupeId, Map.of("memoryId", memoryId, "deleted", deleted.deleted()));
            return MemoryApiResult.noContent();
        } catch (SQLException error) {
            recordFailure(dedupeId);
            return storageError(error);
        }
    }

    // ---- 状态计数 ----

    /** GET /memory/status：计数与暂停原因，不含任何正文（§3）。 */
    public MemoryApiResult status() {
        try {
            Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
            Map<String, Object> body = new LinkedHashMap<>(vault.statusBody());
            body.put("limits", Map.of("activeFacts", MemoryPolicyService.ACTIVE_FACT_LIMIT,
                    "pendingFacts", MemoryPolicyService.PENDING_FACT_LIMIT));
            if (envelope.isPresent()) {
                String vaultId = envelope.get().vaultId();
                body.put("activeFacts", memory.activeCount(vaultId));
                body.put("pendingFacts", memory.pendingCount(vaultId));
                body.put("pendingProposals", proposals.pendingCount(vaultId));
                body.put("suppressions", suppressions.countAll(vaultId));
            }
            body.put("queuedJobs", jobs.countByStatus(MemoryJobRepository.STATUS_QUEUED));
            body.put("retryJobs", jobs.countByStatus(MemoryJobRepository.STATUS_RETRY));
            body.put("extraction", policy.extractionEligibility("GLOBAL", 0));
            return MemoryApiResult.of(200, body);
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    // ---- 行视图与载荷 ----

    private Map<String, Object> itemBody(MemoryKey key, FactRow row, boolean withSources) throws SQLException {
        Map<String, Object> body = new LinkedHashMap<>(factKeys(row));
        body.put("origin", row.origin());
        body.put("manualProtected", row.manualProtected());
        body.put("pinned", row.pinned());
        body.put("validFrom", row.validFrom());
        body.put("observedAt", row.observedAt());
        body.put("lastConfirmedAt", row.lastConfirmedAt());
        try {
            Map<String, Object> payload = readPayload(key, row);
            body.put("predicate", payload.get("predicate"));
            body.put("value", payload.get("value"));
            body.put("corrupt", false);
        } catch (MemoryCryptoException error) {
            body.put("corrupt", true);
            body.put("corruptCode", error.code());
            log.info("记忆行密文不可用，按不可用隔离: rowId={}, code={}", row.rowId(), error.code());
        }
        if (withSources) {
            List<Map<String, Object>> sources = new ArrayList<>();
            for (EvidenceRow evidence : memory.evidenceForItem(row.rowId())) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("evidenceId", evidence.evidenceId());
                view.put("sessionId", evidence.sessionId());
                view.put("turnId", evidence.turnId());
                view.put("messageId", evidence.messageId());
                view.put("sourceBlockId", evidence.sourceBlockId());
                view.put("sourceCreatedAt", evidence.sourceCreatedAt());
                try {
                    view.put("quote", crypto.decrypt(key, "ai_memory_evidence", evidence.evidenceId(), 1,
                            evidence.quoteCipher(), evidence.cryptoMeta()));
                } catch (MemoryCryptoException error) {
                    view.put("quote", null);
                    view.put("corrupt", true);
                }
                sources.add(view);
            }
            body.put("sources", sources);
        }
        return body;
    }

    /** 元信息集合：409 与列表用，不含正文。 */
    private static Map<String, Object> factKeys(FactRow row) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("memoryId", row.memoryId());
        keys.put("version", row.version());
        keys.put("kind", row.kind());
        keys.put("scopeType", row.scopeType());
        keys.put("scopeId", row.scopeId());
        keys.put("status", row.status());
        keys.put("validUntil", row.validUntil());
        keys.put("recordedAt", row.recordedAt());
        return keys;
    }

    private Map<String, Object> readPayload(MemoryKey key, FactRow row) throws MemoryCryptoException {
        String json = crypto.decrypt(key, "ai_memory_item", row.rowId(), row.version(), row.payloadCipher(),
                row.cryptoMeta());
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            throw new MemoryCryptoException("MEMORY_CIPHER_CORRUPT", "该行载荷不是可用 JSON，按不可用隔离");
        }
    }

    private String writePayload(String kind, String predicate, String family, String value) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("predicate", predicate);
        payload.put("family", family);
        payload.put("value", value);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new SQLException("记忆载荷无法序列化", error);
        }
    }

    // ---- 校验与工具 ----

    private String shapeProblem(String kind, String scopeType, String predicate, String value, String validUntil) {
        if (kind == null || !KINDS.contains(kind)) {
            return "MEMORY_KIND_INVALID";
        }
        if (!"GLOBAL".equals(scopeType) && !"NOTEBOOK".equals(scopeType)) {
            return "MEMORY_SCOPE_INVALID";
        }
        if (blank(predicate) || codePoints(predicate) > MAX_PREDICATE_CODE_POINTS) {
            return "MEMORY_PREDICATE_INVALID";
        }
        if (blank(value) || codePoints(value) > MAX_VALUE_CODE_POINTS) {
            return "MEMORY_VALUE_INVALID";
        }
        if (validUntil != null && !validUntil.isBlank()) {
            try {
                if (Instant.parse(validUntil).isBefore(Instant.now().minusSeconds(60))) {
                    return "MEMORY_EXPIRY_IN_PAST";
                }
            } catch (RuntimeException error) {
                return "MEMORY_EXPIRY_INVALID";
            }
        }
        return null;
    }

    private static boolean matches(Map<String, Object> view, String needle) {
        return text(view.get("value")).toLowerCase(Locale.ROOT).contains(needle)
                || text(view.get("predicate")).toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String encodeCursor(String recordedAt, String rowId) {
        if (recordedAt == null || rowId == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((recordedAt + "|" + rowId).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        if (blank(cursor)) {
            return null;
        }
        String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        int split = decoded.lastIndexOf('|');
        if (split <= 0 || split == decoded.length() - 1) {
            throw new IllegalArgumentException("bad cursor");
        }
        return new String[]{decoded.substring(0, split), decoded.substring(split + 1)};
    }

    private MemoryApiResult begin(String operationId, String action, String targetId, int replayStatus) {
        if (operationId == null) {
            return null;
        }
        try {
            MemoryOperationRepository.StartResult started = operations.start(operationId, action, targetId,
                    MemoryTimes.now());
            return switch (started.outcome()) {
                case REPLAY -> MemoryApiResult.of(replayStatus, replayBody(started.resultMeta()));
                case IN_FLIGHT -> MemoryApiResult.error(409, "MEMORY_OPERATION_IN_FLIGHT", "同一操作正在进行中",
                        true, null);
                case CONFLICT -> MemoryApiResult.error(409, "MEMORY_OPERATION_ID_CONFLICT",
                        "operationId 已被别的动作或内容占用");
                case NEW -> null;
            };
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    private Map<String, Object> replayBody(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
    }

    /**
     * 幂等台账（{@code ai_memory_operation.result_meta}）只存元信息：这一列是明文 TEXT，
     * 把 predicate/value 写进去等于绕过了整层加密（S12）。重放要的只是"这条操作当初成功了、 id 是这个"，
     * 正文由调用方按 memoryId+version 重新解密读取。
     */
    private static Map<String, Object> ledgerBody(Map<String, Object> body) {
        Map<String, Object> meta = new LinkedHashMap<>(body);
        meta.remove("predicate");
        meta.remove("value");
        meta.remove("sources");
        return meta;
    }

    private void recordSuccess(String operationId, Map<String, Object> body) throws SQLException {
        if (operationId == null) {
            return;
        }
        try {
            operations.complete(operationId, mapper.writeValueAsString(body), MemoryTimes.now());
        } catch (JsonProcessingException error) {
            throw new SQLException("操作响应无法登记", error);
        }
    }

    private void recordFailure(String operationId) {
        if (operationId == null) {
            return;
        }
        try {
            operations.abandon(operationId);
        } catch (SQLException error) {
            log.warn("operationId 登记回滚失败: {}", error.getClass().getSimpleName());
        }
    }

    private static MemoryApiResult locked() {
        return MemoryApiResult.error(MemoryVaultService.HTTP_LOCKED, "MEMORY_LOCKED",
                "记忆库未解锁或已锁定，需要重新解锁", true, null);
    }

    private static MemoryApiResult badRequest(String code) {
        return switch (code) {
            case "MEMORY_KIND_INVALID" ->
                    MemoryApiResult.error(400, code, "记忆类型只能是 PREFERENCE/GOAL/BACKGROUND/TASK");
            case "MEMORY_SCOPE_INVALID" ->
                    MemoryApiResult.error(400, code, "作用域只能是 GLOBAL 或带正数 ID 的 NOTEBOOK");
            case "MEMORY_PREDICATE_INVALID" -> MemoryApiResult.error(400, code,
                    "谓词不能为空且不超过 " + MAX_PREDICATE_CODE_POINTS + " 个字符");
            case "MEMORY_VALUE_INVALID" -> MemoryApiResult.error(400, code,
                    "内容不能为空且不超过 " + MAX_VALUE_CODE_POINTS + " 个字符");
            case "MEMORY_EXPIRY_INVALID" -> MemoryApiResult.error(400, code, "到期时间必须是 ISO-8601 UTC 时间");
            case "MEMORY_EXPIRY_IN_PAST" -> MemoryApiResult.error(400, code, "到期时间不能是过去的时刻");
            default -> MemoryApiResult.error(400, code, "请求不合法");
        };
    }

    private MemoryApiResult cryptoFailure(MemoryCryptoException error) {
        return switch (error.code()) {
            case "MEMORY_LOCKED" -> locked();
            case "MEMORY_CIPHER_CORRUPT" -> MemoryApiResult.error(500, error.code(), "该行密文不可用，已按隔离处理");
            default -> MemoryApiResult.error(500, error.code(), "记忆内容加解密失败，旧值保持不变");
        };
    }

    private MemoryApiResult storageError(Exception error) {
        log.warn("记忆存储层失败: {}", error.getMessage());
        return MemoryApiResult.error(500, "MEMORY_STORAGE_UNAVAILABLE", "记忆存储层暂时不可用");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
