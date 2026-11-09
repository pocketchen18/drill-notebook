package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.SessionLookupRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatSteeringPathTest extends SessionDispatchServiceTest {
    @ParameterizedTest @ValueSource(strings={"chat_completions","anthropic"})
    void finalResponseBoundaryConsumesSteeringAndHistoryAndNextTurnKeepItsRole(String format) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);gates.add(release);
        var requests=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            int round=requests.size();
            if(round==1){entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            String answer=round==1?"原先回答。":"已结合补充。";
            byte[] bytes=json.writeValueAsBytes("anthropic".equals(format)
                    ?Map.of("type","message","role","assistant","content",List.of(Map.of("type","text","text",answer)),"stop_reason","end_turn","usage",Map.of("input_tokens",10,"output_tokens",5))
                    :Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content",answer),"finish_reason","stop")),"usage",Map.of("prompt_tokens",10,"completion_tokens",5)));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            ai.saveConfig(TestModelLimits.config("provider","custom","endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","apiKey","fake-key","model","fixture","streaming",false,"tools",false,"apiFormat",format));
            String id=UuidV7.generate(System.currentTimeMillis());
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",id,"input",Map.of("userText","原问题")));
            assertTrue(entered.await(4,TimeUnit.SECONDS));
            String sid=UuidV7.generate(System.currentTimeMillis());
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",sid,"mode","steer","targetRequestId",id,"input",Map.of("userText","补充：用中文回答")));
            assertEquals(1,requests.size(),"插话不能中断当前请求另起并发请求");
            release.countDown();terminal(id);
            assertEquals("COMPLETED",dispatch.status(id).get("status"),dispatch.status(id).toString());
            assertEquals(2,requests.size());assertTrue(requests.get(1).contains("补充：用中文回答"));
            assertEquals("原先回答。已结合补充。",dispatch.status(id).get("reply"));
            assertEquals("RECEIVED",jdbc.queryForObject("SELECT state FROM ai_chat_pending WHERE request_id=?",String.class,sid));
            assertTrue(stack.history().page(source,null,null,20).toString().contains("补充：用中文回答"));
            assertTrue(dispatch.readRequest(String.valueOf(source),id,0,null).toString().contains("用户插话"));
            var lookup=new SessionLookupService(new SessionLookupRepository(jdbc.getDataSource()),wiring.cipher(),stack.vault());
            assertTrue(lookup.read(plan,String.valueOf(source),null,20).toString().contains("补充：用中文回答"));
            for (String exportFormat : List.of("md", "html", "json")) {
                String exported = String.valueOf(ai.exportSession(source, exportFormat, "").get("content"));
                assertTrue(exported.contains("补充：用中文回答"), exportFormat);
                assertTrue(exported.indexOf("原先回答。") < exported.indexOf("补充：用中文回答"), exportFormat);
                assertTrue(exported.indexOf("补充：用中文回答") < exported.indexOf("已结合补充。"), exportFormat);
            }
            var exportedMessages = (List<Map<String,Object>>) ai.exportSession(source, "json", "").get("messages");
            assertEquals(List.of("user", "assistant", "user", "assistant"), exportedMessages.stream().map(item -> item.get("role")).toList());
            String next=UuidV7.generate(System.currentTimeMillis());
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",next,"input",Map.of("userText","继续")));terminal(next);
            var messages=json.readTree(requests.get(2)).path("messages");
            assertTrue(messages.toString().contains("补充：用中文回答"));
            boolean userRole=false;for(var message:messages)if(message.path("content").toString().contains("补充：用中文回答"))userRole|="user".equals(message.path("role").asText());
            assertTrue(userRole,"后续上下文必须保持插话的用户角色");
            jdbc.update("UPDATE ai_chat_pending SET payload_cipher='corrupt' WHERE request_id=?", sid);
            assertTrue(String.valueOf(ai.exportSession(source, "md", "").get("content")).contains("插话内容无法解密"));
        }finally {release.countDown();server.stop(0);}
    }

    @Test void failedSupplementResponseIsUnknownAndNeverAutomaticallyReplayed() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);gates.add(release);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();int round=count.incrementAndGet();
            if(round==1){entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
            byte[] bytes=round==1?json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","已完成原回复"),"finish_reason","stop")))):"upstream unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(round==1?200:503,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            ai.saveConfig(TestModelLimits.config("provider","custom","endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","apiKey","fake-key","model","fixture","streaming",false,"tools",false));
            String id=UuidV7.generate(System.currentTimeMillis()), sid=UuidV7.generate(System.currentTimeMillis());
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",id,"input",Map.of("userText","先回答")));
            assertTrue(entered.await(4,TimeUnit.SECONDS));
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",sid,"mode","steer","targetRequestId",id,"input",Map.of("userText","不能确认的插话")));
            release.countDown();terminal(id);
            assertEquals("已完成原回复",dispatch.status(id).get("reply"));
            assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM ai_chat_pending WHERE request_id=?",String.class,sid));
            assertTrue(((List<?>)dispatch.queueView(source).get("items")).isEmpty());
            assertFalse(stack.history().page(source,null,null,20).toString().contains("不能确认的插话"));
            assertFalse(String.valueOf(ai.exportSession(source, "md", "").get("content")).contains("不能确认的插话"));
            assertEquals(2,count.get());
            var item=((List<Map<String,Object>>)dispatch.queueView(source).get("steering")).get(0);
            var removed=dispatch.queueAction(source,sid,Map.of("action","remove","revision",item.get("revision")));
            assertThrows(ChatTurnRejectException.class,()->dispatch.queueAction(source,sid,Map.of("action","undo","revision",removed.get("itemRevision"))));
        }finally {release.countDown();server.stop(0);}
    }

    @Test void steeringWaitsForToolResultAndPreservesCallResultPair() throws Exception {
        var toolEntered=new CountDownLatch(1);var release=new CountDownLatch(1);gates.add(release);
        ai.setChatTools(List.of(new AiService.ChatTool(){
            public AiUpstreamClient.ToolDefinition definition(){return new AiUpstreamClient.ToolDefinition("lookup","fixture",Map.of("type","object","properties",Map.of()));}
            public Map<String,Object> execute(com.drillnotebook.app.model.ChatContextPlan plan,String args,boolean memory,String user){
                toolEntered.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();}
                return Map.of("status","OK","facts",List.of());
            }
        }));
        var requests=new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            var message=requests.size()==1?Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","call_1","type","function","function",Map.of("name","lookup","arguments","{}")))):Map.of("role","assistant","content","已结合工具结果与补充");
            byte[] bytes=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",message,"finish_reason",requests.size()==1?"tool_calls":"stop"))));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            ai.saveConfig(TestModelLimits.config("provider","custom","endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","apiKey","fake-key","model","fixture","streaming",false,"tools",true));
            String id=UuidV7.generate(System.currentTimeMillis());
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",id,"input",Map.of("userText","查询")));
            assertTrue(toolEntered.await(5,TimeUnit.SECONDS));
            dispatch.submitHuman(source,Map.of("protocolVersion",2,"requestId",UuidV7.generate(System.currentTimeMillis()),"mode","steer","targetRequestId",id,"input",Map.of("userText","补充条件")));
            assertEquals(1,requests.size());release.countDown();terminal(id);
            assertEquals("COMPLETED",dispatch.status(id).get("status"));
            var messages=requests.get(1).path("messages");int call=-1,result=-1,supplement=-1;
            for(int i=0;i<messages.size();i++){var m=messages.get(i);if(m.has("tool_calls"))call=i;if(m.has("tool_call_id"))result=i;if(m.path("content").toString().contains("补充条件"))supplement=i;}
            assertTrue(call>=0&&result==call+1&&supplement==result+1,messages.toString());
            assertEquals("call_1",messages.get(result).path("tool_call_id").asText());
        }finally {release.countDown();server.stop(0);}
    }
}
