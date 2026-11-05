package com.drillnotebook.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用待恢复的备份包：在 DataSource 创建前把 data/study.db.pending-restore
 * 原子换入 data/study.db（删除 WAL/SHM 防止旧日志污染新库），并替换附件目录。
 * 由 DatabaseConfig 在创建数据源前调用；无标记时为廉价 no-op（一次 exists 检查）。
 */
public final class PendingRestoreApplier {
    private static final Logger log = LoggerFactory.getLogger(PendingRestoreApplier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PendingRestoreApplier() {
    }

    public static boolean applyIfPending(Path dataDir) {
        Path pendingDb = dataDir.resolve("study.db.pending-restore");
        Path pendingAttachments = dataDir.resolve("attachments.pending-restore");
        Path marker = dataDir.resolve("restore-manifest.json");
        if (!Files.exists(pendingDb) && !Files.exists(pendingAttachments) && !Files.exists(marker)) {
            return false;
        }

        ObjectNode state = readMarker(marker);
        if (state != null) {
            int version = state.path("schemaVersion").asInt(0);
            int current = DatabaseInitializer.currentSchemaVersion();
            if (version > current) {
                log.error("待恢复备份结构版本 {} 高于当前支持的 {}，保留 pending 文件并继续使用现有数据库",
                        version, current);
                return false;
            }
        }

        boolean databaseReplaced = false;
        try {
            if (state == null && (Files.exists(pendingDb) || Files.exists(pendingAttachments))) {
                throw new IOException("恢复标记缺失，拒绝替换数据库");
            }
            String stage = state == null ? "" : state.path("stage").asText("");
            if (Files.exists(pendingDb)) {
                Files.deleteIfExists(dataDir.resolve("study.db-wal"));
                Files.deleteIfExists(dataDir.resolve("study.db-shm"));
                Files.move(pendingDb, dataDir.resolve("study.db"), StandardCopyOption.REPLACE_EXISTING);
                databaseReplaced = true;
                stage = "DB_REPLACED";
                writeStage(marker, state, stage);
            }

            if (Files.exists(pendingAttachments) && state != null) {
                replaceAttachments(dataDir, pendingAttachments);
                // 附件替换完成仍不能提前删标记：安全事务失败时下次启动还要能续跑。
                stage = "DB_REPLACED";
                writeStage(marker, state, stage);
            }

            // 换库后必须完成安全事务才允许应用继续启动。marker 存在但还未到
            // SAFETY_APPLIED 时也要补跑：可能在上次 Files.move 成功、写 DB_REPLACED
            // 之前崩溃，此时 pending DB 已消失，但 marker 仍停在 PREPARED。
            if (!"SAFETY_APPLIED".equals(stage) && (databaseReplaced || state != null)) {
                try {
                    applyRestoreSafety(dataDir.resolve("study.db"));
                } catch (IOException error) {
                    throw new IllegalStateException("恢复安全事务失败，已阻止应用启动；保留标记以便下次重试", error);
                }
                writeStage(marker, state, "SAFETY_APPLIED");
                stage = "SAFETY_APPLIED";
            }

            if (Files.exists(marker)) {
                String from = state == null ? "未知来源" : state.path("from").asText("未知来源");
                Files.deleteIfExists(marker);
                boolean completed = databaseReplaced
                        || "DB_REPLACED".equals(stage)
                        || "SAFETY_APPLIED".equals(stage);
                if (completed) {
                    log.info("已应用待恢复备份（来源: {}），数据库与附件已替换", from);
                }
                return completed;
            }

            if (databaseReplaced) {
                log.info("已应用待恢复备份，数据库已替换");
                return true;
            }
            return false;
        } catch (IOException error) {
            if (databaseReplaced) {
                throw new IllegalStateException("恢复换库后处理失败，已阻止应用启动；pending 文件保留: " + pendingDb, error);
            }
            // 换库前失败：现有数据库未被覆盖，保留 pending 文件便于重试。
            log.error("应用待恢复备份失败，将以现有数据库启动；pending 文件保留: {}", pendingDb, error);
            return false;
        }
    }

    private static void applyRestoreSafety(Path database) throws IOException {
        String now = Instant.now().toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.setAutoCommit(false);
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA busy_timeout = 5000");
            }
            try {
                clearMemoryConsent(connection, now);
                if (tableExists(connection, "ai_memory_vault")
                        && columnExists(connection, "ai_memory_vault", "epoch")
                        && columnExists(connection, "ai_memory_vault", "revision")) {
                    update(connection, "UPDATE ai_memory_vault SET epoch = epoch + 1, revision = revision + 1");
                }
                advanceSessionGenerations(connection);
                interruptRunningTurns(connection, now);
                supersedeActiveJobs(connection);
                settleSentUsage(connection, now);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("恢复安全事务失败", error);
        }
    }

    private static void clearMemoryConsent(Connection connection, String now) throws SQLException {
        if (!tableExists(connection, "ai_memory_settings")) return;
        List<String> sets = new ArrayList<>();
        if (columnExists(connection, "ai_memory_settings", "enabled")) sets.add("enabled = 0");
        if (columnExists(connection, "ai_memory_settings", "auto_extract")) sets.add("auto_extract = 0");
        if (columnExists(connection, "ai_memory_settings", "use_consent_fingerprint")) {
            sets.add("use_consent_fingerprint = NULL");
        }
        if (columnExists(connection, "ai_memory_settings", "maintenance_consent_fingerprint")) {
            sets.add("maintenance_consent_fingerprint = NULL");
        }
        if (columnExists(connection, "ai_memory_settings", "consented_at")) sets.add("consented_at = NULL");
        if (sets.isEmpty()) return;
        if (columnExists(connection, "ai_memory_settings", "revision")) sets.add("revision = revision + 1");
        if (columnExists(connection, "ai_memory_settings", "updated_at")) sets.add("updated_at = ?");
        update(connection, "UPDATE ai_memory_settings SET " + String.join(", ", sets), now);
    }

    private static void advanceSessionGenerations(Connection connection) throws SQLException {
        if (!tableExists(connection, "ai_chat_session")) return;
        List<String> sets = new ArrayList<>();
        if (columnExists(connection, "ai_chat_session", "context_generation")) {
            sets.add("context_generation = context_generation + 1");
        }
        if (tableExists(connection, "ai_chat_turn")) {
            if (columnExists(connection, "ai_chat_session", "memory_scan_after_turn")) {
                sets.add("memory_scan_after_turn = COALESCE((SELECT MAX(id) FROM ai_chat_turn t"
                        + " WHERE t.session_id = ai_chat_session.id), 0)");
            }
            if (columnExists(connection, "ai_chat_session", "summary_scan_after_turn")) {
                sets.add("summary_scan_after_turn = COALESCE((SELECT MAX(id) FROM ai_chat_turn t"
                        + " WHERE t.session_id = ai_chat_session.id), 0)");
            }
        }
        if (!sets.isEmpty()) {
            update(connection, "UPDATE ai_chat_session SET " + String.join(", ", sets));
        }
    }

    private static void interruptRunningTurns(Connection connection, String now) throws SQLException {
        if (!tableExists(connection, "ai_chat_turn") || !columnExists(connection, "ai_chat_turn", "status")) {
            return;
        }
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        sets.add("status = 'INTERRUPTED'");
        if (columnExists(connection, "ai_chat_turn", "finished_at")) {
            sets.add("finished_at = ?");
            args.add(now);
        }
        if (columnExists(connection, "ai_chat_turn", "persistence_status")) {
            sets.add("persistence_status = 'failed'");
        }
        if (columnExists(connection, "ai_chat_turn", "finish_reason")) {
            sets.add("finish_reason = 'RESTORED'");
        }
        update(connection, "UPDATE ai_chat_turn SET " + String.join(", ", sets)
                + " WHERE status = 'RUNNING'", args.toArray());
    }

    private static void supersedeActiveJobs(Connection connection) throws SQLException {
        if (!tableExists(connection, "ai_memory_job") || !columnExists(connection, "ai_memory_job", "status")) {
            return;
        }
        List<String> sets = new ArrayList<>();
        sets.add("status = 'SUPERSEDED'");
        if (columnExists(connection, "ai_memory_job", "lease_token")) sets.add("lease_token = NULL");
        if (columnExists(connection, "ai_memory_job", "lease_until")) sets.add("lease_until = NULL");
        update(connection, "UPDATE ai_memory_job SET " + String.join(", ", sets)
                + " WHERE status IN ('QUEUED', 'CLAIMED', 'RETRY', 'WAITING_UNLOCK',"
                + " 'WAITING_CONSENT', 'PAUSED_BUDGET')");
    }

    private static void settleSentUsage(Connection connection, String now) throws SQLException {
        if (!tableExists(connection, "ai_usage_attempt")
                || !columnExists(connection, "ai_usage_attempt", "state")
                || !columnExists(connection, "ai_usage_attempt", "settled_at")) {
            return;
        }
        update(connection, "UPDATE ai_usage_attempt SET state = 'UNKNOWN', settled_at = ?"
                + " WHERE state = 'SENT'", now);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }

    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static ObjectNode readMarker(Path marker) {
        if (!Files.exists(marker)) return null;
        try {
            JsonNode node = MAPPER.readTree(marker.toFile());
            return node instanceof ObjectNode object ? object : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void writeStage(Path marker, ObjectNode state, String stage) throws IOException {
        if (state == null) return;
        state.put("stage", stage);
        MAPPER.writeValue(marker.toFile(), state);
    }

    private static void replaceAttachments(Path dataDir, Path pendingAttachments) throws IOException {
        Path attachments = dataDir.resolve("attachments");
        if (Files.exists(attachments)) {
            Path retired = dataDir.resolve("attachments.restored-away");
            deleteRecursively(retired);
            Files.move(attachments, retired);
        }
        Files.move(pendingAttachments, attachments);
        deleteRecursively(dataDir.resolve("attachments.restored-away"));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            });
        }
    }
}
