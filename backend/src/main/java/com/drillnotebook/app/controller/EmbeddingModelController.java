package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.EmbeddingConfigService;
import com.drillnotebook.app.service.EmbeddingModelService;
import com.drillnotebook.app.service.HuggingFaceCatalogService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Embedding model lifecycle endpoints (Canonical Contracts table).
 * All status/idempotency decisions live in {@link EmbeddingModelService};
 * this controller only maps {@code ApiResult} to HTTP responses.
 */
@RestController
@RequestMapping("/api/ai/embeddings")
public class EmbeddingModelController {

    private final EmbeddingModelService service;
    private final EmbeddingConfigService config;
    private final HuggingFaceCatalogService hfCatalog;

    public EmbeddingModelController(EmbeddingModelService service, EmbeddingConfigService config,
            HuggingFaceCatalogService hfCatalog) {
        this.service = service;
        this.config = config;
        this.hfCatalog = hfCatalog;
    }

    /** POST /api/ai/embeddings/test — probe the saved embedding config. */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestBody(required = false) Map<String, Object> body) {
        EmbeddingConfigService.ApiResult result =
                config.testEndpoint(body == null ? Map.of() : body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        Map<String, Object> response = service.catalogResponse();
        // Merge online catalog models
        HuggingFaceCatalogService.CatalogResult online = hfCatalog.getCatalog(refresh);
        if (!online.models().isEmpty()) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> models =
                    (java.util.List<Map<String, Object>>) response.get("models");
            java.util.Set<String> existingIds = new java.util.HashSet<>();
            models.forEach(m -> existingIds.add((String) m.get("id")));
            for (var model : online.models()) {
                if (!existingIds.contains(model.id())) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", model.id());
                    row.put("providerModelId", model.providerModelId());
                    row.put("artifactRevision", model.artifactRevision());
                    row.put("displayName", model.displayName());
                    row.put("license", model.license());
                    row.put("languages", model.languages());
                    row.put("dimensions", model.dimensions());
                    row.put("inventorySizeBytes", model.inventorySizeBytes());
                    row.put("installationState", "AVAILABLE");
                    row.put("downloadError", null);
                    row.put("downloadProgress", null);
                    row.put("source", "online");
                    models.add(row);
                }
            }
            response.put("onlineStale", online.stale());
            response.put("onlineError", online.error());
        } else {
            response.put("onlineStale", online.stale());
            response.put("onlineError", online.error());
        }
        return response;
    }

    @PostMapping("/models/{id}/download")
    public ResponseEntity<Map<String, Object>> download(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return toResponse(service.download(id, body));
    }

    @DeleteMapping("/downloads/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelDownload(@PathVariable String jobId) {
        return toResponse(service.cancelDownload(jobId));
    }

    @PostMapping("/models/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable String id) {
        return toResponse(service.activate(id));
    }

    @PostMapping("/models/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable String id) {
        return toResponse(service.disable(id));
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<Map<String, Object>> uninstall(
            @PathVariable String id,
            @RequestParam(required = false) Boolean confirm) {
        return toResponse(service.uninstall(id, Boolean.TRUE.equals(confirm)));
    }

    private static ResponseEntity<Map<String, Object>> toResponse(
            EmbeddingModelService.ApiResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
