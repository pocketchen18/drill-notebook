package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.AiConnectionsService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/connections")
public class AiConnectionsController {
    private final AiConnectionsService service;
    public AiConnectionsController(AiConnectionsService service) { this.service = service; }
    @GetMapping public Object get() { return service.view(); }
    @GetMapping("/providers/{id}/presets") public Object presets(@PathVariable String id) { return service.presets(id); }
    @PostMapping("/providers") public Object create(@RequestBody Map<String, Object> body) { return service.saveProvider(null, body); }
    @PutMapping("/providers/{id}") public Object update(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.saveProvider(id, body); }
    @DeleteMapping("/providers/{id}") public Object deleteProvider(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.delete(id, true, body); }
    @PostMapping("/providers/{id}/models") public Object addModels(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.saveModels(id, null, body); }
    @PostMapping("/providers/{id}/verify-add") public Object verifyAndAdd(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.verifyAndAdd(id, body); }
    @PutMapping("/models/{id}") public Object updateModel(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.saveModels(null, id, body); }
    @DeleteMapping("/models/{id}") public Object deleteModel(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.delete(id, false, body); }
    @PutMapping("/bindings") public Object bind(@RequestBody Map<String, Object> body) { return service.bind(body); }
    @PostMapping("/providers/{id}/discover") public Object discover(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.discover(id, body); }
    @PostMapping("/models/{id}/test") public Object test(@PathVariable String id, @RequestBody Map<String, Object> body) { return service.test(id, body); }
    @PostMapping("/tests/{id}/cancel") public Object cancelTest(@PathVariable String id) { return service.cancelTest(id); }
}
