package com.drillnotebook.app.service;

import java.util.List;

/**
 * Unified embedding provider abstraction shared by Local (Rust worker),
 * OpenAI-compatible and Ollama adapters.
 *
 * <p>Implementations return raw provider vectors; the caller is responsible
 * for validation, L2 normalization and BLOB encoding via
 * {@link EmbeddingVectorCodec}. Implementations must never log or persist
 * input text.
 */
public interface EmbeddingProvider {

    /** Provider type, e.g. {@code local-rust}, {@code openai}, {@code ollama}. */
    String providerType();

    /** Stable model identifier, e.g. {@code Qdrant/bge-small-zh-v1.5}. */
    String modelId();

    /** Expected vector dimension count. */
    int dimensions();

    /**
     * Embed a batch of document chunks.
     *
     * @return one raw vector per input, in input order
     */
    List<List<Float>> embedDocuments(List<String> texts) throws EmbeddingProviderException;

    /** Embed a single retrieval query. */
    List<Float> embedQuery(String text) throws EmbeddingProviderException;

    /**
     * Cheap health check: whether the provider is currently worth calling.
     * Returning {@code false} makes the job executor idle-wait instead of
     * burning job attempts.
     */
    boolean isAvailable();
}
