package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake embedding worker for JUnit tests.
 * <p>
 * Reads NDJSON from stdin and writes NDJSON responses to stdout, following the
 * same protocol as the real Rust worker.  Controlled via environment variables
 * (with {@code -D} system‑property fallback):
 * <ul>
 *   <li>{@code FAKE_WORKER_CRASH_AFTER} — crash (System.exit(1)) after N requests</li>
 *   <li>{@code FAKE_WORKER_DELAY_MS} — delay before responding (simulates slow worker)</li>
 *   <li>{@code FAKE_WORKER_CONTAMINATE} — if set, writes one garbage line before each response</li>
 *   <li>{@code FAKE_WORKER_SKIP_HANDSHAKE} — if set, does not respond to hello (simulates hang)</li>
 * </ul>
 * <p>
 * Must be compiled with the same classpath as the test suite; executed via
 * {@code ProcessBuilder} using {@code java} from the current JDK.
 */
public final class FakeEmbeddingWorker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PROTOCOL_VERSION = 1;

    private FakeEmbeddingWorker() {}

    public static void main(String[] args) throws Exception {
        // Read from system property first (for -D flags), then env var.
        String crashAfterStr = System.getProperty("FAKE_WORKER_CRASH_AFTER",
            System.getenv("FAKE_WORKER_CRASH_AFTER"));
        int crashAfter = crashAfterStr != null && !crashAfterStr.isEmpty()
            ? Integer.parseInt(crashAfterStr) : Integer.MAX_VALUE;
        String delayStr = System.getProperty("FAKE_WORKER_DELAY_MS",
            System.getenv("FAKE_WORKER_DELAY_MS"));
        long delayMs = delayStr != null && !delayStr.isEmpty()
            ? Long.parseLong(delayStr) : 0L;
        String contStr = System.getProperty("FAKE_WORKER_CONTAMINATE",
            System.getenv("FAKE_WORKER_CONTAMINATE"));
        boolean contaminate = contStr != null && !contStr.isEmpty();
        String skipStr = System.getProperty("FAKE_WORKER_SKIP_HANDSHAKE",
            System.getenv("FAKE_WORKER_SKIP_HANDSHAKE"));
        boolean skipHandshake = skipStr != null && !skipStr.isEmpty();

        // Spawn marker for generation counting in tests.
        String spawnMarker = System.getProperty("FAKE_WORKER_SPAWN_MARKER",
            System.getenv("FAKE_WORKER_SPAWN_MARKER"));
        if (spawnMarker != null && !spawnMarker.isEmpty()) {
            try {
                Path markerFile = Paths.get(spawnMarker);
                int count = Files.exists(markerFile)
                    ? Integer.parseInt(Files.readString(markerFile).trim()) + 1
                    : 1;
                Files.writeString(markerFile, Integer.toString(count));
            } catch (Exception ignored) {
                // best-effort
            }
        }

        int requestCount = 0;
        boolean modelLoaded = false;

        // Write stderr for the test to verify
        System.err.println("FakeEmbeddingWorker started");

        try (BufferedReader reader = new BufferedReader(
                 new InputStreamReader(System.in, StandardCharsets.UTF_8));
             Writer writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8)) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                requestCount++;
                if (crashAfter > 0 && requestCount >= crashAfter) {
                    System.err.println("FakeEmbeddingWorker crashing by request");
                    System.exit(1);
                }

                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }

                // Parse the request.
                WorkerProtocol.Request request;
                try {
                    request = MAPPER.readValue(line, WorkerProtocol.Request.class);
                } catch (Exception e) {
                    // Malformed request: respond with error
                    String errorJson = MAPPER.writeValueAsString(
                        new WorkerProtocol.Response.Error(PROTOCOL_VERSION, "",
                            WorkerProtocol.ErrorCode.MALFORMED_REQUEST,
                            "Invalid JSON: " + e.getMessage(), false));
                    writer.write(errorJson + "\n");
                    writer.flush();
                    continue;
                }

                if (contaminate) {
                    writer.write("CONTAMINATION: this is not JSON\n");
                    writer.flush();
                }

                String rid = request.requestId();

                // Java-17-compatible instanceof chain.
                if (request instanceof WorkerProtocol.Request.Hello) {
                    if (skipHandshake) {
                        continue;
                    }
                    String respJson = MAPPER.writeValueAsString(
                        new WorkerProtocol.Response.Ready(PROTOCOL_VERSION, rid));
                    writer.write(respJson + "\n");
                } else if (request instanceof WorkerProtocol.Request.LoadModel lm) {
                    modelLoaded = true;
                    String respJson = MAPPER.writeValueAsString(
                        new WorkerProtocol.Response.ModelLoaded(PROTOCOL_VERSION, rid, lm.dimensions()));
                    writer.write(respJson + "\n");
                } else if (request instanceof WorkerProtocol.Request.Embed e) {
                    if (!modelLoaded) {
                        String errJson = MAPPER.writeValueAsString(
                            new WorkerProtocol.Response.Error(PROTOCOL_VERSION, rid,
                                WorkerProtocol.ErrorCode.MODEL_NOT_LOADED,
                                "No model loaded", true));
                        writer.write(errJson + "\n");
                    } else {
                        // Return dummy embeddings: each input -> [0.1, 0.2, ..., 0.512]
                        List<List<Float>> embeddings = new ArrayList<>();
                        for (String ignored : e.inputs()) {
                            List<Float> vec = new ArrayList<>();
                            for (int i = 0; i < 512; i++) {
                                vec.add((float) (i + 1) / 512.0f);
                            }
                            embeddings.add(vec);
                        }
                        String respJson = MAPPER.writeValueAsString(
                            new WorkerProtocol.Response.EmbedResult(PROTOCOL_VERSION, rid, embeddings));
                        writer.write(respJson + "\n");
                    }
                } else if (request instanceof WorkerProtocol.Request.Unload) {
                    modelLoaded = false;
                    String respJson = MAPPER.writeValueAsString(
                        new WorkerProtocol.Response.Ok(PROTOCOL_VERSION, rid));
                    writer.write(respJson + "\n");
                } else if (request instanceof WorkerProtocol.Request.Shutdown) {
                    String respJson = MAPPER.writeValueAsString(
                        new WorkerProtocol.Response.Ok(PROTOCOL_VERSION, rid));
                    writer.write(respJson + "\n");
                    writer.flush();
                    System.err.println("FakeEmbeddingWorker shutting down");
                    System.exit(0);
                } else {
                    String errJson = MAPPER.writeValueAsString(
                        new WorkerProtocol.Response.Error(PROTOCOL_VERSION, rid,
                            WorkerProtocol.ErrorCode.MALFORMED_REQUEST,
                            "Unknown request type", false));
                    writer.write(errJson + "\n");
                }
                writer.flush();
            }
        }
        System.err.println("FakeEmbeddingWorker stdin closed, exiting");
        System.exit(0);
    }
}
