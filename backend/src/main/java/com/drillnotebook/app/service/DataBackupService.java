package com.drillnotebook.app.service;

import com.drillnotebook.app.config.PortablePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 数据备份：SQLite 经 VACUUM INTO 产出一致性快照，连同（可选）附件目录打包为 zip。
 * 恢复采用「暂存 + 下次启动换库」策略：运行中直接替换 SQLite 文件有损坏风险，
 * 因此恢复只把内容解包到 data/ 下的 pending 文件，由 PendingRestoreApplier
 * 在 DataSource 创建前原子换入。备份配置存 data/backup-config.json（独立于数据库，
 * 恢复旧库后配置仍保留）。
 */
@Service
public class DataBackupService {
    private static final Logger log = LoggerFactory.getLogger(DataBackupService.class);
    static final String DB_ZIP_ENTRY = "study.db";
    static final String MANIFEST_ZIP_ENTRY = "manifest.json";
    static final String ATTACHMENTS_ZIP_PREFIX = "attachments/";
    private static final String DB_FILE = "study.db";
    private static final String PENDING_DB = "study.db.pending-restore";
    private static final String PENDING_ATTACHMENTS = "attachments.pending-restore";
    private static final String RESTORE_MARKER = "restore-manifest.json";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final PortablePathResolver paths;
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DataBackupService(PortablePathResolver paths, DataSource dataSource, ObjectMapper mapper) {
        this.paths = paths;
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    public record BackupConfig(String directory, int autoIntervalHours, int maxCount, boolean lite) {
        static BackupConfig defaults() {
            return new BackupConfig("", 24, 10, false);
        }
    }

    public record BackupState(long lastBackupAt, long lastAutoBackupAt, String lastResult, String lastError) {
        static BackupState empty() {
            return new BackupState(0, 0, null, null);
        }
    }

    public BackupConfig loadConfig() {
        Path file = paths.data().resolve("backup-config.json");
        if (!Files.exists(file)) return BackupConfig.defaults();
        try {
            JsonNode node = mapper.readTree(file.toFile());
            BackupConfig fallback = BackupConfig.defaults();
            return new BackupConfig(
                    node.path("directory").asText(fallback.directory()),
                    node.path("autoIntervalHours").asInt(fallback.autoIntervalHours()),
                    node.path("maxCount").asInt(fallback.maxCount()),
                    node.path("lite").asBoolean(fallback.lite()));
        } catch (IOException error) {
            log.warn("读取备份配置失败，使用默认配置", error);
            return BackupConfig.defaults();
        }
    }

    public BackupConfig saveConfig(BackupConfig config) {
        if (config.autoIntervalHours() != 0 && config.autoIntervalHours() < 1) throw new IllegalArgumentException("自动备份间隔无效");
        if (config.autoIntervalHours() > 24 * 30) throw new IllegalArgumentException("自动备份间隔过长");
        if (config.maxCount() < 1 || config.maxCount() > 100) throw new IllegalArgumentException("最大备份数需在 1–100 之间");
        try {
            String directory = config.directory() == null ? "" : config.directory().trim();
            Path resolvedDir = null;
            if (!directory.isBlank()) {
                Path candidate = Path.of(directory);
                resolvedDir = (candidate.isAbsolute() ? candidate : paths.root().resolve(candidate)).normalize();
                Files.createDirectories(resolvedDir);
            }
            BackupConfig normalized = new BackupConfig(resolvedDir == null ? "" : resolvedDir.toString(), config.autoIntervalHours(), config.maxCount(), config.lite());
            Files.createDirectories(paths.data());
            Path file = paths.data().resolve("backup-config.json");
            mapper.writeValue(file.toFile(), normalized);
            return normalized;
        } catch (IOException error) {
            throw new IllegalStateException("保存备份配置失败：" + error.getMessage(), error);
        }
    }

    /** 备份目录（配置为空时回退 APP_ROOT/backups）。 */
    public Path backupDirectory() {
        String configured = loadConfig().directory();
        Path dir = configured == null || configured.isBlank() ? paths.root().resolve("backups") : Path.of(configured);
        try {
            Files.createDirectories(dir);
        } catch (IOException error) {
            throw new IllegalStateException("备份目录不可用：" + dir, error);
        }
        return dir;
    }

    private BackupState loadState() {
        Path file = paths.data().resolve("backup-state.json");
        if (!Files.exists(file)) return BackupState.empty();
        try {
            JsonNode node = mapper.readTree(file.toFile());
            return new BackupState(
                    node.path("lastBackupAt").asLong(0),
                    node.path("lastAutoBackupAt").asLong(0),
                    node.path("lastResult").asText(null),
                    node.path("lastError").asText(null));
        } catch (IOException error) {
            return BackupState.empty();
        }
    }

    private void saveState(BackupState state) {
        try {
            Files.createDirectories(paths.data());
            mapper.writeValue(paths.data().resolve("backup-state.json").toFile(), state);
        } catch (IOException error) {
            log.warn("写入备份状态失败", error);
        }
    }

    // ------------------------------------------------------------------
    // 创建备份
    // ------------------------------------------------------------------

    /** 立即创建备份（liteOverride 为 null 时取配置默认）。写入备份目录并执行保留策略。 */
    public Map<String, Object> createBackup(Boolean liteOverride) {
        Path dir = backupDirectory();
        Path target = dir.resolve(targetName(liteOverride));
        createBackupInto(target, liteOverride);
        prune();
        return describe(target);
    }

    /** 导出备份到任意绝对路径（Electron 另存为流程），不参与保留策略。 */
    public Map<String, Object> exportBackup(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) throw new IllegalArgumentException("缺少导出路径");
        Path target = Path.of(targetPath).toAbsolutePath().normalize();
        if (!target.getFileName().toString().toLowerCase().endsWith(".zip")) {
            target = target.resolveSibling(target.getFileName().toString() + ".zip");
        }
        createBackupInto(target, null);
        return describe(target);
    }

    private String targetName(Boolean liteOverride) {
        boolean lite = liteOverride != null ? liteOverride : loadConfig().lite();
        String base = "drill-backup-" + STAMP.format(Instant.now()) + (lite ? "-lite" : "");
        // 文件名时间戳为秒级，同秒内连续备份（手动+自动竞态）会互相覆盖，追加序号保证唯一
        Path dir = backupDirectory();
        String name = base + ".zip";
        int counter = 1;
        while (Files.exists(dir.resolve(name))) {
            name = base + "-" + counter + ".zip";
            counter++;
        }
        return name;
    }

    private void createBackupInto(Path target, Boolean liteOverride) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有备份正在进行，请稍后再试");
        }
        boolean lite = liteOverride != null ? liteOverride : loadConfig().lite();
        Path snapshot = paths.data().resolve("backup-snapshot.db");
        try {
            Files.createDirectories(target.getParent());
            vacuumeInto(snapshot);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                putManifest(zip, lite);
                putFile(zip, DB_ZIP_ENTRY, snapshot);
                if (!lite) {
                    putAttachments(zip);
                }
            }
            saveState(new BackupState(System.currentTimeMillis(), loadState().lastAutoBackupAt(),
                    (lite ? "精简" : "完整") + "备份 " + target.getFileName(), null));
            log.info("备份完成: {} (lite={})", target, lite);
        } catch (IOException | SQLException | UncheckedIOException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            saveState(new BackupState(loadState().lastBackupAt(), loadState().lastAutoBackupAt(), null, message));
            try { Files.deleteIfExists(target); } catch (IOException ignored) { /* 清理半成品 */ }
            throw new IllegalStateException("备份失败：" + message, error);
        } finally {
            try { Files.deleteIfExists(snapshot); } catch (IOException ignored) { /* 清理快照 */ }
            running.set(false);
        }
    }

    /** SQLite 在线一致性快照：VACUUM INTO 读取的是单个事务快照，不受 WAL 影响。 */
    private void vacuumeInto(Path snapshot) throws SQLException, IOException {
        Files.deleteIfExists(snapshot);
        String literal = snapshot.toString().replace("'", "''");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + literal + "'");
        }
    }

    private void putManifest(ZipOutputStream zip, boolean lite) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("app", "drill-notebook");
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("mode", lite ? "lite" : "full");
        manifest.put("includesAttachments", !lite);
        zip.putNextEntry(new ZipEntry(MANIFEST_ZIP_ENTRY));
        zip.write(mapper.writeValueAsBytes(manifest));
        zip.closeEntry();
    }

    private void putFile(ZipOutputStream zip, String entryName, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private void putAttachments(ZipOutputStream zip) throws IOException {
        Path root = paths.data().resolve("attachments");
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String relative = root.relativize(file).toString().replace('\\', '/');
                try {
                    zip.putNextEntry(new ZipEntry(ATTACHMENTS_ZIP_PREFIX + relative));
                    Files.copy(file, zip);
                    zip.closeEntry();
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 备份列表 / 删除 / 保留策略
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listBackups() {
        Path dir = backupDirectory();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(file -> file.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path file) -> safeMtime(file)).reversed())
                    .map(this::describe)
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("读取备份目录失败：" + error.getMessage(), error);
        }
    }

    private long safeMtime(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (IOException e) { return 0; }
    }

    private Map<String, Object> describe(Path file) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", file.getFileName().toString());
        try {
            entry.put("sizeBytes", Files.size(file));
            entry.put("createdAt", Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString());
        } catch (IOException error) {
            entry.put("sizeBytes", 0);
        }
        entry.put("lite", readManifestMode(file));
        return entry;
    }

    private boolean readManifestMode(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry manifest = zip.getEntry(MANIFEST_ZIP_ENTRY);
            if (manifest == null) return false;
            try (InputStream input = zip.getInputStream(manifest)) {
                JsonNode node = mapper.readTree(input.readAllBytes());
                return "lite".equalsIgnoreCase(node.path("mode").asText("full"));
            }
        } catch (IOException error) {
            return false;
        }
    }

    public void deleteBackup(String name) {
        try {
            Files.deleteIfExists(requireSafeBackup(name));
        } catch (IOException error) {
            throw new IllegalStateException("删除备份失败：" + error.getMessage(), error);
        }
    }

    /** 按最大备份数清理最旧的备份。 */
    private void prune() {
        int maxCount = loadConfig().maxCount();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(backupDirectory())) {
            stream.filter(file -> file.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path file) -> safeMtime(file)).reversed())
                    .forEach(files::add);
        } catch (IOException error) {
            log.warn("保留策略清理失败", error);
            return;
        }
        for (int index = maxCount; index < files.size(); index++) {
            try { Files.deleteIfExists(files.get(index)); } catch (IOException error) { log.warn("删除旧备份失败: {}", files.get(index), error); }
        }
    }

    private Path requireSafeBackup(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("缺少备份文件名");
        String fileName = Path.of(name).getFileName().toString();
        if (!fileName.equals(name) || !fileName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("非法备份文件名");
        }
        Path file = backupDirectory().resolve(fileName);
        if (!Files.exists(file)) throw new IllegalArgumentException("备份不存在");
        return file;
    }

    // ------------------------------------------------------------------
    // 恢复（暂存，启动时生效）
    // ------------------------------------------------------------------

    /** 从备份目录恢复：校验 zip 后暂存到 data/ pending 文件，等待下次启动换库。 */
    public Map<String, Object> restoreBackup(String name) {
        Path file = requireSafeBackup(name);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return stageRestore(zip, name);
        } catch (IOException error) {
            throw new IllegalStateException("备份文件无法读取：" + error.getMessage(), error);
        }
    }

    /** 从上传流恢复（导入备份）。 */
    public Map<String, Object> importBackup(InputStream input) {
        try (ZipInputStream stream = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            return stageRestore(stream, null);
        } catch (IOException error) {
            throw new IllegalStateException("备份包无法读取：" + error.getMessage(), error);
        }
    }

    /**
     * 校验并暂存备份包：study.db → data/study.db.pending-restore，
     * attachments/* → data/attachments.pending-restore/，写 restore-manifest.json 标记。
     * 注意 ZipInputStream 只能顺序读一次，因此单遍处理所有条目。
     */
    private Map<String, Object> stageRestore(Object source, String name) throws IOException {
        Path dataDir = paths.data();
        Files.createDirectories(dataDir);
        Path pendingDb = dataDir.resolve(PENDING_DB);
        Path pendingAttachments = dataDir.resolve(PENDING_ATTACHMENTS);
        boolean lite = true;
        boolean sawDb = false;
        try {
            Files.deleteIfExists(pendingDb);
            if (Files.exists(pendingAttachments)) deleteRecursively(pendingAttachments);

            if (source instanceof ZipFile zipFile) {
                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    try (InputStream entryInput = zipFile.getInputStream(entry)) {
                        lite = consumeEntry(entry.getName(), entryInput, pendingDb, pendingAttachments, lite);
                    }
                    sawDb = sawDb || entry.getName().equals(DB_ZIP_ENTRY);
                }
            } else if (source instanceof ZipInputStream zipStream) {
                ZipEntry entry;
                while ((entry = zipStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    lite = consumeEntry(entry.getName(), zipStream, pendingDb, pendingAttachments, lite);
                    sawDb = sawDb || entry.getName().equals(DB_ZIP_ENTRY);
                }
            } else {
                throw new IllegalArgumentException("不支持的备份源");
            }
            if (!sawDb) throw new IllegalArgumentException("备份包缺少数据库文件（study.db），无法恢复");

            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("from", name == null ? "（导入文件）" : name);
            marker.put("at", Instant.now().toString());
            marker.put("lite", lite);
            mapper.writeValue(dataDir.resolve(RESTORE_MARKER).toFile(), marker);
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(pendingDb); } catch (IOException ignored) { /* 清理半成品 */ }
            try { if (Files.exists(pendingAttachments)) deleteRecursively(pendingAttachments); } catch (IOException ignored) { /* 清理半成品 */ }
            throw error;
        }
        return Map.of(
                "staged", true,
                "from", name == null ? "（导入文件）" : name,
                "lite", lite,
                "message", "恢复内容已就绪，重启应用后生效");
    }

    /** 处理单个 zip 条目；返回更新后的 lite 标记。 */
    private boolean consumeEntry(String entryName, InputStream input, Path pendingDb, Path pendingAttachments, boolean lite) throws IOException {
        if (entryName.equals(DB_ZIP_ENTRY)) {
            Files.copy(input, pendingDb, StandardCopyOption.REPLACE_EXISTING);
        } else if (entryName.equals(MANIFEST_ZIP_ENTRY)) {
            // 先读全量字节再解析：Jackson readTree(InputStream) 会关闭流，
            // 而 ZipInputStream 被关闭后后续 getNextEntry 会直接抛 Stream closed。
            JsonNode node = mapper.readTree(input.readAllBytes());
            lite = "lite".equalsIgnoreCase(node.path("mode").asText("full"));
        } else if (entryName.startsWith(ATTACHMENTS_ZIP_PREFIX)) {
            String relative = entryName.substring(ATTACHMENTS_ZIP_PREFIX.length());
            Path target = pendingAttachments.resolve(relative).normalize();
            if (!target.startsWith(pendingAttachments)) return lite; // 防 zip 路径穿越
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return lite;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(file -> {
                try { Files.delete(file); } catch (IOException error) { throw new UncheckedIOException(error); }
            });
        }
    }

    // ------------------------------------------------------------------
    // 状态与自动备份
    // ------------------------------------------------------------------

    public Map<String, Object> status() {
        BackupConfig config = loadConfig();
        BackupState state = loadState();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directory", backupDirectory().toString());
        result.put("autoEnabled", config.autoIntervalHours() > 0);
        result.put("autoIntervalHours", config.autoIntervalHours());
        result.put("maxCount", config.maxCount());
        result.put("lite", config.lite());
        result.put("running", running.get());
        result.put("lastBackupAt", state.lastBackupAt() > 0 ? Instant.ofEpochMilli(state.lastBackupAt()).toString() : null);
        result.put("nextBackupAt", config.autoIntervalHours() > 0 && state.lastAutoBackupAt() > 0
                ? Instant.ofEpochMilli(state.lastAutoBackupAt() + config.autoIntervalHours() * 3600_000L).toString()
                : null);
        result.put("lastError", state.lastError());
        result.put("backupCount", listBackups().size());
        return result;
    }

    /** 自动备份心跳：到期即执行。fixedDelay 保证上一次检查完成后再计时。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void autoBackupTick() {
        try {
            BackupConfig config = loadConfig();
            if (config.autoIntervalHours() <= 0 || running.get()) return;
            BackupState state = loadState();
            long lastAuto = state.lastAutoBackupAt();
            if (lastAuto == 0) {
                // 首次启用：已有备份则以最新备份时间为基线，避免装完就备份
                List<Map<String, Object>> existing = listBackups();
                if (!existing.isEmpty()) {
                    saveState(new BackupState(state.lastBackupAt(), System.currentTimeMillis(), state.lastResult(), state.lastError()));
                    return;
                }
            }
            long dueAt = (lastAuto == 0 ? 0 : lastAuto) + config.autoIntervalHours() * 3600_000L;
            if (System.currentTimeMillis() < dueAt) return;
            Map<String, Object> created = createBackup(null);
            saveState(new BackupState(loadState().lastBackupAt(), System.currentTimeMillis(),
                    "自动" + created.get("name"), null));
            log.info("自动备份完成: {}", created.get("name"));
        } catch (RuntimeException error) {
            log.warn("自动备份失败: {}", error.getMessage());
        }
    }
}
