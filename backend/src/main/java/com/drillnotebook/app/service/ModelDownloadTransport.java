package com.drillnotebook.app.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

/**
 * Transport abstraction for model artifact downloads.
 *
 * <p>The production implementation ({@link PinnedHttpsTransport}) enforces
 * HTTPS with a pinned host allowlist. Tests may inject a loopback-only HTTP
 * fixture transport; that class lives in the test sources and can never be
 * enabled through regular configuration or APIs.
 */
public interface ModelDownloadTransport {

    /**
     * Perform a GET on {@code uri} with the given request headers
     * (e.g. {@code Range}/{@code If-Range}). The caller must close the
     * response.
     */
    Response get(URI uri, Map<String, String> headers) throws IOException;

    interface Response extends Closeable {
        /** HTTP status code (200/206/416/...). */
        int status();

        /** First value of a response header, or {@code null}. */
        String header(String name);

        /** Response body stream (may be empty for error statuses). */
        InputStream body();
    }
}
