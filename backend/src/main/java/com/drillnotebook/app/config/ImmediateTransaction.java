package com.drillnotebook.app.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 显式 IMMEDIATE 写事务：容量账本这类靠 CAS 判胜负的写入必须走它。
 *
 * <p>连接池默认 DEFERRED：事务先读后写时，后手在锁升级的瞬间直接拿到 SQLITE_BUSY
 * （busy_timeout 不覆盖锁升级，P0a 第④组实测复现）。BEGIN IMMEDIATE 让第二个写者排队等锁，
 * 醒来后 CAS 影响 0 行，服务层于是能干净地返回"额度不足"而不是 500。
 * 全局事务模式保持 DEFERRED：既有服务在事务内调用 LLM，只读事务也排队会打挂普通读请求。</p>
 */
@Component
public class ImmediateTransaction {
    private final DataSource dataSource;

    public ImmediateTransaction(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * 在 BEGIN IMMEDIATE … COMMIT 里执行 work；work 抛错则整体回滚后原样抛出。
     * 不做重试：等锁由连接的 busy_timeout 负责，超时后仍失败就交给调用方判定。
     */
    public <T> T execute(Work<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }
            try {
                T result = work.apply(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("COMMIT");
                }
                return result;
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // 连接已失效时由 close 收尾，不能盖住原始异常
        }
    }
}
