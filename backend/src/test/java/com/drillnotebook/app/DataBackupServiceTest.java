package com.drillnotebook.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drillnotebook.app.config.PendingRestoreApplier;
import com.drillnotebook.app.config.PortablePathResolver;
import com.drillnotebook.app.service.DataBackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

class DataBackupServiceTest {
    private static final String APP_ROOT_PROPERTY = "app.root";

    @TempDir
    Path tempRoot;

    private String previousRoot;
    private SQLiteDataSource dataSource;
    private DataBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        previousRoot = System.getProperty(APP_ROOT_PROPERTY);
        System.setProperty(APP_ROOT_PROPERTY, tempRoot.toString());
        Files.createDirectories(tempRoot.resolve("data"));
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempRoot.resolve("data").resolve("study.db"));
        service = new DataBackupService(new PortablePathResolver(), dataSource, new ObjectMapper());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS marker_table(value TEXT)");
            statement.execute("DELETE FROM marker_table");
            statement.execute("INSERT INTO marker_table(value) VALUES ('original')");
        }
    }

    @AfterEach
    void tearDown() {
        if (previousRoot != null) System.setProperty(APP_ROOT_PROPERTY, previousRoot);
        else System.clearProperty(APP_ROOT_PROPERTY);
    }

    private String markerValue() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT value FROM marker_table LIMIT 1")) {
            result.next();
            return result.getString(1);
        }
    }

    @Test
    void createLiteBackupProducesZipWithoutAttachments() throws Exception {
        Files.createDirectories(tempRoot.resolve("data").resolve("attachments").resolve("1"));
        Files.writeString(tempRoot.resolve("data").resolve("attachments").resolve("1").resolve("a.txt"), "x");

        Map<String, Object> entry = service.createBackup(true);

        assertThat(entry.get("lite")).isEqualTo(true);
        List<Map<String, Object>> list = service.listBackups();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("name")).isEqualTo(entry.get("name"));
        Path zip = tempRoot.resolve("backups").resolve(String.valueOf(entry.get("name")));
        assertThat(zip).exists();
        assertThat(countEntries(zip, "attachments/")).isZero();
        assertThat(countEntries(zip, "study.db")).isEqualTo(1);
    }

    @Test
    void createFullBackupIncludesAttachments() throws Exception {
        Files.createDirectories(tempRoot.resolve("data").resolve("attachments").resolve("1"));
        Files.writeString(tempRoot.resolve("data").resolve("attachments").resolve("1").resolve("a.txt"), "hello");

        Map<String, Object> entry = service.createBackup(false);

        assertThat(entry.get("lite")).isEqualTo(false);
        Path zip = tempRoot.resolve("backups").resolve(String.valueOf(entry.get("name")));
        assertThat(countEntries(zip, "attachments/")).isEqualTo(1);
    }

    @Test
    void retentionPrunesOldestBackups() throws Exception {
        service.saveConfig(new DataBackupService.BackupConfig("", 0, 2, false));
        List<String> names = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Map<String, Object> entry = service.createBackup(false);
            names.add(String.valueOf(entry.get("name")));
            // zip 文件名时间戳为秒级，手工错开 mtime 保证排序确定性
            Path zip = tempRoot.resolve("backups").resolve(names.get(index));
            Files.setLastModifiedTime(zip, Files.getLastModifiedTime(zip).toMillis() > 0
                    ? java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L + index * 60_000L)
                    : java.nio.file.attribute.FileTime.fromMillis(0));
        }
        List<Map<String, Object>> remaining = service.listBackups();
        assertThat(remaining).hasSize(2);
        assertThat(remaining.stream().map(row -> row.get("name"))).doesNotContain(names.get(0));
    }

    @Test
    void configRoundTripAndValidation() {
        DataBackupService.BackupConfig saved = service.saveConfig(
                new DataBackupService.BackupConfig(tempRoot.resolve("custom-backups").toString(), 12, 5, true));
        assertThat(saved.autoIntervalHours()).isEqualTo(12);
        assertThat(saved.maxCount()).isEqualTo(5);
        assertThat(saved.lite()).isTrue();
        assertThat(service.loadConfig()).isEqualTo(saved);

        assertThatThrownBy(() -> service.saveConfig(new DataBackupService.BackupConfig("", 0, 0, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveConfig(new DataBackupService.BackupConfig("", -3, 10, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreStagesPendingFilesAndApplierSwapsDatabase() throws Exception {
        Files.createDirectories(tempRoot.resolve("data").resolve("attachments").resolve("1"));
        Files.writeString(tempRoot.resolve("data").resolve("attachments").resolve("1").resolve("a.txt"), "backup-version");
        Map<String, Object> entry = service.createBackup(false);
        String name = String.valueOf(entry.get("name"));

        // 备份后改动当前库，模拟"数据已变"
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE marker_table SET value = 'changed-after-backup'");
        }

        Map<String, Object> staged = service.restoreBackup(name);
        assertThat(staged.get("staged")).isEqualTo(true);
        assertThat(tempRoot.resolve("data").resolve("study.db.pending-restore")).exists();
        assertThat(tempRoot.resolve("data").resolve("restore-manifest.json")).exists();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        assertThat(PendingRestoreApplier.applyIfPending(tempRoot.resolve("data"))).isTrue();
        assertThat(tempRoot.resolve("data").resolve("study.db.pending-restore")).doesNotExist();
        // 换库后数据回到备份时点，附件目录同样被替换
        assertThat(markerValue()).isEqualTo("original");
        assertThat(tempRoot.resolve("data").resolve("attachments").resolve("1").resolve("a.txt"))
                .exists()
                .hasContent("backup-version");
    }

    @Test
    void importBackupStagesFromStreamAndRejectsInvalidZip() throws Exception {
        Map<String, Object> entry = service.createBackup(true);
        Path zip = tempRoot.resolve("backups").resolve(String.valueOf(entry.get("name")));
        Map<String, Object> staged = service.importBackup(new ByteArrayInputStream(Files.readAllBytes(zip)));
        assertThat(staged.get("staged")).isEqualTo(true);
        assertThat(tempRoot.resolve("data").resolve("study.db.pending-restore")).exists();

        // 缺少 study.db 的 zip 必须拒绝且不留半成品
        Path invalid = tempRoot.resolve("invalid.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(invalid))) {
            output.putNextEntry(new ZipEntry("random.txt"));
            output.write("junk".getBytes());
            output.closeEntry();
        }
        assertThatThrownBy(() -> service.importBackup(new ByteArrayInputStream(Files.readAllBytes(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("study.db");
        // 失败的导入会清理本次半成品（此前成功的暂存已被本次失败覆盖清理，属预期）
        assertThat(tempRoot.resolve("data").resolve("study.db.pending-restore")).doesNotExist();
    }

    @Test
    void statusReflectsConfigAndBackups() {
        service.saveConfig(new DataBackupService.BackupConfig("", 24, 10, false));
        service.createBackup(false);
        service.autoBackupTick(); // 首跳以现有备份建立基线，不再重复创建
        Map<String, Object> status = service.status();
        assertThat(status.get("autoEnabled")).isEqualTo(true);
        assertThat(status.get("backupCount")).isEqualTo(1);
        assertThat(status.get("lastBackupAt")).isNotNull();
        assertThat(status.get("nextBackupAt")).isNotNull();
    }

    private int countEntries(Path zip, String prefix) throws Exception {
        int count = 0;
        try (InputStream input = Files.newInputStream(zip); ZipInputStream stream = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if (entry.getName().startsWith(prefix)) count++;
            }
        }
        return count;
    }
}
