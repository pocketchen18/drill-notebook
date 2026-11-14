package com.drillnotebook.app.service.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class WebProvidersTest {
    private final ObjectMapper json = new ObjectMapper();
    private WebHttpClient http;
    private PublicWebPolicy policy;
    private WebProviders providers;
    private final List<Map<String,Object>> sent = new ArrayList<>();
    @BeforeEach void setup() {
        http=mock(WebHttpClient.class); policy=new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        providers=new WebProviders(http,policy);
    }
    @AfterEach void cleanup() { policy.close(); }
    private WebHttpClient.Reply reply(int status, Object body, String retry) throws Exception {
        return new WebHttpClient.Reply(status,"application/json",json.writeValueAsBytes(body),null,retry,null);
    }
    private WebRequestContext context() { return new WebRequestContext(10_000,null); }

    @Test void exaUsesRealMcpHandshakeAndLabelledSearchResultsIncludingSseRpcIds() throws Exception {
        when(http.request(eq("POST"),any(),anyMap(),anyString(),anyInt(),any())).thenAnswer(call -> {
            Map<String,Object> body=json.readValue(call.getArgument(3,String.class),Map.class); sent.add(body);
            String method=(String)body.get("method");
            if (method.equals("initialize")) return reply(200,Map.of("jsonrpc","2.0","id",1,"result",Map.of("protocolVersion","2025-03-26")),null);
            if (method.equals("notifications/initialized")) return new WebHttpClient.Reply(202,"application/json",new byte[0],null,null,null);
            String actual="Title: 官方文档\nURL: https://docs.example/guide\nPublished: N/A\nAuthor: N/A\nHighlights:\n真实资料片段。\n\n---\n\nTitle: 发布说明\nURL: https://release.example/v1\nPublished: 2026-11-14\nAuthor: N/A\nHighlights:\n版本发布的真实内容。";
            String event=json.writeValueAsString(Map.of("jsonrpc","2.0","id",2,"result",Map.of("content",List.of(Map.of("type","text","text",actual)))));
            return new WebHttpClient.Reply(200,"text/event-stream",("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: "+event+"\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n").getBytes(StandardCharsets.UTF_8),null,null,null);
        });
        var result=providers.search(WebResult.Provider.exa,"公开查询",5,null,context());
        assertEquals("OK",result.status()); assertEquals(2,result.pages().size());
        assertEquals("官方文档",result.pages().get(0).title()); assertEquals("真实资料片段。",result.pages().get(0).content());
        assertNull(result.pages().get(0).publishedAt()); assertEquals("2026-11-14",result.pages().get(1).publishedAt());
        assertEquals(List.of("initialize","notifications/initialized","tools/call"),sent.stream().map(row -> row.get("method")).toList());
        var params=json.valueToTree(sent.get(2)).path("params");
        assertEquals("web_search_exa",params.path("name").asText()); assertEquals("公开查询",params.path("arguments").path("query").asText());
        assertFalse(params.path("arguments").path("objective").asText().isBlank());
        assertFalse(json.writeValueAsString(sent).contains("agent_run"));
    }

    @Test void exaFetchReadsTheActualHeadingUrlFormatAndBusinessErrorsAreFailures() throws Exception {
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenAnswer(call -> {
            var body=json.readTree(call.getArgument(3,String.class));
            if (body.path("method").asText().equals("initialize")) return reply(200,Map.of("jsonrpc","2.0","id",1,"result",Map.of("protocolVersion","2025-03-26")),null);
            if (body.path("method").asText().equals("notifications/initialized")) return new WebHttpClient.Reply(202,"",new byte[0],null,null,null);
            return reply(200,Map.of("jsonrpc","2.0","id",2,"result",Map.of("content",List.of(Map.of("type","text","text","# 正文标题\nURL: https://docs.example/a\n\n这是实际抓取正文。\n# 内容中的标题\n保留这段正文。")))),null);
        });
        var result=providers.fetch(WebResult.Provider.exa,List.of("https://docs.example/a"),null,context());
        assertEquals("正文标题",result.pages().get(0).title()); assertTrue(result.pages().get(0).content().contains("内容中的标题"));
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(reply(200,
                Map.of("jsonrpc","2.0","id",1,"error",Map.of("code",-32000,"message","You've hit Exa's free MCP rate limit. Create your own Exa API key. fixture-api-key")),"60"));
        var failure=assertThrows(WebFailure.class,() -> providers.search(WebResult.Provider.exa,"public",2,null,context()));
        assertEquals(WebFailure.Code.RATE_LIMITED,failure.code()); assertFalse(failure.getMessage().contains("fixture-api-key")); assertNotNull(failure.retryAt());
    }

    @Test void tavilyKeylessAndUserKeyAuthenticationNeverMixAndResultFieldsAreReal() throws Exception {
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(reply(200,Map.of("results",List.of(Map.of(
                "url","https://docs.example/a","title","文档","content","实际搜索片段"))),null));
        assertEquals("实际搜索片段",providers.search(WebResult.Provider.tavily,"公开词",3,null,context()).pages().get(0).content());
        verify(http).request(eq("POST"),eq(URI.create("https://api.tavily.com/search")),argThat(headers -> headers.containsKey("X-Tavily-Access-Mode") && !headers.containsKey("Authorization")),
                argThat(body -> body.contains("\"max_results\":3") && body.contains("\"include_raw_content\":false")),anyInt(),any());
        providers.search(WebResult.Provider.tavily,"公开词",3,"fixture-key",context());
        verify(http).request(eq("POST"),any(),argThat(headers -> "Bearer fixture-key".equals(headers.get("Authorization")) && !headers.containsKey("X-Tavily-Access-Mode")),anyString(),anyInt(),any());
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(reply(429,Map.of("error",Map.of("code","daily_cap_reached","retry_after_seconds",600)),null));
        var failure=assertThrows(WebFailure.class,() -> providers.search(WebResult.Provider.tavily,"公开词",3,null,context()));
        assertEquals(WebFailure.Code.QUOTA_EXHAUSTED,failure.code()); assertTrue(failure.retryAt()>System.currentTimeMillis());
    }

    @Test void tinyfishUsesGetSearchAndPostFetchWithOneUserKeyAndPreservesPartialPages() throws Exception {
        when(http.request(eq("GET"),any(),anyMap(),isNull(),anyInt(),any())).thenReturn(reply(200,Map.of("results",List.of(Map.of(
                "url","https://docs.example/a","title","来源","snippet","真实片段","date","2026-11-14"))),null));
        var search=providers.search(WebResult.Provider.tinyfish,"中文 空格 & query",5,"fixture-key",context());
        assertEquals("真实片段",search.pages().get(0).content());
        verify(http).request(eq("GET"),argThat(url -> url.toString().startsWith("https://api.search.tinyfish.ai/") && url.toString().contains("query=")),
                argThat(headers -> "fixture-key".equals(headers.get("X-API-Key"))),isNull(),anyInt(),any());
        when(http.request(eq("POST"),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(reply(200,Map.of("results",List.of(Map.of(
                "url","https://docs.example/a","final_url","https://docs.example/final","title","正文","text","正文内容")),
                "errors",List.of(Map.of("url","https://docs.example/b","error","timeout"))),null));
        var fetch=providers.fetch(WebResult.Provider.tinyfish,List.of("https://docs.example/a","https://docs.example/b"),"fixture-key",context());
        assertEquals("OK",fetch.status()); assertTrue(fetch.incomplete()); assertEquals(1,fetch.pages().size());
        assertEquals("https://docs.example/final",fetch.pages().get(0).finalUrl());
        verify(http).request(eq("POST"),eq(URI.create("https://api.fetch.tinyfish.ai")),argThat(headers -> "fixture-key".equals(headers.get("X-API-Key"))),
                argThat(body -> body.contains("\"format\":\"markdown\"") && body.contains("per_url_timeout_ms")),anyInt(),any());
    }

    @Test void http200ErrorsAndPerUrlFailuresCannotLookLikeEmptySuccessfulSearches() throws Exception {
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(reply(200,Map.of("error",Map.of("code","insufficient_credits")),null));
        assertEquals(WebFailure.Code.QUOTA_EXHAUSTED,assertThrows(WebFailure.class,() -> providers.search(WebResult.Provider.tavily,"公开",3,"fixture-key",context())).code());
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(reply(200,Map.of("results",List.of(),"errors",List.of(Map.of("url","https://docs.example/a","error","login_required"))),null));
        assertEquals(WebFailure.Code.LOGIN_REQUIRED,assertThrows(WebFailure.class,() -> providers.fetch(WebResult.Provider.tinyfish,List.of("https://docs.example/a"),"fixture-key",context())).code());
    }

    @Test void exaUnlabelledAdvisoryTextCannotMasqueradeAsFetchedContent() throws Exception {
        when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenAnswer(call -> {
            var body=json.readTree(call.getArgument(3,String.class));
            if (body.path("method").asText().equals("initialize")) return reply(200,Map.of("jsonrpc","2.0","id",1,"result",Map.of("protocolVersion","2025-03-26")),null);
            if (body.path("method").asText().equals("notifications/initialized")) return new WebHttpClient.Reply(202,"",new byte[0],null,null,null);
            return reply(200,Map.of("jsonrpc","2.0","id",2,"result",Map.of("content",List.of(Map.of("type","text","text","The requested page could not be retrieved. Please sign in to continue.")))),null);
        });
        assertEquals(WebFailure.Code.EMPTY_CONTENT,assertThrows(WebFailure.class,() -> providers.fetch(WebResult.Provider.exa,List.of("https://docs.example/a"),null,context())).code());
    }
}
