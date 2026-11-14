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
    private boolean creating;
    private boolean updating;
    private boolean searching;
    private EmbeddingProviderRegistry embeddingProviders;
    private HybridRetrievalService hybrid;
    private String updateVersion;
    private String supplementId;
    @BeforeEach void setup() throws Exception {
        root=TestWorkspace.scratchDirectory("material-chat-");
        var ds=Schema9Fixture.dataSource(root); new DatabaseInitializer(ds,Schema9Fixture.resolver(root)).initialize();
        jdbc=new JdbcTemplate(ds); wiring=ChatLegacyWiring.wire(ds,jdbc); stack=ChatContextWiring.wire(ds,jdbc,wiring);
        session=wiring.sessions().create("资料读取","stub");
        ai=new AiService(new AiConfigRepository(jdbc),wiring.sessions(),new ApiKeyEncryptor(),json,null,wiring.turnService(),wiring.cipher(),stack.contexts());
        embeddingProviders = new EmbeddingProviderRegistry();
        hybrid = MaterialReadTestSupport.hybrid(jdbc, json, embeddingProviders, 1500, 25);
        var material=MaterialReadTestSupport.service(jdbc,json,hybrid);
        var notebooks = new NotebookRepository(jdbc, json);
        var create = new MaterialCreateTool(new MaterialCreateService(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds), notebooks,
                new BankRepository(jdbc), new KnowledgePointRepository(jdbc, json), new QuestionRepository(jdbc, json),
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), wiring.handles(), json));
        var update = new MaterialUpdateTool(new MaterialUpdateService(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds), new MaterialReadRepository(jdbc), notebooks,
                new BankRepository(jdbc), new KnowledgePointRepository(jdbc, json), new QuestionRepository(jdbc, json),
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), wiring.handles(), json));
        ai.setChatTools(List.of(MaterialReadTools.tool("read_current_page",material),MaterialReadTools.tool("search_materials",material),
                MaterialReadTools.tool("read_notebook",material),MaterialReadTools.tool("read_knowledge",material),
                MaterialReadTools.tool("read_question_bank",material),MaterialReadTools.tool("read_wrong_questions",material),create,update,stack.memorySearchTool()));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            JsonNode request=json.readTree(exchange.getRequestBody()); requests.add(request);
            int round=requests.size();
            boolean done=!tools || round>(updating ? 3 : steering || creating || searching ? 2 : 1);
            boolean anthropic=exchange.getRequestURI().getPath().endsWith("/messages");
            boolean stream=request.path("stream").asBoolean();
            String toolName = creating ? "create_material" : "read_current_page";
            Map<String,Object> args=creating ? Map.of("type", "notebook", "operationId", "save-book", "title", "工具保存的普通笔记本")
                    : steering && round==2 ? Map.of("requestId",supplementId) : Map.of();
            if (updating && !done) {
                toolName = round == 1 ? "read_notebook" : "update_material";
                if (round == 2) updateVersion = readResult(request, "c1").path("items").path(0).path("updateVersion").asText();
                args = round == 1 ? Map.of("pageId", 1) : Map.of("type", "note", "id", 1, "operationId", "edit-note",
                        "expectedVersion", updateVersion, "changes", Map.of("append", "修改工具保存的追加正文"));
            }
            if (searching && !done) {
                toolName = round == 1 ? "search_materials" : "read_notebook";
                args = round == 1 ? Map.of("type", "note", "query", "如何理解物体受力平衡", "notebookId", 1)
                        : Map.of("pageId", readResult(request, "c1").path("items").path(0).path("pageId").asLong(), "maxChars", 1000);
            }
            Map<String,Object> message=done ? Map.of("role","assistant","content","已按实际读取内容回答。")
                    : Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","c"+round,"type","function","function",Map.of("name",toolName,"arguments",json.writeValueAsString(args)))));
            String payload;
            if(anthropic) {
                var block=done ? Map.of("type","text","text","已按实际读取内容回答。")
                        : Map.of("type","tool_use","id","c"+round,"name",toolName,"input",args);
                payload=json.writeValueAsString(Map.of("type","message","role","assistant","content",List.of(block),"stop_reason",done?"end_turn":"tool_use","usage",Map.of("input_tokens",10,"output_tokens",5)));
            } else {
                if(stream && !done) message=Map.of("tool_calls",List.of(Map.of("index",0,"id","c"+round,"type","function","function",Map.of("name",toolName,"arguments",json.writeValueAsString(args)))));
                payload=json.writeValueAsString(Map.of("choices",List.of(Map.of(stream?"delta":"message",message,"finish_reason",done?"stop":"tool_calls")),"usage",Map.of("prompt_tokens",10,"completion_tokens",5)));
                if(stream) payload="data: "+payload+"\n\ndata: [DONE]\n\n";
            }
            var bytes=payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }); server.start();
    }
    @AfterEach void cleanup() throws Exception { SessionExecution.CURRENT.remove(); server.stop(0); hybrid.shutdown(); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root); }
    private void config(String format,boolean stream) {
        ai.saveConfig(TestModelLimits.config("provider","custom","endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","apiKey","fake-key","model","fixture","apiFormat",format,"tools",tools,"streaming",stream));
    }
    private Map<String,Object> body(String text) {
        return Map.of("protocolVersion",2,"sessionId",session,"requestId",UuidV7.generate(System.currentTimeMillis()),
                "memoryOptions",Map.of("use",false),"input",Map.of("userText","讲解当前内容"),"pageSnapshot",snapshot(text));
    }
    private Map<String,Object> snapshot(String text) { return Map.of("kind","manual","title","业务页面","capturedAt","2026-11-09T10:00:00Z","markdown",text,"isDraft",true); }
    private JsonNode readResult(JsonNode request, String callId) throws java.io.IOException {
        for (var message : request.path("messages")) {
            if (message.path("tool_call_id").asText().equals(callId)) return json.readTree(message.path("content").asText());
            if (message.path("content").isArray()) for (var block : message.path("content"))
                if (block.path("type").asText().equals("tool_result") && block.path("tool_use_id").asText().equals(callId))
                    return json.readTree(block.path("content").asText());
        }
        return json.createObjectNode();
    }

    @ParameterizedTest @CsvSource({"chat_completions,false,false","chat_completions,true,true","anthropic,false,true"})
    void vectorSearchThenIdReadWorksThroughTheRealChatToolPath(String format, boolean upstreamStream, boolean uiStream) throws Exception {
        var notebooks = new NotebookRepository(jdbc, json);
        long notebook = notebooks.insert("力学笔记");
        var doc = Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content",
                List.of(Map.of("type", "text", "text", "所有外力互相抵消，速度保持恒定。" + "背景内容。".repeat(110) + "只有续读才得到的结论")))));
        long page = notebooks.insertPage(notebook, "合力", doc);
        new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json).savePageAndIndex(page, "合力", doc);
        String space = MaterialHybridSearchTest.SPACE;
        jdbc.update("INSERT INTO embedding_space(embedding_space_id,canonical_contract_json,provider_type,model_identifier,dimensions,state,coverage,is_selected)"
                + " VALUES(?,'{}','local-rust','test/model',4,'ACTIVE',1,1)", space);
        for (var chunk : jdbc.queryForList("SELECT id,content_hash FROM retrieval_chunk WHERE source_id=?", page))
            jdbc.update("INSERT INTO retrieval_embedding(chunk_id,corpus_type,embedding_space_id,dimensions,content_hash,vector_blob) VALUES(?,'NOTEBOOK',?,4,?,?)",
                    chunk.get("id"), space, chunk.get("content_hash"), EmbeddingVectorCodec.encode(List.of(1f, 0f, 0f, 0f), 4));
        embeddingProviders.setActive(new HybridRetrievalServiceTest.FakeProvider(4, new float[]{1, 0, 0, 0}));
        searching = true; config(format, upstreamStream);
        var body = new LinkedHashMap<>(body("当前页面无笔记正文"));
        body.put("input", Map.of("userText", "如何理解物体受力平衡"));
        var events = new ArrayList<Map<String, Object>>();
        if (uiStream) assertEquals("COMPLETED", ai.chatStream(body, (kind, text) -> true, events::add).status());
        else assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(3, requests.size());
        assertFalse(requests.get(0).toString().contains("所有外力互相抵消"));
        var search = readResult(requests.get(1), "c1");
        assertEquals("OK", search.path("status").asText());
        assertEquals(List.of("vector"), json.convertValue(search.path("items").path(0).path("matchTypes"), List.class));
        assertEquals(page, search.path("items").path(0).path("pageId").asLong());
        assertTrue(search.toString().contains("所有外力互相抵消"));
        assertFalse(search.toString().contains("只有续读才得到的结论"));
        assertTrue(readResult(requests.get(2), "c2").toString().contains("只有续读才得到的结论"));
        assertTrue(requests.get(0).path("tools").toString().contains("searchMode"));
        assertFalse(requests.get(0).path("tools").toString().contains("search_memories"));
        var activities = wiring.turnService().toolActivities((String) body.get("requestId"), "");
        assertEquals(List.of("search_materials", "read_notebook"), activities.stream().map(com.drillnotebook.app.model.ToolActivity::toolName).toList());
        assertFalse(json.writeValueAsString(activities).contains("外力互相抵消"));
        assertFalse(json.writeValueAsString(events).contains("外力互相抵消"));
        assertFalse(stack.history().page(session, null, null, 20).toString().contains("外力互相抵消"));
    }
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
    @ParameterizedTest @CsvSource({"chat_completions,false,false","chat_completions,true,true","anthropic,false,true"})
    void creationWorksWithMemoryOffAndRetriesPreserveOneOrdinaryMaterial(String format, boolean upstreamStream, boolean uiStream) throws Exception {
        creating=true; config(format, upstreamStream);
        var body = new LinkedHashMap<>(body("页面正文"));
        body.put("input", Map.of("userText", "新建一个笔记本"));
        var events = new ArrayList<Map<String,Object>>();
        if(uiStream) assertEquals("COMPLETED", ai.chatStream(body, (kind,text)->true, events::add).status());
        else assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(3, requests.size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notebook WHERE title='工具保存的普通笔记本'", Integer.class));
        assertTrue(requests.get(1).toString().contains("CREATED"));
        assertTrue(requests.get(2).toString().contains("DUPLICATE"));
        var activities=wiring.turnService().toolActivities((String)body.get("requestId"), "");
        assertEquals(List.of("CREATED", "DUPLICATE"), activities.stream().map(com.drillnotebook.app.model.ToolActivity::outcome).toList());
        assertTrue(activities.get(0).sourceLink().startsWith("drill-notebook://material/notebook/"));
        assertEquals(activities.get(0).sourceLink(), activities.get(1).sourceLink());
        assertFalse(json.writeValueAsString(activities).contains("工具保存的普通笔记本"));
        assertFalse(json.writeValueAsString(events).contains("工具保存的普通笔记本"));
        assertFalse(requests.get(0).path("tools").toString().contains("search_memories"));
    }

    @ParameterizedTest @CsvSource({"chat_completions,false,false","chat_completions,true,true","anthropic,false,true"})
    void readThenUpdateUsesReturnedVersionAndPublishesOnlySafeActivities(String format, boolean upstreamStream, boolean uiStream) throws Exception {
        jdbc.update("INSERT INTO notebook(id,title) VALUES(1,'笔记本')");
        jdbc.update("INSERT INTO note_page(id,notebook_id,title,content) VALUES(1,1,'待修改笔记',?)", "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"保留原文\"}]}]}");
        updating = true; config(format, upstreamStream);
        var body = new LinkedHashMap<>(body("页面正文")); body.put("input", Map.of("userText", "在这页笔记后追加结论"));
        var events = new ArrayList<Map<String,Object>>();
        if (uiStream) assertEquals("COMPLETED", ai.chatStream(body, (kind,text)->true, events::add).status());
        else assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(4, requests.size()); assertEquals(64, updateVersion.length());
        assertEquals("UPDATED", readResult(requests.get(2), "c2").path("status").asText());
        assertEquals("DUPLICATE", readResult(requests.get(3), "c3").path("status").asText());
        var doc = json.readTree(jdbc.queryForObject("SELECT content FROM note_page WHERE id=1", String.class));
        assertTrue(doc.toString().contains("保留原文"));
        assertEquals(1, doc.findValuesAsText("markdown").stream().filter("修改工具保存的追加正文"::equals).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_material_update", Integer.class));
        var activities = wiring.turnService().toolActivities((String)body.get("requestId"), "");
        assertEquals(List.of("OK", "UPDATED", "DUPLICATE"), activities.stream().map(com.drillnotebook.app.model.ToolActivity::outcome).toList());
        assertEquals("drill-notebook://material/note/1", activities.get(1).sourceLink());
        assertFalse(json.writeValueAsString(activities).contains("追加正文"));
        assertFalse(json.writeValueAsString(events).contains("追加正文"));
        assertFalse(stack.history().page(session,null,null,20).toString().contains("追加正文"));
        assertTrue(requests.get(0).path("tools").toString().contains("update_material"));
        assertFalse(requests.get(0).path("tools").toString().contains("search_memories"));
    }

    @Test void disabledToolsNeverSilentlyRestoreWholePageInjection() {
        tools=false;config("chat_completions",false);
        assertEquals("COMPLETED",ai.chat(body("不可预注入正文")).get("status"));
        assertEquals(1,requests.size());assertFalse(requests.get(0).toString().contains("不可预注入正文"));
        assertTrue(requests.get(0).toString().contains("本轮无法调用页面读取工具"));
        assertFalse(requests.get(0).path("tools").toString().contains("update_material"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_material_update", Integer.class));
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
