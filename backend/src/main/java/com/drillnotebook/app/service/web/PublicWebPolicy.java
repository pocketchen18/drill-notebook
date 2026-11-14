package com.drillnotebook.app.service.web;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Validates DNS at the connection itself, including every redirect. No browser or local credentials. */
@Component
public class PublicWebPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] lookup(String host) throws UnknownHostException; }
    private final Resolver resolver;
    private final ThreadPoolExecutor dns = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                var thread = new Thread(task, "web-public-dns"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public PublicWebPolicy() { this(InetAddress::getAllByName); }
    public PublicWebPolicy(Resolver resolver) { this.resolver = resolver; }

    public URI parse(String value) {
        try {
            if (value == null || value.length() > 2048 || value.chars().anyMatch(ch -> ch <= 32 || ch == 127 || ch == '\\')) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!List.of("http", "https").contains(scheme) || host == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443 || uri.getRawAuthority().contains("%")) {
                throw new IllegalArgumentException();
            }
            host = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".home") || host.endsWith(".lan") || !host.contains(".") && !host.contains(":")) {
                throw new IllegalArgumentException();
            }
            if (host.contains(":") || host.matches("[0-9.]+")) {
                if (!host.contains(":") && (!host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))) {
                    throw new IllegalArgumentException(); // no abbreviated or ambiguous octal-looking IPv4 literals
                }
                InetAddress address = InetAddress.getByName(host.replace("[", "").replace("]", ""));
                if (!isPublic(address) || host.contains(":") && address.getAddress().length == 4) throw new IllegalArgumentException();
            }
            if (host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){1,3}")) {
                if (host.toLowerCase(Locale.ROOT).contains("0x")) throw new IllegalArgumentException();
            }
            // Drop fragments; queries are preserved only in the model's source result, never in activity metadata.
            String raw = uri.toASCIIString();
            return URI.create(raw.split("#", 2)[0]);
        } catch (Exception error) { throw new WebFailure(WebFailure.Code.UNSAFE_URL); }
    }

    public URI validate(String value, WebRequestContext context) {
        URI uri = parse(value); resolve(uri.getHost(), context); return uri;
    }

    public List<InetAddress> resolve(String host, WebRequestContext context) {
        context.check();
        Future<InetAddress[]> pending;
        try { pending = dns.submit(() -> resolver.lookup(host.replace("[", "").replace("]", ""))); }
        catch (RejectedExecutionException error) { throw new WebFailure(WebFailure.Code.NETWORK_ERROR); }
        try {
            InetAddress[] addresses;
            while (true) {
                context.check();
                try { addresses = pending.get(Math.min(100, context.remainingMillis()), TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException waiting) { /* recheck stop/deadline without blocking the turn */ }
            }
            if (addresses == null || addresses.length == 0 || addresses.length > 32) throw new WebFailure(WebFailure.Code.NETWORK_ERROR);
            if (Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) throw new WebFailure(WebFailure.Code.UNSAFE_URL);
            if (host.contains(":") && Arrays.stream(addresses).anyMatch(address -> address.getAddress().length == 4)) {
                throw new WebFailure(WebFailure.Code.UNSAFE_URL); // IPv4-mapped/compatible IPv6 literals
            }
            return List.of(addresses);
        } catch (ExecutionException error) { throw new WebFailure(WebFailure.Code.NETWORK_ERROR); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new WebFailure(WebFailure.Code.CANCELLED); }
        finally { pending.cancel(true); dns.purge(); }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && b >= 64 && b <= 127
                    || a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168
                    || a == 192 && b == 0 && (c == 0 || c == 2) || a == 192 && b == 88 && c == 99
                    || a == 198 && (b == 18 || b == 19) || a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113);
        }
        if (bytes.length != 16 || (a & 0xe0) != 0x20) return false; // global unicast only
        int c = bytes[2] & 255, d = bytes[3] & 255;
        return !(a == 0x20 && b == 1 && (c < 2 || c == 0x0d && d == 0xb8)
                || a == 0x20 && b == 2 || a == 0x3f && (b & 0xf0) == 0xf0); // transition/documentation ranges
    }

    @PreDestroy public void close() { dns.shutdownNow(); }
}
