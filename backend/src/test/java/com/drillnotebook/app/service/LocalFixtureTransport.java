package com.drillnotebook.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * Test-only download transport: plain HTTP restricted to loopback hosts.
 *
 * <p>Lives in the test sources so it can never be enabled through regular
 * configuration or APIs. It performs no redirects and still leaves all
 * catalog ID/revision/size/SHA-256 validation to the download service.
 */
public class LocalFixtureTransport implements ModelDownloadTransport {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    @Override
    public Response get(URI uri, Map<String, String> headers) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("fixture transport only allows loopback HTTP: " + uri);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!LOOPBACK_HOSTS.contains(host)) {
            throw new IOException("fixture transport only allows loopback hosts: " + host);
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(60_000);
        connection.setRequestMethod("GET");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        int status = connection.getResponseCode();
        return new Response() {
            @Override
            public int status() { return status; }

            @Override
            public String header(String name) { return connection.getHeaderField(name); }

            @Override
            public InputStream body() {
                try {
                    InputStream stream = status >= 400
                            ? connection.getErrorStream() : connection.getInputStream();
                    return stream == null ? InputStream.nullInputStream() : stream;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void close() { connection.disconnect(); }
        };
    }
}
