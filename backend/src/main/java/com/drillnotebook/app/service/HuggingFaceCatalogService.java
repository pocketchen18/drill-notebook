package com.drillnotebook.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fetches embedding model catalog from HuggingFace API.
 *
 * <p>Queries the HF model hub for sentence-similarity models that contain
 * fastembed-compatible ONNX files ({@code model_optimized.onnx} or
 * {@code model.onnx}), validates file availability via the tree API, and
 * returns a catalog compatible with the existing download flow.
 *
 * <p>Results are cached in memory (TTL 1 hour) and persisted to
 * {@code APP_ROOT/cache/embedding-catalog-cache.json} for offline access.
 */
@Service
public class HuggingFaceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceCatalogService.class);

    private static final String HF_API_BASE = "https://huggingface.co/api";
    private static final int SEARCH_LIMIT = 30;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long CACHE_TTL_MS = 3_600_000; // 1 hour

    /** Files required for a fastembed-compatible model. */
    private static final Set<String> REQUIRED_FILES = Set.of(
            "tokenizer.json", "config.json", "tokenizer_config.json", "special_tokens_map.json");

    /** At least one ONNX model file must exist. */
    private static final Set<String> ONNX_NAMES = Set.of(
            "model_optimized.onnx", "model.onnx");

    private final ObjectMapper mapper;
    private final Path cacheFile;

    private volatile List<ModelCatalog.CatalogModel> cachedModels;
    private volatile long cacheTimestamp;
    private volatile String lastError;

    public HuggingFaceCatalogService(
            ObjectMapper mapper,
            @Value("${app.root:runtime-portable}") String appRoot) {
        this.mapper = mapper;
        this.cacheFile = Path.of(appRoot, "cache", "embedding-catalog-cache.json");
        loadDiskCache();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Get the online catalog. Without forceRefresh, returns cache only (never
     * blocks on network). With forceRefresh, fetches from HuggingFace API
     * synchronously (may take several seconds).
     */
    public CatalogResult getCatalog(boolean forceRefresh) {
        if (!forceRefresh) {
            // Never block: return cache if available, else empty
            List<ModelCatalog.CatalogModel> cached = cachedModels;
            if (cached != null) {
                boolean stale = System.currentTimeMillis() - cacheTimestamp > CACHE_TTL_MS;
                return new CatalogResult(cached, stale, lastError);
            }
            return new CatalogResult(List.of(), true, null);
        }
        try {
            List<ModelCatalog.CatalogModel> models = fetchFromHuggingFace();
            cachedModels = models;
            cacheTimestamp = System.currentTimeMillis();
            lastError = null;
            saveDiskCache(models);
            return new CatalogResult(models, false, null);
        } catch (Exception e) {
            log.warn("Failed to fetch online model catalog: {}", e.getMessage());
            lastError = e.getMessage();
            List<ModelCatalog.CatalogModel> fallback =
                    cachedModels != null ? cachedModels : List.of();
            return new CatalogResult(fallback, true, e.getMessage());
        }
    }

    public String getLastError() {
        return lastError;
    }

    public record CatalogResult(List<ModelCatalog.CatalogModel> models, boolean stale, String error) {}

    // ── HuggingFace API ─────────────────────────────────────────────────────

    private List<ModelCatalog.CatalogModel> fetchFromHuggingFace() throws IOException {
        // Step 1: Search for embedding models
        List<String> modelIds = searchModels();
        log.info("HF catalog: found {} candidate models", modelIds.size());

        // Step 2: For each, verify files and build catalog entry
        List<ModelCatalog.CatalogModel> result = new ArrayList<>();
        for (String modelId : modelIds) {
            try {
                ModelCatalog.CatalogModel entry = buildCatalogEntry(modelId);
                if (entry != null) {
                    result.add(entry);
                }
            } catch (Exception e) {
                log.debug("Skipping model {}: {}", modelId, e.getMessage());
            }
            if (result.size() >= 15) break; // cap catalog size
        }
        log.info("HF catalog: {} verified fastembed-compatible models", result.size());
        return result;
    }

    private List<String> searchModels() throws IOException {
        // Fastembed-compatible models are published by Qdrant with ONNX at root
        String url = HF_API_BASE + "/models?author=Qdrant"
                + "&pipeline_tag=sentence-similarity"
                + "&sort=downloads&direction=-1&limit=" + SEARCH_LIMIT;
        JsonNode array = httpGetJson(url);
        List<String> ids = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String id = node.has("modelId") ? node.get("modelId").asText()
                        : node.has("id") ? node.get("id").asText() : null;
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Build a catalog entry for a model by checking its file tree.
     * Returns null if the model lacks required fastembed files.
     */
    private ModelCatalog.CatalogModel buildCatalogEntry(String modelId) throws IOException {
        // Get latest revision
        String revision = getLatestRevision(modelId);
        if (revision == null) return null;

        // Get file tree
        List<FileInfo> files = getFileTree(modelId, revision);
        if (files.isEmpty()) return null;

        // Check for ONNX model file
        FileInfo onnxFile = files.stream()
                .filter(f -> ONNX_NAMES.contains(f.name))
                .findFirst().orElse(null);
        if (onnxFile == null) return null;

        // Check required support files
        Set<String> available = new java.util.HashSet<>();
        files.forEach(f -> available.add(f.name));
        for (String req : REQUIRED_FILES) {
            if (!available.contains(req)) return null;
        }

        // Get dimensions from config.json
        int dimensions = getDimensions(modelId, revision);
        if (dimensions <= 0) return null;

        // Build file list (only required runtime files + onnx)
        List<ModelCatalog.CatalogFile> catalogFiles = new ArrayList<>();
        long totalSize = 0;
        // ONNX model first
        catalogFiles.add(new ModelCatalog.CatalogFile(
                onnxFile.name, onnxFile.size, onnxFile.sha256, true));
        totalSize += onnxFile.size;
        // Support files
        for (String req : List.of("tokenizer.json", "config.json",
                "tokenizer_config.json", "special_tokens_map.json")) {
            FileInfo fi = files.stream().filter(f -> f.name.equals(req)).findFirst().orElse(null);
            if (fi != null) {
                catalogFiles.add(new ModelCatalog.CatalogFile(fi.name, fi.size, fi.sha256, true));
                totalSize += fi.size;
            }
        }
        // Optional files
        for (FileInfo fi : files) {
            if (fi.name.equals("vocab.txt") || fi.name.equals("ort_config.json")) {
                catalogFiles.add(new ModelCatalog.CatalogFile(fi.name, fi.size, fi.sha256, false));
                totalSize += fi.size;
            }
        }

        String catalogId = toCatalogId(modelId);
        String baseUrl = "https://huggingface.co/" + modelId + "/resolve/" + revision + "/";
        String displayName = modelId.contains("/")
                ? modelId.substring(modelId.indexOf('/') + 1) : modelId;

        return new ModelCatalog.CatalogModel(
                catalogId, modelId, revision, displayName,
                "unknown", List.of("multilingual"), dimensions,
                totalSize, baseUrl, catalogFiles);
    }

    private String getLatestRevision(String modelId) throws IOException {
        String url = HF_API_BASE + "/models/" + modelId + "/revision/main";
        JsonNode node = httpGetJson(url);
        if (node != null && node.has("sha")) {
            return node.get("sha").asText();
        }
        return null;
    }

    private record FileInfo(String name, long size, String sha256) {}

    private List<FileInfo> getFileTree(String modelId, String revision) throws IOException {
        String url = HF_API_BASE + "/models/" + modelId + "/tree/" + revision;
        JsonNode array = httpGetJson(url);
        List<FileInfo> files = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                if (!"file".equals(node.path("type").asText())) continue;
                String name = node.path("path").asText();
                long size = node.path("size").asLong(0);
                // LFS files have sha256 in lfs.oid; regular files have blobId
                String sha = "";
                if (node.has("lfs") && node.get("lfs").has("sha256")) {
                    sha = node.get("lfs").get("sha256").asText();
                } else if (node.has("blobId")) {
                    sha = node.get("blobId").asText();
                }
                if (size > 0) {
                    files.add(new FileInfo(name, size, sha));
                }
            }
        }
        return files;
    }

    private int getDimensions(String modelId, String revision) {
        try {
            String url = "https://huggingface.co/" + modelId
                    + "/resolve/" + revision + "/config.json";
            JsonNode config = httpGetJson(url);
            if (config != null) {
                if (config.has("hidden_size")) return config.get("hidden_size").asInt(0);
                if (config.has("dim")) return config.get("dim").asInt(0);
            }
        } catch (Exception e) {
            log.debug("Cannot read dimensions for {}: {}", modelId, e.getMessage());
        }
        return 0;
    }

    // ── HTTP helper ─────────────────────────────────────────────────────────

    /** Resolve HTTP proxy from environment (HTTPS_PROXY / HTTP_PROXY). */
    private static Proxy resolveProxy() {
        String proxyUrl = System.getenv("HTTPS_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("https_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("HTTP_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("http_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) return Proxy.NO_PROXY;
        try {
            URI uri = URI.create(proxyUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port));
        } catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    private JsonNode httpGetJson(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL()
                .openConnection(resolveProxy());
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "DrillNotebook/0.5");
        int status = conn.getResponseCode();
        if (status != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + status + " from " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            return mapper.readTree(in);
        } finally {
            conn.disconnect();
        }
    }

    // ── Catalog ID ──────────────────────────────────────────────────────────

    /** Convert "Qdrant/bge-small-zh-v1.5" to URL-safe catalog id "bge-small-zh-v1.5". */
    static String toCatalogId(String modelId) {
        String name = modelId.contains("/")
                ? modelId.substring(modelId.indexOf('/') + 1) : modelId;
        // Ensure safe: only alphanumeric, dot, hyphen, underscore
        return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    // ── Disk cache ──────────────────────────────────────────────────────────

    private void saveDiskCache(List<ModelCatalog.CatalogModel> models) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("fetchedAt", System.currentTimeMillis());
            wrapper.put("models", models);
            Files.writeString(cacheFile, mapper.writeValueAsString(wrapper), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Cannot persist catalog cache: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDiskCache() {
        try {
            if (!Files.exists(cacheFile)) return;
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(json);
            long fetchedAt = root.path("fetchedAt").asLong(0);
            List<ModelCatalog.CatalogModel> models = mapper.convertValue(
                    root.get("models"),
                    new TypeReference<List<ModelCatalog.CatalogModel>>() {});
            if (models != null && !models.isEmpty()) {
                cachedModels = models;
                cacheTimestamp = fetchedAt;
                log.info("Loaded {} cached catalog models from disk", models.size());
            }
        } catch (Exception e) {
            log.debug("Cannot load catalog cache: {}", e.getMessage());
        }
    }
}
