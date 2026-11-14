package com.drillnotebook.app.service.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Stateless HTTP transport: no cookie jar, disk cache, proxy credentials or automatic redirects/retries. */
@Component
public class WebHttpClient {
    public record Reply(int status, String contentType, byte[] body, String location, String retryAfter, String mcpSession) {
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }
    private final PublicWebPolicy policy;
    private final OkHttpClient client;

    public WebHttpClient(PublicWebPolicy policy) {
        this.policy = policy;
        client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
    }

    public Reply request(String method, URI url, Map<String, String> headers, String body, int byteLimit, WebRequestContext context) {
        context.check();
        URI checked = policy.parse(url.toString());
        OkHttpClient scoped = client.newBuilder().callTimeout(Math.max(1, context.remainingMillis()), TimeUnit.MILLISECONDS)
                .dns(host -> {
                    var addresses = policy.resolve(host, context);
                    if ("mcp.exa.ai".equalsIgnoreCase(host)) {
                        addresses = addresses.stream().filter(address -> address.getAddress().length == 4).toList();
                        if (addresses.isEmpty()) throw new WebFailure(WebFailure.Code.AUTH_REQUIRED);
                    }
                    return addresses;
                }).build();
        Request.Builder builder = new Request.Builder().url(checked.toString())
                .header("User-Agent", "DrillNotebook-Web/0.8.2");
        headers.forEach(builder::header);
        builder.method(method, body == null ? null : RequestBody.create(body, MediaType.get("application/json; charset=utf-8")));
        Call call = scoped.newCall(builder.build());
        if (context.handle() != null) context.handle().bindAbortHook(call::cancel);
        try (Response response = call.execute()) {
            context.check();
            ResponseBody payload = response.body();
            if (payload == null) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
            // OkHttp inflates gzip while reading; the bound applies to decompressed bytes too.
            if (payload.contentLength() > byteLimit) throw new WebFailure(WebFailure.Code.CONTENT_TOO_LARGE);
            var bytes = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            try (var input = payload.byteStream()) {
                int count;
                while ((count = input.read(block)) != -1) {
                    context.check();
                    if (bytes.size() + count > byteLimit) throw new WebFailure(WebFailure.Code.CONTENT_TOO_LARGE);
                    bytes.write(block, 0, count);
                }
            }
            return new Reply(response.code(), response.header("Content-Type", ""), bytes.toByteArray(),
                    response.header("Location"), response.header("Retry-After"), response.header("Mcp-Session-Id"));
        } catch (IOException error) {
            context.check();
            throw new WebFailure(error instanceof java.io.InterruptedIOException ? WebFailure.Code.TIME_LIMIT : WebFailure.Code.NETWORK_ERROR);
        } finally {
            if (context.handle() != null) context.handle().bindAbortHook(null);
        }
    }

    public static Long retryAt(String value, long now) {
        if (value == null || value.length() > 100) return null;
        try {
            long milliseconds;
            try { milliseconds = Math.multiplyExact(Long.parseLong(value.trim()), 1000); }
            catch (NumberFormatException error) {
                milliseconds = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now;
            }
            return now + Math.max(0, Math.min(86_400_000L, milliseconds));
        } catch (RuntimeException error) { return null; }
    }
}
