package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.repository.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class MaterialDeleteToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private MaterialReadService reads;
    private MaterialDeleteTool tool;
    private WrongQuestionRemoveTool wrongTool;
    private QuestionRepository questions;
    private ActiveTurnRegistry active;
    private AiService.ToolInvocation invocation;
    private ChatContextPlan plan;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("material-delete-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO ai_chat_session(id,title) VALUES(1,'测试')");
        jdbc.update("INSERT INTO ai_chat_message(id,session_id,role,content) VALUES(1,1,'user','删除学习资料')");
        turn("request", null, 1);
        jdbc.update("INSERT INTO notebook(id,title) VALUES(1,'数学'),(2,'空笔记本')");
        String doc = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文\"}]}]}";
        jdbc.update("INSERT INTO note_page(id,notebook_id,parent_id,title,content) VALUES(1,1,NULL,'根页面',?),(2,1,1,'中间页面',?),(3,1,2,'子页面',?)", doc, doc, doc);
        jdbc.update("INSERT INTO retrieval_chunk(id,corpus_type,corpus_id,source_id,chunk_index,text,start_offset,end_offset,content_hash) VALUES(1,'NOTEBOOK',1,2,0,'正文',0,2,'h')");
        jdbc.update("INSERT INTO question_bank(id,name,description,source_type) VALUES(1,'数学题库','描述','manual'),(2,'空题库','','manual')");
        jdbc.update("INSERT INTO question(id,bank_id,type,stem,answer,analysis) VALUES(1,1,'fill','2+2','4','解析'),(2,1,'fill','3+3','6','')");
        jdbc.update("INSERT INTO note_question_ref(note_id,question_id,snapshot_json) VALUES(2,1,'{}')");
        jdbc.update("INSERT INTO knowledge_point(id,bank_id,title,content) VALUES(1,1,'加法','正文'),(2,NULL,'未归属','正文')");
        jdbc.update("INSERT INTO knowledge_point_question VALUES(1,1)");
        jdbc.update("INSERT INTO knowledge_point_original(point_id,role,content) VALUES(1,'original','原文存档')");
        active = new ActiveTurnRegistry();
        invocation = new AiService.ToolInvocation("request", "c", 1, 1, active.acquire("request", 1).orElseThrow());
        plan = new ChatContextPlan("p", 1, 1, 1, null, null, null, 1, List.of(), List.of(), List.of(), null);
        var materials = new MaterialReadRepository(jdbc); reads = new MaterialReadService(materials, json);
        var notebooks = new NotebookRepository(jdbc, json); questions = new QuestionRepository(jdbc, json);
        var service = new MaterialDeleteService(jdbc, new DataSourceTransactionManager(ds), materials, new BankRepository(jdbc),
                new KnowledgePointRepository(jdbc, json), questions,
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), active, json);
        tool = new MaterialDeleteTool(service); wrongTool = new WrongQuestionRemoveTool(service);
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private void turn(String request, String retry, long id) {
        jdbc.update("""
                INSERT INTO ai_chat_turn(id,request_id,session_id,retry_of,status,user_message_id,context_generation,source_scope_type,source_scope_id,source_timezone,runtime_generation,request_meta_cipher,request_meta_crypto,started_at)
                VALUES(?,?,1,?,'RUNNING',1,1,'GLOBAL',0,'UTC','test','fixture','fixture','2026-11-10T00:00:00Z')
                """, id, request, retry);
    }
    private JsonNode read(String name, String args) { return json.valueToTree(MaterialReadTools.tool(name, reads).execute(null,args,false,"")); }
    private String version(String type, long id) {
        return switch(type) {
            case "notebook" -> read("read_notebook", "{\"notebookId\":"+id+"}").path("container").path("updateVersion").asText();
            case "bank" -> read("read_question_bank", "{\"bankId\":"+id+"}").path("container").path("updateVersion").asText();
            case "note" -> read("read_notebook", "{\"pageId\":"+id+"}").path("items").path(0).path("updateVersion").asText();
            case "knowledge" -> read("read_knowledge", "{\"pointId\":"+id+"}").path("items").path(0).path("updateVersion").asText();
            default -> read(type.equals("wrong") ? "read_wrong_questions" : "read_question_bank", "{\"questionId\":"+id+"}").path("items").path(0).path("updateVersion").asText();
        };
    }
    private Map<String,Object> args(String type, long id, String operation) {
        String v = version(type, id); assertEquals(64, v.length(), type);
        return new LinkedHashMap<>(Map.of("type",type,"id",id,"operationId",operation,"expectedVersion",v));
    }
    private JsonNode call(Map<String,Object> args) throws Exception { return json.valueToTree(tool.execute(plan, json.writeValueAsString(args), false, "删除资料", invocation)); }
    private JsonNode removeWrong(long id, String operation, String version) throws Exception {
        return json.valueToTree(wrongTool.execute(plan, json.writeValueAsString(Map.of("questionId",id,"operationId",operation,"expectedVersion",version)), false, "移出错题本", invocation));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void deletingAPageCleansItsIndexAndReparentsChildren() throws Exception {
        var result = call(args("note", 2, "page"));
        assertEquals("DELETED", result.path("status").asText(), result.toString());
        assertEquals("drill-notebook://material/note/2", result.path("sourceLink").asText());
        assertEquals("中间页面", result.path("items").get(0).path("title").asText());
        assertEquals(1, result.path("items").get(0).path("notebookId").asInt());
        assertEquals(List.of(1L, 3L), jdbc.queryForList("SELECT id FROM note_page ORDER BY id", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT parent_id FROM note_page WHERE id=3", Long.class));
        assertEquals(0, count("retrieval_chunk")); assertEquals(0, count("note_question_ref"));
        assertEquals(2, count("question"), "嵌入题目的原题及其他题目均不随页面删除");
        assertFalse(jdbc.queryForList("SELECT * FROM ai_material_deletion").toString().contains("中间页面"));
    }
    @Test void knowledgeAndQuestionDeletionFollowTheManualCascade() throws Exception {
        questions.recordAnswer(1,"3",false,0,"s",null,null);
        assertEquals("DELETED", call(args("question", 1, "q")).path("status").asText());
        assertEquals(0, count("answer_record")); assertEquals(0, count("knowledge_point_question"));
        assertEquals(1, count("question")); assertEquals(2, count("knowledge_point"), "关联知识点保留");
        assertEquals("DELETED", call(args("knowledge", 1, "k")).path("status").asText());
        assertEquals(0, count("knowledge_point_original")); assertEquals(1, count("knowledge_point"));
        assertEquals(2, count("question_bank"), "删除题目和知识点不删除所属题库或空题库");
    }
    @Test void nonEmptyContainersNeedExplicitCascadeAndReportWhatTheyContain() throws Exception {
        var notebook = args("notebook", 1, "book");
        var refused = call(notebook);
        assertEquals("NOT_EMPTY", refused.path("status").asText()); assertEquals(3, refused.path("contains").path("pages").asInt());
        assertEquals(3, count("note_page")); assertEquals(0, count("ai_material_deletion"));
        notebook.put("cascade", true); notebook.put("operationId", "book-all");
        var deleted = call(notebook);
        assertEquals("DELETED", deleted.path("status").asText(), deleted.toString());
        assertEquals(3, deleted.path("items").get(0).path("deleted").path("pages").asInt());
        assertEquals(0, count("note_page")); assertEquals(0, count("retrieval_chunk")); assertEquals(1, count("notebook"));
        assertEquals("DELETED", call(args("notebook", 2, "empty-book")).path("status").asText());

        var bank = args("bank", 1, "bank");
        var bankRefused = call(bank);
        assertEquals(2, bankRefused.path("contains").path("questions").asInt()); assertEquals(1, bankRefused.path("contains").path("knowledgePoints").asInt());
        bank.put("cascade", true); bank.put("operationId", "bank-all");
        assertEquals("DELETED", call(bank).path("status").asText());
        assertEquals(0, count("question")); assertEquals(1, count("knowledge_point"), "未归属题库的知识点保留");
        assertEquals("DELETED", call(args("bank", 2, "empty-bank")).path("status").asText()); assertEquals(0, count("question_bank"));
    }
    @Test void notebookBoundToTheCallingSessionCannotBeDeletedFromIt() throws Exception {
        jdbc.update("UPDATE ai_chat_session SET memory_scope_type='NOTEBOOK',memory_notebook_id=2 WHERE id=1");
        assertEquals("IN_USE", call(args("notebook", 2, "bound")).path("status").asText());
        assertEquals(2, count("notebook"));
        assertEquals(1, jdbc.queryForObject("SELECT context_generation FROM ai_chat_session WHERE id=1", Integer.class));
    }
    @Test void aChangedObjectIsNeverDeletedWithAnOldVersion() throws Exception {
        var input = args("note", 1, "stale");
        jdbc.update("UPDATE note_page SET title='人工改名' WHERE id=1");
        assertEquals("CONFLICT", call(input).path("status").asText());
        assertEquals(3, count("note_page")); assertEquals(0, count("ai_material_deletion"));
        input.put("id", 99); assertEquals("NOT_FOUND", call(input).path("status").asText());
    }
    @Test void failuresRollBackEveryCascadeStep() throws Exception {
        var input = args("note", 2, "rollback");
        jdbc.execute("CREATE TRIGGER fail_page BEFORE DELETE ON note_page BEGIN SELECT RAISE(ABORT,'test'); END");
        assertEquals("UNAVAILABLE", call(input).path("status").asText());
        assertEquals(3, count("note_page")); assertEquals(1, count("retrieval_chunk")); assertEquals(1, count("note_question_ref"));
        assertEquals(2L, jdbc.queryForObject("SELECT parent_id FROM note_page WHERE id=3", Long.class));
        assertEquals(0, count("ai_material_deletion"));
    }
    @Test void retriesReportTheOriginalDeletionAndChangedArgumentsConflict() throws Exception {
        var input = args("knowledge", 2, "once");
        assertEquals("DELETED", call(input).path("status").asText());
        var again = call(input);
        assertEquals("DUPLICATE", again.path("status").asText()); assertFalse(again.path("items").get(0).has("title"));
        input.put("operationId", "another-call"); assertEquals("DUPLICATE", call(input).path("status").asText());
        jdbc.update("UPDATE ai_chat_turn SET status='FAILED' WHERE id=1"); turn("retry", "request", 2);
        invocation = new AiService.ToolInvocation("retry", "c2", 2, 1, active.acquire("retry", 1).orElseThrow());
        assertEquals("DUPLICATE", call(input).path("status").asText());
        input.put("operationId", "once"); input.put("id", 1); input.put("expectedVersion", version("knowledge", 1));
        assertEquals("CONFLICT", call(input).path("status").asText()); assertEquals(1, count("knowledge_point"));
        input.put("operationId", "fresh"); input.put("id", 2);
        assertEquals("NOT_FOUND", call(input).path("status").asText(), "新的操作不会把已删除对象当作成功");
        jdbc.update("DELETE FROM ai_chat_session WHERE id=1"); assertEquals(0, count("ai_material_deletion"));
    }
    @Test void failedNotebookDeletionAlsoRollsBackScopedMemoriesAndSessionGeneration() throws Exception {
        var wiring = ChatLegacyWiring.wire(jdbc.getDataSource(), jdbc);
        var stack = ChatContextWiring.wire(jdbc.getDataSource(), jdbc, wiring);
        try {
            assertEquals(201, stack.vault().create("password", "correct horse battery", "create-vault").status());
            assertEquals(200, stack.vault().unlock("correct horse battery", List.of()).status());
            var added = stack.items().add(new com.drillnotebook.app.service.memory.MemoryItemService.AddInput(
                    "GOAL", "NOTEBOOK", 1L, "book.goal", "book.goal:本册目标", "本册目标", null, true, "add-goal"));
            assertEquals(201, added.status(), added.body().toString());
            jdbc.update("INSERT INTO ai_chat_session(id,title,memory_scope_type,memory_notebook_id) VALUES(2,'关联会话','NOTEBOOK',1)");
            long revision = wiring.memory().readEnvelope().orElseThrow().revision();
            var input = args("notebook", 1, "rollback-book"); input.put("cascade", true);
            jdbc.execute("CREATE TRIGGER fail_book BEFORE DELETE ON notebook BEGIN SELECT RAISE(ABORT,'test'); END");
            assertEquals("UNAVAILABLE", call(input).path("status").asText());
            assertEquals(2, count("notebook")); assertEquals(3, count("note_page"));
            assertEquals(1, count("ai_memory_item")); assertEquals(0, count("ai_material_deletion"));
            assertEquals(revision, wiring.memory().readEnvelope().orElseThrow().revision());
            assertEquals(1, jdbc.queryForObject("SELECT context_generation FROM ai_chat_session WHERE id=2", Integer.class));
        } finally { stack.indexes().shutdown(); }
    }
    @Test void removingFromTheWrongBookKeepsTheQuestionAndItsHistory() throws Exception {
        questions.recordAnswer(1,"3",false,0,"s",null,null);
        String wrongVersion = version("wrong", 1);
        var before = jdbc.queryForList("SELECT * FROM answer_record");
        var removed = removeWrong(1, "w", wrongVersion);
        assertEquals("REMOVED", removed.path("status").asText(), removed.toString());
        assertEquals("drill-notebook://material/question/1", removed.path("sourceLink").asText());
        assertFalse(questions.inWrongBook(1)); assertEquals(2, count("question"));
        assertEquals(before, jdbc.queryForList("SELECT * FROM answer_record"));
        assertEquals("DUPLICATE", removeWrong(1, "w", wrongVersion).path("status").asText());
        assertEquals("DUPLICATE", removeWrong(1, "same-turn", wrongVersion).path("status").asText(), "同轮同内容不能换标识重放");
        jdbc.update("UPDATE ai_chat_turn SET status='COMPLETED' WHERE id=1"); turn("next", null, 2);
        invocation = new AiService.ToolInvocation("next", "c2", 2, 1, active.acquire("next", 1).orElseThrow());
        assertEquals("NO_CHANGE", removeWrong(1, "later", wrongVersion).path("status").asText(), "新一轮确认已不在错题本，不报成功移出");
        questions.recordAnswer(1,"5",false,0,"s",null,null);
        assertTrue(questions.inWrongBook(1), "再次答错按既有规则重新计入");
        assertEquals("CONFLICT", removeWrong(1, "old", "0".repeat(64)).path("status").asText());
        assertEquals("NOT_FOUND", removeWrong(99, "none", wrongVersion).path("status").asText());
    }
    @Test void cancelledStaleOrForeignInvocationsNeverDelete() throws Exception {
        var input = args("note", 1, "p");
        var foreign = new AiService.ToolInvocation("request", "c", 1, 1, new ActiveTurnRegistry.Handle("request", 1));
        assertEquals("REJECTED", json.valueToTree(tool.execute(plan, json.writeValueAsString(input), false, "", foreign)).path("status").asText());
        invocation.handle().requestCancel(); assertEquals("CANCELLED", call(input).path("status").asText());
        active.release("request", invocation.handle());
        invocation = new AiService.ToolInvocation("request", "c", 1, 1, active.acquire("request", 1).orElseThrow());
        jdbc.update("UPDATE ai_chat_session SET context_generation=2 WHERE id=1"); assertEquals("STALE", call(input).path("status").asText());
        assertEquals("REJECTED", tool.execute(plan, json.writeValueAsString(input), false, "").get("status"));
        assertEquals(3, count("note_page")); assertEquals(0, count("ai_material_deletion"));
    }
    @Test void strictValidationRejectsUnknownTypesFieldsAndMisplacedCascade() throws Exception {
        var input = args("note", 1, "invalid");
        for (var change : List.<Map<String,Object>>of(Map.of("type", "wrong"), Map.of("type", "session"), Map.of("cascade", true),
                Map.of("cascade", "true"), Map.of("id", "1"), Map.of("id", 0), Map.of("expectedVersion", "abc"), Map.of("operationId", "有空格 x"), Map.of("ids", List.of(1, 2)))) {
            var bad = new LinkedHashMap<>(input); bad.putAll(change);
            assertEquals("INVALID_ARGUMENT", call(bad).path("status").asText(), change.toString());
        }
        for (String raw : List.of("{} {}", "null", "{\"type\":\"note\",\"type\":\"bank\"}", "{\"type\":\"note\",\"operationId\":\"a\",\"expectedVersion\":\"" + "0".repeat(64) + "\"}"))
            assertEquals("INVALID_ARGUMENT", tool.execute(plan, raw, false, "", invocation).get("status"), raw);
        assertEquals("INVALID_ARGUMENT", wrongTool.execute(plan, "{\"questionId\":1,\"operationId\":\"a\",\"expectedVersion\":\"" + "0".repeat(64) + "\",\"cascade\":true}", false, "", invocation).get("status"));
        assertEquals(3, count("note_page")); assertEquals(0, count("ai_material_deletion"));
    }
    @Test void upgrades16KeepingReceiptsAndMaterialsWithDeleteSwitchesOff() throws Exception {
        jdbc.update("INSERT INTO ai_material_update(request_id,operation_id,session_id,fingerprint,material_type,material_id) VALUES('request','old',1,'hash','note',1)");
        jdbc.execute("DROP TABLE ai_material_deletion"); jdbc.execute("DROP TABLE ai_tool_settings"); jdbc.update("UPDATE schema_version SET version=16");
        new DatabaseInitializer(jdbc.getDataSource(), Schema9Fixture.resolver(root)).initialize();
        assertEquals(DatabaseInitializer.currentSchemaVersion(), jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(1, count("ai_material_update")); assertEquals(0, count("ai_material_deletion")); assertEquals(3, count("note_page"));
        var settings = new AiToolSettingsRepository(jdbc).read();
        assertEquals(0, settings.revision());
        for (String name : com.drillnotebook.app.model.AiToolSettings.DELETE_TOOLS) assertFalse(settings.values().enabled(name), name);
    }
}
