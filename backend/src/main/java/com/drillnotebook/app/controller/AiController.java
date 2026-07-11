package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.AiService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiService ai;
    public AiController(AiService ai) { this.ai = ai; }

    @GetMapping("/config")
    public Map<String, Object> config() { return ai.redactedConfig(); }
    @PutMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) { return ai.saveConfig(body); }
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) { return ai.chat(body); }
    @PostMapping("/summarize")
    public Map<String, Object> summarize(@RequestBody Map<String, Object> body) { return ai.summarize(body); }
    @GetMapping("/messages")
    public List<Map<String, Object>> messages() { return ai.messages(); }
}
