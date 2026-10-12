package com.drillnotebook.app.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 测试临时工作区：一律建在 APP_ROOT 的 tmp/ 下（可用 -Ddrill.notebook.test.tmp 改到别处），
 * 不写系统 %TEMP%，与绿色便携约束一致。
 */
public final class TestWorkspace {
    private TestWorkspace() {
    }

    public static Path scratchDirectory(String prefix) throws IOException {
        Path module = Paths.get(System.getProperty("basedir", System.getProperty("user.dir", ".")))
                .toAbsolutePath().normalize();
        Path repoRoot = module.getParent() == null ? module : module.getParent();
        Path base = Paths.get(System.getProperty("drill.notebook.test.tmp", repoRoot.resolve("tmp").toString()));
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix);
    }

    /**
     * Windows 上刚关闭的 SQLite 连接会把文件留在「待删除」状态几百毫秒，目录此时删不掉；
     * 收尾短暂重试，避免把清理竞态算成产品失败。
     */
    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                deleteOnce(root);
                return;
            } catch (IOException error) {
                last = error;
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) throw last;
    }

    private static void deleteOnce(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
