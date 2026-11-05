package com.drillnotebook.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 聊天请求体上限（contracts §4.3）：20 MiB 之外既不能进服务层，也一字节都不该往外读。
 */
class ChatBodyLimitFilterTest {
    private final ChatBodyLimitFilter filter = new ChatBodyLimitFilter(new ObjectMapper());

    @Test
    void declaredOversizeBodyIsRejectedBeforeTheChainRuns() throws Exception {
        // MockHttpServletRequest 只认实际字节数，所以直接声明长度：拒绝必须发生在读体之前，
        // 测试也因此不必真的分配 20 MiB 堆
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat") {
            @Override
            public long getContentLengthLong() {
                return ChatBodyLimitFilter.MAX_CHAT_BODY_BYTES + 1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertFalse(chained.get(), "超限请求不能进控制器，也就绝不可能外发");
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("REQUEST_BODY_TOO_LARGE"), response.getContentAsString());
    }

    @Test
    void smallChatBodyPassesThroughAndStaysReadable() throws Exception {
        byte[] payload = "{\"input\":{\"userText\":\"你好\"}}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = chatRequest();
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();
        AtomicReference<byte[]> read = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            chained.set(true);
            read.set(((jakarta.servlet.http.HttpServletRequest) req).getInputStream().readAllBytes());
        });

        assertTrue(chained.get());
        assertArrayEquals(payload, read.get(), "计数包装不能吞字节");
        assertEquals(200, response.getStatus());
    }

    @Test
    void onlyChatPostsAreLimited() {
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/ai/summarize");
        assertTrue(filter.shouldNotFilter(other), "限额只管聊天入口，别的接口不受影响");
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/ai/chat");
        assertTrue(filter.shouldNotFilter(get));
        assertFalse(filter.shouldNotFilter(chatRequest()));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/ai/chat/stream")));
    }

    @Test
    void countingStreamStopsRightPastTheCap() {
        ServletInputStream endless = new ServletInputStream() {
            @Override
            public int read() {
                return 'A';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'A');
                return length;
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // 测试里只走同步读取
            }
        };
        java.util.concurrent.atomic.AtomicBoolean tripped = new java.util.concurrent.atomic.AtomicBoolean();
        ChatBodyLimitFilter.LimitedInputStream counted =
                new ChatBodyLimitFilter.LimitedInputStream(endless, () -> tripped.set(true));
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        String message = null;

        try {
            while (true) {
                total += counted.read(buffer, 0, buffer.length);
            }
        } catch (IOException error) {
            message = error.getMessage();
        }

        assertEquals("请求体超过 20 MiB 上限", message);
        // 调用方拿到的字节永远不超过上限：越界的那一次读取直接抛，不会先把数据交出去
        assertEquals(ChatBodyLimitFilter.MAX_CHAT_BODY_BYTES, total);
        // 旗标是 413 改判的唯一依据：异常交回 Spring 时会被包成"参数解析失败"，认不出原始类型
        assertTrue(tripped.get(), "越界必须在请求上留下旗标");
    }

    private static MockHttpServletRequest chatRequest() {
        return new MockHttpServletRequest("POST", "/api/ai/chat");
    }
}
