package com.drillnotebook.app.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

@Component
public class PortablePathResolver {
    private final Path root;

    public PortablePathResolver() {
        this.root = resolveRoot();
    }

    public static Path resolveRoot() {
        String property = System.getProperty("app.root");
        if (property != null && !property.isBlank()) return Paths.get(property).toAbsolutePath().normalize();
        String environment = System.getenv("APP_ROOT");
        if (environment != null && !environment.isBlank()) return Paths.get(environment).toAbsolutePath().normalize();
        return Paths.get(".").toAbsolutePath().normalize();
    }

    public Path root() { return root; }
    public Path data() { return root.resolve("data"); }
    public Path database() { return data().resolve("study.db"); }
    public Path runtime() { return root.resolve("runtime"); }
}
