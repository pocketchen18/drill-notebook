package com.drillnotebook.app.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {
    private final JdbcTemplate jdbc;

    public AttachmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long pageId, String fileName, String storagePath, String mimeType, long fileSize, String sha256) {
        String sql = "INSERT INTO note_attachment(page_id, file_name, storage_path, mime_type, file_size, sha256) VALUES (?, ?, ?, ?, ?, ?)";
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, pageId);
            statement.setString(2, fileName);
            statement.setString(3, storagePath);
            statement.setString(4, mimeType);
            statement.setLong(5, fileSize);
            statement.setString(6, sha256);
            return statement;
        }, holder);
        Number key = holder.getKey();
        return key == null ? jdbc.queryForObject("SELECT last_insert_rowid()", Long.class) : key.longValue();
    }

    public Map<String, Object> findById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, page_id, file_name, storage_path, mime_type, file_size, sha256, created_at FROM note_attachment WHERE id = ?",
                    (result, row) -> mapRow(result), id);
        } catch (org.springframework.dao.EmptyResultDataAccessException error) {
            return null;
        }
    }

    public List<Map<String, Object>> findByPageId(long pageId) {
        return jdbc.query(
                "SELECT id, page_id, file_name, storage_path, mime_type, file_size, sha256, created_at FROM note_attachment WHERE page_id = ? ORDER BY id",
                (result, row) -> mapRow(result), pageId);
    }

    public Map<String, Object> findBySha256(long pageId, String sha256) {
        if (sha256 == null || sha256.isBlank()) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT id, page_id, file_name, storage_path, mime_type, file_size, sha256, created_at FROM note_attachment WHERE page_id = ? AND sha256 = ? LIMIT 1",
                    (result, row) -> mapRow(result), pageId, sha256);
        } catch (org.springframework.dao.EmptyResultDataAccessException error) {
            return null;
        }
    }

    public void delete(long id) { jdbc.update("DELETE FROM note_attachment WHERE id = ?", id); }

    private Map<String, Object> mapRow(java.sql.ResultSet result) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", result.getLong("id"));
        item.put("pageId", result.getLong("page_id"));
        item.put("fileName", result.getString("file_name"));
        item.put("storagePath", result.getString("storage_path"));
        item.put("mimeType", result.getString("mime_type"));
        item.put("fileSize", result.getLong("file_size"));
        item.put("sha256", result.getString("sha256"));
        item.put("createdAt", result.getString("created_at"));
        return item;
    }
}
