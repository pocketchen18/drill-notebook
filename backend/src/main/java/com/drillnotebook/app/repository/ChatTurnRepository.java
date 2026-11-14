package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 轮次持久化（contracts §2.2 + §2.7）：{@code ai_chat_turn} 与它的正文、附件、账本的唯一原子入口。
 *
 * <p>本类只管生命周期与容量，不碰供应商调用——网络请求永远不在 SQLite 写事务内（执行方案 §4 不变量 4）。
 * 写方法一律自己开 {@link ImmediateTransaction}，因此<b>不得</b>在 Spring {@code @Transactional}
 * 内部调用：那是两个连接上的两笔事务，会互相等锁。</p>
 */
@Repository
public class ChatTurnRepository {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ABORTED = "ABORTED";
    public static final String STATUS_TRUNCATED = "TRUNCATED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    /** 开始轮次的结果。额度不足与重复请求都不留下 RUNNING 残留。 */
    public enum StartOutcome { STARTED, HISTORY_STORAGE_FULL, ATTACHMENT_STORAGE_FULL, DUPLICATE_REQUEST, RUNNING_EXISTS }

    /**
     * 结束轮次的结果。{@code applied=false} 且 {@code alreadyTerminal=true} 表示这一轮早已是终态：
     * 没有重复插入消息、没有二次结算，调用方拿既有状态回给前端（C06「done 丢失后查状态」）。
     */
    public record TerminalOutcome(boolean applied, boolean alreadyTerminal, String status, long storedHistoryBytes,
                                  long historyRevision) {
    }

    /** 实际回复超出预留：整个事务回滚，轮次保持 RUNNING，不伪装 COMPLETED。 */
    public static class ReservationExceededException extends SQLException {
        public ReservationExceededException(long turnId, long needed, long reserved) {
            super("轮次 " + turnId + " 实际新增 " + needed + " 字节，超出预留 " + reserved + " 字节");
        }
    }

    /**
     * 显式清理在事务里发现"要删的东西已经不是预览那一份"：整笔回滚，一个字节都不少删也不多删。
     * 它继承 {@link SQLException} 是为了让 {@link ImmediateTransaction} 认定失败并回滚，
     * 服务层再按 {@link #reason()} 翻成 409，而不是让它冒成 500。
     */
    public static class CleanupRefusalException extends SQLException {
        private final String reason;

        public CleanupRefusalException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** {@code REVISION_CONFLICT}（版本被别的写推走）或 {@code SOURCE_CHANGED}（行被改写）。 */
        public String reason() {
            return reason;
        }
    }

    /**
     * 一次显式清理的实删结果。字节口径与预览同源（同一份行数据算出来的释放量），
     * 记忆侧的四个数与 {@link MemoryRepository#memoryImpactIn} 的预览计数同源——
     * 预览说受影响几条，这里就必须是几条，少一条多一条都是缺陷。
     *
     * @param checkpointsInvalidated 作废的摘要检查点行数（contracts §3「先断开相关读/任务」）
     * @param jobsSuperseded 被作废的在途维护任务数（只顶掉读数跨过被清范围的那几笔）
     */
    public record CleanupOutcome(int cleanedTurns, int legacyCount, int messageCount, long releasedHistoryBytes,
                                 long releasedAttachmentBytes, int evidenceDeleted, int proposalsDeleted,
                                 int autoFactsRemoved, int protectedFactsKept, int checkpointsInvalidated,
                                 int jobsSuperseded, long historyRevision) {
    }

    /** 开始轮次输入：正文与请求元数据必须已由调用方完成有界校验与加密。 */
    public record Draft(String requestId, long sessionId, long contextGeneration, String sourceScopeType,
                        long sourceScopeId, String sourceTimezone, String runtimeGeneration, boolean memoryEligible,
                        Long memoryPolicyRevision, String requestMetaCipher, String requestMetaCrypto,
                        String userContent, String userContentCipher, String userContentMeta,
                        List<AttachmentPayload> attachments, String startedAt, String retryOf) {
        public Draft {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** 不带 retryOf 的旧形态：不是"重新生成"的发言走这里。 */
        public Draft(String requestId, long sessionId, long contextGeneration, String sourceScopeType,
                     long sourceScopeId, String sourceTimezone, String runtimeGeneration, boolean memoryEligible,
                     Long memoryPolicyRevision, String requestMetaCipher, String requestMetaCrypto,
                     String userContent, String userContentCipher, String userContentMeta,
                     List<AttachmentPayload> attachments, String startedAt) {
            this(requestId, sessionId, contextGeneration, sourceScopeType, sourceScopeId, sourceTimezone,
                    runtimeGeneration, memoryEligible, memoryPolicyRevision, requestMetaCipher, requestMetaCrypto,
                    userContent, userContentCipher, userContentMeta, attachments, startedAt, null);
        }
    }

    /** 附件在开始事务前已完成校验与加密，这里只带落库材料。 */
    public record AttachmentPayload(String id, String blockId, String kind, byte[] payloadCipher, String cryptoMeta) {
        /** byte_length 口径：密文 BLOB 长度 + crypto_meta 的 UTF-8 长度。 */
        public long storedBytes() {
            return ChatStorageSizer.storedBytes(payloadCipher, cryptoMeta);
        }
    }

    /** 终态写入内容；{@code status} 必须是 RUNNING 之外的终态。 */
    public record Terminal(String status, String assistantContent, String assistantContentCipher,
                           String assistantContentMeta, String reasoningCipher, String reasoningMeta,
                           String resultMetaCipher, String resultMetaCrypto, String finishReason,
                           String persistenceStatus, String finishedAt) {
    }

    /** 轮次读取视图（不含任何需要解密的内容）。 */
    public record TurnRow(long id, String requestId, long sessionId, String status, Long userMessageId,
                          Long assistantMessageId, long contextGeneration, String sourceScopeType, long sourceScopeId,
                          long sourceRevision, long storedHistoryBytes, long reservedHistoryBytes,
                          String contentDeletedAt, String persistenceStatus, String finishReason, String startedAt,
                          String finishedAt, String retryOf, boolean memoryEligible) {
    }

    /**
     * 开始轮次的结果。{@code historyRevision} 只有一个含义：本事务提交后的会话 history_revision，
     * 没写任何东西时（额度不足/会话忙）为 0；重复请求也带回当前值，供调用方直接比对快照。
     * {@code turn} 在 DUPLICATE_REQUEST 时是已存在的那一行，其余为 null。
     */
    public record StartResult(StartOutcome outcome, TurnRow turn, long historyRevision) {
        public static StartResult of(StartOutcome outcome) {
            return new StartResult(outcome, null, 0);
        }

        public long turnId() {
            return turn == null ? 0 : turn.id();
        }

        public long userMessageId() {
            return turn == null ? 0 : orZero(turn.userMessageId());
        }
    }

    private static final String TURN_SELECT = "SELECT id, request_id, session_id, status, user_message_id,"
            + " assistant_message_id, context_generation, source_scope_type, source_scope_id, source_revision,"
            + " stored_history_bytes, reserved_history_bytes, content_deleted_at, persistence_status, finish_reason,"
            + " started_at, finished_at, retry_of, memory_eligible FROM ai_chat_turn";

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;
    private final ChatStorageLedger ledger;
    private final ChatSessionMetadataRepository metadata;
    private final MemoryRepository memory;

    public ChatTurnRepository(DataSource dataSource, ImmediateTransaction immediate, ChatStorageLedger ledger,
                              ChatSessionMetadataRepository metadata, MemoryRepository memory) {
        this.dataSource = dataSource;
        this.immediate = immediate;
        this.ledger = ledger;
        this.metadata = metadata;
        this.memory = memory;
    }

    /**
     * 开始轮次：一次事务写 turn + user 消息 + 附件 + 账本，并为回复预留 2 MiB。
     * 额度不足时账本与表都不留痕（无 RUNNING、无上游调用）。
     */
    public StartResult begin(Draft draft) throws SQLException {
        ledger.ensureRebuiltInProcess();
        return immediate.execute(connection -> {
            rejectSuperseded(connection, draft.requestId());
            Optional<TurnRow> existing = findByRequestId(connection, draft.requestId());
            if (existing.isPresent()) {
                return new StartResult(StartOutcome.DUPLICATE_REQUEST, existing.get(),
                        ChatSessionMetadataRepository.read(connection, draft.sessionId())
                                .orElseThrow().historyRevision());
            }
            if (findRunning(connection, draft.sessionId()).isPresent()
                    || SessionDispatchRepository.blocksStart(connection,draft.sessionId(),draft.requestId())) {
                return StartResult.of(StartOutcome.RUNNING_EXISTS);
            }

            long userBytes = messageBytes(draft.userContent(), draft.userContentCipher(), draft.userContentMeta(),
                    null, null);
            long turnMetaBytes = ChatStorageSizer.storedBytes(draft.requestMetaCipher(), draft.requestMetaCrypto());
            long newHistoryBytes = Math.addExact(userBytes, turnMetaBytes);
            long attachmentBytes = draft.attachments().stream().mapToLong(AttachmentPayload::storedBytes).sum();

            if (draft.retryOf() != null) {
                return restart(connection, draft, newHistoryBytes, attachmentBytes);
            }

            ChatStorageLedger.Fit fit = ledger.reserve(connection, newHistoryBytes, attachmentBytes);
            if (!fit.accepted()) {
                return StartResult.of(StartOutcome.valueOf(fit.name()));
            }

            long turnId = insertTurn(connection, draft, newHistoryBytes);
            long userMessageId = insertMessage(connection, draft.sessionId(), "user", turnId, "USER",
                    draft.userContent(), draft.userContentCipher(), draft.userContentMeta(), null, null);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_chat_turn SET user_message_id = ? WHERE id = ?")) {
                statement.setLong(1, userMessageId);
                statement.setLong(2, turnId);
                requireOneRow(statement.executeUpdate(), "轮次回填 user 消息");
            }
            for (AttachmentPayload attachment : draft.attachments()) {
                insertAttachment(connection, turnId, attachment, draft.startedAt());
            }
            long revision = bumpSession(connection, draft.sessionId());
            return new StartResult(StartOutcome.STARTED,
                    findByRequestId(connection, draft.requestId()).orElseThrow(), revision);
        });
    }

    /** 同一事务复用原 turn/user/assistant 身份，旧请求成为幂等回执，绝不追加一轮提问。 */
    private StartResult restart(Connection connection, Draft draft, long newHistoryBytes, long newAttachmentBytes) throws SQLException {
        rejectSuperseded(connection, draft.retryOf());
        TurnRow original = findByRequestId(connection, draft.retryOf()).orElseThrow(() ->
                new com.drillnotebook.app.service.ChatTurnRejectException(409, "RETRY_TARGET_CHANGED", "原轮次已变化，请刷新后重试", Map.of()));
        if (original.sessionId() != draft.sessionId() || STATUS_RUNNING.equals(original.status())) {
            throw new com.drillnotebook.app.service.ChatTurnRejectException(409, "RETRY_TARGET_ACTIVE", "原轮次正在运行或不属于此会话", Map.of());
        }
        if (original.contentDeletedAt() != null || original.userMessageId() == null) {
            throw new com.drillnotebook.app.service.ChatTurnRejectException(410, "TURN_CONTENT_DELETED", "原提问已清理，无法重试", Map.of());
        }
        if (original.contextGeneration() != draft.contextGeneration()) {
            throw new com.drillnotebook.app.service.ChatTurnRejectException(409, "RETRY_CONTEXT_CHANGED", "会话上下文已重置，无法原地重试旧轮次", Map.of());
        }
        if (original.assistantMessageId() == null && scalar(connection,
                "SELECT COUNT(*) FROM ai_chat_message m JOIN ai_chat_turn t ON m.session_id = t.session_id"
                        + " WHERE t.id = ? AND m.id > t.user_message_id", original.id()) > 0) {
            throw new com.drillnotebook.app.service.ChatTurnRejectException(409, "RETRY_TARGET_INCOMPLETE",
                    "原轮次未保存回答位置，且已有后续对话，无法原地重试", Map.of());
        }
        long oldAttachmentBytes = scalar(connection, "SELECT COALESCE(SUM(byte_length), 0) FROM ai_chat_attachment WHERE turn_id = ?", original.id());
        var fit = ledger.reserveReplacement(connection, original.storedHistoryBytes(), newHistoryBytes, oldAttachmentBytes, newAttachmentBytes);
        if (!fit.accepted()) return StartResult.of(StartOutcome.valueOf(fit.name()));

        memory.purgeEvidence(connection, null, List.of(original.id()), messageIdsOf(connection, original.id()));
        SessionContextRepository.invalidateIn(connection, original.sessionId(), original.id());
        MemoryJobRepository.supersedeForSessionIn(connection, original.sessionId());
        try (var statement = connection.prepareStatement("""
                UPDATE ai_chat_session SET memory_scan_after_turn = MIN(memory_scan_after_turn, ?),
                  summary_scan_after_turn = MIN(summary_scan_after_turn, ?), summary_last_input_revision = NULL,
                  summary_last_attempt_through = 0 WHERE id = ?
                """)) {
            statement.setLong(1, original.id() - 1); statement.setLong(2, original.id() - 1);
            statement.setLong(3, original.sessionId()); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("UPDATE ai_chat_pending SET parent_request_id = ? WHERE parent_request_id = ?")) {
            statement.setString(1, draft.requestId()); statement.setString(2, original.requestId()); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO ai_chat_retry_receipt(request_id, turn_id, retry_of, replacement_request_id, replaced_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, original.requestId()); statement.setLong(2, original.id());
            statement.setString(3, original.retryOf()); statement.setString(4, draft.requestId());
            statement.setString(5, draft.startedAt()); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                UPDATE ai_chat_message SET content = ?, content_cipher = ?, content_meta = ? WHERE id = ?
                """)) {
            statement.setString(1, draft.userContent()); statement.setString(2, draft.userContentCipher());
            statement.setString(3, draft.userContentMeta()); statement.setLong(4, original.userMessageId());
            requireOneRow(statement.executeUpdate(), "原提问复用");
        }
        if (original.assistantMessageId() != null) {
            try (var statement = connection.prepareStatement("""
                    UPDATE ai_chat_message SET content = '', content_cipher = NULL, content_meta = NULL,
                      reasoning_cipher = NULL, reasoning_meta = NULL WHERE id = ?
                    """)) {
                statement.setLong(1, original.assistantMessageId()); requireOneRow(statement.executeUpdate(), "原回答占位复用");
            }
        }
        try (var statement = connection.prepareStatement("DELETE FROM ai_chat_attachment WHERE turn_id = ?")) {
            statement.setLong(1, original.id()); statement.executeUpdate();
        }
        for (AttachmentPayload attachment : draft.attachments()) insertAttachment(connection, original.id(), attachment, draft.startedAt());
        try (var statement = connection.prepareStatement("""
                UPDATE ai_chat_turn SET request_id = ?, retry_of = ?, status = 'RUNNING',
                  request_meta_cipher = ?, request_meta_crypto = ?, result_meta_cipher = NULL, result_meta_crypto = NULL,
                  runtime_generation = ?, memory_eligible = ?, memory_policy_revision = ?, source_revision = source_revision + 1,
                  stored_history_bytes = ?, reserved_history_bytes = ?, persistence_status = 'pending', finish_reason = NULL,
                  finished_at = NULL, model_label = NULL, reasoning_effort = NULL, context_tokens = NULL, context_window = NULL,
                  cache_read_tokens = NULL, output_tokens = NULL WHERE id = ?
                """)) {
            statement.setString(1, draft.requestId()); statement.setString(2, original.requestId());
            statement.setString(3, draft.requestMetaCipher()); statement.setString(4, draft.requestMetaCrypto());
            statement.setString(5, draft.runtimeGeneration()); statement.setInt(6, draft.memoryEligible() ? 1 : 0);
            statement.setObject(7, draft.memoryPolicyRevision()); statement.setLong(8, newHistoryBytes);
            statement.setLong(9, ChatStorageSizer.REPLY_RESERVATION_BYTES); statement.setLong(10, original.id());
            requireOneRow(statement.executeUpdate(), "原轮次重试");
        }
        if (ChatStorageSizer.turnStoredBytes(connection, original.id()) != newHistoryBytes) throw new SQLException("原地重试存量与实测不一致");
        return new StartResult(StartOutcome.STARTED, findByRequestId(connection, draft.requestId()).orElseThrow(), bumpSession(connection, draft.sessionId()));
    }

    public void assertCurrentRequest(String requestId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) { rejectSuperseded(connection, requestId); }
    }

    private static void rejectSuperseded(Connection connection, String requestId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT t.request_id, t.session_id FROM ai_chat_retry_receipt r JOIN ai_chat_turn t ON t.id = r.turn_id WHERE r.request_id = ?
                """)) {
            statement.setString(1, requestId);
            try (var row = statement.executeQuery()) {
                if (row.next()) throw new com.drillnotebook.app.service.ChatTurnRejectException(409, "TURN_SUPERSEDED",
                        "这一轮已在原处重试，请刷新查看最新回答", Map.of("replacementRequestId", row.getString(1), "sessionId", row.getLong(2)));
            }
        }
    }

    /** 写工具沿旧请求回执链查重；原地重试不会因替换 request_id 丢掉已经确认的写入。 */
    public static String retryParent(org.springframework.jdbc.core.JdbcTemplate jdbc, String requestId, long sessionId) {
        var rows = jdbc.query("""
                SELECT retry_of FROM ai_chat_turn WHERE request_id = ? AND session_id = ?
                UNION ALL SELECT r.retry_of FROM ai_chat_retry_receipt r JOIN ai_chat_turn t ON t.id = r.turn_id
                  WHERE r.request_id = ? AND t.session_id = ? LIMIT 1
                """, (row, index) -> row.getString(1), requestId, sessionId, requestId, sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 终态结算：一次事务写助手正文与 result_meta，把实际新增存量计入账本，并把该轮预留减到 0。
     *
     * <p>delta 直接由待写入的列算出（= newStored − oldStored），不依赖再次测量，因此不受写入顺序影响；
     * 结算后仍用 {@link ChatStorageSizer#turnStoredBytes} 复核，两者不一致就是账本与实测脱节，抛错回滚。
     * 重复完成由事务内的状态检查拦住（BEGIN IMMEDIATE 已独占写锁，不会有第三个并发写者抢先）。</p>
     */
    public TerminalOutcome finalizeTurn(String requestId, Terminal terminal) throws SQLException {
        return immediate.execute(connection -> {
            TurnRow turn = findByRequestId(connection, requestId)
                    .orElseThrow(() -> new SQLException("轮次不存在: " + requestId));
            if (!STATUS_RUNNING.equals(turn.status())) {
                return new TerminalOutcome(false, true, turn.status(), turn.storedHistoryBytes(), 0);
            }

            long assistantBytes = 0;
            long assistantMessageId = orZero(turn.assistantMessageId());
            if (hasContent(terminal.assistantContent()) || hasContent(terminal.assistantContentCipher())
                    || hasContent(terminal.reasoningCipher()) || assistantMessageId != 0) {
                if (assistantMessageId == 0) {
                    assistantMessageId = insertMessage(connection, turn.sessionId(), "assistant", turn.id(), "ASSISTANT",
                            terminal.assistantContent(), terminal.assistantContentCipher(), terminal.assistantContentMeta(),
                            terminal.reasoningCipher(), terminal.reasoningMeta());
                } else {
                    // 工具活动已创建空助手占位；定稿复用它，使历史消息身份保持稳定。
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE ai_chat_message SET content = ?, content_cipher = ?, content_meta = ?,
                                reasoning_cipher = ?, reasoning_meta = ? WHERE id = ?
                            """)) {
                        statement.setString(1, terminal.assistantContent() == null ? "" : terminal.assistantContent());
                        statement.setString(2, terminal.assistantContentCipher());
                        statement.setString(3, terminal.assistantContentMeta());
                        statement.setString(4, terminal.reasoningCipher());
                        statement.setString(5, terminal.reasoningMeta());
                        statement.setLong(6, assistantMessageId);
                        requireOneRow(statement.executeUpdate(), "工具回复定稿");
                    }
                }
                assistantBytes = messageBytes(terminal.assistantContent(), terminal.assistantContentCipher(),
                        terminal.assistantContentMeta(), terminal.reasoningCipher(), terminal.reasoningMeta());
            }
            long resultMetaBytes = ChatStorageSizer.storedBytes(terminal.resultMetaCipher(), terminal.resultMetaCrypto());
            long delta = Math.addExact(assistantBytes, resultMetaBytes) - resultMetaBytes(connection, turn.id());
            if (delta > turn.reservedHistoryBytes()) {
                throw new ReservationExceededException(turn.id(), delta, turn.reservedHistoryBytes());
            }
            long newStored = Math.addExact(turn.storedHistoryBytes(), delta);

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ai_chat_turn
                       SET status = ?, assistant_message_id = ?, result_meta_cipher = ?, result_meta_crypto = ?,
                           finish_reason = ?, persistence_status = ?, finished_at = ?, stored_history_bytes = ?,
                           reserved_history_bytes = 0, source_revision = source_revision + 1,
                           memory_eligible = CASE WHEN ? = 'COMPLETED' THEN memory_eligible ELSE 0 END
                     WHERE id = ? AND status = 'RUNNING'
                    """)) {
                statement.setString(1, terminal.status());
                // 没有回复正文时必须是 NULL：assistant_message_id 带外键，写 0 会当成"引用一条不存在的消息"
                statement.setObject(2, assistantMessageId == 0 ? null : assistantMessageId);
                statement.setString(3, terminal.resultMetaCipher());
                statement.setString(4, terminal.resultMetaCrypto());
                statement.setString(5, terminal.finishReason());
                statement.setString(6, terminal.persistenceStatus());
                statement.setString(7, terminal.finishedAt());
                statement.setLong(8, newStored);
                // 只有 COMPLETED 才保留可学习资格：中止/截断/失败都不进摘要与提取（§7.2 第 5 条、§7.4 第 3 条）
                statement.setString(9, terminal.status());
                statement.setLong(10, turn.id());
                requireOneRow(statement.executeUpdate(), "轮次终态 CAS");
            }

            long measured = ChatStorageSizer.turnStoredBytes(connection, turn.id());
            if (measured != newStored) {
                throw new SQLException("轮次 " + turn.id() + " 存量与实测不一致：算得 " + newStored + "，实测 " + measured);
            }
            ledger.settle(connection, delta, turn.reservedHistoryBytes());
            if (STATUS_COMPLETED.equals(terminal.status()) && turn.memoryEligible()) {
                // 只有"合格且已完成"的来源才置唤醒；v1、关掉学习的会话、失败轮次都不产生维护工作（§7）
                metadata.markMaintenanceWake(connection, turn.sessionId());
            }
            return new TerminalOutcome(true, false, terminal.status(), newStored,
                    bumpSession(connection, turn.sessionId()));
        });
    }

    /** 工具活动检查点：只使用本轮已有预留，元数据与容量在同一事务中更新。 */
    public boolean saveRunningResultMeta(String requestId, String cipher, String crypto) throws SQLException {
        return immediate.execute(connection -> {
            TurnRow turn = findByRequestId(connection, requestId).orElseThrow();
            if (!STATUS_RUNNING.equals(turn.status()) || turn.contentDeletedAt() != null) return false;
            long delta = ChatStorageSizer.storedBytes(cipher, crypto) - resultMetaBytes(connection, turn.id());
            if (delta > turn.reservedHistoryBytes()) {
                throw new ReservationExceededException(turn.id(), delta, turn.reservedHistoryBytes());
            }
            long assistantId = orZero(turn.assistantMessageId());
            if (assistantId == 0) {
                assistantId = insertMessage(connection, turn.sessionId(), "assistant", turn.id(), "ASSISTANT",
                        "", null, null, null, null);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ai_chat_turn SET result_meta_cipher = ?, result_meta_crypto = ?,
                        assistant_message_id = ?, stored_history_bytes = stored_history_bytes + ?,
                        reserved_history_bytes = reserved_history_bytes - ? WHERE id = ? AND status = 'RUNNING'
                    """)) {
                statement.setString(1, cipher);
                statement.setString(2, crypto);
                statement.setLong(3, assistantId);
                statement.setLong(4, delta);
                statement.setLong(5, delta);
                statement.setLong(6, turn.id());
                requireOneRow(statement.executeUpdate(), "工具活动检查点");
            }
            ledger.settle(connection, delta, delta);
            if (ChatStorageSizer.turnStoredBytes(connection, turn.id()) != turn.storedHistoryBytes() + delta) {
                throw new SQLException("工具活动存量与容量账本不一致");
            }
            // 活动元数据和空占位不参与模型上下文；此处不推进 history_revision，
            // 否则同轮的冻结计划会被自己的工具检查点误判为过期。定稿仍统一推进版本。
            return true;
        });
    }

    private static long resultMetaBytes(Connection connection, long turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT result_meta_cipher, result_meta_crypto FROM ai_chat_turn WHERE id = ?")) {
            statement.setLong(1, turnId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? ChatStorageSizer.storedBytes(result.getString(1), result.getString(2)) : 0;
            }
        }
    }

    /**
     * 用户明确清理该轮原文：一次事务删消息、附件与该轮证据，清空两组密文元数据并立墓碑，
     * 同事务把账本扣准。墓碑行本身保留（重复 request ID 据此返回 RESULT_EXPIRED）。
     *
     * <p>证据清理走 {@link MemoryRepository#purgeEvidence}，已保存记忆只断来源；同一事务还会清理待确认项、
     * 删掉引用被删原话的建议（§2.3「不能只依赖 SET NULL 把 quote 留下」）。</p>
     */
    public void clearContent(String requestId, String deletedAt) throws SQLException {
        immediate.execute(connection -> {
            TurnRow turn = findByRequestId(connection, requestId)
                    .orElseThrow(() -> new SQLException("轮次不存在: " + requestId));
            if (STATUS_RUNNING.equals(turn.status())) {
                throw new SQLException("轮次仍在进行，不能清理原文: " + requestId);
            }
            Released released = clearTurnContent(connection, turn, deletedAt);
            ledger.releaseStorage(connection, released.historyBytes(), released.attachmentBytes());
            bumpSession(connection, turn.sessionId());
            return null;
        });
    }

    /**
     * 一次事务清完预览确定的范围：先 CAS 认领会话版本，再逐轮立墓碑、legacy 展示行删行，
     * 账本只扣一次。
     *
     * <p>这里<b>不重新选范围</b>——只按预览给出的身份逐条核它还在不在原位：轮次不见了、跨了会话、
     * 还在 RUNNING、已立墓碑，或{@code stored_history_bytes}/{@code source_revision} 与预览那一份不同，
     * 都抛 {@link CleanupRefusalException} 让整笔回滚。字节按行现算但必须等于预览报的数，
     * 于是"预览说释放多少"与"账本实际扣多少"不可能分叉，也不会清出一个比预览更大的范围。</p>
     */
    public CleanupOutcome clearScope(List<ChatHistoryRepository.CleanupTurn> selected,
                                     List<ChatHistoryRepository.CleanupMessage> legacy, long sessionId,
                                     long expectedHistoryRevision, String deletedAt) throws SQLException {
        return immediate.execute(connection -> {
            // 会话级前提先立（contracts §3「在<b>同会话</b>短锁/事务内复核集合、版本和无 RUNNING」Q1 取会话级）：
            // 还在流式的那一轮可能马上要往这一页里写正文，让它和"清理"抢同一批行是不一致的。
            if (findRunning(connection, sessionId).isPresent() || SessionDispatchRepository.busy(connection,sessionId)) {
                throw new CleanupRefusalException("SESSION_RUNNING",
                        "该会话还有正在进行的轮次，先结束或取消再清理原文");
            }
            // 先占版本再删东西：CAS 输了什么都没动，回滚是免费的
            if (!metadata.claimHistoryRevision(connection, sessionId, expectedHistoryRevision)) {
                throw new CleanupRefusalException("REVISION_CONFLICT",
                        "会话在预览之后又推进过历史版本: " + sessionId);
            }
            long historyBytes = 0;
            long attachmentBytes = 0;
            int messageCount = 0;
            int evidence = 0;
            int proposals = 0;
            int autoFacts = 0;
            int keptFacts = 0;
            List<Long> cleanedTurnIds = new ArrayList<>();
            for (ChatHistoryRepository.CleanupTurn preview : selected) {
                TurnRow turn = findByRequestId(connection, preview.requestId())
                        .orElseThrow(() -> refusal("SOURCE_CHANGED", "预览里的轮次已经不见了: " + preview.requestId()));
                if (turn.sessionId() != sessionId) {
                    throw refusal("SOURCE_CHANGED", "预览范围跨会话，拒绝执行: " + preview.requestId());
                }
                if (STATUS_RUNNING.equals(turn.status())) {
                    throw refusal("SOURCE_CHANGED", "轮次仍在进行，不能清理原文: " + preview.requestId());
                }
                if (turn.contentDeletedAt() != null) {
                    throw refusal("SOURCE_CHANGED", "这一轮的原文早已被清理: " + preview.requestId());
                }
                if (turn.storedHistoryBytes() != preview.storedHistoryBytes()
                        || turn.sourceRevision() != preview.sourceRevision()) {
                    throw refusal("SOURCE_CHANGED", "这一轮在预览之后被改写，字节口径已变: " + preview.requestId());
                }
                Released released = clearTurnContent(connection, turn, deletedAt);
                historyBytes += released.historyBytes();
                attachmentBytes += released.attachmentBytes();
                messageCount += released.messages();
                evidence += released.purge().evidenceDeleted();
                proposals += released.purge().proposalsDeleted();
                autoFacts += released.purge().autoFactsRemoved();
                keptFacts += released.purge().protectedFactsKept();
                cleanedTurnIds.add(turn.id());
            }
            for (ChatHistoryRepository.CleanupMessage message : legacy) {
                long bytes = scalar(connection, "SELECT " + ChatStorageSizer.MESSAGE_BYTES_SQL
                        + " FROM ai_chat_message WHERE id = ? AND turn_id IS NULL", message.messageId());
                if (bytes != message.bytes()) {
                    throw refusal("SOURCE_CHANGED", "legacy 展示行在预览之后被改写: " + message.messageId());
                }
                MemoryRepository.PurgeResult purge = memory.purgeEvidence(connection, null, List.of(),
                        List.of(message.messageId()));
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ai_chat_message WHERE id = ? AND turn_id IS NULL")) {
                    statement.setLong(1, message.messageId());
                    if (statement.executeUpdate() != 1) {
                        throw refusal("SOURCE_CHANGED", "legacy 展示行已不在原位: " + message.messageId());
                    }
                }
                historyBytes += bytes;
                messageCount++;
                evidence += purge.evidenceDeleted();
                proposals += purge.proposalsDeleted();
                autoFacts += purge.autoFactsRemoved();
                keptFacts += purge.protectedFactsKept();
            }
            // 断开相关读与任务（§3 顺序：先断开，再删派生，最后才动账本与墓碑）。
            // 检查点只在它的已检查上界确实覆盖到本批某一轮时才作废：`selected` 是"最新在前"取的，
            // 所以本批最早的轮次要自己算 min，不能拿 get(0)（那是最新的那一轮）。
            long lowestCleanedTurnId = cleanedTurnIds.stream().mapToLong(Long::longValue).min().orElse(0L);
            int checkpoints = SessionContextRepository.invalidateIn(connection, sessionId, lowestCleanedTurnId);
            int jobs = MemoryJobRepository.supersedeReadingTurnsIn(connection, sessionId, cleanedTurnIds);
            if (historyBytes > 0 || attachmentBytes > 0) {
                ledger.releaseStorage(connection, historyBytes, attachmentBytes);
            }
            return new CleanupOutcome(selected.size(), legacy.size(), messageCount, historyBytes, attachmentBytes,
                    evidence, proposals, autoFacts, keptFacts, checkpoints, jobs,
                    metadata.historyRevision(connection, sessionId));
        });
    }

    /** 一轮清完之后的释放字节、删掉的消息行数，以及记忆侧的实际计数（与预览口径必须相等）。 */
    private record Released(long historyBytes, long attachmentBytes, int messages,
                            MemoryRepository.PurgeResult purge) {
    }

    /** 一次事务里的单轮清理：清证据 → 删行 → 立墓碑，计数一并带回。 */
    private Released clearTurnContent(Connection connection, TurnRow turn, String deletedAt) throws SQLException {
        try(var statement=connection.prepareStatement("UPDATE ai_chat_pending SET payload_cipher='',payload_meta='',payload_bytes=0,state='CONSUMED' WHERE parent_request_id=?")){
            statement.setString(1,turn.requestId());statement.executeUpdate();
        }
        long attachmentBytes = scalar(connection,
                "SELECT COALESCE(SUM(byte_length), 0) FROM ai_chat_attachment WHERE turn_id = ?", turn.id());
        long storedBytes = turn.storedHistoryBytes();
        List<Long> messageIds = messageIdsOf(connection, turn.id());
        // 证据必须在删正文之前清：行没了以后 §2.3 的孤儿自动事实就没人重算了
        MemoryRepository.PurgeResult purge = memory.purgeEvidence(connection, null, List.of(turn.id()), messageIds);
        int deletedMessages;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM ai_chat_message WHERE turn_id = ?")) {
            statement.setLong(1, turn.id());
            deletedMessages = statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM ai_chat_attachment WHERE turn_id = ?")) {
            statement.setLong(1, turn.id());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_chat_turn SET user_message_id = NULL, assistant_message_id = NULL,"
                        + " request_meta_cipher = NULL, request_meta_crypto = NULL,"
                        + " result_meta_cipher = NULL, result_meta_crypto = NULL, stored_history_bytes = 0,"
                        + " source_revision = source_revision + 1, content_deleted_at = ? WHERE id = ?")) {
            statement.setString(1, deletedAt);
            statement.setLong(2, turn.id());
            requireOneRow(statement.executeUpdate(), "轮次墓碑写入");
        }
        return new Released(storedBytes, attachmentBytes, deletedMessages, purge);
    }

    private static CleanupRefusalException refusal(String reason, String message) {
        return new CleanupRefusalException(reason, message);
    }

    private static List<Long> messageIdsOf(Connection connection, long turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ai_chat_message WHERE turn_id = ? ORDER BY id")) {
            statement.setLong(1, turnId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            return ids;
        }
    }

    /** 该会话当前 RUNNING 轮次（状态查询、孤儿恢复与"同会话只一轮"检查）。 */
    public Optional<TurnRow> findRunning(long sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     TURN_SELECT + " WHERE session_id = ? AND status = 'RUNNING' ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, sessionId);
            return first(statement);
        }
    }

    public Optional<TurnRow> findByRequestId(String requestId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TURN_SELECT + " WHERE request_id = ?")) {
            statement.setString(1, requestId);
            return first(statement);
        }
    }

    /**
     * 收尾事务失败后的兜底裁决（§7.2.1）：只按 request ID 把 RUNNING 置成终态，
     * 不写任何需要加密的正文，因此正文加密失败、预留超出、DB 慢都能落到这个分支。
     *
     * <p>已经不是 RUNNING（CAS 失手或先前已写）说明这一轮早被别人收尾：带回既有状态，
     * <b>不重复释放预留</b>（§2.7「释放磁盘预留只一次」）。</p>
     */
    public TerminalOutcome failFinalization(String requestId, String finishReason, String finishedAt)
            throws SQLException {
        return terminalWithoutContent(requestId, STATUS_FAILED, finishReason, "failed", finishedAt);
    }

    /** 进程重启或句柄消失后的孤儿裁决：RUNNING → INTERRUPTED，同样一次释放本轮预留。 */
    public TerminalOutcome markInterrupted(String requestId, String finishedAt) throws SQLException {
        return terminalWithoutContent(requestId, STATUS_INTERRUPTED, "interrupted", "failed", finishedAt);
    }

    private TerminalOutcome terminalWithoutContent(String requestId, String status, String finishReason,
                                                   String persistenceStatus, String finishedAt) throws SQLException {
        return immediate.execute(connection -> {
            TurnRow turn = findByRequestId(connection, requestId)
                    .orElseThrow(() -> new SQLException("轮次不存在: " + requestId));
            if (!STATUS_RUNNING.equals(turn.status())) {
                return new TerminalOutcome(false, true, turn.status(), turn.storedHistoryBytes(), 0);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ai_chat_turn
                       SET status = ?, finish_reason = ?, persistence_status = ?, memory_eligible = 0,
                           reserved_history_bytes = 0, source_revision = source_revision + 1, finished_at = ?
                     WHERE id = ? AND status = 'RUNNING'
                    """)) {
                statement.setString(1, status);
                statement.setString(2, finishReason);
                statement.setString(3, persistenceStatus);
                statement.setString(4, finishedAt);
                statement.setLong(5, turn.id());
                if (statement.executeUpdate() != 1) {
                    TurnRow raced = findByRequestId(connection, requestId).orElseThrow();
                    return new TerminalOutcome(false, true, raced.status(), raced.storedHistoryBytes(), 0);
                }
            }
            // 预留归零与账本扣减同事务，账本不会记着一笔永远不会结算的预留
            ledger.releaseReservation(connection, turn.reservedHistoryBytes());
            return new TerminalOutcome(true, false, status, turn.storedHistoryBytes(),
                    bumpSession(connection, turn.sessionId()));
        });
    }

    /** 仍在 RUNNING 的全部轮次：孤儿清扫按「本进程有没有活跃句柄」再过滤（不按年龄清，§7.2.2）。 */
    public List<TurnRow> findRunningTurns() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     TURN_SELECT + " WHERE status = 'RUNNING' ORDER BY id")) {
            List<TurnRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(row(result));
            }
            return rows;
        }
    }

    /** 按消息 ID 取落库列（不解密）：幂等重放据此取回已存回复。 */
    public Optional<MessageColumns> findMessage(long messageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, role, content, content_cipher, content_meta, reasoning_cipher, reasoning_meta"
                             + " FROM ai_chat_message WHERE id = ?")) {
            statement.setLong(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new MessageColumns(result.getLong("id"), result.getString("role"),
                        result.getString("content"), result.getString("content_cipher"),
                        result.getString("content_meta"), result.getString("reasoning_cipher"),
                        result.getString("reasoning_meta")));
            }
        }
    }

    /** 消息行的存储列，加解密由服务层完成（仓储层不持有加密材料）。 */
    public record MessageColumns(long id, String role, String content, String contentCipher, String contentMeta,
                                 String reasoningCipher, String reasoningMeta) {
    }

    /** 附件行的存储列：密文与封装材料原样交回，解密与 AAD 校验由服务层做。 */
    public record AttachmentRow(String id, String blockId, String kind, byte[] payloadCipher, String cryptoMeta,
                                long byteLength, String createdAt) {
    }

    /** 本轮附件，按落库顺序（block_id 是 a10 排在 a2 前的字典序，所以按 rowid 取回请求顺序）。 */
    public List<AttachmentRow> findAttachments(long turnId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, block_id, kind, payload_cipher, crypto_meta, byte_length, created_at"
                             + " FROM ai_chat_attachment WHERE turn_id = ? ORDER BY rowid")) {
            statement.setLong(1, turnId);
            List<AttachmentRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new AttachmentRow(result.getString("id"), result.getString("block_id"),
                            result.getString("kind"), result.getBytes("payload_cipher"),
                            result.getString("crypto_meta"), result.getLong("byte_length"),
                            result.getString("created_at")));
                }
            }
            return rows;
        }
    }

    /** 幂等判定要比对的两列请求元数据（不解密）。 */
    public Optional<MetaColumns> findRequestMeta(String requestId) throws SQLException {
        return findMeta("request_meta_cipher, request_meta_crypto", requestId);
    }

    /** 收尾落库的结果元数据（不解密）：上下文检查器与重放响应据此只读已保存的审计。 */
    public Optional<MetaColumns> findResultMeta(String requestId) throws SQLException {
        return findMeta("result_meta_cipher, result_meta_crypto", requestId);
    }

    private Optional<MetaColumns> findMeta(String columns, String requestId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + columns + " FROM ai_chat_turn WHERE request_id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new MetaColumns(result.getString(1), result.getString(2)));
            }
        }
    }

    public record MetaColumns(String cipher, String crypto) {
    }

    private static Optional<TurnRow> findByRequestId(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TURN_SELECT + " WHERE request_id = ?")) {
            statement.setString(1, requestId);
            return first(statement);
        }
    }

    private static Optional<TurnRow> findRunning(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                TURN_SELECT + " WHERE session_id = ? AND status = 'RUNNING' ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, sessionId);
            return first(statement);
        }
    }

    private static Optional<TurnRow> first(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) return Optional.empty();
            return Optional.of(row(result));
        }
    }

    private static TurnRow row(ResultSet result) throws SQLException {
        return new TurnRow(result.getLong("id"), result.getString("request_id"),
                result.getLong("session_id"), result.getString("status"),
                nullableLong(result, "user_message_id"), nullableLong(result, "assistant_message_id"),
                result.getLong("context_generation"), result.getString("source_scope_type"),
                result.getLong("source_scope_id"), result.getLong("source_revision"),
                result.getLong("stored_history_bytes"), result.getLong("reserved_history_bytes"),
                result.getString("content_deleted_at"), result.getString("persistence_status"),
                result.getString("finish_reason"), result.getString("started_at"),
                result.getString("finished_at"), result.getString("retry_of"),
                result.getInt("memory_eligible") == 1);
    }

    private long insertTurn(Connection connection, Draft draft, long storedBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_chat_turn(request_id, session_id, retry_of, status, request_meta_cipher,
                    request_meta_crypto, context_generation, source_scope_type, source_scope_id, source_timezone,
                    runtime_generation, memory_eligible, memory_policy_revision, source_revision,
                    stored_history_bytes, reserved_history_bytes, persistence_status, started_at)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'pending', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, draft.requestId());
            statement.setLong(2, draft.sessionId());
            statement.setString(3, draft.retryOf());
            statement.setString(4, draft.requestMetaCipher());
            statement.setString(5, draft.requestMetaCrypto());
            statement.setLong(6, draft.contextGeneration());
            statement.setString(7, draft.sourceScopeType());
            statement.setLong(8, draft.sourceScopeId());
            statement.setString(9, draft.sourceTimezone());
            statement.setString(10, draft.runtimeGeneration());
            statement.setInt(11, draft.memoryEligible() ? 1 : 0);
            statement.setObject(12, draft.memoryPolicyRevision());
            statement.setLong(13, storedBytes);
            statement.setLong(14, ChatStorageSizer.REPLY_RESERVATION_BYTES);
            statement.setString(15, draft.startedAt());
            requireOneRow(statement.executeUpdate(), "轮次写入");
            return generatedKey(statement);
        }
    }

    private long insertMessage(Connection connection, long sessionId, String role, long turnId, String turnRole,
                               String content, String contentCipher, String contentMeta,
                               String reasoningCipher, String reasoningMeta) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_chat_message(session_id, role, content, content_cipher, content_meta,
                    reasoning_cipher, reasoning_meta, turn_id, turn_role)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sessionId);
            statement.setString(2, role);
            statement.setString(3, content == null ? "" : content);
            statement.setString(4, contentCipher);
            statement.setString(5, contentMeta);
            statement.setString(6, reasoningCipher);
            statement.setString(7, reasoningMeta);
            statement.setLong(8, turnId);
            statement.setString(9, turnRole);
            requireOneRow(statement.executeUpdate(), "消息写入");
            return generatedKey(statement);
        }
    }

    private void insertAttachment(Connection connection, long turnId, AttachmentPayload attachment, String createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_chat_attachment(id, turn_id, block_id, kind, payload_cipher, crypto_meta,
                    byte_length, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, attachment.id());
            statement.setLong(2, turnId);
            statement.setString(3, attachment.blockId());
            statement.setString(4, attachment.kind());
            statement.setBytes(5, attachment.payloadCipher());
            statement.setString(6, attachment.cryptoMeta());
            statement.setLong(7, attachment.storedBytes());
            statement.setString(8, createdAt);
            requireOneRow(statement.executeUpdate(), "附件写入");
        }
    }

    /** 消息新增、终态保存、源编辑/删除都推进 history_revision；阅读、usage、摘要改写不推进（§2.1）。 */
    private long bumpSession(Connection connection, long sessionId) throws SQLException {
        return metadata.bumpHistoryRevision(connection, sessionId);
    }

    private static long messageBytes(String content, String contentCipher, String contentMeta,
                                     String reasoningCipher, String reasoningMeta) {
        return ChatStorageSizer.storedBytes(content, contentCipher, contentMeta, reasoningCipher, reasoningMeta);
    }

    private static long scalar(Connection connection, String sql, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("统计没有返回行: " + sql);
                return result.getLong(1);
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("没有取回自增主键");
            return keys.getLong(1);
        }
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static boolean hasContent(String value) {
        return value != null && !value.isEmpty();
    }

    private static void requireOneRow(int updated, String what) throws SQLException {
        if (updated != 1) throw new SQLException(what + "没有命中单行");
    }
}
