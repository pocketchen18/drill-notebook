package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.*;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class MaterialToolChatPathTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private HttpServer server;
    private long session;
    private boolean tools = true;
    private boolean steering;
    private String supplementId;
    @BeforeEach void setup() throws Exception {
        root=TestWorkspace.scratchDirectory("material-chat-");
        var ds=Schema9Fixture.dataSource(root); new DatabaseInitializer(ds,Schema9Fixture.resolver(root)).initialize();
        jdbc=new JdbcTemplate(ds); wiring=ChatLegacyWiring.wire(ds,jdbc); stack=ChatContextWiring.wire(ds,jdbc,wiring);
        session=wiring.sessions().create("资料读取","stub");
        ai=new AiService(new AiConfigRepository(jdbc),wiring.sessions(),new ApiKeyEncryptor(),json,null,wiring.turnService(),wiring.cipher(),stack.contexts());
        var material=new MaterialReadService(new MaterialReadRepository(jdbc),json);
        ai.setChatTools(List.of(MaterialReadTools.tool("read_current_page",material),MaterialReadTools.tool("search_materials",material),
                MaterialReadTools.tool("read_notebook",material),MaterialReadTools.tool("read_knowledge",material),
                MaterialReadTools.tool("read_question_bank",material),MaterialReadTools.tool("read_wrong_questions",material),stack.memorySearchTool()));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            JsonNode request=json.readTree(exchange.getRequestBody()); requests.add(request);
            int round=requests.size();
            boolean done=!tools || round>(steering ? 2 : 1);
            boolean anthropic=exchange.getRequestURI().getPath().endsWith("/messages");
            boolean stream=request.path("stream").asBoolean();
            var args=steering && round==2 ? Map.of("requestId",supplementId) : Map.of();
            Map<String,Object> message=done ? Map.of("role","assistant","content","已按实际读取内容回答。")
                    : Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","c"+round,"type","function","function",Map.of("name","read_current_page","arguments",json.writeValueAsString(args)))));
            String payload;
            if(anthropic) {
                var block=done ? Map.of("type","text","text","已按实际读取内容回答。")
                        : Map.of("type","tool_use","id","c"+round,"name","read_current_page","input",args);
                payload=json.writeValueAsString(Map.of("type","message","role","assistant","content",List.of(block),"stop_reason",done?"end_turn":"tool_use","usage",Map.of("input_tokens",10,"output_tokens",5)));
            } else {
                if(stream && !done) message=Map.of("tool_calls",List.of(Map.of("index",0,"id","c"+round,"type","function","function",Map.of("name","read_current_page","arguments",json.writeValueAsString(args)))));
                payload=json.writeValueAsString(Map.of("choices",List.of(Map.of(stream?"delta":"message",message,"finish_reason",done?"stop":"tool_calls")),"usage",Map.of("prompt_tokens",10,"completion_tokens",5)));
                if(stream) payload="data: "+payload+"\n\ndata: [DONE]\n\n";
            }
            var bytes=payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }); server.start();
    }
    @AfterEach void cleanup() throws Exception { SessionExecution.CURRENT.remove(); server.stop(0); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root); }
    private void config(String format,boolean stream) {
        ai.saveConfig(Map.of("provider","custom","endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","apiKey","fake-key","model","fixture","apiFormat",format,"tools",tools,"streaming",stream));
    }
    private Map<String,Object> body(String text) {
        return Map.of("protocolVersion",2,"sessionId",session,"requestId",UuidV7.generate(System.currentTimeMillis()),
                "memoryOptions",Map.of("use",false),"input",Map.of("userText","讲解当前内容"),"pageSnapshot",snapshot(text));
    }
    private Map<String,Object> snapshot(String text) { return Map.of("kind","manual","title","业务页面","capturedAt","2026-11-09T10:00:00Z","markdown",text,"isDraft",true); }
    @ParameterizedTest @CsvSource({"chat_completions,false,false","chat_completions,true,true","anthropic,false,true"})
    void snapshotIsOnDemandWithMemoryOffAndOnlySafeActivityIsPersisted(String format,boolean upstreamStream,boolean uiStream) throws Exception {
        config(format,upstreamStream); var body=body("仅工具应读到的草稿正文");
        var events=new ArrayList<Map<String,Object>>();
        if(uiStream) assertEquals("COMPLETED",ai.chatStream(body,(kind,text)->true,events::add).status());
        else assertEquals("COMPLETED",ai.chat(body).get("status"));
        assertEquals(2,requests.size());
        assertFalse(requests.get(0).toString().contains("仅工具应读到的草稿正文"));
        assertTrue(requests.get(1).toString().contains("仅工具应读到的草稿正文"));
        assertTrue(requests.get(0).path("tools").toString().contains("read_wrong_questions"));
        assertFalse(requests.get(0).path("tools").toString().contains("search_memories"));
        var activity=wiring.turnService().toolActivities((String)body.get("requestId"),"").get(0);
        assertEquals("read_current_page",activity.toolName());assertEquals("OK",activity.outcome());
        assertFalse(json.writeValueAsString(events).contains("草稿正文"));
        assertFalse(stack.history().page(session,null,null,20).toString().contains("草稿正文"));
        assertFalse(jdbc.queryForList("SELECT * FROM ai_chat_turn").toString().contains("草稿正文"));
    }
    @Test void disabledToolsNeverSilentlyRestoreWholePageInjection() {
        tools=false;config("chat_completions",false);
        assertEquals("COMPLETED",ai.chat(body("不可预注入正文")).get("status"));
        assertEquals(1,requests.size());assertFalse(requests.get(0).toString().contains("不可预注入正文"));
        assertTrue(requests.get(0).toString().contains("本轮无法调用页面读取工具"));
    }
    @Test void steeringAddsItsOwnSnapshotWithoutChangingMainTarget() {
        steering=true;config("chat_completions",false); supplementId=UuidV7.generate(System.currentTimeMillis());
        var execution=new SessionExecution(new AiTurnBudget(ai.dispatchSettings()),Set.of(session));
        execution.steering=new SessionExecution.Steering() {
            boolean sent;
            public List<SessionExecution.Supplement> take(boolean finishing,int offset) {
                if(sent || requests.isEmpty()) return List.of();sent=true;
                return List.of(new SessionExecution.Supplement(supplementId,Map.of("protocolVersion",2,"sessionId",session,"requestId",supplementId,"input",Map.of("userText","再看这页"),"pageSnapshot",snapshot("插话正文")),offset));
            }
            public void sending(List<SessionExecution.Supplement> batch) { }
            public void received(List<SessionExecution.Supplement> batch) { }
        };
        SessionExecution.CURRENT.set(execution);
        assertEquals("COMPLETED",ai.chat(body("主消息正文")).get("status"));
        assertEquals(3,requests.size());
        assertTrue(requests.get(1).toString().contains("主消息正文"));assertFalse(requests.get(1).toString().contains("插话正文"));
        assertTrue(requests.get(2).toString().contains("插话正文"));
    }
}
