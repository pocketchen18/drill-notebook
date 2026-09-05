package com.drillnotebook.app.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.QuestionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * 错题本成员规则（频率统计 + 连续答对自动清出 + 手动移出）的真库回归。
 * 规则：wrongCount>0 且 wrong_excluded=0 且「最近一次答错后连续答对次数 < 2」。
 */
class QuestionWrongBookTest {
    private JdbcTemplate jdbc;
    private QuestionRepository questions;
    private long bankId;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("wrong-book-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        questions = new QuestionRepository(jdbc, new ObjectMapper());
        jdbc.update("INSERT INTO question_bank(name) VALUES ('Bank')");
        bankId = jdbc.queryForObject("SELECT id FROM question_bank", Long.class);
    }

    private long newQuestion(String stem) {
        jdbc.update("INSERT INTO question(bank_id, type, stem, answer) VALUES (?, 'single', ?, 'A')", bankId, stem);
        return jdbc.queryForObject("SELECT id FROM question ORDER BY id DESC LIMIT 1", Long.class);
    }

    private void wrong(long id) {
        questions.recordAnswer(id, "B", false, 0, "s", "deterministic", null);
    }

    private void right(long id) {
        questions.recordAnswer(id, "A", true, 0, "s", "deterministic", null);
    }

    private boolean inBook(long id) {
        return questions.wrongQuestions().stream().anyMatch((question) -> question.id == id);
    }

    private Map<String, Object> bookRow(long id) {
        return questions.wrongBook().stream()
                .filter((row) -> ((Number) row.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void wrongOnce_appearsWithCountOne() {
        long id = newQuestion("甲");
        wrong(id);
        assertTrue(inBook(id));
        assertEquals(1, ((Number) bookRow(id).get("wrongCount")).intValue());
    }

    @Test
    void wrongThenOneCorrect_stillInBook_avoidsRepeatUntilCorrectTrap() {
        long id = newQuestion("乙");
        wrong(id);
        right(id); // 最新一次为对：旧「最近一次答错且未纠正」口径会清出，新规则应保留（连续答对 1 < 2）
        assertTrue(inBook(id), "答对一次不应立即清出");
        assertEquals(1, ((Number) bookRow(id).get("wrongCount")).intValue());
    }

    @Test
    void wrongThenTwoCorrects_autoCleared() {
        long id = newQuestion("丙");
        wrong(id);
        right(id);
        right(id); // 连续答对满 2 次 → 自动清出
        assertFalse(inBook(id), "连续答对 2 次后自动清出");
    }

    @Test
    void manualExclude_hidesUntilNextWrong() {
        long id = newQuestion("丁");
        wrong(id);
        questions.setWrongExcluded(id, true);
        assertFalse(inBook(id), "手动移出后不在错题本");
        wrong(id); // 再次答错由 recordAnswer 重置 wrong_excluded
        assertTrue(inBook(id), "再次答错应重新计入错题本");
    }

    @Test
    void orderedByWrongCountDescending() {
        long a = newQuestion("A");
        long b = newQuestion("B");
        wrong(a);
        wrong(b);
        wrong(b);
        wrong(b);
        List<QuestionRecord> book = questions.wrongQuestions();
        assertEquals(2, book.size());
        assertEquals(b, book.get(0).id, "错误次数多的排前");
        assertEquals(a, book.get(1).id);
    }

    @Test
    void errorRateReflectsWrongOverAttempts() {
        long id = newQuestion("戊");
        wrong(id);
        wrong(id);
        right(id); // 2 错 / 3 作答
        Map<String, Object> row = bookRow(id);
        assertEquals(2, ((Number) row.get("wrongCount")).intValue());
        assertEquals(3, ((Number) row.get("attemptCount")).intValue());
        assertEquals(0.6667, ((Number) row.get("errorRate")).doubleValue(), 0.0001);
    }
}
