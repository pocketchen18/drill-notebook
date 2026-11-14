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
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class MaterialUpdateToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private MaterialReadService reads;
    private MaterialUpdateTool tool;
    private MaterialReadRepository materials;
    private QuestionRepository questions;
    private ActiveTurnRegistry active;
    private AiService.ToolInvocation invocation;
    private ChatContextPlan plan;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("material-update-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO ai_chat_session(id,title) VALUES(1,'测试')");
        jdbc.update("INSERT INTO ai_chat_message(id,session_id,role,content) VALUES(1,1,'user','修改学习资料')");
        turn("request", null, 1);
        jdbc.update("INSERT INTO notebook(id,title) VALUES(1,'数学')");
        jdbc.update("INSERT INTO note_page(id,notebook_id,title,content) VALUES(1,1,'导数',?)", """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"原始说明","marks":[{"type":"bold"}]},{"type":"mathInline","attrs":{"latex":"x^2"}}]},
                {"type":"image","attrs":{"src":"local-image","alt":"图"}},{"type":"fileBlock","attrs":{"fileId":12,"fileName":"课件.pdf"}},
                {"type":"questionBlock","attrs":{"questionId":1,"snapshot":{"stem":"嵌入原题","answer":"A"}}},{"type":"markdownBlock","attrs":{"markdown":"# Markdown\\n可替换段落"}}]}
                """);
        jdbc.update("INSERT INTO question_bank(id,name,description,source_type) VALUES(1,'数学','旧描述','import'),(2,'物理','其他','manual')");
        jdbc.update("INSERT INTO question(id,bank_id,type,stem,answer,analysis,tags,chapter,group_id,order_in_group) VALUES(1,1,'fill','2+2','4','原解析','[\"tag\"]','第一章','组',2),(2,2,'essay','物理题','','','','','',NULL)");
        jdbc.update("INSERT INTO knowledge_point(id,bank_id,title,content,category,tags,heading_path,sort_index) VALUES(1,1,'加法','旧正文','原分类','[\"原标签\"]','[\"原章节\"]',7)");
        jdbc.update("INSERT INTO knowledge_point_question VALUES(1,1)");
        jdbc.update("INSERT INTO knowledge_point_original(point_id,role,content) VALUES(1,'original','原文存档'),(1,'summary','总结存档')");
        active = new ActiveTurnRegistry();
        invocation = new AiService.ToolInvocation("request", "c", 1, 1, active.acquire("request", 1).orElseThrow());
        plan = new ChatContextPlan("p", 1, 1, 1, null, null, null, 1, List.of(), List.of(), List.of(), null);
        materials = new MaterialReadRepository(jdbc); reads = MaterialReadTestSupport.service(jdbc, json);
        var notebooks = new NotebookRepository(jdbc, json); questions = new QuestionRepository(jdbc, json);
        tool = new MaterialUpdateTool(new MaterialUpdateService(jdbc, new DataSourceTransactionManager(ds), materials, notebooks,
                new BankRepository(jdbc), new KnowledgePointRepository(jdbc, json), questions,
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), active, json));
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private void turn(String request, String retry, long id) {
        jdbc.update("""
                INSERT INTO ai_chat_turn(id,request_id,session_id,retry_of,status,user_message_id,context_generation,source_scope_type,source_scope_id,source_timezone,runtime_generation,request_meta_cipher,request_meta_crypto,started_at)
                VALUES(?,?,1,?,'RUNNING',1,1,'GLOBAL',0,'UTC','test','fixture','fixture','2026-11-10T00:00:00Z')
                """, id, request, retry);
    }
    private JsonNode read(String name, String args) { return json.valueToTree(MaterialReadTools.tool(name, reads).execute(null,args,false,"")); }
    private String version(String type) {
        return switch(type) {
            case "notebook" -> read("read_notebook", "{\"notebookId\":1}").path("container").path("updateVersion").asText();
            case "bank" -> read("read_question_bank", "{\"bankId\":1}").path("container").path("updateVersion").asText();
            case "note" -> read("read_notebook", "{\"pageId\":1}").path("items").path(0).path("updateVersion").asText();
            case "knowledge" -> read("read_knowledge", "{\"pointId\":1}").path("items").path(0).path("updateVersion").asText();
            default -> read(type.equals("wrong") ? "read_wrong_questions" : "read_question_bank", "{\"questionId\":1}").path("items").path(0).path("updateVersion").asText();
        };
    }
    private Map<String,Object> args(String type, String operation, Map<String,Object> changes) {
        String v = version(type); assertEquals(64, v.length());
        return new LinkedHashMap<>(Map.of("type",type,"id",1,"operationId",operation,"expectedVersion",v,"changes",changes));
    }
    private JsonNode call(Map<String,Object> args) throws Exception { return json.valueToTree(tool.execute(plan, json.writeValueAsString(args), false, "修改资料", invocation)); }
    private JsonNode note() throws Exception { return json.readTree(jdbc.queryForObject("SELECT content FROM note_page WHERE id=1",String.class)); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void readsSupplySeparateUpdateVersionsAndContainers() {
        assertNotEquals(read("read_notebook","{\"pageId\":1}").path("version").asText(), version("note"));
        assertEquals("旧描述", read("read_question_bank","{\"bankId\":1}").path("container").path("description").asText());
        assertFalse(read("read_knowledge","{\"pointId\":1,\"variant\":\"original\"}").path("items").path(0).has("updateVersion"));
        String before = version("knowledge");
        jdbc.update("DELETE FROM knowledge_point_question WHERE knowledge_point_id=1");
        assertNotEquals(before, version("knowledge"));
        String question = version("question");
        questions.recordAnswer(1,"4",true,0,"s",null,null);
        assertEquals(question, version("question"));
    }
    @Test void renamesContainersWithoutChangingOtherFields() throws Exception {
        assertEquals("UPDATED",call(args("notebook","n",Map.of("title","新笔记本名"))).path("status").asText());
        assertEquals("UPDATED",call(args("bank","b",Map.of("title","新题库名"))).path("status").asText());
        assertEquals("旧描述", new BankRepository(jdbc).find(1).get("description"));
        assertEquals("import", new BankRepository(jdbc).find(1).get("sourceType"));
        assertEquals(1, count("note_page")); assertEquals(2, count("question"));
    }
    @Test void noteEditsAndAppendPreserveRichNodesAndUpdateIndexAtomically() throws Exception {
        var before = note();
        var result = call(args("note","edit",Map.of("title","新的导数", "edits",List.of(Map.of("oldText","原始说明","newText","新的说明"), Map.of("oldText","x^2","newText","x^3")),"append","## 追加结论")));
        assertEquals("UPDATED",result.path("status").asText(), result.toString());
        var after = note();
        for (int i : List.of(1,2,3,4)) assertEquals(before.path("content").get(i), after.path("content").get(i));
        assertEquals(before.path("content").get(0).path("content").get(0).path("marks"), after.path("content").get(0).path("content").get(0).path("marks"));
        assertTrue(after.toString().contains("新的说明")); assertTrue(after.toString().contains("追加结论"));
        assertTrue(jdbc.queryForList("SELECT text FROM retrieval_chunk WHERE source_id=1").toString().contains("新的说明"));
        assertFalse(jdbc.queryForList("SELECT * FROM ai_material_update").toString().contains("追加结论"));
        var remove = call(args("note","remove-text",Map.of("edits",List.of(Map.of("oldText","新的说明","newText","")))));
        assertEquals("UPDATED",remove.path("status").asText());
        assertFalse(note().toString().contains("\"text\":\"\""));
    }
    @Test void ambiguousOrMissingNoteTextAndIndexFailureRollbackEveryChange() throws Exception {
        String before = note().toString();
        assertEquals("CONFLICT",call(args("note","missing",Map.of("title","不能改名", "edits",List.of(Map.of("oldText","不存在","newText","替换"))))).path("status").asText());
        assertEquals(before,note().toString()); assertEquals("导数",materials.detail("note",1).get("title"));
        jdbc.execute("CREATE TRIGGER fail_chunk BEFORE INSERT ON retrieval_chunk BEGIN SELECT RAISE(ABORT,'test'); END");
        assertEquals("UNAVAILABLE",call(args("note","rollback",Map.of("append","新增内容"))).path("status").asText());
        assertEquals(before,note().toString()); assertEquals(0,count("ai_material_update"));
        jdbc.execute("DROP TRIGGER fail_chunk");
        jdbc.update("UPDATE note_page SET content=? WHERE id=1", "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"重复 重复\"}]}]}");
        assertEquals("CONFLICT",call(args("note","ambiguous",Map.of("edits",List.of(Map.of("oldText","重复","newText","替换"))))).path("status").asText());
    }
    @Test void knowledgePatchKeepsMetadataAssociationsAndArchives() throws Exception {
        jdbc.execute("CREATE TRIGGER unchanged_knowledge BEFORE UPDATE ON knowledge_point BEGIN SELECT RAISE(ABORT,'should not write'); END");
        assertEquals("NO_CHANGE",call(args("knowledge","same-links",Map.of("questionIds",List.of(1)))).path("status").asText());
        jdbc.execute("DROP TRIGGER unchanged_knowledge");
        assertEquals("UPDATED",call(args("knowledge","k",Map.of("content","新卡片正文"))).path("status").asText());
        var repository = new KnowledgePointRepository(jdbc,json); var point = repository.findById(1);
        assertEquals("加法",point.title); assertEquals("原分类",point.category); assertEquals(List.of("原标签"),point.tags); assertEquals(7,point.sortIndex);
        assertEquals(List.of(1L),repository.questionIds(1)); assertEquals(2,count("knowledge_point_original"));
        assertEquals("INVALID_ARGUMENT",call(args("knowledge","bad",Map.of("title","不应保存", "questionIds",List.of(2)))).path("status").asText());
        assertEquals("加法",repository.findById(1).title);
        assertEquals("UPDATED",call(args("knowledge","clear",Map.of("category","","questionIds",List.of()))).path("status").asText());
        assertEquals(List.of(),repository.questionIds(1)); assertEquals("新卡片正文",repository.findById(1).content);
    }
    @Test void wrongEditsOriginalQuestionWithoutChangingStatisticsOrMembership() throws Exception {
        questions.recordAnswer(1,"3",false,0,"s",null,null); questions.addToWrongBook(1);
        String originalVersion = version("wrong");
        assertEquals(version("question"),originalVersion);
        var before = jdbc.queryForList("SELECT * FROM answer_record");
        assertEquals("UPDATED",call(args("wrong","w",Map.of("analysis","更新解析"))).path("status").asText());
        assertEquals("4",questions.findById(1).answer); assertEquals("组",questions.findById(1).groupId);
        assertEquals(before,jdbc.queryForList("SELECT * FROM answer_record")); assertTrue(questions.inWrongBook(1));
        assertEquals(2,count("question"));
        assertEquals("INVALID_ARGUMENT",call(args("question","badtype",Map.of("questionType","single"))).path("status").asText());
        assertEquals("fill",questions.findById(1).type);
        var changes=Map.<String,Object>of("questionType","single","answer","b","options",List.of(Map.of("key","A","text","3"),Map.of("key","B","text","4")));
        assertEquals("UPDATED",call(args("question","type",changes)).path("status").asText());
        assertEquals("B",questions.findById(1).answer);
        var missing=args("wrong","removed",Map.of("analysis","不能覆盖")); questions.setWrongExcluded(1,true);
        assertEquals("NOT_FOUND",call(missing).path("status").asText());
    }
    @Test void questionPatchDoesNotNormalizeUnrequestedLegacyFields() throws Exception {
        jdbc.update("UPDATE question SET answer=' 4 ',difficulty=NULL,tags=NULL,options=NULL WHERE id=1");
        var before = materials.detail("question",1);
        assertEquals("UPDATED",call(args("question","only-analysis",Map.of("analysis","只改解析"))).path("status").asText());
        var after = materials.detail("question",1);
        for (String field : before.keySet()) if (!field.equals("analysis")) assertEquals(before.get(field),after.get(field),field);
        assertEquals("只改解析",after.get("analysis"));
    }

    @Test void staleVersionNeverOverwritesHumanEditEvenWithinSameSecond() throws Exception {
        var input=args("question","q",Map.of("analysis","AI 解析"));
        jdbc.update("UPDATE question SET analysis='人工最新解析' WHERE id=1");
        assertEquals("CONFLICT",call(input).path("status").asText()); assertEquals("人工最新解析",questions.findById(1).analysis);
        assertEquals(0,count("ai_material_update"));
    }
    @Test void retriesDoNotReplayAppendOrOverwriteLaterEditsAndSurviveTurnRetry() throws Exception {
        var input=args("note","append",Map.of("append","唯一追加"));
        assertEquals("UPDATED",call(input).path("status").asText());
        input.put("operationId","another-call"); assertEquals("DUPLICATE",call(input).path("status").asText());
        assertEquals(1, note().path("content").findValuesAsText("markdown").stream().filter(text->text.equals("唯一追加")).count());
        jdbc.update("UPDATE note_page SET title='人工改名' WHERE id=1");
        assertTrue(call(input).path("items").get(0).path("changedSinceOperation").asBoolean());
        jdbc.update("UPDATE ai_chat_turn SET status='FAILED' WHERE id=1"); turn("retry","request",2);
        invocation = new AiService.ToolInvocation("retry","c2",2,1,active.acquire("retry",1).orElseThrow());
        assertEquals("DUPLICATE",call(input).path("status").asText()); assertEquals("人工改名",materials.detail("note",1).get("title"));
        input.put("changes",Map.of("append","不同参数")); assertEquals("CONFLICT",call(input).path("status").asText());
        input.put("changes",Map.of("append","唯一追加")); jdbc.update("DELETE FROM note_page WHERE id=1");
        assertEquals("NOT_FOUND",call(input).path("status").asText()); assertEquals(0,count("note_page"));
    }
    @Test void noChangeDoesNotTouchIndexesAndDeletingSourceSessionKeepsMaterial() throws Exception {
        var input=args("note","same",Map.of("title","导数"));
        jdbc.execute("CREATE TRIGGER fail_note BEFORE UPDATE ON note_page BEGIN SELECT RAISE(ABORT,'should not write'); END");
        assertEquals("NO_CHANGE",call(input).path("status").asText()); assertEquals("DUPLICATE",call(input).path("status").asText());
        jdbc.update("DELETE FROM ai_chat_session WHERE id=1");
        assertEquals(0,count("ai_material_update")); assertEquals(1,count("note_page"));
    }
    @Test void parallelDifferentEditsUseOneSnapshotOnlyOnce() throws Exception {
        var first=args("note","one",Map.of("append","第一个")); var second=args("note","two",Map.of("append","第二个"));
        var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()-> { gate.await(); return call(first).path("status").asText(); });
            var b=pool.submit(()-> { gate.await(); return call(second).path("status").asText(); }); gate.countDown();
            assertEquals(Set.of("UPDATED","CONFLICT"),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
            assertEquals(1,count("ai_material_update"));
        } finally { pool.shutdownNow(); }
    }
    @Test void draftCancellationAndStaleTurnNeverWrite() throws Exception {
        var input=args("note","p",Map.of("append","追加"));
        var snapshot=PageSnapshot.from(Map.of("kind","note","pageId",1,"markdown","草稿","isDraft",true,"capturedAt","2026-11-10T00:00:00Z"));
        invocation=new AiService.ToolInvocation("request","c",1,1,invocation.handle(),Map.of("request",snapshot));
        assertEquals("DRAFT_UNSAVED",call(input).path("status").asText());
        invocation.handle().requestCancel(); assertEquals("CANCELLED",call(input).path("status").asText());
        active.release("request",invocation.handle()); invocation=new AiService.ToolInvocation("request","c",1,1,active.acquire("request",1).orElseThrow());
        jdbc.update("UPDATE ai_chat_session SET context_generation=2 WHERE id=1"); assertEquals("STALE",call(input).path("status").asText());
        assertEquals(0,count("ai_material_update"));
    }
    @Test void strictValidationRejectsWrongTypesUnsupportedFieldsAndWholePageReplacement() throws Exception {
        var input=args("note","invalid",Map.of("content","整页覆盖")); assertEquals("INVALID_ARGUMENT",call(input).path("status").asText());
        for (var changes:List.of(Map.of(),Map.of("title",9),Map.of("title",""),Map.of("parentPageId",3),Map.of("append","x".repeat(12001)))) {
            input.put("changes",changes); assertEquals("INVALID_ARGUMENT",call(input).path("status").asText());
        }
        for (String raw:List.of("{} {}","{\"type\":\"note\",\"type\":\"bank\"}","null")) assertEquals("INVALID_ARGUMENT",tool.execute(plan,raw,false,"",invocation).get("status"));
        assertEquals(0,count("ai_material_update"));
    }
    @Test void upgrades15WithExistingCreateReceiptsAndBusinessDataIntact() throws Exception {
        jdbc.update("INSERT INTO ai_material_creation(request_id,operation_id,session_id,fingerprint,material_type,material_id) VALUES('request','old',1,'hash','note',1)");
        jdbc.execute("DROP TABLE ai_material_update"); jdbc.update("UPDATE schema_version SET version=15");
        new DatabaseInitializer(jdbc.getDataSource(),Schema9Fixture.resolver(root)).initialize();
        assertEquals(DatabaseInitializer.currentSchemaVersion(),jdbc.queryForObject("SELECT version FROM schema_version",Integer.class));
        assertEquals(1,count("ai_material_creation")); assertEquals(0,count("ai_material_update")); assertEquals(1,count("note_page"));
        assertEquals("导数",materials.detail("note",1).get("title"));
    }
}
