package com.drillnotebook.app.memory.prototype;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P0a 第③组原型（执行方案 §7.2.2、验收 F11/F12）：30 秒必须是覆盖建连、响应头与响应体读取的
 * <b>总期限</b>，而不是只给 HttpRequest 设 timeout。用 loopback 受控服务把响应停在"头已发、
 * 正文不结束"的位置，验证到期时能不能真的关闭响应体、让读取任务退出、只留一个终态。
 *
 * <p>结论决定 P6 能否接后台 worker：阻塞读取若不可靠取消，并发 1 的执行槽会被悬挂任务永久占住，
 * 按方案必须暂停后续 worker 集成。</p>
 */
public final class HangingResponsePrototype {

    /** 头已发送、正文永不结束（普通 JSON 读取）。 */
    public static final String MODE_BODY_HANG = "body-hang";
    /** 头已发送、正文只有一段不含换行的数据（SSE 行读取会阻塞）。 */
    public static final String MODE_SSE_NO_NEWLINE = "sse-no-newline";
    /** 连响应头都不发（尚未取得响应体时也要能取消）。 */
    public static final String MODE_BEFORE_HEADERS = "before-headers";

    private HangingResponsePrototype() {
    }

    public record Outcome(boolean headersSeen, boolean readerExited, long readerExitMillis, int terminalCommits,
            int duplicateCommits, boolean bodyCloseAttempted, boolean readerPoolTerminated, int servedRequests) {

        public boolean singleTerminalState() {
            return terminalCommits == 1;
        }
    }

    /**
     * 一次受控悬挂实验。
     *
     * @param deadlineMillis 总期限（测试用 200ms，不靠真实 30 秒 sleep，acceptance §9.1.3）
     * @param exitWaitMillis 允许读取任务退出的等待窗口（对应 §7.2.2 的 2 秒健康判定）
     */
    public static Outcome run(String mode, long deadlineMillis, long exitWaitMillis) throws Exception {
        CountDownLatch handlerReleased = new CountDownLatch(1);
        AtomicInteger served = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/hang", exchange -> {
            try {
                served.incrementAndGet();
                if (mode.equals(MODE_BEFORE_HEADERS)) {
                    handlerReleased.await(10, TimeUnit.SECONDS);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type",
                        mode.equals(MODE_SSE_NO_NEWLINE) ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, 0);
                if (mode.equals(MODE_SSE_NO_NEWLINE)) {
                    OutputStream body = exchange.getResponseBody();
                    body.write("data: {\"partial\"".getBytes(StandardCharsets.UTF_8));
                    body.flush();
                }
                // 保持输出流打开且不结束：复刻上游网关"头之后悬挂"的形状
                handlerReleased.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                // 客户端取消后写入失败属预期
            } finally {
                try {
                    exchange.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        });
        server.start();

        ExecutorService readerPool = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        HttpClient client = HttpClient.newBuilder()
                .executor(readerPool)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/hang"))
                // 故意把 HttpRequest.timeout 放宽：证明总期限不能靠它
                .timeout(Duration.ofMillis(deadlineMillis * 10))
                .GET()
                .build();

        AtomicReference<HttpResponse<InputStream>> responseRef = new AtomicReference<>();
        AtomicBoolean headersSeen = new AtomicBoolean();
        AtomicBoolean bodyCloseAttempted = new AtomicBoolean();
        AtomicBoolean deadlineExpired = new AtomicBoolean();
        AtomicBoolean terminalLatch = new AtomicBoolean();
        AtomicInteger terminalCommits = new AtomicInteger();
        AtomicInteger duplicateCommits = new AtomicInteger();
        AtomicLong readerExitMillis = new AtomicLong(-1);
        CountDownLatch readerDone = new CountDownLatch(1);
        long started = System.nanoTime();

        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
        future.thenAccept(response -> {
            responseRef.set(response);
            headersSeen.set(true);
            // §7.2.2：期限可能先于响应头到期；刚取得响应体时若已取消，必须立刻关闭
            if (deadlineExpired.get()) {
                bodyCloseAttempted.set(true);
                try {
                    response.body().close();
                } catch (Exception ignored) {
                    // 关闭失败仍要继续走终态
                }
            }
            readerPool.submit(() -> {
                try {
                    InputStream body = response.body();
                    byte[] buffer = new byte[64];
                    // 读到 EOF 才算正常结束：只读一次会在 SSE 首块就误判成功
                    int read;
                    while ((read = body.read(buffer)) != -1) {
                        if (read == 0) {
                            break;
                        }
                    }
                    commit(terminalLatch, terminalCommits, duplicateCommits);
                } catch (Exception cancelled) {
                    commit(terminalLatch, terminalCommits, duplicateCommits);
                } finally {
                    readerExitMillis.set((System.nanoTime() - started) / 1_000_000L);
                    readerDone.countDown();
                }
            });
        }).whenComplete((ignored, error) -> {
            // 未取得响应体就被取消：同样只能有一个终态
            if (error != null) {
                commit(terminalLatch, terminalCommits, duplicateCommits);
            }
        });

        ScheduledFuture<?> deadlineTask = scheduler.schedule(() -> {
            deadlineExpired.set(true);
            HttpResponse<InputStream> response = responseRef.get();
            if (response != null) {
                // 标志代表"到期路径调用了 close"，必须在调用前置位：读取线程会被 close 立刻唤醒，
                // 置位放后面会让主线程在竞态下读到 false（全量并跑时已复现过一次假失败）。
                bodyCloseAttempted.set(true);
                try {
                    response.body().close();
                } catch (Exception ignored) {
                    // close 失败也要继续取消
                }
            }
            commit(terminalLatch, terminalCommits, duplicateCommits);
            future.cancel(true);
        }, deadlineMillis, TimeUnit.MILLISECONDS);

        boolean readerExited = readerDone.await(exitWaitMillis, TimeUnit.MILLISECONDS);
        handlerReleased.countDown();
        deadlineTask.cancel(false);
        scheduler.shutdownNow();
        readerPool.shutdownNow();
        boolean poolTerminated = readerPool.awaitTermination(2, TimeUnit.SECONDS);
        server.stop(0);

        return new Outcome(headersSeen.get(), readerExited, readerExitMillis.get(), terminalCommits.get(),
                duplicateCommits.get(), bodyCloseAttempted.get(), poolTerminated, served.get());
    }

    /** 一次性终态门闩：达到期限、读到正文、取消竞争，只允许第一个赢，其余计为重复提交。 */
    private static void commit(AtomicBoolean latch, AtomicInteger terminalCommits, AtomicInteger duplicateCommits) {
        if (latch.compareAndSet(false, true)) {
            terminalCommits.incrementAndGet();
        } else {
            duplicateCommits.incrementAndGet();
        }
    }
}
