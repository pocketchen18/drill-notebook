package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.BankRepository;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.service.QuestionImportService;
import com.drillnotebook.app.service.QuestionTypeRules;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class BankController {
    private final BankRepository banks;
    private final QuestionRepository questions;
    private final QuestionImportService importer;

    public BankController(BankRepository banks, QuestionRepository questions, QuestionImportService importer) {
        this.banks = banks; this.questions = questions; this.importer = importer;
    }

    @GetMapping("/banks")
    public List<Map<String, Object>> listBanks() { return banks.findAll(); }

    @PostMapping("/banks")
    public Map<String, Object> createBank(@RequestBody Map<String, Object> body) {
        String name = required(body, "name");
        long id = banks.insert(name, string(body, "description"), stringOr(body, "sourceType", "manual"));
        return banks.find(id);
    }

    @GetMapping("/banks/{id}")
    public Map<String, Object> getBank(@PathVariable long id) { return findBank(id); }

    @PutMapping("/banks/{id}")
    public Map<String, Object> updateBank(@PathVariable long id, @RequestBody Map<String, Object> body) {
        findBank(id);
        banks.update(id, string(body, "name"), string(body, "description"), string(body, "sourceType"));
        return findBank(id);
    }

    @DeleteMapping("/banks/{id}")
    public void deleteBank(@PathVariable long id) { findBank(id); banks.delete(id); }

    @GetMapping("/banks/{id}/questions")
    public List<Map<String, Object>> listQuestions(@PathVariable long id) { findBank(id); return questions.findByBank(id).stream().map((question) -> question.toMap(true)).toList(); }

    @PostMapping("/banks/{id}/questions")
    public Map<String, Object> createQuestion(@PathVariable long id, @RequestBody Map<String, Object> body) {
        findBank(id);
        String type = QuestionTypeRules.requireType(required(body, "type"));
        String stem = required(body, "stem");
        String answer = QuestionTypeRules.canonicalAnswer(type, string(body, "answer"));
        try {
            @SuppressWarnings("unchecked") List<Map<String, String>> options = (List<Map<String, String>>) body.getOrDefault("options", List.of());
            QuestionTypeRules.validate(type, answer, options);
            List<String> tags = body.get("tags") instanceof List<?> values ? values.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).toList() : List.of();
            long questionId = questions.insert(id, type, stem, questions.optionsJson(options), answer, string(body, "analysis"), difficulty(body, "difficulty", 3), questions.tagsJson(tags), string(body, "chapter"), string(body, "groupId"), integerNullable(body, "orderInGroup"), null);
            return questions.findById(questionId).toMap(true);
        } catch (Exception error) { throw new IllegalArgumentException("题目保存失败"); }
    }

    @PutMapping("/questions/{id}")
    public Map<String, Object> updateQuestion(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try { questions.update(id, body); return questions.findById(id).toMap(true); } catch (EmptyResultDataAccessException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在"); } catch (Exception error) { throw new IllegalArgumentException("题目保存失败"); }
    }

    @DeleteMapping("/questions/{id}")
    public void deleteQuestion(@PathVariable long id) { try { questions.findById(id); questions.delete(id); } catch (EmptyResultDataAccessException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在"); } }

    @PostMapping("/banks/{id}/import/markdown")
    public Map<String, Object> importMarkdown(@PathVariable long id, @RequestBody JsonNode body) {
        findBank(id);
        String content = body.isTextual() ? body.asText() : body.path("content").asText(null);
        if (content == null) throw new IllegalArgumentException("缺少 Markdown 内容");
        return importer.importMarkdown(id, content).toMap();
    }

    private Map<String, Object> findBank(long id) {
        try { return banks.find(id); } catch (EmptyResultDataAccessException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题库不存在"); }
    }

    private static String required(Map<String, Object> body, String key) { String value = string(body, key); if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 " + key); return value.trim(); }
    private static String string(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? null : String.valueOf(value); }
    private static String stringOr(Map<String, Object> body, String key, String fallback) { String value = string(body, key); return value == null || value.isBlank() ? fallback : value; }
    private static Integer integerNullable(Map<String, Object> body, String key) { Object value = body.get(key); if (value == null) return null; try { return Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException error) { return null; } }
    private static int difficulty(Map<String, Object> body, String key, int fallback) {
        Object raw = body.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) return fallback;
        final int value;
        try { value = Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("难度必须是 1 到 5 的整数"); }
        if (value < 1 || value > 5) throw new IllegalArgumentException("难度必须是 1 到 5 的整数");
        return value;
    }
}
