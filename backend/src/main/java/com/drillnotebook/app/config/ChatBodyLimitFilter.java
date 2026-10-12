package com.drillnotebook.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 聊天请求体上限（contracts §4.3）：JSON 体含 base64 膨胀最多 20 MiB，超限直接 413，上游零调用。
 *
 * <p>这一层必须走在读体之前：{@code @RequestBody} 会把整个 JSON 缓冲进内存，等服务层再检查就晚了。
 * 声明的 Content-Length 只是第一道；客户端不声明或谎报时，计数流仍会在越界处把读取掐断。</p>
 */
@Component
public class ChatBodyLimitFilter extends OncePerRequestFilter {
    static final long MAX_CHAT_BODY_BYTES = 20L * 1024 * 1024;
    private static final Set<String> LIMITS = Set.of("/api/ai/chat", "/api/ai/chat/stream");
    /** 越界旗标：读体中途抛出的异常会被 Spring 归成"参数解析失败"，所以 413 的改判依据放在请求上。 */
    public static final String TRIP_ATTRIBUTE = "drillnotebook.chatBodyTooLarge";
    public static final String ERROR_CODE = "REQUEST_BODY_TOO_LARGE";
    public static final String ERROR_MESSAGE = "请求体超过 20 MiB 上限，请减少附件数量或改用更小的图片";

    /** 读取越界：与 §4.3 的 413 同一口径，消息里带上上限便于前端提示。 */
    public static class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("请求体超过 20 MiB 上限");
        }
    }

    private final ObjectMapper mapper;

    public ChatBodyLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LIMITS.contains(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_CHAT_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        // 与 ApiExceptionHandler 同形状，前端一处解析就能拿到稳定错误码
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), Map.of(
                "error", ERROR_CODE, "errorCode", ERROR_CODE, "message", ERROR_MESSAGE));
    }

    /** 只包装输入流：边读边计数，越界即在请求上记旗标并抛，避免无声明长度时把整份大 body 读进内存。 */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(),
                    () -> setAttribute(TRIP_ATTRIBUTE, Boolean.TRUE));
        }
    }

    static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final Runnable onTrip;
        private long read;

        LimitedInputStream(ServletInputStream delegate) {
            this(delegate, () -> {
            });
        }

        LimitedInputStream(ServletInputStream delegate, Runnable onTrip) {
            this.delegate = delegate;
            this.onTrip = onTrip;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value < 0) return value;
            account(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(long bytes) throws BodyTooLargeException {
            read += bytes;
            if (read > MAX_CHAT_BODY_BYTES) {
                onTrip.run();
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
