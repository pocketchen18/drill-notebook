package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.config.*;
import com.drillnotebook.app.memory.*;
import com.drillnotebook.app.model.*;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionDispatchServiceTest {
    Path root;
    JdbcTemplate jdbc;
    ChatLegacyWiring.Wiring wiring;
    ChatContextWiring.Stack stack;
    AiService ai;
    SessionDispatchService dispatch;
    SessionDispatchRepository receipts;
    ChatContextPlan plan;
    final ObjectMapper json=new ObjectMapper();
    final List<CountDownLatch> gates=new ArrayList<>();
    long source;
    @BeforeEach void setup() throws Exception {
        root=TestWorkspace.scratchDirectory("session-dispatch-");
        var ds=Schema9Fixture.dataSource(root); new DatabaseInitializer(ds,Schema9Fixture.resolver(root)).initialize();
        jdbc=new JdbcTemplate(ds); wiring=ChatLegacyWiring.wire(ds,jdbc); stack=ChatContextWiring.wire(ds,jdbc,wiring);
        ai=spy(new AiService(new AiConfigRepository(jdbc),wiring.sessions(),new ApiKeyEncryptor(),json,null,
                wiring.turnService(),wiring.cipher(),stack.contexts()));
        ai.saveConfig(Map.of("provider","custom","endpoint","mock://local","apiKey","test-key","model","fixture"));
        var beans=new DefaultListableBeanFactory(); beans.registerSingleton("ai",ai);
        receipts=new SessionDispatchRepository(jdbc,new ImmediateTransaction(ds));
        dispatch=new SessionDispatchService(beans.getBeanProvider(AiService.class),receipts,wiring.sessions(),wiring.turnService(),stack.vault(),wiring.cipher(),jdbc,json);
        source=wiring.sessions().create("当前会话","fixture");
        plan=new ChatContextPlan("plan",source,1,0,null,null,null,0,List.of(),List.of(),List.of(),null);
        SessionExecution.CURRENT.set(new SessionExecution(new AiTurnBudget(ai.dispatchSettings()),Set.of(source)));
    }
    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown); dispatch.close();
        for(int i=0;i<100 && !((Collection<?>)dispatch.runtime(null).get("sessions")).isEmpty();i++) {
            if(jdbc.queryForObject("SELECT count(*) FROM ai_session_dispatch WHERE state IN ('RUNNING','QUEUED')",Integer.class)==0) break;
            Thread.sleep(20);
        }
        SessionExecution.CURRENT.remove(); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root);
    }
    AiService.ToolInvocation call(String id) { return new AiService.ToolInvocation("caller",id,1,1,new ActiveTurnRegistry.Handle("caller",source)); }
    Map<String,Object> send(String call,long target,String message) { return dispatch.dispatch(plan,call(call),String.valueOf(target),message,null); }
    String request(Map<String,Object> accepted) { return (String)accepted.get("requestId"); }
    long target(Map<String,Object> accepted) { return ((Number)((Map<?,?>)accepted.get("session")).get("sessionId")).longValue(); }
    void terminal(String id) {
        assertTimeoutPreemptively(Duration.ofSeconds(12),()-> { while(Boolean.TRUE.equals(dispatch.status(id).get("active"))) Thread.sleep(20); });
    }
    CountDownLatch block(String text,CountDownLatch entered) {
        var release=new CountDownLatch(1); gates.add(release);
        doAnswer(invocation -> {
            Map<?,?> body=invocation.getArgument(0);
            if(text.equals(((Map<?,?>)body.get("input")).get("userText"))) { entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); }
            return invocation.callRealMethod();
        }).when(ai).runAccepted(anyMap(),any(),any(),any());
        return release;
    }
    @Test void createsNormalSessionAtomicallyInheritsScopeAndDeduplicatesCall() {
        jdbc.update("INSERT INTO notebook(id,title) VALUES(44,'笔记本')");
        jdbc.update("UPDATE ai_chat_session SET memory_scope_type='NOTEBOOK',memory_notebook_id=44 WHERE id=?",source);
        var created=dispatch.dispatch(plan,call("new"),null,"新任务","临时标题");
        assertEquals("CREATED",created.get("status"));
        var repeated=dispatch.dispatch(plan,call("new"),null,"新任务","临时标题");
        assertEquals(request(created),request(repeated));
        assertEquals("REJECTED",dispatch.dispatch(plan,call("new"),null,"更换消息",null).get("status"));
        terminal(request(created));
        assertEquals("COMPLETED",dispatch.status(request(created)).get("status"));
        assertEquals(44,jdbc.queryForObject("SELECT memory_notebook_id FROM ai_chat_session WHERE id=?",Integer.class,target(created)));
        assertEquals("temporary",wiring.sessions().find(target(created)).get("titleSource"));
        assertEquals(2,wiring.sessions().messages(target(created)).size());
        assertEquals(2,wiring.sessions().list(false).size());
        assertFalse(jdbc.queryForList("SELECT * FROM ai_session_dispatch").toString().contains("新任务"));
    }
    @Test void invalidConfigDoesNotLeaveEmptySession() {
        jdbc.update("DELETE FROM ai_config");
        assertThrows(IllegalArgumentException.class,()->dispatch.dispatch(plan,call("bad"),null,"问题",null));
        assertEquals(1,wiring.sessions().list(false).size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai_session_dispatch",Integer.class));
    }
    @Test void independentSessionsExecuteInParallelAndSubscriberDisconnectDoesNotCancel() throws Exception {
        var entered=new CountDownLatch(2); var release=block("hold",entered);
        var a=dispatch.dispatch(plan,call("a"),null,"hold",null);
        var b=dispatch.dispatch(plan,call("b"),null,"hold",null);
        assertTrue(entered.await(3,TimeUnit.SECONDS),"Two sessions must enter before either is released");
        var sub=dispatch.subscribe(request(a),(name,event)->{}); sub.close();
        assertTrue((boolean)dispatch.status(request(a)).get("active"));
        release.countDown(); terminal(request(a)); terminal(request(b));
        assertEquals("COMPLETED",dispatch.status(request(a)).get("status"));
    }
    @Test void busyTargetQueuesFifoAndCannotBeDeletedOrCleared() throws Exception {
        var entered=new CountDownLatch(1); var release=block("hold",entered);
        var first=dispatch.dispatch(plan,call("first"),null,"hold",null); long target=target(first);
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        var second=send("second",target,"第二条"); var third=send("third",target,"第三条");
        assertEquals("QUEUED",second.get("status")); assertEquals(1,second.get("queuePosition")); assertEquals(2,third.get("queuePosition"));
        assertFalse(dispatch.status(request(second)).containsKey("userText"),"A queue receipt must not expose an unstarted prompt as a chat turn");
        assertEquals("QUEUED",dispatch.readRequest(String.valueOf(target),request(second),0,null).get("status"));
        assertThrows(ChatTurnRejectException.class,()->wiring.sessions().delete(target));
        var turns=new ChatTurnRepository(jdbc.getDataSource(),new ImmediateTransaction(jdbc.getDataSource()),wiring.ledger(),wiring.metadata(),wiring.memory());
        assertThrows(Exception.class,()->turns.clearScope(List.of(),List.of(),target,1,"now"));
        release.countDown(); terminal(request(third));
        var texts=wiring.sessions().messages(target).stream().filter(r->r.role().equals("user"))
                .map(r->wiring.cipher().read(r.contentCipher(),r.contentMeta(),"").value()).toList();
        assertEquals(List.of("hold","第二条","第三条"),texts);
    }
    @Test void stopCancelsOnlyTargetQueueAndRejectsLateCancellation() throws Exception {
        var entered=new CountDownLatch(1); var release=block("hold",entered);
        var a=dispatch.dispatch(plan,call("a"),null,"hold",null);
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        var queued=send("q",target(a),"不能执行");
        var other=dispatch.dispatch(plan,call("b"),null,"独立任务",null);
        assertThrows(ChatTurnRejectException.class,()->dispatch.stop(target(a),"wrong"));
        dispatch.stop(target(a),request(a));
        assertEquals("CANCELLED",dispatch.status(request(queued)).get("status"));
        release.countDown(); terminal(request(a)); terminal(request(other));
        assertEquals("COMPLETED",dispatch.status(request(other)).get("status"));
        assertNotEquals("COMPLETED",dispatch.status(request(a)).get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai_chat_turn WHERE request_id=?",Integer.class,request(queued)));
    }
    @Test void exactReadDoesNotReturnPreviousResponseAndWaitReturnsSpecificResult() {
        var a=dispatch.dispatch(plan,call("a"),null,"首轮",null); terminal(request(a));
        var b=send("b",target(a),"第二轮"); terminal(request(b));
        var read=dispatch.readRequest((String)a.get("sessionLink"),request(b),1,null);
        assertEquals("OK",read.get("status"));
        assertEquals("第二轮",((Map<?,?>)((List<?>)read.get("messages")).get(0)).get("content"));
        assertEquals("NOT_FOUND",dispatch.readRequest(String.valueOf(source),request(b),0,null).get("status"));
    }
    @Test void staleCallerCannotDispatchOrReadExactResults() throws Exception {
        var child=dispatch.dispatch(plan,call("child"),null,"任务",null); terminal(request(child));
        wiring.metadata().resetContextGeneration(source);
        assertEquals("STALE",dispatch.dispatch(plan,call("late"),null,"迟到调用",null).get("status"));
        assertEquals("STALE",dispatch.readRequest(plan,(String)child.get("sessionLink"),request(child),0,call("read")).get("status"));
        assertEquals(2,wiring.sessions().list(false).size());
    }
    @Test void exactReadRechecksGenerationAfterDecrypting() {
        var child=dispatch.dispatch(plan,call("child"),null,"旧正文",null); terminal(request(child));
        var decryptor=spy(wiring.cipher());
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            var result=invocation.callRealMethod();
            if(changed.compareAndSet(false,true)) jdbc.update("UPDATE ai_chat_session SET context_generation=context_generation+1 WHERE id=?",target(child));
            return result;
        }).when(decryptor).readLegacy(nullable(String.class),nullable(String.class),nullable(String.class),anyString());
        var beans=new DefaultListableBeanFactory(); beans.registerSingleton("ai",ai);
        var reader=new SessionDispatchService(beans.getBeanProvider(AiService.class),receipts,wiring.sessions(),wiring.turnService(),stack.vault(),decryptor,jdbc,json);
        try {
            var result=reader.readRequest((String)child.get("sessionLink"),request(child),0,null);
            assertEquals("STALE",result.get("status"));
            assertFalse(result.containsKey("messages"));
        } finally { reader.close(); }
    }
    @Test void rejectsSelfAncestorsAmbiguousTitlesAndArchivedTargets() {
        assertEquals("CYCLE_BLOCKED",send("self",source,"x").get("status"));
        assertEquals("NOT_FOUND",dispatch.dispatch(plan,call("title"),"当前会话","x",null).get("status"));
        long target=wiring.sessions().create("归档","fixture"); wiring.sessions().update(target,null,true,null);
        assertThrows(ChatTurnRejectException.class,()->send("archived",target,"x"));
    }
    @Test void restartMarksReceiptsTerminalWithoutReplay() {
        var q=receipts.accept(UuidV7.generate(System.currentTimeMillis()),source,null,null,"fingerprint",null,"fixture");
        var r=receipts.accept(UuidV7.generate(System.currentTimeMillis()),source,null,null,"other",null,"fixture");
        receipts.state(r.requestId(),"RUNNING"); dispatch.recover();
        assertEquals("CANCELLED",dispatch.status(q.requestId()).get("status"));
        assertEquals("INTERRUPTED",dispatch.status(r.requestId()).get("status"));
        assertEquals(0,wiring.sessions().messages(source).size());
    }
    @Test void manualRequestRetryKeepsIdentityAndDoesNotEnqueueDuplicate() {
        var id=UuidV7.generate(System.currentTimeMillis());
        var body=Map.<String,Object>of("protocolVersion",2,"requestId",id,"sessionId",source,"input",Map.of("userText","同一问题"));
        dispatch.submit(body); dispatch.submit(body); terminal(id);
        assertEquals(2,wiring.sessions().messages(source).size());
        assertThrows(ChatTurnRejectException.class,()->dispatch.submit(Map.of("protocolVersion",2,"requestId",id,"sessionId",source,"input",Map.of("userText","不同问题"))));
    }

    @Test void expiredManualIdentityIsRejectedBeforeAcceptingAReceipt() {
        var id=UuidV7.generate(System.currentTimeMillis()-UuidV7.MAX_AGE_MILLIS-1000);
        var error=assertThrows(ChatTurnRejectException.class,()->dispatch.submit(Map.of(
                "protocolVersion",2,"requestId",id,"sessionId",source,"input",Map.of("userText","过期请求"))));
        assertEquals("RESULT_EXPIRED",error.code());
        assertNull(receipts.find(id));
        assertTrue(wiring.sessions().messages(source).isEmpty());
    }

    @Test void queuedRunFreezesAcceptedConfigurationAndSharesBudget() throws Exception {
        var entered=new CountDownLatch(1); var release=block("hold",entered);
        var first=dispatch.dispatch(plan,call("first"),null,"hold",null);
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        var shared=SessionExecution.CURRENT.get().budget;
        var seen=new CopyOnWriteArrayList<String>();
        doAnswer(invocation->{
            seen.add(((AiService.AcceptedConfig)invocation.getArgument(1)).row().model());
            assertSame(shared,SessionExecution.CURRENT.get().budget);
            return invocation.callRealMethod();
        }).when(ai).runAccepted(anyMap(),any(),any(),any());
        var next=send("next",target(first),"after");
        ai.saveConfig(Map.of("provider","custom","endpoint","mock://local","apiKey","test-key","model","changed"));
        release.countDown(); terminal(request(next));
        assertEquals(List.of("fixture"),seen);
    }

    @Test void upstreamFailureCancelsPendingMessages() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); gates.add(release);
        doAnswer(invocation->{ entered.countDown(); release.await(5,TimeUnit.SECONDS); throw new IllegalStateException("fixture failure"); })
                .when(ai).runAccepted(anyMap(),any(),any(),any());
        var first=dispatch.dispatch(plan,call("first"),null,"hold",null); assertTrue(entered.await(3,TimeUnit.SECONDS));
        var queued=send("next",target(first),"after"); release.countDown(); terminal(request(first));
        assertEquals("FAILED",dispatch.status(request(first)).get("status"));
        assertEquals("CANCELLED",dispatch.status(request(queued)).get("status"));
    }

    @ParameterizedTest @CsvSource({"chat_completions,false","chat_completions,true","anthropic,false","anthropic,true"})
    void realToolLoopCreatesQueuesWaitsAndReadsExactChild(String format,boolean stream) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var network=Executors.newCachedThreadPool(); server.setExecutor(network);
        var results=new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/",exchange->{
            try {
                var body=json.readTree(exchange.getRequestBody());
                var messages=body.path("messages"); String user="";
                var toolResults=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                for(var m:messages) {
                    if(m.path("role").asText().equals("user") && m.path("content").isTextual()) user=m.path("content").asText();
                    if(m.path("role").asText().equals("tool")) toolResults.add(json.readTree(m.path("content").asText()));
                    if(m.path("content").isArray()) for(var block:m.path("content")) {
                        if(m.path("role").asText().equals("user") && block.path("type").asText().equals("text")) user=block.path("text").asText();
                        if(block.path("type").asText().equals("tool_result")) toolResults.add(json.readTree(block.path("content").asText()));
                    }
                }
                String name=null; Map<String,Object> args=Map.of(); String text="child: "+user;
                if(user.equals("DELEGATE")) {
                    if(toolResults.isEmpty()) { name="create_session"; args=Map.of("message","CHILD FIRST","title","普通子会话"); }
                    else if(toolResults.size()==1) { name="send_message"; args=Map.of("session",toolResults.get(0).path("sessionLink").asText(),"message","CHILD SECOND"); }
                    else if(toolResults.size()==2) { name="read_session"; args=Map.of("session",toolResults.get(1).path("sessionLink").asText(),"requestId",toolResults.get(1).path("requestId").asText(),"waitSeconds",10); }
                    else { text="已读取本次发送的结果"; results.addAll(toolResults); }
                } else Thread.sleep(250);
                boolean tool=name!=null; String payload;
                if(format.equals("anthropic")) {
                    var block=tool ? Map.of("type","tool_use","id","call_"+toolResults.size(),"name",name,"input",args) : Map.of("type","text","text",text);
                    if(stream) {
                        var start=tool ? Map.of("type","tool_use","id","call_"+toolResults.size(),"name",name,"input",Map.of()) : Map.of("type","text","text","");
                        payload=event(Map.of("type","message_start","message",Map.of("usage",Map.of("input_tokens",20))))
                                +event(Map.of("type","content_block_start","index",0,"content_block",start))
                                +event(Map.of("type","content_block_delta","index",0,"delta",tool
                                  ? Map.of("type","input_json_delta","partial_json",json.writeValueAsString(args)) : Map.of("type","text_delta","text",text)))
                                +event(Map.of("type","content_block_stop","index",0))
                                +event(Map.of("type","message_delta","delta",Map.of("stop_reason",tool ? "tool_use" : "end_turn"),"usage",Map.of("output_tokens",15)))
                                +event(Map.of("type","message_stop"));
                    } else payload=json.writeValueAsString(Map.of("type","message","role","assistant","content",List.of(block),"stop_reason",tool ? "tool_use" : "end_turn","usage",Map.of("input_tokens",20,"output_tokens",15)));
                } else {
                    var call=new LinkedHashMap<String,Object>();
                    if(tool) { call.put("id","call_"+toolResults.size()); call.put("type","function"); call.put("function",Map.of("name",name,"arguments",json.writeValueAsString(args))); if(stream) call.put("index",0); }
                    var message=tool ? Map.of("content","","tool_calls",List.of(call)) : Map.of("content",text);
                    var frame=Map.of("choices",List.of(Map.of(stream ? "delta" : "message",message,"finish_reason",tool ? "tool_calls" : "stop")),"usage",Map.of("prompt_tokens",20,"completion_tokens",15));
                    payload=stream ? "data: "+json.writeValueAsString(frame)+"\n\ndata: [DONE]\n\n" : json.writeValueAsString(frame);
                }
                byte[] bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            } catch(Exception error) { exchange.close(); }
        }); server.start();
        try {
            var lookup=new SessionLookupService(new SessionLookupRepository(jdbc.getDataSource()),wiring.cipher(),stack.vault());
            var read=new SessionReadTool(lookup); read.setDispatch(dispatch);
            ai.setChatTools(List.of(new SessionCreateTool(dispatch),new SessionSendTool(dispatch),read,new SessionListTool(lookup)));
            ai.saveConfig(Map.of("provider","custom","endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",
                    "apiFormat",format,"model","fixture","apiKey","test-key","tools",true,"streaming",stream));
            String id=UuidV7.generate(System.currentTimeMillis());
            dispatch.submit(Map.of("protocolVersion",2,"requestId",id,"sessionId",source,"input",Map.of("userText","DELEGATE")));
            terminal(id);
            assertEquals("COMPLETED",dispatch.status(id).get("status"));
            assertEquals(3,results.size(),dispatch.status(id).toString());
            assertEquals("CREATED",results.get(0).path("status").asText());
            assertEquals("QUEUED",results.get(1).path("status").asText());
            assertEquals("CHILD SECOND",results.get(2).path("messages").get(0).path("content").asText());
            assertTrue(results.get(2).path("messages").get(1).path("content").asText().contains("CHILD SECOND"));
            assertEquals(2,wiring.sessions().list(false).size());
            var activities=wiring.turnService().toolActivities(id,"");
            assertEquals(List.of("create_session","send_message","read_session"),activities.stream().map(ToolActivity::toolName).toList());
            assertNotNull(activities.get(0).sessionLink());
            assertFalse(json.writeValueAsString(activities).contains("CHILD"));
        } finally { server.stop(0); network.shutdownNow(); }
    }
    private String event(Map<String,Object> data) throws Exception { return "event: "+data.get("type")+"\ndata: "+json.writeValueAsString(data)+"\n\n"; }
}
