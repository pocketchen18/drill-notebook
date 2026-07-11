package com.drillnotebook.app;

import com.drillnotebook.app.config.PortablePathResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) throws Exception {
        Path root = PortablePathResolver.resolveRoot();
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("logs"));
        Files.createDirectories(root.resolve("runtime"));
        System.setProperty("app.root", root.toString());
        System.setProperty("logging.file.name", root.resolve("logs").resolve("backend.log").toString());
        SpringApplication.run(BackendApplication.class, args);
    }
}
