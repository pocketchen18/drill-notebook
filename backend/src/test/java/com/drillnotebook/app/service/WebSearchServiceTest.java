package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.model.WebSearchSettings;
import com.drillnotebook.app.repository.WebSearchSettingsRepository;
import com.drillnotebook.app.service.web.*;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

class WebSearchServiceTest {
    private WebSearchSettingsService settings;
    private WebProviders providers;
    private LocalWebFetcher local;
    private PublicWebPolicy policy;
    private WebSearchService web;
    @BeforeEach void setup() {
        settings=mock(WebSearchSettingsService.class); providers=mock(WebProviders.class); local=mock(LocalWebFetcher.class);
        policy=new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        web=new WebSearchService(settings,providers,local,policy);
        when(settings.read()).thenReturn(new WebSearchSettingsRepository.Snapshot(0,WebSearchSettings.DEFAULTS));
    }
    @AfterEach void cleanup() { policy.close(); }
    private WebResult page(WebResult.Provider provider,String url) { return WebResult.success(List.of(new WebResult.Page(url,"真实标题","实际返回内容",null)),provider); }
    private Map<String,Object> search(AiService.ToolInvocation invocation) { return web.execute(false,"仅公开检索词",List.of(),3,1200,0,invocation); }

    @Test void defaultSearchTriesEachAnonymousServiceOnceAndHonorsRetryAfterCooldown() {
        when(providers.search(eq(WebResult.Provider.exa),anyString(),anyInt(),isNull(),any()))
                .thenThrow(new WebFailure(WebFailure.Code.RATE_LIMITED,429,System.currentTimeMillis()+120_000));
        when(providers.search(eq(WebResult.Provider.tavily),anyString(),anyInt(),isNull(),any()))
                .thenReturn(page(WebResult.Provider.tavily,"https://docs.example/a"));
        var first=search(null); assertEquals("OK",first.get("status")); assertEquals("tavily",first.get("webProvider"));
        assertEquals(2,((List<?>)first.get("webRoute")).size()); assertTrue(first.toString().contains("RATE_LIMITED"));
        var second=search(null); assertEquals("OK",second.get("status")); assertTrue(second.toString().contains("COOLDOWN"));
        verify(providers,times(1)).search(eq(WebResult.Provider.exa),eq("仅公开检索词"),eq(3),isNull(),argThat(context -> context.remainingMillis()<=10_000));
        verify(providers,times(2)).search(eq(WebResult.Provider.tavily),anyString(),anyInt(),isNull(),any());
    }
    @Test void allUnavailableShowsRealFailureAndNeverMasqueradesAsNoMatch() {
        when(providers.search(eq(WebResult.Provider.exa),anyString(),anyInt(),isNull(),any())).thenThrow(new WebFailure(WebFailure.Code.AUTH_REQUIRED,403,null));
        when(providers.search(eq(WebResult.Provider.tavily),anyString(),anyInt(),isNull(),any())).thenThrow(new WebFailure(WebFailure.Code.QUOTA_EXHAUSTED,429,System.currentTimeMillis()+60_000));
        var result=search(null); assertEquals("QUOTA_EXHAUSTED",result.get("status")); assertTrue(((List<?>)result.get("items")).isEmpty());
        assertEquals(2,((List<?>)result.get("webRoute")).size()); assertFalse(result.toString().contains("apiKey"));
    }
    @Test void explicitProviderErrorsDoNotSendQueriesToAnotherRemoteService() {
        var config=new WebSearchSettings(WebSearchSettings.Provider.tavily,WebSearchSettings.Provider.auto,false,Map.of());
        when(settings.read()).thenReturn(new WebSearchSettingsRepository.Snapshot(3,config));
        when(settings.key(config,WebSearchSettings.Provider.tavily)).thenReturn("fixture-key");
        when(providers.search(eq(WebResult.Provider.tavily),anyString(),anyInt(),eq("fixture-key"),any())).thenThrow(new WebFailure(WebFailure.Code.AUTH_REQUIRED,401,null));
        var result=search(null); assertEquals("AUTH_REQUIRED",result.get("status")); assertEquals("tavily",result.get("webProvider"));
        verify(providers,times(1)).search(any(),anyString(),anyInt(),any(),any()); verifyNoInteractions(local);
        verify(providers,never()).search(eq(WebResult.Provider.exa),anyString(),anyInt(),any(),any());
    }
    @Test void defaultFetchKeepsLocalPagesAndOnlyFallsBackForMissingUrls() {
        String a="https://docs.example/a",b="https://docs.example/b";
        when(local.fetch(eq(List.of(a,b)),any())).thenReturn(new WebResult("OK",page(WebResult.Provider.local,a).pages(),
                List.of(new WebResult.Attempt(WebResult.Provider.local,"PARTIAL",null,null)),true));
        when(providers.fetch(eq(WebResult.Provider.exa),eq(List.of(b)),isNull(),any())).thenReturn(page(WebResult.Provider.exa,b));
        var result=web.execute(true,"",List.of(a,b),3,6000,0,null);
        assertEquals("OK",result.get("status")); assertEquals(false,result.get("incomplete")); assertEquals(2,((List<?>)result.get("items")).size());
        verify(providers).fetch(eq(WebResult.Provider.exa),eq(List.of(b)),isNull(),any());
        verify(providers,never()).fetch(eq(WebResult.Provider.tavily),anyList(),any(),any());
    }
    @Test void nativePriorityIsCapabilitySpecificAndPreservesNativeFailuresAndPermissionBudgetStops() {
        when(settings.read()).thenReturn(new WebSearchSettingsRepository.Snapshot(1,new WebSearchSettings(WebSearchSettings.Provider.auto,WebSearchSettings.Provider.auto,true,Map.of())));
        var nativeCall=mock(WebSearchService.NativeExecutor.class);
        when(nativeCall.supports(false)).thenReturn(true); when(nativeCall.provider()).thenReturn(WebResult.Provider.openai);
        when(nativeCall.execute(eq(false),anyString(),anyList(),anyInt(),any())).thenReturn(page(WebResult.Provider.openai,"https://docs.example/a"));
        var invocation=new AiService.ToolInvocation("fixture","call",1,1,null,Map.of(),new WebSearchService.TurnContext(2000,nativeCall));
        assertEquals("openai",search(invocation).get("webProvider")); verifyNoInteractions(providers,local);
        when(local.fetch(anyList(),any())).thenReturn(page(WebResult.Provider.local,"https://docs.example/a"));
        assertEquals("local",web.execute(true,"",List.of("https://docs.example/a"),3,6000,0,invocation).get("webProvider"));
        verify(nativeCall,never()).execute(eq(true),anyString(),anyList(),anyInt(),any());
        when(nativeCall.execute(eq(false),anyString(),anyList(),anyInt(),any())).thenThrow(new WebFailure(WebFailure.Code.LIMIT_REACHED));
        var stopped=search(invocation); assertEquals("LIMIT_REACHED",stopped.get("status")); assertEquals("openai",stopped.get("webProvider"));
        verifyNoInteractions(providers);
    }
    @Test void safetyCancelAndZeroRemainingBudgetNeverReachAnyProvider() {
        assertEquals("UNSAFE_URL",web.execute(true,"",List.of("http://169.254.169.254/"),3,6000,0,null).get("status"));
        var handle=new ActiveTurnRegistry().acquire("fixture",1).orElseThrow(); handle.requestCancel();
        assertEquals("CANCELLED",search(new AiService.ToolInvocation("fixture","call",1,1,handle)).get("status"));
        assertEquals("TIME_LIMIT",search(new AiService.ToolInvocation("fixture","call",1,1,null,Map.of(),new WebSearchService.TurnContext(0,null))).get("status"));
        verifyNoInteractions(providers,local);
    }
    @Test void unexpectedProviderFailureKeepsItsRouteWithoutClaimingAKeyProblem() {
        when(providers.search(eq(WebResult.Provider.exa),anyString(),anyInt(),isNull(),any())).thenThrow(new IllegalStateException("untrusted fixture detail"));
        when(providers.search(eq(WebResult.Provider.tavily),anyString(),anyInt(),isNull(),any())).thenReturn(page(WebResult.Provider.tavily,"https://docs.example/a"));
        var result=search(null);
        assertEquals("OK",result.get("status")); assertTrue(result.toString().contains("UPSTREAM_ERROR"));
        assertFalse(result.toString().contains("KEY_UNAVAILABLE")); assertFalse(result.toString().contains("untrusted fixture detail"));
    }
    @Test void aBlockedLocalRedirectCannotBeRetriedThroughARemoteService() {
        String a="https://docs.example/a",b="https://docs.example/b";
        when(local.fetch(anyList(),any())).thenReturn(new WebResult("UNSAFE_URL",page(WebResult.Provider.local,a).pages(),
                List.of(new WebResult.Attempt(WebResult.Provider.local,"UNSAFE_URL",null,null)),true));
        var result=web.execute(true,"",List.of(a,b),3,6000,0,null);
        assertEquals("UNSAFE_URL",result.get("status")); assertEquals(1,((List<?>)result.get("items")).size());
        verifyNoInteractions(providers);
    }
    @Test void toolArgumentValidationIsStrictAndContentIsBoundedInUtf8WithARealContinuationOffset() {
        var search=new SearchWebTool(web); var fetch=new FetchWebTool(web);
        for (String arguments:List.of("{}","{\"query\":\"a\",\"query\":\"b\"}","{\"query\":\"a\"} {}","{\"query\":\"a\",\"count\":6}","{\"query\":\"a\",\"extra\":true}"))
            assertEquals("INVALID_ARGUMENT",search.execute(null,arguments,false,"私人会话不发送").get("status"));
        for (String arguments:List.of("{\"urls\":[]}","{\"urls\":[false]}","{\"urls\":[\"https://docs.example/\"],\"offset\":-1}"))
            assertEquals("INVALID_ARGUMENT",fetch.execute(null,arguments,false,"").get("status"));
        verifyNoInteractions(providers,local);
        String large="真实正文😀".repeat(5000);
        var payload=WebResult.success(List.of(new WebResult.Page("https://docs.example/a","标题",large,null)),WebResult.Provider.local).payload(true,12000,0);
        @SuppressWarnings("unchecked") var item=((List<Map<String,Object>>)payload.get("items")).get(0);
        String text=(String)item.get("content"); assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=14_000);
        assertFalse(text.contains("\ufffd")); assertEquals(text.length(),item.get("nextOffset")); assertEquals(true,item.get("untrusted")); assertEquals(true,payload.get("incomplete"));
    }
}
