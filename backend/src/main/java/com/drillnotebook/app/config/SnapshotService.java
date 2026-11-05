package com.drillnotebook.app.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 整库一致性快照：VACUUM INTO 读的是单个事务快照，不受 WAL 影响，也不拷贝活跃日志文件。
 *
 * <p>执行方案 §11.4 要求快照走独立 autocommit 连接，并且不依赖已完成迁移——初始化在 schema 事务之前
 * 用它留升级前副本，DataBackupService 复用它产出备份，两边都不必互相注入（避免构造器循环依赖）。</p>
 */
@Component
public class SnapshotService {
    private final DataSource dataSource;

    public SnapshotService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 产出快照。目标文件必须不存在：已存在说明同名副本（例如上一次升级前的安全快照）已在，
     * 静默覆盖会吃掉唯一的回退证据。
     *
     * <p>清理责任跟着创建责任走：只有本方法自己建出来的半成品才由它删除，
     * 因"目标已存在"而失败时绝不碰那个已存在的文件。</p>
     */
    public void vacuumInto(Path target) throws SQLException, IOException {
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException("快照目标已存在，拒绝覆盖: " + target);
        }
        Files.createDirectories(target.getParent());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        } catch (SQLException | RuntimeException error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailed) {
                // 半成品留在原地也不能掩盖原始失败原因
            }
            throw error;
        }
    }
}
