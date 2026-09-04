package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.EmbeddingJobRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Read-only index status for the UI poller
 * ({@code GET /api/ai/retrieval/status}).
 *
 * <p>Scope {@code all} covers every notebook; {@code current} restricts
 * page/chunk/job counts to one notebook. {@code coverage} and
 * {@code indexState} always describe the whole selected embedding space
 * (activation is a corpus-wide property, never per notebook).
 */
@Service
public class RetrievalStatusService {

    private static final String CORPUS_NOTEBOOK = "NOTEBOOK";

    private final EmbeddingJobRepository jobs;
    private final EmbeddingProviderRegistry providers;

    public RetrievalStatusService(
            EmbeddingJobRepository jobs, EmbeddingProviderRegistry providers) {
        this.jobs = jobs;
        this.providers = providers;
    }

    /**
     * Whether the active provider can actually produce vectors right now. The
     * local provider reports {@code false} when the Rust worker binary is not
     * configured/built, which is the difference between "still building" and
     * "will never finish".
     */
    private boolean providerReady(int dimensions) {
        EmbeddingProvider provider = providers == null ? null : providers.active();
        return provider != null && provider.isAvailable()
                && provider.dimensions() == dimensions;
    }

    public Map<String, Object> status(String scope, Long notebookId) {
        String normalizedScope = "current".equals(scope) ? "current" : "all";
        if ("current".equals(normalizedScope) && notebookId == null) {
            throw new IllegalArgumentException("scope=current 需要 notebookId");
        }
        Long corpusId = "current".equals(normalizedScope) ? notebookId : null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", normalizedScope);
        out.put("notebookId", corpusId);
        out.put("totalPages", jobs.countPages(corpusId));
        out.put("totalChunks", jobs.countChunks(CORPUS_NOTEBOOK, corpusId));

        Map<String, Object> space = jobs.findSelectedSpaceAnyState();
        if (space == null) {
            out.put("indexedChunks", 0);
            out.put("staleChunks", 0);
            out.put("queuedJobs", 0);
            out.put("failedJobs", 0);
            out.put("coverage", 0.0);
            out.put("indexState", "DISABLED");
            out.put("embeddingSpaceId", null);
            out.put("provider", null);
            out.put("providerReady", false);
            return out;
        }

        String spaceId = (String) space.get("embedding_space_id");
        out.put("indexedChunks",
                jobs.countIndexedChunks(CORPUS_NOTEBOOK, spaceId, corpusId));
        out.put("staleChunks",
                jobs.countStaleChunks(CORPUS_NOTEBOOK, spaceId, corpusId));
        out.put("queuedJobs", jobs.countJobsByStatuses(
                spaceId, List.of("QUEUED", "CLAIMED", "RETRY"), corpusId));
        out.put("failedJobs", jobs.countJobsByStatuses(
                spaceId, List.of("FAILED"), corpusId));
        out.put("coverage", space.get("coverage"));
        out.put("indexState", space.get("state"));
        out.put("embeddingSpaceId", spaceId);
        out.put("provider", space.get("provider_type"));
        out.put("providerReady",
                providerReady(((Number) space.get("dimensions")).intValue()));
        return out;
    }
}
