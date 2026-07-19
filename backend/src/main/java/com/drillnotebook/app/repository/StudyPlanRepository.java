package com.drillnotebook.app.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class StudyPlanRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<Map<String, Object>> GROUP_MAPPER = (result, row) -> {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", result.getLong("id"));
        item.put("planDate", result.getString("plan_date"));
        item.put("title", result.getString("title"));
        item.put("note", result.getString("note"));
        item.put("source", result.getString("source"));
        item.put("createdAt", result.getString("created_at"));
        item.put("updatedAt", result.getString("updated_at"));
        return item;
    };

    private static final RowMapper<Map<String, Object>> ITEM_MAPPER = (result, row) -> {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", result.getLong("id"));
        item.put("groupId", result.getLong("group_id"));
        item.put("planDate", result.getString("plan_date"));
        item.put("resourceType", result.getString("resource_type"));
        item.put("resourceId", result.getLong("resource_id"));
        item.put("title", result.getString("title"));
        item.put("note", result.getString("note"));
        item.put("status", result.getString("status"));
        item.put("completedAt", result.getString("completed_at"));
        item.put("createdAt", result.getString("created_at"));
        item.put("updatedAt", result.getString("updated_at"));
        return item;
    };

    public StudyPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertGroup(String planDate, String title, String note, String source) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO study_plan_group(plan_date, title, note, source) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, planDate);
            statement.setString(2, title);
            statement.setString(3, note);
            statement.setString(4, source);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void updateGroup(long id, String title, String note, String planDate) {
        jdbc.update("""
                UPDATE study_plan_group SET
                    title = COALESCE(?, title),
                    note = COALESCE(?, note),
                    plan_date = COALESCE(?, plan_date),
                    updated_at = datetime('now')
                WHERE id = ?
                """, title, note, planDate, id);
    }

    public void deleteGroup(long id) {
        jdbc.update("DELETE FROM study_plan_item WHERE group_id = ?", id);
        jdbc.update("DELETE FROM study_plan_group WHERE id = ?", id);
    }

    public Map<String, Object> findGroup(long id) {
        return jdbc.queryForObject(
                "SELECT id, plan_date, title, note, source, created_at, updated_at FROM study_plan_group WHERE id = ?",
                GROUP_MAPPER,
                id);
    }

    public long insertItem(long groupId, String planDate, String resourceType, long resourceId, String title, String note) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO study_plan_item(group_id, plan_date, resource_type, resource_id, title, note) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, groupId);
            statement.setString(2, planDate);
            statement.setString(3, resourceType);
            statement.setLong(4, resourceId);
            statement.setString(5, title);
            statement.setString(6, note);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public void updateItem(long id, String planDate, String note, String status) {
        if (planDate != null) {
            jdbc.update(
                    "UPDATE study_plan_item SET plan_date = ?, updated_at = datetime('now') WHERE id = ?",
                    planDate,
                    id);
        }
        if (note != null) {
            jdbc.update(
                    "UPDATE study_plan_item SET note = ?, updated_at = datetime('now') WHERE id = ?",
                    note,
                    id);
        }
        if (status != null) {
            if ("done".equals(status)) {
                jdbc.update(
                        "UPDATE study_plan_item SET status = 'done', completed_at = datetime('now'), updated_at = datetime('now') WHERE id = ?",
                        id);
            } else if ("todo".equals(status)) {
                jdbc.update(
                        "UPDATE study_plan_item SET status = 'todo', completed_at = NULL, updated_at = datetime('now') WHERE id = ?",
                        id);
            } else {
                jdbc.update(
                        "UPDATE study_plan_item SET status = ?, updated_at = datetime('now') WHERE id = ?",
                        status,
                        id);
            }
        }
    }

    public void deleteItem(long id) {
        jdbc.update("DELETE FROM study_plan_item WHERE id = ?", id);
    }

    public Map<String, Object> findItem(long id) {
        return jdbc.queryForObject(
                "SELECT id, group_id, plan_date, resource_type, resource_id, title, note, status, completed_at, created_at, updated_at FROM study_plan_item WHERE id = ?",
                ITEM_MAPPER,
                id);
    }

    public List<Map<String, Object>> findGroupsBetween(String from, String to) {
        return jdbc.query(
                "SELECT id, plan_date, title, note, source, created_at, updated_at FROM study_plan_group WHERE plan_date >= ? AND plan_date <= ? ORDER BY plan_date, id",
                GROUP_MAPPER,
                from,
                to);
    }

    public List<Map<String, Object>> findItemsForGroups(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(groupIds.size(), "?"));
        String sql = "SELECT id, group_id, plan_date, resource_type, resource_id, title, note, status, completed_at, created_at, updated_at FROM study_plan_item WHERE group_id IN ("
                + placeholders
                + ") ORDER BY id";
        return jdbc.query(sql, ITEM_MAPPER, groupIds.toArray());
    }

    public boolean existsTodo(String planDate, String resourceType, long resourceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_item WHERE plan_date = ? AND resource_type = ? AND resource_id = ? AND status = 'todo'",
                Integer.class,
                planDate,
                resourceType,
                resourceId);
        return count != null && count > 0;
    }

    /**
     * Mark matching todo items as done. Optional scope filters narrow the update.
     * Returns number of rows updated.
     */
    public int markTodoDoneByResources(
            String resourceType,
            List<Long> resourceIds,
            String planDate,
            Long groupId) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(resourceIds.size(), "?"));
        StringBuilder sql = new StringBuilder(
                "UPDATE study_plan_item SET status = 'done', completed_at = datetime('now'), updated_at = datetime('now') "
                        + "WHERE status = 'todo' AND resource_type = ? AND resource_id IN ("
                        + placeholders
                        + ")");
        List<Object> args = new java.util.ArrayList<>();
        args.add(resourceType);
        args.addAll(resourceIds);
        if (planDate != null && !planDate.isBlank()) {
            sql.append(" AND plan_date = ?");
            args.add(planDate);
        }
        if (groupId != null && groupId > 0) {
            sql.append(" AND group_id = ?");
            args.add(groupId);
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    public int countItems(long groupId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_item WHERE group_id = ?",
                Integer.class,
                groupId);
        return count == null ? 0 : count;
    }

    public void deleteGroupIfEmpty(long groupId) {
        if (countItems(groupId) == 0) {
            jdbc.update("DELETE FROM study_plan_group WHERE id = ?", groupId);
        }
    }

    public void syncTodoItemDates(long groupId, String planDate) {
        jdbc.update(
                "UPDATE study_plan_item SET plan_date = ?, updated_at = datetime('now') WHERE group_id = ? AND status = 'todo'",
                planDate,
                groupId);
    }
}
