package com.drillnotebook.app.service.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;

/** Fixed official endpoints only. Adapters send the necessary query/URLs, never the assistant context. */
@Component
public class WebProviders {
    private static final URI EXA = URI.create("https://mcp.exa.ai/mcp?tools=web_search_exa,web_fetch_exa");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SEARCH_HEAD = Pattern.compile("(?m)^Title: ([^\\r\\n]+)\\r?\\nURL: (https?://[^\\s]+)\\r?\\n");
    private static final Pattern FETCH_HEAD = Pattern.compile("(?m)^# ([^\\r\\n]+)\\r?\\nURL: (https?://[^\\s]+)\\r?\\n");
    private final WebHttpClient http;
    private final PublicWebPolicy policy;

    public WebProviders(WebHttpClient http, PublicWebPolicy policy) { this.http = http; this.policy = policy; }

    public WebResult search(WebResult.Provider provider, String query, int count, String key, WebRequestContext context) {
        if (provider == WebResult.Provider.exa) return exa(false, query, List.of(), count, context);
        WebHttpClient.Reply reply;
        if (provider == WebResult.Provider.tavily) {
            reply = http.request("POST", URI.create("https://api.tavily.com/search"), tavilyAuth(key), json(Map.of(
                    "query", query, "max_results", count, "search_depth", "basic", "include_answer", false, "include_raw_content", false)), 2_097_152, context);
        } else {
            String target = Objects.requireNonNull(HttpUrl.parse("https://api.search.tinyfish.ai")).newBuilder()
                    .addQueryParameter("query", query).build().toString();
            reply = http.request("GET", URI.create(target), Map.of("X-API-Key", key, "Accept", "application/json"), null, 2_097_152, context);
        }
        JsonNode root = response(reply);
        var pages = rows(root.path("results"), false, List.of(), count, context);
        if (!root.path("results").isArray()) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
        return WebResult.success(pages, provider);
    }

    public WebResult fetch(WebResult.Provider provider, List<String> urls, String key, WebRequestContext context) {
        if (provider == WebResult.Provider.exa) return exa(true, "", urls, 3, context);
        WebHttpClient.Reply reply;
        if (provider == WebResult.Provider.tavily) {
            reply = http.request("POST", URI.create("https://api.tavily.com/extract"), tavilyAuth(key), json(Map.of(
                    "urls", urls, "extract_depth", "basic", "format", "markdown", "include_images", false)), 2_097_152, context);
        } else {
            reply = http.request("POST", URI.create("https://api.fetch.tinyfish.ai"), Map.of("X-API-Key", key, "Accept", "application/json"),
                    json(Map.of("urls", urls, "format", "markdown", "ttl", 0, "per_url_timeout_ms", Math.min(8000, context.remainingMillis()))), 2_097_152, context);
        }
        JsonNode root = response(reply);
        var pages = rows(root.path("results"), true, urls, 3, context);
        JsonNode errors = root.path(provider == WebResult.Provider.tavily ? "failed_results" : "errors");
        if (pages.isEmpty()) throw providerError(errors.isArray() && !errors.isEmpty() ? errors.get(0).path("error").asText("") : "empty_content", reply.status(), null);
        return new WebResult("OK", pages, List.of(new WebResult.Attempt(provider, pages.size() < urls.size() ? "PARTIAL" : "OK", null, null)), pages.size() < urls.size());
    }

    private Map<String, String> tavilyAuth(String key) {
        return key == null ? Map.of("X-Tavily-Access-Mode", "keyless", "Accept", "application/json")
                : Map.of("Authorization", "Bearer " + key, "Accept", "application/json");
    }

    private WebResult exa(boolean fetch, String query, List<String> urls, int count, WebRequestContext context) {
        // A session is scoped to this attempt; no handshake cache or persistent session tokens.
        String version = "2025-03-26";
        Map<String, String> headers = new LinkedHashMap<>(Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", version));
        var initialized = http.request("POST", EXA, headers, json(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                "protocolVersion", version, "capabilities", Map.of(), "clientInfo", Map.of("name", "drill-notebook", "version", "0.8.2")))), 2_097_152, context);
        JsonNode init = mcp(initialized, 1);
        String negotiated = init.path("result").path("protocolVersion").asText("");
        if (!Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(negotiated)) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
        headers.put("MCP-Protocol-Version", negotiated);
        if (initialized.mcpSession() != null) {
            if (!initialized.mcpSession().matches("[A-Za-z0-9._~-]{1,256}")) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
            headers.put("Mcp-Session-Id", initialized.mcpSession());
        }
        var ready = http.request("POST", EXA, headers, json(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")), 65_536, context);
        if (ready.status() < 200 || ready.status() >= 300) response(ready);
        Map<String, Object> arguments = fetch ? Map.of("urls", urls, "maxCharacters", 36_000)
                : Map.of("query", query, "objective", "Find reliable webpages matching this query. Return source titles, URLs and relevant passages.", "numResults", count);
        var reply = http.request("POST", EXA, headers, json(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", Map.of(
                "name", fetch ? "web_fetch_exa" : "web_search_exa", "arguments", arguments))), 2_097_152, context);
        JsonNode root = mcp(reply, 2);
        JsonNode result = root.path("result");
        if (result.path("isError").asBoolean(false)) throw providerError(contentText(result), reply.status(), WebHttpClient.retryAt(reply.retryAfter(), System.currentTimeMillis()));
        JsonNode structured = result.path("structuredContent");
        List<WebResult.Page> pages = structured.path("results").isArray()
                ? rows(structured.path("results"), fetch, urls, count, context)
                : exaText(contentText(result), fetch, urls, count, context);
        if (pages.isEmpty()) throw new WebFailure(WebFailure.Code.EMPTY_CONTENT);
        return new WebResult("OK", pages, List.of(new WebResult.Attempt(WebResult.Provider.exa, fetch && pages.size() < urls.size() ? "PARTIAL" : "OK", null, null)), fetch && pages.size() < urls.size());
    }

    private JsonNode mcp(WebHttpClient.Reply reply, int id) {
        if (reply.status() < 200 || reply.status() >= 300) return response(reply);
        String text = reply.text().trim();
        JsonNode root = null;
        try {
            if (text.startsWith("{")) root = JSON.readTree(text);
            else {
                for (String event : text.split("\\r?\\n\\r?\\n")) {
                    var data = new StringBuilder();
                    for (String line : event.split("\\r?\\n")) if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
                    if (!data.isEmpty() && !data.toString().trim().equals("[DONE]")) {
                        JsonNode candidate = JSON.readTree(data.toString());
                        if (candidate.path("id").asInt(-1) == id) root = candidate;
                    }
                }
            }
        } catch (Exception ignored) { throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR); }
        if (root == null || !"2.0".equals(root.path("jsonrpc").asText()) || root.path("id").asInt(-1) != id) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
        if (root.hasNonNull("error")) throw providerError(root.path("error").path("message").asText(""), reply.status(), WebHttpClient.retryAt(reply.retryAfter(), System.currentTimeMillis()));
        if (!root.path("result").isObject()) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
        return root;
    }

    private List<WebResult.Page> exaText(String text, boolean fetch, List<String> urls, int count, WebRequestContext context) {
        // Exa's current basic MCP tools return labelled text, not a fictional JSON search schema.
        var matcher = (fetch ? FETCH_HEAD : SEARCH_HEAD).matcher(text);
        var heads = new ArrayList<String[]>();
        var starts = new ArrayList<Integer>();
        var ends = new ArrayList<Integer>();
        while (matcher.find() && heads.size() < 20) { heads.add(new String[]{matcher.group(1), matcher.group(2)}); starts.add(matcher.start()); ends.add(matcher.end()); }
        var pages = new ArrayList<WebResult.Page>();
        for (int i = 0; i < heads.size() && pages.size() < count; i++) {
            String body = text.substring(ends.get(i), i + 1 < heads.size() ? starts.get(i + 1) : text.length());
            String date = null;
            if (!fetch) {
                var published = Pattern.compile("(?m)^Published(?: Date)?: ([^\\r\\n]+)").matcher(body);
                if (published.find() && !published.group(1).equals("N/A")) date = published.group(1);
                body = body.replaceFirst("(?s)^(?:Published[^\\n]*\\n)?(?:Author[^\\n]*\\n)?(?:Highlights:|Text:)?\\s*", "");
            }
            body = body.replaceFirst("\\n\\n---\\s*$", "");
            addPage(pages, heads.get(i)[1], heads.get(i)[0], body.trim(), date, null, fetch, urls, context);
        }
        return pages;
    }

    private List<WebResult.Page> rows(JsonNode rows, boolean fetch, List<String> urls, int count, WebRequestContext context) {
        var pages = new ArrayList<WebResult.Page>();
        if (!rows.isArray()) return pages;
        for (JsonNode row : rows) {
            if (pages.size() >= count) break;
            String content = fetch ? row.path("raw_content").asText(row.path("text").asText(row.path("content").asText("")))
                    : row.path("content").asText(row.path("snippet").asText(""));
            addPage(pages, row.path("url").asText(""), row.path("title").asText(""), content,
                    row.path("published_date").asText(row.path("date").asText(null)), row.path("final_url").asText(null), fetch, urls, context);
        }
        return pages;
    }

    private void addPage(List<WebResult.Page> pages, String url, String title, String text, String date, String finalUrl,
                         boolean fetch, List<String> urls, WebRequestContext context) {
        if (text == null || text.isBlank()) return;
        try {
            String safeUrl = policy.parse(url).toString();
            if (fetch && !urls.contains(safeUrl) || pages.stream().anyMatch(page -> page.url().equals(safeUrl))) return;
            if (fetch && finalUrl != null) policy.validate(finalUrl, context);
            pages.add(new WebResult.Page(safeUrl, title, text, date, finalUrl, false, null));
        } catch (WebFailure failure) {
            if (fetch || failure.code() != WebFailure.Code.UNSAFE_URL) throw failure;
        }
    }

    private JsonNode response(WebHttpClient.Reply reply) {
        JsonNode root;
        try { root = JSON.readTree(reply.text()); }
        catch (Exception error) { root = JSON.createObjectNode(); }
        if (root == null) root = JSON.createObjectNode();
        Long retry = WebHttpClient.retryAt(reply.retryAfter(), System.currentTimeMillis());
        if (retry == null && root.path("error").path("retry_after_seconds").isNumber()) {
            retry = WebHttpClient.retryAt(root.path("error").path("retry_after_seconds").asText(), System.currentTimeMillis());
        }
        if (reply.status() < 200 || reply.status() >= 300 || root.hasNonNull("error") || root.path("success").isBoolean() && !root.path("success").booleanValue()) {
            String code = root.path("error").isTextual() ? root.path("error").asText()
                    : root.path("error").path("code").asText(root.path("code").asText(""));
            throw providerError(code, reply.status(), retry);
        }
        return root;
    }

    private static String contentText(JsonNode result) {
        var text = new StringBuilder();
        for (JsonNode block : result.path("content")) if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText()).append('\n');
        return text.toString().trim();
    }

    static WebFailure providerError(String safeInput, int status, Long retryAt) {
        String code = safeInput == null ? "" : safeInput.toLowerCase(Locale.ROOT);
        // A rate-limit response may suggest adding an API key; that text does not turn it into an authentication failure.
        WebFailure.Code reason = status == 402 || code.contains("daily_cap") || code.contains("quota") || code.contains("insufficient_credits") || code.contains("allowance_exhausted")
                ? WebFailure.Code.QUOTA_EXHAUSTED
                : status == 429 || code.contains("rate limit") || code.contains("rate_limit") ? WebFailure.Code.RATE_LIMITED
                : status == 401 || status == 403 || code.contains("api key") || code.contains("unauthorized") || code.contains("authentication")
                ? WebFailure.Code.AUTH_REQUIRED
                : status == 400 || status == 422 ? WebFailure.Code.INVALID_ARGUMENT
                : code.contains("invalid_url") || code.contains("invalid_redirect") ? WebFailure.Code.UNSAFE_URL
                : code.contains("login_required") || code.contains("bot_blocked") ? WebFailure.Code.LOGIN_REQUIRED
                : code.contains("timeout") ? WebFailure.Code.TIME_LIMIT
                : code.contains("too_large") ? WebFailure.Code.CONTENT_TOO_LARGE
                : code.contains("empty_content") ? WebFailure.Code.EMPTY_CONTENT : WebFailure.Code.UPSTREAM_ERROR;
        return new WebFailure(reason, status, retryAt);
    }

    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new WebFailure(WebFailure.Code.INVALID_ARGUMENT); }
    }
}
