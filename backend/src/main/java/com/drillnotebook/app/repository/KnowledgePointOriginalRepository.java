package com.drillnotebook.app.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgePointOriginalRepository {

    public record OriginalRecord(long id, long pointId, String role, String content, String savedAt) {}

    private final JdbcTemplate jdbc;

    public KnowledgePointOriginalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OriginalRecord find(long pointId, String role) {
        List<OriginalRecord> rows = jdbc.query(
                "SELECT id, point_id, role, content, saved_at FROM knowledge_point_original WHERE point_id = ? AND role = ?",
                (rs, n) -> new OriginalRecord(rs.getLong("id"), rs.getLong("point_id"), rs.getString("role"), rs.getString("content"), rs.getString("saved_at")),
                pointId, role);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsert(long pointId, String role, String content) {
        jdbc.update("INSERT OR REPLACE INTO knowledge_point_original(point_id, role, content) VALUES (?, ?, ?)",
                pointId, role, content);
    }

    public boolean existsOriginal(long pointId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_point_original WHERE point_id = ? AND role = 'original'",
                Integer.class, pointId);
        return count != null && count > 0;
    }

    public void deleteByPoint(long pointId) {
        jdbc.update("DELETE FROM knowledge_point_original WHERE point_id = ?", pointId);
    }

    public List<Long> findPointIdsWithOriginal(long bankId) {
        return jdbc.query(
                "SELECT kpo.point_id FROM knowledge_point_original kpo " +
                "JOIN knowledge_point kp ON kp.id = kpo.point_id " +
                "WHERE kpo.role = 'original' AND kp.bank_id = ? " +
                "ORDER BY kpo.point_id",
                (rs, n) -> rs.getLong(1), bankId);
    }
}
