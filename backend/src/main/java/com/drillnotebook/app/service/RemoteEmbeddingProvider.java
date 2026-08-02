package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared HTTP plumbing for remote embedding providers (OpenAI-compatible,
 * Ollama).
 *
 * <p>Frozen error contract (Task 12): timeouts/connect errors/429/5xx are
 * retryable; any other non-2xx, response-shape or dimension mismatch is a
 * non-retryable typed error. Redirects are never followed, and neither the
 * API key nor input text is ever logged.
 */
abstract class RemoteEmbeddingProvider implements EmbeddingProvider {

    protected final ObjectMapper mapper;
    /** Normalized endpoint (no trailing slash), see {@link EmbeddingSpaceContracts#normalizeEndpoint}. */
    protected final String endpoint;
    private final String model;
    private final int dimensions;
    private final String apiKey;
    private final int batchSize;
    private final Duration timeout;
    private final HttpClient http;

    protected RemoteEmbeddingProvider(ObjectMapper mapper, String endpoint, String model,
                                      int dimensions, String apiKey, int batchSize, int timeoutSeconds) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.model = model;
        this.dimensions = dimensions;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.batchSize = Math.max(1, batchSize);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override public String modelId() { return model; }

    @Override public int dimensions() { return dimensions; }

    /** Remote endpoints have no cheap local health signal; always worth trying. */
    @Override public boolean isAvailable() { return true; }

    @Override
    public List<List<Float>> embedDocuments(List<String> texts) throws EmbeddingProviderException {
        List<List<Float>> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            out.addAll(embedBatch(texts.subList(from, Math.min(texts.size(), from + batchSize))));
        }
        return out;
    }

    @Override
    public List<Float> embedQuery(String text) throws EmbeddingProviderException {
        return embedBatch(List.of(text)).get(0);
    }

    /** Fixed request URL (path is part of the frozen protocol, never guessed). */
    protected abstract String requestUrl();

    /** JSON-serializable request body for one batch. */
    protected abstract Object requestBody(List<String> texts, String model);

    /** Extract exactly {@code count} vectors in input order or throw a typed error. */
    protected abstract List<List<Float>> parseVectors(JsonNode root, int count)
            throws EmbeddingProviderException;

    private List<List<Float>> embedBatch(List<String> texts) throws EmbeddingProviderException {
        String body;
        try {
            body = mapper.writeValueAsString(requestBody(texts, model));
        } catch (Exception e) {
            throw new EmbeddingProviderException("REQUEST_ENCODING", "embedding 请求编码失败", false);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(requestUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new EmbeddingProviderException("TIMEOUT", "embedding 请求超时", true);
        } catch (IOException e) {
            throw new EmbeddingProviderException("CONNECT_ERROR", "embedding 连接失败", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingProviderException("INTERRUPTED", "embedding 请求被中断", true);
        }
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new EmbeddingProviderException("HTTP_" + status, "上游返回 HTTP " + status, true);
        }
        if (status < 200 || status >= 300) {
            throw new EmbeddingProviderException("HTTP_" + status, "上游返回 HTTP " + status, false);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new EmbeddingProviderException("INVALID_RESPONSE", "embedding 响应不是合法 JSON", false);
        }
        List<List<Float>> vectors = parseVectors(root, texts.size());
        for (List<Float> vector : vectors) {
            if (vector == null || vector.size() != dimensions) {
                throw new EmbeddingProviderException("DIMENSION_MISMATCH",
                        "期望 " + dimensions + " 维，上游返回 "
                                + (vector == null ? 0 : vector.size()) + " 维", false);
            }
        }
        return vectors;
    }

    /** Shared helper: JSON array node → float list, rejecting non-numeric items. */
    protected static List<Float> floatList(JsonNode array) throws EmbeddingProviderException {
        if (array == null || !array.isArray()) {
            throw new EmbeddingProviderException("INVALID_RESPONSE", "embedding 向量缺失或不是数组", false);
        }
        List<Float> out = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!item.isNumber()) {
                throw new EmbeddingProviderException("INVALID_RESPONSE", "embedding 向量包含非数字", false);
            }
            out.add((float) item.asDouble());
        }
        return out;
    }
}
