package com.drillnotebook.app.service.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;

class WebHttpClientTest {
    private HttpServer server;
    private ExecutorService tasks;
    private WebHttpClient http;
    private URI base;
    private CountDownLatch sentHeaders;
    private CountDownLatch release;
    private final AtomicBoolean cookieSeen=new AtomicBoolean();
    @BeforeEach void setup() throws Exception {
        tasks=Executors.newCachedThreadPool(); sentHeaders=new CountDownLatch(1); release=new CountDownLatch(1);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(tasks);
        server.createContext("/",exchange -> {
            cookieSeen.set(cookieSeen.get() || exchange.getRequestHeaders().containsKey("Cookie"));
            exchange.getResponseHeaders().set("Content-Type","text/plain"); exchange.getResponseHeaders().set("Set-Cookie","fixture=never-store");
            try {
                if (exchange.getRequestURI().getPath().equals("/gzip")) {
                    var bytes=new ByteArrayOutputStream(); try(var gzip=new GZIPOutputStream(bytes)){gzip.write("x".repeat(5000).getBytes());}
                    exchange.getResponseHeaders().set("Content-Encoding","gzip"); exchange.sendResponseHeaders(200,bytes.size()); exchange.getResponseBody().write(bytes.toByteArray());
                } else if (exchange.getRequestURI().getPath().equals("/slow")) {
                    exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('a'); exchange.getResponseBody().flush(); sentHeaders.countDown();
                    try { release.await(); } catch(InterruptedException error){Thread.currentThread().interrupt();}
                } else { exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write("valid content".getBytes()); }
            } finally { exchange.close(); }
        }); server.start();
        // Only the injected test double permits the fixture server. Production has no private-network bypass setting.
        var policy=mock(PublicWebPolicy.class); when(policy.parse(anyString())).thenAnswer(call -> URI.create(call.getArgument(0,String.class)));
        when(policy.resolve(eq("fixture.example"),any())).thenReturn(List.of(InetAddress.getByName("127.0.0.1")));
        http=new WebHttpClient(policy); base=URI.create("http://fixture.example:"+server.getAddress().getPort()+"/");
    }
    @AfterEach void cleanup(){release.countDown();server.stop(0);tasks.shutdownNow();}
    @Test void responseLimitAppliesToDecompressedGzipAndCookiesAreNeverRetained() {
        assertEquals(WebFailure.Code.CONTENT_TOO_LARGE,assertThrows(WebFailure.class,() -> http.request("GET",base.resolve("gzip"),Map.of(),null,1024,new WebRequestContext(2000,null))).code());
        http.request("GET",base,Map.of(),null,1024,new WebRequestContext(2000,null));
        http.request("GET",base,Map.of(),null,1024,new WebRequestContext(2000,null)); assertFalse(cookieSeen.get());
    }
    @Test void stoppingTheTurnClosesAnActiveResponseWithoutWaitingForTheRemoteServer() throws Exception {
        var handle=new ActiveTurnRegistry().acquire("cancel-web",1).orElseThrow();
        var future=tasks.submit(() -> assertThrows(WebFailure.class,() -> http.request("GET",base.resolve("slow"),Map.of(),null,1024,new WebRequestContext(10_000,handle))).code());
        assertTrue(sentHeaders.await(2,TimeUnit.SECONDS)); handle.requestCancel();
        assertEquals(WebFailure.Code.CANCELLED,future.get(2,TimeUnit.SECONDS));
    }
}
