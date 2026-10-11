package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

    /** 开始轮次输入：正文与请求元数据必须已由调用方完成有界校验与加密。 */
    public record Draft(String requestId, long sessionId, long contextGeneration, String sourceScopeType,
                        long sourceScopeId, String sourceTimezone, String runtimeGeneration, boolean memoryEligible,
                        Long memoryPolicyRevision, String requestMetaCipher, String requestMetaCrypto,
                        String userContent, String userContentCipher, String userContentMeta,
                        List<AttachmentPayload> attachments, String startedAt) {
        public Draft {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
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
                          String finishedAt) {
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
            + " started_at, finished_at FROM ai_chat_turn";

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
            Optional<TurnRow> existing = findByRequestId(connection, draft.requestId());
            if (existing.isPresent()) {
                return new StartResult(StartOutcome.DUPLICATE_REQUEST, existing.get(),
                        ChatSessionMetadataRepository.read(connection, draft.sessionId())
                                .orElseThrow().historyRevision());
            }
            if (findRunning(connection, draft.sessionId()).isPresent()) {
                return StartResult.of(StartOutcome.RUNNING_EXISTS);
            }

            long userBytes = messageBytes(draft.userContent(), draft.userContentCipher(), draft.userContentMeta(),
                    null, null);
            long turnMetaBytes = ChatStorageSizer.storedBytes(draft.requestMetaCipher(), draft.requestMetaCrypto());
            long newHistoryBytes = Math.addExact(userBytes, turnMetaBytes);
            long attachmentBytes = draft.attachments().stream().mapToLong(AttachmentPayload::storedBytes).sum();

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
            long assistantMessageId = 0;
            if (hasContent(terminal.assistantContent()) || hasContent(terminal.assistantContentCipher())
                    || hasContent(terminal.reasoningCipher())) {
                assistantMessageId = insertMessage(connection, turn.sessionId(), "assistant", turn.id(), "ASSISTANT",
                        terminal.assistantContent(), terminal.assistantContentCipher(), terminal.assistantContentMeta(),
                        terminal.reasoningCipher(), terminal.reasoningMeta());
                assistantBytes = messageBytes(terminal.assistantContent(), terminal.assistantContentCipher(),
                        terminal.assistantContentMeta(), terminal.reasoningCipher(), terminal.reasoningMeta());
            }
            long resultMetaBytes = ChatStorageSizer.storedBytes(terminal.resultMetaCipher(), terminal.resultMetaCrypto());
            long delta = Math.addExact(assistantBytes, resultMetaBytes);
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
            return new TerminalOutcome(true, false, terminal.status(), newStored,
                    bumpSession(connection, turn.sessionId()));
        });
    }

    /**
     * 用户明确清理该轮原文：一次事务删消息、附件与该轮证据，清空两组密文元数据并立墓碑，
     * 同事务把账本扣准。墓碑行本身保留（重复 request ID 据此返回 RESULT_EXPIRED）。
     *
     * <p>证据清理走 {@link MemoryRepository#purgeEvidence}，因此同一事务里还会重算孤儿自动事实、
     * 删掉引用被删原话的建议（§2.3「不能只依赖 SET NULL 把 quote 留下」）。</p>
     */
    public void clearContent(String requestId, String deletedAt) throws SQLException {
        immediate.execute(connection -> {
            TurnRow turn = findByRequestId(connection, requestId)
                    .orElseThrow(() -> new SQLException("轮次不存在: " + requestId));
            if (STATUS_RUNNING.equals(turn.status())) {
                throw new SQLException("轮次仍在进行，不能清理原文: " + requestId);
            }
            long attachmentBytes = scalar(connection,
                    "SELECT COALESCE(SUM(byte_length), 0) FROM ai_chat_attachment WHERE turn_id = ?", turn.id());
            long storedBytes = turn.storedHistoryBytes();

            List<Long> messageIds = new ArrayList<>();
            if (orZero(turn.userMessageId()) > 0) {
                messageIds.add(turn.userMessageId());
            }
            if (orZero(turn.assistantMessageId()) > 0) {
                messageIds.add(turn.assistantMessageId());
            }
            memory.purgeEvidence(connection, null, List.of(turn.id()), messageIds);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM ai_chat_message WHERE turn_id = ?")) {
                statement.setLong(1, turn.id());
                statement.executeUpdate();
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
            ledger.releaseStorage(connection, storedBytes, attachmentBytes);
            bumpSession(connection, turn.sessionId());
            return null;
        });
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

    /** 幂等判定要比对的两列请求元数据（不解密）。 */
    public Optional<MetaColumns> findRequestMeta(String requestId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT request_meta_cipher, request_meta_crypto FROM ai_chat_turn WHERE request_id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new MetaColumns(result.getString("request_meta_cipher"),
                        result.getString("request_meta_crypto")));
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
                result.getString("finished_at"));
    }

    private long insertTurn(Connection connection, Draft draft, long storedBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_chat_turn(request_id, session_id, status, request_meta_cipher, request_meta_crypto,
                    context_generation, source_scope_type, source_scope_id, source_timezone, runtime_generation,
                    memory_eligible, memory_policy_revision, source_revision, stored_history_bytes,
                    reserved_history_bytes, persistence_status, started_at)
                VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'pending', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, draft.requestId());
            statement.setLong(2, draft.sessionId());
            statement.setString(3, draft.requestMetaCipher());
            statement.setString(4, draft.requestMetaCrypto());
            statement.setLong(5, draft.contextGeneration());
            statement.setString(6, draft.sourceScopeType());
            statement.setLong(7, draft.sourceScopeId());
            statement.setString(8, draft.sourceTimezone());
            statement.setString(9, draft.runtimeGeneration());
            statement.setInt(10, draft.memoryEligible() ? 1 : 0);
            statement.setObject(11, draft.memoryPolicyRevision());
            statement.setLong(12, storedBytes);
            statement.setLong(13, ChatStorageSizer.REPLY_RESERVATION_BYTES);
            statement.setString(14, draft.startedAt());
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
