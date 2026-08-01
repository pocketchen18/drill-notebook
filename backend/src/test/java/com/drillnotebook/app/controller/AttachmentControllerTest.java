package com.drillnotebook.app.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachmentControllerTest {
    @Autowired
    private TestRestTemplate rest;

    @Test
    void uploadAndDownloadRoundTrip() {
        Long pageId = createTestPage();
        Long attachmentId = uploadFile(pageId, "note.txt", "hello-world".getBytes());

        ResponseEntity<byte[]> downloadResp = rest.getForEntity("/api/attachments/" + attachmentId + "/content", byte[].class);
        assertEquals(HttpStatus.OK, downloadResp.getStatusCode());
        assertArrayEquals("hello-world".getBytes(), downloadResp.getBody());

        rest.delete("/api/attachments/" + attachmentId);
        ResponseEntity<Map> afterDelete = rest.getForEntity("/api/attachments/" + attachmentId, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode());
    }

    @Test
    void duplicateSha256ReturnsSameRecord() {
        Long pageId = createTestPage();
        byte[] content = "duplicate-content".getBytes();
        Long firstId = uploadFile(pageId, "a.txt", content);
        Long secondId = uploadFile(pageId, "b.txt", content);
        assertEquals(firstId, secondId, "相同 SHA256 应返回同一条记录");
    }

    private Long uploadFile(Long pageId, String fileName, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> resp = rest.exchange("/api/note-pages/" + pageId + "/attachments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return ((Number) resp.getBody().get("id")).longValue();
    }

    private Long createTestPage() {
        ResponseEntity<Map> nbResp = rest.postForEntity("/api/notebooks", Map.of("title", "test-nb"), Map.class);
        Long nbId = ((Number) nbResp.getBody().get("id")).longValue();
        ResponseEntity<Map> pageResp = rest.postForEntity("/api/notebooks/" + nbId + "/pages", Map.of("title", "test-page"), Map.class);
        return ((Number) pageResp.getBody().get("id")).longValue();
    }
}
