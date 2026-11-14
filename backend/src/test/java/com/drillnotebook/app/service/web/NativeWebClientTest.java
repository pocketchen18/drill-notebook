package com.drillnotebook.app.service.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.service.AiUpstreamClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

class NativeWebClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private PublicWebPolicy policy;
    private WebHttpClient http;
    private NativeWebClient nativeWeb;
    @BeforeEach void setup(){policy=new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});http=mock(WebHttpClient.class);nativeWeb=new NativeWebClient(http,policy);}
    @AfterEach void cleanup(){policy.close();}
    private WebRequestContext context(){return new WebRequestContext(1000,null);}
    private AiUpstreamClient.Upstream target(boolean anthropic){return new AiUpstreamClient.Upstream(anthropic?"https://api.anthropic.com/v1":"https://api.openai.com/v1","fixture-key",anthropic?"claude-sonnet-4-6":"gpt-5.2",anthropic?"anthropic":"chat_completions",2048,true,false);}
    private void response(Object value)throws Exception{when(http.request(anyString(),any(),anyMap(),anyString(),anyInt(),any())).thenReturn(new WebHttpClient.Reply(200,"application/json",json.writeValueAsBytes(value),null,null,null));}

    @Test void onlyExplicitOfficialModelAndProtocolPairsHaveNativeCapabilities(){
        assertTrue(NativeWebCapabilities.of("https://api.anthropic.com/v1","anthropic","claude-fable-5-2").fetch());
        assertTrue(NativeWebCapabilities.of("https://api.openai.com/v1","chat_completions","gpt-5.2").search());
        assertFalse(NativeWebCapabilities.of("https://api.openai.com/v1","chat_completions","gpt-5.2").fetch());
        for(String endpoint:List.of("https://compatible.example/v1","https://api.anthropic.com.evil.example/v1","http://api.anthropic.com/v1","https://api.anthropic.com:444/v1","https://api.anthropic.com/arbitrary"))
            assertEquals(NativeWebCapabilities.NONE,NativeWebCapabilities.of(endpoint,"anthropic","claude-sonnet-4-6"));
        assertEquals(NativeWebCapabilities.NONE,NativeWebCapabilities.of("https://api.openai.com/v1","anthropic","gpt-5.2"));
        assertEquals(NativeWebCapabilities.NONE,NativeWebCapabilities.of("https://api.anthropic.com/v1","anthropic","claude-unknown-future"));
    }
    @Test void anthropicServerSearchReturnsRealSourcesAndCitedTextWithReportedUsage()throws Exception{
        response(Map.of("content",List.of(Map.of("type","web_search_tool_result","content",List.of(Map.of("type","web_search_result","url","https://docs.example/a","title","官方文档","encrypted_content","do-not-persist"))),
                Map.of("type","text","text","回答","citations",List.of(Map.of("url","https://docs.example/a","cited_text","实际引用片段")))),
                "stop_reason","end_turn","usage",Map.of("input_tokens",50,"output_tokens",12)));
        var result=nativeWeb.call(target(true),false,"公开检索词",List.of(),3,1024,context());
        assertEquals("anthropic",result.result().payload(false,1200,0).get("webProvider")); assertEquals("实际引用片段",result.result().pages().get(0).content());
        assertEquals(50,result.usage().inputTokens()); assertEquals(12,result.usage().outputTokens());
        assertFalse(result.result().toString().contains("do-not-persist"));
        verify(http).request(eq("POST"),eq(URI.create("https://api.anthropic.com/v1/messages")),anyMap(),argThat(body -> body.contains("web_search_20250305") && body.contains("\"max_uses\":1") && !body.contains("code_execution")),anyInt(),any());
    }
    @Test void anthropicFetchReturnsActualTextDocumentAndRestrictsUrlDomains()throws Exception{
        response(Map.of("content",List.of(Map.of("type","web_fetch_tool_result","content",Map.of("type","web_fetch_result","url","https://docs.example/a","content",Map.of("title","资料正文","source",Map.of("type","text","media_type","text/plain","data","实际页面正文"))))),"usage",Map.of("input_tokens",80,"output_tokens",10)));
        var result=nativeWeb.call(target(true),true,"",List.of("https://docs.example/a"),3,1024,context());
        assertEquals("实际页面正文",result.result().pages().get(0).content());
        verify(http).request(eq("POST"),any(),anyMap(),argThat(body -> body.contains("web_fetch_20250910") && body.contains("\"allowed_domains\":[\"docs.example\"]") && body.contains("max_content_tokens")),anyInt(),any());
    }
    @Test void openaiUsesSameModelResponsesSearchWithOneToolCallAndRealClickableCitations()throws Exception{
        response(Map.of("status","completed","output",List.of(Map.of("type","web_search_call","status","completed","action",Map.of("type","search","sources",List.of(Map.of("url","https://docs.example/a","title","来源")))),
                Map.of("type","message","content",List.of(Map.of("type","output_text","text","带来源的实际回答","annotations",List.of(Map.of("type","url_citation","url","https://docs.example/a","title","来源")))))),
                "usage",Map.of("input_tokens",100,"output_tokens",20,"input_tokens_details",Map.of("cached_tokens",30))));
        var result=nativeWeb.call(target(false),false,"公开检索词",List.of(),3,1024,context());
        assertEquals("OK",result.result().status()); assertEquals("https://docs.example/a",result.result().pages().get(0).url());
        assertEquals(30,result.usage().cacheReadTokens()); assertTrue(result.usage().cacheReadIncludedInInput());
        verify(http).request(eq("POST"),eq(URI.create("https://api.openai.com/v1/responses")),anyMap(),argThat(body -> body.contains("\"model\":\"gpt-5.2\"") && body.contains("\"max_tool_calls\":1") && body.contains("\"store\":false") && !body.contains("gpt-5-search-api")),anyInt(),any());
    }
    @Test void nativeBusinessFailureStillReportsItsActualTokenUsage()throws Exception{
        response(Map.of("content",List.of(Map.of("type","web_search_tool_result","content",Map.of("type","web_search_tool_result_error","error_code","too_many_requests"))),"usage",Map.of("input_tokens",10,"output_tokens",5)));
        var response=nativeWeb.call(target(true),false,"公开",List.of(),3,1024,context());
        assertEquals("RATE_LIMITED",response.result().status()); assertEquals(5,response.usage().outputTokens());
    }
    @Test void aNormalUncitedAnswerOrUnsupportedFetchCannotMasqueradeAsWebRetrieval()throws Exception{
        response(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","模型自己的知识"))))));
        assertEquals("EMPTY_CONTENT",nativeWeb.call(target(false),false,"公开",List.of(),3,1024,context()).result().status());
        reset(http);
        assertEquals(WebFailure.Code.NATIVE_UNSUPPORTED,assertThrows(WebFailure.class,() -> nativeWeb.call(target(false),true,"",List.of("https://docs.example/a"),3,1024,context())).code());
        verifyNoInteractions(http);
    }
}
