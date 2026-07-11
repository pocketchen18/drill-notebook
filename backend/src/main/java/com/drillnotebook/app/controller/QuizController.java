package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.QuizService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quiz")
public class QuizController {
    private final QuizService quiz;

    public QuizController(QuizService quiz) { this.quiz = quiz; }

    @PostMapping("/sessions")
    public Map<String, Object> start(@RequestBody Map<String, Object> body) { return quiz.start(body); }

    @PostMapping("/sessions/{sessionId}/submit")
    public Map<String, Object> submit(@PathVariable String sessionId, @RequestBody Map<String, Object> body) { return quiz.submit(sessionId, body); }

    @GetMapping("/sessions/{sessionId}/summary")
    public Map<String, Object> summary(@PathVariable String sessionId) { return quiz.summary(sessionId); }

    @GetMapping("/wrong")
    public List<Map<String, Object>> wrong() { return quiz.wrong(); }
}
