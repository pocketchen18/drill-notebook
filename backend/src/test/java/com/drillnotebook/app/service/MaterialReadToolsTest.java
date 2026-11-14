package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MaterialReadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class MaterialReadToolsTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private MaterialReadService service;
    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("material-read-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds);
        service = MaterialReadTestSupport.service(jdbc, json);
        jdbc.update("INSERT INTO notebook(id,title) VALUES(1,'同名'),(2,'同名')");
        jdbc.update("INSERT INTO question_bank(id,name) VALUES(1,'数学')");
        jdbc.update("INSERT INTO note_page(id,notebook_id,title,content,parent_id) VALUES(1,1,'微积分',?,NULL),(2,1,'子页',?,1)",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文😀测试\"},{\"type\":\"mathInline\",\"attrs\":{\"latex\":\"x^2\"}}]}]}", "{\"type\":\"doc\"}");
        jdbc.update("INSERT INTO knowledge_point(id,bank_id,title,content,heading_path) VALUES(1,1,'定理','当前卡片','[\"第一章\"]')");
        jdbc.update("INSERT INTO knowledge_point_original(point_id,role,content) VALUES(1,'original','原文'),(1,'summary','总结')");
        for (int id=1; id<=4; id++) jdbc.update("INSERT INTO question(id,bank_id,type,stem,options,answer,analysis,chapter) VALUES(?,1,'single',?,'[{\"key\":\"A\",\"text\":\"选项\"}]','A','解析','一')", id, "题目"+id);
        jdbc.update("INSERT INTO answer_record(question_id,is_correct) VALUES(1,0),(1,1),(2,0),(2,1),(2,1),(3,0)");
        jdbc.update("UPDATE question SET wrong_excluded=1 WHERE id=3");
    }
    @AfterEach void cleanup() throws Exception { TestWorkspace.deleteRecursively(root); }
    private JsonNode call(String name, String arguments) {
        return json.valueToTree(MaterialReadTools.tool(name,service).execute(null,arguments,false,""));
    }
    private JsonNode page(String args, Map<String,PageSnapshot> snapshots) {
        return json.valueToTree(MaterialReadTools.tool("read_current_page",service).execute(null,args,false,"",
                new AiService.ToolInvocation("main","call",1,1,null,snapshots)));
    }
    @Test void discoversAmbiguousNamesAndPaginatesWithoutLoadingBodies() {
        var first=call("search_materials","{\"type\":\"notebook\",\"query\":\"同名\",\"limit\":1}");
        assertEquals("OK",first.path("status").asText()); assertTrue(first.path("hasMore").asBoolean());
        assertEquals(1,first.path("nextOffset").asInt()); assertEquals(1,first.path("items").get(0).path("id").asInt());
        var second=call("search_materials","{\"type\":\"notebook\",\"query\":\"同名\",\"limit\":1,\"offset\":1}");
        assertEquals(2,second.path("items").get(0).path("id").asInt()); assertFalse(second.path("hasMore").asBoolean());
        assertTrue(call("read_notebook","{\"notebookId\":1}").toString().contains("parentPageId\":1"));
        assertFalse(call("read_notebook","{\"notebookId\":1}").toString().contains("正文"));
        assertEquals("OK",call("search_materials","{}").path("status").asText());
    }
    @Test void searchesExistingNoteIndexAndEscapesWildcardInput() {
        jdbc.update("INSERT INTO retrieval_chunk(corpus_type,corpus_id,source_id,chunk_index,title,text,start_offset,end_offset,content_hash) VALUES('NOTEBOOK',1,1,0,'微积分','独特关键词',0,5,'hash')");
        var hit=call("search_materials","{\"type\":\"note\",\"query\":\"独特关键词\",\"notebookId\":1}");
        assertEquals(1,hit.path("items").get(0).path("id").asInt());
        assertEquals("NO_MATCH",call("search_materials","{\"query\":\"%_' OR 1=1\"}").path("status").asText());
        assertEquals("NO_MATCH",call("search_materials","{\"type\":\"note\",\"query\":\"独特关键词\",\"notebookId\":2}").path("status").asText());
    }
    @Test void noteReadsAreUnicodePagedVersionedAndDoNotMixEdits() throws Exception {
        var first=call("read_notebook","{\"pageId\":1,\"maxChars\":3}");
        assertEquals("正文😀",first.path("items").get(0).path("text").asText());
        assertTrue(first.path("hasMore").asBoolean());
        var args=new LinkedHashMap<String,Object>(Map.of("pageId",1,"offset",3,"version",first.path("version").asText()));
        assertTrue(call("read_notebook",json.writeValueAsString(args)).toString().contains("$x^2$"));
        assertEquals("INVALID_ARGUMENT",call("read_notebook","{\"pageId\":1,\"offset\":3}").path("status").asText());
        jdbc.update("UPDATE note_page SET content=? WHERE id=1","{\"type\":\"doc\",\"content\":[]}");
        assertEquals("STALE",call("read_notebook",json.writeValueAsString(args)).path("status").asText());
    }
    @Test void notePreservesQuestionAndFileReferencesWithoutOpeningFiles() {
        jdbc.update("UPDATE note_page SET content=? WHERE id=1", "{\"type\":\"doc\",\"content\":[{\"type\":\"fileBlock\",\"attrs\":{\"fileName\":\"课件.pdf\"}},{\"type\":\"questionBlock\",\"attrs\":{\"questionId\":1,\"snapshot\":{\"stem\":\"嵌入题\",\"answer\":\"旧答案\"}}}]}");
        String output=call("read_notebook","{\"pageId\":1}").toString();
        assertTrue(output.contains("课件.pdf")); assertTrue(output.contains("未读取文件正文"));
        assertTrue(output.contains("questionId=1")); assertTrue(output.contains("旧答案"));
        jdbc.update("UPDATE note_page SET content='invalid json' WHERE id=1");
        assertEquals("UNAVAILABLE",call("read_notebook","{\"pageId\":1}").path("status").asText());
    }
    @Test void knowledgeVersionsRemainDistinctAndMissingVersionIsExplicit() {
        for(var pair: Map.of("current","当前卡片","original","原文","summary","总结").entrySet()) {
            var result=call("read_knowledge","{\"pointId\":1,\"variant\":\""+pair.getKey()+"\"}");
            assertEquals(pair.getValue(),result.path("items").get(0).path("text").asText());
        }
        jdbc.update("DELETE FROM knowledge_point_original WHERE role='summary'");
        assertEquals("NOT_FOUND",call("read_knowledge","{\"pointId\":1,\"variant\":\"summary\"}").path("status").asText());
        assertEquals("OK",call("read_knowledge","{\"bankId\":1}").path("status").asText());
    }
    @Test void questionDetailsContainAnswerButCatalogDoesNot() {
        assertEquals("OK",call("read_question_bank","{}").path("status").asText());
        var listing=call("read_question_bank","{\"bankId\":1}");
        assertEquals(4,listing.path("items").size()); assertFalse(listing.toString().contains("解析"));
        var result=call("read_question_bank","{\"questionId\":1}");
        assertTrue(result.toString().contains("A. 选项")); assertTrue(result.toString().contains("答案：A"));
        assertTrue(result.toString().contains("解析：解析")); assertTrue(result.toString().contains("material/question/1"));
    }
    @Test void wrongBookUsesActualMembershipAndStatistics() {
        var result=call("read_wrong_questions","{}");
        assertEquals(1,result.path("items").size());
        var row=result.path("items").get(0);
        assertEquals(1,row.path("id").asInt()); assertEquals(2,row.path("attemptCount").asInt());
        assertEquals(1,row.path("wrongCount").asInt()); assertEquals(.5,row.path("errorRate").asDouble());
        assertEquals("OK",call("read_wrong_questions","{\"questionId\":1}").path("status").asText());
        for(int id:List.of(2,3,4)) assertEquals("NOT_FOUND",call("read_wrong_questions","{\"questionId\":"+id+"}").path("status").asText());
    }
    @Test void invalidArgumentsAndDeletedTargetsAreNotSuccessfulEmptyResults() {
        for(String args: List.of("{\"limit\":21}","{\"offset\":-1}","{\"query\":null}","{\"limit\":\"2\"}","{\"path\":\"C:/\"}","{\"limit\":1,\"limit\":2}","{} {}"))
            assertEquals("INVALID_ARGUMENT",call("search_materials",args).path("status").asText(),args);
        assertEquals("NOT_FOUND",call("read_notebook","{\"pageId\":999}").path("status").asText());
        assertEquals("NOT_FOUND",call("read_notebook","{\"pageId\":1,\"notebookId\":2}").path("status").asText());
        assertEquals("NOT_FOUND",call("read_knowledge","{\"bankId\":99}").path("status").asText());
    }
    @Test void currentPageSnapshotIsBoundToMainOrExplicitSupplementAndRejectsDeletion() {
        var main=PageSnapshot.from(Map.of("kind","note","title","草稿","capturedAt","2026-11-09T10:00:00Z","pageId",1,"markdown","未保存文本","isDraft",true));
        var supplement=PageSnapshot.from(Map.of("kind","quiz","title","下一题","capturedAt","2026-11-09T10:00:01Z","questionId",1,"markdown","只有题面"));
        var snapshots=Map.of("main",main,"supplement",supplement);
        assertTrue(page("{}",snapshots).toString().contains("未保存文本"));
        assertTrue(page("{}",snapshots).path("items").get(0).path("isDraft").asBoolean());
        assertTrue(page("{\"requestId\":\"supplement\"}",snapshots).toString().contains("只有题面"));
        assertFalse(page("{\"requestId\":\"supplement\"}",snapshots).toString().contains("答案"));
        assertEquals("UNAVAILABLE",page("{\"requestId\":\"other\"}",snapshots).path("status").asText());
        jdbc.update("DELETE FROM note_page WHERE id=1");
        assertEquals("NOT_FOUND",page("{}",snapshots).path("status").asText());
    }
    @Test void snapshotFingerprintIncludesBodyButLocatorDoesNot() {
        var body=new HashMap<String,Object>(Map.of("kind","manual","capturedAt","2026-11-09T10:00:00Z","markdown","私有正文","selection","私有选区"));
        var first=PageSnapshot.from(body); String hash=first.fingerprint();
        assertFalse(first.locator("main",true).contains("私有"));
        body.put("markdown","另一份正文"); assertNotEquals(hash,PageSnapshot.from(body).fingerprint());
        body.put("path","C:/outside"); assertThrows(IllegalArgumentException.class,()->PageSnapshot.from(body));
    }
    @Test void emptyBusinessPageIsExplicitAndMissingSnapshotKindIsRejected() {
        var empty=PageSnapshot.from(Map.of("kind","none","title","设置","route","/settings","capturedAt","2026-11-09T10:00:00Z","markdown",""));
        var result=page("{}",Map.of("main",empty));
        assertEquals("OK",result.path("status").asText());
        assertEquals("",result.path("items").get(0).path("text").asText());
        assertTrue(result.path("items").get(0).path("notice").asText().contains("没有可读取"));
        assertThrows(IllegalArgumentException.class,()->PageSnapshot.from(Map.of("capturedAt","2026-11-09T10:00:00Z")));
    }
    @Test void settingsAndCalendarSnapshotsAreReadableWithoutMaterialIds() {
        for (String kind : List.of("settings", "calendar")) {
            String route = kind.equals("settings") ? "/settings?tab=about" : "/calendar?date=2026-11-14";
            String markdown = kind.equals("settings") ? "当前版本 v0.8.1；自动检查更新：开启；间隔 24 小时" : "2026-11-14；周末复习；极限知识点待完成";
            var snapshot = PageSnapshot.from(Map.of("kind", kind, "title", kind, "route", route,
                    "capturedAt", "2026-11-14T02:00:00Z", "markdown", markdown));
            var result = page("{}", Map.of("main", snapshot));
            assertEquals("OK", result.path("status").asText());
            var item = result.path("items").get(0);
            assertEquals(kind, item.path("kind").asText());
            assertEquals(route, item.path("route").asText());
            assertEquals(markdown, item.path("text").asText());
            assertTrue(item.path("snapshot").asBoolean());
            assertFalse(item.has("notice"));
            assertFalse(snapshot.locator("main", true).contains(markdown));
        }
    }
}
