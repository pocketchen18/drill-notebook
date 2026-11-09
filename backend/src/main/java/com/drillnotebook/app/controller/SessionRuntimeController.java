package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.SessionDispatchService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/sessions")
public class SessionRuntimeController {
    private final SessionDispatchService dispatch;
    public SessionRuntimeController(SessionDispatchService dispatch) { this.dispatch=dispatch; }
    @GetMapping("/{id}/queue") public Map<String,Object> queue(@PathVariable long id){return dispatch.queueView(id);}
    @PostMapping("/{id}/queue") public Map<String,Object> submit(@PathVariable long id,@RequestBody Map<String,Object> body){return dispatch.submitHuman(id,body);}
    @PostMapping("/{id}/queue/pause") public Map<String,Object> pause(@PathVariable long id,@RequestBody Map<String,Object> body){return dispatch.pauseQueue(id,Boolean.TRUE.equals(body.get("paused")));}
    @PostMapping("/{id}/queue/{requestId}/action") public Map<String,Object> action(@PathVariable long id,@PathVariable String requestId,@RequestBody Map<String,Object> body){return dispatch.queueAction(id,requestId,body);}
    @GetMapping("/runtime") public Map<String,Object> runtime(@RequestParam(required=false) Long sessionId) { return dispatch.runtime(sessionId); }
    @PostMapping("/{id}/stop") public Map<String,Object> stop(@PathVariable long id,@RequestBody Map<String,Object> body) {
        return dispatch.stop(id,(String)body.get("expectedRequestId"));
    }
}
