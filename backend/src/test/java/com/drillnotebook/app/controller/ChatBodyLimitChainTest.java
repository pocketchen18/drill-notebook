package com.drillnotebook.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 聊天请求体上限的全链验证（contracts §4.3）：不声明 Content-Length 的 chunked 大 body 也必须拿 413，
 * 而不是被 Spring 的"参数解析失败"兜成 500——那会让前端把"图太大"当成"服务坏了"反复重试（核验发现 1）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatBodyLimitChainTest {
    @LocalServerPort
    private int port;

    @Test
    void chunkedOversizeBodyIsRejectedWith413EndToEnd() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/chat"))
                .header("Content-Type", "application/json")
                // ofInputStream 不发 Content-Length：只能靠计数输入流发现越界，走的正是过滤器兜底那条路
                .POST(HttpRequest.BodyPublishers.ofInputStream(this::oversizedBody))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode(), "越界必须如实报 413，实际 " + response.statusCode()
                + " body=" + response.body());
        assertTrue(response.body().contains("REQUEST_BODY_TOO_LARGE"), response.body());
    }

    /** 前缀 + 反复复用的 1 MiB 块，凑到 21 MiB：不为测试一次性分配更大的连续内存。 */
    private InputStream oversizedBody() {
        byte[] head = "{\"input\":{\"userText\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] chunk = new byte[1024 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'A');
        AtomicInteger remaining = new AtomicInteger(21);
        return new SequenceInputStream(new java.util.Enumeration<>() {
            boolean headDone = false;

            @Override
            public boolean hasMoreElements() {
                return !headDone || remaining.get() > 0;
            }

            @Override
            public InputStream nextElement() {
                if (!headDone) {
                    headDone = true;
                    return new ByteArrayInputStream(head);
                }
                remaining.decrementAndGet();
                return new ByteArrayInputStream(chunk);
            }
        });
    }
}
