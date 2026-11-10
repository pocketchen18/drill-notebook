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

    /**
     * 新增一条事实的版本内容。{@code rowId} 必须由持有钥匙的调用方先生成——
     * 密文的 AAD 绑定 {@code vault|table|rowId|version}，仓储内部造 ID 会让上层没法预加密（§6.1.1）；
     * {@code memoryId} 仍然只在新增时由服务端生成，替换走已有链路。
     */
    public record NewMemory(String rowId, String vaultId, String scopeType, long scopeId, String kind,
                            String predicateKeyHmac, String familyKeyHmac, String valueHmac,
                            String status, String payloadCipher, String cryptoMeta, String origin,
                            boolean manualProtected, boolean pinned, String validFrom, String validUntil,
                            String observedAt, String recordedAt, String lastConfirmedAt) {
    }

    /**
     * 一条证据的落库材料；{@code evidenceId} 同样由调用方先生成（quote 的 AAD 绑它），
     * {@code sourceHmac} 是 (vault, source_hmac) 去重键，重复提交复用同一行。
     */
    public record EvidenceDraft(String evidenceId, String vaultId, Long sessionId, Long turnId, Long messageId,
                                String sourceBlockId, String sourceHmac, String quoteCipher, String cryptoMeta,
                                String sourceCreatedAt) {
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

    /** 新增结果。FAMILY_CONFLICT / PENDING_QUOTA_FULL / DUPLICATE 都不留任何行。 */
    public enum AddOutcome { ADDED, DUPLICATE, FAMILY_CONFLICT, PENDING_QUOTA_FULL }

    /** 替换结果。VERSION_CONFLICT 时 {@code current} 是最新的 ACTIVE 版本元数据（供 409 响应）。 */
    public enum ReplaceOutcome { REPLACED, VERSION_CONFLICT, NOT_FOUND }

    /** 单版本 CAS 的状态改写结果（停用/过期回收用），不复用 REPLACED 以免和"产生新版"混淆。 */
    public enum CasOutcome { APPLIED, VERSION_CONFLICT, NOT_FOUND }

    public record AddResult(AddOutcome outcome, String memoryId, String rowId, int version, long vaultRevision) {
        public static AddResult of(AddOutcome outcome) {
            return new AddResult(outcome, null, null, 0, 0);
        }
    }

    /**
     * 带证据的新增结果。归一值重复时 {@code duplicateMemoryId/duplicateVersion} 指向已有当前版本，
     * 不新增任何行，也不推进 vault revision。
     */
    public record AddWithEvidenceResult(AddOutcome outcome, String memoryId, int version, String duplicateMemoryId,
                                        int duplicateVersion, long vaultRevision) {
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

    /** 删来源后的清理统计：已保存记忆保留，只移除失去依据的待确认项与原话。 */
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

    /** 事务内读库计数：§4.4 的 epoch 判定必须与变更同笔事务，不能拿事务外的快照。 */
    public static Optional<VaultState> vaultStateIn(Connection connection, String vaultId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT epoch, revision FROM ai_memory_vault WHERE id = ?")) {
            statement.setString(1, vaultId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(
                        new VaultState(vaultId, result.getLong("epoch"), result.getLong("revision")))
                        : Optional.empty();
            }
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

    /** 单例行的包装信息：只有 wrapped DEK 与其元数据，绝不包含主材料明文。 */
    public record VaultEnvelope(String vaultId, String wrappedDek, String wrapMeta, String mode, long epoch,
                                long revision, String createdAt) {
    }

    /** 重置记忆库时各类派生行的删除计数。 */
    public record VaultPurgeResult(int factsDeleted, int evidenceDeleted, int proposalsDeleted,
                                   int suppressionsDeleted, int jobsDeleted, int contextsDeleted) {
    }

    /** 读当前唯一的记忆库（本应用只有一个库，ID 由创建时随机生成）。 */
    public Optional<VaultEnvelope> readEnvelope() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, wrapped_dek, wrap_meta, mode,"
                     + " epoch, revision, created_at FROM ai_memory_vault WHERE singleton = 1")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VaultEnvelope(result.getString("id"), result.getString("wrapped_dek"),
                        result.getString("wrap_meta"), result.getString("mode"), result.getLong("epoch"),
                        result.getLong("revision"), result.getString("created_at")));
            }
        }
    }

    /**
     * 创建唯一记忆库行。已有库时返回 false 且<b>不覆盖</b>旧 DEK（§3 405 行：不能以错误密码自动覆盖旧库）。
     */
    public boolean createVault(String vaultId, String wrappedDek, String wrapMeta, String mode, String createdAt)
            throws SQLException {
        return Boolean.TRUE.equals(immediate.execute(connection -> {
            if (count(connection, "SELECT COUNT(*) FROM ai_memory_vault WHERE singleton = 1") > 0) {
                return false;
            }
            execute(connection, "INSERT INTO ai_memory_vault(id, singleton, wrapped_dek, wrap_meta, mode, created_at)"
                    + " VALUES (?, 1, ?, ?, ?, ?)", vaultId, wrappedDek, wrapMeta, mode, createdAt);
            return true;
        }));
    }

    /** 换密码只做重包装：DEK 不变，事实与计数一行都不动（§6.1.8）。 */
    public void updateVaultWrapper(String vaultId, String wrappedDek, String wrapMeta, String mode)
            throws SQLException {
        immediate.execute(connection -> {
            if (execute(connection, "UPDATE ai_memory_vault SET wrapped_dek = ?, wrap_meta = ?, mode = ? WHERE id = ?",
                    wrappedDek, wrapMeta, mode, vaultId) != 1) {
                throw new SQLException("记忆库包装更新未命中单行: " + vaultId);
            }
            return null;
        });
    }

    /**
     * 重置不可解锁的记忆库：同一事务里删掉旧库的全部派生记录、旧库行，再装入新 ID 与新包装。
     *
     * <p>作业与摘要检查点没有 vault_id 列，但内容是用旧 DEK 封的、且按 epoch 绑定，重置后必然读不出来，
     * 所以一并清空；{@code ai_memory_operation} 只有动作去重元数据，留在库里以保证 operationId 幂等。</p>
     */
    public ResetResult resetVault(String oldVaultId, String newVaultId, String wrappedDek, String wrapMeta,
                                  String mode, String createdAt) throws SQLException {
        return immediate.execute(connection -> {
            execute(connection, "DELETE FROM ai_memory_item_evidence WHERE item_row_id IN"
                    + " (SELECT row_id FROM ai_memory_item WHERE vault_id = ?)", oldVaultId);
            int facts = execute(connection, "DELETE FROM ai_memory_item WHERE vault_id = ?", oldVaultId);
            execute(connection, "DELETE FROM ai_memory_item_evidence WHERE evidence_id IN"
                    + " (SELECT id FROM ai_memory_evidence WHERE vault_id = ?)", oldVaultId);
            int evidence = execute(connection, "DELETE FROM ai_memory_evidence WHERE vault_id = ?", oldVaultId);
            int proposals = execute(connection, "DELETE FROM ai_memory_proposal WHERE vault_id = ?", oldVaultId);
            int suppressions = execute(connection, "DELETE FROM ai_memory_suppression WHERE vault_id = ?", oldVaultId);
            int jobs = execute(connection, "DELETE FROM ai_memory_job");
            int contexts = execute(connection, "DELETE FROM ai_session_context");
            if (execute(connection, "DELETE FROM ai_memory_vault WHERE id = ?", oldVaultId) != 1) {
                throw new SQLException("待重置的记忆库行不存在: " + oldVaultId);
            }
            execute(connection, "INSERT INTO ai_memory_vault(id, singleton, wrapped_dek, wrap_meta, mode, created_at)"
                    + " VALUES (?, 1, ?, ?, ?, ?)", newVaultId, wrappedDek, wrapMeta, mode, createdAt);
            return new ResetResult(new VaultPurgeResult(facts, evidence, proposals, suppressions, jobs, contexts),
                    new VaultState(newVaultId, 1, 1));
        });
    }

    /** 重置结果：派生行计数 + 新库状态。 */
    public record ResetResult(VaultPurgeResult purged, VaultState vault) {
    }

    /** 一趟清理的计数；{@code remaining} 说明这个库还有派生行没删完（§3：清空未完要回 202）。 */
    public record PurgePass(int factsDeleted, int evidenceDeleted, int proposalsDeleted, int suppressionsDeleted,
                            int jobsDeleted, int contextsDeleted, boolean remaining) {
    }

    /**
     * 清空全部 AI 记忆的批次半边（§11.3）：删事实/候选/证据/摘要/任务/旧 suppression，
     * 每表最多删 {@code budget} 行，删不完就把 {@code remaining} 报回去让调用方继续或回 202。
     *
     * <p>与 {@link #resetVault} 的区别只有一个，也是关键的那个：<b>不换库、不动 DEK</b>。
     * 遗忘边界由调用方先推进 epoch（读路径按 epoch 取内存快照，epoch 一变旧内容立刻不可读），
     * 所以分批删除期间不存在"读到半删状态"的窗口。作业与摘要检查点没有 vault_id 列，但都按 epoch 绑定，
     * 整表清空是有界的（队列有容量上限、每会话一行）。</p>
     */
    public PurgePass purgeVaultBatch(String vaultId, int budget) throws SQLException {
        int limit = Math.max(1, budget);
        return immediate.execute(connection -> {
            // 关联先按同一 ORDER BY + LIMIT 删，不靠外键级联（resetVault 也是这个口径）
            execute(connection, "DELETE FROM ai_memory_item_evidence WHERE item_row_id IN ("
                    + "SELECT row_id FROM ai_memory_item WHERE vault_id = ? ORDER BY row_id LIMIT ?)", vaultId, limit);
            int facts = execute(connection, "DELETE FROM ai_memory_item WHERE row_id IN ("
                    + "SELECT row_id FROM ai_memory_item WHERE vault_id = ? ORDER BY row_id LIMIT ?)", vaultId, limit);
            execute(connection, "DELETE FROM ai_memory_item_evidence WHERE evidence_id IN ("
                    + "SELECT id FROM ai_memory_evidence WHERE vault_id = ? ORDER BY id LIMIT ?)", vaultId, limit);
            int evidence = execute(connection, "DELETE FROM ai_memory_evidence WHERE id IN ("
                    + "SELECT id FROM ai_memory_evidence WHERE vault_id = ? ORDER BY id LIMIT ?)", vaultId, limit);
            int proposals = execute(connection, "DELETE FROM ai_memory_proposal WHERE id IN ("
                    + "SELECT id FROM ai_memory_proposal WHERE vault_id = ? ORDER BY id LIMIT ?)", vaultId, limit);
            int suppressions = execute(connection, "DELETE FROM ai_memory_suppression WHERE rowid IN ("
                    + "SELECT rowid FROM ai_memory_suppression WHERE vault_id = ? ORDER BY rowid LIMIT ?)",
                    vaultId, limit);
            int jobs = execute(connection, "DELETE FROM ai_memory_job WHERE id IN ("
                    + "SELECT id FROM ai_memory_job ORDER BY id LIMIT ?)", limit);
            int contexts = execute(connection, "DELETE FROM ai_session_context WHERE session_id IN ("
                    + "SELECT session_id FROM ai_session_context ORDER BY session_id LIMIT ?)", limit);
            boolean remaining = count(connection, "SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = ?", vaultId) > 0
                    || count(connection, "SELECT COUNT(*) FROM ai_memory_evidence WHERE vault_id = ?", vaultId) > 0
                    || count(connection, "SELECT COUNT(*) FROM ai_memory_proposal WHERE vault_id = ?", vaultId) > 0
                    || count(connection, "SELECT COUNT(*) FROM ai_memory_suppression WHERE vault_id = ?", vaultId) > 0
                    || count(connection, "SELECT COUNT(*) FROM ai_memory_job") > 0
                    || count(connection, "SELECT COUNT(*) FROM ai_session_context") > 0;
            return new PurgePass(facts, evidence, proposals, suppressions, jobs, contexts, remaining);
        });
    }

    /**
     * 删除某本笔记本作用域下的全部记忆派生行。笔记本删除会同时删掉页面/索引，
     * NOTEBOOK scope 的 fact 若留着就会指向不存在的 notebookId；这里把 fact、证据链接、
     * 针对这些 fact 的候选、suppression、扫描 job 一并清掉，并失效绑定该笔记本的会话上下文。
     *
     * @return 删除的 fact 行数
     */
    public int purgeNotebookScope(String vaultId, long notebookId) throws SQLException {
        return immediate.execute(connection -> purgeNotebookScopeIn(connection, vaultId, notebookId));
    }

    /** 事务内版本：供与笔记本删除同成同败的调用方复用，语义与 {@link #purgeNotebookScope} 相同。 */
    public static int purgeNotebookScopeIn(Connection connection, String vaultId, long notebookId)
            throws SQLException {
        requireVault(connection, vaultId);
        execute(connection, "DELETE FROM ai_memory_item_evidence WHERE item_row_id IN ("
                + "SELECT row_id FROM ai_memory_item WHERE vault_id = ? AND scope_type = 'NOTEBOOK'"
                + " AND scope_id = ?)", vaultId, notebookId);
        int proposals = execute(connection, "DELETE FROM ai_memory_proposal WHERE target_memory_id IN ("
                + "SELECT memory_id FROM ai_memory_item WHERE vault_id = ? AND scope_type = 'NOTEBOOK'"
                + " AND scope_id = ?)", vaultId, notebookId);
        int suppressions = execute(connection, "DELETE FROM ai_memory_suppression"
                + " WHERE vault_id = ? AND scope_type = 'NOTEBOOK' AND scope_id = ?", vaultId, notebookId);
        int jobs = execute(connection, "DELETE FROM ai_memory_job"
                + " WHERE source_scope_type = 'NOTEBOOK' AND source_scope_id = ?", notebookId);
        int facts = execute(connection, "DELETE FROM ai_memory_item"
                + " WHERE vault_id = ? AND scope_type = 'NOTEBOOK' AND scope_id = ?", vaultId, notebookId);
        execute(connection, "DELETE FROM ai_memory_evidence WHERE id NOT IN"
                + " (SELECT evidence_id FROM ai_memory_item_evidence)");
        execute(connection, "UPDATE ai_chat_session SET context_generation = context_generation + 1"
                + " WHERE memory_scope_type = 'NOTEBOOK' AND memory_notebook_id = ?", notebookId);
        if (facts > 0 || proposals > 0 || suppressions > 0 || jobs > 0) {
            updateVault(connection, vaultId, false);
        }
        return facts;
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
            insertFact(connection, draft, memoryId, draft.rowId(), 1);
            return new AddResult(AddOutcome.ADDED, memoryId, draft.rowId(), 1,
                    updateVault(connection, draft.vaultId(), false).revision());
        });
    }

    /**
     * 新增事实并挂上来源证据，三者（去重判断、事实、证据）在同一事务内完成。
     * 归一值相同就返回 DUPLICATE 并指向已有版本；重复判断与插入之间不给并发写入留窗口。
     */
    public AddWithEvidenceResult addWithEvidence(NewMemory draft, EvidenceDraft evidence) throws SQLException {
        scopeContract(draft);
        return immediate.execute(connection -> {
            requireVault(connection, draft.vaultId());
            Optional<FactRow> duplicate = findCurrentByValueHmac(connection, draft.vaultId(), draft.valueHmac());
            if (duplicate.isPresent()) {
                return new AddWithEvidenceResult(AddOutcome.DUPLICATE, null, 0, duplicate.get().memoryId(),
                        duplicate.get().version(), 0);
            }
            if (draft.status().equals(STATUS_PENDING) && pendingCount(connection, draft.vaultId()) >= PENDING_QUOTA) {
                return new AddWithEvidenceResult(AddOutcome.PENDING_QUOTA_FULL, null, 0, null, 0, 0);
            }
            if (draft.status().equals(STATUS_ACTIVE) && hasActiveInFamily(connection, draft)) {
                return new AddWithEvidenceResult(AddOutcome.FAMILY_CONFLICT, null, 0, null, 0, 0);
            }
            String memoryId = UUID.randomUUID().toString();
            insertFact(connection, draft, memoryId, draft.rowId(), 1);
            attachEvidenceIn(connection, evidence, draft.rowId());
            return new AddWithEvidenceResult(AddOutcome.ADDED, memoryId, 1, null, 0,
                    updateVault(connection, draft.vaultId(), false).revision());
        });
    }

    /**
     * 替换：同一事务里旧 ACTIVE 版 CAS 成 SUPERSEDED、新 ACTIVE 版 version+1。
     * CAS 未命中（并发改过）就返回 VERSION_CONFLICT，旧版与证据都不动。
     */
    public ReplaceResult replace(String vaultId, String memoryId, int expectedVersion, NewMemory draft)
            throws SQLException {
        return replace(vaultId, memoryId, expectedVersion, draft, STATUS_ACTIVE);
    }

    /**
     * 替换指定状态的当前版本：ACTIVE 与 DISABLED 都允许原地改名/改正文。
     * 新版本沿用调用方给的 draft 状态（详情编辑会保留停用状态，不会因为改名把记忆重新启用）。
     */
    public ReplaceResult replace(String vaultId, String memoryId, int expectedVersion, NewMemory draft,
                                 String expectedStatus) throws SQLException {
        scopeContract(draft);
        if (!STATUS_ACTIVE.equals(expectedStatus) && !"DISABLED".equals(expectedStatus)) {
            throw new SQLException("只能替换 ACTIVE 或 DISABLED 版本: " + expectedStatus);
        }
        return immediate.execute(connection -> {
            requireVault(connection, vaultId);
            Optional<FactRow> current = findVersion(connection, vaultId, memoryId, expectedVersion);
            if (current.isEmpty()) {
                Optional<FactRow> latest = findCurrentAnyStatus(connection, vaultId, memoryId);
                return latest.map(row -> ReplaceResult.conflict(memoryId, row))
                        .orElseGet(() -> ReplaceResult.missing(memoryId));
            }
            FactRow active = current.get();
            if (!expectedStatus.equals(active.status())) {
                return ReplaceResult.conflict(memoryId,
                        findCurrentAnyStatus(connection, vaultId, memoryId).orElse(active));
            }
            int updated = execute(connection, "UPDATE ai_memory_item SET status = ?"
                    + " WHERE memory_id = ? AND version = ? AND status = ?",
                    STATUS_SUPERSEDED, memoryId, expectedVersion, expectedStatus);
            if (updated != 1) {
                return ReplaceResult.conflict(memoryId,
                        findCurrentAnyStatus(connection, vaultId, memoryId).orElse(active));
            }
              String rowId = draft.rowId();
              insertFact(connection, draft, memoryId, rowId, expectedVersion + 1);
              execute(connection, "INSERT OR IGNORE INTO ai_memory_item_evidence(item_row_id, evidence_id)"
                      + " SELECT ?, evidence_id FROM ai_memory_item_evidence WHERE item_row_id = ?",
                      rowId, active.rowId());
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
            // 存在性按"这条记忆还有没有版本"判：重新启用停用中的版本时它正是没有 ACTIVE 行的那一刻
            if (count(connection, "SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = ? AND memory_id = ?",
                    vaultId, memoryId) == 0) {
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

    /** 删除结果：{@code DELETED} 才带抑制所需的键，冲突或缺失时计数为零。 */
    public enum DeleteOutcome { DELETED, VERSION_CONFLICT, NOT_FOUND }

    public record DeleteResult(DeleteOutcome outcome, String scopeType, long scopeId, String familyKeyHmac,
                               String predicateKeyHmac, int versionsDeleted, int evidenceDeleted,
                               int proposalsStaled, int suppressionsAdded, VaultState vault) {
        public static DeleteResult of(DeleteOutcome outcome) {
            return new DeleteResult(outcome, null, 0, null, null, 0, 0, 0, 0, null);
        }

        public boolean deleted() {
            return outcome == DeleteOutcome.DELETED;
        }
    }

    /**
     * 按 memoryId 删掉整条版本链（§4.4 deleteMemory 的仓储半边）：
     * CAS 指定的那一版 → 写 family/predicate 抑制 → 删所有版本 → 删因此失去引用的证据并写来源抑制 →
     * 相关 PENDING 建议置 STALE → epoch 与 revision 一起推进（遗忘边界）。全程同一事务，冲突时一行都不动。
     *
     * <p>指定的版本可以是 ACTIVE 也可以是被停用的那版（停用后再删是正常路径），
     * 但不能拿一个 SUPERSEDED 版本号来删——那说明客户端看的是旧页面。</p>
     */
    public DeleteResult deleteVersionsByMemoryId(String vaultId, String memoryId, int expectedVersion)
            throws SQLException {
        return immediate.execute(connection -> deleteVersionsByMemoryIdIn(connection, vaultId, memoryId,
                expectedVersion));
    }

    /**
     * 事务内半边删除：§4.4 的 accept 撤销要"复用 deleteMemory 全套清理/边界/索引 barrier"，
     * 且必须与建议终态同一笔事务，所以这里只搬走原来 {@code deleteVersionsByMemoryId} 的事务体，
     * 语义一字未改。提交后的 epoch barrier（作废任务与摘要检查点）仍由调用方负责。
     */
    public DeleteResult deleteVersionsByMemoryIdIn(Connection connection, String vaultId, String memoryId,
                                                   int expectedVersion) throws SQLException {
        requireVault(connection, vaultId);
        if (count(connection, "SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = ? AND memory_id = ?",
                vaultId, memoryId) == 0) {
            return DeleteResult.of(DeleteOutcome.NOT_FOUND);
        }
        Optional<FactRow> victimRow = findVersion(connection, vaultId, memoryId, expectedVersion);
        if (victimRow.isEmpty() || STATUS_SUPERSEDED.equals(victimRow.get().status())) {
            FactRow victim = victimRow.orElse(null);
            return new DeleteResult(DeleteOutcome.VERSION_CONFLICT,
                    victim == null ? null : victim.scopeType(), victim == null ? 0 : victim.scopeId(),
                    victim == null ? null : victim.familyKeyHmac(),
                    victim == null ? null : victim.predicateKeyHmac(), 0, 0, 0, 0, null);
        }
        FactRow victim = victimRow.get();
        List<String> rowIds = ids(connection, "SELECT row_id FROM ai_memory_item WHERE vault_id = ?"
                + " AND memory_id = ?", vaultId, memoryId);
        List<String> linkedEvidence = rowIds.isEmpty() ? List.of() : ids(connection,
                "SELECT DISTINCT evidence_id FROM ai_memory_item_evidence WHERE item_row_id IN ("
                        + placeholders(rowIds.size()) + ")", rowIds.toArray());
        int suppressions = insertSuppression(connection, vaultId, victim,
                MemorySuppressionRepository.KIND_FAMILY, victim.familyKeyHmac())
                + insertSuppression(connection, vaultId, victim,
                MemorySuppressionRepository.KIND_PREDICATE, victim.predicateKeyHmac());
        int versions = execute(connection, "DELETE FROM ai_memory_item WHERE vault_id = ? AND memory_id = ?",
                vaultId, memoryId);
        List<String> orphans = linkedEvidence.isEmpty() ? List.of() : ids(connection,
                "SELECT id FROM ai_memory_evidence WHERE id IN (" + placeholders(linkedEvidence.size()) + ")"
                        + " AND NOT EXISTS (SELECT 1 FROM ai_memory_item_evidence link WHERE link.evidence_id"
                        + " = ai_memory_evidence.id)", linkedEvidence.toArray());
        if (!orphans.isEmpty()) {
            List<String> orphanHmacs = ids(connection, "SELECT source_hmac FROM ai_memory_evidence WHERE id IN ("
                    + placeholders(orphans.size()) + ")", orphans.toArray());
            for (String sourceHmac : orphanHmacs) {
                suppressions += insertSuppression(connection, vaultId, victim,
                        MemorySuppressionRepository.KIND_EVIDENCE, sourceHmac);
            }
        }
        // 先置 STALE 再删证据：evidence_id 的外键是 ON DELETE SET NULL，删完就找不到该建议了
        int staled = execute(connection, "UPDATE ai_memory_proposal SET status = 'STALE' WHERE vault_id = ?"
                + " AND status = 'PENDING' AND target_memory_id = ?", vaultId, memoryId);
        if (!linkedEvidence.isEmpty()) {
            staled += execute(connection, "UPDATE ai_memory_proposal SET status = 'STALE' WHERE vault_id = ?"
                            + " AND status = 'PENDING' AND evidence_id IN ("
                            + placeholders(linkedEvidence.size()) + ")",
                    concat(vaultId, linkedEvidence).toArray());
        }
        int evidence = orphans.isEmpty() ? 0 : execute(connection,
                "DELETE FROM ai_memory_evidence WHERE id IN (" + placeholders(orphans.size()) + ")",
                orphans.toArray());
        VaultState vault = updateVault(connection, vaultId, true);
        return new DeleteResult(DeleteOutcome.DELETED, victim.scopeType(), victim.scopeId(),
                victim.familyKeyHmac(), victim.predicateKeyHmac(), versions, evidence, staled, suppressions,
                vault);
    }

    /** 遗忘后旧摘要检查点不可信：按 epoch 清掉快照（§4.4 invalidate session contexts）。 */
    public int invalidateSessionContextsBelowEpoch(long vaultEpoch) throws SQLException {
        return immediate.execute(connection -> execute(connection,
                "DELETE FROM ai_session_context WHERE vault_epoch < ?", vaultEpoch));
    }

    private static int insertSuppression(Connection connection, String vaultId, FactRow source, String kind,
                                         String keyHmac) throws SQLException {
        return execute(connection, "INSERT OR IGNORE INTO ai_memory_suppression(vault_id, scope_type, scope_id,"
                        + " suppression_kind, key_hmac, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                vaultId, source.scopeType(), source.scopeId(), kind, keyHmac, source.recordedAt());
    }

    /** 当前 ACTIVE 版本（供 GET 与 409 响应带最新 metadata）。 */
    public Optional<FactRow> findCurrent(String vaultId, String memoryId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findCurrent(connection, vaultId, memoryId);
        }
    }

    /**
     * 当前可编辑版本（ACTIVE 或 DISABLED），不含历史 SUPERSEDED。
     * 列表只显示一行、详情点开后要能管理停用记忆，都靠这个口径。
     */
    public Optional<FactRow> findCurrentAnyStatus(String vaultId, String memoryId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findCurrentAnyStatus(connection, vaultId, memoryId);
        }
    }

    private static Optional<FactRow> findCurrentAnyStatus(Connection connection, String vaultId, String memoryId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS
                + " FROM ai_memory_item WHERE vault_id = ? AND memory_id = ? AND status <> '"
                + STATUS_SUPERSEDED + "' ORDER BY version DESC LIMIT 1")) {
            statement.setString(1, vaultId);
            statement.setString(2, memoryId);
            return first(statement.executeQuery());
        }
    }

    /** 跨作用域按归一值查当前记忆；自动提取写库前用它拒绝确定重复的内容。 */
    public Optional<FactRow> findCurrentByValueHmac(String vaultId, String valueHmac) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findCurrentByValueHmac(connection, vaultId, valueHmac);
        }
    }

    public static Optional<FactRow> findCurrentByValueHmac(Connection connection, String vaultId, String valueHmac)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS
                + " FROM ai_memory_item WHERE vault_id = ? AND value_hmac = ? AND status <> '"
                + STATUS_SUPERSEDED + "' ORDER BY version DESC LIMIT 1")) {
            statement.setString(1, vaultId);
            statement.setString(2, valueHmac);
            return first(statement.executeQuery());
        }
    }

    /**
     * 某个作用域内按归一值查当前版本。update_memory 用文本定位时必须同时按 scope 收口，
     * 不能拿跨作用域的第一条冒充本册目标。
     */
    public List<FactRow> findCurrentByValueHmacInScope(String vaultId, String scopeType, long scopeId,
                                                       String valueHmac) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS
                     + " FROM ai_memory_item WHERE vault_id = ? AND scope_type = ? AND scope_id = ?"
                     + " AND value_hmac = ? AND status <> '" + STATUS_SUPERSEDED
                     + "' ORDER BY version DESC")) {
            statement.setString(1, vaultId);
            statement.setString(2, scopeType);
            statement.setLong(3, scopeId);
            statement.setString(4, valueHmac);
            return collect(statement.executeQuery());
        }
    }

    /** 按版本号读某一版（状态改写与冲突 metadata 用，不限于当前 ACTIVE）。 */
    public Optional<FactRow> findVersion(String vaultId, String memoryId, int version) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findVersion(connection, vaultId, memoryId, version);
        }
    }

    private static Optional<FactRow> findVersion(Connection connection, String vaultId, String memoryId, int version)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS
                + " FROM ai_memory_item WHERE vault_id = ? AND memory_id = ? AND version = ?")) {
            statement.setString(1, vaultId);
            statement.setString(2, memoryId);
            statement.setInt(3, version);
            return first(statement.executeQuery());
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

    /** ACTIVE 事实条数（§6.2 的 2,000 上限判定用；不含被替换/停用/过期的历史版本）。 */
    public long activeCount(String vaultId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return count(connection, "SELECT COUNT(*) FROM ai_memory_item WHERE vault_id = ? AND status = '"
                    + STATUS_ACTIVE + "'", vaultId);
        }
    }

    /**
     * 管理页分页读：按 {@code (recorded_at, row_id)} 倒序 keyset 翻页，多取一条用于判断还有没有下一页。
     * {@code status} 为空表示不按状态过滤；{@code scopeType} 为空表示不按作用域过滤；
     * 游标是上一页最后一行的时间+行号，翻页期间数据变更不做补齐。
     */
    public List<FactRow> findPage(String vaultId, String status, String scopeType, Long scopeId,
                                  String beforeRecordedAt, String beforeRowId, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ").append(FACT_COLUMNS).append(" FROM ai_memory_item")
                .append(" WHERE vault_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(vaultId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        } else {
            sql.append(" AND status <> '").append(STATUS_SUPERSEDED)
                    .append("' AND status <> '").append(STATUS_PENDING).append("'");
        }
        if (scopeType != null) {
            sql.append(" AND scope_type = ? AND scope_id = ?");
            args.add(scopeType);
            args.add(scopeId);
        }
        if (beforeRecordedAt != null && beforeRowId != null) {
            sql.append(" AND (recorded_at < ? OR (recorded_at = ? AND row_id < ?))");
            args.add(beforeRecordedAt);
            args.add(beforeRecordedAt);
            args.add(beforeRowId);
        }
        sql.append(" ORDER BY recorded_at DESC, row_id DESC LIMIT ?");
        args.add(Math.max(1, limit));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, args.toArray());
            return collect(statement.executeQuery());
        }
    }

    /** 一条事实版本的来源证据（含密文，解密由上层决定）。 */
    public record EvidenceRow(String evidenceId, Long sessionId, Long turnId, Long messageId, String sourceBlockId,
                             String sourceHmac, String quoteCipher, String cryptoMeta, String sourceCreatedAt) {
    }

    /** 读回某一行（某个版本）关联的证据，按来源时间倒序。 */
    public List<EvidenceRow> evidenceForItem(String itemRowId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return evidenceForItem(connection, itemRowId);
        }
    }

    private static List<EvidenceRow> evidenceForItem(Connection connection, String itemRowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT e.id, e.session_id, e.turn_id,"
                + " e.message_id, e.source_block_id, e.source_hmac, e.quote_cipher, e.crypto_meta,"
                + " e.source_created_at FROM ai_memory_evidence e JOIN ai_memory_item_evidence link"
                + " ON link.evidence_id = e.id WHERE link.item_row_id = ?"
                + " ORDER BY e.source_created_at DESC, e.id")) {
            statement.setString(1, itemRowId);
            try (ResultSet result = statement.executeQuery()) {
                List<EvidenceRow> rows = new ArrayList<>();
                while (result.next()) {
                    Long session = nullableLong(result, "session_id");
                    Long turn = nullableLong(result, "turn_id");
                    Long message = nullableLong(result, "message_id");
                    rows.add(new EvidenceRow(result.getString("id"), session, turn, message,
                            result.getString("source_block_id"), result.getString("source_hmac"),
                            result.getString("quote_cipher"), result.getString("crypto_meta"),
                            result.getString("source_created_at")));
                }
                return rows;
            }
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
     * 原话证据与来源删除在同一事务里完成，已保存的长期记忆独立保留；
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
            if (orphan && removableWithSource(fact.get())) {
                execute(connection, "DELETE FROM ai_memory_item WHERE row_id = ?", itemRowId);
                removedMemoryIds.add(fact.get().memoryId());
                removed++;
            } else if (orphan) {
                kept++;   // 已保存的记忆（包括自动记忆）与历史版本都只断来源
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
        // 原话/待确认项不属于检索快照；保留的正文没变，不必作废索引或其它会话的计划。
        long revision = vaultStateIn(connection, vaultId).orElseThrow().revision();
        return new PurgeResult(evidenceIds.size(), removed, kept, proposalsDeleted, proposalsStaled, revision);
    }

    private static boolean removableWithSource(FactRow fact) {
        return "AUTO".equals(fact.origin()) && !fact.manualProtected() && STATUS_PENDING.equals(fact.status());
    }

    /**
     * 清理预览要报的「受影响记忆」计数（contracts §3「保存…受影响记忆计数」、execution §7.4.5
     *「用户清理必须先预览…受影响记忆」）。<b>纯读</b>：一行都不删，判定口径与
     * {@link #purgeEvidence} 逐字同源，所以预览报的数与执行时真正删掉的数必须相等。
     *
     * @param evidence 会被删掉的原话证据行数
     * @param proposals 挂在这些证据上的待处理建议数（会一起删）
     * @param autoFacts 失去唯一来源且无人工保护的待确认项（会被删）
     * @param protectedFacts 失去来源的已保存记忆与历史版本（保留内容；字段名保持兼容）
     */
    public record MemoryImpact(int evidence, int proposals, int autoFacts, int protectedFacts) {
    }

    /** 预览用的只读计数：自开一条只读连接，不参与任何写事务（清理预览必须是纯读）。 */
    public MemoryImpact memoryImpact(Long sessionId, List<Long> turnIds, List<Long> messageIds) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return memoryImpactIn(connection, sessionId, turnIds, messageIds);
        }
    }

    public static MemoryImpact memoryImpactIn(Connection connection, Long sessionId, List<Long> turnIds,
                                              List<Long> messageIds) throws SQLException {
        boolean noScope = sessionId == null && (turnIds == null || turnIds.isEmpty())
                && (messageIds == null || messageIds.isEmpty());
        if (noScope) {
            throw new SQLException("统计受影响记忆必须给出会话或轮次/消息范围");
        }
        List<String> evidenceIds = ids(connection,
                "SELECT id FROM ai_memory_evidence WHERE " + evidenceScope(sessionId, turnIds, messageIds),
                evidenceArgs(sessionId, turnIds, messageIds).toArray());
        if (evidenceIds.isEmpty()) {
            return new MemoryImpact(0, 0, 0, 0);
        }
        int proposals = Math.toIntExact(count(connection,
                "SELECT COUNT(*) FROM ai_memory_proposal WHERE evidence_id IN ("
                        + placeholders(evidenceIds.size()) + ")", evidenceIds.toArray()));
        List<String> affectedItemRowIds = ids(connection,
                "SELECT DISTINCT item_row_id FROM ai_memory_item_evidence WHERE evidence_id IN ("
                        + placeholders(evidenceIds.size()) + ")", evidenceIds.toArray());
        int autoFacts = 0;
        int protectedFacts = 0;
        for (String itemRowId : affectedItemRowIds) {
            Optional<FactRow> fact = findFactByRowId(connection, itemRowId);
            if (fact.isEmpty()) {
                continue;
            }
            // 与 purgeEvidence 同一个孤儿判据：除这批之外没有别的来源了才算"失去唯一来源"
            if (hasRemainingEvidence(connection, itemRowId, evidenceIds)) {
                continue;
            }
            if (removableWithSource(fact.get())) {
                autoFacts++;
            } else {
                protectedFacts++;
            }
        }
        return new MemoryImpact(evidenceIds.size(), proposals, autoFacts, protectedFacts);
    }

    // ---- 事务内原语 ----
    //
    // 下面这组 *接受调用方 Connection* 的方法只允许在调用方自己的事务里用（P7 的 §7 提交就是这个场景：
    // 租约 CAS、来源复验、写事实与推进水位必须在同一笔事务内见胜负）。
    // 类里那些不带 Connection 的写方法各自自开 ImmediateTransaction，绝不能被套进别人的事务。

    /** 同族当前 ACTIVE 行（事务内版本，供冲突判定与提交前复验用）。 */
    public static List<FactRow> activeInFamily(Connection connection, String vaultId, String scopeType, long scopeId,
                                               String familyKeyHmac) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + FACT_COLUMNS + " FROM ai_memory_item"
                + " WHERE vault_id = ? AND scope_type = ? AND scope_id = ? AND family_key_hmac = ?"
                + " AND status = '" + STATUS_ACTIVE + "' ORDER BY memory_id, version")) {
            statement.setString(1, vaultId);
            statement.setString(2, scopeType);
            statement.setLong(3, scopeId);
            statement.setString(4, familyKeyHmac);
            return collect(statement.executeQuery());
        }
    }

    /**
     * 按 (memory_id, version) 取代号把当前 ACTIVE 版置为 SUPERSEDED：与手动 {@code replace} 同一套 CAS，
     * 命中 0 行就是"目标被并发改过"，调用方必须放弃这条候选而不是硬写。
     */
    public static int supersedeVersionIn(Connection connection, String memoryId, int version) throws SQLException {
        return execute(connection, "UPDATE ai_memory_item SET status = ?"
                        + " WHERE memory_id = ? AND version = ? AND status = ?",
                STATUS_SUPERSEDED, memoryId, version, STATUS_ACTIVE);
    }

    /** 同值确认：只刷新 ACTIVE 版的 last_confirmed_at，不产生新版本（§8.3「CONFIRM 不新增 ACTIVE」）。 */
    public static int confirmIn(Connection connection, String rowId, String asOf) throws SQLException {
        return execute(connection, "UPDATE ai_memory_item SET last_confirmed_at = ?"
                + " WHERE row_id = ? AND status = '" + STATUS_ACTIVE + "'", asOf, rowId);
    }

    /** 证据 + 关联同时写：重复关联幂等（§2.3 的去重来源）。 */
    public static EvidenceResult attachEvidenceIn(Connection connection, EvidenceDraft draft, String itemRowId)
            throws SQLException {
        EvidenceResult result = insertEvidence(connection, draft);
        execute(connection, "INSERT OR IGNORE INTO ai_memory_item_evidence(item_row_id, evidence_id) VALUES (?, ?)",
                itemRowId, result.evidenceId());
        return result;
    }

    /** 把<b>已存在</b>的证据挂到新的一行上：审阅采纳建议时原话已随建议落库，不该重新造一份证据。 */
    public static int linkEvidenceIn(Connection connection, String itemRowId, String evidenceId) throws SQLException {
        return execute(connection, "INSERT OR IGNORE INTO ai_memory_item_evidence(item_row_id, evidence_id)"
                + " VALUES (?, ?)", itemRowId, evidenceId);
    }

    /** 事务内按版本号读某一版（含 PENDING/DISABLED）：审阅待确认候选要读的就是那条 PENDING 行。 */
    public static Optional<FactRow> findVersionIn(Connection connection, String vaultId, String memoryId, int version)
            throws SQLException {
        return findVersion(connection, vaultId, memoryId, version);
    }

    /** 事务内读某一行挂着哪些来源：候选审阅要拿原话判"根据还在不在"，拒绝时还要它的 {@code source_hmac} 写抑制。 */
    public static List<EvidenceRow> evidenceForItemIn(Connection connection, String itemRowId) throws SQLException {
        return evidenceForItem(connection, itemRowId);
    }

    /**
     * 采纳一条待确认候选：PENDING 版就地转 ACTIVE 并打人工保护。
     *
     * <p>状态前提写在 {@code WHERE} 里，命中 0 行就是"这一版已经不是待确认"，调用方回 409 而不是重试。
     * 同族只允许一条 ACTIVE 是<b>数据库</b>保证的（{@code idx_memory_one_active_family} 部分唯一索引），
     * 所以调用方必须先做 {@link #activeInFamily} 复验把冲突翻成 409，不能靠这里报错。</p>
     */
    public static int promotePendingIn(Connection connection, String vaultId, String memoryId, int version,
                                       String asOf) throws SQLException {
        return execute(connection, "UPDATE ai_memory_item SET status = ?, manual_protected = 1,"
                        + " last_confirmed_at = ? WHERE vault_id = ? AND memory_id = ? AND version = ? AND status = ?",
                STATUS_ACTIVE, asOf, vaultId, memoryId, version, STATUS_PENDING);
    }

    /** 删掉一条待确认候选（只允许删 PENDING；ACTIVE 与历史版本都得留着）。关联证据由外键级联断开。 */
    public static int deletePendingVersionIn(Connection connection, String vaultId, String memoryId, int version)
            throws SQLException {
        return execute(connection, "DELETE FROM ai_memory_item WHERE vault_id = ? AND memory_id = ? AND version = ?"
                + " AND status = '" + STATUS_PENDING + "'", vaultId, memoryId, version);
    }

    /** 按证据 id 读那行来源（列表与详情的只读半边）。 */
    public Optional<EvidenceRow> findEvidence(String evidenceId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findEvidenceIn(connection, evidenceId);
        }
    }

    /**
     * 事务内按证据 id 读那行来源：审阅建议时既要判"来源还在不在"（源被撤销或整轮清理后就没有可审阅的
     * 根据了），也要拿它的 {@code source_hmac} 写拒绝抑制、拿 {@code sourceCreatedAt} 定来源时间。
     */
    public static Optional<EvidenceRow> findEvidenceIn(Connection connection, String evidenceId)
            throws SQLException {
        if (evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, session_id, turn_id, message_id,"
                + " source_block_id, source_hmac, quote_cipher, crypto_meta, source_created_at"
                + " FROM ai_memory_evidence WHERE id = ?")) {
            statement.setString(1, evidenceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EvidenceRow(result.getString("id"), nullableLong(result, "session_id"),
                        nullableLong(result, "turn_id"), nullableLong(result, "message_id"),
                        result.getString("source_block_id"), result.getString("source_hmac"),
                        result.getString("quote_cipher"), result.getString("crypto_meta"),
                        result.getString("source_created_at")));
            }
        }
    }

    public static EvidenceResult insertEvidence(Connection connection, EvidenceDraft draft) throws SQLException {
        Optional<String> existing = optionalString(connection,
                "SELECT id FROM ai_memory_evidence WHERE vault_id = ? AND source_hmac = ?",
                draft.vaultId(), draft.sourceHmac());
        if (existing.isPresent()) {
            return new EvidenceResult(existing.get(), true);
        }
        String evidenceId = draft.evidenceId();
        if (evidenceId == null || evidenceId.isBlank()) {
            throw new SQLException("evidenceId 必须由调用方先生成：原话密文的 AAD 绑定它");
        }
        execute(connection, "INSERT INTO ai_memory_evidence(id, vault_id, session_id, turn_id, message_id,"
                        + " source_block_id, source_hmac, quote_cipher, crypto_meta, source_created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                evidenceId, draft.vaultId(), draft.sessionId(), draft.turnId(), draft.messageId(),
                draft.sourceBlockId(), draft.sourceHmac(), draft.quoteCipher(), draft.cryptoMeta(),
                draft.sourceCreatedAt());
        return new EvidenceResult(evidenceId, false);
    }

    public static void insertFact(Connection connection, NewMemory draft, String memoryId, String rowId, int version)
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

    public static Optional<FactRow> findCurrent(Connection connection, String vaultId, String memoryId)
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

    public static VaultState updateVault(Connection connection, String vaultId, boolean advanceEpoch)
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

    /** SQLite 的 {@code getLong} 对 NULL 返回 0，可空外键必须靠 wasNull 区分"没有"和"0 号"。 */
    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
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
        if (draft.rowId() == null || draft.rowId().isBlank()) {
            throw new SQLException("rowId 必须由调用方先生成：密文 AAD 绑定行身份，仓储不能事后补");
        }
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
