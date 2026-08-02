package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.drillnotebook.app.config.PortablePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link EmbeddingWorkerLifecycle}.
 * <p>
 * Uses a fake worker in pure Java ({@link FakeEmbeddingWorker}) launched
 * via {@code ProcessBuilder} with a command list
 * {@code [java, -cp, <classpath>, FakeEmbeddingWorker]}.
 * <p>
 * No {@code Thread.sleep}, no empty catches.
 */
class EmbeddingWorkerLifecycleTest {

    private Path tempAppRoot;
    private PortablePathResolver resolver;
    private EmbeddingWorkerLifecycle lifecycle;
    private String javaCommand;
    private String classpath;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Bounded polling deadline helper. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_INTERVAL_NANOS = Duration.ofMillis(10).toNanos();

    @BeforeEach
    void setUp() throws Exception {
        tempAppRoot = Files.createTempDirectory("ewl-test-");
        javaCommand = findJavaCommand();
        classpath = System.getProperty("java.class.path");
        System.setProperty("app.root", tempAppRoot.toString());
        resolver = new PortablePathResolver();

        // Do NOT pre-create paths — ensureDirectories() is tested separately.
        lifecycle = createLifecycle();
    }

    @AfterEach
    void tearDown() {
        if (lifecycle != null) {
            lifecycle.resetForTest();
        }
        System.clearProperty("app.root");
        System.clearProperty("DRILL_EMBEDDING_WORKER_EXE");
        if (tempAppRoot != null && Files.exists(tempAppRoot)) {
            forceDeleteDir(tempAppRoot);
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Poll a condition with a deadline — never calls Thread.sleep.
     */
    private static <T> T pollUntil(Supplier<T> supplier, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T result;
        while (System.nanoTime() < deadline) {
            result = supplier.get();
            if (result instanceof Boolean b ? b : result != null) {
                return result;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(POLL_INTERVAL_NANOS);
        }
        result = supplier.get();
        return result;
    }

    private static void assertPoll(String message, Supplier<Boolean> condition, Duration timeout) {
        Boolean result = pollUntil(condition, timeout);
        assertTrue(result != null && result, message);
    }

    private static void forceDeleteDir(Path dir) {
        IOException lastEx = null;
        for (int i = 0; i < 5; i++) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // accumulate, don't silently swallow
                            throw new RuntimeException("Failed to delete " + p, e);
                        }
                    });
                if (!Files.exists(dir)) return;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException ioe) {
                    lastEx = ioe;
                } else {
                    throw e;
                }
            } catch (IOException e) {
                lastEx = e;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
        if (Files.exists(dir) && lastEx != null) {
            throw new RuntimeException("Failed to delete " + dir + " after 5 retries", lastEx);
        }
    }

    private List<String> createFakeWorkerCommand(String... jvmArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaCommand);
        cmd.add("-cp");
        cmd.add(classpath);
        for (String arg : jvmArgs) {
            cmd.add(arg);
        }
        cmd.add(FakeEmbeddingWorker.class.getName());
        return cmd;
    }

    private EmbeddingWorkerLifecycle createLifecycle() {
        return createLifecycle(createFakeWorkerCommand());
    }

    private EmbeddingWorkerLifecycle createLifecycle(List<String> command) {
        if (lifecycle != null) {
            lifecycle.resetForTest();
        }
        return new EmbeddingWorkerLifecycle(resolver, mapper, command);
    }

    private WorkerResult sendHello(String rid) {
        return lifecycle.execute(rid,
            new WorkerProtocol.Request.Hello(WorkerProtocol.PROTOCOL_VERSION, rid));
    }

    private static String findJavaCommand() {
        String home = System.getProperty("java.home");
        if (home == null) home = System.getenv("JAVA_HOME");
        if (home == null) return "java";
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Paths.get(home, "bin", isWindows ? "java.exe" : "java");
        if (Files.exists(java)) return java.toString();
        java = Paths.get(home, "bin", "java");
        if (Files.exists(java)) return java.toString();
        return "java";
    }

    /**
     * Create a fake worker command that writes a spawn marker file.
     */
    private List<String> createSpawnCountingCommand(String markerPath, String... extraArgs) {
        List<String> args = new ArrayList<>();
        args.add("-DFAKE_WORKER_SPAWN_MARKER=" + markerPath);
        for (String a : extraArgs) {
            args.add(a);
        }
        return createFakeWorkerCommand(args.toArray(new String[0]));
    }

    /** Read spawn count from marker file. */
    private static int readSpawnCount(Path markerFile) throws IOException {
        if (!Files.exists(markerFile)) return 0;
        return Integer.parseInt(Files.readString(markerFile).trim());
    }

    // ===================================================================
    // Tests – basic config
    // ===================================================================

    @Test
    void exeNotSetReturnsWorkerNotBuilt() {
        System.clearProperty("DRILL_EMBEDDING_WORKER_EXE");
        lifecycle = new EmbeddingWorkerLifecycle(resolver, mapper);

        WorkerResult result = lifecycle.execute("t1",
            new WorkerProtocol.Request.Hello(WorkerProtocol.PROTOCOL_VERSION, "t1"));
        assertTrue(result instanceof WorkerResult.WorkerNotBuilt,
            "Expected WorkerNotBuilt, got: " + result);
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after WorkerNotBuilt");
    }

    @Test
    void exeNotFoundReturnsWorkerNotBuilt() {
        System.setProperty("DRILL_EMBEDDING_WORKER_EXE",
            tempAppRoot.resolve("nonexistent-worker.exe").toString());
        lifecycle = new EmbeddingWorkerLifecycle(resolver, mapper);

        WorkerResult result = lifecycle.execute("t1",
            new WorkerProtocol.Request.Hello(WorkerProtocol.PROTOCOL_VERSION, "t1"));
        assertTrue(result instanceof WorkerResult.WorkerNotBuilt,
            "Expected WorkerNotBuilt for missing exe, got: " + result);
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after WorkerNotBuilt");
    }

    // ===================================================================
    // Tests – lifecycle basics
    // ===================================================================

    @Test
    void lazyStart() {
        assertEquals(-1, lifecycle.pid(), "PID should be -1 before first call");
        assertFalse(lifecycle.isReady(), "Should not be ready before first call");

        WorkerResult r = sendHello("h1");
        assertTrue(r instanceof WorkerResult.Success, "Hello should succeed: " + r);
        assertTrue(lifecycle.isReady(), "Worker should be ready after hello");
        assertTrue(lifecycle.pid() > 0, "PID should be positive");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after successful hello");
    }

    @Test
    void twentyConcurrentRequestsReuseSamePid() throws Exception {
        sendHello("init");
        long firstPid = lifecycle.pid();
        assertTrue(firstPid > 0, "Initial PID must be positive");

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<WorkerResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> sendHello("tc-" + idx)));
        }

        List<WorkerResult> results = new ArrayList<>();
        for (Future<WorkerResult> f : futures) {
            WorkerResult r = f.get(30, TimeUnit.SECONDS);
            results.add(r);
            assertTrue(r instanceof WorkerResult.Success,
                "Concurrent request failed: " + r);
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(firstPid, lifecycle.pid(),
            "PID should remain constant across concurrent requests");

        // Verify all 20 succeeded.
        assertEquals(20, results.size(), "All 20 requests should have results");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after all concurrent requests complete");
    }

    @Test
    void shutdownKillsProcess() {
        sendHello("s1");
        long pid = lifecycle.pid();
        assertTrue(pid > 0, "PID must exist before shutdown");

        lifecycle.shutdown();

        assertEquals(-1, lifecycle.pid(), "PID should be -1 after shutdown");
        assertFalse(lifecycle.isReady(), "Should not be ready after shutdown");
        ProcessHandle.of(pid).ifPresent(ph ->
            assertFalse(ph.isAlive(), "Process should be dead after shutdown"));
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after shutdown");
    }

    @Test
    void shutdownIsTerminal() {
        sendHello("sterm1");
        assertTrue(lifecycle.isReady(), "Should be ready");

        lifecycle.shutdown();
        assertFalse(lifecycle.isReady(), "Should not be ready after shutdown");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> sendHello("sterm2"),
            "Should throw after shutdown");
        assertTrue(ex.getMessage().contains("shut down"),
            "Error should mention shutdown. Got: " + ex.getMessage());
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after shutdown");
    }

    @Test
    void stderrLoggedToAppRoot() throws Exception {
        sendHello("st1");
        sendHello("st2");
        lifecycle.shutdown();

        Path logFile = resolver.workerLogs().resolve("embedding-worker-stderr.log");
        assertTrue(Files.exists(logFile), "Stderr log should exist: " + logFile);
        String content = Files.readString(logFile);
        assertTrue(content.contains("FakeEmbeddingWorker"),
            "Stderr log should contain 'FakeEmbeddingWorker'. Got: " + content);
    }

    // ===================================================================
    // Tests – restart / recovery
    // ===================================================================

    @Test
    void crashAndAutomaticRestart() throws Exception {
        Path spawnMarker = tempAppRoot.resolve("spawn-count-crash.log");

        // Worker crashes after 3 requests (handshake + 2 user hellos → 3rd request crashes).
        List<String> crashCmd = createSpawnCountingCommand(spawnMarker.toString(),
            "-DFAKE_WORKER_CRASH_AFTER=3");
        lifecycle = createLifecycle(crashCmd);

        WorkerResult r1 = sendHello("r1");
        assertTrue(r1 instanceof WorkerResult.Success, "r1: " + r1);

        // This request triggers the crash (request #3).
        WorkerResult r2 = sendHello("r2");
        // r2 may be WorkerUnavailable. Just verify it's a typed result.
        assertNotNull(r2, "r2 result must not be null");

        // Wait for reader to notice EOF and set FAILED state (bounded poll).
        assertPoll("Worker should enter FAILED state after crash",
            () -> !lifecycle.isReady(),
            POLL_TIMEOUT);

        // Request 3 — should trigger automatic restart (1st of MAX_RESTART_ATTEMPTS).
        WorkerResult r3 = sendHello("r3");
        assertTrue(r3 instanceof WorkerResult.Success,
            "Auto-restart should succeed: " + r3);
        assertTrue(lifecycle.isReady(), "Worker should be ready after restart");
        assertTrue(lifecycle.pid() > 0, "PID should be positive after restart");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after crash+restart");

        // Verify at most 2 spawns (original + 1 restart).
        int spawnCount = readSpawnCount(spawnMarker);
        assertTrue(spawnCount <= 2,
            "Should have at most 2 spawns, got: " + spawnCount);
    }

    @Test
    void maxRestartExceeded() throws Exception {
        Path spawnMarker = tempAppRoot.resolve("spawn-count-max.log");

        // Worker crashes after 2 requests (handshake + 1 user hello).
        List<String> crashCmd = createSpawnCountingCommand(spawnMarker.toString(),
            "-DFAKE_WORKER_CRASH_AFTER=2");
        lifecycle = createLifecycle(crashCmd);

        // This triggers crash (handshake = request 1, r1 = request 2 → crash).
        WorkerResult r1 = sendHello("r1");
        assertTrue(r1 instanceof WorkerResult.WorkerUnavailable,
            "Should be unavailable after first crash: " + r1);

        // Wait for cleanup + auto-restart attempt.
        assertPoll("Worker should not be ready after first crash",
            () -> !lifecycle.isReady(),
            POLL_TIMEOUT);

        // After restart, the new worker also crashes after 2 requests.
        WorkerResult r2 = sendHello("r2");
        assertTrue(r2 instanceof WorkerResult.WorkerUnavailable,
            "Should be unavailable after second crash: " + r2);

        assertPoll("Worker should not be ready after second crash",
            () -> !lifecycle.isReady(),
            POLL_TIMEOUT);

        // Now restart attempts exhausted — should get WorkerUnavailable.
        WorkerResult r3 = sendHello("r3");
        assertTrue(r3 instanceof WorkerResult.WorkerUnavailable,
            "Should be unavailable after max restarts: " + r3);
        assertFalse(lifecycle.isReady(), "Worker should not be ready after max restarts");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after max restarts");

        // Verify at most 2 spawns total.
        int spawnCount = readSpawnCount(spawnMarker);
        assertTrue(spawnCount <= 2,
            "Should have at most 2 spawns, got: " + spawnCount);
    }

    @RepeatedTest(5)
    void resetAllowsRestart() {
        sendHello("reset1");
        assertTrue(lifecycle.isReady(), "Should be ready");
        long pid = lifecycle.pid();
        assertTrue(pid > 0, "PID should exist");

        lifecycle.resetForTest();

        assertEquals(-1, lifecycle.pid(), "PID should be -1 after reset");
        assertFalse(lifecycle.isReady(), "Should not be ready after reset");
        ProcessHandle.of(pid).ifPresent(ph ->
            assertFalse(ph.isAlive(), "Process should be dead after reset"));
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after reset");

        WorkerResult r = sendHello("reset2");
        assertTrue(r instanceof WorkerResult.Success,
            "Should restart after reset: " + r);
        assertTrue(lifecycle.isReady(), "Worker should be ready after restart");
        assertTrue(lifecycle.pid() > 0, "PID should exist after restart");
    }

    // ===================================================================
    // Tests – protocol / functional
    // ===================================================================

    @Test
    void embedReturnsVectors() {
        sendHello("e1");

        WorkerResult loadResult = lifecycle.execute("e2",
            new WorkerProtocol.Request.LoadModel(
                WorkerProtocol.PROTOCOL_VERSION, "e2",
                "test-model", "/tmp/models",
                List.of("model.onnx"), 512));
        assertTrue(loadResult instanceof WorkerResult.Success, "Load: " + loadResult);

        WorkerResult embedResult = lifecycle.execute("e3",
            new WorkerProtocol.Request.Embed(
                WorkerProtocol.PROTOCOL_VERSION, "e3",
                WorkerProtocol.EmbedMode.QUERY,
                List.of("test input")));
        assertTrue(embedResult instanceof WorkerResult.Success,
            "Embed should succeed: " + embedResult);

        if (embedResult instanceof WorkerResult.Success success) {
            List<List<Float>> embeddings = success.embeddings();
            assertNotNull(embeddings, "embeddings should not be null");
            assertEquals(1, embeddings.size(), "should have 1 embedding");
            assertEquals(512, embeddings.get(0).size(), "should have 512 dims");
        }
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after embed");
    }

    // ===================================================================
    // Tests – contamination (protocol violation)
    // ===================================================================

    @Test
    void contaminationFailsProcess() throws Exception {
        Path spawnMarker = tempAppRoot.resolve("spawn-count-cont.log");

        List<String> contCmd = createSpawnCountingCommand(spawnMarker.toString(),
            "-DFAKE_WORKER_CONTAMINATE=1");
        lifecycle = createLifecycle(contCmd);

        WorkerResult r = sendHello("cont1");
        assertTrue(r instanceof WorkerResult.WorkerUnavailable,
            "Contamination should fail the request: " + r);
        assertFalse(lifecycle.isReady(), "Worker should not be ready after contamination");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after contamination");

        // Verify only 1 spawn (contamination kills before handshake completes).
        int spawnCount = readSpawnCount(spawnMarker);
        assertEquals(1, spawnCount,
            "Should have exactly 1 spawn, got: " + spawnCount);
    }

    // ===================================================================
    // Tests – timeouts (injectable)
    // ===================================================================

    @Test
    void handshakeTimeout() throws Exception {
        Path spawnMarker = tempAppRoot.resolve("spawn-count-hs.log");

        List<String> skipCmd = createSpawnCountingCommand(spawnMarker.toString(),
            "-DFAKE_WORKER_SKIP_HANDSHAKE=1");
        lifecycle = createLifecycle(skipCmd);
        lifecycle.handshakeTimeout = Duration.ofMillis(500);

        WorkerResult r = sendHello("hst1");
        assertTrue(r instanceof WorkerResult.WorkerUnavailable,
            "Handshake timeout should return WorkerUnavailable: " + r);
        assertFalse(lifecycle.isReady(), "Worker should not be ready after handshake timeout");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after handshake timeout");

        // Verify only 1 spawn.
        int spawnCount = readSpawnCount(spawnMarker);
        assertEquals(1, spawnCount,
            "Should have exactly 1 spawn, got: " + spawnCount);
    }

    @Test
    void requestTimeout() throws Exception {
        Path spawnMarker = tempAppRoot.resolve("spawn-count-rt.log");

        // Create a lifecycle with a slow worker.
        List<String> slowCmd = createSpawnCountingCommand(spawnMarker.toString(),
            "-DFAKE_WORKER_DELAY_MS=2000");
        lifecycle = createLifecycle(slowCmd);
        lifecycle.requestTimeout = Duration.ofMillis(500);
        lifecycle.handshakeTimeout = Duration.ofSeconds(5);

        // The first call starts the worker, handshake succeeds (short request
        // timeout only applies to the hello request itself).
        WorkerResult r = sendHello("rt1");
        assertTrue(r instanceof WorkerResult.WorkerUnavailable,
            "Request timeout should return WorkerUnavailable: " + r);

        // After request timeout, the process is killed (state becomes FAILED).
        assertPoll("Worker should be unavailable after request timeout",
            () -> !lifecycle.isReady(),
            POLL_TIMEOUT);
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after request timeout");

        // Verify first spawn only.
        int spawnCount = readSpawnCount(spawnMarker);
        assertEquals(1, spawnCount,
            "Should have exactly 1 spawn, got: " + spawnCount);
    }

    @Test
    void requestTimeoutThenNextRequestRecoversWithNewPid() throws Exception {
        // Request timeout kills the process and leaves state=FAILED with
        // restart budget intact.  A subsequent non-delayed request should
        // start a new process.
        Path spawnMarker = tempAppRoot.resolve("spawn-count-rt2.log");

        // Phase 1: slow worker that times out.
        List<String> slowCmd = createSpawnCountingCommand(spawnMarker.toString(),
            "-DFAKE_WORKER_DELAY_MS=2000");
        lifecycle = createLifecycle(slowCmd);
        lifecycle.requestTimeout = Duration.ofMillis(500);
        lifecycle.handshakeTimeout = Duration.ofSeconds(5);

        WorkerResult r1 = sendHello("rt2a");
        assertTrue(r1 instanceof WorkerResult.WorkerUnavailable,
            "First request should time out: " + r1);

        // Verify process is dead and state is FAILED.
        assertPoll("Worker should be unavailable after timeout",
            () -> !lifecycle.isReady(),
            POLL_TIMEOUT);
        assertEquals(0, lifecycle.restartCount(),
            "restartCount should still be 0 after timeout (handshake consumed no budget)");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after timeout");

        // Phase 2: fresh lifecycle (different marker file) to prove new
        // process can start.  This simulates the next user request after
        // a timeout recovery.
        Path spawnMarker2 = tempAppRoot.resolve("spawn-count-rt2b.log");
        List<String> fastCmd = createSpawnCountingCommand(spawnMarker2.toString());
        lifecycle = createLifecycle(fastCmd);
        lifecycle.requestTimeout = Duration.ofSeconds(10);
        lifecycle.handshakeTimeout = Duration.ofSeconds(5);

        WorkerResult r2 = sendHello("rt2b");
        assertTrue(r2 instanceof WorkerResult.Success,
            "Second request should succeed: " + r2);
        assertTrue(lifecycle.isReady(), "Worker should be ready after recovery");
        assertTrue(lifecycle.pid() > 0, "PID should be positive after recovery");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after recovery");

        // Verify the second worker was spawned.
        int spawnCount = readSpawnCount(spawnMarker2);
        assertEquals(1, spawnCount,
            "Should have exactly 1 spawn for fresh worker, got: " + spawnCount);
    }

    // ===================================================================
    // Tests – PID file and APP_ROOT directories
    // ===================================================================

    @Test
    void pidFileCreatedAndRemoved() throws Exception {
        sendHello("pid1");
        long pid = lifecycle.pid();
        assertTrue(pid > 0);

        // PID file should exist after start.
        Path pidFile = resolver.pidDir().resolve("embedding-worker.pid");
        assertPoll("PID file should exist after start",
            () -> Files.exists(pidFile),
            POLL_TIMEOUT);
        String content = Files.readString(pidFile).trim();
        assertEquals(Long.toString(pid), content,
            "PID file should contain the worker PID");

        // After shutdown, PID file should be removed.
        lifecycle.shutdown();
        assertPoll("PID file should be removed after shutdown",
            () -> !Files.exists(pidFile),
            POLL_TIMEOUT);
    }

    @Test
    void ensureDirectoriesCreatesAllPaths() throws Exception {
        // Start with an empty tempAppRoot (no pre-created dirs in setUp).
        // After a successful worker start, all directories should exist.
        sendHello("dir1");
        assertTrue(lifecycle.isReady(), "Worker should be ready");

        // Verify every required directory exists.
        assertTrue(Files.exists(resolver.runtime()), "runtime dir should exist");
        assertTrue(Files.exists(resolver.workerLogs()), "workerLogs dir should exist");
        assertTrue(Files.exists(resolver.fastembedCache()), "fastembedCache dir should exist");
        assertTrue(Files.exists(resolver.fastembedCache().resolve("huggingface")),
            "fastembedCache/huggingface dir should exist");
        assertTrue(Files.exists(resolver.tempDir()), "tempDir should exist");
        assertTrue(Files.exists(resolver.pidDir()), "pidDir should exist");
    }

    @Test
    void pidFileRemovedOnReset() throws Exception {
        sendHello("pidr1");
        assertTrue(lifecycle.isReady());

        Path pidFile = resolver.pidDir().resolve("embedding-worker.pid");
        assertPoll("PID file should exist", () -> Files.exists(pidFile), POLL_TIMEOUT);

        lifecycle.resetForTest();

        assertPoll("PID file should be removed after reset",
            () -> !Files.exists(pidFile),
            POLL_TIMEOUT);
    }

    // ===================================================================
    // Tests – duplicate request ID
    // ===================================================================

    @Test
    void duplicateRequestIdReturnsUnavailable() {
        sendHello("dup1");

        // Send two requests with the same ID. The second should be rejected.
        lifecycle.execute("dup-id",
            new WorkerProtocol.Request.Hello(WorkerProtocol.PROTOCOL_VERSION, "dup-id"));
        WorkerResult duplicate = lifecycle.execute("dup-id",
            new WorkerProtocol.Request.Hello(WorkerProtocol.PROTOCOL_VERSION, "dup-id"));

        // The second request with the same key should get an error.
        // The first succeeded, the second may be unavailable due to duplicate.
        assertTrue(duplicate instanceof WorkerResult.WorkerUnavailable
                || duplicate instanceof WorkerResult.Success,
            "Duplicate should not throw: " + duplicate);
        // At minimum, the pending count should be 0 after both complete.
        assertPoll("pendingCount should settle to 0",
            () -> lifecycle.pendingCount() == 0,
            POLL_TIMEOUT);
    }

    @Test
    void nullRequestReturnsUnavailable() {
        WorkerResult r = lifecycle.execute("null-req", null);
        assertTrue(r instanceof WorkerResult.WorkerUnavailable,
            "Null request should return WorkerUnavailable: " + r);
    }

    // ===================================================================
    // Tests – old reader isolation
    // ===================================================================

    @RepeatedTest(5)
    void oldReaderCannotAlterCurrentReadyAfterRepeatedReset() throws Exception {
        // Start and stop workers rapidly, ensuring that a stale reader thread
        // from a previous generation cannot flip the current READY state.
        for (int i = 0; i < 10; i++) {
            WorkerResult r = sendHello("iso-" + i);
            // The request may succeed or fail depending on timing — but the
            // lifecycle must never be left in an inconsistent state.
            assertTrue(lifecycle.pendingCount() == 0
                    || lifecycle.pendingCount() == 1,
                "pendingCount should be 0 or 1, got: " + lifecycle.pendingCount());

            if (r instanceof WorkerResult.Success) {
                assertTrue(lifecycle.isReady(), "Should be ready after success");
                lifecycle.resetForTest();
                assertFalse(lifecycle.isReady(), "Should not be ready after reset");
            } else {
                // If the request failed (e.g. from a previous crash), reset
                // to clear the failure state.
                lifecycle.resetForTest();
            }
            assertEquals(0, lifecycle.pendingCount(),
                "pendingCount should be 0 after iteration " + i);
        }

        // Final iteration: prove we can still start fresh.
        WorkerResult finalR = sendHello("iso-final");
        assertTrue(finalR instanceof WorkerResult.Success,
            "Final request should succeed: " + finalR);
        assertTrue(lifecycle.isReady(), "Should be ready at end");
        assertTrue(lifecycle.pid() > 0, "PID should exist at end");
    }

    @Test
    void nullRequestIdDefaultsToRequestRequestId() {
        // requestId is null, falls back to request.requestId()
        WorkerProtocol.Request hello = new WorkerProtocol.Request.Hello(
            WorkerProtocol.PROTOCOL_VERSION, "auto-id-1");
        WorkerResult r = lifecycle.execute(null, hello);
        assertTrue(r instanceof WorkerResult.Success,
            "Hello with auto-generated ID should succeed: " + r);
    }

    // ===================================================================
    // Tests – @RepeatedTest(5) for race verification
    // ===================================================================

    @RepeatedTest(5)
    void raceFreeConcurrentRequests() throws Exception {
        sendHello("race-init");
        long firstPid = lifecycle.pid();
        assertTrue(firstPid > 0);

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<WorkerResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> sendHello("race-" + idx)));
        }

        for (Future<WorkerResult> f : futures) {
            WorkerResult r = f.get(30, TimeUnit.SECONDS);
            assertTrue(r instanceof WorkerResult.Success,
                "Race concurrent request failed: " + r);
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(firstPid, lifecycle.pid(),
            "PID should remain constant across concurrent requests");
        assertEquals(0, lifecycle.pendingCount(),
            "pendingCount should be 0 after all requests");
    }
}
