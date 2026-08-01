package com.drillnotebook.app.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Loopback fixture file server with HTTP Range / If-Range support.
 *
 * <p>Records every request's path and Range/If-Range headers so tests can
 * assert resume behaviour. Configurable to ignore Range (always 200) and to
 * hold responses on a latch for deterministic in-flight assertions.
 */
class FixtureHttpServer implements Closeable {

    private final HttpServer server;
    private final Path rootDir;

    volatile String etag = "\"fixture-v1\"";
    volatile String lastModified = "Wed, 01 Jan 2025 00:00:00 GMT";
    volatile boolean ignoreRange = false;
    volatile CountDownLatch holdLatch;

    final List<Map<String, String>> requests =
            Collections.synchronizedList(new java.util.ArrayList<>());

    FixtureHttpServer(Path rootDir) throws IOException {
        this.rootDir = rootDir;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        this.server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring(1);
        Map<String, String> record = new LinkedHashMap<>();
        record.put("path", name);
        record.put("range", exchange.getRequestHeaders().getFirst("Range"));
        record.put("ifRange", exchange.getRequestHeaders().getFirst("If-Range"));
        requests.add(record);

        CountDownLatch latch = holdLatch;
        if (latch != null) {
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Path file = rootDir.resolve(name);
        if (name.contains("..") || !Files.exists(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        long size = Files.size(file);
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Last-Modified", lastModified);

        String range = exchange.getRequestHeaders().getFirst("Range");
        long start = 0;
        int status = 200;
        if (range != null && range.startsWith("bytes=") && !ignoreRange) {
            long requested = Long.parseLong(range.substring(6).replace("-", ""));
            if (requested >= size) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
            if (ifRange == null || ifRange.equals(etag) || ifRange.equals(lastModified)) {
                start = requested;
                status = 206;
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + (size - 1) + "/" + size);
            }
            // Validator mismatch → fall through with 200 (full body).
        }

        long length = size - start;
        exchange.sendResponseHeaders(status, length);
        try (OutputStream out = exchange.getResponseBody();
             InputStream in = Files.newInputStream(file)) {
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        exchange.close();
    }

    /** Requests recorded for one file, in arrival order. */
    List<Map<String, String>> requestsFor(String fileName) {
        synchronized (requests) {
            return requests.stream().filter(r -> fileName.equals(r.get("path"))).toList();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
