package com.drillnotebook.app.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AttachmentRepositoryTest {
    @Autowired
    private AttachmentRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void insertAndFindById() {
        Long pageId = createTestPage();
        long id = repository.insert(pageId, "test.png", "attachments/1/abc.png", "image/png", 1024L, "sha256hash");
        Map<String, Object> found = repository.findById(id);
        assertNotNull(found);
        assertEquals("test.png", found.get("fileName"));
        assertEquals("image/png", found.get("mimeType"));
    }

    @Test
    void findBySha256ReturnsExisting() {
        Long pageId = createTestPage();
        repository.insert(pageId, "a.png", "attachments/1/a.png", "image/png", 100L, "hash-a");
        Map<String, Object> existing = repository.findBySha256(pageId, "hash-a");
        assertNotNull(existing);
        assertEquals("a.png", existing.get("fileName"));
    }

    @Test
    void deleteRemovesRecord() {
        Long pageId = createTestPage();
        long id = repository.insert(pageId, "x.png", "attachments/1/x.png", "image/png", 50L, "hash-x");
        repository.delete(id);
        assertNull(repository.findById(id));
    }

    private Long createTestPage() {
        jdbc.update("INSERT INTO notebook(title) VALUES ('test-nb')");
        Long nbId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO note_page(notebook_id, title, content) VALUES (?, 'test-page', '{}')", nbId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }
}
