package com.drillnotebook.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * 快照件（§11.4）：一致性副本可用、且不静默覆盖已有的升级前证据。
 */
class SnapshotServiceTest {
    private Path appRoot;
    private DataSource dataSource;
    private SnapshotService snapshots;

    @BeforeEach
    void createDatabase() throws Exception {
        appRoot = TestWorkspace.scratchDirectory("p1-snapshot-");
        dataSource = Schema9Fixture.dataSource(appRoot);
        new DatabaseInitializer(dataSource, Schema9Fixture.resolver(appRoot)).initialize();
        snapshots = new SnapshotService(dataSource);
    }

    @AfterEach
    void cleanup() throws Exception {
        TestWorkspace.deleteRecursively(appRoot);
    }

    @Test
    void copyIsReadableAndPassesIntegrityCheck() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO notebook(title) VALUES ('快照前的笔记本')");
        Path target = appRoot.resolve("backups/copy.db");

        snapshots.vacuumInto(target);

        SQLiteDataSource copy = new SQLiteDataSource();
        copy.setUrl("jdbc:sqlite:" + target);
        JdbcTemplate copyJdbc = new JdbcTemplate(copy);
        assertEquals("快照前的笔记本",
                copyJdbc.queryForObject("SELECT title FROM notebook WHERE id = 1", String.class));
        assertEquals(9, copyJdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        try (Connection connection = copy.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            assertTrue(result.next());
            assertEquals("ok", result.getString(1));
        }
    }

    @Test
    void refusesToOverwriteAnExistingTarget() throws Exception {
        Path target = appRoot.resolve("backups/pre-existing.db");
        Files.createDirectories(target.getParent());
        new JdbcTemplate(dataSource).update("INSERT INTO notebook(title) VALUES ('第一次快照')");
        snapshots.vacuumInto(target);
        long firstSize = Files.size(target);

        new JdbcTemplate(dataSource).update("INSERT INTO notebook(title) VALUES ('后来的写入')");
        // DatabaseInitializer 的失败分支不再删文件：目标已存在时那个文件正是唯一的回退证据
        assertThrows(FileAlreadyExistsException.class, () -> snapshots.vacuumInto(target));

        JdbcTemplate copyJdbc = new JdbcTemplate(singletonCopyDataSource(target));
        assertEquals(1, copyJdbc.queryForObject("SELECT COUNT(*) FROM notebook", Integer.class));
        assertEquals(firstSize, Files.size(target), "拒绝覆盖时原快照不能被截断或删除");
    }

    private static DataSource singletonCopyDataSource(Path file) {
        SQLiteDataSource copy = new SQLiteDataSource();
        copy.setUrl("jdbc:sqlite:" + file);
        return copy;
    }
}
