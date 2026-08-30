package com.drillnotebook.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用待恢复的备份包：在 DataSource 创建前把 data/study.db.pending-restore
 * 原子换入 data/study.db（删除 WAL/SHM 防止旧日志污染新库），并替换附件目录。
 * 由 DatabaseConfig 在创建数据源前调用；无标记时为廉价 no-op（一次 exists 检查）。
 */
public final class PendingRestoreApplier {
    private static final Logger log = LoggerFactory.getLogger(PendingRestoreApplier.class);

    private PendingRestoreApplier() {
    }

    public static boolean applyIfPending(Path dataDir) {
        Path pendingDb = dataDir.resolve("study.db.pending-restore");
        if (!Files.exists(pendingDb)) return false;
        try {
            Path database = dataDir.resolve("study.db");
            Files.deleteIfExists(dataDir.resolve("study.db-wal"));
            Files.deleteIfExists(dataDir.resolve("study.db-shm"));
            Files.move(pendingDb, database, StandardCopyOption.REPLACE_EXISTING);

            Path pendingAttachments = dataDir.resolve("attachments.pending-restore");
            if (Files.exists(pendingAttachments)) {
                Path attachments = dataDir.resolve("attachments");
                if (Files.exists(attachments)) {
                    Path retired = dataDir.resolve("attachments.restored-away");
                    deleteRecursively(retired);
                    Files.move(attachments, retired);
                }
                Files.move(pendingAttachments, attachments);
                deleteRecursively(dataDir.resolve("attachments.restored-away"));
            }

            Path marker = dataDir.resolve("restore-manifest.json");
            String from = "未知来源";
            if (Files.exists(marker)) {
                try {
                    JsonNode node = new ObjectMapper().readTree(marker.toFile());
                    from = node.path("from").asText(from);
                } catch (IOException ignored) {
                    // 标记损坏不影响换库本身
                }
                Files.deleteIfExists(marker);
            }
            log.info("已应用待恢复备份（来源: {}），数据库与附件已替换", from);
            return true;
        } catch (IOException error) {
            // 恢复失败时保留 pending 文件，应用以旧库继续启动，便于重试
            log.error("应用待恢复备份失败，将以现有数据库启动；pending 文件保留: {}", pendingDb, error);
            return false;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(file -> {
                try { Files.delete(file); } catch (IOException error) { throw new IllegalStateException(error); }
            });
        }
    }
}
