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

class MaterialCreateToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private MaterialCreateTool tool;
    private MaterialReadService reads;
    private QuestionRepository questions;
    private ActiveTurnRegistry active;
    private AiService.ToolInvocation invocation;
    private ChatContextPlan plan;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("material-create-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO ai_chat_session(id,title) VALUES(1,'测试')");
        jdbc.update("INSERT INTO ai_chat_message(id,session_id,role,content) VALUES(1,1,'user','保存学习资料')");
        insertTurn("request", null, 1);
        jdbc.update("INSERT INTO notebook(id,title) VALUES(1,'数学')");
        jdbc.update("INSERT INTO question_bank(id,name) VALUES(1,'数学'),(2,'物理')");
        active = new ActiveTurnRegistry();
        invocation = new AiService.ToolInvocation("request", "call", 1, 1, active.acquire("request", 1).orElseThrow());
        plan = new ChatContextPlan("p", 1, 1, 1, null, null, null, 1, List.of(), List.of(), List.of(), null);
        var notebooks = new NotebookRepository(jdbc, json);
        questions = new QuestionRepository(jdbc, json);
        tool = new MaterialCreateTool(new MaterialCreateService(jdbc, new DataSourceTransactionManager(ds), notebooks,
                new BankRepository(jdbc), new KnowledgePointRepository(jdbc, json), questions,
                new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json), active, json));
        reads = MaterialReadTestSupport.service(jdbc, json);
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private void insertTurn(String request, String retryOf, long id) {
        jdbc.update("""
                INSERT INTO ai_chat_turn(id,request_id,session_id,retry_of,status,user_message_id,context_generation,
                  source_scope_type,source_scope_id,source_timezone,runtime_generation,request_meta_cipher,request_meta_crypto,started_at)
                VALUES(?,?,1,?,'RUNNING',1,1,'GLOBAL',0,'UTC','test','fixture','fixture','2026-11-10T00:00:00Z')
                """, id, request, retryOf);
    }
    private JsonNode call(Map<String, Object> args) throws Exception {
        return json.valueToTree(tool.execute(plan, json.writeValueAsString(args), false, "保存资料", invocation));
    }
    private Map<String, Object> args(String type, String operation, Object... fields) {
        var args = new LinkedHashMap<String, Object>(); args.put("type", type); args.put("operationId", operation);
        for (int i = 0; i < fields.length; i += 2) args.put((String) fields[i], fields[i + 1]);
        return args;
    }
    private long id(JsonNode result) { assertEquals("CREATED", result.path("status").asText(), result.toString()); return result.path("items").get(0).path("id").asLong(); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test void createsNotebookWithOrdinaryInitialPageAndIndexesMarkdownChild() throws Exception {
        long notebook = id(call(args("notebook", "book", "title", "新笔记本")));
        var initial = jdbc.queryForMap("SELECT id,content FROM note_page WHERE notebook_id=?", notebook);
        long page = id(call(args("note", "page", "notebookId", notebook, "parentPageId", initial.get("id"), "title", "导数", "content", "# 求导\n\n$f'(x)$ **总结**")));
        var saved = new NotebookRepository(jdbc, json).findPage(page);
        assertEquals(initial.get("id").toString(), saved.get("parentId").toString());
        assertTrue(saved.get("content").toString().contains("markdownBlock"));
        assertTrue(jdbc.queryForList("SELECT text FROM retrieval_chunk WHERE source_id=?", page).toString().contains("求导"));
        var result = MaterialReadTools.tool("read_notebook", reads).execute(null, "{\"pageId\":" + page + "}", false, "");
        assertTrue(result.toString().contains("f'(x)"));
        assertFalse(jdbc.queryForList("SELECT * FROM ai_material_creation").toString().contains("总结"));
    }

    @Test void createsOrdinaryBankKnowledgeAndQuestionWithValidReferences() throws Exception {
        long bank = id(call(args("bank", "bank", "title", "新题库", "description", "练习")));
        long question = id(call(args("question", "q", "bankId", bank, "questionType", "single", "stem", "1+1？", "answer", "b",
                "options", List.of(Map.of("key", "A", "text", "1"), Map.of("key", "B", "text", "2")), "analysis", "加法", "difficulty", 2)));
        assertEquals("B", questions.findById(question).answer);
        long point = id(call(args("knowledge", "k", "bankId", bank, "title", "加法", "content", "## 知识\n$x+y$",
                "questionIds", List.of(question), "headingPath", List.of("第一章"))));
        var saved = new KnowledgePointRepository(jdbc, json);
        assertEquals(List.of(question), saved.questionIds(point));
        assertTrue(saved.findById(point).content.contains("$x+y$"));
        long unfiled = id(call(args("knowledge", "k2", "title", "未归属", "content", "内容")));
        assertNull(saved.findById(unfiled).bankId);
    }

    @Test void manualWrongQuestionHasZeroAttemptsAndLeavesAfterTwoRealCorrectAnswers() throws Exception {
        long question = id(call(args("wrong", "w", "bankId", 1, "questionType", "essay", "stem", "解释导数")));
        assertEquals(0, count("answer_record"));
        var wrong = questions.wrongBook().get(0);
        assertEquals(0, wrong.get("wrongCount")); assertEquals(0, wrong.get("attemptCount"));
        assertTrue(MaterialReadTools.tool("read_wrong_questions", reads).execute(null, "{}", false, "").toString().contains("wrongCount=0"));
        questions.recordAnswer(question, "回答", true, 0, "session", null, null);
        assertTrue(questions.inWrongBook(question));
        assertEquals("NO_CHANGE", call(args("wrong", "already", "questionId", question)).path("status").asText());
        questions.recordAnswer(question, "回答", true, 0, "session", null, null);
        assertFalse(questions.inWrongBook(question));
        assertEquals("NOT_FOUND", call(args("wrong", "again", "questionId", question)).path("status").asText());
        jdbc.update("UPDATE ai_chat_turn SET status='COMPLETED' WHERE id=1"); insertTurn("new-message", null, 2);
        invocation = new AiService.ToolInvocation("new-message", "c", 2, 1, active.acquire("new-message", 1).orElseThrow());
        id(call(args("wrong", "again", "questionId", question)));
        assertTrue(questions.inWrongBook(question)); assertEquals(2, count("answer_record"));
        questions.setWrongExcluded(question, true); assertFalse(questions.inWrongBook(question));
        questions.recordAnswer(question, "错误", false, 0, "session", null, null);
        assertTrue(questions.inWrongBook(question));
    }

    @Test void addingExistingQuestionDoesNotEditOrCopyIt() throws Exception {
        long question = id(call(args("question", "q", "bankId", 1, "questionType", "fill", "stem", "2+2", "answer", "4")));
        long added = id(call(args("wrong", "w", "questionId", question)));
        assertEquals(question, added); assertEquals(1, count("question"));
        assertEquals("4", questions.findById(question).answer); assertEquals(0, count("answer_record"));
        assertEquals("INVALID_ARGUMENT", call(args("wrong", "bad", "questionId", question, "stem", "改掉原题")).path("status").asText());
    }

    @Test void repeatedOperationAndChangedToolCallIdNeverDuplicate() throws Exception {
        var input = args("note", "save", "notebookId", 1, "title", "同名", "content", "内容");
        long page = id(call(input));
        invocation = new AiService.ToolInvocation("request", "different-call", 1, 1, invocation.handle());
        assertEquals("DUPLICATE", call(input).path("status").asText());
        input.put("operationId", "changed-id");
        assertEquals("DUPLICATE", call(input).path("status").asText()); assertEquals(1, count("note_page"));
        input.put("operationId", "save"); input.put("content", "另一个内容");
        assertEquals("CONFLICT", call(input).path("status").asText());
        assertTrue(new NotebookRepository(jdbc, json).findPage(page).get("content").toString().contains("内容"));
    }

    @Test void explicitTurnRetryReusesCommittedReceiptAndDeletedMaterialIsNotRecreated() throws Exception {
        var input = args("note", "save", "notebookId", 1, "title", "总结", "content", "内容");
        long page = id(call(input));
        jdbc.update("UPDATE ai_chat_turn SET status='FAILED' WHERE id=1"); insertTurn("retry", "request", 2);
        invocation = new AiService.ToolInvocation("retry", "retry-call", 2, 1, active.acquire("retry", 1).orElseThrow());
        assertEquals("DUPLICATE", call(input).path("status").asText()); assertEquals(1, count("note_page"));
        jdbc.update("DELETE FROM note_page WHERE id=?", page);
        assertEquals("NOT_FOUND", call(input).path("status").asText()); assertEquals(0, count("note_page"));
    }

    @Test void concurrentReplayCreatesExactlyOneOrdinaryObject() throws Exception {
        var input = args("bank", "bank", "title", "并发题库");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<JsonNode> work = () -> { gate.await(); return call(input); };
            var a = pool.submit(work); var b = pool.submit(work); gate.countDown();
            var outcomes = Set.of(a.get(10, TimeUnit.SECONDS).path("status").asText(), b.get(10, TimeUnit.SECONDS).path("status").asText());
            assertEquals(Set.of("CREATED", "DUPLICATE"), outcomes); assertEquals(3, count("question_bank"));
        } finally { pool.shutdownNow(); }
    }

    @Test void invalidReferencesAndIndexFailureRollbackReceiptAndPartialObjects() throws Exception {
        assertEquals("NOT_FOUND", call(args("note", "missing", "notebookId", 99, "title", "a", "content", "b")).path("status").asText());
        jdbc.update("INSERT INTO note_page(id,notebook_id,title) VALUES(42,1,'父页')");
        jdbc.update("INSERT INTO notebook(id,title) VALUES(2,'别的本')");
        assertEquals("NOT_FOUND", call(args("note", "parent", "notebookId", 2, "parentPageId", 42, "title", "a", "content", "b")).path("status").asText());
        assertEquals("INVALID_ARGUMENT", call(args("knowledge", "link", "bankId", 1, "title", "a", "content", "b", "questionIds", List.of(999))).path("status").asText());
        jdbc.execute("CREATE TRIGGER fail_chunk BEFORE INSERT ON retrieval_chunk BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertEquals("UNAVAILABLE", call(args("note", "index", "notebookId", 1, "title", "a", "content", "b")).path("status").asText());
        assertEquals(1, count("note_page")); assertEquals(0, count("knowledge_point")); assertEquals(0, count("ai_material_creation"));
    }

    @Test void cancellationAndStaleSessionRejectBeforeWriting() throws Exception {
        invocation.handle().requestCancel();
        assertEquals("CANCELLED", call(args("bank", "b", "title", "a")).path("status").asText());
        active.release("request", invocation.handle());
        invocation = new AiService.ToolInvocation("request", "c", 1, 1, active.acquire("request", 1).orElseThrow());
        jdbc.update("UPDATE ai_chat_session SET context_generation=2 WHERE id=1");
        assertEquals("STALE", call(args("bank", "b", "title", "a")).path("status").asText());
        assertEquals(2, count("question_bank")); assertEquals(0, count("ai_material_creation"));
    }

    @Test void strictValidationRejectsUnknownFieldsTypesDuplicateKeysAndLargeContent() throws Exception {
        for (String raw : List.of("{\"type\":\"bank\",\"type\":\"note\"}", "{} {}", "{\"type\":\"bank\",\"operationId\":\"x\",\"title\":9}",
                "{\"type\":\"bank\",\"operationId\":\"x\",\"title\":\"a\",\"path\":\"C:/\"}"))
            assertEquals("INVALID_ARGUMENT", tool.execute(plan, raw, false, "", invocation).get("status"));
        assertEquals("INVALID_ARGUMENT", call(args("note", "huge", "notebookId", 1, "title", "a", "content", "x".repeat(12001))).path("status").asText());
        assertEquals("INVALID_ARGUMENT", call(args("question", "q", "bankId", 1, "questionType", "single", "stem", "x", "answer", "A")).path("status").asText());
        assertEquals("INVALID_ARGUMENT", call(args("question", "q", "bankId", 1, "questionType", "essay", "stem", "x", "difficulty", 6)).path("status").asText());
        assertEquals(0, count("ai_material_creation"));
    }

    @Test void upgradeFrom14KeepsExistingWrongBookAndAddsOnlyEmptyReceiptState() throws Exception {
        jdbc.execute("DROP TABLE ai_material_creation");
        jdbc.execute("ALTER TABLE question DROP COLUMN wrong_manual_after");
        jdbc.update("INSERT INTO question(id,bank_id,type,stem,answer) VALUES(1,1,'fill','原题','答案')");
        jdbc.update("INSERT INTO answer_record(question_id,is_correct) VALUES(1,0)");
        jdbc.update("UPDATE schema_version SET version=14");
        new DatabaseInitializer(jdbc.getDataSource(), Schema9Fixture.resolver(root)).initialize();
        assertEquals(DatabaseInitializer.currentSchemaVersion(), jdbc.queryForObject("SELECT version FROM schema_version", Integer.class));
        assertEquals(1, questions.wrongBook().size()); assertEquals(1, count("answer_record"));
        assertNull(jdbc.queryForObject("SELECT wrong_manual_after FROM question WHERE id=1", Long.class));
        assertEquals(0, count("ai_material_creation"));
    }
}
