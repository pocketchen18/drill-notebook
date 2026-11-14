package com.drillnotebook.app.service;

import com.drillnotebook.app.model.WebSearchSettings;
import com.drillnotebook.app.service.web.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class WebSearchService {
    public interface NativeExecutor {
        boolean supports(boolean fetch);
        WebResult.Provider provider();
        WebResult execute(boolean fetch, String query, List<String> urls, int count, WebRequestContext context);
    }
    public record TurnContext(long remainingMillis, NativeExecutor nativeExecutor) { }
    private record CooldownKey(WebResult.Provider provider, boolean fetch) { }
    private final Map<CooldownKey, Long> cooldowns = new ConcurrentHashMap<>();
    private final WebSearchSettingsService settings;
    private final WebProviders providers;
    private final LocalWebFetcher local;
    private final PublicWebPolicy policy;

    public WebSearchService(WebSearchSettingsService settings, WebProviders providers, LocalWebFetcher local, PublicWebPolicy policy) {
        this.settings = settings; this.providers = providers; this.local = local; this.policy = policy;
    }

    public Map<String, Object> execute(boolean fetch, String query, List<String> urls, int count, int maxCharacters,
                                       int offset, AiService.ToolInvocation invocation) {
        var turn = invocation == null ? null : invocation.webContext();
        var context = new WebRequestContext(turn == null ? 20_000 : turn.remainingMillis(), invocation == null ? null : invocation.handle());
        try {
            WebResult result = run(settings.read().values(), fetch, query, urls, count, context, turn == null ? null : turn.nativeExecutor());
            return result.payload(fetch, maxCharacters, offset);
        } catch (WebFailure failure) {
            return WebResult.failure(failure).payload(fetch, maxCharacters, offset);
        } catch (RuntimeException failure) {
            return WebResult.failure(new WebFailure(WebFailure.Code.UPSTREAM_ERROR)).payload(fetch, maxCharacters, offset);
        }
    }

    public Map<String, Object> test(WebSearchSettings draft, boolean fetch) {
        WebResult result;
        try { result = run(draft, fetch, "Drill Notebook GitHub", fetch ? List.of("https://example.com/") : List.of(), 2,
                new WebRequestContext(20_000, null), null); }
        catch (WebFailure failure) { result = WebResult.failure(failure); }
        catch (RuntimeException failure) { result = WebResult.failure(new WebFailure(WebFailure.Code.UPSTREAM_ERROR)); }
        var safe = new LinkedHashMap<String, Object>();
        safe.put("capability", fetch ? "fetch" : "search"); safe.put("status", result.status()); safe.put("count", result.pages().size());
        var payload = result.payload(fetch, 100, 0); safe.put("webRoute", payload.get("webRoute"));
        if (payload.containsKey("webProvider")) safe.put("webProvider", payload.get("webProvider"));
        return safe;
    }

    private WebResult run(WebSearchSettings config, boolean fetch, String query, List<String> rawUrls, int count,
                          WebRequestContext context, NativeExecutor nativeExecutor) {
        context.check();
        List<String> urls = fetch ? rawUrls.stream().map(url -> policy.validate(url, context).toString()).distinct().toList() : List.of();
        if (config.preferNative() && nativeExecutor != null && nativeExecutor.supports(fetch)) {
            try { return nativeExecutor.execute(fetch, query, urls, count, context.child(10_000)); }
            catch (WebFailure failure) { return WebResult.failure(failure, nativeExecutor.provider()); }
            catch (RuntimeException failure) { return WebResult.failure(new WebFailure(WebFailure.Code.UPSTREAM_ERROR), nativeExecutor.provider()); }
        }
        var selected = fetch ? config.fetchProvider() : config.searchProvider();
        if (selected != WebSearchSettings.Provider.auto) {
            WebResult.Provider provider = selected == WebSearchSettings.Provider.tavily ? WebResult.Provider.tavily : WebResult.Provider.tinyfish;
            String key;
            try { key = settings.key(config, selected); }
            catch (RuntimeException failure) { return WebResult.failure(new WebFailure(WebFailure.Code.KEY_UNAVAILABLE), provider); }
            try { return fetch ? providers.fetch(provider, urls, key, context.child(10_000)) : providers.search(provider, query, count, key, context.child(10_000)); }
            catch (WebFailure failure) { return WebResult.failure(failure, provider); } // explicit provider choice is never silently replaced
            catch (RuntimeException failure) { return WebResult.failure(new WebFailure(WebFailure.Code.UPSTREAM_ERROR), provider); }
        }
        return fetch ? autoFetch(urls, context) : autoSearch(query, count, context);
    }

    private WebResult autoSearch(String query, int count, WebRequestContext context) {
        var previous = new ArrayList<WebResult.Attempt>();
        WebResult last = null;
        for (var provider : List.of(WebResult.Provider.exa, WebResult.Provider.tavily)) {
            last = anonymous(provider, false, query, List.of(), count, context);
            if (!last.pages().isEmpty() || terminal(last.status())) return last.prepend(previous);
            previous.addAll(last.attempts());
        }
        return new WebResult(last == null ? "UNAVAILABLE" : last.status(), List.of(), previous, true);
    }

    private WebResult autoFetch(List<String> urls, WebRequestContext context) {
        WebResult initial = local.fetch(urls, context.child(10_000));
        if (terminal(initial.status())) return initial;
        var pages = new LinkedHashMap<String, WebResult.Page>(); initial.pages().forEach(page -> pages.put(page.url(), page));
        var route = new ArrayList<>(initial.attempts());
        String last = initial.status();
        for (var provider : List.of(WebResult.Provider.exa, WebResult.Provider.tavily)) {
            List<String> missing = urls.stream().filter(url -> !pages.containsKey(url)).toList();
            if (missing.isEmpty()) break;
            WebResult result = anonymous(provider, true, "", missing, missing.size(), context);
            result.pages().forEach(page -> pages.put(page.url(), page)); route.addAll(result.attempts()); last = result.status();
            if (terminal(result.status())) break;
        }
        return new WebResult(pages.isEmpty() ? last : "OK", urls.stream().filter(pages::containsKey).map(pages::get).toList(), route, pages.size() < urls.size());
    }

    private WebResult anonymous(WebResult.Provider provider, boolean fetch, String query, List<String> urls, int count, WebRequestContext context) {
        CooldownKey key = new CooldownKey(provider, fetch);
        Long until = cooldowns.get(key);
        long now = System.currentTimeMillis();
        if (until != null && until > now) return WebResult.failure(new WebFailure(WebFailure.Code.COOLDOWN, null, until), provider);
        try {
            context.check();
            return fetch ? providers.fetch(provider, urls, null, context.child(10_000)) : providers.search(provider, query, count, null, context.child(10_000));
        } catch (WebFailure failure) {
            if (Set.of(WebFailure.Code.RATE_LIMITED, WebFailure.Code.QUOTA_EXHAUSTED, WebFailure.Code.AUTH_REQUIRED).contains(failure.code())) {
                cooldowns.put(key, failure.retryAt() == null ? now + 60_000 : failure.retryAt());
            }
            return WebResult.failure(failure, provider);
        } catch (RuntimeException failure) {
            return WebResult.failure(new WebFailure(WebFailure.Code.UPSTREAM_ERROR), provider);
        }
    }
    private static boolean terminal(String status) { return Set.of("CANCELLED", "INVALID_ARGUMENT", "UNSAFE_URL", "LIMIT_REACHED").contains(status); }
}
