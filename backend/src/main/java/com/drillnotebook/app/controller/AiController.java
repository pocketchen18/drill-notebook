package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.AiService;
import com.drillnotebook.app.service.ChatHistoryService;
import com.drillnotebook.app.service.ChatStorageService;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.drillnotebook.app.service.RetrievalMaintenanceService;
import com.drillnotebook.app.service.RetrievalStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ChatHistoryService history;
    private final ChatStorageService storage;
    private final RetrievalStatusService retrievalStatus;
    private final RetrievalMaintenanceService retrievalMaintenance;
    /** SSE 流式推送专用执行器：有界固定池（命名守护线程），避免裸 new Thread 绕过监控与资源回收。 */
    private final ExecutorService chatStreamExecutor = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "ai-chat-stream");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectMapper mapper;

    public AiController(
            AiService ai,
            ChatHistoryService history,
            ChatStorageService storage,
            RetrievalStatusService retrievalStatus,
            RetrievalMaintenanceService retrievalMaintenance,
            ObjectMapper mapper) {
        this.ai = ai;
        this.history = history;
        this.storage = storage;
        this.retrievalStatus = retrievalStatus;
        this.retrievalMaintenance = retrievalMaintenance;
        this.mapper = mapper;
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

    /** 流式聊天（SSE）：event=metadata|text|reasoning|done|error，data 为 JSON。 */
    @PostMapping(value = "/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chatStream(@RequestBody Map<String, Object> body) {
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(300_000L);
        chatStreamExecutor.submit(() -> {
            // 本轮是否产出过增量：error 帧要如实带 partial，前端据此判断能不能自动回退（§7.3）
            boolean[] produced = {false};
            try {
                AiService.ChatResult result = ai.chatStream(body, (type, text) -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name(type)
                                .data(mapperValueToJson(Map.of("text", text))));
                        produced[0] = true;
                        return true;
                    } catch (Exception sendError) {
                        return false; // 下游断开，中止上游读取
                    }
                }, metadata -> {
                    try {
                        // 首个 metadata 只是 prepared/not_sent：发送状态与最终 planId 以后端结果为准（§4.2）
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("metadata")
                                .data(mapperValueToJson(metadata)));
                    } catch (Exception ignored) {
                        // 推送失败不撤销本轮：前端断了由 onDelta 的 false 负责裁决
                    }
                });
                // done 与 POST /chat 同一份字段集，由服务层单点构造；这里只补流式才有的 reasoning。
                // 以前控制器自己拼 done，漏了 requestId/sessionId/protocolVersion/replayed 与 dispatchState。
                Map<String, Object> donePayload = new java.util.LinkedHashMap<>(result.resultView());
                if (result.reasoning() != null && !result.reasoning().isBlank()) {
                    donePayload.put("reasoning", result.reasoning());
                }
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("done")
                        .data(mapperValueToJson(donePayload)));
                emitter.complete();
            } catch (Exception error) {
                try {
                    Map<String, Object> frame = new java.util.LinkedHashMap<>();
                    frame.put("message", error.getMessage() == null ? "AI 服务暂时不可用" : error.getMessage());
                    frame.put("partial", produced[0]);
                    if (error instanceof AiService.StreamFailure failure) {
                        // 身份 + 收尾事实：只回一句 message 的话，前端分不清"这轮已作废"还是"这轮还在跑"（§4.2 末段）
                        if (failure.requestId() != null && !failure.requestId().isBlank()) {
                            frame.put("requestId", failure.requestId());
                        }
                        frame.put("sessionId", failure.sessionId());
                        if (failure.code() != null) frame.put("code", failure.code());
                        frame.put("persistenceStatus", failure.persistenceStatus());
                        frame.put("stateDurable", failure.stateDurable());
                    } else if (error instanceof ChatTurnRejectException reject) {
                        // begin() 之前就被拒（请求形状不对）：这一轮还不存在，只有 code
                        frame.put("code", reject.code());
                    }
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("error")
                            .data(mapperValueToJson(frame)));
                } catch (Exception ignored) {
                    // 下游已断开
                }
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    private String mapperValueToJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    /** 一键拉取可用模型列表。body: purpose?。 */
    @PostMapping("/models")
    public Map<String, Object> models(@RequestBody(required = false) Map<String, Object> body) {
        return Map.of("models", ai.listModels(body == null ? Map.of() : body));
    }

    /** 模型测活。body: prompt? baseUrl? model? apiKey? apiFormat? purpose? masterPassword?。 */
    @PostMapping("/models/test")
    public Map<String, Object> testModel(@RequestBody Map<String, Object> body) {
        return ai.testModel(body);
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

    /**
     * 只读历史分页（contracts §3）：按稳定 message ID seek，主密码只从进程内的会话解锁状态取，
     * 不接受 query 里传密码——旧 {@code /messages} 入口仅保留给旧客户端。
     */
    @GetMapping("/sessions/{id}/history")
    public Map<String, Object> sessionHistory(@PathVariable long id,
                                              @RequestParam(required = false) Long beforeMessageId,
                                              @RequestParam(required = false) Long afterMessageId,
                                              @RequestParam(defaultValue = "50") int limit) throws SQLException {
        return history.page(id, beforeMessageId, afterMessageId, limit);
    }

    /**
     * 只读容量快照（contracts §2.7、§3）：逻辑落库字节、预留、可配额度与 80% 预警。
     * 不把物理 DB 大小冒充逻辑计数，也不在读取时重建账本。
     */
    @GetMapping("/history/storage")
    public Map<String, Object> historyStorage() throws SQLException {
        return storage.snapshot();
    }

    /** 显式改额度（contracts §3）：CAS 基线是账本 revision，只改配置，一条记录都不清。 */
    @PutMapping("/history/storage")
    public Map<String, Object> updateHistoryStorage(@RequestBody Map<String, Object> body) throws SQLException {
        return storage.updateLimits(body);
    }

    /**
     * 清理预览（contracts §3）：只读，把"边界内最多 200 个完整轮次 + legacy 展示行"钉成一份范围，
     * 连同字节与 2 分钟有效的 previewId 一起回给界面。删任何东西都只发生在下一个入口。
     */
    @PostMapping("/history/cleanup/preview")
    public Map<String, Object> historyCleanupPreview(@RequestBody Map<String, Object> body) throws SQLException {
        return storage.previewCleanup(body);
    }

    /**
     * 执行清理（contracts §3）：原子删掉 previewId 钉住的那一份原文，不可撤销。
     * 范围变了 409、预览过期 410、confirm 不为 true 400，重复 operationId 只生效一次。
     */
    @PostMapping("/history/cleanup")
    public Map<String, Object> historyCleanup(@RequestBody Map<String, Object> body) throws SQLException {
        return storage.cleanup(body);
    }

    @GetMapping("/sessions/{id}/export")
    public Map<String, Object> exportSession(
            @PathVariable long id,
            @RequestParam(defaultValue = "md") String format,
            @RequestParam(required = false) String masterPassword) {
        return ai.exportSession(id, format, masterPassword == null ? "" : masterPassword);
    }
}
