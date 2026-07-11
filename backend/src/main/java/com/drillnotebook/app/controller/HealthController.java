package com.drillnotebook.app.controller;

import com.drillnotebook.app.config.PortablePathResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final PortablePathResolver paths;

    public HealthController(PortablePathResolver paths) { this.paths = paths; }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "appRoot", paths.root().toString(), "dbPath", paths.database().toString());
    }
}
