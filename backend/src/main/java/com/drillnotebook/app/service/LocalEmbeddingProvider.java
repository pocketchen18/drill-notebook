package com.drillnotebook.app.service;

import java.util.List;
import java.util.UUID;

/**
 * Local embedding provider backed by the Rust FastEmbed worker via
 * {@link EmbeddingWorkerLifecycle}.
 *
 * <p>The worker owns query/document prefixing (canonical contract); Java only
 * selects the {@code mode}. Model loading is lazy and re-attempted after any
 * failure because a worker crash/restart loses loaded model state.
 */
public class LocalEmbeddingProvider implements EmbeddingProvider {

    public static final String PROVIDER_TYPE = "local-rust";

    private final EmbeddingWorkerLifecycle lifecycle;
    private final String modelId;
    private final String modelDir;
    private final List<String> requiredFiles;
    private final int dimensions;

    private final Object loadLock = new Object();
    private volatile boolean modelLoaded = false;

    public LocalEmbeddingProvider(
            EmbeddingWorkerLifecycle lifecycle,
            String modelId,
            String modelDir,
            List<String> requiredFiles,
            int dimensions) {
        this.lifecycle = lifecycle;
        this.modelId = modelId;
        this.modelDir = modelDir;
        this.requiredFiles = List.copyOf(requiredFiles);
        this.dimensions = dimensions;
    }

    @Override
    public String providerType() { return PROVIDER_TYPE; }

    @Override
    public String modelId() { return modelId; }

    @Override
    public int dimensions() { return dimensions; }

    @Override
    public boolean isAvailable() {
        return lifecycle.isConfigured();
    }

    @Override
    public List<List<Float>> embedDocuments(List<String> texts)
            throws EmbeddingProviderException {
        return embed(WorkerProtocol.EmbedMode.DOCUMENT, texts);
    }

    @Override
    public List<Float> embedQuery(String text) throws EmbeddingProviderException {
        List<List<Float>> result = embed(WorkerProtocol.EmbedMode.QUERY, List.of(text));
        return result.get(0);
    }

    private List<List<Float>> embed(WorkerProtocol.EmbedMode mode, List<String> inputs)
            throws EmbeddingProviderException {
        ensureModelLoaded();
        String rid = UUID.randomUUID().toString();
        WorkerResult result = lifecycle.execute(rid, new WorkerProtocol.Request.Embed(
                WorkerProtocol.PROTOCOL_VERSION, rid, mode, inputs));
        if (result instanceof WorkerResult.Success success) {
            List<List<Float>> embeddings = success.embeddings();
            if (embeddings == null || embeddings.size() != inputs.size()) {
                // Response shape violation cannot be fixed by retrying the same input.
                throw new EmbeddingProviderException(
                        "INVALID_RESPONSE",
                        "Expected " + inputs.size() + " embeddings, got "
                                + (embeddings == null ? 0 : embeddings.size()),
                        false);
            }
            return embeddings;
        }
        // Any worker failure may have lost loaded-model state; force a reload next call.
        modelLoaded = false;
        if (result instanceof WorkerResult.WorkerNotBuilt notBuilt) {
            throw new EmbeddingProviderException(
                    "WORKER_NOT_BUILT", notBuilt.detail(), true);
        }
        String detail = result instanceof WorkerResult.WorkerUnavailable unavailable
                ? unavailable.detail() : "Unknown worker result";
        throw new EmbeddingProviderException("WORKER_UNAVAILABLE", detail, true);
    }

    private void ensureModelLoaded() throws EmbeddingProviderException {
        // A crashed-and-restarted worker starts without a model even though we
        // loaded one earlier, so a non-ready worker always forces a reload.
        if (modelLoaded && lifecycle.isReady()) return;
        synchronized (loadLock) {
            if (modelLoaded && lifecycle.isReady()) return;
            String rid = UUID.randomUUID().toString();
            WorkerResult result = lifecycle.execute(rid, new WorkerProtocol.Request.LoadModel(
                    WorkerProtocol.PROTOCOL_VERSION, rid, modelId, modelDir,
                    requiredFiles, dimensions));
            if (result instanceof WorkerResult.Success) {
                modelLoaded = true;
                return;
            }
            modelLoaded = false;
            if (result instanceof WorkerResult.WorkerNotBuilt notBuilt) {
                throw new EmbeddingProviderException(
                        "WORKER_NOT_BUILT", notBuilt.detail(), true);
            }
            String detail = result instanceof WorkerResult.WorkerUnavailable unavailable
                    ? unavailable.detail() : "Unknown worker result";
            throw new EmbeddingProviderException("MODEL_LOAD_FAILED", detail, true);
        }
    }
}
