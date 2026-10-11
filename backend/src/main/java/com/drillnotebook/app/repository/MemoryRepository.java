package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 事实与证据的落库事务（contracts §2.3 + §7 的 applyExtraction/deleteMemory 片段）。
 *
 * <p>本类只搬字节：payload/quote/HMAC 都由调用方（P2 的 MemoryCryptoService、P7 的提取链）准备好，
 * 这里不解密、不生成 HMAC，也绝不联网。写方法一律自开 {@link ImmediateTransaction}，
 * 因此不得在 Spring {@code @Transactional} 内调用。</p>
 *
 * <p>版本链的口径：同一 {@code memory_id} 的多行按 {@code version} 递增，{@code UNIQUE(memory_id,version)}
 * 与「同 family 只有一行 ACTIVE」的部分唯一索引都是硬约束；替换必须<b>先</b>把旧 ACTIVE 版 CAS 成
 * SUPERSEDED <b>再</b>插新版，两者同事务，冲突时旧版一行都不动（§2.3「manual version CAS 失败返回 409，不删旧版」）。</p>
 */
@Repository
public class MemoryRepository {
    /** proposal 与 item 的 PENDING 共用上限（§2.3.1）。 */
    public static final int PENDING_QUOTA = 500;
    /** 每条证据原话的明文码点上限（§2.3）：只留最短原话，不复制整轮。 */
    public static final int MAX_QUOTE_CODE_POINTS = 400;
    /** 上限的密文侧等价形式：400 码点最多 1600 UTF-8 字节，封装后不可能超过这个长度。 */
    public static final long MAX_QUOTE_CIPHER_CODE_POINTS =
            ChatStorageSizer.base64CipherBytes((long) MAX_QUOTE_CODE_POINTS * 4L);

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** 新增一条事实的版本内容；{@code memoryId}/{@code rowId} 由服务端生成，不接受外部传入。 */
    public record NewMemory(String vaultId, String scopeType, long scopeId, String kind,
                            String predicateKeyHmac, String familyKeyHmac, String valueHmac,
                            String status, String payloadCipher, String cryptoMeta, String origin,
                            boolean manualProtected, boolean pinned, String validFrom, String validUntil,
                            String observedAt, String recordedAt, String lastConfirmedAt) {
    }

    /** 一条证据的落库材料；{@code sourceHmac} 是 (vault, source_hmac) 去重键，重复提交复用同一行。 */
    public record EvidenceDraft(String vaultId, Long sessionId, Long turnId, Long messageId, String sourceBlockId,
                                String sourceHmac, String quoteCipher, String cryptoMeta, String sourceCreatedAt) {
        public EvidenceDraft {
            int codePoints = quoteCipher == null ? 0 : quoteCipher.codePointCount(0, quoteCipher.length());
            if (codePoints > MAX_QUOTE_CIPHER_CODE_POINTS) {
                throw new IllegalArgumentException("证据密文 " + codePoints + " 码点，已超过 " + MAX_QUOTE_CODE_POINTS
                        + " 个原话码点封装后的上界 " + MAX_QUOTE_CIPHER_CODE_POINTS
                        + "：调用方必须先裁到最短原话（明文的 400 码点上限在 P2 加密前把关）");
            }
        }
    }

    /** 读回的事实行（含密文，解密由上层决定）。 */
    public record FactRow(String rowId, String memoryId, String vaultId, int version, String scopeType, long scopeId,
                          String kind, String predicateKeyHmac, String familyKeyHmac, String valueHmac,
                          String status, String payloadCipher, String cryptoMeta, String origin,
                          boolean manualProtected, boolean pinned, String validFrom, String validUntil,
                          String observedAt, String recordedAt, String lastConfirmedAt) {
    }

    /** 新增结果。FAMILY_CONFLICT / PENDING_QUOTA_FULL 都不留任何行。 */
    public enum AddOutcome { ADDED, FAMILY_CONFLICT, PENDING_QUOTA_FULL }

    /** 替换结果。VERSION_CONFLICT 时 {@code current} 是最新的 ACTIVE 版本元数据（供 409 响应）。 */
    public enum ReplaceOutcome { REPLACED, VERSION_CONFLICT, NOT_FOUND }

    /** 单版本 CAS 的状态改写结果（停用/过期回收用），不复用 REPLACED 以免和"产生新版"混淆。 */
    public enum CasOutcome { APPLIED, VERSION_CONFLICT, NOT_FOUND }

    public record AddResult(AddOutcome outcome, String memoryId, String rowId, int version, long vaultRevision) {
        public static AddResult of(AddOutcome outcome) {
            return new AddResult(outcome, null, null, 0, 0);
        }
    }

    public record ReplaceResult(ReplaceOutcome outcome, String memoryId, String rowId, int version,
                                long vaultRevision, FactRow current) {
        public static ReplaceResult conflict(String memoryId, FactRow current) {
            return new ReplaceResult(ReplaceOutcome.VERSION_CONFLICT, memoryId, null, 0, 0, current);
        }

        public static ReplaceResult missing(String memoryId) {
            return new ReplaceResult(ReplaceOutcome.NOT_FOUND, memoryId, null, 0, 0, null);
        }
    }

    /** 证据去重结果：{@code reused} 表示 (vault, source_hmac) 已有行，没有插入第二条原话。 */
    public record EvidenceResult(String evidenceId, boolean reused) {
    }

    /** 删来源后的重算统计（§2.3「先识别受影响，再删证据，最后重算孤儿自动事实，同一事务完成」）。 */
    public record PurgeResult(int evidenceDeleted, int autoFactsRemoved, int protectedFactsKept,
                              int proposalsDeleted, int proposalsStaled, long vaultRevision) {
        public boolean changed() {
            return evidenceDeleted > 0 || autoFactsRemoved > 0 || proposalsDeleted > 0 || proposalsStaled > 0;
        }
    }

    /** vault 的两个计数器：epoch 是安全边界（遗忘/清空），revision 是内容版本（读路径一致性）。 */
    public record VaultState(String vaultId, long epoch, long revision) {
    }

    private static final String FACT_COLUMNS = "row_id, memory_id, vault_id, version, scope_type, scope_id, kind,"
            + " predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher, crypto_meta, origin,"
            + " manual_protected, pinned, valid_from, valid_until, observed_at, recorded_at, last_confirmed_at";

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemoryRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    // ---- vault 计数 ----

    public Optional<VaultState> readVault(String vaultId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, epoch, revision FROM ai_memory_vault WHERE id = ?")) {
            statement.setString(1, vaultId);
            return firstVault(statement.executeQuery());
        }
    }

    /** 内容变更只推进 revision：读路径据此失效缓存，但不构成遗忘边界。 */
    public VaultState bumpRevision(String vaultId) throws SQLException {
        return updateVault(vaultId, false);
    }

    /** 遗忘/清空：epoch 与 revision 一起推进，旧 epoch 的轮次、job、上下文快照全部作废。 */
    public VaultState bumpEpoch(String vaultId) throws SQLException {
        return updateVault(vaultId, true);
    }

    private VaultState updateVault(String vaultId, boolean advanceEpoch) throws SQLException {
        return immediate.execute(connection -> updateVault(connection, vaultId, advanceEpoch));
    }

    // ---- 事实 ----

    /**
     * 新增一条事实的第 1 版：同 family 已有 ACTIVE 时拒绝（人工添加撞 family 走 409），
     * PENDING 总量到 500 时拒绝，两种拒绝都不插行也不动 vault 计数。
     */
    public AddResult add(NewMemory draft) throws SQLException {
        scopeContract(draft);
        return immediate.execute(connection -> {
            requireVault(connection, draft.vaultId());
            if (draft.status().equals(STATUS_PENDING) && pendingCount(connection, draft.vaultId()) >= PENDING_QUOTA) {
                return AddResult.of(AddOutcome.PENDING_QUOTA_FULL);
            }
            if (draft.status().equals(STATUS_ACTIVE) && hasActiveInFamily(connection, draft)) {
                return AddResult.of(AddOutcome.FAMILY_CONFLICT);
            }
            String memoryId = UUID.randomUUID().toString();
            String rowId = UUID.randomUUID().toString();
            insertFact(connection, draft, memoryId, rowId, 1);
            return new AddResult(AddOutcome.ADDED, memoryId, rowId, 1,
                    updateVault(connection, draft.vaultId(), false).revision());
        });
    }

    /**
     * 替换：同一事务里旧 ACTIVE 版 CAS 成 SUPERSEDED、新 ACTIVE 版 version+1。
     * CAS 未命中（并发改过）就返回 VERSION_CONFLICT，旧版与证据都不动。
     */
    public ReplaceResult replace(String vaultId, String memoryId, int expectedVersion, NewMemory draft)
            throws SQLException {
        scopeContract(draft);
        return immediate.execute(connection -> {
            requireVault(connection, vaultId);
            Optional<FactRow> current = findCurrent(connection, vaultId, memoryId);
            if (current.isEmpty()) {
                return ReplaceResult.missing(memoryId);
            }
            FactRow active = current.get();
            if (active.version() != expectedVersion) {
                return ReplaceResult.conflict(memoryId, active);
            }
            int updated = execute(connection, "UPDATE ai_memory_item SET status = ?"
                    + " WHERE memory_id = ? AND version = ? AND status = ?",
                    STATUS_SUPERSEDED, memoryId, expectedVersion, STATUS_ACTIVE);
            if (updated != 1) {
                return ReplaceResult.conflict(memoryId, findCurrent(connection, vaultId, memoryId).orElse(active));
            }
            String rowId = UUID.randomUUID().toString();
            insertFact(connection, draft, memoryId, rowId, expectedVersion + 1);
            return new ReplaceResult(ReplaceOutcome.REPLACED, memoryId, rowId, expectedVersion + 1,
                    updateVault(connection, vaultId, false).revision(), null);
        });
    }

    /**
     * 确认同一事实仍然成立：多值事实的重复确认不得产生新版本，只刷新 last_confirmed_at。
     */
    public void confirm(String vaultId, String memoryId, int version, String lastConfirmedAt) throws SQLException {
        immediate.execute(connection -> {
            requireVault(connection, vaultId);
            int updated = execute(connection, "UPDATE ai_memory_item SET last_confirmed_at = ?"
                    + " WHERE vault_id = ? AND memory_id = ? AND version = ?", lastConfirmedAt, vaultId, memoryId, version);
            if (updated != 1) {
                throw new SQLException("事实版本不存在，无法确认: " + memoryId + " v" + version);
            }
            return null;
        });
    }

    /** 停用/启用一版事实（人工停用与过期回收都不删历史版本，也不产生新版本）。 */
    public CasOutcome setStatus(String vaultId, String memoryId, int expectedVersion, String expectedStatus,
                               String newStatus) throws SQLException {
        return immediate.execute(connection -> {
            requireVault(connection, vaultId);
            if (findCurrent(connection, vaultId, memoryId).isEmpty()) {
                return CasOutcome.NOT_FOUND;
            }
            int updated = execute(connection, "UPDATE ai_memory_item SET status = ?"
                    + " WHERE vault_id = ? AND memory_id = ? AND version = ? AND status = ?",
                    newStatus, vaultId, memoryId, expectedVersion, expectedStatus);
            if (updated != 1) {
                return CasOutcome.VERSION_CONFLICT;
            }
            updateVault(connection, vaultId, false);
            return CasOutcome.APPLIED;
        });
    }

    /** 当前 ACTIVE 版本（供 GET 与 409 响应带最新 metadata）。 */
    public Optional<FactRow> findCurrent(String vaultId, String memoryId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findCurrent(connection, vaultId, memoryId);
        }
    }

    /** 同 family 的当前 ACTIVE 版本（冲突判定与审阅面板用）。 */
    public List<FactRow> findActiveInFamily(String vaultId, String scopeType, long scopeId, String familyKeyHmac)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS + " FROM ai_memory_item"
                     + " WHERE vault_id = ? AND scope_type = ? AND scope_id = ? AND family_key_hmac = ?"
                     + " AND status = '" + STATUS_ACTIVE + "' ORDER BY memory_id, version")) {
            statement.setString(1, vaultId);
            statement.setString(2, scopeType);
            statement.setLong(3, scopeId);
            statement.setString(4, familyKeyHmac);
            return collect(statement.executeQuery());
        }
    }

    public long pendingCount(String vaultId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return pendingCount(connection, vaultId);
        }
    }

    // ---- 证据 ----

    /**
     * 写入或复用一条证据：{@code UNIQUE(vault_id, source_hmac)} 让同一来源重复提交只留一份原话，
     * 复用命中时不插行、不推进 vault revision。
     */
    public EvidenceResult insertEvidence(EvidenceDraft draft) throws SQLException {
        return immediate.execute(connection -> insertEvidence(connection, draft));
    }

    /** 证据入关联：同一 (item, evidence) 重复关联是幂等的。 */
    public EvidenceResult attachEvidence(EvidenceDraft draft, String itemRowId) throws SQLException {
        return immediate.execute(connection -> {
            EvidenceResult result = insertEvidence(connection, draft);
            execute(connection, "INSERT OR IGNORE INTO ai_memory_item_evidence(item_row_id, evidence_id) VALUES (?, ?)",
                    itemRowId, result.evidenceId());
            return result;
        });
    }

    /** 某版事实当前挂着哪些证据 id（孤儿判定与来源展示用）。 */
    public List<String> evidenceIdsForItem(String itemRowId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT evidence_id FROM ai_memory_item_evidence WHERE item_row_id = ? ORDER BY evidence_id")) {
            statement.setString(1, itemRowId);
            try (ResultSet result = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getString(1));
                }
                return ids;
            }
        }
    }

    /**
     * 来源被删（轮次清理 / 会话删除 / 历史清理）后的证据级联重算，一次事务完成：
     * 识别受影响证据 → 删证据（连带关联）→ 无任何来源的 AUTO 事实整条移除、人工保护的事实只去掉来源 →
     * 引用被删证据的建议删除、目标已消失的建议 STALE → 有变更才推进 vault revision。
     *
     * <p>绝不依赖 {@code ON DELETE SET NULL} 把原话留在库里（§2.3）。</p>
     */
    public PurgeResult purgeEvidenceForDeletedSources(Long sessionId, List<Long> turnIds, List<Long> messageIds)
            throws SQLException {
        return immediate.execute(connection -> purgeEvidence(connection, sessionId, turnIds, messageIds));
    }

    /**
     * 同一事务内的重算入口：删除来源行的调用方（{@code ChatTurnRepository.clearContent}、
     * {@code AiChatSessionRepository.delete}、P9 的历史清理）必须用它，而不是外面再开一个事务——
     * §2.3 要求"识别受影响 → 删证据 → 重算孤儿自动事实"与来源删除在同一事务里完成，
     * 拆成两笔就会出现"原话已清但会话还在"的半截状态。
     */
    public PurgeResult purgeEvidence(Connection connection, Long sessionId, List<Long> turnIds, List<Long> messageIds)
            throws SQLException {
        boolean noScope = sessionId == null && (turnIds == null || turnIds.isEmpty())
                && (messageIds == null || messageIds.isEmpty());
        if (noScope) {
            throw new SQLException("清理证据必须给出会话或轮次/消息范围");
        }
        List<String> evidenceIds = ids(connection,
                "SELECT id FROM ai_memory_evidence WHERE " + evidenceScope(sessionId, turnIds, messageIds),
                evidenceArgs(sessionId, turnIds, messageIds).toArray());
        if (evidenceIds.isEmpty()) {
            return new PurgeResult(0, 0, 0, 0, 0, 0);
        }
        List<String> affectedItemRowIds = ids(connection,
                "SELECT DISTINCT item_row_id FROM ai_memory_item_evidence WHERE evidence_id IN ("
                        + placeholders(evidenceIds.size()) + ")", evidenceIds.toArray());
        // vault 归属必须在删证据之前取，删完就查不到行了
        String vaultId = vaultIdOf(connection, evidenceIds.get(0));
        List<String> removedMemoryIds = new ArrayList<>();
        int removed = 0;
        int kept = 0;
        for (String itemRowId : affectedItemRowIds) {
            Optional<FactRow> fact = findFactByRowId(connection, itemRowId);
            if (fact.isEmpty()) {
                continue;
            }
            boolean orphan = !hasRemainingEvidence(connection, itemRowId, evidenceIds);
            boolean autoLiveFact = "AUTO".equals(fact.get().origin()) && !fact.get().manualProtected()
                    && (STATUS_ACTIVE.equals(fact.get().status()) || STATUS_PENDING.equals(fact.get().status()));
            if (orphan && autoLiveFact) {
                execute(connection, "DELETE FROM ai_memory_item WHERE row_id = ?", itemRowId);
                removedMemoryIds.add(fact.get().memoryId());
                removed++;
            } else if (orphan) {
                kept++;   // 人工保护/历史版本：内容留着，只是来源被去掉
            }
        }
        int proposalsDeleted = execute(connection, "DELETE FROM ai_memory_proposal WHERE evidence_id IN ("
                + placeholders(evidenceIds.size()) + ")", evidenceIds.toArray());
        execute(connection, "DELETE FROM ai_memory_evidence WHERE id IN (" + placeholders(evidenceIds.size()) + ")",
                evidenceIds.toArray());
        int proposalsStaled = 0;
        if (!removedMemoryIds.isEmpty()) {
            proposalsStaled = execute(connection, "UPDATE ai_memory_proposal SET status = 'STALE'"
                    + " WHERE status = 'PENDING' AND target_memory_id IN ("
                    + placeholders(removedMemoryIds.size()) + ")", removedMemoryIds.toArray());
        }
        long revision = updateVault(connection, vaultId, false).revision();
        return new PurgeResult(evidenceIds.size(), removed, kept, proposalsDeleted, proposalsStaled, revision);
    }

    // ---- 事务内原语 ----

    private static EvidenceResult insertEvidence(Connection connection, EvidenceDraft draft) throws SQLException {
        Optional<String> existing = optionalString(connection,
                "SELECT id FROM ai_memory_evidence WHERE vault_id = ? AND source_hmac = ?",
                draft.vaultId(), draft.sourceHmac());
        if (existing.isPresent()) {
            return new EvidenceResult(existing.get(), true);
        }
        String evidenceId = UUID.randomUUID().toString();
        execute(connection, "INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id,"
                        + " source_block_id, source_hmac, quote_cipher, crypto_meta, source_created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                evidenceId, draft.vaultId(), draft.sessionId(), draft.turnId(), draft.messageId(),
                draft.sourceBlockId(), draft.sourceHmac(), draft.quoteCipher(), draft.cryptoMeta(),
                draft.sourceCreatedAt());
        return new EvidenceResult(evidenceId, false);
    }

    private static void insertFact(Connection connection, NewMemory draft, String memoryId, String rowId, int version)
            throws SQLException {
        execute(connection, "INSERT INTO ai_memory_item(row_id, memory_id, vault_id, version, scope_type, scope_id,"
                        + " kind, predicate_key_hmac, family_key_hmac, value_hmac, status, payload_cipher,"
                        + " crypto_meta, origin, manual_protected, pinned, valid_from, valid_until, observed_at,"
                        + " recorded_at, last_confirmed_at) VALUES (" + placeholders(21) + ")",
                rowId, memoryId, draft.vaultId(), version, draft.scopeType(), draft.scopeId(), draft.kind(),
                draft.predicateKeyHmac(), draft.familyKeyHmac(), draft.valueHmac(), draft.status(),
                draft.payloadCipher(), draft.cryptoMeta(), draft.origin(), draft.manualProtected() ? 1 : 0,
                draft.pinned() ? 1 : 0, draft.validFrom(), draft.validUntil(), draft.observedAt(),
                draft.recordedAt(), draft.lastConfirmedAt());
    }

    private static boolean hasActiveInFamily(Connection connection, NewMemory draft) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = ? AND scope_type = ?"
                + " AND scope_id = ? AND family_key_hmac = ? AND status = '" + STATUS_ACTIVE + "'",
                draft.vaultId(), draft.scopeType(), draft.scopeId(), draft.familyKeyHmac()) > 0;
    }

    /** 事务内的 PENDING 计数：建议仓储要与事实仓储共算同一个 500 上限（§2.3.1）。 */
    public static long pendingCount(Connection connection, String vaultId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = ? AND status = '"
                + STATUS_PENDING + "'", vaultId);
    }

    private static boolean hasRemainingEvidence(Connection connection, String itemRowId, List<String> deleting)
            throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM ai_memory_item_evidence WHERE item_row_id = ?"
                + " AND evidence_id NOT IN (" + placeholders(deleting.size()) + ")",
                concat(itemRowId, deleting).toArray()) > 0;
    }

    private static Optional<FactRow> findCurrent(Connection connection, String vaultId, String memoryId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS
                + " FROM ai_memory_item WHERE vault_id = ? AND memory_id = ? AND status = '" + STATUS_ACTIVE
                + "' ORDER BY version DESC LIMIT 1")) {
            statement.setString(1, vaultId);
            statement.setString(2, memoryId);
            return first(statement.executeQuery());
        }
    }

    private static Optional<FactRow> findFactByRowId(Connection connection, String rowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + FACT_COLUMNS + " FROM ai_memory_item WHERE row_id = ?")) {
            statement.setString(1, rowId);
            return first(statement.executeQuery());
        }
    }

    private static void requireVault(Connection connection, String vaultId) throws SQLException {
        if (count(connection, "SELECT COUNT(*) FROM ai_memory_vault WHERE id = ?", vaultId) == 0) {
            throw new SQLException("记忆库不存在: " + vaultId);
        }
    }

    private static VaultState updateVault(Connection connection, String vaultId, boolean advanceEpoch)
            throws SQLException {
        String sql = "UPDATE ai_memory_vault SET revision = revision + 1"
                + (advanceEpoch ? ", epoch = epoch + 1" : "") + " WHERE id = ?";
        if (execute(connection, sql, vaultId) != 1) {
            throw new SQLException("记忆库计数更新未命中单行: " + vaultId);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, epoch, revision FROM ai_memory_vault WHERE id = ?")) {
            statement.setString(1, vaultId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new VaultState(result.getString("id"), result.getLong("epoch"), result.getLong("revision"));
            }
        }
    }

    private static String vaultIdOf(Connection connection, String evidenceId) throws SQLException {
        return optionalString(connection, "SELECT vault_id FROM ai_memory_evidence WHERE id = ?", evidenceId)
                .orElseThrow(() -> new SQLException("证据行已不存在: " + evidenceId));
    }

    private static String evidenceScope(Long sessionId, List<Long> turnIds, List<Long> messageIds) {
        List<String> clauses = new ArrayList<>();
        if (sessionId != null) {
            clauses.add("session_id = ?");
        }
        if (turnIds != null && !turnIds.isEmpty()) {
            clauses.add("turn_id IN (" + placeholders(turnIds.size()) + ")");
        }
        if (messageIds != null && !messageIds.isEmpty()) {
            clauses.add("message_id IN (" + placeholders(messageIds.size()) + ")");
        }
        return String.join(" OR ", clauses);
    }

    private static List<Object> evidenceArgs(Long sessionId, List<Long> turnIds, List<Long> messageIds) {
        List<Object> args = new ArrayList<>();
        if (sessionId != null) {
            args.add(sessionId);
        }
        if (turnIds != null) {
            args.addAll(turnIds);
        }
        if (messageIds != null) {
            args.addAll(messageIds);
        }
        return args;
    }

    private static List<Object> concat(String first, List<String> rest) {
        List<Object> args = new ArrayList<>();
        args.add(first);
        args.addAll(rest);
        return args;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("计数查询没有返回行: " + sql);
                }
                return result.getLong(1);
            }
        }
    }

    private static List<String> ids(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    private static Optional<String> optionalString(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int index = 0; index < args.length; index++) {
            statement.setObject(index + 1, args[index]);
        }
    }

    private static Optional<FactRow> first(ResultSet result) throws SQLException {
        List<FactRow> rows = collect(result);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static List<FactRow> collect(ResultSet result) throws SQLException {
        List<FactRow> rows = new ArrayList<>();
        while (result.next()) {
            rows.add(new FactRow(result.getString("row_id"), result.getString("memory_id"),
                    result.getString("vault_id"), result.getInt("version"), result.getString("scope_type"),
                    result.getLong("scope_id"), result.getString("kind"),
                    result.getString("predicate_key_hmac"), result.getString("family_key_hmac"),
                    result.getString("value_hmac"), result.getString("status"),
                    result.getString("payload_cipher"), result.getString("crypto_meta"),
                    result.getString("origin"), result.getInt("manual_protected") == 1,
                    result.getInt("pinned") == 1, result.getString("valid_from"), result.getString("valid_until"),
                    result.getString("observed_at"), result.getString("recorded_at"),
                    result.getString("last_confirmed_at")));
        }
        return rows;
    }

    private static void scopeContract(NewMemory draft) throws SQLException {
        if (!"GLOBAL".equals(draft.scopeType()) && !"NOTEBOOK".equals(draft.scopeType())) {
            throw new SQLException("非法的记忆作用域: " + draft.scopeType());
        }
        if ("GLOBAL".equals(draft.scopeType()) && draft.scopeId() != 0) {
            throw new SQLException("GLOBAL 事实的 scope_id 必须为 0");
        }
        if ("NOTEBOOK".equals(draft.scopeType()) && draft.scopeId() <= 0) {
            throw new SQLException("NOTEBOOK 事实的 scope_id 必须为正数");
        }
        if (!List.of(STATUS_ACTIVE, STATUS_PENDING, STATUS_SUPERSEDED, "DISABLED", "EXPIRED").contains(draft.status())) {
            throw new SQLException("非法的事实状态: " + draft.status());
        }
        if (!List.of("AUTO", "MANUAL").contains(draft.origin())) {
            throw new SQLException("非法的事实来源: " + draft.origin());
        }
    }

    private static Optional<VaultState> firstVault(ResultSet result) throws SQLException {
        if (!result.next()) {
            return Optional.empty();
        }
        return Optional.of(new VaultState(result.getString("id"), result.getLong("epoch"), result.getLong("revision")));
    }
}
