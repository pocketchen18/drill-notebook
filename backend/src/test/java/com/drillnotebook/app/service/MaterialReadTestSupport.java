package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.MaterialReadRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real read-tool wiring; embedding remains opt-in in ordinary material tests. */
final class MaterialReadTestSupport {
    private MaterialReadTestSupport() {}

    static MaterialReadService service(JdbcTemplate jdbc, ObjectMapper json) {
        return service(jdbc, json, hybrid(jdbc, json, new EmbeddingProviderRegistry(), 1500, 500));
    }

    static MaterialReadService service(JdbcTemplate jdbc, ObjectMapper json, HybridRetrievalService hybrid) {
        var materials = new MaterialReadRepository(jdbc);
        return new MaterialReadService(materials, json, new MaterialSearchService(materials, hybrid));
    }

    static HybridRetrievalService hybrid(JdbcTemplate jdbc, ObjectMapper json, EmbeddingProviderRegistry providers,
                                         long timeout, long workerReady) {
        var index = new RetrievalIndexRepository(jdbc);
        return new HybridRetrievalService(new RetrievalService(index, new NotebookRepository(jdbc, json)),
                index, new EmbeddingJobRepository(jdbc), providers, timeout, workerReady);
    }
}
