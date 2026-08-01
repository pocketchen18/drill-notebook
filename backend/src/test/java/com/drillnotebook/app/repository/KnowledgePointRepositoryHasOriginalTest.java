package com.drillnotebook.app.repository;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.KnowledgePointRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgePointRepositoryHasOriginalTest {

    private KnowledgePointRepository repo;
    private KnowledgePointOriginalRepository originals;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        Path tmp = java.nio.file.Files.createTempFile("study", ".db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.toAbsolutePath());
        new DatabaseInitializer(ds).initialize();
        jdbc = new JdbcTemplate(ds);
        repo = new KnowledgePointRepository(jdbc, new ObjectMapper());
        originals = new KnowledgePointOriginalRepository(jdbc);
        jdbc.update("INSERT INTO question_bank(name) VALUES ('b')");
        jdbc.update("INSERT INTO knowledge_point(bank_id, title, content, tags, heading_path) VALUES (1, 'T1', 'c1', '[]', '[]')");
        jdbc.update("INSERT INTO knowledge_point(bank_id, title, content, tags, heading_path) VALUES (1, 'T2', 'c2', '[]', '[]')");
        originals.upsert(1, "original", "原始1");
    }

    @Test
    void findAllReturnsHasOriginalFlag() {
        List<KnowledgePointRecord> cards = repo.findAll(1L);
        assertEquals(2, cards.size());
        KnowledgePointRecord card1 = cards.stream().filter(c -> c.id == 1L).findFirst().orElseThrow();
        KnowledgePointRecord card2 = cards.stream().filter(c -> c.id == 2L).findFirst().orElseThrow();
        assertTrue(card1.hasOriginal, "卡1已存原文应 hasOriginal=true");
        assertFalse(card2.hasOriginal, "卡2未存原文应 hasOriginal=false");
    }
}
