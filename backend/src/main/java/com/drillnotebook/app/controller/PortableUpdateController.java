package com.drillnotebook.app.controller;

import com.drillnotebook.app.config.PortableUpdateRequestGate;
import com.drillnotebook.app.service.SessionDispatchService;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Main-process-only capability; the renderer never receives the process-scoped key or a shutdown endpoint. */
@RestController
@RequestMapping("/api/app/update")
public class PortableUpdateController {
    private final String capability;
    private final PortableUpdateRequestGate gate;
    private final SessionDispatchService dispatch;
    private final ConfigurableApplicationContext context;
    private final ActiveTurnRegistry turns;

    public PortableUpdateController(@Value("${DRILL_UPDATE_EXIT_TOKEN:}") String capability,
            PortableUpdateRequestGate gate, SessionDispatchService dispatch, ConfigurableApplicationContext context, ActiveTurnRegistry turns) {
        this.capability = capability; this.gate = gate; this.dispatch = dispatch; this.context = context; this.turns = turns;
    }
    private boolean authorized(String value) {
        return value != null && !capability.isBlank() && MessageDigest.isEqual(
                capability.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready(@RequestHeader(value="X-Drill-Update-Exit", required=false) String value) {
        if (!authorized(value)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ready", false));
        boolean ready = !gate.hasWrites() && turns.size() == 0 && dispatch.readyForPortableUpdate();
        return ResponseEntity.ok(Map.of("ready", ready, "reason", ready ? "" : "仍有业务操作或 AI 任务在执行，请完成或停止后继续更新。"));
    }
    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown(@RequestHeader(value="X-Drill-Update-Exit", required=false) String value) {
        if (!authorized(value)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("accepted", false));
        if (!gate.beginClose()) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("accepted", false, "reason", "仍有业务操作正在保存。"));
        if (turns.size() > 0 || !dispatch.beginPortableUpdateShutdown()) {
            gate.resume();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("accepted", false, "reason", "AI 任务仍在执行，请完成或停止后继续更新。"));
        }
        Thread close = new Thread(() -> {
            try { Thread.sleep(250); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            context.close();
        }, "portable-update-shutdown");
        close.setDaemon(false);
        close.start();
        return ResponseEntity.ok(Map.of("accepted", true));
    }
}
