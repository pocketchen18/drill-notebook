package com.drillnotebook.app.service.web;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

@Component
public class LocalWebFetcher {
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "main", "li", "h1", "h2", "h3", "h4", "pre", "tr", "br");
    private final WebHttpClient http;
    private final PublicWebPolicy policy;
    public LocalWebFetcher(WebHttpClient http, PublicWebPolicy policy) { this.http = http; this.policy = policy; }

    public WebResult fetch(List<String> urls, WebRequestContext context) {
        var pages = new ArrayList<WebResult.Page>();
        WebFailure last = null;
        for (String url : urls) {
            try { pages.add(read(url, context)); }
            catch (WebFailure failure) {
                last = failure;
                if (failure.code() == WebFailure.Code.CANCELLED) throw failure;
                if (failure.code() == WebFailure.Code.UNSAFE_URL) {
                    return new WebResult("UNSAFE_URL", pages, List.of(new WebResult.Attempt(WebResult.Provider.local,
                            "UNSAFE_URL", failure.httpStatus(), null)), true);
                }
            }
        }
        if (pages.isEmpty()) return WebResult.failure(last == null ? new WebFailure(WebFailure.Code.EMPTY_CONTENT) : last, WebResult.Provider.local);
        return new WebResult("OK", pages, List.of(new WebResult.Attempt(WebResult.Provider.local,
                pages.size() == urls.size() ? "OK" : "PARTIAL", last == null ? null : last.httpStatus(), null)), pages.size() < urls.size());
    }

    private WebResult.Page read(String original, WebRequestContext context) {
        URI url = policy.parse(original);
        var visited = new java.util.HashSet<URI>();
        for (int redirects = 0; redirects <= 5; redirects++) {
            context.check();
            if (!visited.add(url)) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
            var reply = http.request("GET", url, Map.of("Accept", "text/html,text/plain,text/markdown;q=0.9"), null, 1_048_576, context);
            if (Set.of(301, 302, 303, 307, 308).contains(reply.status())) {
                if (reply.location() == null || redirects == 5) throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
                URI next;
                try { next = policy.parse(url.resolve(reply.location()).toString()); }
                catch (RuntimeException failure) { throw new WebFailure(WebFailure.Code.UNSAFE_URL); }
                // A redirect to private DNS must be rejected before the follow-up request is made.
                policy.validate(next.toString(), context);
                url = next;
                continue;
            }
            if (reply.status() < 200 || reply.status() >= 300) {
                throw new WebFailure(reply.status() == 401 || reply.status() == 403 ? WebFailure.Code.LOGIN_REQUIRED
                        : WebFailure.Code.UPSTREAM_ERROR, reply.status(), WebHttpClient.retryAt(reply.retryAfter(), System.currentTimeMillis()));
            }
            String type = reply.contentType().split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            WebResult.Page page;
            if (type.equals("text/html") || type.equals("application/xhtml+xml")) {
                page = extractHtml(original, url.toString(), reply.body());
            } else if (Set.of("text/plain", "text/markdown", "text/x-markdown").contains(type)) {
                String text = reply.text().trim();
                if (text.length() < 80) throw new WebFailure(WebFailure.Code.EMPTY_CONTENT);
                page = new WebResult.Page(original, url.getHost(), text, null, url.toString(), false, null);
            } else throw new WebFailure(WebFailure.Code.UNSUPPORTED_CONTENT);
            context.check();
            return page;
        }
        throw new WebFailure(WebFailure.Code.UPSTREAM_ERROR);
    }

    static WebResult.Page extractHtml(String original, String finalUrl, byte[] html) {
        Document document;
        try { document = Jsoup.parse(new ByteArrayInputStream(html), null, finalUrl); }
        catch (java.io.IOException failure) { throw new WebFailure(WebFailure.Code.EMPTY_CONTENT); }
        String title = document.title();
        String lowerTitle = title.toLowerCase(java.util.Locale.ROOT);
        if (lowerTitle.contains("just a moment") || lowerTitle.contains("checking your browser")
                || lowerTitle.contains("access denied") || lowerTitle.contains("verify you are human")) {
            throw new WebFailure(WebFailure.Code.LOGIN_REQUIRED);
        }
        boolean passwordWall = !document.select("input[type=password]").isEmpty();
        document.select("script,style,noscript,nav,header,footer,aside,form,svg,iframe,button,[hidden],[aria-hidden=true]").remove();
        Element content = document.select("article,main,[role=main]").stream()
                .max(java.util.Comparator.comparingInt(element -> element.text().length())).orElse(document.body());
        if (content == null) throw new WebFailure(WebFailure.Code.EMPTY_CONTENT);
        String text = readableText(content);
        int linked = content.select("a").stream().mapToInt(element -> element.text().length()).sum();
        if (passwordWall && text.length() < 800) throw new WebFailure(WebFailure.Code.LOGIN_REQUIRED);
        if (text.length() < 80 || linked > text.length() * 0.7) throw new WebFailure(WebFailure.Code.EMPTY_CONTENT);
        String published = document.select("meta[property=article:published_time],meta[name=date],meta[name=pubdate]").stream()
                .map(element -> element.attr("content")).filter(value -> !value.isBlank()).findFirst().orElse(null);
        return new WebResult.Page(original, title.isBlank() ? content.select("h1").text() : title, text, published, finalUrl, false, null);
    }

    private static String readableText(Element root) {
        var out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) out.append(text.getWholeText());
                else if (node instanceof Element element && BLOCKS.contains(element.normalName())) out.append('\n');
            }
            public void tail(Node node, int depth) {
                if (node instanceof Element element && BLOCKS.contains(element.normalName())) out.append('\n');
            }
        }, root);
        return out.toString().replaceAll("[\\t \\x0B\\f\\r]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
