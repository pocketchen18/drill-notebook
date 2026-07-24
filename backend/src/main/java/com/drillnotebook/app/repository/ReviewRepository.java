package com.drillnotebook.app.repository;

import com.drillnotebook.app.model.ReviewLog;
import com.drillnotebook.app.model.ReviewSchedule;
import com.drillnotebook.app.model.SpacedRepetitionConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.RowMapper<ReviewSchedule> scheduleRowMapper;
    private final org.springframework.jdbc.core.RowMapper<ReviewLog> logRowMapper;
    private final org.springframework.jdbc.core.RowMapper<SpacedRepetitionConfig> configRowMapper;

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ReviewRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.scheduleRowMapper = (result, row) -> ReviewSchedule.from(result, mapper);
        this.logRowMapper = (result, row) -> ReviewLog.from(result, mapper);
        this.configRowMapper = (result, row) -> SpacedRepetitionConfig.from(result, mapper);
    }

    // ===================== 配置管理 =====================

    public List<SpacedRepetitionConfig> findAllConfigs() {
        return jdbc.query("SELECT * FROM spaced_repetition_config ORDER BY is_default DESC, id", configRowMapper);
    }

    public SpacedRepetitionConfig findConfigById(long id) {
        return jdbc.queryForObject("SELECT * FROM spaced_repetition_config WHERE id = ?", configRowMapper, id);
    }

    public SpacedRepetitionConfig findDefaultConfig() {
        List<SpacedRepetitionConfig> configs = jdbc.query(
            "SELECT * FROM spaced_repetition_config WHERE is_default = 1 ORDER BY id LIMIT 1", configRowMapper);
        if (configs.isEmpty()) {
            configs = jdbc.query("SELECT * FROM spaced_repetition_config ORDER BY id LIMIT 1", configRowMapper);
        }
        return configs.isEmpty() ? null : configs.get(0);
    }

    @Transactional
    public long insertConfig(SpacedRepetitionConfig config) {
        if (config.isDefault) {
            jdbc.update("UPDATE spaced_repetition_config SET is_default = 0 WHERE is_default = 1");
        }
        String intervalsJson;
        try {
            intervalsJson = mapper.writeValueAsString(config.intervals);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize intervals", e);
        }
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO spaced_repetition_config(name, is_default, intervals_json, initial_ef, minimum_ef, max_interval_days, wrong_strategy, wrong_fixed_days, daily_new_limit, daily_review_limit, priority_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, config.name);
            ps.setInt(2, config.isDefault ? 1 : 0);
            ps.setString(3, intervalsJson);
            ps.setDouble(4, config.initialEf);
            ps.setDouble(5, config.minimumEf);
            ps.setInt(6, config.maxIntervalDays);
            ps.setString(7, config.wrongStrategy);
            ps.setDouble(8, config.wrongFixedDays);
            ps.setInt(9, config.dailyNewLimit);
            ps.setInt(10, config.dailyReviewLimit);
            ps.setString(11, config.priorityMode);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    @Transactional
    public void updateConfig(long id, SpacedRepetitionConfig config) {
        if (config.isDefault) {
            jdbc.update("UPDATE spaced_repetition_config SET is_default = 0 WHERE is_default = 1 AND id != ?", id);
        }
        String intervalsJson;
        try {
            intervalsJson = mapper.writeValueAsString(config.intervals);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize intervals", e);
        }
        jdbc.update(
            "UPDATE spaced_repetition_config SET name=?, is_default=?, intervals_json=?, initial_ef=?, minimum_ef=?, max_interval_days=?, wrong_strategy=?, wrong_fixed_days=?, daily_new_limit=?, daily_review_limit=?, priority_mode=? WHERE id=?",
            config.name,
            config.isDefault ? 1 : 0,
            intervalsJson,
            config.initialEf,
            config.minimumEf,
            config.maxIntervalDays,
            config.wrongStrategy,
            config.wrongFixedDays,
            config.dailyNewLimit,
            config.dailyReviewLimit,
            config.priorityMode,
            id);
    }

    @Transactional
    public void deleteConfig(long id) {
        SpacedRepetitionConfig config = findConfigById(id);
        if (config != null && config.isDefault) {
            throw new IllegalArgumentException("不能删除默认配置方案，请先将其他方案设为默认");
        }
        jdbc.update("DELETE FROM review_schedule WHERE config_id = ?", id);
        jdbc.update("DELETE FROM spaced_repetition_config WHERE id = ?", id);
    }

    // ===================== 复习调度管理 =====================

    public ReviewSchedule findScheduleByItem(String itemType, long itemId, Long configId) {
        if (configId != null) {
            List<ReviewSchedule> results = jdbc.query(
                "SELECT * FROM review_schedule WHERE item_type = ? AND item_id = ? AND config_id = ?",
                scheduleRowMapper, itemType, itemId, configId);
            return results.isEmpty() ? null : results.get(0);
        }
        List<ReviewSchedule> results = jdbc.query(
            "SELECT * FROM review_schedule WHERE item_type = ? AND item_id = ? ORDER BY id",
            scheduleRowMapper, itemType, itemId);
        return results.isEmpty() ? null : results.get(0);
    }

    public ReviewSchedule findScheduleById(long id) {
        return jdbc.queryForObject("SELECT * FROM review_schedule WHERE id = ?", scheduleRowMapper, id);
    }

    public List<ReviewSchedule> findDueItems(String itemType, Long configId, int limit, String priorityMode) {
        String now = LocalDateTime.now().format(ISO_DATETIME);
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM review_schedule WHERE next_review IS NOT NULL AND next_review <= ?");
        List<Object> params = new ArrayList<>();
        params.add(now);
        if (itemType != null) {
            sql.append(" AND item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        switch (priorityMode != null ? priorityMode : "due_first") {
            case "worst_first":
                sql.append(" ORDER BY ef ASC, next_review ASC");
                break;
            case "random":
                sql.append(" ORDER BY RANDOM()");
                break;
            default:
                sql.append(" ORDER BY next_review ASC");
                break;
        }
        if (limit > 0) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        return jdbc.query(sql.toString(), scheduleRowMapper, params.toArray());
    }

    public List<ReviewSchedule> findNewItems(String itemType, Long configId, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM review_schedule WHERE status = 'new'");
        List<Object> params = new ArrayList<>();
        if (itemType != null) {
            sql.append(" AND item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        sql.append(" ORDER BY created_at ASC");
        if (limit > 0) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        return jdbc.query(sql.toString(), scheduleRowMapper, params.toArray());
    }

    /** 返回所有非 mastered 的登记项（不限到期状态） */
    public List<ReviewSchedule> findAllActiveItems(String itemType, Long configId) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM review_schedule WHERE status IN ('new','learning','review')");
        List<Object> params = new ArrayList<>();
        if (itemType != null) {
            sql.append(" AND item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        sql.append(" ORDER BY status = 'new' DESC, next_review ASC");
        return jdbc.query(sql.toString(), scheduleRowMapper, params.toArray());
    }

    @Transactional
    public long createSchedule(String itemType, long itemId, Long configId) {
        // New cards must be due immediately so they appear in the calendar today-queue
        // (queue filters date(next_review) <= day; NULL next_review was invisible).
        String now = LocalDateTime.now().format(ISO_DATETIME);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO review_schedule(item_type, item_id, config_id, status, next_review, interval, repetitions) "
                    + "VALUES (?, ?, ?, 'new', ?, 0, 0)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, itemType);
            ps.setLong(2, itemId);
            if (configId == null) ps.setObject(3, null);
            else ps.setLong(3, configId);
            ps.setString(4, now);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    /** Rows with NULL next_review that are still new/learning (legacy enroll before next_review was set). */
    public List<ReviewSchedule> findNewWithoutNextReview(String itemTypeOrNull, Long configIdOrNull) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM review_schedule WHERE next_review IS NULL AND status IN ('new', 'learning')");
        List<Object> params = new ArrayList<>();
        if (itemTypeOrNull != null) {
            sql.append(" AND item_type = ?");
            params.add(itemTypeOrNull);
        }
        if (configIdOrNull != null) {
            sql.append(" AND config_id = ?");
            params.add(configIdOrNull);
        }
        sql.append(" ORDER BY id ASC");
        return jdbc.query(sql.toString(), scheduleRowMapper, params.toArray());
    }

    @Transactional
    public void updateSchedule(long id, double ef, double interval, int repetitions,
                                String nextReview, String lastReview, int lastQuality,
                                int isCorrect, String status) {
        String now = LocalDateTime.now().format(ISO_DATETIME);
        int wrongIncrement = isCorrect != 0 ? 0 : 1;
        int streakCorrect = isCorrect != 0 ? 1 : 0;
        jdbc.update(
            "UPDATE review_schedule SET ef=?, interval=?, repetitions=?, next_review=?, last_review=?, last_quality=?, total_reviews=total_reviews+1, total_wrong=total_wrong+?, streak_correct=CASE WHEN ? = 1 THEN streak_correct+1 ELSE 0 END, status=?, updated_at=? WHERE id=?",
            ef, interval, repetitions, nextReview, lastReview, lastQuality,
            wrongIncrement, isCorrect, status, now, id);
    }

    /** Adjust next_review/interval only (no total_reviews bump). Used after queue clear-view-day. */
    public void updateNextReviewOnly(long id, String nextReview, double interval) {
        String now = LocalDateTime.now().format(ISO_DATETIME);
        jdbc.update(
            "UPDATE review_schedule SET next_review=?, interval=?, updated_at=? WHERE id=?",
            nextReview, interval, now, id);
    }

    public int countNewToday(String itemType, Long configId) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM review_schedule WHERE status = 'new' AND created_at >= ?");
        List<Object> params = new ArrayList<>();
        params.add(today);
        if (itemType != null) {
            sql.append(" AND item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    public int countDueToday(String itemType, Long configId) {
        String now = LocalDateTime.now().format(ISO_DATETIME);
        String todayStart = LocalDateTime.now().toLocalDate().atStartOfDay().format(ISO_DATETIME);
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM review_schedule WHERE next_review IS NOT NULL AND next_review <= ? AND next_review >= ?");
        List<Object> params = new ArrayList<>();
        params.add(now);
        params.add(todayStart);
        if (itemType != null) {
            sql.append(" AND item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    public int countByStatus(String itemType, Long configId, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM review_schedule WHERE status = ?");
        List<Object> params = new ArrayList<>();
        params.add(status);
        if (itemType != null) {
            sql.append(" AND item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND config_id = ?");
            params.add(configId);
        }
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    @Transactional
    public void resetSchedule(long id) {
        jdbc.update("UPDATE review_schedule SET ef=2.5, interval=0, repetitions=0, next_review=NULL, last_review=NULL, last_quality=NULL, total_reviews=0, total_wrong=0, streak_correct=0, status='new', updated_at=datetime('now') WHERE id=?", id);
    }

    @Transactional
    public void deleteSchedule(long id) {
        jdbc.update("DELETE FROM review_log WHERE schedule_id = ?", id);
        jdbc.update("DELETE FROM review_schedule WHERE id = ?", id);
    }

    @Transactional
    public void deleteSchedulesByItems(String itemType, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return;
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(itemIds));
        for (int start = 0; start < distinct.size(); start += 500) {
            List<Long> batch = distinct.subList(start, Math.min(start + 500, distinct.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            List<Object> params = new ArrayList<>();
            params.add(itemType);
            params.addAll(batch);
            jdbc.update(
                "DELETE FROM review_log WHERE schedule_id IN (SELECT id FROM review_schedule WHERE item_type = ? AND item_id IN (" + placeholders + "))",
                params.toArray());
            jdbc.update(
                "DELETE FROM review_schedule WHERE item_type = ? AND item_id IN (" + placeholders + ")",
                params.toArray());
        }
    }

    // ===================== 复习日志 =====================

    @Transactional
    public long insertLog(long scheduleId, int quality, Integer responseTime,
                          double scheduledInterval, double actualInterval, String source) {
        String now = LocalDateTime.now().format(ISO_DATETIME);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO review_log(schedule_id, quality, response_time, scheduled_interval, actual_interval, source, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, scheduleId);
            ps.setInt(2, quality);
            if (responseTime == null) ps.setObject(3, null);
            else ps.setInt(3, responseTime);
            ps.setDouble(4, scheduledInterval);
            ps.setDouble(5, actualInterval);
            ps.setString(6, source);
            ps.setString(7, now);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public List<ReviewLog> findLogsBySchedule(long scheduleId, int limit) {
        return jdbc.query(
            "SELECT * FROM review_log WHERE schedule_id = ? ORDER BY reviewed_at DESC, id DESC LIMIT ?",
            logRowMapper, scheduleId, limit);
    }

    /**
     * True if a same-day review_log exists that advances the schedule (not source = 'extra').
     * NULL or any non-extra source counts as main.
     */
    public boolean hasMainAdvanceOnDate(long scheduleId, String ymd) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM review_log WHERE schedule_id = ? "
                + "AND date(reviewed_at) = date(?) "
                + "AND IFNULL(source, '') <> 'extra'",
            Integer.class, scheduleId, ymd);
        return n != null && n > 0;
    }

    /**
     * Schedules whose next_review falls exactly on ymd. Strict due rule only —
     * items with NULL next_review are excluded (new pool is handled elsewhere).
     * Overdue items stay pinned to their original next_review day and do NOT
     * spill into subsequent days' queues.
     */
    public List<ReviewSchedule> findDueOnOrBefore(String ymd, String itemTypeOrNull, Long configIdOrNull) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM review_schedule WHERE next_review IS NOT NULL AND date(next_review) = date(?)");
        List<Object> params = new ArrayList<>();
        params.add(ymd);
        if (itemTypeOrNull != null) {
            sql.append(" AND item_type = ?");
            params.add(itemTypeOrNull);
        }
        if (configIdOrNull != null) {
            sql.append(" AND config_id = ?");
            params.add(configIdOrNull);
        }
        sql.append(" ORDER BY next_review ASC");
        return jdbc.query(sql.toString(), scheduleRowMapper, params.toArray());
    }

    /**
     * Count SRS schedules whose next_review falls on each day in [from, to].
     * Used by the calendar view to overlay memory-curve due markers per day.
     * Only non-mastered, non-new-legacy rows with a concrete next_review are counted.
     */
    public List<Map<String, Object>> countDueByDay(String from, String to, Long configIdOrNull) {
        StringBuilder sql = new StringBuilder(
            "SELECT date(next_review) as due_date, COUNT(*) as due_count "
                + "FROM review_schedule "
                + "WHERE next_review IS NOT NULL "
                + "AND date(next_review) BETWEEN date(?) AND date(?) "
                + "AND status IN ('new','learning','review')");
        List<Object> params = new ArrayList<>();
        params.add(from);
        params.add(to);
        if (configIdOrNull != null) {
            sql.append(" AND config_id = ?");
            params.add(configIdOrNull);
        }
        sql.append(" GROUP BY date(next_review) ORDER BY due_date");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /**
     * Count schedules that are overdue relative to real today (next_review < today)
     * and still not mastered. Grouped by date(next_review) so the calendar can mark
     * the original due day with a red/overdue indicator.
     */
    public List<Map<String, Object>> countOverdueByDay(String from, String to, String realToday, Long configIdOrNull) {
        StringBuilder sql = new StringBuilder(
            "SELECT date(next_review) as due_date, COUNT(*) as overdue_count "
                + "FROM review_schedule "
                + "WHERE next_review IS NOT NULL "
                + "AND date(next_review) < date(?) "
                + "AND date(next_review) BETWEEN date(?) AND date(?) "
                + "AND status IN ('new','learning','review')");
        List<Object> params = new ArrayList<>();
        params.add(realToday);
        params.add(from);
        params.add(to);
        if (configIdOrNull != null) {
            sql.append(" AND config_id = ?");
            params.add(configIdOrNull);
        }
        sql.append(" GROUP BY date(next_review) ORDER BY due_date");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> dailyStats(String itemType, Long configId, int days) {
        StringBuilder sql = new StringBuilder(
            "SELECT date(rl.reviewed_at) as review_date, COUNT(*) as total, SUM(CASE WHEN rl.quality >= 3 THEN 1 ELSE 0 END) as passed FROM review_log rl JOIN review_schedule rs ON rl.schedule_id = rs.id WHERE rl.reviewed_at >= datetime('now', ?)");
        List<Object> params = new ArrayList<>();
        params.add("-" + days + " days");
        if (itemType != null) {
            sql.append(" AND rs.item_type = ?");
            params.add(itemType);
        }
        if (configId != null) {
            sql.append(" AND rs.config_id = ?");
            params.add(configId);
        }
        sql.append(" GROUP BY date(rl.reviewed_at) ORDER BY review_date");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }
}
