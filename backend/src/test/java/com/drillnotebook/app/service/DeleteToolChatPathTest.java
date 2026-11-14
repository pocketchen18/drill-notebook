package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.*;
import com.drillnotebook.app.model.AiToolSettings;
import com.drillnotebook.app.model.ToolActivity;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.*;
import com.drillnotebook.app.service.memory.MemoryItemService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 真实编排下的全局工具开关及删除：上游为本机回环夹具，存储、轮次与资料写入使用实际实现。 */
class DeleteToolChatPathTest {
    private static final String PASSWORD = "correct horse battery";
    private record Call(String name, Map<String,Object> args) { }

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    /** 每个模型请求依次取一步：返回工具调用，或 null 表示给出最终回答。 */
    private final List<Function<JsonNode, Call>> script = new ArrayList<>();
    private Path root;
    private JdbcTemplate jdbc;
    private ChatLegacyWiring.Wiring wiring;
    private ChatContextWiring.Stack stack;
    private AiService ai;
    private AiToolSettingsRepository switches;
    private List<AiService.ChatTool> allTools;
    private HttpServer server;
    private long session;
    private int sequence;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("delete-chat-");
        var ds = Schema9Fixture.dataSource(root); new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds); wiring = ChatLegacyWiring.wire(ds, jdbc); stack = ChatContextWiring.wire(ds, jdbc, wiring);
        session = ChatLegacyWiring.ensureSession(jdbc);
        ai = new AiService(new AiConfigRepository(jdbc), wiring.sessions(), new ApiKeyEncryptor(), json, null, wiring.turnService(), wiring.cipher(), stack.contexts());
        switches = new AiToolSettingsRepository(jdbc); ai.setToolSettings(switches);
        var materials = new MaterialReadRepository(jdbc); var reads = MaterialReadTestSupport.service(jdbc, json);
        var notebooks = new NotebookRepository(jdbc, json);
        var deletes = new MaterialDeleteService(jdbc, new DataSourceTransactionManager(ds), materials, new BankRepository(jdbc),
                new KnowledgePointRepository(jdbc, json), new QuestionRepository(jdbc, json),
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), wiring.handles(), json);
        var lookup = new SessionLookupService(new SessionLookupRepository(ds), wiring.cipher(), stack.vault());
        var dispatch = mock(SessionDispatchService.class);
        var creates = new MaterialCreateService(jdbc, new DataSourceTransactionManager(ds), notebooks, new BankRepository(jdbc),
                new KnowledgePointRepository(jdbc, json), new QuestionRepository(jdbc, json),
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), wiring.handles(), json);
        var updates = new MaterialUpdateService(jdbc, new DataSourceTransactionManager(ds), materials, notebooks, new BankRepository(jdbc),
                new KnowledgePointRepository(jdbc, json), new QuestionRepository(jdbc, json),
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), wiring.handles(), json);
        allTools = List.of(MaterialReadTools.tool("read_notebook", reads), MaterialReadTools.tool("search_materials", reads),
                MaterialReadTools.tool("read_wrong_questions", reads), MaterialReadTools.tool("read_current_page", reads),
                MaterialReadTools.tool("read_knowledge", reads), MaterialReadTools.tool("read_question_bank", reads),
                new MaterialCreateTool(creates), new MaterialUpdateTool(updates),
                new MaterialDeleteTool(deletes), new WrongQuestionRemoveTool(deletes),
                new SessionListTool(lookup), new SessionReadTool(lookup), new SessionCreateTool(dispatch), new SessionSendTool(dispatch),
                new SessionDeleteTool(new SessionLookupRepository(ds), wiring.sessions(), wiring.turnService(), wiring.handles()),
                stack.memorySearchTool(), stack.memoryListTool(), stack.memoryCreateTool(), stack.memoryUpdateTool(), new MemoryDeleteTool(stack.contexts()));
        ai.setChatTools(allTools);
        jdbc.update("INSERT INTO notebook(id,title) VALUES(1,'数学')");
        jdbc.update("INSERT INTO note_page(id,notebook_id,title,content) VALUES(1,1,'旧草稿',?)", "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"私密正文\"}]}]}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            Call call;
            synchronized (requests) {
                int round = requests.size(); requests.add(request);
                call = round < script.size() ? script.get(round).apply(request) : null;
            }
            boolean anthropic = exchange.getRequestURI().getPath().endsWith("/messages");
            boolean stream = request.path("stream").asBoolean();
            String id = "c" + requests.size();
            String payload;
            if (anthropic) {
                var block = call == null ? Map.of("type", "text", "text", "已按实际结果回答。")
                        : Map.of("type", "tool_use", "id", id, "name", call.name(), "input", call.args());
                payload = json.writeValueAsString(Map.of("type", "message", "role", "assistant", "content", List.of(block),
                        "stop_reason", call == null ? "end_turn" : "tool_use", "usage", Map.of("input_tokens", 10, "output_tokens", 5)));
            } else {
                Map<String,Object> message = call == null ? Map.of("role", "assistant", "content", "已按实际结果回答。")
                        : Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function",
                        "function", Map.of("name", call.name(), "arguments", json.writeValueAsString(call.args())))));
                payload = json.writeValueAsString(Map.of("choices", List.of(Map.of(stream ? "delta" : "message", message,
                        "finish_reason", call == null ? "stop" : "tool_calls")), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
                if (stream) payload = "data: " + payload + "\n\ndata: [DONE]\n\n";
            }
            var bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        config("chat_completions", false);
    }
    @AfterEach void cleanup() throws Exception { SessionExecution.CURRENT.remove(); server.stop(0); stack.indexes().shutdown(); TestWorkspace.deleteRecursively(root); }

    private void config(String format, boolean stream) {
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "apiKey", "fake-key", "model", "fixture", "apiFormat", format, "tools", true, "streaming", stream));
    }
    private void enable(String... tools) {
        var changes = new LinkedHashMap<String,Boolean>(); for (String tool : tools) changes.put(tool, true);
        setSwitches(changes);
    }
    private void setSwitches(Map<String,Boolean> changes) {
        var current = switches.read(); switches.save(current.revision(), current.values().with(changes));
    }
    private Map<String,Object> body(String text, boolean memory) {
        return Map.of("protocolVersion", 2, "sessionId", session, "requestId", UuidV7.generate(System.currentTimeMillis()),
                "memoryOptions", Map.of("use", memory), "input", Map.of("userText", text));
    }
    /** 上一步工具结果：按调用 ID 从续写请求中取，兼容两种上游格式。 */
    private JsonNode toolResult(JsonNode request, int round) {
        String callId = "c" + round;
        try {
            for (var message : request.path("messages")) {
                if (message.path("tool_call_id").asText().equals(callId)) return json.readTree(message.path("content").asText());
                if (message.path("content").isArray()) for (var block : message.path("content"))
                    if (block.path("type").asText().equals("tool_result") && block.path("tool_use_id").asText().equals(callId))
                        return json.readTree(block.path("content").asText());
            }
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        return json.createObjectNode();
    }
    private List<ToolActivity> activities(Map<String,Object> body) { return wiring.turnService().toolActivities((String) body.get("requestId"), ""); }
    private String systemText(JsonNode request) {
        var text = new StringBuilder(request.path("system").toString());
        for (var message : request.path("messages")) if (message.path("role").asText().equals("system")) text.append(message.path("content").asText());
        return text.toString();
    }
    private int pages() { return jdbc.queryForObject("SELECT COUNT(*) FROM note_page", Integer.class); }
    private Set<String> offeredTools(JsonNode request) {
        Set<String> names = new HashSet<>();
        for (JsonNode tool : request.path("tools"))
            names.add(tool.has("function") ? tool.path("function").path("name").asText() : tool.path("name").asText());
        return names;
    }
    private void deleteNoteScript() {
        script.add(request -> new Call("read_notebook", Map.of("pageId", 1)));
        script.add(request -> new Call("delete_material", Map.of("type", "note", "id", 1, "operationId", "drop-note",
                "expectedVersion", toolResult(request, 1).path("items").path(0).path("updateVersion").asText())));
    }

    @Test void catalogCoversEveryBuiltinAndDefaultChatOffersOnlyTheSixteenOrdinaryTools() {
        Set<String> catalog = new HashSet<>(AiToolSettings.TOOLS.stream().map(AiToolSettings.Tool::name).toList());
        assertEquals(20, catalog.size());
        assertEquals(catalog, new HashSet<>(allTools.stream().map(tool -> tool.definition().name()).toList()));
        assertEquals("COMPLETED", ai.chat(body("看看有哪些能力", true)).get("status"));
        catalog.removeAll(AiToolSettings.DELETE_TOOLS);
        assertEquals(catalog, offeredTools(requests.get(0)));
    }

    @Test void ordinaryReadWriteMemoryAndSessionSwitchesAreIndependentAndCannotBeBypassed() {
        setSwitches(Map.of("read_notebook", false, "create_material", false, "create_memory", false, "send_message", false));
        script.add(request -> new Call("create_material", Map.of("type", "notebook", "title", "不应创建", "operationId", "blocked-create")));
        var body = body("新建一个笔记本", true);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        Set<String> offered = offeredTools(requests.get(0));
        for (String name : List.of("read_notebook", "create_material", "create_memory", "send_message")) assertFalse(offered.contains(name), name);
        for (String name : List.of("search_materials", "update_material", "list_memories", "read_session")) assertTrue(offered.contains(name), name);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notebook", Integer.class));
        assertEquals("INVALID_ARGUMENT", activities(body).get(0).outcome());
        String instructions = systemText(requests.get(0));
        assertTrue(instructions.contains("本轮未提供以下能力") && instructions.contains("send_message"));
        assertFalse(instructions.contains("必须调用 create_memory"), "关闭的能力不能仍被系统指引要求执行");
    }

    @ParameterizedTest @CsvSource({"read_notebook,chat_completions,false", "create_material,chat_completions,true",
            "read_notebook,anthropic,false", "create_material,anthropic,false"})
    void disablingOrdinaryToolsWhileReplyingPreventsReadsAndWrites(String name, String format, boolean stream) {
        config(format, stream);
        script.add(request -> {
            setSwitches(Map.of(name, false));
            return new Call(name, name.equals("read_notebook") ? Map.of("pageId", 1)
                    : Map.of("type", "notebook", "title", "不应创建", "operationId", "late-create"));
        });
        var body = body("读取笔记或新建一个笔记本", false);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertTrue(offeredTools(requests.get(0)).contains(name));
        assertEquals("DISABLED", toolResult(requests.get(1), 1).path("status").asText());
        assertEquals("DISABLED", activities(body).get(0).outcome());
        assertFalse(requests.get(1).toString().contains("私密正文"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notebook", Integer.class));
    }

    @Test void reenablingAToolTakesEffectOnTheNextTurnOnly() {
        setSwitches(Map.of("read_notebook", false));
        script.add(request -> { enable("read_notebook"); return new Call("read_notebook", Map.of("pageId", 1)); });
        var first = body("读取这页笔记", false);
        assertEquals("COMPLETED", ai.chat(first).get("status"));
        assertFalse(offeredTools(requests.get(0)).contains("read_notebook"));
        assertEquals("INVALID_ARGUMENT", activities(first).get(0).outcome());
        requests.clear(); script.clear();
        script.add(request -> new Call("read_notebook", Map.of("pageId", 1)));
        var second = body("再读这页笔记", false);
        assertEquals("COMPLETED", ai.chat(second).get("status"));
        assertTrue(offeredTools(requests.get(0)).contains("read_notebook"));
        assertEquals("OK", activities(second).get(0).outcome());
        assertTrue(requests.get(1).toString().contains("私密正文"));
    }

    @Test void allToolsCanBeDisabledWithoutPreventingNormalChat() {
        switches.save(0, AiToolSettings.ALL_DISABLED);
        var body = body("你好", true);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertTrue(offeredTools(requests.get(0)).isEmpty());
        assertTrue(systemText(requests.get(0)).contains("设置 → 工具"));
        assertFalse(systemText(requests.get(0)).contains("必须调用 create_memory"));
        assertTrue(activities(body).isEmpty());
    }

    @Test void storageFailuresDoNotFallBackToEnablingOrdinaryTools() {
        var broken = mock(AiToolSettingsRepository.class);
        when(broken.read()).thenThrow(new IllegalStateException("fixture storage unavailable"));
        ai.setToolSettings(broken);
        assertEquals("COMPLETED", ai.chat(body("你好", true)).get("status"));
        assertTrue(offeredTools(requests.get(0)).isEmpty());
        assertTrue(systemText(requests.get(0)).contains("设置暂不可读取"));
    }

    @Test void failureReadingSettingsDuringATurnDoesNotAllowAPendingWrite() {
        script.add(request -> {
            jdbc.update("INSERT INTO ai_tool_settings(id,revision,document) VALUES(1,1,'broken-json')");
            return new Call("create_material", Map.of("type", "notebook", "title", "不应创建", "operationId", "broken-create"));
        });
        var body = body("新建一个笔记本", false);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals("DISABLED", activities(body).get(0).outcome());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notebook", Integer.class));
    }

    @Test void globalDefaultsDoNotBypassMemoryOrModelToolControls() {
        assertEquals("COMPLETED", ai.chat(body("你好", false)).get("status"));
        for (var tool : allTools) if (tool.requiresMemory()) assertFalse(offeredTools(requests.get(0)).contains(tool.definition().name()));
        requests.clear();
        ai.saveConfig(TestModelLimits.config("provider", "custom", "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "apiKey", "fake-key", "model", "fixture", "tools", false, "streaming", false));
        assertEquals("COMPLETED", ai.chat(body("你好", true)).get("status"));
        assertTrue(offeredTools(requests.get(0)).isEmpty());
    }

    @Test void delegatedTasksKeepOrdinarySwitchesSeparateFromTheirDeletionRestriction() {
        enable(AiToolSettings.DELETE_TOOLS.toArray(String[]::new));
        setSwitches(Map.of("read_notebook", false));
        var execution = new SessionExecution(new AiTurnBudget(ai.dispatchSettings()), Set.of(session));
        execution.delegated = true; SessionExecution.CURRENT.set(execution);
        assertEquals("COMPLETED", ai.chat(body("读笔记", false)).get("status"));
        Set<String> offered = offeredTools(requests.get(0));
        assertFalse(offered.contains("read_notebook")); assertTrue(offered.contains("search_materials"));
        for (String name : AiToolSettings.DELETE_TOOLS) assertFalse(offered.contains(name));
        String instructions = systemText(requests.get(0));
        assertTrue(instructions.contains("本轮未提供以下能力：read_notebook"));
        assertTrue(instructions.contains("本轮由其他会话派发，不提供删除能力"));
    }

    @Test void deleteToolsAreNotOfferedUntilSwitchedOnAndTheModelIsToldWhere() {
        script.add(request -> new Call("delete_material", Map.of("type", "note", "id", 1, "operationId", "guess", "expectedVersion", "0".repeat(64))));
        var body = body("删除旧草稿这页", false);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        String tools = requests.get(0).path("tools").toString();
        assertTrue(tools.contains("read_notebook"));
        for (String name : AiToolSettings.DELETE_TOOLS) assertFalse(tools.contains(name), name);
        String system = systemText(requests.get(0));
        assertTrue(system.contains("设置 → 工具") && system.contains("删除学习资料") && system.contains("不得用修改"), system);
        assertFalse(system.contains("删除记忆"), "本轮未启用记忆时不提示记忆工具");
        assertEquals(1, pages(), "未提供的工具即使被调用也不执行");
        assertEquals("delete_material", activities(body).get(0).toolName());
        assertEquals("INVALID_ARGUMENT", activities(body).get(0).outcome(), "已知但未提供的工具不执行");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_material_deletion", Integer.class));
    }

    @ParameterizedTest @CsvSource({"chat_completions,false,false", "chat_completions,true,true", "anthropic,false,true"})
    void enabledSwitchLetsTheModelReadThenDeleteAndOnlySafeActivityIsKept(String format, boolean upstreamStream, boolean uiStream) throws Exception {
        config(format, upstreamStream); enable("delete_material"); deleteNoteScript();
        var body = body("删除旧草稿这页", false);
        var events = new ArrayList<Map<String,Object>>();
        if (uiStream) assertEquals("COMPLETED", ai.chatStream(body, (kind, text) -> true, events::add).status());
        else assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(3, requests.size()); assertEquals(0, pages());
        var tools = offeredTools(requests.get(0));
        assertTrue(tools.contains("delete_material"));
        assertFalse(tools.contains("remove_wrong_question") || tools.contains("delete_session") || tools.contains("delete_memory"), "各开关相互独立");
        assertTrue(systemText(requests.get(0)).contains("移出错题本"), "仍关闭的删除能力如实告知");
        assertEquals("DELETED", toolResult(requests.get(2), 2).path("status").asText());
        var rows = activities(body);
        assertEquals(List.of("OK", "DELETED"), rows.stream().map(ToolActivity::outcome).toList());
        assertEquals("delete_material", rows.get(1).toolName()); assertEquals(1, rows.get(1).count());
        assertEquals("drill-notebook://material/note/1", rows.get(1).sourceLink());
        assertFalse(json.writeValueAsString(rows).contains("旧草稿"));
        assertFalse(json.writeValueAsString(events).contains("私密正文"));
        assertFalse(stack.history().page(session, null, null, 20).toString().contains("私密正文"));
    }

    @Test void switchingOffDuringATurnStopsCallsThatHaveNotRunYet() {
        enable("delete_material");
        script.add(request -> new Call("read_notebook", Map.of("pageId", 1)));
        script.add(request -> {
            var current = switches.read();
            switches.save(current.revision(), current.values().with(Map.of("delete_material", false)));
            return new Call("delete_material", Map.of("type", "note", "id", 1, "operationId", "late",
                    "expectedVersion", toolResult(request, 1).path("items").path(0).path("updateVersion").asText()));
        });
        var body = body("删除旧草稿这页", false);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals(1, pages());
        assertEquals("DISABLED", toolResult(requests.get(2), 2).path("status").asText());
        assertEquals("DISABLED", activities(body).get(1).outcome());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_material_deletion", Integer.class));
    }

    @Test void sessionDeletionCompletesTheAnswerWithoutLeavingADeadActivityLink() {
        long target = wiring.sessions().create("待删会话", "fixture");
        String ref = jdbc.queryForObject("SELECT session_ref FROM ai_chat_session WHERE id=?", String.class, target);
        enable("delete_session");
        script.add(request -> new Call("delete_session", Map.of("session", "drill-notebook://session/" + ref)));
        var body = body("删除链接中的会话", false);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals("DELETED", toolResult(requests.get(1), 1).path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE id=?", Integer.class, target));
        assertEquals("delete_session", activities(body).get(0).toolName());
        assertNull(activities(body).get(0).sessionLink());
    }

    @Test void removingAWrongQuestionReadsItsVersionAndPreservesTheOriginal() {
        jdbc.update("INSERT INTO question_bank(id,name,source_type) VALUES(1,'题库','manual')");
        jdbc.update("INSERT INTO question(id,bank_id,type,stem,answer) VALUES(1,1,'fill','题干','答案')");
        var questions = new QuestionRepository(jdbc, json);
        questions.recordAnswer(1,"错答",false,0,"s",null,null);
        enable("remove_wrong_question");
        script.add(request -> new Call("read_wrong_questions", Map.of("questionId", 1)));
        script.add(request -> new Call("remove_wrong_question", Map.of("questionId", 1, "operationId", "remove-wrong",
                "expectedVersion", toolResult(request, 1).path("items").path(0).path("updateVersion").asText())));
        var body = body("把这道题移出错题本，保留原题", false);
        assertEquals("COMPLETED", ai.chat(body).get("status"));
        assertEquals("REMOVED", toolResult(requests.get(2), 2).path("status").asText());
        assertFalse(questions.inWrongBook(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM answer_record", Integer.class));
        assertEquals("drill-notebook://material/question/1", activities(body).get(1).sourceLink());
    }

    @Test void tasksDispatchedByAnotherSessionNeverReceiveDeleteTools() {
        enable(AiToolSettings.DELETE_TOOLS.toArray(String[]::new)); deleteNoteScript();
        var execution = new SessionExecution(new AiTurnBudget(ai.dispatchSettings()), Set.of(session));
        execution.delegated = true; SessionExecution.CURRENT.set(execution);
        assertEquals("COMPLETED", ai.chat(body("帮我清理旧草稿", false)).get("status"));
        String tools = requests.get(0).path("tools").toString();
        for (String name : AiToolSettings.DELETE_TOOLS) assertFalse(tools.contains(name), name);
        assertTrue(systemText(requests.get(0)).contains("由其他会话派发"));
        assertEquals(1, pages());
    }

    @Test void deletingANotebookWithItsMemoriesDoesNotInterruptTheSameTurn() throws Exception {
        unlockVault(); enableUseConsent();
        String scoped = add("GOAL", "NOTEBOOK", 1L, "book.goal", "本册只属于数学笔记本");
        String global = add("GOAL", "GLOBAL", 0L, "study.goal", "全局目标保留");
        indexReady(); enable("delete_material");
        script.add(request -> new Call("read_notebook", Map.of("notebookId", 1)));
        script.add(request -> new Call("delete_material", Map.of("type", "notebook", "id", 1, "operationId", "drop-book", "cascade", true,
                "expectedVersion", toolResult(request, 1).path("container").path("updateVersion").asText())));
        script.add(request -> new Call("search_memories", Map.of("query", "全局目标")));
        var body = body("把数学笔记本连同里面的页面都删掉", true);
        long revision = wiring.memory().readEnvelope().orElseThrow().revision();
        var response = ai.chat(body);
        assertEquals("COMPLETED", response.get("status"), String.valueOf(response));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notebook", Integer.class)); assertEquals(0, pages());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id=?", Integer.class, scoped));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id=?", Integer.class, global));
        assertTrue(wiring.memory().readEnvelope().orElseThrow().revision() > revision);
        assertEquals(List.of("OK", "DELETED", "OK"), activities(body).stream().map(ToolActivity::outcome).toList(), "删除后的同轮记忆工具仍可用");
        assertFalse(requests.get(2).toString().contains(AiService.VAULT_CHANGED), "内部标记不发给模型");
        assertEquals(1, toolResult(requests.get(2), 2).path("items").get(0).path("deleted").path("pages").asInt());
    }

    @Test void deletingAMemoryAdvancesTheVaultWithoutFailingTheTurnThatAskedForIt() throws Exception {
        unlockVault(); enableUseConsent();
        String target = add("GOAL", "GLOBAL", 0L, "study.goal", "考试安排在周五");
        add("PREFERENCE", "GLOBAL", 0L, "study.style", "喜欢先看结论");
        indexReady(); enable("delete_memory");
        script.add(request -> new Call("search_memories", Map.of("query", "考试安排")));
        script.add(request -> new Call("delete_memory", Map.of("currentText", toolResult(request, 1).path("facts").path(0).path("text").asText())));
        script.add(request -> new Call("search_memories", Map.of("query", "考试安排")));
        var body = body("忘掉我的考试安排", true);
        long epoch = wiring.memory().readEnvelope().orElseThrow().epoch();
        var response = ai.chat(body);
        assertEquals("COMPLETED", response.get("status"), String.valueOf(response));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_item WHERE memory_id=?", Integer.class, target));
        assertTrue(wiring.memory().readEnvelope().orElseThrow().epoch() > epoch);
        var rows = activities(body);
        assertEquals(List.of("search_memories", "delete_memory", "search_memories"), rows.stream().map(ToolActivity::toolName).toList());
        assertEquals("DELETED", rows.get(1).outcome()); assertEquals(1, rows.get(1).count());
        assertFalse(toolResult(requests.get(3), 3).toString().contains("考试安排在周五"), "同轮后续检索不再返回已删除的记忆");
        assertFalse(json.writeValueAsString(rows).contains("考试安排"));
        assertTrue(requests.get(0).path("tools").toString().contains("delete_memory"));

        requests.clear(); script.clear();
        assertEquals("COMPLETED", ai.chat(body("还有什么记忆", false)).get("status"));
        assertFalse(requests.get(0).path("tools").toString().contains("delete_memory"), "本轮未启用记忆时不提供记忆删除");
    }

    private String add(String kind, String scopeType, long scopeId, String predicate, String value) {
        var added = stack.items().add(new MemoryItemService.AddInput(kind, scopeType, scopeId, predicate, predicate + ':' + value, value, null, true, op("add")));
        assertEquals(201, added.status(), value + " 没有落成: " + added.body());
        return String.valueOf(added.body().get("memoryId"));
    }
    private void unlockVault() {
        assertEquals(201, stack.vault().create("password", PASSWORD, op("vault")).status());
        assertEquals(200, stack.vault().unlock(PASSWORD, List.of(new MemoryVaultService.SessionUnlock(session, PASSWORD))).status());
    }
    private void enableUseConsent() throws Exception {
        assertEquals(200, stack.policy().update(stack.policy().current().revision(), true, false, false, false, null).status());
        MemoryPolicyService.Target target = stack.policy().targetOf(MemoryPolicyService.PURPOSE_USE);
        assertEquals(200, stack.policy().grantConsent(MemoryPolicyService.PURPOSE_USE, target.fingerprint(), MemoryPolicyService.POLICY_VERSION).status());
    }
    private void indexReady() throws Exception {
        var envelope = wiring.memory().readEnvelope().orElseThrow();
        stack.indexes().buildNow(envelope.vaultId(), envelope.epoch(), envelope.revision());
    }
    private String op(String action) { return "delete-chat-" + action + '-' + (++sequence); }
}
