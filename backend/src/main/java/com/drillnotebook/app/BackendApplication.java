package com.drillnotebook.app;

import com.drillnotebook.app.config.PortablePathResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {
    public static void main(String[] args) throws Exception {
        Path root = PortablePathResolver.resolveRoot();
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("logs"));
        Files.createDirectories(root.resolve("runtime"));
        // 绿色便携兜底：裸 java -jar 启动（不带 -Djava.io.tmpdir 参数）时，
        // multipart 上传 spool、Tomcat 工作目录等 JVM 临时文件必须落在 APP_ROOT，
        // 不允许写系统 %TEMP%（Electron 启动路径已在 java-bridge 中显式传参，此处是双保险）。
        // 须在第一次 createTempFile 之前设置（File 的 TempDirectory 会缓存 tmpdir）。
        Path portableTmp = root.resolve("runtime").resolve("tmp");
        Files.createDirectories(portableTmp);
        System.setProperty("java.io.tmpdir", portableTmp.toString());
        System.setProperty("app.root", root.toString());
        System.setProperty("logging.file.name", root.resolve("logs").resolve("backend.log").toString());
        SpringApplication.run(BackendApplication.class, args);
    }
}
