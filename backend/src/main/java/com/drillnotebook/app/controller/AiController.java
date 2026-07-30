package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.AiService;
import com.drillnotebook.app.service.RetrievalMaintenanceService;
import com.drillnotebook.app.service.RetrievalStatusService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiService ai;
    private final RetrievalStatusService retrievalStatus;
    private final RetrievalMaintenanceService retrievalMaintenance;

    public AiController(
            AiService ai,
            RetrievalStatusService retrievalStatus,
            RetrievalMaintenanceService retrievalMaintenance) {
        this.ai = ai;
        this.retrievalStatus = retrievalStatus;
        this.retrievalMaintenance = retrievalMaintenance;
    }

    @GetMapping("/retrieval/status")
    public Map<String, Object> retrievalStatus(
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) Long notebookId) {
        return retrievalStatus.status(scope, notebookId);
    }

    @PostMapping("/retrieval/reindex")
    public ResponseEntity<Map<String, Object>> retrievalReindex(
            @RequestBody(required = false) Map<String, Object> body) {
        RetrievalMaintenanceService.ApiResult result =
                retrievalMaintenance.reindex(body == null ? Map.of() : body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/retrieval/retry-failed")
    public Map<String, Object> retrievalRetryFailed(
            @RequestBody(required = false) Map<String, Object> body) {
        return retrievalMaintenance.retryFailed(body == null ? Map.of() : body);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return ai.redactedConfig();
    }

    @PutMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        return ai.saveConfig(body);
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        return ai.chat(body);
    }

    @PostMapping("/summarize")
    public Map<String, Object> summarize(@RequestBody Map<String, Object> body) {
        return ai.summarize(body);
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> messages() {
        return ai.messages();
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return ai.listSessions(includeArchived);
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, Object> body) {
        return ai.createSession(body == null ? Map.of() : body);
    }

    @PutMapping("/sessions/{id}")
    public Map<String, Object> updateSession(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ai.updateSession(id, body);
    }

    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable long id) {
        ai.deleteSession(id);
    }

    @GetMapping("/sessions/{id}/messages")
    public List<Map<String, Object>> sessionMessages(@PathVariable long id, @RequestParam(required = false) String masterPassword) {
        return ai.sessionMessages(id, masterPassword == null ? "" : masterPassword);
    }

    @GetMapping("/sessions/{id}/export")
    public Map<String, Object> exportSession(
            @PathVariable long id,
            @RequestParam(defaultValue = "md") String format,
            @RequestParam(required = false) String masterPassword) {
        return ai.exportSession(id, format, masterPassword == null ? "" : masterPassword);
    }
}
