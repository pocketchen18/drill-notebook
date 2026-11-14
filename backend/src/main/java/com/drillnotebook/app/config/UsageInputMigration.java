package com.drillnotebook.app.config;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 旧 attempt 没有缓存桶口径，但 daily 已按去重用量记账。
 * 仅在逐项原始桶、次数及结算状态与 daily 完整吻合时，用独立的 charged 账本核实口径；
 * target 指纹绑定 endpoint/API 格式/model，相同指纹的已核实口径可用于其余旧记录。
 * 缺失、冲突或损坏的账本保持 NULL，不按当前模型、缓存大小或供应商名称猜历史。
 */
final class UsageInputMigration {
    private UsageInputMigration() { }

    static void backfill(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    WITH buckets AS (
                        SELECT budget_date, budget_timezone, target_fingerprint, purpose,
                            SUM(CASE WHEN state <> 'RELEASED' THEN 1 ELSE 0 END) AS attempts,
                            SUM(CASE WHEN state IN ('RESERVED', 'SENT') THEN 1 ELSE 0 END) AS pending,
                            SUM(COALESCE(input_tokens, 0)) AS input,
                            SUM(COALESCE(output_tokens, 0)) AS output,
                            SUM(COALESCE(cache_read_tokens, 0)) AS cache_read,
                            SUM(COALESCE(cache_write_tokens, 0)) AS cache_write,
                            SUM(CASE WHEN input_tokens < 0 OR output_tokens < 0
                                OR cache_read_tokens < 0 OR cache_write_tokens < 0 THEN 1 ELSE 0 END) AS invalid
                        FROM ai_usage_attempt WHERE purpose = 'CHAT'
                        GROUP BY budget_date, budget_timezone, target_fingerprint, purpose
                    ), verified AS (
                        SELECT b.target_fingerprint,
                            CASE WHEN d.charged_tokens = b.input + b.output THEN 1 ELSE 0 END AS included
                        FROM buckets b JOIN ai_usage_daily d
                          ON d.budget_date = b.budget_date AND d.budget_timezone = b.budget_timezone
                            AND d.target_fingerprint = b.target_fingerprint AND d.purpose = b.purpose
                        WHERE b.pending = 0 AND b.invalid = 0 AND d.reserved_tokens = 0
                          AND d.attempts = b.attempts AND d.input_tokens = b.input
                          AND d.output_tokens = b.output AND d.cache_read_tokens = b.cache_read
                          AND d.cache_write_tokens = b.cache_write AND b.cache_read + b.cache_write > 0
                          AND d.charged_tokens IN (b.input + b.output,
                              b.input + b.output + b.cache_read + b.cache_write)
                    ), formats AS (
                        SELECT target_fingerprint, MIN(included) AS included FROM verified
                        GROUP BY target_fingerprint HAVING COUNT(DISTINCT included) = 1
                    )
                    UPDATE ai_usage_attempt SET cache_read_included_in_input = (
                        SELECT included FROM formats WHERE formats.target_fingerprint = ai_usage_attempt.target_fingerprint
                    )
                    WHERE purpose = 'CHAT' AND state = 'SETTLED' AND usage_source = 'provider'
                      AND cache_read_included_in_input IS NULL
                      AND target_fingerprint IN (SELECT target_fingerprint FROM formats)
                    """);
        }
    }
}
