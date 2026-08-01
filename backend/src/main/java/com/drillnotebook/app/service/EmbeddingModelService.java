package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local embedding model lifecycle: catalog exposure, resumable verified
 * download, atomic finalize, activation (embedding-space switch), disable and
 * thorough uninstall.
 *
 * <p>State machine (Canonical Contracts):
 * {@code AVAILABLE → DOWNLOADING → VERIFYING → READY → UNINSTALLING → AVAILABLE}
 * with recoverable {@code FAILED}/{@code PAUSED} branches. All heavy I/O runs
 * on a single background thread so uninstall can never race an open download
 * stream; API methods only flip flags and enqueue tasks.
 */
@Service
public class EmbeddingModelService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelService.class);

    static final int MAX_HTTP_ATTEMPTS = 3;
    static final long[] BACKOFF_SECONDS = {1, 2, 4};
    static final long FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String MANIFEST_FILE = "manifest.json";

    /** Result carrying the HTTP status the controller should emit. */
    public record ApiResult(int status, Map<String, Object> body) {}

    /** Injectable finalize move — tests can simulate ATOMIC_MOVE_UNSUPPORTED. */
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }

    private static final class JobHandle {
        final String jobId = UUID.randomUUID().toString();
        final String catalogId;
        final boolean activateAfterDownload;
        volatile boolean cancelRequested;
        volatile boolean uninstallRequested;
        volatile boolean terminal;

        JobHandle(String catalogId, boolean activateAfterDownload) {
            this.catalogId = catalogId;
            this.activateAfterDownload = activateAfterDownload;
        }
    }

    private final ModelCatalog catalog;
    private final EmbeddingModelRepository models;
    private final EmbeddingJobRepository jobs;
    private final EmbeddingProviderRegistry providers;
    private final EmbeddingWorkerLifecycle lifecycle;
    private final EmbeddingJobExecutor executor;
    private final ModelDownloadTransport transport;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Path embeddingModelsRoot;
    private final boolean autoRecover;
    private final RetrievalMaintenanceService maintenance;
    private final HuggingFaceCatalogService hfCatalog;

    private final ExecutorService tasks = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-model-tasks");
        t.setDaemon(true);
        return t;
    });

    /** Download jobs by jobId — the only handles addressable by the cancel API. */
    private final Map<String, JobHandle> downloadJobs = new ConcurrentHashMap<>();
    private final Map<String, JobHandle> activeDownloadByModel = new ConcurrentHashMap<>();
    private final Map<String, JobHandle> activeUninstallByModel = new ConcurrentHashMap<>();
    private final Map<String, String> reindexJobBySpace = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> liveProgressByModel = new ConcurrentHashMap<>();

    /** Test hook: called with (fileName, bytesSoFar) while streaming. */
    volatile java.util.function.ObjLongConsumer<String> progressHook;
    /** Finalize move, replaceable in tests. */
    volatile AtomicMover atomicMover =
            (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);

    public EmbeddingModelService(
            ModelCatalog catalog,
            EmbeddingModelRepository models,
            EmbeddingJobRepository jobs,
            EmbeddingProviderRegistry providers,
            EmbeddingWorkerLifecycle lifecycle,
            EmbeddingJobExecutor executor,
            ModelDownloadTransport transport,
            ObjectMapper mapper,
            PlatformTransactionManager txManager,
            com.drillnotebook.app.config.PortablePathResolver paths,
            @Value("${drill.embedding.models.auto-recover:true}") boolean autoRecover,
            RetrievalMaintenanceService maintenance,
            HuggingFaceCatalogService hfCatalog) {
        this.catalog = catalog;
        this.models = models;
        this.jobs = jobs;
        this.providers = providers;
        this.lifecycle = lifecycle;
        this.executor = executor;
        this.transport = transport;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.embeddingModelsRoot = paths.embeddingModels();
        this.autoRecover = autoRecover;
        this.maintenance = maintenance;
        this.hfCatalog = hfCatalog;
    }

    // ── Startup recovery ────────────────────────────────────────────────────

    /** Resolve a model from built-in catalog first, then online catalog cache. */
    private ModelCatalog.CatalogModel resolveModel(String catalogId) {
        ModelCatalog.CatalogModel builtIn = catalog.model(catalogId);
        if (builtIn != null) return builtIn;
        if (hfCatalog != null) {
            HuggingFaceCatalogService.CatalogResult online = hfCatalog.getCatalog(false);
            return online.models().stream()
                    .filter(m -> m.id().equals(catalogId))
                    .findFirst().orElse(null);
        }
        return null;
    }

    @PostConstruct
    void init() {
        for (ModelCatalog.CatalogModel model : catalog.models()) {
            models.ensureRow(model.id(), model.providerModelId(),
                    model.artifactRevision(), model.dimensions());
        }
        if (autoRecover) {
            recover();
        }
    }

    /** Fixed startup recovery rules per installation state. */
    void recover() {
        for (ModelCatalog.CatalogModel model : catalog.models()) {
            Map<String, Object> row = models.find(model.id());
            if (row == null) continue;
            String state = (String) row.get("installation_state");
            switch (state) {
                case "DOWNLOADING" -> models.updateState(model.id(), "PAUSED");
                case "VERIFYING" -> {
                    JobHandle handle = new JobHandle(model.id(), false);
                    activeDownloadByModel.put(model.id(), handle);
                    downloadJobs.put(handle.jobId, handle);
                    tasks.submit(() -> {
                        try {
                            verifyAndFinalize(model, handle);
                        } finally {
                            handle.terminal = true;
                            activeDownloadByModel.remove(model.id(), handle);
                        }
                    });
                }
                case "READY" -> {
                    if (!manifestFilesValid(model)) {
                        models.updateStateAndError(model.id(), "FAILED",
                                "CORRUPT: manifest/file mismatch at startup");
                    } else {
                        restoreProviderIfSelected(model);
                    }
                }
                case "UNINSTALLING" -> {
                    JobHandle handle = new JobHandle(model.id(), false);
                    activeUninstallByModel.put(model.id(), handle);
                    tasks.submit(() -> runUninstall(model, handle));
                }
                default -> { /* AVAILABLE / FAILED / PAUSED need no action */ }
            }
        }
    }

    private void restoreProviderIfSelected(ModelCatalog.CatalogModel model) {
        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        if (selected == null) return;
        boolean matches = LocalEmbeddingProvider.PROVIDER_TYPE.equals(selected.get("provider_type"))
                && model.providerModelId().equals(selected.get("model_identifier"));
        if (matches) {
            registerLocalProvider(model);
            executor.wake();
        }
    }

    @PreDestroy
    void shutdown() {
        tasks.shutdownNow();
        try {
            tasks.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Catalog API ─────────────────────────────────────────────────────────

    /** GET catalog: built-in data merged with installation state; no network. */
    public Map<String, Object> catalogResponse() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ModelCatalog.CatalogModel model : catalog.models()) {
            Map<String, Object> row = models.find(model.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", model.id());
            m.put("providerModelId", model.providerModelId());
            m.put("artifactRevision", model.artifactRevision());
            m.put("displayName", model.displayName());
            m.put("license", model.license());
            m.put("languages", model.languages());
            m.put("dimensions", model.dimensions());
            m.put("inventorySizeBytes", model.inventorySizeBytes());
            m.put("installationState", row == null ? "AVAILABLE" : row.get("installation_state"));
            m.put("downloadError", row == null ? null : row.get("download_error"));
            Map<String, Object> live = liveProgressByModel.get(model.id());
            m.put("downloadProgress", live != null ? live
                    : parseJsonOrNull(row == null ? null : (String) row.get("download_progress_json")));
            out.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogVersion", catalog.catalogVersion());
        body.put("models", out);
        return body;
    }

    // ── Download API ────────────────────────────────────────────────────────

    public synchronized ApiResult download(String catalogId, Map<String, Object> body) {
        ModelCatalog.CatalogModel model = resolveModel(catalogId);
        if (model == null) return notFound("unknown model: " + catalogId);
        Object flag = body == null ? null : body.get("activateAfterDownload");
        if (!(flag instanceof Boolean activateAfter)) {
            throw new IllegalArgumentException("activateAfterDownload 必须显式提供");
        }
        Map<String, Object> row = models.find(catalogId);
        String state = row == null ? "AVAILABLE" : (String) row.get("installation_state");

        JobHandle active = activeDownloadByModel.get(catalogId);
        if (active != null && !active.terminal) {
            return new ApiResult(200, Map.of("jobId", active.jobId, "state", state));
        }
        if ("READY".equals(state)) {
            return conflict("ALREADY_INSTALLED", "模型已安装");
        }
        if ("UNINSTALLING".equals(state)) {
            return conflict("UNINSTALL_IN_PROGRESS", "模型正在卸载");
        }

        JobHandle handle = new JobHandle(catalogId, activateAfter);
        downloadJobs.put(handle.jobId, handle);
        activeDownloadByModel.put(catalogId, handle);
        models.updateStateAndError(catalogId, "DOWNLOADING", null);
        persistProgress(model, handle, Map.of());
        tasks.submit(() -> {
            try {
                runDownload(model, handle);
            } finally {
                handle.terminal = true;
                activeDownloadByModel.remove(catalogId, handle);
            }
        });
        return new ApiResult(202, Map.of("jobId", handle.jobId, "state", "DOWNLOADING"));
    }

    public ApiResult cancelDownload(String jobId) {
        JobHandle handle = downloadJobs.get(jobId);
        if (handle == null) return notFound("unknown download job: " + jobId);
        if (handle.terminal) {
            Map<String, Object> row = models.find(handle.catalogId);
            String state = row == null ? "AVAILABLE" : (String) row.get("installation_state");
            return new ApiResult(200, Map.of("jobId", jobId, "state", state));
        }
        handle.cancelRequested = true;
        return new ApiResult(202, Map.of("jobId", jobId, "state", "CANCEL_REQUESTED"));
    }

    // ── Activate / disable / uninstall API ──────────────────────────────────

    public ApiResult activate(String catalogId) {
        ModelCatalog.CatalogModel model = resolveModel(catalogId);
        if (model == null) return notFound("unknown model: " + catalogId);
        Map<String, Object> row = models.find(catalogId);
        String state = row == null ? "AVAILABLE" : (String) row.get("installation_state");
        if (!"READY".equals(state)) {
            return conflict("NOT_READY", "模型未安装完成，当前状态 " + state);
        }
        if (!manifestFilesValid(model)) {
            models.updateStateAndError(catalogId, "FAILED", "CORRUPT: manifest/file mismatch");
            return conflict("MODEL_CORRUPT", "模型文件与 manifest 不符，需要重新下载");
        }

        String canonicalJson = EmbeddingSpaceContracts.localCanonicalJson(
                model, catalog.catalogVersion());
        String spaceId = EmbeddingSpaceContracts.spaceId(canonicalJson);

        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        String oldSpaceId = null;
        if (selected != null) {
            String currentSpaceId = (String) selected.get("embedding_space_id");
            if (spaceId.equals(currentSpaceId)) {
                String spaceState = (String) selected.get("state");
                if ("REBUILDING".equals(spaceState) || "ACTIVE".equals(spaceState)) {
                    String reindexJobId = reindexJobBySpace.computeIfAbsent(
                            spaceId, k -> UUID.randomUUID().toString());
                    return new ApiResult(200, Map.of(
                            "embeddingSpaceId", spaceId,
                            "reindexJobId", reindexJobId,
                            "state", spaceState));
                }
            }
            // Switching away from a different space: its vectors become stale
            // once deselected+DISABLED and are removed asynchronously below.
            if (!spaceId.equals(currentSpaceId)) {
                oldSpaceId = currentSpaceId;
            }
        }

        providers.withSpaceLock(() -> {
            tx.executeWithoutResult(status -> {
                models.deselectCurrentSpace();
                models.upsertSelectedRebuildingSpace(spaceId, canonicalJson,
                        LocalEmbeddingProvider.PROVIDER_TYPE, model.providerModelId(),
                        model.dimensions());
                models.enqueueMissingJobs(spaceId, "activate-backfill");
                double coverage = jobs.computeCoverage("NOTEBOOK", spaceId);
                jobs.updateSpaceCoverage(spaceId, coverage);
                jobs.activateSpaceIfComplete(spaceId);
            });
            registerLocalProvider(model);
        });
        executor.wake();
        if (oldSpaceId != null) {
            maintenance.scheduleDisabledSpaceCleanup(oldSpaceId);
        }

        String reindexJobId = UUID.randomUUID().toString();
        reindexJobBySpace.put(spaceId, reindexJobId);
        Map<String, Object> space = models.findSpace(spaceId);
        return new ApiResult(202, Map.of(
                "embeddingSpaceId", spaceId,
                "reindexJobId", reindexJobId,
                "state", space == null ? "REBUILDING" : space.get("state")));
    }

    public ApiResult disable(String catalogId) {
        ModelCatalog.CatalogModel model = resolveModel(catalogId);
        if (model == null) return notFound("unknown model: " + catalogId);
        providers.withSpaceLock(() -> {
            tx.executeWithoutResult(status ->
                    models.disableSpacesForModel(
                            LocalEmbeddingProvider.PROVIDER_TYPE, model.providerModelId()));
            clearProviderIfModel(model);
        });
        return new ApiResult(200, Map.of("state", "DISABLED"));
    }

    public synchronized ApiResult uninstall(String catalogId, boolean confirm) {
        if (!confirm) {
            throw new IllegalArgumentException("彻底卸载必须携带 confirm=true");
        }
        ModelCatalog.CatalogModel model = resolveModel(catalogId);
        if (model == null) return notFound("unknown model: " + catalogId);

        JobHandle existing = activeUninstallByModel.get(catalogId);
        if (existing != null && !existing.terminal) {
            return new ApiResult(200, Map.of("jobId", existing.jobId, "state", "UNINSTALLING"));
        }

        // Uninstall takes priority: stop in-flight download/verify I/O first.
        JobHandle download = activeDownloadByModel.get(catalogId);
        if (download != null) {
            download.uninstallRequested = true;
            download.cancelRequested = true;
        }
        JobHandle handle = new JobHandle(catalogId, false);
        activeUninstallByModel.put(catalogId, handle);
        tx.executeWithoutResult(status -> {
            models.updateStateAndError(catalogId, "UNINSTALLING", null);
            models.markSpacesUninstalling(
                    LocalEmbeddingProvider.PROVIDER_TYPE, model.providerModelId());
        });
        clearProviderIfModel(model);
        // Single-thread executor: this runs only after any download task exits.
        tasks.submit(() -> runUninstall(model, handle));
        return new ApiResult(202, Map.of("jobId", handle.jobId, "state", "UNINSTALLING"));
    }

    // ── Download worker ─────────────────────────────────────────────────────

    private void runDownload(ModelCatalog.CatalogModel model, JobHandle handle) {
        try {
            Path staging = stagingDir(model);
            Files.createDirectories(staging);

            long remaining = remainingBytes(model, staging);
            long usable = Files.getFileStore(staging).getUsableSpace();
            if (usable < remaining + FREE_SPACE_MARGIN_BYTES) {
                fail(model, handle, "INSUFFICIENT_DISK_SPACE",
                        "usable=" + usable + " needed=" + (remaining + FREE_SPACE_MARGIN_BYTES));
                return;
            }

            for (ModelCatalog.CatalogFile file : model.files()) {
                if (handle.uninstallRequested) return;
                if (handle.cancelRequested) {
                    pause(model, handle);
                    return;
                }
                Path complete = staging.resolve(file.name());
                if (Files.exists(complete) && Files.size(complete) == file.sizeBytes()) {
                    continue;
                }
                if (!downloadFile(model, file, staging, handle)) {
                    return; // state already transitioned (PAUSED/FAILED)
                }
            }
            models.updateState(model.id(), "VERIFYING");
            verifyAndFinalize(model, handle);
        } catch (Exception e) {
            log.warn("model download failed: {}", model.id(), e);
            fail(model, handle, "DOWNLOAD_FAILED", String.valueOf(e.getMessage()));
        }
    }

    /** Marker for HTTP errors that must not be retried (4xx except 408/429). */
    private static final class NonRetryableHttpException extends IOException {
        NonRetryableHttpException(String message) { super(message); }
    }

    private boolean downloadFile(
            ModelCatalog.CatalogModel model, ModelCatalog.CatalogFile file,
            Path staging, JobHandle handle) throws IOException {
        URI uri = URI.create(model.baseUrl() + file.name());
        Path part = staging.resolve(file.name() + ".part");
        Path meta = staging.resolve(file.name() + ".part.meta.json");
        int attempts = 0;
        while (true) {
            if (handle.uninstallRequested) return false;
            if (handle.cancelRequested) {
                pause(model, handle);
                return false;
            }
            try {
                if (attemptFile(model, file, uri, part, meta, staging, handle)) {
                    return true;
                }
                return false; // paused mid-stream, state already set
            } catch (NonRetryableHttpException e) {
                fail(model, handle, "DOWNLOAD_FAILED", file.name() + ": " + e.getMessage());
                return false;
            } catch (IOException e) {
                attempts++;
                if (attempts >= MAX_HTTP_ATTEMPTS) {
                    fail(model, handle, "DOWNLOAD_FAILED",
                            file.name() + ": " + e.getMessage() + " (attempts=" + attempts + ")");
                    return false;
                }
                if (!sleepSeconds(BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)],
                        handle)) {
                    pause(model, handle);
                    return false;
                }
            }
        }
    }

    /**
     * One HTTP attempt for one file. Returns true when the file completed,
     * false when paused by cancel; throws for retryable/non-retryable errors.
     */
    private boolean attemptFile(
            ModelCatalog.CatalogModel model, ModelCatalog.CatalogFile file, URI uri,
            Path part, Path meta, Path staging, JobHandle handle) throws IOException {
        Map<String, Object> storedMeta = readMeta(meta);
        long have = Files.exists(part) ? Files.size(part) : 0;

        Map<String, String> headers = new LinkedHashMap<>();
        boolean resuming = false;
        if (have > 0 && storedMeta != null
                && uri.toString().equals(storedMeta.get("url"))
                && (storedMeta.get("etag") != null || storedMeta.get("lastModified") != null)) {
            String validator = storedMeta.get("etag") != null
                    ? (String) storedMeta.get("etag") : (String) storedMeta.get("lastModified");
            headers.put("Range", "bytes=" + have + "-");
            headers.put("If-Range", validator);
            resuming = true;
        } else if (have > 0) {
            // Partial without a usable validator can never be safely resumed.
            Files.deleteIfExists(part);
            Files.deleteIfExists(meta);
            have = 0;
        }

        try (ModelDownloadTransport.Response response = transport.get(uri, headers)) {
            int status = response.status();
            boolean append;
            if (status == 206 && resuming) {
                String etag = response.header("ETag");
                if (etag != null && storedMeta.get("etag") != null
                        && !etag.equals(storedMeta.get("etag"))) {
                    // Validator changed even though the server sent 206: restart.
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(meta);
                    throw new IOException("validator changed on 206");
                }
                append = true;
            } else if (status == 200) {
                // Full body (fresh download, Range ignored, or validator changed).
                Files.deleteIfExists(part);
                writeMeta(meta, uri, response, file);
                have = 0;
                append = false;
            } else if (status == 416) {
                Files.deleteIfExists(part);
                Files.deleteIfExists(meta);
                throw new IOException("416 range not satisfiable, restarting from 0");
            } else if (status == 408 || status == 429 || status >= 500) {
                throw new IOException("retryable HTTP " + status);
            } else {
                throw new NonRetryableHttpException("HTTP " + status);
            }

            long written = have;
            try (FileChannel channel = FileChannel.open(part,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                InputStream in = response.body();
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    channel.write(ByteBuffer.wrap(buffer, 0, n));
                    written += n;
                    updateLiveProgress(model, file.name(), written);
                    java.util.function.ObjLongConsumer<String> hook = progressHook;
                    if (hook != null) hook.accept(file.name(), written);
                    if (written > file.sizeBytes()) {
                        channel.close();
                        Files.deleteIfExists(part);
                        Files.deleteIfExists(meta);
                        throw new IOException("more bytes than expected for " + file.name());
                    }
                    if (handle.cancelRequested || handle.uninstallRequested) {
                        channel.force(true);
                        if (!handle.uninstallRequested) pause(model, handle);
                        return false;
                    }
                }
                channel.force(true);
            }

            long size = Files.size(part);
            if (size != file.sizeBytes()) {
                throw new IOException("short read " + size + "/" + file.sizeBytes());
            }
            Files.move(part, staging.resolve(file.name()), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(meta);
            persistProgress(model, handle, currentFileBytes(model, staging));
            return true;
        }
    }

    // ── Verify & finalize ───────────────────────────────────────────────────

    private void verifyAndFinalize(ModelCatalog.CatalogModel model, JobHandle handle) {
        try {
            Path staging = stagingDir(model);
            if (!Files.exists(staging) && finalDirComplete(model)) {
                // Crash happened between the atomic move and the READY commit.
                models.markReady(model.id(), buildManifest(model));
                return;
            }
            for (ModelCatalog.CatalogFile file : model.files()) {
                if (handle.uninstallRequested) return;
                if (handle.cancelRequested) {
                    pause(model, handle);
                    return;
                }
                Path path = staging.resolve(file.name());
                if (!Files.exists(path) || Files.size(path) != file.sizeBytes()) {
                    fail(model, handle, "VERIFY_FAILED", file.name() + " missing or wrong size");
                    return;
                }
                String actual = sha256(path);
                if (!actual.equals(file.sha256())) {
                    // A corrupt artifact must never remain loadable.
                    Files.deleteIfExists(path);
                    fail(model, handle, "HASH_MISMATCH",
                            file.name() + " expected " + file.sha256() + " got " + actual);
                    return;
                }
            }

            String manifestJson = buildManifest(model);
            Path manifest = staging.resolve(MANIFEST_FILE);
            Files.writeString(manifest, manifestJson, java.nio.charset.StandardCharsets.UTF_8);

            Path finalDir = finalDir(model);
            if (Files.exists(finalDir)) {
                Map<String, Object> row = models.find(model.id());
                if (row != null && "READY".equals(row.get("installation_state"))) {
                    // Never overwrite a READY final directory.
                    fail(model, handle, "FINAL_DIR_EXISTS", finalDir.toString());
                    return;
                }
                deleteRecursively(finalDir); // orphan from an earlier crash
            }
            try {
                atomicMover.move(staging, finalDir);
            } catch (AtomicMoveNotSupportedException e) {
                // No copy fallback by contract.
                fail(model, handle, "ATOMIC_MOVE_UNSUPPORTED", String.valueOf(e.getMessage()));
                return;
            }
            liveProgressByModel.remove(model.id());
            models.markReady(model.id(), manifestJson);
            log.info("embedding model {} READY at {}", model.id(), finalDir);

            if (handle.activateAfterDownload) {
                ApiResult result = activate(model.id());
                if (result.status() >= 400) {
                    log.warn("activateAfterDownload failed for {}: {}", model.id(), result.body());
                }
            }
        } catch (Exception e) {
            log.warn("verify/finalize failed: {}", model.id(), e);
            fail(model, handle, "VERIFY_FAILED", String.valueOf(e.getMessage()));
        }
    }

    // ── Uninstall worker ────────────────────────────────────────────────────

    private void runUninstall(ModelCatalog.CatalogModel model, JobHandle handle) {
        try {
            // Files first: a crash keeps state UNINSTALLING and recovery re-runs.
            deleteRecursively(embeddingModelsRoot.resolve(model.id()));
            tx.executeWithoutResult(status -> {
                models.deleteSpacesForModel(
                        LocalEmbeddingProvider.PROVIDER_TYPE, model.providerModelId());
                models.resetToAvailable(model.id());
            });
            liveProgressByModel.remove(model.id());
            log.info("embedding model {} uninstalled", model.id());
        } catch (Exception e) {
            log.error("uninstall failed for {}", model.id(), e);
            models.updateStateAndError(model.id(), "UNINSTALLING",
                    "UNINSTALL_RETRY_NEEDED: " + e.getMessage());
        } finally {
            handle.terminal = true;
            activeUninstallByModel.remove(model.id(), handle);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void registerLocalProvider(ModelCatalog.CatalogModel model) {
        providers.setActive(new LocalEmbeddingProvider(
                lifecycle, model.providerModelId(), finalDir(model).toString(),
                model.runtimeRequiredFiles(), model.dimensions()));
    }

    private void clearProviderIfModel(ModelCatalog.CatalogModel model) {
        EmbeddingProvider active = providers.active();
        if (active != null && model.providerModelId().equals(active.modelId())
                && LocalEmbeddingProvider.PROVIDER_TYPE.equals(active.providerType())) {
            providers.clear();
        }
    }

    /** READY validation: manifest parses and every file exists with the pinned size. */
    private boolean manifestFilesValid(ModelCatalog.CatalogModel model) {
        Map<String, Object> row = models.find(model.id());
        String manifestJson = row == null ? null : (String) row.get("manifest_json");
        if (manifestJson == null || parseJsonOrNull(manifestJson) == null) return false;
        return finalDirComplete(model);
    }

    /** Every pinned file exists in the final dir with the pinned size, plus manifest. */
    private boolean finalDirComplete(ModelCatalog.CatalogModel model) {
        Path finalDir = finalDir(model);
        try {
            for (ModelCatalog.CatalogFile file : model.files()) {
                Path path = finalDir.resolve(file.name());
                if (!Files.exists(path) || Files.size(path) != file.sizeBytes()) return false;
            }
            return Files.exists(finalDir.resolve(MANIFEST_FILE));
        } catch (IOException e) {
            return false;
        }
    }

    Path stagingDir(ModelCatalog.CatalogModel model) {
        return embeddingModelsRoot.resolve(model.id()).resolve(".partial");
    }

    Path finalDir(ModelCatalog.CatalogModel model) {
        return embeddingModelsRoot.resolve(model.id()).resolve(model.artifactRevision());
    }

    private long remainingBytes(ModelCatalog.CatalogModel model, Path staging) throws IOException {
        long remaining = 0;
        for (ModelCatalog.CatalogFile file : model.files()) {
            Path complete = staging.resolve(file.name());
            if (Files.exists(complete) && Files.size(complete) == file.sizeBytes()) continue;
            Path part = staging.resolve(file.name() + ".part");
            long have = Files.exists(part) ? Files.size(part) : 0;
            remaining += Math.max(0, file.sizeBytes() - have);
        }
        return remaining;
    }

    private Map<String, Long> currentFileBytes(ModelCatalog.CatalogModel model, Path staging)
            throws IOException {
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (ModelCatalog.CatalogFile file : model.files()) {
            Path complete = staging.resolve(file.name());
            Path part = staging.resolve(file.name() + ".part");
            long have = Files.exists(complete) ? Files.size(complete)
                    : Files.exists(part) ? Files.size(part) : 0;
            bytes.put(file.name(), have);
        }
        return bytes;
    }

    private void updateLiveProgress(ModelCatalog.CatalogModel model, String fileName, long bytes) {
        Map<String, Object> progress = liveProgressByModel.computeIfAbsent(
                model.id(), k -> new ConcurrentHashMap<>());
        progress.put("totalBytes", model.inventorySizeBytes());
        progress.put(fileName, bytes);
    }

    private void persistProgress(ModelCatalog.CatalogModel model, JobHandle handle,
                                 Map<String, Long> fileBytes) {
        try {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("jobId", handle.jobId);
            progress.put("totalBytes", model.inventorySizeBytes());
            progress.put("files", fileBytes);
            models.updateProgress(model.id(), mapper.writeValueAsString(progress));
        } catch (Exception e) {
            log.warn("failed to persist download progress for {}", model.id(), e);
        }
    }

    private void pause(ModelCatalog.CatalogModel model, JobHandle handle) {
        if (handle.uninstallRequested) return; // uninstall owns the state now
        try {
            persistProgress(model, handle, currentFileBytes(model, stagingDir(model)));
        } catch (IOException ignored) {
            // progress persistence is best-effort on pause
        }
        models.updateState(model.id(), "PAUSED");
    }

    private void fail(ModelCatalog.CatalogModel model, JobHandle handle,
                      String code, String message) {
        if (handle.uninstallRequested) return;
        models.updateStateAndError(model.id(), "FAILED", code + ": " + message);
    }

    /** Sleep in cancel-aware slices; false when interrupted by cancel/uninstall. */
    private static boolean sleepSeconds(long seconds, JobHandle handle) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (handle.cancelRequested || handle.uninstallRequested) return false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> readMeta(Path meta) {
        if (!Files.exists(meta)) return null;
        try {
            return mapper.readValue(Files.readString(meta), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeMeta(Path meta, URI uri, ModelDownloadTransport.Response response,
                           ModelCatalog.CatalogFile file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", uri.toString());
        m.put("etag", response.header("ETag"));
        m.put("lastModified", response.header("Last-Modified"));
        m.put("expectedSize", file.sizeBytes());
        m.put("expectedSha256", file.sha256());
        Files.writeString(meta, mapper.writeValueAsString(m),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private String buildManifest(ModelCatalog.CatalogModel model) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("catalogId", model.id());
        manifest.put("providerModelId", model.providerModelId());
        manifest.put("artifactRevision", model.artifactRevision());
        manifest.put("dimensions", model.dimensions());
        manifest.put("catalogVersion", catalog.catalogVersion());
        List<Map<String, Object>> files = new java.util.ArrayList<>();
        for (ModelCatalog.CatalogFile file : model.files()) {
            files.add(Map.of("name", file.name(), "sizeBytes", file.sizeBytes(),
                    "sha256", file.sha256(), "runtimeRequired", file.runtimeRequired()));
        }
        manifest.put("files", files);
        manifest.put("verifiedAt", java.time.Instant.now().toString());
        return mapper.writeValueAsString(manifest);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Map<String, Object> parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ApiResult notFound(String message) {
        return new ApiResult(404, Map.of(
                "error", "not_found", "errorCode", "not_found", "message", message));
    }

    private static ApiResult conflict(String code, String message) {
        return new ApiResult(409, Map.of(
                "error", code, "errorCode", code, "message", message));
    }
}
