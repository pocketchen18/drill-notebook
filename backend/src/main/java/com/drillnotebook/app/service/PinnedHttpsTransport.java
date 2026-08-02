package com.drillnotebook.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Production model-download transport: pinned HTTPS only.
 *
 * <p>Every request URL and every redirect hop must be HTTPS and its host must
 * be on the Canonical Contracts allowlist; anything else fails with
 * {@code UNTRUSTED_REDIRECT}. Redirects are followed manually so each hop is
 * validated before a connection is opened.
 */
@Component
public class PinnedHttpsTransport implements ModelDownloadTransport {

    /**
     * Canonical Contracts redirect allowlist (suffix-based).
     * HuggingFace may redirect to regional CDN hosts such as
     * {@code us.aws.cdn.hf.co}, {@code cdn-lfs-us-1.huggingface.co}, etc.
     * Suffix matching covers all official HF infrastructure while still
     * rejecting arbitrary third-party hosts.
     */
    static final Set<String> ALLOWED_HOST_SUFFIXES = Set.of(
            "huggingface.co",
            "hf.co");

    private static boolean isAllowedHost(String host) {
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Resolve HTTP proxy from environment (HTTPS_PROXY / HTTP_PROXY). */
    private static Proxy resolveProxy() {
        String proxyUrl = System.getenv("HTTPS_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("https_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("HTTP_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("http_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) return Proxy.NO_PROXY;
        try {
            URI uri = URI.create(proxyUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port));
        } catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    @Override
    public Response get(URI uri, Map<String, String> headers) throws IOException {
        URI current = uri;
        Proxy proxy = resolveProxy();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validate(current);
            HttpURLConnection connection =
                    (HttpURLConnection) current.toURL().openConnection(proxy);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without Location from " + current.getHost());
                }
                current = current.resolve(location);
                continue;
            }
            return wrap(connection, status);
        }
        throw new IOException("Too many redirects for " + uri.getHost());
    }

    private static void validate(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("UNTRUSTED_REDIRECT: non-HTTPS URL " + uri.getScheme() + "://" + uri.getHost());
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!isAllowedHost(host)) {
            throw new IOException("UNTRUSTED_REDIRECT: host not allowlisted: " + host);
        }
    }

    private static Response wrap(HttpURLConnection connection, int status) {
        return new Response() {
            @Override
            public int status() { return status; }

            @Override
            public String header(String name) { return connection.getHeaderField(name); }

            @Override
            public InputStream body() throws RuntimeException {
                try {
                    InputStream stream = status >= 400
                            ? connection.getErrorStream() : connection.getInputStream();
                    return stream == null ? InputStream.nullInputStream() : stream;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void close() {
                connection.disconnect();
            }
        };
    }
}
