package com.drillnotebook.app.service;

import com.drillnotebook.app.config.PortablePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Spring-managed lifecycle for a single long-lived Rust embedding worker process.
 * <p>
 * The worker is <em>lazily</em> started — no process is spawned until the first
 * call to {@link #execute(String, WorkerProtocol.Request)}.  All APP_ROOT paths
 * are resolved through {@link PortablePathResolver}, and environment variables
 * are set so the worker never touches the system profile or user cache.
 * <p>
 * Thread‑safe.  Exactly one process at a time.  On crash or EOF, exactly one
 * automatic restart is attempted.  Callers receive a typed {@link WorkerResult}
 * — never a bare exception except for documented use‑after‑shutdown.
 */
@Service
public class EmbeddingWorkerLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingWorkerLifecycle.class);

    // -------------------------------------------------------------------
    // Timeout constants — package‑private so tests can shorten them
    // -------------------------------------------------------------------

    /** How long to wait for a hello handshake after spawning (5s per plan). */
    static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
    /** Per-request timeout applied to every future. */
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    /** How long to wait for graceful shutdown before force‑killing. */
    static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(5);
    /** How long to wait for force‑kill to take effect. */
    static final Duration FORCE_KILL_TIMEOUT = Duration.ofSeconds(3);

    static final int MAX_RESTART_ATTEMPTS = 1;
    /** PID file name written under {@code pidDir}. */
    static final String PID_FILE_NAME = "embedding-worker.pid";

    // -------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------

    private enum State {
        UNINITIALIZED,
        STARTING,
        READY,
        FAILED,
        SHUTDOWN
    }

    private final PortablePathResolver paths;
    private final ObjectMapper mapper;
    private final String workerExe;

    /** Alternative command list (for testing).  When non-null, used instead of workerExe. */
    private final List<String> commandExe;

    private final AtomicReference<State> state = new AtomicReference<>(State.UNINITIALIZED);
    private final AtomicReference<Process> process = new AtomicReference<>(null);
    private final AtomicReference<Writer> stdin = new AtomicReference<>(null);
    private final AtomicInteger restartCount = new AtomicInteger(0);

    /** Guards stdin writes — exactly one thread writes at a time. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Pending‑response map, <em>replaced</em> on each process generation so a
     * stale {@code readStdout} thread cannot fail futures belonging to the
     * replacement process.  Read/written via {@code volatile}.
     * <p>
     * Package‑private for tests that need to assert pending count.
     */
    volatile Map<String, CompletableFuture<WorkerProtocol.Response>> pending;

    /** Scheduled timeout tasks per requestId, cancelled on normal completion. */
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-worker-stdout-reader");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService stderrExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-worker-stderr-logger");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService timeoutExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embedding-worker-timeout");
            t.setDaemon(true);
            return t;
        });

    /** Injectable handshake timeout — shortened in tests. */
    volatile Duration handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;
    /** Injectable request timeout — shortened in tests. */
    volatile Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    // -------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------

    @Autowired
    public EmbeddingWorkerLifecycle(PortablePathResolver paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
        this.commandExe = null;
        this.pending = new ConcurrentHashMap<>();
        // Check system property first (for testing), then env var.
        String exe = System.getProperty("DRILL_EMBEDDING_WORKER_EXE");
        if (exe == null || exe.isBlank()) {
            exe = System.getenv("DRILL_EMBEDDING_WORKER_EXE");
        }
        this.workerExe = (exe != null && !exe.isBlank()) ? exe.trim() : "";
    }

    /**
     * Alternative constructor for testing — accepts a full command list
     * (e.g. {@code ["java", "-cp", "...", "com.example.FakeWorker"]}),
     * bypassing the single‑executable path.
     */
    EmbeddingWorkerLifecycle(PortablePathResolver paths, ObjectMapper mapper,
                             List<String> command) {
        this.paths = paths;
        this.mapper = mapper;
        this.commandExe = List.copyOf(command);
        this.workerExe = "";
        this.pending = new ConcurrentHashMap<>();
    }

    // -------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------

    /**
     * Execute a request against the worker process, starting it lazily if
     * needed.
     *
     * @param requestId unique identifier for the request (generated if blank)
     * @param request   the protocol request (must not be null)
     * @return typed result – never {@code null}
     */
    public WorkerResult execute(String requestId, WorkerProtocol.Request request) {
        if (request == null) {
            return new WorkerResult.WorkerUnavailable("Request must not be null");
        }

        // Validate executable path up front (skip when using command list).
        if (commandExe == null) {
            if (workerExe.isEmpty()) {
                return new WorkerResult.WorkerNotBuilt(
                    "DRILL_EMBEDDING_WORKER_EXE is not set. Build the embedding worker first.");
            }
            Path exePath = Path.of(workerExe);
            if (!exePath.isAbsolute()) {
                return new WorkerResult.WorkerNotBuilt(
                    "Worker executable path must be absolute");
            }
            if (!Files.isRegularFile(exePath)) {
                return new WorkerResult.WorkerNotBuilt(
                    "Worker executable is not a regular file");
            }
            if (!Files.isExecutable(exePath)) {
                return new WorkerResult.WorkerNotBuilt(
                    "Worker executable is not executable");
            }
        }

        // Derive request ID: prefer caller-supplied, fall back to request's own id,
        // then generated.
        String rid;
        if (requestId != null && !requestId.isBlank()) {
            rid = requestId;
        } else {
            rid = request.requestId();
            if (rid == null || rid.isBlank()) {
                rid = UUID.randomUUID().toString();
            }
        }

        try {
            return executeInner(rid, request);
        } catch (IllegalStateException e) {
            // Shutdown is a programming error — let it propagate.
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error in worker execute (requestId={})", rid, e);
            return new WorkerResult.WorkerUnavailable("Internal error: " + e.getMessage());
        }
    }

    /**
     * Check if the worker is currently alive and ready.
     */
    public boolean isReady() {
        return state.get() == State.READY && process.get() != null && process.get().isAlive();
    }

    /**
     * Check if the worker exe is configured (whether or not it has been
     * started).
     */
    public boolean isConfigured() {
        return commandExe != null || (!workerExe.isEmpty() && Files.exists(Path.of(workerExe)));
    }

    /**
     * Get the current process PID, or -1 if not running.
     */
    public long pid() {
        Process p = process.get();
        if (p == null || !p.isAlive()) return -1;
        return p.pid();
    }

    /**
     * Get the configured worker executable path (may be empty or point to a
     * non-existent file).
     */
    public String workerExe() {
        return workerExe;
    }

    // -------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------

    private WorkerResult executeInner(String requestId, WorkerProtocol.Request request)
            throws IOException {
        // Ensure process is running.  Distinguish shutdown (programming
        // error → propagate) from max-restart (degraded → WorkerUnavailable).
        try {
            ensureRunning();
        } catch (IllegalStateException e) {
            if (state.get() == State.SHUTDOWN) throw e;
            return new WorkerResult.WorkerUnavailable(e.getMessage());
        }

        // Capture the current pending map (volatile read) — guarantees this
        // future is registered with the right process generation.
        Map<String, CompletableFuture<WorkerProtocol.Response>> myPending = pending;

        // Create the future and register it before writing.
        CompletableFuture<WorkerProtocol.Response> future = new CompletableFuture<>();
        CompletableFuture<WorkerProtocol.Response> existing = myPending.putIfAbsent(requestId, future);
        if (existing != null) {
            return new WorkerResult.WorkerUnavailable("Duplicate request ID: " + requestId);
        }

        // Schedule timeout against the local requestTimeout.
        Duration rt = requestTimeout;
        ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(() -> {
            future.completeExceptionally(new TimeoutException(
                "Request timed out after " + rt.getSeconds() + "s"));
        }, rt.toMillis(), TimeUnit.MILLISECONDS);
        timeoutTasks.put(requestId, timeoutTask);

        // Serialize request and write to stdin (under lock).
        String json = mapper.writeValueAsString(request) + "\n";
        writeLock.lock();
        try {
            Writer w = stdin.get();
            if (w == null) {
                future.completeExceptionally(new IOException("stdin not available"));
                myPending.remove(requestId);
                timeoutTask.cancel(false);
                timeoutTasks.remove(requestId);
                return new WorkerResult.WorkerUnavailable("Worker stdin not available");
            }
            w.write(json);
            w.flush();
        } finally {
            writeLock.unlock();
        }

        // Wait for response.
        WorkerProtocol.Response response;
        try {
            response = future.get(rt.toMillis() + 5000, TimeUnit.MILLISECONDS);
            // Cancel timeout on normal completion.
            timeoutTask.cancel(false);
            timeoutTasks.remove(requestId);
        } catch (ExecutionException e) {
            myPending.remove(requestId);
            timeoutTask.cancel(false);
            timeoutTasks.remove(requestId);
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                // Scheduled timeout fired — kill the process so one restart
                // can be consumed by the next request.
                killCurrentProcess("Request timed out");
                return new WorkerResult.WorkerUnavailable("Request timed out");
            }
            String msg;
            if (cause instanceof IOException) {
                msg = cause.getMessage();
            } else {
                msg = "Request failed: " + (cause != null ? cause.getMessage() : e.getMessage());
            }
            return new WorkerResult.WorkerUnavailable(msg);
        } catch (TimeoutException e) {
            myPending.remove(requestId);
            timeoutTask.cancel(false);
            timeoutTasks.remove(requestId);
            // future.get() itself timed out (rare — scheduled timeout should
            // fire first). Kill the process so restart can be consumed.
            killCurrentProcess("Request timed out");
            return new WorkerResult.WorkerUnavailable("Request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            myPending.remove(requestId);
            timeoutTask.cancel(false);
            timeoutTasks.remove(requestId);
            return new WorkerResult.WorkerUnavailable("Interrupted");
        } catch (CancellationException e) {
            myPending.remove(requestId);
            timeoutTasks.remove(requestId);
            return new WorkerResult.WorkerUnavailable("Request cancelled");
        }
        myPending.remove(requestId);

        // Interpret the response (Java-17-compatible instanceof chain).
        if (response instanceof WorkerProtocol.Response.Ready) {
            log.debug("Worker ready (requestId={})", response.requestId());
            return new WorkerResult.Success(null);
        }
        if (response instanceof WorkerProtocol.Response.ModelLoaded r) {
            log.debug("Model loaded (requestId={}, dims={})", r.requestId(), r.dimensions());
            return new WorkerResult.Success(null);
        }
        if (response instanceof WorkerProtocol.Response.EmbedResult r) {
            return new WorkerResult.Success(r.embeddings());
        }
        if (response instanceof WorkerProtocol.Response.Ok) {
            log.debug("Worker ok (requestId={})", response.requestId());
            return new WorkerResult.Success(null);
        }
        if (response instanceof WorkerProtocol.Response.Error err) {
            log.warn("Worker error (requestId={}, code={}, msg={})",
                err.requestId(), err.code(), err.message());
            return new WorkerResult.WorkerUnavailable(
                "Worker error: " + err.code() + " - " + err.message());
        }

        // Unknown response type — should never happen with sealed hierarchy.
        log.warn("Unknown response type: {}", response != null ? response.getClass() : "null");
        return new WorkerResult.WorkerUnavailable("Unknown response type");
    }

    /**
     * Kill the current process if alive and transition to FAILED, so the one
     * restart can be consumed by the next request.
     */
    private void killCurrentProcess(String reason) {
        Process p = process.getAndSet(null);
        if (p != null && p.isAlive()) {
            log.warn("Killing worker process (pid={}): {}", p.pid(), reason);
            p.destroyForcibly();
            try {
                p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        stdin.set(null);
        removePidFile();
        state.set(State.FAILED);
    }

    /**
     * Ensure a process is running.  Starts lazily on first call or after a
     * crash (one restart attempt).  Caller holds the monitor lock.
     */
    private synchronized void ensureRunning() {
        State s = state.get();
        if (s == State.SHUTDOWN) {
            throw new IllegalStateException("Worker lifecycle is shut down");
        }

        // Already ready and alive — nothing to do.
        if (s == State.READY) {
            Process p = process.get();
            if (p != null && p.isAlive()) return;
            // Process died — transition to recovery.
            log.warn("Worker process died unexpectedly");
            state.compareAndSet(State.READY, State.FAILED);
            s = State.FAILED;
        }

        if (s == State.FAILED) {
            int retries = restartCount.get();
            if (retries >= MAX_RESTART_ATTEMPTS) {
                throw new IllegalStateException(
                    "Worker exceeded max restart attempts (" + MAX_RESTART_ATTEMPTS + ")");
            }
            log.info("Attempting worker restart #{}/{}", retries + 1, MAX_RESTART_ATTEMPTS);
            restartCount.incrementAndGet();
            // Fall through to start.
        }

        // Start the process.
        startProcess();
    }

    /**
     * Actually spawn the worker process, handshake, and start readers.
     */
    private void startProcess() {
        state.set(State.STARTING);

        ensureDirectories();

        Path runtimeDir = paths.runtime();
        Path workerLogFile = paths.workerLogs().resolve("embedding-worker-stderr.log");

        try {
            // Replace the pending map so a stale reader cannot fail our futures.
            Map<String, CompletableFuture<WorkerProtocol.Response>> freshPending =
                new ConcurrentHashMap<>();
            pending = freshPending;

            ProcessBuilder pb;
            if (commandExe != null) {
                pb = new ProcessBuilder(commandExe);
            } else {
                pb = new ProcessBuilder(Path.of(workerExe).toString());
            }
            pb.directory(runtimeDir.toFile());

            // Set environment: all paths under APP_ROOT.
            Map<String, String> env = pb.environment();
            env.put("FASTEMBED_CACHE_DIR", paths.fastembedCache().toString());
            env.put("HF_HOME", paths.fastembedCache().resolve("huggingface").toString());
            env.put("TEMP", paths.tempDir().toString());
            env.put("TMP", paths.tempDir().toString());
            env.put("APP_ROOT", paths.root().toString());
            if (!env.containsKey("PATH")) {
                env.put("PATH", System.getenv("PATH"));
            }

            Process p = pb.start();
            process.set(p);
            stdin.set(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            writePidFile(p.pid());

            // Start stderr logger.
            stderrExecutor.submit(() -> logStderr(p, workerLogFile));

            // Start stdout reader — pass the current pending map so it
            // operates only on this generation's futures.
            readerExecutor.submit(() -> readStdout(p, freshPending));

            // Perform hello handshake.
            String handshakeId = "handshake-" + UUID.randomUUID().toString().substring(0, 8);
            CompletableFuture<WorkerProtocol.Response> helloFuture = new CompletableFuture<>();
            freshPending.put(handshakeId, helloFuture);

            Duration ht = handshakeTimeout;
            ScheduledFuture<?> handshakeTimeoutTask = timeoutExecutor.schedule(() ->
                helloFuture.completeExceptionally(
                    new TimeoutException("Hello handshake timed out")),
                ht.toMillis(), TimeUnit.MILLISECONDS);

            try {
                // Write hello.
                WorkerProtocol.Request.Hello hello = new WorkerProtocol.Request.Hello(
                    WorkerProtocol.PROTOCOL_VERSION, handshakeId);
                String helloJson = mapper.writeValueAsString(hello) + "\n";
                stdin.get().write(helloJson);
                stdin.get().flush();

                // Wait for ready.
                WorkerProtocol.Response resp = helloFuture.get(
                    ht.toMillis() + 1000, TimeUnit.MILLISECONDS);
                handshakeTimeoutTask.cancel(false);
                if (!(resp instanceof WorkerProtocol.Response.Ready)) {
                    throw new IOException("Expected ready response, got: " +
                        (resp != null ? resp.getClass().getSimpleName() : "null"));
                }

                log.info("Worker ready (pid={})", p.pid());
                state.set(State.READY);
                // restartCount is NOT reset here — initial start has budget 0,
                // first recovery increments to 1 and stays at 1.
            } catch (TimeoutException e) {
                handshakeTimeoutTask.cancel(false);
                log.warn("Hello handshake timed out");
                p.destroyForcibly();
                try {
                    p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                removePidFile();
                state.set(State.FAILED);
                throw new IOException("Hello handshake timed out after " + ht.getSeconds() + "s");
            } catch (ExecutionException e) {
                handshakeTimeoutTask.cancel(false);
                log.warn("Hello handshake failed: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                p.destroyForcibly();
                try {
                    p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                removePidFile();
                state.set(State.FAILED);
                throw new IOException("Hello handshake failed", e);
            } catch (InterruptedException e) {
                handshakeTimeoutTask.cancel(false);
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                try {
                    p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                removePidFile();
                state.set(State.FAILED);
                throw new IOException("Hello handshake interrupted");
            } finally {
                freshPending.remove(handshakeId);
            }
        } catch (IOException e) {
            log.error("Failed to start worker process: {}", e.getMessage());
            state.set(State.FAILED);
            throw new RuntimeException("Failed to start worker process", e);
        }
    }

    /**
     * Read NDJSON lines from the worker's stdout.  Operates only on
     * {@code myPending} so an EOF from a replaced process cannot fail
     * the replacement's futures.
     */
    private void readStdout(Process p,
                            Map<String, CompletableFuture<WorkerProtocol.Response>> myPending) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    WorkerProtocol.Response resp = mapper.readValue(
                        line, WorkerProtocol.Response.class);
                    String rid = resp.requestId();
                    CompletableFuture<WorkerProtocol.Response> future = myPending.remove(rid);
                    if (future != null) {
                        future.complete(resp);
                    } else {
                        log.warn("Received response for unknown requestId={}: {}", rid, line);
                    }
                } catch (Exception e) {
                    log.warn("Contamination on worker stdout, terminating: {}",
                        e.getMessage());
                    // Contamination is a protocol failure: fail pending,
                    // kill the process, transition to FAILED.
                    failMapPending(myPending,
                        "Worker process contaminated (non-JSON on stdout)");
                    p.destroyForcibly();
                    try {
                        p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    // Only transition if this exact process is still current.
                    if (process.get() == p) {
                        removePidFile();
                        state.set(State.FAILED);
                    }
                    return;
                }
            }
            // Normal EOF.
            log.debug("Worker stdout closed (EOF)");
        } catch (IOException e) {
            log.debug("Error reading worker stdout: {}", e.getMessage());
        } finally {
            // Only fail pending and transition state if this process is still
            // the current one — avoids a stale generation overwriting a
            // replacement process's state.
            failMapPending(myPending, "Worker process terminated unexpectedly");
            if (process.get() == p) {
                removePidFile();
                state.compareAndSet(State.READY, State.FAILED);
            }
        }
    }

    /**
     * Fail every future in {@code map} with the given message, then clear.
     */
    private static void failMapPending(
            Map<String, CompletableFuture<WorkerProtocol.Response>> map, String message) {
        if (map.isEmpty()) return;
        for (Iterator<Map.Entry<String, CompletableFuture<WorkerProtocol.Response>>> it =
                 map.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, CompletableFuture<WorkerProtocol.Response>> entry = it.next();
            entry.getValue().completeExceptionally(new IOException(message));
            it.remove();
        }
    }

    /**
     * Read the worker's stderr and append to a log file under APP_ROOT.
     */
    private static void logStderr(Process p, Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            log.warn("Cannot create worker log dir: {}", e.getMessage());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
             Writer fileWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                 StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileWriter.write(java.time.LocalDateTime.now().toString());
                fileWriter.write(" ");
                fileWriter.write(line);
                fileWriter.write(System.lineSeparator());
                fileWriter.flush();
            }
        } catch (IOException e) {
            log.debug("Stderr reader finished: {}", e.getMessage());
        }
    }

    /**
     * Ensure all APP_ROOT subdirectories used by the worker exist.
     */
    private void ensureDirectories() {
        try {
            Files.createDirectories(paths.runtime());
            Files.createDirectories(paths.workerLogs());
            Files.createDirectories(paths.fastembedCache());
            Files.createDirectories(paths.fastembedCache().resolve("huggingface"));
            Files.createDirectories(paths.tempDir());
            Files.createDirectories(paths.pidDir());
        } catch (IOException e) {
            log.error("Failed to create APP_ROOT directories", e);
            throw new RuntimeException("Failed to create APP_ROOT directories", e);
        }
    }

    /**
     * Write the worker's PID to {@code pidDir/embedding-worker.pid}.
     */
    private void writePidFile(long pid) {
        Path pidFile = paths.pidDir().resolve(PID_FILE_NAME);
        try {
            Files.writeString(pidFile, Long.toString(pid), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("Failed to write PID file: {}", e.getMessage());
        }
    }

    /**
     * Remove the PID file if it belongs to our current process.
     */
    private void removePidFile() {
        removePidFile(-1);
    }

    /**
     * Remove the PID file if it matches the given PID; if pid < 0, read from
     * the file and only delete if it belongs to our process.
     */
    private void removePidFile(long expectedPid) {
        Path pidFile = paths.pidDir().resolve(PID_FILE_NAME);
        try {
            if (Files.exists(pidFile)) {
                String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                long filePid = content.isEmpty() ? -1 : Long.parseLong(content);
                long actualExpected = expectedPid >= 0 ? expectedPid : filePid;
                Process current = process.get();
                if (current != null && current.isAlive() && current.pid() == actualExpected) {
                    // PID still belongs to our process — don't remove yet.
                    return;
                }
                if (filePid == actualExpected || actualExpected < 0) {
                    Files.deleteIfExists(pidFile);
                }
            }
        } catch (IOException | NumberFormatException e) {
            log.debug("Could not remove PID file: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------

    /**
     * Gracefully shut down the worker process.
     * <p>
     * Sends a {@code shutdown} request registered in the pending map so the
     * reader thread can correlate the response and avoid the "unknown
     * requestId" warning.  Waits up to {@link #SHUTDOWN_GRACE_PERIOD}, then
     * force-kills if still alive.
     */
    public synchronized void shutdown() {
        State s = state.get();
        if (s == State.SHUTDOWN || s == State.UNINITIALIZED) return;

        log.info("Shutting down worker (pid={})", pid());

        // Try graceful shutdown.
        Process p = process.get();
        Writer w = stdin.get();
        if (p != null && p.isAlive() && w != null) {
            String rid = "shutdown-" + UUID.randomUUID().toString().substring(0, 8);
            CompletableFuture<WorkerProtocol.Response> shutdownFuture =
                new CompletableFuture<>();
            pending.put(rid, shutdownFuture);
            try {
                String shutdownJson = mapper.writeValueAsString(
                    new WorkerProtocol.Request.Shutdown(
                        WorkerProtocol.PROTOCOL_VERSION, rid)) + "\n";
                w.write(shutdownJson);
                w.flush();
            } catch (IOException e) {
                log.debug("Failed to send shutdown request: {}", e.getMessage());
            }
            // Wait briefly for the response (don't block long).
            try {
                shutdownFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.debug("Shutdown response not received within 2s (process may already be exiting)");
            } catch (ExecutionException e) {
                log.debug("Shutdown response future failed: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Shutdown response wait interrupted");
            }
            // Wait for process exit.
            try {
                if (!p.waitFor(SHUTDOWN_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Worker did not exit gracefully, force-killing");
                    p.destroyForcibly();
                    try {
                        p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }

        // Clean up.
        failMapPending(pending, "Worker is shutting down");
        state.set(State.SHUTDOWN);
        process.set(null);
        stdin.set(null);
        removePidFile();

        // Cancel all pending timeout tasks.
        for (ScheduledFuture<?> task : timeoutTasks.values()) {
            task.cancel(false);
        }
        timeoutTasks.clear();

        // Shut down executors (daemon threads, but release resources).
        readerExecutor.shutdownNow();
        stderrExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();

        log.info("Worker shut down complete");
    }

    @Override
    public void close() {
        shutdown();
    }

    @jakarta.annotation.PreDestroy
    public void preDestroy() {
        shutdown();
    }

    // -------------------------------------------------------------------
    // Package‑private helpers for testing
    // -------------------------------------------------------------------

    /**
     * Reset the lifecycle state (for testing only).  Kills any running
     * process.  After return, the lifecycle can be restarted.
     */
    synchronized void resetForTest() {
        Process p = process.getAndSet(null);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            try {
                p.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        failMapPending(pending, "Test reset");
        stdin.set(null);
        removePidFile();
        for (ScheduledFuture<?> task : timeoutTasks.values()) {
            task.cancel(false);
        }
        timeoutTasks.clear();
        state.set(State.UNINITIALIZED);
        restartCount.set(0);
        // Fresh pending map for the next process generation.
        pending = new ConcurrentHashMap<>();
    }

    /**
     * Number of pending requests (for test assertions).
     */
    int pendingCount() {
        return pending.size();
    }

    /**
     * Expose the current pending map for test inspection of remaining entries
     * after a timeout.
     */
    Map<String, CompletableFuture<WorkerProtocol.Response>> pendingMap() {
        return pending;
    }

    /** Package-private: current restart count (for tests). */
    int restartCount() {
        return restartCount.get();
    }
}
