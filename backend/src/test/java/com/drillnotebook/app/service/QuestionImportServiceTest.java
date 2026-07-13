package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.repository.QuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class QuestionImportServiceTest {
    @Test
    void skipsNonChoiceQuestionStoredWithLegacyHash() throws Exception {
        var root = Files.createTempDirectory("question-import-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        long bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
        QuestionRepository repository = new QuestionRepository(jdbc, new ObjectMapper());
        MarkdownQuestionParser parser = new MarkdownQuestionParser();
        String source = """
                ---
                type: fill
                answer: JVM
                ---
                ### 题干
                Java 虚拟机的缩写是什么？
                """;
        MarkdownQuestionParser.ParsedQuestion item = parser.parse(source).get(0);
        repository.insert(bankId, item.type(), item.stem(), repository.optionsJson(item.options()), item.answer(), item.analysis(), item.difficulty(), repository.tagsJson(item.tags()), item.chapter(), item.groupId(), item.orderInGroup(), QuestionImportService.legacyHash(item));

        QuestionImportService.ImportResult result = new QuestionImportService(parser, repository).importMarkdown(bankId, source);

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failed());
        assertEquals(1, repository.findByBank(bankId).size());
    }
}
