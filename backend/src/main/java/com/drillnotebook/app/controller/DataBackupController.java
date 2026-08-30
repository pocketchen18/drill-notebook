package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.DataBackupService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 数据管理：备份创建/列表/删除、配置、状态、导出与恢复/导入。 */
@RestController
@RequestMapping("/api/data")
public class DataBackupController {
    private final DataBackupService backups;

    public DataBackupController(DataBackupService backups) {
        this.backups = backups;
    }

    @GetMapping("/backup/config")
    public Map<String, Object> getConfig() {
        return configPayload(backups.loadConfig());
    }

    @PutMapping("/backup/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        try {
            DataBackupService.BackupConfig config = new DataBackupService.BackupConfig(
                    stringOf(body.get("directory")),
                    intOf(body.get("autoIntervalHours")),
                    intOf(body.get("maxCount")),
                    Boolean.TRUE.equals(body.get("lite")));
            return configPayload(backups.saveConfig(config));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @GetMapping("/backup/status")
    public Map<String, Object> status() {
        return backups.status();
    }

    @GetMapping("/backups")
    public List<Map<String, Object>> list() {
        return backups.listBackups();
    }

    @PostMapping("/backups")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        Boolean lite = body == null ? null : (body.get("lite") == null ? null : Boolean.TRUE.equals(body.get("lite")));
        try {
            return backups.createBackup(lite);
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());
        }
    }

    @DeleteMapping("/backups/{name}")
    public void delete(@PathVariable String name) {
        try {
            backups.deleteBackup(name);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @PostMapping("/backups/export")
    public Map<String, Object> export(@RequestBody Map<String, Object> body) {
        try {
            return backups.exportBackup(stringOf(body.get("targetPath")));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());
        }
    }

    @PostMapping("/backups/{name}/restore")
    public Map<String, Object> restore(@PathVariable String name) {
        try {
            return backups.restoreBackup(name);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage());
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }

    @PostMapping("/backups/import")
    public Map<String, Object> importBackup(HttpServletRequest request) throws IOException, ServletException {
        var part = request.getPart("file");
        if (part == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 file 字段");
        String fileName = part.getSubmittedFileName();
        if (fileName == null || !fileName.toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .zip 备份包");
        }
        try (var input = part.getInputStream()) {
            try {
                return backups.importBackup(input);
            } catch (IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
            } catch (IllegalStateException error) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传内容无法读取");
        }
    }

    private static Map<String, Object> configPayload(DataBackupService.BackupConfig config) {
        return Map.of(
                "directory", config.directory() == null ? "" : config.directory(),
                "autoIntervalHours", config.autoIntervalHours(),
                "maxCount", config.maxCount(),
                "lite", config.lite());
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { /* 落入默认校验 */ }
        }
        return -1;
    }
}
