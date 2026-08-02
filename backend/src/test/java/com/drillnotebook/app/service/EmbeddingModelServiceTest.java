package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.config.PortablePathResolver;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * Tests for {@link EmbeddingModelService}: resumable verified download,
 * atomic finalize, activation, disable, thorough uninstall and the fixed
 * startup/edge recovery rules — all against a loopback Range server via the
 * test-only {@link LocalFixtureTransport}.
 */
class EmbeddingModelServiceTest {

    private static final String REAL_ID = "bge-small-zh-v1.5";
    private static final String REAL_REVISION = "46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59";
    private static final String TINY_ID = "tiny-model";
    private static final String TINY_REVISION = "abababababababababababababababababababab";

    private Path tempDir;
    private PortablePathResolver resolver;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private NoteIndexingService indexing;
    private EmbeddingJobRepository jobs;
    private EmbeddingModelRepository models;
    private EmbeddingProviderRegistry registry;
    private EmbeddingJobExecutor executor;
    private EmbeddingWorkerLifecycle lifecycle;
    private DataSourceTransactionManager txManager;
    private NotebookRepository notebooks;

    private final List<EmbeddingModelService> services = new ArrayList<>();
    private final List<FixtureHttpServer> servers = new ArrayList<>();

    // Deterministic tiny fixture content served from a temp dir.
    private Path tinyRoot;
    private byte[] alphaBytes;
    private byte[] betaBytes;
    private String alphaSha;
    private String betaSha;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("embedding-model-test");
        String oldRoot = System.getProperty("app.root");
        System.setProperty("app.root", tempDir.toString());
        resolver = new PortablePathResolver();
        if (oldRoot == null) System.clearProperty("app.root");
        else System.setProperty("app.root", oldRoot);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("study.db") + "?foreign_keys=on");
        new DatabaseInitializer(ds).initialize();
        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper();
        notebooks = new NotebookRepository(jdbc, mapper);
        indexing = new NoteIndexingService(
                notebooks, new RetrievalIndexRepository(jdbc), jdbc, mapper);
        jobs = new EmbeddingJobRepository(jdbc);
        models = new EmbeddingModelRepository(jdbc);
        registry = new EmbeddingProviderRegistry();
        txManager = new DataSourceTransactionManager(ds);
        executor = new EmbeddingJobExecutor(jobs, registry, txManager, false, 1000);
        lifecycle = new EmbeddingWorkerLifecycle(resolver, mapper);

        tinyRoot = Files.createDirectories(tempDir.resolve("server-root"));
        alphaBytes = new byte[5000];
        for (int i = 0; i < alphaBytes.length; i++) alphaBytes[i] = (byte) (i % 251);
        betaBytes = "tiny beta fixture content for drill notebook".repeat(7)
                .getBytes(StandardCharsets.UTF_8);
        Files.write(tinyRoot.resolve("alpha.bin"), alphaBytes);
        Files.write(tinyRoot.resolve("beta.bin"), betaBytes);
        alphaSha = sha256(alphaBytes);
        betaSha = sha256(betaBytes);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (EmbeddingModelService service : services) service.shutdown();
        for (FixtureHttpServer server : servers) server.close();
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    // ── Builders ───────────────────────────────────────────────────────────

    private FixtureHttpServer server(Path root) throws IOException {
        FixtureHttpServer server = new FixtureHttpServer(root);
        servers.add(server);
        return server;
    }

    private EmbeddingModelService service(ModelCatalog catalog) {
        RetrievalMaintenanceService maintenance =
                new RetrievalMaintenanceService(models, jobs, executor);
        EmbeddingModelService service = new EmbeddingModelService(
                catalog, models, jobs, registry, lifecycle, executor,
                new LocalFixtureTransport(), mapper, txManager, resolver, false, maintenance, null);
        service.init();
        services.add(service);
        return service;
    }

    private ModelCatalog tinyCatalog(String baseUrl, String alphaShaOverride) {
        List<ModelCatalog.CatalogFile> files = List.of(
                new ModelCatalog.CatalogFile("alpha.bin", alphaBytes.length,
                        alphaShaOverride == null ? alphaSha : alphaShaOverride, true),
                new ModelCatalog.CatalogFile("beta.bin", betaBytes.length, betaSha, true));
        ModelCatalog.CatalogModel model = new ModelCatalog.CatalogModel(
                TINY_ID, "tiny/model", TINY_REVISION, "Tiny", "MIT", List.of("zh"),
                512, alphaBytes.length + betaBytes.length, baseUrl, files);
        return new ModelCatalog(1, List.of(model));
    }

    private ModelCatalog realCatalog(String baseUrl) {
        List<ModelCatalog.CatalogFile> files = List.of(
                new ModelCatalog.CatalogFile("model_optimized.onnx", 94_781_076L,
                        "1294ea4b6331115a353d81f96b85e8c8d7fdcc284453d5b2fab5b016230aad38", true),
                new ModelCatalog.CatalogFile("tokenizer.json", 439_125L,
                        "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26", true),
                new ModelCatalog.CatalogFile("config.json", 739L,
                        "9088751d39abbf86ec3d19ffca92ad62ad19075f7e59712e6c71217fa125d1d3", true),
                new ModelCatalog.CatalogFile("tokenizer_config.json", 367L,
                        "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a", true),
                new ModelCatalog.CatalogFile("special_tokens_map.json", 125L,
                        "b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3", true),
                new ModelCatalog.CatalogFile("vocab.txt", 109_540L,
                        "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c", false),
                new ModelCatalog.CatalogFile("ort_config.json", 1_234L,
                        "97e78d1d21c2eb719e865b018f17915df6a12ed987446eb7f3f3a783a5afb1e1", false));
        ModelCatalog.CatalogModel model = new ModelCatalog.CatalogModel(
                REAL_ID, "Qdrant/bge-small-zh-v1.5", REAL_REVISION,
                "BGE Small 中文 v1.5", "MIT", List.of("zh"),
                512, 95_332_206L, baseUrl, files);
        return new ModelCatalog(1, List.of(model));
    }

    private static Path fixtureDir() {
        return Paths.get(System.getProperty("user.dir"))
                .resolve("../runtime-portable/data/models/embedding-fixtures").normalize();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String awaitState(String catalogId, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = models.find(catalogId);
            state = row == null ? null : (String) row.get("installation_state");
            if (expected.equals(state)) return state;
            Thread.sleep(50);
        }
        return state;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256File(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /** TipTap doc with three ~2000-char paragraphs → exactly 3 chunks. */
    private static Map<String, Object> threeChunkContent(String marker) {
        List<Object> paragraphs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String text = marker + "段落" + i + "："
                    + String.valueOf((char) ('甲' + i)).repeat(1990);
            paragraphs.add(Map.of("type", "paragraph", "content",
                    List.of(Map.of("type", "text", "text", text))));
        }
        return Map.of("type", "doc", "content", paragraphs);
    }

    static class FakeProvider implements EmbeddingProvider {
        @Override public String providerType() { return "local-rust"; }
        @Override public String modelId() { return "fake"; }
        @Override public int dimensions() { return 512; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public List<List<Float>> embedDocuments(List<String> texts) {
            List<List<Float>> out = new ArrayList<>();
            for (String ignored : texts) {
                List<Float> vec = new ArrayList<>(512);
                for (int i = 0; i < 512; i++) vec.add((i + 1) / 512f);
                out.add(vec);
            }
            return out;
        }
        @Override
        public List<Float> embedQuery(String text) { return embedDocuments(List.of(text)).get(0); }
    }

    private void writeEvidence(String name, List<String> lines) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        List<String> out = new ArrayList<>();
        out.add("generatedBy: EmbeddingModelServiceTest");
        out.addAll(lines);
        Files.writeString(Path.of(dir, name),
                String.join(System.lineSeparator(), out) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private void writePartialWithMeta(ModelCatalog catalog, String fileName,
                                      byte[] partialBytes, String etag) throws Exception {
        ModelCatalog.CatalogModel model = catalog.models().get(0);
        Path staging = Files.createDirectories(
                resolver.embeddingModels().resolve(model.id()).resolve(".partial"));
        Files.write(staging.resolve(fileName + ".part"), partialBytes);
        ModelCatalog.CatalogFile file = model.files().stream()
                .filter(f -> f.name().equals(fileName)).findFirst().orElseThrow();
        Map<String, Object> meta = Map.of(
                "url", model.baseUrl() + fileName,
                "etag", etag,
                "lastModified", "Wed, 01 Jan 2025 00:00:00 GMT",
                "expectedSize", file.sizeBytes(),
                "expectedSha256", file.sha256());
        Files.writeString(staging.resolve(fileName + ".part.meta.json"),
                mapper.writeValueAsString(meta), StandardCharsets.UTF_8);
    }

    // ── Catalog & contracts ────────────────────────────────────────────────

    @Test
    void builtInCatalogValidatesAndSpaceIdIsStable() {
        ModelCatalog catalog = ModelCatalog.loadBuiltIn(mapper);
        assertEquals(1, catalog.catalogVersion());
        assertEquals(1, catalog.models().size());
        ModelCatalog.CatalogModel model = catalog.models().get(0);
        assertEquals(REAL_ID, model.id());
        assertEquals("Qdrant/bge-small-zh-v1.5", model.providerModelId());
        assertEquals(REAL_REVISION, model.artifactRevision());
        assertEquals(512, model.dimensions());
        assertEquals(95_332_206L, model.inventorySizeBytes());
        assertEquals(7, model.files().size());
        assertEquals(5, model.runtimeRequiredFiles().size());
        assertTrue(model.baseUrl().startsWith("https://huggingface.co/Qdrant/bge-small-zh-v1.5/resolve/"));

        String canonical = EmbeddingSpaceContracts.localCanonicalJson(model, catalog.catalogVersion());
        String spaceId = EmbeddingSpaceContracts.spaceId(canonical);
        assertTrue(spaceId.matches("^[0-9a-f]{64}$"));
        // Sorted keys and pinned values are part of the frozen identity.
        assertTrue(canonical.indexOf("artifactRevision") < canonical.indexOf("catalogVersion"));
        assertTrue(canonical.contains("\"providerType\":\"local-rust\""));
        assertTrue(canonical.contains("\"pooling\":\"cls\""));
        assertTrue(canonical.contains("\"normalization\":\"java-l2-v1\""));
        assertEquals(spaceId, EmbeddingSpaceContracts.spaceId(
                EmbeddingSpaceContracts.localCanonicalJson(model, catalog.catalogVersion())));
    }

    @Test
    void transportsEnforceHostPolicies() {
        PinnedHttpsTransport pinned = new PinnedHttpsTransport();
        IOException nonHttps = assertThrows(IOException.class,
                () -> pinned.get(URI.create("http://huggingface.co/x"), Map.of()));
        assertTrue(nonHttps.getMessage().contains("UNTRUSTED_REDIRECT"));
        IOException badHost = assertThrows(IOException.class,
                () -> pinned.get(URI.create("https://evil.example.com/x"), Map.of()));
        assertTrue(badHost.getMessage().contains("UNTRUSTED_REDIRECT"));

        LocalFixtureTransport fixture = new LocalFixtureTransport();
        assertThrows(IOException.class,
                () -> fixture.get(URI.create("https://127.0.0.1/x"), Map.of()));
        assertThrows(IOException.class,
                () -> fixture.get(URI.create("http://huggingface.co/x"), Map.of()));
    }

    // ── QA Scenario 1: 中断续传并校验激活 ───────────────────────────────────

    @Test
    void interruptedDownloadResumesWithRangeVerifiesAndActivates() throws Exception {
        assumeTrue(Files.exists(fixtureDir().resolve("model_optimized.onnx")),
                "embedding fixture files required (scripts/fetch-embedding-fixture.ps1)");
        FixtureHttpServer server = server(fixtureDir());
        ModelCatalog catalog = realCatalog(server.baseUrl());
        EmbeddingModelService service = service(catalog);

        long notebookId = notebooks.insert("Model NB");
        long pageId = notebooks.insertPage(notebookId, "Page", null);
        indexing.savePageAndIndex(pageId, null, threeChunkContent("模"));

        // Cancel once mid-onnx (~50%).
        final String[] jobIdHolder = new String[1];
        final boolean[] cancelled = {false};
        service.progressHook = (file, bytes) -> {
            if (!cancelled[0] && "model_optimized.onnx".equals(file) && bytes >= 47_000_000L) {
                cancelled[0] = true;
                service.cancelDownload(jobIdHolder[0]);
            }
        };
        EmbeddingModelService.ApiResult first =
                service.download(REAL_ID, Map.of("activateAfterDownload", true));
        assertEquals(202, first.status());
        jobIdHolder[0] = (String) first.body().get("jobId");

        assertEquals("PAUSED", awaitState(REAL_ID, "PAUSED", 120_000));
        Path staging = resolver.embeddingModels().resolve(REAL_ID).resolve(".partial");
        Path part = staging.resolve("model_optimized.onnx.part");
        assertTrue(Files.exists(part), "partial must survive cancel");
        long pausedBytes = Files.size(part);
        assertTrue(pausedBytes >= 47_000_000L && pausedBytes < 94_781_076L,
                "expected ~50% partial, got " + pausedBytes);
        assertTrue(Files.exists(staging.resolve("model_optimized.onnx.part.meta.json")));

        service.progressHook = null;
        EmbeddingModelService.ApiResult second =
                service.download(REAL_ID, Map.of("activateAfterDownload", true));
        assertEquals(202, second.status());
        assertEquals("READY", awaitState(REAL_ID, "READY", 180_000));

        List<Map<String, String>> onnxRequests = server.requestsFor("model_optimized.onnx");
        assertTrue(onnxRequests.size() >= 2);
        Map<String, String> resume = onnxRequests.get(onnxRequests.size() - 1);
        assertEquals("bytes=" + pausedBytes + "-", resume.get("range"));
        assertEquals(server.etag, resume.get("ifRange"));

        // Verified final layout + hashes.
        Path finalDir = resolver.embeddingModels().resolve(REAL_ID).resolve(REAL_REVISION);
        assertTrue(Files.exists(finalDir.resolve("manifest.json")));
        assertFalse(Files.exists(staging), "staging must be atomically moved away");
        assertEquals(94_781_076L, Files.size(finalDir.resolve("model_optimized.onnx")));
        assertEquals("9088751d39abbf86ec3d19ffca92ad62ad19075f7e59712e6c71217fa125d1d3",
                sha256File(finalDir.resolve("config.json")));

        // activateAfterDownload switched the selected space to REBUILDING.
        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        assertNotNull(selected);
        String rebuildingState = (String) selected.get("state");
        assertEquals("REBUILDING", rebuildingState);
        String spaceId = (String) selected.get("embedding_space_id");

        // Index the backfilled jobs; 100% coverage flips the space ACTIVE.
        registry.setActive(new FakeProvider());
        while (executor.runOnce()) { /* drain queue */ }
        Map<String, Object> after = jobs.findSelectedSpaceAnyState();
        assertEquals("ACTIVE", after.get("state"));
        assertEquals(1.0, ((Number) after.get("coverage")).doubleValue(), 1e-9);

        writeEvidence("task-10-model-download.txt", List.of(
                "scenario: 中断续传并校验激活",
                "pausedPartialBytes: " + pausedBytes,
                "resumeRangeHeader: " + resume.get("range"),
                "resumeIfRangeHeader: " + resume.get("ifRange"),
                "installationState: READY",
                "manifestExists: true",
                "configJsonSha256Verified: true",
                "embeddingSpaceId: " + spaceId,
                "spaceStateAfterActivate: " + rebuildingState,
                "spaceStateAfterIndexing: " + after.get("state"),
                "coverage: " + after.get("coverage")));
    }

    // ── QA Scenario 2: hash 错误与彻底卸载 ──────────────────────────────────

    @Test
    void hashFailureThenThoroughUninstall() throws Exception {
        FixtureHttpServer server = server(tinyRoot);

        // Part 1: wrong hash → FAILED, corrupt artifact deleted.
        ModelCatalog wrong = tinyCatalog(server.baseUrl(), "0".repeat(64));
        EmbeddingModelService wrongService = service(wrong);
        assertEquals(202, wrongService.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("FAILED", awaitState(TINY_ID, "FAILED", 30_000));
        Map<String, Object> failedRow = models.find(TINY_ID);
        assertTrue(String.valueOf(failedRow.get("download_error")).contains("HASH_MISMATCH"));
        Path staging = resolver.embeddingModels().resolve(TINY_ID).resolve(".partial");
        assertFalse(Files.exists(staging.resolve("alpha.bin")),
                "corrupt file must not remain loadable");
        assertFalse(Files.exists(resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION)));

        // Part 2: correct catalog → READY → activate → vectors → uninstall.
        ModelCatalog correct = tinyCatalog(server.baseUrl(), null);
        EmbeddingModelService service = service(correct);
        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));

        long notebookId = notebooks.insert("Tiny NB");
        long pageId = notebooks.insertPage(notebookId, "Page", null);
        indexing.savePageAndIndex(pageId, null, threeChunkContent("卸"));

        EmbeddingModelService.ApiResult activate = service.activate(TINY_ID);
        assertEquals(202, activate.status());
        assertEquals("REBUILDING", activate.body().get("state"));
        registry.setActive(new FakeProvider());
        while (executor.runOnce()) { /* drain queue */ }
        int embeddings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_embedding", Integer.class);
        assertEquals(3, embeddings);
        int chunksBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk", Integer.class);
        int ftsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk_fts", Integer.class);

        EmbeddingModelService.ApiResult uninstall = service.uninstall(TINY_ID, true);
        assertEquals(202, uninstall.status());
        assertEquals("UNINSTALLING", uninstall.body().get("state"));
        assertEquals("AVAILABLE", awaitState(TINY_ID, "AVAILABLE", 30_000));

        assertFalse(Files.exists(resolver.embeddingModels().resolve(TINY_ID)),
                "model dir (final + partial + manifest) must be gone");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_embedding", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_space", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_job", Integer.class));
        assertEquals(chunksBefore, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk", Integer.class));
        assertEquals(ftsBefore, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_chunk_fts", Integer.class));
        Map<String, Object> row = models.find(TINY_ID);
        assertNull(row.get("manifest_json"));
        assertNull(row.get("download_error"));

        writeEvidence("task-10-model-uninstall.txt", List.of(
                "scenario: hash 错误与彻底卸载",
                "wrongHashState: FAILED",
                "wrongHashError: " + failedRow.get("download_error"),
                "corruptFileDeleted: true",
                "readyStateAfterCorrectDownload: READY",
                "embeddingsBeforeUninstall: " + embeddings,
                "stateAfterUninstall: AVAILABLE",
                "modelDirExists: false",
                "embeddingsAfterUninstall: 0",
                "chunksUnchanged: " + chunksBefore,
                "ftsUnchanged: " + ftsBefore));
    }

    // ── API contract: idempotency & statuses ───────────────────────────────

    @Test
    void downloadContractIdempotencyAndCancel() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        server.holdLatch = new CountDownLatch(1);
        EmbeddingModelService service = service(tinyCatalog(server.baseUrl(), null));

        assertThrows(IllegalArgumentException.class,
                () -> service.download(TINY_ID, Map.of()),
                "activateAfterDownload must be explicit");
        assertEquals(404, service.download(
                "nope", Map.of("activateAfterDownload", false)).status());

        EmbeddingModelService.ApiResult first =
                service.download(TINY_ID, Map.of("activateAfterDownload", false));
        assertEquals(202, first.status());
        String jobId = (String) first.body().get("jobId");

        EmbeddingModelService.ApiResult repeat =
                service.download(TINY_ID, Map.of("activateAfterDownload", false));
        assertEquals(200, repeat.status());
        assertEquals(jobId, repeat.body().get("jobId"));

        assertEquals(404, service.cancelDownload("unknown-job").status());
        EmbeddingModelService.ApiResult cancel = service.cancelDownload(jobId);
        assertEquals(202, cancel.status());
        assertEquals("CANCEL_REQUESTED", cancel.body().get("state"));

        server.holdLatch.countDown();
        assertEquals("PAUSED", awaitState(TINY_ID, "PAUSED", 30_000));

        // Terminal job: cancel returns 200 with the unchanged state.
        long deadline = System.currentTimeMillis() + 5_000;
        EmbeddingModelService.ApiResult terminal = null;
        while (System.currentTimeMillis() < deadline) {
            terminal = service.cancelDownload(jobId);
            if (terminal.status() == 200) break;
            Thread.sleep(50);
        }
        assertEquals(200, terminal.status());
        assertEquals("PAUSED", terminal.body().get("state"));

        // Resume from PAUSED completes.
        EmbeddingModelService.ApiResult resume =
                service.download(TINY_ID, Map.of("activateAfterDownload", false));
        assertEquals(202, resume.status());
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));
        // READY: further downloads conflict.
        assertEquals(409, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
    }

    @Test
    void activateDisableUninstallContracts() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        EmbeddingModelService service = service(tinyCatalog(server.baseUrl(), null));

        assertEquals(409, service.activate(TINY_ID).status(), "not READY yet");
        assertEquals(404, service.activate("nope").status());
        assertThrows(IllegalArgumentException.class, () -> service.uninstall(TINY_ID, false));
        assertEquals(404, service.uninstall("nope", true).status());

        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));

        EmbeddingModelService.ApiResult first = service.activate(TINY_ID);
        assertEquals(202, first.status());
        String spaceId = (String) first.body().get("embeddingSpaceId");
        assertNotNull(first.body().get("reindexJobId"));

        // Same selected space → 200 with the current reindex job.
        EmbeddingModelService.ApiResult again = service.activate(TINY_ID);
        assertEquals(200, again.status());
        assertEquals(spaceId, again.body().get("embeddingSpaceId"));
        assertEquals(first.body().get("reindexJobId"), again.body().get("reindexJobId"));

        // Empty corpus: coverage 1.0 → immediately ACTIVE; provider registered.
        Map<String, Object> selected = jobs.findSelectedSpaceAnyState();
        assertEquals("ACTIVE", selected.get("state"));
        assertNotNull(registry.active());
        assertEquals("tiny/model", registry.active().modelId());

        // Disable stops the provider but keeps files and the space row.
        EmbeddingModelService.ApiResult disable = service.disable(TINY_ID);
        assertEquals(200, disable.status());
        assertEquals("DISABLED", disable.body().get("state"));
        assertNull(registry.active());
        assertEquals("DISABLED", models.findSpace(spaceId).get("state"));
        assertTrue(Files.exists(resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION)));
        assertEquals(200, service.disable(TINY_ID).status(), "disable is idempotent");
    }

    // ── Resume edge rules ──────────────────────────────────────────────────

    @Test
    void serverIgnoringRangeRestartsFromZero() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        server.ignoreRange = true;
        ModelCatalog catalog = tinyCatalog(server.baseUrl(), null);
        writePartialWithMeta(catalog, "alpha.bin",
                java.util.Arrays.copyOf(alphaBytes, 2000), server.etag);

        EmbeddingModelService service = service(catalog);
        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));

        Map<String, String> request = server.requestsFor("alpha.bin").get(0);
        assertEquals("bytes=2000-", request.get("range"), "resume was attempted");
        Path finalFile = resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION).resolve("alpha.bin");
        assertEquals(alphaSha, sha256File(finalFile), "200 response restarted from zero");
    }

    @Test
    void changedValidatorRestartsFromZero() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        ModelCatalog catalog = tinyCatalog(server.baseUrl(), null);
        // Meta stores an old validator; the server has since changed content.
        writePartialWithMeta(catalog, "alpha.bin",
                java.util.Arrays.copyOf(alphaBytes, 2000), "\"stale-etag\"");

        EmbeddingModelService service = service(catalog);
        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));

        Map<String, String> request = server.requestsFor("alpha.bin").get(0);
        assertEquals("\"stale-etag\"", request.get("ifRange"));
        Path finalFile = resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION).resolve("alpha.bin");
        assertEquals(alphaSha, sha256File(finalFile));
    }

    @Test
    void http416DeletesPartialAndRestarts() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        ModelCatalog catalog = tinyCatalog(server.baseUrl(), null);
        // Oversized partial → Range start beyond EOF → 416.
        writePartialWithMeta(catalog, "alpha.bin",
                new byte[alphaBytes.length + 10], server.etag);

        EmbeddingModelService service = service(catalog);
        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));

        List<Map<String, String>> requests = server.requestsFor("alpha.bin");
        assertTrue(requests.size() >= 2);
        assertEquals("bytes=" + (alphaBytes.length + 10) + "-", requests.get(0).get("range"));
        assertNull(requests.get(requests.size() - 1).get("range"),
                "retry after 416 must start from zero");
        Path finalFile = resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION).resolve("alpha.bin");
        assertEquals(alphaSha, sha256File(finalFile));
    }

    // ── Startup recovery & finalize edge rules ─────────────────────────────

    @Test
    void startupRecoveryAppliesFixedRules() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        ModelCatalog catalog = tinyCatalog(server.baseUrl(), null);
        EmbeddingModelService service = service(catalog);

        // DOWNLOADING (dead process) → PAUSED.
        models.updateState(TINY_ID, "DOWNLOADING");
        service.recover();
        assertEquals("PAUSED", models.find(TINY_ID).get("installation_state"));

        // READY with manifest/file mismatch → FAILED (CORRUPT).
        models.markReady(TINY_ID, "{}");
        service.recover();
        Map<String, Object> corrupt = models.find(TINY_ID);
        assertEquals("FAILED", corrupt.get("installation_state"));
        assertTrue(String.valueOf(corrupt.get("download_error")).contains("CORRUPT"));

        // VERIFYING with complete staging → re-hash → READY.
        Path staging = Files.createDirectories(
                resolver.embeddingModels().resolve(TINY_ID).resolve(".partial"));
        Files.write(staging.resolve("alpha.bin"), alphaBytes);
        Files.write(staging.resolve("beta.bin"), betaBytes);
        models.updateState(TINY_ID, "VERIFYING");
        service.recover();
        assertEquals("READY", awaitState(TINY_ID, "READY", 30_000));
        assertTrue(Files.exists(resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION).resolve("manifest.json")));
    }

    @Test
    void atomicMoveUnsupportedFailsWithoutCopyFallback() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        EmbeddingModelService service = service(tinyCatalog(server.baseUrl(), null));
        service.atomicMover = (source, target) -> {
            throw new AtomicMoveNotSupportedException(
                    source.toString(), target.toString(), "test filesystem");
        };
        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        assertEquals("FAILED", awaitState(TINY_ID, "FAILED", 30_000));
        assertTrue(String.valueOf(models.find(TINY_ID).get("download_error"))
                .contains("ATOMIC_MOVE_UNSUPPORTED"));
        assertFalse(Files.exists(resolver.embeddingModels()
                .resolve(TINY_ID).resolve(TINY_REVISION)), "no copy fallback allowed");
    }

    @Test
    void uninstallWinsOverInFlightDownload() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        server.holdLatch = new CountDownLatch(1);
        EmbeddingModelService service = service(tinyCatalog(server.baseUrl(), null));

        assertEquals(202, service.download(
                TINY_ID, Map.of("activateAfterDownload", false)).status());
        EmbeddingModelService.ApiResult uninstall = service.uninstall(TINY_ID, true);
        assertEquals(202, uninstall.status());
        // Repeat while running returns the same job.
        EmbeddingModelService.ApiResult repeat = service.uninstall(TINY_ID, true);
        assertTrue(repeat.status() == 200 || repeat.status() == 202);

        server.holdLatch.countDown();
        assertEquals("AVAILABLE", awaitState(TINY_ID, "AVAILABLE", 30_000));
        assertFalse(Files.exists(resolver.embeddingModels().resolve(TINY_ID)),
                "uninstall must remove staging even for an interrupted download");
    }

    @Test
    void catalogEndpointExposesInstallationState() throws Exception {
        FixtureHttpServer server = server(tinyRoot);
        EmbeddingModelService service = service(tinyCatalog(server.baseUrl(), null));
        Map<String, Object> body = service.catalogResponse();
        assertEquals(1, body.get("catalogVersion"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modelsOut = (List<Map<String, Object>>) body.get("models");
        assertEquals(1, modelsOut.size());
        assertEquals(TINY_ID, modelsOut.get(0).get("id"));
        assertEquals("AVAILABLE", modelsOut.get(0).get("installationState"));
        assertEquals(512, modelsOut.get(0).get("dimensions"));
    }
}

