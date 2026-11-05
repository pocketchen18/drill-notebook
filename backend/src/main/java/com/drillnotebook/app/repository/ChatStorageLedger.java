package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 历史容量账本（contracts §2.7）：{@code ai_chat_storage} 的唯一写入口。
 *
 * <p>账本记逻辑落库字节：全局 {@code history_bytes} = 全部消息五列 + 全部轮次四列元数据，
 * 每个轮次自己的预留另记在 {@code ai_chat_turn.reserved_history_bytes}。存量可以大于默认额度
 * （升级/恢复时照常保留、可查看、可备份），因此 DDL 不给 {@code history_bytes} 加上限约束，
 * 上限只在新增事务里检查增量——绝不能为了满足 CHECK 而删用户数据。</p>
 *
 * <p>所有写方法都要求传入已在 {@link ImmediateTransaction} 里的连接：额度判定与消息写入必须同事务，
 * 否则账本与实测脱节，并发下还会超卖。</p>
 */
@Repository
public class ChatStorageLedger {
    /** 额度判定结果（对应 REST 的 409 码）。 */
    public enum Fit {
        ACCEPTED, HISTORY_STORAGE_FULL, ATTACHMENT_STORAGE_FULL;

        public boolean accepted() {
            return this == ACCEPTED;
        }

        public String code() {
            return name();
        }
    }

    /**
     * 账本一行。所有字节数都是 64 位整数；{@code usedHistory} 含未结算的回复预留。
     */
    public record Snapshot(long revision, long historyBytes, long reservedHistoryBytes, long attachmentBytes,
                           long historyLimitBytes, long attachmentLimitBytes) {
        public long usedHistory() {
            return Math.addExact(historyBytes, reservedHistoryBytes);
        }

        public boolean historyWarning() {
            return reachedWarning(historyBytes, reservedHistoryBytes, historyLimitBytes);
        }

        public boolean attachmentWarning() {
            return reachedWarning(attachmentBytes, 0, attachmentLimitBytes);
        }

        private static boolean reachedWarning(long stored, long reserved, long limit) {
            if (limit <= 0) {
                return true;
            }
            // 用减法而不是 used*100 >= limit*80：额度是用户可配的 64 位字节数，乘法会溢出
            long threshold = limit - limit / 5;
            return Math.addExact(stored, reserved) >= threshold;
        }
    }

    /** 额度调整结果：拒绝降到已用+预留之下，也拒绝并发版本不符。 */
    public enum LimitResult { APPLIED, REVISION_CONFLICT, BELOW_USED, INVALID_VALUE }

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;
    private final AtomicBoolean rebuiltInProcess = new AtomicBoolean(false);

    public ChatStorageLedger(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    /**
     * 只读快照（供 GET /history/storage 与预警展示），不改动账本。
     *
     * <p>这里刻意<b>不</b>触发重建：读一次就把计数拉平会抹掉 {@link #reconcile()} 要暴露的漂移，
     * 而漂移必须让门禁停下来报告。重建只由写入口（{@code begin} 与旧聊天写路径）在进入进程后
     * 第一次使用时执行一次。</p>
     */
    public Snapshot read() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return read(connection);
        }
    }

    public Snapshot read(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision, history_bytes, reserved_history_bytes, attachment_bytes,"
                        + " history_limit_bytes, attachment_limit_bytes FROM ai_chat_storage WHERE id = 1");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("容量账本行缺失（ai_chat_storage.id=1）");
            }
            return new Snapshot(result.getLong("revision"), result.getLong("history_bytes"),
                    result.getLong("reserved_history_bytes"), result.getLong("attachment_bytes"),
                    result.getLong("history_limit_bytes"), result.getLong("attachment_limit_bytes"));
        }
    }

    /**
     * 进程首次使用账本前按实测重建计数（§2.7「迁移和恢复在开放 AI 写入口前重建这些计数」）。
     * 只读密文长度、不解密、不外发；本进程只跑一次，之后 begin 事务直接用账本值。
     */
    public synchronized void ensureRebuiltInProcess() throws SQLException {
        if (rebuiltInProcess.get()) {
            return;
        }
        rebuild();
        rebuiltInProcess.set(true);
    }

    /**
     * 按实测重算全局计数并推进 revision。用于迁移/恢复后的重建与人工对账修复。
     *
     * <p>预留不是清零，而是按仍在 RUNNING 的轮次实测求和：孤儿恢复（P3）会在之后逐轮释放，
     * 若这里直接抹成 0，那笔释放就会去减一个已经为零的全局预留。</p>
     */
    public Snapshot rebuild() throws SQLException {
        return immediate.execute(connection -> {
            long history = ChatStorageSizer.globalHistoryBytes(connection);
            long attachments = ChatStorageSizer.globalAttachmentBytes(connection);
            long liveReservation;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(reserved_history_bytes), 0) FROM ai_chat_turn WHERE status = 'RUNNING'");
                 ResultSet result = statement.executeQuery()) {
                result.next();
                liveReservation = result.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_chat_storage SET history_bytes = ?, attachment_bytes = ?,"
                            + " reserved_history_bytes = ?, revision = revision + 1 WHERE id = 1")) {
                statement.setLong(1, history);
                statement.setLong(2, attachments);
                statement.setLong(3, liveReservation);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("容量账本重建未命中单行");
                }
            }
            return read(connection);
        });
    }

    /**
     * 只读对账：实测与账本是否一致。不覆盖差异——发现不一致时调用方必须停住 AI 写入口并报告，
     * 不能"顺手修正"后继续外发（§2.7）。
     */
    public ReconcileReport reconcile() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Snapshot snapshot = read(connection);
            return new ReconcileReport(snapshot.historyBytes(), ChatStorageSizer.globalHistoryBytes(connection),
                    snapshot.attachmentBytes(), ChatStorageSizer.globalAttachmentBytes(connection));
        }
    }

    public record ReconcileReport(long ledgerHistoryBytes, long measuredHistoryBytes,
                                  long ledgerAttachmentBytes, long measuredAttachmentBytes) {
        public boolean consistent() {
            return ledgerHistoryBytes == measuredHistoryBytes && ledgerAttachmentBytes == measuredAttachmentBytes;
        }
    }

    /**
     * 旧（v1）聊天写路径的即时记账：没有轮次预留，写进多少存量就记多少。
     * 只允许在与消息写入同一事务内调用，否则账本与实测脱节。
     */
    public void addStoredHistory(Connection connection, long historyBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage SET history_bytes = history_bytes + ?, revision = revision + 1
                 WHERE id = 1 AND history_bytes + ? >= 0
                """)) {
            statement.setLong(1, historyBytes);
            statement.setLong(2, historyBytes);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("容量记账失败：新增字节与账本不合法");
            }
        }
    }

    /**
     * 开始轮次：在同事务里预留「本轮正文 + 2 MiB 回复预留 + 本轮附件」。
     * 额度不足返回对应码，账本一行都不动（不留 RUNNING、不产生外发）。
     *
     * <p>判定直接放在 Java 侧用 64 位整数算（溢出即失败），事务已由 BEGIN IMMEDIATE 独占写锁，
     * 所以读到的额度就是判定时的额度；UPDATE 仍带 {@code revision = ?} 作为「没有别人写过」的断言，
     * 不命中就抛错回滚，绝不静默放行。</p>
     */
    public Fit reserve(Connection connection, long newHistoryBytes, long newAttachmentBytes) throws SQLException {
        Snapshot before = read(connection);
        long reply = ChatStorageSizer.REPLY_RESERVATION_BYTES;
        long neededHistory = Math.addExact(Math.addExact(before.usedHistory(), newHistoryBytes), reply);
        if (neededHistory > before.historyLimitBytes()) {
            return Fit.HISTORY_STORAGE_FULL;
        }
        long neededAttachment = Math.addExact(before.attachmentBytes(), newAttachmentBytes);
        if (neededAttachment > before.attachmentLimitBytes()) {
            return Fit.ATTACHMENT_STORAGE_FULL;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage
                   SET history_bytes = history_bytes + ?,
                       reserved_history_bytes = reserved_history_bytes + ?,
                       attachment_bytes = attachment_bytes + ?,
                       revision = revision + 1
                 WHERE id = 1 AND revision = ?
                   AND history_bytes + reserved_history_bytes + ? + ? <= history_limit_bytes
                   AND attachment_bytes + ? <= attachment_limit_bytes
                """)) {
            statement.setLong(1, newHistoryBytes);
            statement.setLong(2, reply);
            statement.setLong(3, newAttachmentBytes);
            statement.setLong(4, before.revision());
            statement.setLong(5, newHistoryBytes);
            statement.setLong(6, reply);
            statement.setLong(7, newAttachmentBytes);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("容量预留断言失败：写锁内账本行被改动");
            }
        }
        return Fit.ACCEPTED;
    }

    /**
     * 终态结算：把本轮实际新增的存量计入，并一次性释放本轮的回复预留。
     * 调用方必须保证 {@code deltaBytes} 是 newStored − oldStored 且不超过该轮预留，
     * 这里只做下限保护，超发直接在轮次侧拒绝（不留假 COMPLETED）。
     */
    public void settle(Connection connection, long deltaHistoryBytes, long releasedReservationBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage
                   SET history_bytes = history_bytes + ?,
                       reserved_history_bytes = reserved_history_bytes - ?,
                       revision = revision + 1
                 WHERE id = 1 AND reserved_history_bytes >= ?
                   AND history_bytes + ? >= 0
                """)) {
            statement.setLong(1, deltaHistoryBytes);
            statement.setLong(2, releasedReservationBytes);
            statement.setLong(3, releasedReservationBytes);
            statement.setLong(4, deltaHistoryBytes);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("容量结算失败：预留或存量与账本不一致");
            }
        }
    }

    /** 只释放预留、不增加存量（取消/孤儿恢复：本轮一个字都没落库）。 */
    public void releaseReservation(Connection connection, long releasedReservationBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage
                   SET reserved_history_bytes = reserved_history_bytes - ?, revision = revision + 1
                 WHERE id = 1 AND reserved_history_bytes >= ?
                """)) {
            statement.setLong(1, releasedReservationBytes);
            statement.setLong(2, releasedReservationBytes);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("预留释放失败：全局预留小于该轮预留");
            }
        }
    }

    /** 用户明确清理内容时同事务扣掉准确字节（正文/元数据/附件各按实测）。 */
    public void releaseStorage(Connection connection, long historyBytes, long attachmentBytes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_chat_storage
                   SET history_bytes = history_bytes - ?, attachment_bytes = attachment_bytes - ?,
                       revision = revision + 1
                 WHERE id = 1 AND history_bytes >= ? AND attachment_bytes >= ?
                """)) {
            statement.setLong(1, historyBytes);
            statement.setLong(2, attachmentBytes);
            statement.setLong(3, historyBytes);
            statement.setLong(4, attachmentBytes);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("容量释放失败：账本少于待删除字节");
            }
        }
    }

    /**
     * 显式调整额度（PUT /history/storage）：必须带 expectedRevision，且不得低于 used+reserved；
     * 只改配置，不清任何记录。
     */
    public LimitResult updateLimits(long expectedRevision, long historyLimitBytes, long attachmentLimitBytes)
            throws SQLException {
        if (historyLimitBytes <= 0 || attachmentLimitBytes <= 0) {
            return LimitResult.INVALID_VALUE;
        }
        return immediate.execute(connection -> {
            Snapshot before = read(connection);
            if (before.revision() != expectedRevision) {
                return LimitResult.REVISION_CONFLICT;
            }
            if (historyLimitBytes < before.usedHistory() || attachmentLimitBytes < before.attachmentBytes()) {
                return LimitResult.BELOW_USED;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ai_chat_storage
                       SET history_limit_bytes = ?, attachment_limit_bytes = ?, revision = revision + 1
                     WHERE id = 1 AND revision = ?
                    """)) {
                statement.setLong(1, historyLimitBytes);
                statement.setLong(2, attachmentLimitBytes);
                statement.setLong(3, expectedRevision);
                return statement.executeUpdate() == 1 ? LimitResult.APPLIED : LimitResult.REVISION_CONFLICT;
            }
        });
    }
}
