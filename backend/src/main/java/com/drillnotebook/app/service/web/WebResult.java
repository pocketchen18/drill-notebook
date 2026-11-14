package com.drillnotebook.app.service.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded content lives in tool results; activity metadata includes only the safe route fields. */
public record WebResult(String status, List<Page> pages, List<Attempt> attempts, boolean incomplete) {
    public enum Provider { exa, tavily, tinyfish, local, anthropic, openai }
    public record Page(String url, String title, String content, String publishedAt, String finalUrl, boolean truncated, Integer nextOffset) {
        public Page(String url, String title, String content, String publishedAt) { this(url, title, content, publishedAt, null, false, null); }
    }
    public record Attempt(Provider provider, String status, Integer httpStatus, Long retryAt) { }
    public WebResult { pages = List.copyOf(pages); attempts = List.copyOf(attempts); }
    public static WebResult success(List<Page> pages, Provider provider) {
        return new WebResult(pages.isEmpty() ? "NO_MATCH" : "OK", pages, List.of(new Attempt(provider, pages.isEmpty() ? "NO_MATCH" : "OK", null, null)), false);
    }
    public static WebResult failure(WebFailure failure, Provider provider) {
        return new WebResult(failure.code().name(), List.of(), List.of(new Attempt(provider, failure.code().name(), failure.httpStatus(), failure.retryAt())), true);
    }
    public static WebResult failure(WebFailure failure) { return new WebResult(failure.code().name(), List.of(), List.of(), true); }
    public WebResult prepend(List<Attempt> previous) {
        var route = new ArrayList<>(previous); route.addAll(attempts);
        return new WebResult(status, pages, route, incomplete);
    }

    public Map<String, Object> payload(boolean fetch, int maxCharacters, int offset) {
        var rows = new ArrayList<Map<String, Object>>();
        boolean trimmed = incomplete;
        int remainingBytes = 45_000;
        for (Page page : pages.stream().limit(fetch ? 3 : 5).toList()) {
            String content = page.content() == null ? "" : page.content();
            int start = fetch ? Math.min(offset, content.length()) : 0;
            if (start > 0 && start < content.length() && Character.isLowSurrogate(content.charAt(start))) start++;
            int end = Math.min(content.length(), start + (fetch ? maxCharacters : 1200));
            if (end > start && end < content.length() && Character.isLowSurrogate(content.charAt(end))) end--;
            String excerpt = utf8(content.substring(start, end), Math.min(remainingBytes, fetch ? 14_000 : 2400));
            remainingBytes -= excerpt.getBytes(StandardCharsets.UTF_8).length;
            boolean truncated = page.truncated() || end < content.length() || excerpt.length() < end - start;
            trimmed |= truncated;
            var row = new LinkedHashMap<String, Object>();
            row.put("url", page.url()); row.put("title", utf8(page.title() == null ? page.url() : page.title(), 500));
            row.put(fetch ? "content" : "snippet", excerpt); row.put("untrusted", true);
            row.put("truncated", truncated);
            if (fetch && truncated && !excerpt.isEmpty()) row.put("nextOffset", start + excerpt.length());
            if (page.finalUrl() != null && !page.finalUrl().equals(page.url())) row.put("finalUrl", page.finalUrl());
            if (page.publishedAt() != null && !page.publishedAt().isBlank() && !Set.of("N/A", "unknown").contains(page.publishedAt())) {
                row.put("publishedAt", utf8(page.publishedAt(), 100));
            }
            rows.add(row);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", status); payload.put("items", rows); payload.put("incomplete", trimmed);
        var route = new ArrayList<Map<String, Object>>();
        for (Attempt attempt : attempts.stream().limit(4).toList()) {
            var safe = new LinkedHashMap<String, Object>();
            safe.put("provider", attempt.provider().name()); safe.put("status", attempt.status());
            if (attempt.httpStatus() != null) safe.put("httpStatus", attempt.httpStatus());
            if (attempt.retryAt() != null) safe.put("retryAt", Instant.ofEpochMilli(attempt.retryAt()).toString());
            route.add(safe);
        }
        payload.put("webRoute", route);
        if (!attempts.isEmpty()) payload.put("webProvider", attempts.get(attempts.size() - 1).provider().name());
        return payload;
    }

    static String utf8(String text, int byteLimit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= byteLimit) return text;
        int end = Math.max(0, byteLimit);
        while (end > 0 && end < bytes.length && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
