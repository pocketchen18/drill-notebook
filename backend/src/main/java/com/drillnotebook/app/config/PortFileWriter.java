package com.drillnotebook.app.config;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PortFileWriter {
    private final PortablePathResolver paths;
    private Path portFile;

    public PortFileWriter(PortablePathResolver paths) { this.paths = paths; }

    @EventListener(ApplicationReadyEvent.class)
    public void writePort(ApplicationReadyEvent event) throws IOException {
        // A mock web environment (e.g. @SpringBootTest with webEnvironment=MOCK)
        // does not start a real server, so there is no port to publish. Guard the
        // cast instead of assuming a servlet web server is always present.
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext webContext)) {
            return;
        }
        int port = webContext.getWebServer().getPort();
        Files.createDirectories(paths.runtime());
        portFile = paths.runtime().resolve("backend.port");
        Files.writeString(portFile, String.valueOf(port), StandardCharsets.UTF_8);
        System.out.println("BACKEND_PORT=" + port);
        System.out.flush();
    }

    @PreDestroy
    public void removePortFile() throws IOException {
        if (portFile != null) Files.deleteIfExists(portFile);
    }
}
