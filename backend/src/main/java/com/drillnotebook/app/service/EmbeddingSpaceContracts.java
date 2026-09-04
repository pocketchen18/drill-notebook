package com.drillnotebook.app.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Canonical embedding-space contracts (Canonical Contracts section of the
 * plan): a UTF-8 JSON document with lexicographically sorted keys whose
 * SHA-256 hex is the {@code embedding_space_id}.
 */
public final class EmbeddingSpaceContracts {

    /** Product-fixed query prefix for the local bge-small-zh model. */
    public static final String LOCAL_QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    public static final String LOCAL_POOLING = "cls";
    public static final String NORMALIZATION = "java-l2-v1";

    private EmbeddingSpaceContracts() {}

    /**
     * Canonical JSON for a local model space. Keys are emitted in
     * lexicographic order; values are JSON strings except {@code dimensions}
     * and {@code catalogVersion} which are numbers.
     */
    public static String localCanonicalJson(ModelCatalog.CatalogModel model, int catalogVersion) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"artifactRevision\":").append(quote(model.artifactRevision())).append(',');
        sb.append("\"catalogVersion\":").append(catalogVersion).append(',');
        sb.append("\"dimensions\":").append(model.dimensions()).append(',');
        sb.append("\"documentPrefix\":\"\",");
        sb.append("\"model\":").append(quote(model.providerModelId())).append(',');
        sb.append("\"normalization\":").append(quote(NORMALIZATION)).append(',');
        sb.append("\"normalizedEndpoint\":\"\",");
        sb.append("\"pooling\":").append(quote(LOCAL_POOLING)).append(',');
        sb.append("\"providerType\":").append(quote(LocalEmbeddingProvider.PROVIDER_TYPE)).append(',');
        sb.append("\"queryPrefix\":").append(quote(LOCAL_QUERY_PREFIX));
        sb.append('}');
        return sb.toString();
    }

    /** {@code embedding_space_id} = lowercase hex SHA-256 of the canonical JSON. */
    public static String spaceId(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Canonical JSON for a remote (openai/ollama) space. Per the plan,
     * artifactRevision/catalogVersion/pooling/prefixes are empty strings and
     * normalization stays {@code java-l2-v1}.
     */
    public static String remoteCanonicalJson(
            String providerType, String normalizedEndpoint, String model, int dimensions) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"artifactRevision\":\"\",");
        sb.append("\"catalogVersion\":\"\",");
        sb.append("\"dimensions\":").append(dimensions).append(',');
        sb.append("\"documentPrefix\":\"\",");
        sb.append("\"model\":").append(quote(model)).append(',');
        sb.append("\"normalization\":").append(quote(NORMALIZATION)).append(',');
        sb.append("\"normalizedEndpoint\":").append(quote(normalizedEndpoint)).append(',');
        sb.append("\"pooling\":\"\",");
        sb.append("\"providerType\":").append(quote(providerType)).append(',');
        sb.append("\"queryPrefix\":\"\"");
        sb.append('}');
        return sb.toString();
    }

    /**
     * Endpoint normalization (frozen): URI parse, lowercase scheme/host,
     * drop fragment/query/userinfo, drop default port 80/443, collapse
     * duplicate path slashes, strip the final slash; only http/https.
     *
     * @throws IllegalArgumentException for non-http(s) or unparsable input
     */
    public static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(endpoint.trim());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Base URL 不是合法 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Base URL 仅支持 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Base URL 缺少主机名");
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port < 0
                || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        path = path.replaceAll("/{2,}", "/").replaceAll("/$", "");
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    }

    /** {@code consentFingerprint = SHA-256(provider + '\n' + normalizedEndpoint + '\n' + model)}. */
    public static String consentFingerprint(String providerType, String normalizedEndpoint, String model) {
        return spaceId(providerType + "\n" + normalizedEndpoint + "\n" + model);
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
