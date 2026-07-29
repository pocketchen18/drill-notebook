package com.drillnotebook.app.controller;

import com.drillnotebook.app.repository.AttachmentRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.service.AttachmentStorageService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AttachmentController {
    private final AttachmentRepository attachments;
    private final AttachmentStorageService storage;
    private final NotebookRepository notebooks;

    public AttachmentController(AttachmentRepository attachments, AttachmentStorageService storage, NotebookRepository notebooks) {
        this.attachments = attachments;
        this.storage = storage;
        this.notebooks = notebooks;
    }

    @PostMapping("/note-pages/{pageId}/attachments")
    public Map<String, Object> upload(@PathVariable long pageId, HttpServletRequest request) throws IOException, ServletException {
        notebooks.findPage(pageId);
        var part = request.getPart("file");
        if (part == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 file 字段");

        String fileName = part.getSubmittedFileName();
        if (fileName == null || fileName.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名为空");

        try (InputStream input = part.getInputStream()) {
            var storeResult = storage.store(pageId, fileName, input);
            var existing = attachments.findBySha256(pageId, storeResult.sha256());
            if (existing != null) {
                storage.deleteFile(storeResult.storagePath());
                return existing;
            }
            long id = attachments.insert(pageId, fileName, storeResult.storagePath(), storeResult.mimeType(), storeResult.fileSize(), storeResult.sha256());
            return attachments.findById(id);
        }
    }

    @GetMapping("/note-pages/{pageId}/attachments")
    public List<Map<String, Object>> listByPage(@PathVariable long pageId) {
        notebooks.findPage(pageId);
        return attachments.findByPageId(pageId);
    }

    @GetMapping("/attachments/{id}")
    public Map<String, Object> getMeta(@PathVariable long id) {
        var found = attachments.findById(id);
        if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        return found;
    }

    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<InputStreamResource> getContent(@PathVariable long id) throws IOException {
        var found = attachments.findById(id);
        if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        String storagePath = (String) found.get("storagePath");
        String mimeType = (String) found.get("mimeType");
        long size = storage.fileSize(storagePath);
        InputStream stream = storage.openInputStream(storagePath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeType));
        headers.setContentLength(size);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        return new ResponseEntity<>(new InputStreamResource(stream), headers, HttpStatus.OK);
    }

    @DeleteMapping("/attachments/{id}")
    public void delete(@PathVariable long id) throws IOException {
        var found = attachments.findById(id);
        if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        String storagePath = (String) found.get("storagePath");
        storage.deleteFile(storagePath);
        attachments.delete(id);
    }
}
