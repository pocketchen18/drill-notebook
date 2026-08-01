package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable pinned embedding-model catalog (v0.5: built-in JSON only, no
 * online refresh or fallback URLs).
 *
 * <p>All IDs and file names are validated on load so they can never be used
 * for path traversal, and every file carries the exact expected size and
 * SHA-256 from the immutable HuggingFace revision.
 */
public record ModelCatalog(int catalogVersion, List<CatalogModel> models) {

    public static final String BUILT_IN_RESOURCE = "/embedding-model-catalog-v1.json";

    /** URL-safe id/file-name policy: no separators, no dot-dot, no leading dot. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    public record CatalogFile(String name, long sizeBytes, String sha256, boolean runtimeRequired) {}

    public record CatalogModel(
            String id,
            String providerModelId,
            String artifactRevision,
            String displayName,
            String license,
            List<String> languages,
            int dimensions,
            long inventorySizeBytes,
            String baseUrl,
            List<CatalogFile> files) {

        /** Runtime-required file names, in catalog order. */
        public List<String> runtimeRequiredFiles() {
            return files.stream().filter(CatalogFile::runtimeRequired).map(CatalogFile::name).toList();
        }
    }

    /** Load and validate the built-in catalog from the classpath (no network). */
    public static ModelCatalog loadBuiltIn(ObjectMapper mapper) {
        try (InputStream in = ModelCatalog.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing catalog resource " + BUILT_IN_RESOURCE);
            }
            ModelCatalog catalog = mapper.readValue(in, ModelCatalog.class);
            catalog.validate();
            return catalog;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load embedding model catalog", e);
        }
    }

    /** Validate ids, file names, hashes and inventory sums; throws on violation. */
    public void validate() {
        for (CatalogModel model : models) {
            requireSafe(model.id(), "model id");
            if (model.artifactRevision() == null || !model.artifactRevision().matches("^[0-9a-f]{40}$")) {
                throw new IllegalStateException("Invalid artifact revision for " + model.id());
            }
            if (model.dimensions() <= 0) {
                throw new IllegalStateException("Invalid dimensions for " + model.id());
            }
            long sum = 0;
            for (CatalogFile file : model.files()) {
                requireSafe(file.name(), "file name");
                if (file.sha256() == null || !file.sha256().matches("^[0-9a-f]{64}$")) {
                    throw new IllegalStateException("Invalid sha256 for " + file.name());
                }
                if (file.sizeBytes() <= 0) {
                    throw new IllegalStateException("Invalid size for " + file.name());
                }
                sum += file.sizeBytes();
            }
            if (sum != model.inventorySizeBytes()) {
                throw new IllegalStateException("Inventory size mismatch for " + model.id()
                        + ": files sum " + sum + " != declared " + model.inventorySizeBytes());
            }
        }
    }

    private static void requireSafe(String value, String what) {
        if (value == null || value.contains("..") || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalStateException("Unsafe " + what + ": " + value);
        }
    }

    /** Find a model by catalog id, or {@code null}. */
    public CatalogModel model(String catalogId) {
        return models.stream().filter(m -> m.id().equals(catalogId)).findFirst().orElse(null);
    }
}
