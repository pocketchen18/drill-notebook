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

    @Test
    void reordersAllPointsByGivenIdOrder() throws Exception {
        var root = Files.createTempDirectory("knowledge-reorder-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        long bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        KnowledgePointRepository repository = new KnowledgePointRepository(jdbc, new ObjectMapper());

        long a = repository.insert(bankId, "A", "a", null, List.of(), List.of(), List.of());
        long b = repository.insert(bankId, "B", "b", null, List.of(), List.of(), List.of());
        long c = repository.insert(bankId, "C", "c", null, List.of(), List.of(), List.of());

        repository.reorderAll(bankId, List.of(c, a, b));
        assertEquals(List.of(c, a, b), repository.findAll(bankId).stream().map(p -> p.id).toList());
        assertEquals(0, repository.findById(c).sortIndex);
        assertEquals(1, repository.findById(a).sortIndex);
        assertEquals(2, repository.findById(b).sortIndex);

        assertThrows(IllegalArgumentException.class, () -> repository.reorderAll(bankId, List.of(a, b)));
        assertThrows(IllegalArgumentException.class, () -> repository.reorderAll(bankId, List.of(a, b, b)));
        assertThrows(IllegalArgumentException.class, () -> repository.reorderAll(bankId, List.of()));
    }

    @Test
    void migratesExistingDatabaseWithoutSortIndexColumn() throws Exception {
        var root = Files.createTempDirectory("knowledge-sort-migration-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 模拟老库：knowledge_point 表还没有 sort_index 列，且已有一条数据
        jdbc.update("CREATE TABLE knowledge_point (id INTEGER PRIMARY KEY AUTOINCREMENT, bank_id INTEGER, title TEXT NOT NULL, content TEXT NOT NULL, category TEXT, tags TEXT, heading_path TEXT, created_at TEXT, updated_at TEXT)");
        jdbc.update("INSERT INTO knowledge_point(title, content) VALUES ('老知识点', '内容')");

        // 初始化器应能对老库做增量迁移（补列 + 建索引），而不是抛 "no such column: sort_index"
        new DatabaseInitializer(dataSource).initialize();

        // sort_index 列已补齐，顺序排序查询可用，且老数据仍在
        List<Long> ids = jdbc.query("SELECT id FROM knowledge_point ORDER BY (sort_index IS NULL), sort_index, id", (rs, row) -> rs.getLong(1));
        assertEquals(List.of(1L), ids);
    }
}
