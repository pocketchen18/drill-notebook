package com.drillnotebook.app.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 上游网关夹具（P4 验收 F13/F14 与 §7.2.2）：把客户端真正发出去的请求体原样记下来，
 * 并能把响应停在"头已发、正文不结束"的位置，用来验证总期限到期后确实关闭了响应体。
 *
 * <p>只监听 127.0.0.1 的随机端口，不落盘、不外呼。</p>
 */
class UpstreamFixtureServer implements Closeable {
    private static final String REPLY = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"上游回答\","
            + "\"finish_reason\":\"stop\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";

    private final HttpServer server;
    private final List<String> bodies = Collections.synchronizedList(new java.util.ArrayList<>());
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean hung = new AtomicBoolean();
    private final AtomicBoolean bodyWriteFailed = new AtomicBoolean();
    private volatile Mode mode = Mode.NORMAL;

    private enum Mode { NORMAL, HANG_AFTER_FIRST_DELTA, HANG_SILENTLY }

    UpstreamFixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 记录下来的请求体，按到达顺序。 */
    List<String> bodies() {
        return List.copyOf(bodies);
    }

    /** 下一次响应只发一段增量然后挂住，直到 {@link #close()}。 */
    void hangAfterFirstDelta() {
        mode = Mode.HANG_AFTER_FIRST_DELTA;
    }

    /** 下一次响应只发响应头、正文一个字节都不发（上游沉默），直到 {@link #close()} 或连接被取消。 */
    void hangSilently() {
        mode = Mode.HANG_SILENTLY;
    }

    /** 恢复为正常应答：取消之后想用同一个夹具继续发下一轮时调用。 */
    void respondNormally() {
        mode = Mode.NORMAL;
    }

    /** 客户端关闭之后，服务端往响应体里写会不会失败（证明连接真的被关掉）。 */
    boolean bodyWriteFailed() {
        return bodyWriteFailed.get();
    }

    boolean isHung() {
        return hung.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        if (mode == Mode.HANG_SILENTLY) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            hung.set(true);
            try (OutputStream out = exchange.getResponseBody()) {
                // 头已发、正文一个字节都不发：只有取消或总期限才能让这次读取结束
                release.await(20, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 20 && !bodyWriteFailed.get(); attempt++) {
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"晚到的正文\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException closed) {
                bodyWriteFailed.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
            return;
        }
        if (mode != Mode.HANG_AFTER_FIRST_DELTA) {
            byte[] payload = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        hung.set(true);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"悬挂前的半句\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 正文不发终止帧也不关：到期后必须由客户端把连接关掉，服务端这次写才会失败
            release.await(20, TimeUnit.SECONDS);
            for (int attempt = 0; attempt < 20 && !bodyWriteFailed.get(); attempt++) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"晚到的正文\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
            }
        } catch (IOException closed) {
            bodyWriteFailed.set(true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }
}
