package com.drillnotebook.app.config;

import com.drillnotebook.app.service.ModelCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the built-in pinned embedding-model catalog (no network access). */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    public ModelCatalog embeddingModelCatalog(ObjectMapper mapper) {
        return ModelCatalog.loadBuiltIn(mapper);
    }
}
