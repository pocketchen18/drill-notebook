package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.QuestionRepository;
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
public class NotebookController {
    private final NotebookRepository notebooks;
    private final QuestionRepository questions;

    public NotebookController(NotebookRepository notebooks, QuestionRepository questions) { this.notebooks = notebooks; this.questions = questions; }

    @GetMapping("/notebooks")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = notebooks.findAll();
        if (result.isEmpty()) {
            long id = notebooks.insert("默认笔记本");
            notebooks.insertPage(id, "开始这里", null);
            result = notebooks.findAll();
        }
        return result;
    }

    @PostMapping("/notebooks")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) { String title = required(body, "title"); long id = notebooks.insert(title); notebooks.insertPage(id, "未命名页面", null); return notebooks.find(id); }
    @GetMapping("/notebooks/{id}")
    public Map<String, Object> get(@PathVariable long id) { return findNotebook(id); }
    @PutMapping("/notebooks/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) { findNotebook(id); notebooks.update(id, required(body, "title")); return notebooks.find(id); }
    @DeleteMapping("/notebooks/{id}")
    public void delete(@PathVariable long id) { findNotebook(id); notebooks.delete(id); }

    @GetMapping("/notebooks/{id}/pages")
    public List<Map<String, Object>> pages(@PathVariable long id) { findNotebook(id); return notebooks.findPages(id); }
    @PostMapping("/notebooks/{id}/pages")
    public Map<String, Object> createPage(@PathVariable long id, @RequestBody Map<String, Object> body) { findNotebook(id); long pageId = notebooks.insertPage(id, required(body, "title"), body.get("content")); return notebooks.findPage(pageId); }
    @GetMapping("/note-pages/{id}")
    public Map<String, Object> page(@PathVariable long id) { return findPage(id); }
    @PutMapping("/note-pages/{id}")
    public Map<String, Object> updatePage(@PathVariable long id, @RequestBody Map<String, Object> body) { findPage(id); notebooks.updatePage(id, body.get("title") == null ? null : String.valueOf(body.get("title")), body.get("content")); return findPage(id); }
    @DeleteMapping("/note-pages/{id}")
    public void deletePage(@PathVariable long id) { findPage(id); notebooks.deletePage(id); }

    @PostMapping({"/notes/pages/{pageId}/questions/{questionId}", "/notes/{pageId}/questions/{questionId}"})
    public Map<String, Object> addQuestion(@PathVariable long pageId, @PathVariable long questionId) {
        findPage(pageId);
        try { QuestionRecord question = questions.findById(questionId); return notebooks.addQuestionSnapshot(pageId, question); } catch (EmptyResultDataAccessException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在"); }
    }

    private Map<String, Object> findNotebook(long id) { try { return notebooks.find(id); } catch (EmptyResultDataAccessException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记本不存在"); } }
    private Map<String, Object> findPage(long id) { try { return notebooks.findPage(id); } catch (EmptyResultDataAccessException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在"); } }
    private static String required(Map<String, Object> body, String key) { Object value = body.get(key); if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("缺少 " + key); return String.valueOf(value).trim(); }
}
