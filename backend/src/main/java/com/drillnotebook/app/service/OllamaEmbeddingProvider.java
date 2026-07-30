package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama embedding provider.
 *
 * <p>Frozen protocol: {@code POST {endpoint}/api/embed} with body
 * {@code {"model":..., "input":[...]}} and response
 * {@code {"embeddings":[[...],[...]]}} in input order. The legacy
 * {@code /api/embeddings} route is never assumed.
 */
public class OllamaEmbeddingProvider extends RemoteEmbeddingProvider {

    public static final String PROVIDER_TYPE = "ollama";

    public OllamaEmbeddingProvider(ObjectMapper mapper, String endpoint, String model,
                                   int dimensions, String apiKey, int batchSize, int timeoutSeconds) {
        super(mapper, endpoint, model, dimensions, apiKey, batchSize, timeoutSeconds);
    }

    @Override public String providerType() { return PROVIDER_TYPE; }

    @Override
    protected String requestUrl() {
        return endpoint.replaceAll("/+$", "") + "/api/embed";
    }

    @Override
    protected Object requestBody(List<String> texts, String model) {
        return Map.of("model", model, "input", texts);
    }

    @Override
    protected List<List<Float>> parseVectors(JsonNode root, int count)
            throws EmbeddingProviderException {
        JsonNode embeddings = root.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != count) {
            throw new EmbeddingProviderException("INVALID_RESPONSE",
                    "期望 embeddings 含 " + count + " 项，实际 "
                            + (embeddings.isArray() ? embeddings.size() : 0), false);
        }
        List<List<Float>> out = new ArrayList<>(count);
        for (JsonNode vector : embeddings) {
            out.add(floatList(vector));
        }
        return out;
    }
}
