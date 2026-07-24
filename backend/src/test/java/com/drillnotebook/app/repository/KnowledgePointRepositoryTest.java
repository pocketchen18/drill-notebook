package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class KnowledgePointRepositoryTest {
    @Test
    void createsUpdatesLinksAndDeletesKnowledgePoint() throws Exception {
        var root = Files.createTempDirectory("knowledge-repository-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        long bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        jdbc.update("INSERT INTO question_bank(name) VALUES ('Other Bank')");
        long otherBankId = jdbc.queryForObject("SELECT id FROM question_bank WHERE name = 'Other Bank'", Long.class);
        jdbc.update("INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', 'Question', 'A')", bankId);
        long questionId = jdbc.queryForObject("SELECT id FROM question", Long.class);
        jdbc.update("INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', 'Other question', 'A')", otherBankId);
        long otherQuestionId = jdbc.queryForObject("SELECT id FROM question WHERE bank_id = ?", Long.class, otherBankId);
        KnowledgePointRepository repository = new KnowledgePointRepository(jdbc, new ObjectMapper());

        long id = repository.insert(bankId, "JVM", "Heap", "Java", List.of("memory"), List.of(), List.of(questionId, questionId));
        assertEquals("JVM", repository.findById(id).title);
        assertEquals(List.of(questionId), repository.questionIds(id));
        assertThrows(IllegalArgumentException.class, () -> repository.update(id, "JVM", "Heap", "Java", List.of(), List.of(), List.of(otherQuestionId)));
        assertThrows(IllegalArgumentException.class, () -> repository.update(id, "JVM", "Heap", "Java", List.of(), List.of(), List.of(999999L)));
        assertEquals(List.of(questionId), repository.questionIds(id));
        repository.update(id, "JVM memory", "Heap and stack", "Java", List.of("JVM"), List.of(), List.of());
        assertEquals("JVM memory", repository.findById(id).title);
        assertEquals(List.of(), repository.questionIds(id));
        repository.delete(id);
        assertEquals(0, repository.findAll(bankId).size());
    }
}
