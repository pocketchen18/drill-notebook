package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.DatabaseInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgePointOriginalRepositoryTest {

    private KnowledgePointOriginalRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        Path tmp = java.nio.file.Files.createTempFile("study", ".db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.toAbsolutePath());
        new DatabaseInitializer(ds).initialize();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO question_bank(name) VALUES ('test')");
        jdbc.update("INSERT INTO knowledge_point(bank_id, title, content, tags, heading_path) VALUES (1, 'T1', '原文', '[]', '[]')");
        repo = new KnowledgePointOriginalRepository(jdbc);
    }

    @Test
    void upsertInsertsThenReplaces() {
        assertNull(repo.find(1, "original"));
        repo.upsert(1, "original", "v1");
        assertEquals("v1", repo.find(1, "original").content());
        repo.upsert(1, "original", "v2");
        assertEquals("v2", repo.find(1, "original").content());
    }

    @Test
    void existsOriginalAndFindPointIdsWithOriginal() {
        assertFalse(repo.existsOriginal(1));
        repo.upsert(1, "original", "原文");
        assertTrue(repo.existsOriginal(1));
        repo.upsert(1, "summary", "总结");
        assertEquals(java.util.List.of(1L), repo.findPointIdsWithOriginal(1));
    }

    @Test
    void deleteByPointRemovesBothRoles() {
        repo.upsert(1, "original", "o");
        repo.upsert(1, "summary", "s");
        repo.deleteByPoint(1);
        assertNull(repo.find(1, "original"));
        assertNull(repo.find(1, "summary"));
    }
}
