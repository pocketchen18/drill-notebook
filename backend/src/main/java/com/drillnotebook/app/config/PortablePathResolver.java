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
    /** Model storage directory (user-downloaded models). */
    public Path models() { return data().resolve("models"); }
    /** Embedding model directory under models. */
    public Path embeddingModels() { return models().resolve("embeddings"); }
    /** FastEmbed cache directory, mapped to FASTEMBED_CACHE_DIR. */
    public Path fastembedCache() { return root.resolve("cache").resolve("fastembed"); }
    /** Worker PID file directory. */
    public Path pidDir() { return runtime().resolve("pids"); }
    /** Worker log (stderr) directory. */
    public Path workerLogs() { return root.resolve("logs").resolve("worker"); }
    /** Workspace-local temp directory. */
    public Path tempDir() { return root.resolve("temp"); }
}
