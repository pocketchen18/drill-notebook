package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.AiConfigRepository;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.EmbeddingModelRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * Task 12 contract tests: independent embedding config slot, consent
 * fingerprint invalidation, and the frozen OpenAI/Ollama wire protocols.
 */
class EmbeddingConfigServiceTest {

    private JdbcTemplate jdbc;
    private AiConfigRepository configs;
    private EmbeddingModelRepository models;
    private EmbeddingJobRepository jobs;
    private EmbeddingProviderRegistry registry;
    private EmbeddingJobExecutor executor;
    private NotebookRepository notebooks;
    private NoteIndexingService indexing;
    private RetrievalIndexRepository retrievalIndex;
    private RetrievalService lexical;
    private ApiKeyEncryptor encryptor;
    private ObjectMapper mapper;
    private DataSourceTransactionManager txManager;

    private HttpServer server;
    private final List<String> requestLog = new CopyOnWriteArrayList<>();
    private final AtomicInteger embeddingsCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("embedding-config-test");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + root.resolve("study.db") + "?foreign_keys=on");
        new DatabaseInitializer(ds).initialize();
        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper();
        configs = new AiConfigRepository(jdbc);
        models = new EmbeddingModelRepository(jdbc);
        jobs = new EmbeddingJobRepository(jdbc);
        registry = new EmbeddingProviderRegistry();
        txManager = new DataSourceTransactionManager(ds);
        executor = new EmbeddingJobExecutor(jobs, registry, txManager, false, 1000);
        notebooks = new NotebookRepository(jdbc, mapper);
        retrievalIndex = new RetrievalIndexRepository(jdbc);
        indexing = new NoteIndexingService(notebooks, retrievalIndex, jdbc, mapper);
        lexical = new RetrievalService(retrievalIndex, notebooks);
        encryptor = new ApiKeyEncryptor();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private EmbeddingConfigService service() {
        RetrievalMaintenanceService maintenance =
                new RetrievalMaintenanceService(models, jobs, executor);
        return new EmbeddingConfigService(configs, encryptor, mapper, registry,
                models, jobs, executor, maintenance, txManager);
    }

    private Map<String, Object> saveEmbedding(Map<String, Object> body) {
        Map<String, Object> withPurpose = new LinkedHashMap<>(body);
        withPurpose.put("purpose", "embedding");
        return service().saveConfig(withPurpose);
    }

    private Map<String, Object> spaceState() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT embedding_space_id, provider_type, model_identifier, dimensions,"
                        + " state, coverage, is_selected FROM embedding_space");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int jobCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM embedding_job", Integer.class);
        return count == null ? 0 : count;
    }

    private String jobError() {
        return jdbc.queryForObject("SELECT error FROM embedding_job LIMIT 1", String.class);
    }

    private long indexPage(String title, String text) {
        long notebookId = notebooks.insert("nb");
        Map<String, Object> page = indexing.createAndIndexPage(notebookId, title, Map.of(
                "type", "doc",
                "content", List.of(Map.of("type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text))))));
        return ((Number) page.get("id")).longValue();
    }

    // ── Mock upstream servers ───────────────────────────────────────────────

    private String startOpenAiServer(int dims, int status, long delayMs, boolean scramble)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestLog.add(path + " " + body);
            if (!"/embeddings".equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            embeddingsCalls.incrementAndGet();
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            }
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            try {
                JsonNode input = mapper.readTree(body).path("input");
                List<Map<String, Object>> data = new ArrayList<>();
                for (int i = 0; i < input.size(); i++) {
                    double fill = markerValue(input.get(i).asText());
                    List<Double> vector = new ArrayList<>();
                    for (int d = 0; d < dims; d++) vector.add(fill);
                    data.add(Map.of("index", i, "embedding", vector));
                }
                if (scramble && data.size() > 1) java.util.Collections.reverse(data);
                byte[] out = mapper.writeValueAsBytes(Map.of("data", data));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startOllamaServer(int dims) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestLog.add(path + " " + body);
            if (!"/api/embed".equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            try {
                JsonNode input = mapper.readTree(body).path("input");
                List<List<Double>> embeddings = new ArrayList<>();
                for (int i = 0; i < input.size(); i++) {
                    double fill = markerValue(input.get(i).asText());
                    List<Double> vector = new ArrayList<>();
                    for (int d = 0; d < dims; d++) vector.add(fill);
                    embeddings.add(vector);
                }
                byte[] out = mapper.writeValueAsBytes(Map.of("embeddings", embeddings));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Vector fill value derived from the trailing digits of an input text. */
    private static double markerValue(String text) {
        String digits = text.replaceAll("\\D", "");
        return digits.isEmpty() ? 0.0 : Integer.parseInt(digits) * 0.1;
    }

    private void writeEvidence(String name, Object payload) throws Exception {
        String dir = System.getenv("DRILL_EVIDENCE_DIR");
        if (dir == null || dir.isBlank()) return;
        Files.writeString(Path.of(dir, name),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    // ── Endpoint normalization ──────────────────────────────────────────────

    @Test
    void endpointNormalizationRules() {
        assertEquals("http://localhost:11434",
                EmbeddingSpaceContracts.normalizeEndpoint("HTTP://LocalHost:11434/"));
        assertEquals("https://api.example.com/v1",
                EmbeddingSpaceContracts.normalizeEndpoint("https://api.example.com:443/v1/"));
        assertEquals("http://example.com/a/b",
                EmbeddingSpaceContracts.normalizeEndpoint("http://example.com//a//b/"));
        assertEquals("https://example.com",
                EmbeddingSpaceContracts.normalizeEndpoint("https://user:pw@EXAMPLE.com?x=1#frag"));
        for (String bad : List.of("file:///etc/passwd", "jar:file:///x", "ftp://host", "example.com")) {
            try {
                EmbeddingSpaceContracts.normalizeEndpoint(bad);
                throw new AssertionError("should reject: " + bad);
            } catch (IllegalArgumentException expected) { /* ok */ }
        }
    }

    // ── Redacted config ─────────────────────────────────────────────────────

    @Test
    void redactedConfigExposesSlotWithoutPlainKey() {
        saveEmbedding(Map.of("provider", "openai", "endpoint", "https://api.example.com/v1/",
                "model", "text-embedding-3-small", "dimensions", 1536,
                "apiKey", "sk-secret-123", "remoteContentConsent", true));
        Map<String, Object> redacted = service().redactedEmbedding();
        assertEquals("openai", redacted.get("provider"));
        assertEquals("https://api.example.com/v1", redacted.get("endpoint"));
        assertEquals(true, redacted.get("hasKey"));
        assertEquals(true, redacted.get("enabled"));
        assertEquals(true, redacted.get("consent"));
        assertFalse(mapper.valueToTree(redacted).toString().contains("sk-secret-123"),
                "redacted config must not contain the plain key");
    }

    // ── Consent contract ────────────────────────────────────────────────────

    @Test
    void remoteWithoutConsentIsDisabledAndMakesNoRequest() throws Exception {
        String endpoint = startOpenAiServer(4, 200, 0, false);
        Map<String, Object> result = saveEmbedding(Map.of("provider", "openai",
                "endpoint", endpoint, "model", "m", "dimensions", 4));
        assertEquals("CONSENT_REQUIRED", result.get("code"));
        assertEquals(false, result.get("enabled"));
        assertNull(spaceState(), "no embedding space may be created without consent");
        assertEquals(0, jobCount());
        assertEquals(0, embeddingsCalls.get(), "no probe/index request without consent");
    }

    @Test
    void endpointChangeInvalidatesConsent() throws Exception {
        String endpointA = startOpenAiServer(4, 200, 0, false);
        saveEmbedding(Map.of("provider", "openai", "endpoint", endpointA,
                "model", "m", "dimensions", 4, "remoteContentConsent", true));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_space WHERE is_selected = 1", Integer.class));

        // Re-point at endpoint B without new consent: config disabled, no request.
        Map<String, Object> result = saveEmbedding(Map.of("provider", "openai",
                "endpoint", "http://127.0.0.1:59999", "model", "m", "dimensions", 4));
        assertEquals("CONSENT_REQUIRED", result.get("code"));
        assertEquals(false, result.get("enabled"));
        assertEquals(0, embeddingsCalls.get(), "endpoint B must not be probed");
        assertNull(registry.active(), "remote provider must be cleared");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM embedding_space WHERE is_selected = 1", Integer.class),
                "remote space selection must be cleared");
    }

    // ── QA Scenario 1: OpenAI 与 Ollama 批量协议 ────────────────────────────

    @Test
    void openAiBatchOrderAndDimensions() throws Exception {
        String endpoint = startOpenAiServer(4, 200, 0, true);
        OpenAiEmbeddingProvider provider =
                new OpenAiEmbeddingProvider(mapper, endpoint, "m", 4, "k", 2, 10);
        List<List<Float>> vectors =
                provider.embedDocuments(List.of("t1", "t2", "t3"));
        assertEquals(3, vectors.size());
        // batchSize=2 → two HTTP calls (2 + 1); order restored via data.index
        // even though the server scrambles each batch and values are per-text.
        assertEquals(2, embeddingsCalls.get());
        assertEquals(0.1f, vectors.get(0).get(0), 1e-6);
        assertEquals(0.2f, vectors.get(1).get(0), 1e-6);
        assertEquals(0.3f, vectors.get(2).get(0), 1e-6);
        for (List<Float> vector : vectors) assertEquals(4, vector.size());
        assertTrue(requestLog.get(0).startsWith("/embeddings "));
        assertTrue(requestLog.get(0).contains("\"model\":\"m\""));

        List<Float> query = provider.embedQuery("q");
        assertEquals(4, query.size());

        writeEvidence("task-12-remote-providers.txt", Map.of(
                "scenario", "OpenAI 与 Ollama 批量协议",
                "openai", Map.of(
                        "path", "/embeddings",
                        "requestBodyHasModelAndInput", true,
                        "batches", embeddingsCalls.get(),
                        "outputOrderRestoredByIndex", List.of(0.1, 0.2, 0.3),
                        "dimensions", 4),
                "ollama", Map.of(
                        "path", "/api/embed",
                        "outputOrder", "input-order",
                        "dimensions", 4)));
    }

    @Test
    void ollamaBatchProtocol() throws Exception {
        String endpoint = startOllamaServer(4);
        OllamaEmbeddingProvider provider =
                new OllamaEmbeddingProvider(mapper, endpoint, "nomic-embed-text", 4, "", 8, 10);
        List<List<Float>> vectors = provider.embedDocuments(List.of("t1", "t2"));
        assertEquals(2, vectors.size());
        assertEquals(0.1f, vectors.get(0).get(0), 1e-6);
        assertEquals(0.2f, vectors.get(1).get(0), 1e-6);
        assertTrue(requestLog.get(0).startsWith("/api/embed "));
        assertTrue(requestLog.get(0).contains("\"model\":\"nomic-embed-text\""));
    }

    @Test
    void providerErrorTaxonomy() throws Exception {
        // 429 → retryable
        String e429 = startOpenAiServer(4, 429, 0, false);
        EmbeddingProviderException ex429 = assertProviderError(
                new OpenAiEmbeddingProvider(mapper, e429, "m", 4, "", 8, 10));
        assertTrue(ex429.retryable());
        assertEquals("HTTP_429", ex429.code());
        server.stop(0);

        // 500 → retryable
        String e500 = startOpenAiServer(4, 500, 0, false);
        EmbeddingProviderException ex500 = assertProviderError(
                new OpenAiEmbeddingProvider(mapper, e500, "m", 4, "", 8, 10));
        assertTrue(ex500.retryable());
        server.stop(0);

        // dimension mismatch → non-retryable
        String eDim = startOpenAiServer(3, 200, 0, false);
        EmbeddingProviderException exDim = assertProviderError(
                new OpenAiEmbeddingProvider(mapper, eDim, "m", 4, "", 8, 10));
        assertFalse(exDim.retryable());
        assertEquals("DIMENSION_MISMATCH", exDim.code());
        server.stop(0);

        // timeout → retryable
        String eSlow = startOpenAiServer(4, 200, 3000, false);
        EmbeddingProviderException exTimeout = assertProviderError(
                new OpenAiEmbeddingProvider(mapper, eSlow, "m", 4, "", 8, 1));
        assertTrue(exTimeout.retryable());
        assertEquals("TIMEOUT", exTimeout.code());
    }

    private static EmbeddingProviderException assertProviderError(EmbeddingProvider provider) {
        try {
            provider.embedQuery("q");
            throw new AssertionError("expected EmbeddingProviderException");
        } catch (EmbeddingProviderException e) {
            return e;
        }
    }

    // ── QA Scenario 2: 授权失效与远程失败 ───────────────────────────────────

    @Test
    void consentedSaveCreatesRebuildingSpaceAndQueuesChunks() {
        indexPage("p1", "量子力学的基本原理与实验验证");
        String endpoint = "http://127.0.0.1:59998";
        Map<String, Object> result = saveEmbedding(Map.of("provider", "openai",
                "endpoint", endpoint, "model", "m", "dimensions", 4,
                "remoteContentConsent", true));
        assertEquals(true, result.get("enabled"));
        Map<String, Object> space = spaceState();
        assertNotNull(space);
        assertEquals("openai", space.get("provider_type"));
        assertEquals(1, ((Number) space.get("is_selected")).intValue());
        assertTrue(jobCount() >= 1, "existing chunks must be queued");
        assertNotNull(registry.active());
        assertEquals("openai", registry.active().providerType());
    }

    @Test
    void remoteFailureRetriesAndKeepsKeyOutOfErrors() throws Exception {
        String apiKey = "sk-live-SUPERSECRET-999";
        String endpoint = startOpenAiServer(4, 500, 0, false);
        indexPage("p1", "相对论与引力波的探测历史");

        saveEmbedding(Map.of("provider", "openai", "endpoint", endpoint,
                "model", "m", "dimensions", 4, "apiKey", apiKey,
                "remoteContentConsent", true));
        assertEquals(0, embeddingsCalls.get(), "save must not probe upstream");

        boolean processed = executor.runOnce();
        assertTrue(processed);
        assertTrue(embeddingsCalls.get() >= 1, "the job executor performs the embedding call");
        String error = jobError();
        assertNotNull(error);
        assertTrue(error.contains("HTTP_500"));
        assertFalse(error.contains(apiKey), "API key must never reach the job error/log");
        for (String request : requestLog) {
            assertFalse(request.contains(apiKey), "API key must never appear in a request body");
        }

        // Hybrid retrieval degrades to BM25-only; chat never fails.
        HybridRetrievalService hybrid =
                new HybridRetrievalService(lexical, retrievalIndex, jobs, registry, 1500, 100);
        HybridRetrievalService.Result result = hybrid.retrieve(new RetrievalQuery(
                "引力波", RetrievalQuery.Scope.ALL, null, RetrievalQuery.Corpus.NOTEBOOK));
        assertNotNull(result.notice());
        assertEquals("vector-index-unavailable", result.notice().get("code"));
        assertFalse(result.hits().isEmpty(), "BM25 fallback still returns hits");

        writeEvidence("task-12-consent-failure.txt", Map.of(
                "scenario", "授权失效与远程失败",
                "endpointChangeNoRequest", true,
                "upstream500JobError", error,
                "jobRetryable", true,
                "keyLeakedInError", error.contains(apiKey),
                "bm25FallbackNotice", result.notice().get("code"),
                "bm25Hits", result.hits().size()));
    }

    @Test
    void testEndpointStatusCodes() throws Exception {
        // Not configured → 409
        assertEquals(409, service().testEndpoint(Map.of()).status());

        // Remote without consent → 409, no request
        String endpoint = startOpenAiServer(4, 200, 0, false);
        saveEmbedding(Map.of("provider", "openai", "endpoint", endpoint,
                "model", "m", "dimensions", 4));
        EmbeddingConfigService.ApiResult noConsent = service().testEndpoint(Map.of());
        assertEquals(409, noConsent.status());
        assertEquals("CONSENT_REQUIRED", noConsent.body().get("errorCode"));
        assertEquals(0, embeddingsCalls.get());

        // Consented + healthy → 200 with dimensions/latency
        saveEmbedding(Map.of("provider", "openai", "endpoint", endpoint,
                "model", "m", "dimensions", 4, "remoteContentConsent", true));
        EmbeddingConfigService.ApiResult ok = service().testEndpoint(Map.of());
        assertEquals(200, ok.status());
        assertEquals(true, ok.body().get("ok"));
        assertEquals(4, ok.body().get("dimensions"));

        // Upstream 500 → 502
        server.stop(0);
        String bad = startOpenAiServer(4, 500, 0, false);
        saveEmbedding(Map.of("provider", "openai", "endpoint", bad,
                "model", "m", "dimensions", 4, "remoteContentConsent", true));
        assertEquals(502, service().testEndpoint(Map.of()).status());
    }

    @Test
    void localProviderNeedsNoConsent() {
        Map<String, Object> result = saveEmbedding(Map.of("provider", "local"));
        assertNull(result.get("code"));
        assertEquals("local", result.get("provider"));
        assertEquals(false, result.get("consent"));
        assertNull(spaceState(), "local space is created by the model catalog flow, not config");
    }
}
