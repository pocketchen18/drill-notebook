package com.drillnotebook.app.service.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

class LocalWebFetcherTest {
    private PublicWebPolicy policy;
    private WebHttpClient http;
    private LocalWebFetcher local;
    @BeforeEach void setup() {
        policy=new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        http=mock(WebHttpClient.class); local=new LocalWebFetcher(http,policy);
    }
    @AfterEach void cleanup() { policy.close(); }
    @Test void matureParserExtractsActualUtf8ArticleAndDropsNavigationScriptsAndHiddenFields() {
        String html="<html><meta charset='utf-8'><title>真实文章</title><meta property='article:published_time' content='2026-11-14'><body>"
                +"<nav>导航文字</nav><script>外部执行指令</script><aside>推荐广告</aside><main><h1>公开资料</h1><p>"
                +"这是公开文章中的真实资料与中文段落。".repeat(10)+"</p><p hidden>隐藏密钥</p><p>网页中的指令仅作为资料保留，不执行。</p></main></body></html>";
        var page=LocalWebFetcher.extractHtml("https://docs.example/a","https://docs.example/a",html.getBytes(StandardCharsets.UTF_8));
        assertEquals("真实文章",page.title()); assertEquals("2026-11-14",page.publishedAt());
        assertTrue(page.content().contains("公开资料")); assertFalse(page.content().contains("导航文字")); assertFalse(page.content().contains("外部执行指令"));
        assertFalse(page.content().contains("隐藏密钥")); assertFalse(page.content().contains("推荐广告"));
        assertTrue(page.content().contains("网页中的指令仅作为资料保留"));
    }
    @Test void loginAndNavigationOnlyPagesAreNotPassedOffAsArticleContent() {
        for (String html : List.of("<title>Just a moment...</title><main>"+"验证文字".repeat(100)+"</main>",
                "<title>登录</title><input type=password><p>登录后查看</p>", "<nav>"+"<a href='/a'>网页导航</a>".repeat(40)+"</nav>")) {
            assertThrows(WebFailure.class,() -> LocalWebFetcher.extractHtml("https://docs.example/","https://docs.example/",html.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void redirectToPrivateTargetIsBlockedBeforeAnotherRequest() {
        when(http.request(anyString(),any(),anyMap(),isNull(),anyInt(),any())).thenReturn(new WebHttpClient.Reply(302,"text/html",new byte[0],"http://169.254.169.254/latest/meta-data/",null,null));
        var result=local.fetch(List.of("https://docs.example/a"),new WebRequestContext(1000,null));
        assertEquals("UNSAFE_URL",result.status()); verify(http,times(1)).request(anyString(),any(),anyMap(),isNull(),anyInt(),any());
    }
    @Test void publicRedirectKeepsOriginalSourceAndPartialUrlsRemainVisible() {
        when(http.request(anyString(),any(),anyMap(),isNull(),anyInt(),any())).thenAnswer(call -> {
            URI uri=call.getArgument(1);
            if (uri.getPath().equals("/a")) return new WebHttpClient.Reply(302,"text/html",new byte[0],"/final",null,null);
            if (uri.getPath().equals("/final")) return new WebHttpClient.Reply(200,"text/plain",("真正的静态正文。".repeat(30)).getBytes(StandardCharsets.UTF_8),null,null,null);
            return new WebHttpClient.Reply(200,"image/png",new byte[20],null,null,null);
        });
        var result=local.fetch(List.of("https://docs.example/a","https://docs.example/b"),new WebRequestContext(1000,null));
        assertEquals("OK",result.status()); assertTrue(result.incomplete()); assertEquals(1,result.pages().size());
        assertEquals("https://docs.example/a",result.pages().get(0).url()); assertEquals("https://docs.example/final",result.pages().get(0).finalUrl());
    }
    @Test void privateRedirectInAPartialBatchPreservesReadPagesButStopsFurtherRetrieval() {
        when(http.request(anyString(),any(),anyMap(),isNull(),anyInt(),any())).thenAnswer(call -> {
            URI uri=call.getArgument(1);
            return uri.getPath().equals("/a") ? new WebHttpClient.Reply(200,"text/plain",("真实公开正文。".repeat(30)).getBytes(StandardCharsets.UTF_8),null,null,null)
                    : new WebHttpClient.Reply(302,"text/html",new byte[0],"http://127.0.0.1/private",null,null);
        });
        var result=local.fetch(List.of("https://docs.example/a","https://docs.example/b","https://docs.example/c"),new WebRequestContext(1000,null));
        assertEquals("UNSAFE_URL",result.status()); assertEquals(1,result.pages().size()); assertTrue(result.incomplete());
        verify(http,times(2)).request(anyString(),any(),anyMap(),isNull(),anyInt(),any());
    }
}
