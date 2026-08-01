package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible embedding provider.
 *
 * <p>Frozen protocol: {@code POST {endpoint-without-trailing-slash}/embeddings}
 * with body {@code {"model":..., "input":[...]}}; the response's
 * {@code data[i].index} restores input order. Missing/duplicate/out-of-range
 * indexes are a non-retryable {@code INVALID_RESPONSE}.
 */
public class OpenAiEmbeddingProvider extends RemoteEmbeddingProvider {

    public static final String PROVIDER_TYPE = "openai";

    public OpenAiEmbeddingProvider(ObjectMapper mapper, String endpoint, String model,
                                   int dimensions, String apiKey, int batchSize, int timeoutSeconds) {
        super(mapper, endpoint, model, dimensions, apiKey, batchSize, timeoutSeconds);
    }

    @Override public String providerType() { return PROVIDER_TYPE; }

    @Override
    protected String requestUrl() {
        return endpoint.replaceAll("/+$", "") + "/embeddings";
    }

    @Override
    protected Object requestBody(List<String> texts, String model) {
        return Map.of("model", model, "input", texts);
    }

    @Override
    protected List<List<Float>> parseVectors(JsonNode root, int count)
            throws EmbeddingProviderException {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != count) {
            throw new EmbeddingProviderException("INVALID_RESPONSE",
                    "期望 data 含 " + count + " 项，实际 " + (data.isArray() ? data.size() : 0), false);
        }
        List<List<Float>> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(null);
        for (JsonNode item : data) {
            JsonNode indexNode = item.path("index");
            if (!indexNode.isInt() || indexNode.asInt() < 0 || indexNode.asInt() >= count) {
                throw new EmbeddingProviderException("INVALID_RESPONSE",
                        "data.index 缺失或越界", false);
            }
            int index = indexNode.asInt();
            if (out.get(index) != null) {
                throw new EmbeddingProviderException("INVALID_RESPONSE",
                        "data.index 重复：" + index, false);
            }
            out.set(index, floatList(item.path("embedding")));
        }
        for (List<Float> vector : out) {
            if (vector == null) {
                throw new EmbeddingProviderException("INVALID_RESPONSE",
                        "data 缺少部分 index 的向量", false);
            }
        }
        return out;
    }
}
