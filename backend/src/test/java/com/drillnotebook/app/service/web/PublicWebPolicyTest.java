package com.drillnotebook.app.service.web;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicWebPolicyTest {
    private PublicWebPolicy policy;
    @BeforeEach void setup() { policy = new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}); }
    @AfterEach void cleanup() { policy.close(); }
    @ParameterizedTest @ValueSource(strings={"file:///C:/private", "ftp://example.com/a", "http://user:pass@example.com/", "http://example.com:18081/",
            "http://localhost/", "http://sub.localhost/", "http://router.local/", "http://service.internal/", "http://127.0.0.1/", "http://127.1/",
            "http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://0x7f.0.0.1/", "http://8.008.8.8/",
            "http://10.0.0.1/", "http://172.31.1.1/", "http://192.168.0.1/", "http://100.100.100.200/",
            "http://169.254.169.254/latest/meta-data/", "http://198.18.0.1/", "http://203.0.113.1/", "http://224.0.0.1/",
            "http://[::1]/", "http://[fd00:ec2::254]/", "http://[::ffff:8.8.8.8]/", "http://[2002:0808:0808::1]/", "http://[2001:db8::1]/",
            "http://[fe80::1%25eth0]/", "http://example.com\\@localhost/", "http://example.com/%0a\nHost:localhost"})
    void refusesNonPublicUrlsBeforeNetwork(String url) {
        assertEquals(WebFailure.Code.UNSAFE_URL, assertThrows(WebFailure.class, () -> policy.parse(url)).code());
    }
    @Test void publicUrlsKeepQueriesAndRemoveFragments() {
        assertEquals("https://example.com/page?q=public", policy.validate("https://example.com/page?q=public#section", new WebRequestContext(1000,null)).toString());
        assertTrue(PublicWebPolicy.isPublic(assertDoesNotThrow(() -> InetAddress.getByName("2001:4860:4860::8888"))));
    }
    @Test void anyPrivateDnsAnswerRejectsTheWholeTarget() throws Exception {
        policy.close(); policy = new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("10.0.0.1")});
        assertEquals(WebFailure.Code.UNSAFE_URL, assertThrows(WebFailure.class, () -> policy.validate("https://public-looking.example/",new WebRequestContext(1000,null))).code());
    }
    @Test void dnsIsRevalidatedAtTheActualConnection() throws Exception {
        policy.close(); var count = new AtomicInteger();
        policy = new PublicWebPolicy(host -> new InetAddress[]{InetAddress.getByName(count.incrementAndGet()==1 ? "8.8.8.8" : "127.0.0.1")});
        var context = new WebRequestContext(1000,null);
        URI url = policy.validate("https://rebind.example/",context);
        assertEquals(WebFailure.Code.UNSAFE_URL, assertThrows(WebFailure.class, () -> new WebHttpClient(policy).request("GET",url,Map.of(),null,1024,context)).code());
        assertEquals(2,count.get());
    }
    @Test void cancelledAndExpiredContextsNeverResolveOrConnect() {
        var handle = new ActiveTurnRegistry().acquire("web-fixture",1).orElseThrow(); handle.requestCancel();
        assertEquals(WebFailure.Code.CANCELLED, assertThrows(WebFailure.class, () -> policy.validate("https://example.com/",new WebRequestContext(1000,handle))).code());
        assertEquals(WebFailure.Code.TIME_LIMIT, assertThrows(WebFailure.class, () -> policy.validate("https://example.com/",new WebRequestContext(0,null))).code());
    }
    @Test void slowDnsIsBoundedAndDoesNotBlockTheTurn() throws Exception {
        policy.close(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        policy = new PublicWebPolicy(host -> {
            entered.countDown(); try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        });
        long start = System.nanoTime();
        try {
            assertEquals(WebFailure.Code.TIME_LIMIT, assertThrows(WebFailure.class, () -> policy.validate("https://example.com/",new WebRequestContext(60,null))).code());
            assertTrue((System.nanoTime()-start)/1_000_000 < 1000);
        } finally { release.countDown(); }
    }
    @Test void retryAfterAcceptsSecondsAndDatesButNotUnboundedNumbers() {
        assertEquals(61_000L, WebHttpClient.retryAt("60",1000));
        assertEquals(86_401_000L, WebHttpClient.retryAt("999999",1000));
        assertNull(WebHttpClient.retryAt("unknown",1000));
        assertNull(WebHttpClient.retryAt("9999999999999999999999999999999",1000));
    }
}
