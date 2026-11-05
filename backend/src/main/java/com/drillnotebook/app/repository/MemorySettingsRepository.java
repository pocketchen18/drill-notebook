package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.ImmediateTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 记忆设置单行（contracts §2.1：{@code ai_memory_settings}，id 恒为 1）。
 *
 * <p>写方法一律按 {@code revision} 做 CAS：客户端必须带它读到的版本号，撞上就 409 并把最新行原样带回，
 * 不允许"最后写入的人赢"覆盖别人的开关。授权列只有 {@link #grantConsent} / {@link #revokeConsent}
 * 能改，开关写入永远不会顺带授权（§5.1）。</p>
 */
@Repository
public class MemorySettingsRepository {
    public static final long SETTINGS_ID = 1;
    public static final String PURPOSE_USE = "MEMORY_USE";
    public static final String PURPOSE_MAINTENANCE = "MEMORY_MAINTENANCE";
    private static final String USE_COLUMN = "use_consent_fingerprint";
    private static final String MAINTENANCE_COLUMN = "maintenance_consent_fingerprint";

    public record Settings(long revision, boolean enabled, boolean autoExtract, boolean compressSession,
                           boolean autoApplyLowRisk, String policyJson, String useConsentFingerprint,
                           String maintenanceConsentFingerprint, String consentedAt, String updatedAt) {

        /** 该用途当前的授权指纹（未授权为 null）。 */
        public String consentFingerprint(String purpose) {
            return USE_COLUMN.equals(consentColumn(purpose)) ? useConsentFingerprint : maintenanceConsentFingerprint;
        }

        public boolean consented(String purpose) {
            String fingerprint = consentFingerprint(purpose);
            return fingerprint != null && !fingerprint.isBlank();
        }
    }

    public enum WriteOutcome { APPLIED, CONFLICT }

    public record WriteResult(WriteOutcome outcome, Settings settings) {
    }

    private static final String COLUMNS = "revision, enabled, auto_extract, compress_session,"
            + " auto_apply_low_risk, policy_json, use_consent_fingerprint,"
            + " maintenance_consent_fingerprint, consented_at, updated_at";

    private final DataSource dataSource;
    private final ImmediateTransaction immediate;

    public MemorySettingsRepository(DataSource dataSource, ImmediateTransaction immediate) {
        this.dataSource = dataSource;
        this.immediate = immediate;
    }

    /** 首次访问时按 DDL 默认值建行；{@code INSERT OR IGNORE} 让并发首读也只落一行。 */
    public Settings ensure(String defaultPolicyJson, String timestamp) throws SQLException {
        return immediate.execute(connection -> {
            execute(connection, "INSERT OR IGNORE INTO ai_memory_settings(id, policy_json, updated_at)"
                    + " VALUES (?, ?, ?)", SETTINGS_ID, defaultPolicyJson, timestamp);
            return require(connection);
        });
    }

    public Optional<Settings> find() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return Optional.ofNullable(read(connection));
        }
    }

    public Settings require() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return require(connection);
        }
    }

    /**
     * CAS 写入开关与 policy：版本不符时一行都不改，带回最新行供上层做 409 响应。
     * 授权列不在此处，避免"改个开关就顺手同意了外发"。
     */
    public WriteResult write(long expectedRevision, boolean enabled, boolean autoExtract, boolean compressSession,
                             boolean autoApplyLowRisk, String policyJson, String updatedAt) throws SQLException {
        return immediate.execute(connection -> {
            int updated = execute(connection, "UPDATE ai_memory_settings SET revision = revision + 1,"
                            + " enabled = ?, auto_extract = ?, compress_session = ?, auto_apply_low_risk = ?,"
                            + " policy_json = ?, updated_at = ? WHERE id = ? AND revision = ?",
                    enabled ? 1 : 0, autoExtract ? 1 : 0, compressSession ? 1 : 0, autoApplyLowRisk ? 1 : 0,
                    policyJson, updatedAt, SETTINGS_ID, expectedRevision);
            return updated == 1
                    ? new WriteResult(WriteOutcome.APPLIED, require(connection))
                    : new WriteResult(WriteOutcome.CONFLICT, require(connection));
        });
    }

    /** 授予某用途的授权：写指纹与同意时间，并推进版本；不碰开关。 */
    public Settings grantConsent(String purpose, String fingerprint, String at) throws SQLException {
        return updateConsent(purpose, fingerprint, at);
    }

    /** 撤销授权：清空对应指纹并推进版本（上层据此作废相关任务）。 */
    public Settings revokeConsent(String purpose, String at) throws SQLException {
        return updateConsent(purpose, null, at);
    }

    /** 只推版本：遗忘边界之类的服务端 policy 变更没走客户端 CAS 时用。 */
    public Settings bumpRevision(String at) throws SQLException {
        return immediate.execute(connection -> {
            if (execute(connection, "UPDATE ai_memory_settings SET revision = revision + 1, updated_at = ?"
                    + " WHERE id = ?", at, SETTINGS_ID) != 1) {
                throw new SQLException("记忆设置行不存在，需要先初始化");
            }
            return require(connection);
        });
    }

    private Settings updateConsent(String purpose, String fingerprint, String at) throws SQLException {
        String column = consentColumn(purpose);
        return immediate.execute(connection -> {
            if (execute(connection, "UPDATE ai_memory_settings SET revision = revision + 1, " + column
                            + " = ?, consented_at = ?, updated_at = ? WHERE id = ?",
                    fingerprint, at, at, SETTINGS_ID) != 1) {
                throw new SQLException("记忆设置行不存在，需要先初始化");
            }
            return require(connection);
        });
    }

    /** 用途必须精确匹配，列名只可能来自这两个常量，杜绝拼接外部输入。 */
    private static String consentColumn(String purpose) {
        if (PURPOSE_USE.equals(purpose)) {
            return USE_COLUMN;
        }
        if (PURPOSE_MAINTENANCE.equals(purpose)) {
            return MAINTENANCE_COLUMN;
        }
        throw new IllegalArgumentException("未知的授权用途: " + purpose);
    }

    private static Settings require(Connection connection) throws SQLException {
        Settings current = read(connection);
        if (current == null) {
            throw new SQLException("记忆设置行不存在，需要先初始化");
        }
        return current;
    }

    private static Settings read(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM ai_memory_settings WHERE id = ?")) {
            statement.setLong(1, SETTINGS_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Settings(result.getLong("revision"), result.getInt("enabled") == 1,
                        result.getInt("auto_extract") == 1, result.getInt("compress_session") == 1,
                        result.getInt("auto_apply_low_risk") == 1, result.getString("policy_json"),
                        result.getString("use_consent_fingerprint"),
                        result.getString("maintenance_consent_fingerprint"), result.getString("consented_at"),
                        result.getString("updated_at"));
            }
        }
    }

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }
}
