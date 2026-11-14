package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.memory.Schema9Fixture;
import com.drillnotebook.app.memory.TestWorkspace;
import com.drillnotebook.app.repository.MaterialReadRepository;
import com.drillnotebook.app.repository.EmbeddingJobRepository;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Deterministic tool integration, not a live-model accuracy benchmark. */
class MaterialHybridSearchTest {
    static final String SPACE = "e".repeat(64);
    private static final String QUERY = "如何理解物体受力平衡";
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private NotebookRepository notebooks;
    private NoteIndexingService indexing;
    private EmbeddingProviderRegistry registry;
    private CountingProvider provider;
    private HybridRetrievalService hybrid;
    private MaterialReadService reads;

    @BeforeEach void setup() throws Exception {
        root = TestWorkspace.scratchDirectory("material-hybrid-");
        var ds = Schema9Fixture.dataSource(root);
        new DatabaseInitializer(ds, Schema9Fixture.resolver(root)).initialize();
        jdbc = new JdbcTemplate(ds);
        notebooks = new NotebookRepository(jdbc, json);
        notebooks.insert("力学笔记"); notebooks.insert("其它笔记本");
        indexing = new NoteIndexingService(notebooks, new RetrievalIndexRepository(jdbc), jdbc, json);
        registry = new EmbeddingProviderRegistry();
        provider = new CountingProvider();
        hybrid = MaterialReadTestSupport.hybrid(jdbc, json, registry, 1500, 25);
        reads = MaterialReadTestSupport.service(jdbc, json, hybrid);
    }

    @AfterEach void cleanup() throws Exception {
        hybrid.shutdown();
        TestWorkspace.deleteRecursively(root);
    }

    static class CountingProvider extends HybridRetrievalServiceTest.FakeProvider {
        final AtomicInteger calls = new AtomicInteger();
        String model = "test/model";
        CountingProvider() { super(4, new float[]{1, 0, 0, 0}); }
        @Override public String modelId() { return model; }
        @Override public List<Float> embedQuery(String text) throws EmbeddingProviderException {
            calls.incrementAndGet(); return super.embedQuery(text);
        }
    }

    private long note(long notebookId, String title, String text) {
        var doc = Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content",
                List.of(Map.of("type", "text", "text", text)))));
        long id = notebooks.insertPage(notebookId, title, doc);
        indexing.savePageAndIndex(id, title, doc);
        return id;
    }

    private void enableVectors() {
        jdbc.update("INSERT INTO embedding_space(embedding_space_id,canonical_contract_json,provider_type,model_identifier,dimensions,state,coverage,is_selected)"
                + " VALUES(?,'{}','local-rust','test/model',4,'ACTIVE',1,1)", SPACE);
        registry.setActive(provider);
    }

    private void vector(long page, float... values) {
        var raw = new ArrayList<Float>(); for (float value : values) raw.add(value);
        for (var chunk : jdbc.queryForList("SELECT id,content_hash FROM retrieval_chunk WHERE source_id=? AND corpus_type='NOTEBOOK'", page))
            jdbc.update("INSERT INTO retrieval_embedding(chunk_id,corpus_type,embedding_space_id,dimensions,content_hash,vector_blob) VALUES(?,'NOTEBOOK',?,4,?,?)",
                    chunk.get("id"), SPACE, chunk.get("content_hash"), EmbeddingVectorCodec.encode(raw, 4));
    }

    private JsonNode call(String name, Map<String, Object> args) throws Exception {
        return json.valueToTree(MaterialReadTools.tool(name, reads).execute(null, json.writeValueAsString(args), false, ""));
    }

    private JsonNode search(Map<String, Object> args) throws Exception { return call("search_materials", args); }
    private List<Long> ids(JsonNode result) {
        var ids = new ArrayList<Long>(); for (var row : result.path("items")) ids.add(row.path("id").asLong()); return ids;
    }

    @Test void springRegistersTheSearchToolWithTheExistingEmbeddingSelection() {
        long page = note(1, "合力", "所有外力互相抵消，速度保持恒定。");
        enableVectors(); vector(page, 1, 0, 0, 0);
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> json)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NotebookRepository.class, () -> notebooks)
                .withBean(MaterialReadRepository.class, () -> new MaterialReadRepository(jdbc))
                .withBean(RetrievalIndexRepository.class, () -> new RetrievalIndexRepository(jdbc))
                .withBean(EmbeddingJobRepository.class, () -> new EmbeddingJobRepository(jdbc))
                .withBean(EmbeddingProviderRegistry.class, () -> registry)
                .withUserConfiguration(MaterialReadTools.class, MaterialReadService.class, MaterialSearchService.class,
                        RetrievalService.class, HybridRetrievalService.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var result = json.valueToTree(context.getBean("searchMaterials", AiService.ChatTool.class)
                            .execute(null, "{\"type\":\"note\",\"query\":\"" + QUERY + "\"}", false, ""));
                    assertEquals(List.of(page), ids(result));
                    assertEquals(List.of("vector"), json.convertValue(result.path("items").get(0).path("matchTypes"), List.class));
                });
    }

    @Test void naturalLanguageUsesRrfAndIncludesVectorOnlyPageWithReadableLocators() throws Exception {
        long lexical = note(1, "静力学", "物体受力平衡时合力等于零。静止也属于运动状态不变。");
        long semantic = note(1, "合力", "所有外力互相抵消，速度保持恒定。");
        enableVectors(); vector(lexical, .8f, .2f, 0, 0); vector(semantic, 1, 0, 0, 0);
        var result = search(Map.of("type", "note", "query", QUERY, "notebookId", 1));
        assertEquals(List.of(lexical, semantic), ids(result), "two-sided RRF beats the vector-only page");
        assertEquals(List.of("bm25", "vector"), json.convertValue(result.path("items").get(0).path("matchTypes"), List.class));
        var row = result.path("items").get(1);
        assertEquals(List.of("vector"), json.convertValue(row.path("matchTypes"), List.class));
        assertEquals(1, row.path("notebookId").asInt()); assertEquals(semantic, row.path("pageId").asLong());
        assertEquals("力学笔记", row.path("parentTitle").asText());
        assertEquals(MaterialReadService.link("note", semantic), row.path("sourceLink").asText());
        assertTrue(row.path("snippet").asText().contains("外力互相抵消"));
        assertFalse(row.has("text")); assertFalse(row.has("chunkHash"));
        assertEquals(1, provider.calls.get()); assertFalse(result.path("incomplete").asBoolean());
        assertTrue(call("read_notebook", Map.of("pageId", semantic)).toString().contains("速度保持恒定"));
        assertEquals(1, provider.calls.get(), "ID reads never embed a query");
    }

    @Test void literalMatchesStayFirstAndKeywordModePreservesSymbolsWithoutCallingEmbedding() throws Exception {
        long exact = note(1, "精确编号 A%_01", "前面的介绍。".repeat(80) + "原文定位A%_01的解释。");
        long similar = note(1, "相似编号 ABC01", "语义候选的不同内容。");
        enableVectors(); vector(exact, 0, 1, 0, 0); vector(similar, 1, 0, 0, 0);
        var hybridResult = search(Map.of("type", "note", "query", "A%_01"));
        assertEquals(exact, ids(hybridResult).get(0));
        assertTrue(hybridResult.path("items").get(0).path("snippet").asText().contains("A%_01"), "excerpt follows the body match");
        assertTrue(hybridResult.path("items").get(0).path("matchTypes").toString().contains("keyword"));
        int before = provider.calls.get();
        assertEquals(List.of(exact), ids(search(Map.of("type", "note", "query", "A%_01", "searchMode", "keyword"))));
        assertEquals(before, provider.calls.get());
        assertEquals("INVALID_ARGUMENT", search(Map.of("searchMode", "vector")).path("status").asText());
        assertEquals("INVALID_ARGUMENT", search(Map.of("query", "😀".repeat(201))).path("status").asText());
    }

    @Test void directoriesExplicitIdsAndOtherMaterialTypesDoNotRequestVectors() throws Exception {
        long page = note(1, "静力学", "物体受力平衡时合力等于零。");
        jdbc.update("INSERT INTO question_bank(id,name) VALUES(1,'力学题库')");
        jdbc.update("INSERT INTO knowledge_point(id,bank_id,title,content) VALUES(1,1,'力学知识','质点模型')");
        enableVectors(); vector(page, 1, 0, 0, 0);
        assertEquals("OK", search(Map.of("type", "note", "query", "")).path("status").asText());
        assertEquals("OK", call("read_notebook", Map.of("notebookId", 1)).path("status").asText());
        assertEquals("OK", call("read_notebook", Map.of("pageId", page)).path("status").asText());
        assertEquals("OK", search(Map.of("type", "knowledge", "query", "质点")).path("status").asText());
        assertEquals("OK", search(Map.of("type", "all", "bankId", 1, "query", "质点")).path("status").asText());
        assertEquals("NO_MATCH", search(Map.of("type", "notebook", "query", "未找到的容器")).path("status").asText());
        assertEquals(0, provider.calls.get());
    }

    @ParameterizedTest @CsvSource({"REBUILDING,1", "ACTIVE,0.5", "DISABLED,1", "UNINSTALLING,1"})
    void unavailableSpaceDegradesToBm25InsteadOfFailingTheTool(String state, double coverage) throws Exception {
        long page = note(1, "静力学", "物体受力平衡时合力等于零。");
        enableVectors(); vector(page, 1, 0, 0, 0);
        jdbc.update("UPDATE embedding_space SET state=?,coverage=?", state, coverage);
        var result = search(Map.of("type", "note", "query", QUERY));
        assertEquals(List.of(page), ids(result));
        assertEquals(List.of("bm25"), json.convertValue(result.path("items").get(0).path("matchTypes"), List.class));
        assertEquals("vector-index-unavailable", result.path("retrievalNotice").path("code").asText());
        assertTrue(result.path("incomplete").asBoolean()); assertEquals(0, provider.calls.get());
    }

    @Test void missingProviderWorkerFailureAndModelMismatchAllKeepBm25Results() throws Exception {
        long page = note(1, "静力学", "物体受力平衡时合力等于零。");
        enableVectors(); vector(page, 1, 0, 0, 0);
        for (String failure : List.of("missing", "unhealthy", "wrong-model", "crash")) {
            registry.setActive(provider); provider.available = true; provider.fail = false; provider.model = "test/model";
            switch (failure) {
                case "missing" -> registry.clear();
                case "unhealthy" -> provider.available = false;
                case "wrong-model" -> provider.model = "other/model";
                case "crash" -> provider.fail = true;
            }
            var result = search(Map.of("type", "note", "query", QUERY));
            assertEquals(List.of(page), ids(result), failure);
            assertEquals("vector-index-unavailable", result.path("retrievalNotice").path("code").asText(), failure);
        }
    }

    @Test void slowVectorHasABoundedWaitAndNeverTurnsAReadableNoteIntoAnError() throws Exception {
        long page = note(1, "静力学", "物体受力平衡时合力等于零。");
        enableVectors(); vector(page, 1, 0, 0, 0);
        hybrid.shutdown(); hybrid = MaterialReadTestSupport.hybrid(jdbc, json, registry, 100, 25);
        reads = MaterialReadTestSupport.service(jdbc, json, hybrid); provider.sleepMs = 10_000;
        long started = System.nanoTime();
        var result = search(Map.of("type", "note", "query", QUERY));
        assertEquals(List.of(page), ids(result));
        assertEquals("vector-index-unavailable", result.path("retrievalNotice").path("code").asText());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000);
    }

    @Test void unconfiguredEmbeddingStillUsesBm25ForNaturalLanguage() throws Exception {
        long page = note(1, "静力学", "物体受力平衡时合力等于零。");
        registry.setActive(provider);
        var result = search(Map.of("type", "note", "query", QUERY));
        assertEquals(List.of(page), ids(result)); assertFalse(result.has("retrievalNotice"));
        assertEquals(0, provider.calls.get());
    }

    @Test void notebookScopeStaleVectorsAndOrphanChunksCannotLeakCandidates() throws Exception {
        long good = note(1, "有效", "所有外力相互抵消，速度保持恒定。");
        long stale = note(1, "旧索引", "另一份正在修改的正文。");
        long outside = note(2, "外部", "另一个笔记本的私有内容。");
        enableVectors(); vector(good, 1, 0, 0, 0); vector(stale, 1, 0, 0, 0); vector(outside, 1, 0, 0, 0);
        jdbc.update("UPDATE retrieval_embedding SET content_hash='stale' WHERE chunk_id IN (SELECT id FROM retrieval_chunk WHERE source_id=?)", stale);
        jdbc.update("INSERT INTO retrieval_chunk(corpus_type,corpus_id,source_id,chunk_index,title,text,start_offset,end_offset,content_hash) VALUES('NOTEBOOK',1,999,0,'已删除','孤立旧正文',0,5,'orphan')");
        vector(999, 1, 0, 0, 0);
        var result = search(Map.of("type", "all", "query", QUERY, "notebookId", 1));
        assertEquals(List.of(good), ids(result));
        assertFalse(result.toString().contains("私有内容")); assertFalse(result.toString().contains("孤立旧正文"));
        assertEquals("NOT_FOUND", search(Map.of("notebookId", 99, "query", QUERY)).path("status").asText());
    }

    @Test void pageMovedDuringSearchIsRevalidatedBeforeReturningItsSnippet() throws Exception {
        long page = note(1, "即将移动", "只应在原笔记本检索的内容。");
        enableVectors(); vector(page, 1, 0, 0, 0);
        var materials = new MaterialReadRepository(jdbc) {
            @Override public List<Map<String, Object>> noteSearchTargets(List<Long> ids, Long notebookId) {
                jdbc.update("UPDATE note_page SET notebook_id=2 WHERE id=?", page);
                return super.noteSearchTargets(ids, notebookId);
            }
        };
        reads = new MaterialReadService(materials, json, new MaterialSearchService(materials, hybrid));
        var result = search(Map.of("type", "note", "query", QUERY, "notebookId", 1));
        assertEquals("NO_MATCH", result.path("status").asText()); assertTrue(ids(result).isEmpty());
    }

    @Test void pageDeduplicationHappensBeforePagingAndIsNotRestrictedToTenChunks() throws Exception {
        var pages = new ArrayList<Long>();
        for (int i = 0; i < 15; i++) pages.add(note(1, "语义页" + i,
                "所有外力互相抵消。" + (i == 0 ? "长段内容。".repeat(1300) : "补充内容" + i)));
        enableVectors(); for (long page : pages) vector(page, 1, 0, 0, 0);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM retrieval_chunk WHERE source_id=?", Integer.class, pages.get(0)) > 1);
        var seen = new ArrayList<Long>();
        for (int offset = 0; offset < 15; offset += 5) {
            var result = search(Map.of("type", "note", "query", QUERY, "limit", 5, "offset", offset));
            seen.addAll(ids(result));
            assertEquals(offset < 10, result.path("hasMore").asBoolean());
            if (offset < 10) assertEquals(offset + 5, result.path("nextOffset").asInt());
        }
        assertEquals(pages, seen); assertEquals(15, seen.stream().distinct().count());
    }

    @Test void excerptsHaveAUnicodeBudgetAndDuplicateBodiesKeepTheirSourceIds() throws Exception {
        var pages = new ArrayList<Long>();
        for (int i = 0; i < 20; i++) pages.add(note(1, "正文页" + i,
                "内容" + (i == 1 ? 0 : i) + "😀".repeat(700)));
        enableVectors(); for (long page : pages) vector(page, 1, 0, 0, 0);
        var result = search(Map.of("type", "note", "query", QUERY, "limit", 20));
        assertEquals(pages, ids(result));
        int total = 0;
        for (var row : result.path("items")) {
            String snippet = row.path("snippet").asText();
            int count = snippet.codePointCount(0, snippet.length()); total += count;
            assertTrue(count <= 240);
            assertFalse(snippet.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF));
            assertFalse(row.has("text"));
        }
        assertTrue(total <= 4800);
        assertEquals("", result.path("items").get(1).path("snippet").asText());
        assertEquals(MaterialReadService.link("note", pages.get(0)), result.path("items").get(1).path("snippetDuplicateOf").asText());
    }

    @Test void longUnicodeMetadataFitsTheToolByteLimitAndResumesWithoutMissingPages() throws Exception {
        jdbc.update("UPDATE notebook SET title=? WHERE id=1", "😀".repeat(200));
        var pages = new ArrayList<Long>();
        for (int i = 0; i < 20; i++) pages.add(note(1, i + "😀".repeat(200), "内容" + i + "😀".repeat(700)));
        jdbc.update("UPDATE retrieval_chunk SET heading_path=?", "😀".repeat(200));
        enableVectors(); for (long page : pages) vector(page, 1, 0, 0, 0);
        var seen = new ArrayList<Long>();
        int offset = 0;
        do {
            var result = search(Map.of("type", "note", "query", QUERY, "limit", 20, "offset", offset));
            assertTrue(json.writeValueAsBytes(result).length <= 60 * 1024, "leave room below the shared 64 KiB limit");
            assertFalse(ids(result).isEmpty()); seen.addAll(ids(result));
            if (offset == 0) { assertTrue(ids(result).size() < 20); assertTrue(result.path("byteLimited").asBoolean()); }
            if (!result.path("hasMore").asBoolean()) break;
            int next = result.path("nextOffset").asInt();
            assertEquals(offset + ids(result).size(), next); offset = next;
        } while (seen.size() < 25);
        assertEquals(pages, seen);
    }

    @Test void cappedSemanticCandidatesAreExplicitAndKeywordDirectoriesRemainFullyPaged() throws Exception {
        var pages = new ArrayList<Long>();
        for (int i = 0; i < 85; i++) pages.add(note(1, "共同标题" + i, "候选正文" + i));
        enableVectors(); for (long page : pages) vector(page, 1, 0, 0, 0);
        var vectorLast = search(Map.of("type", "note", "query", QUERY, "limit", 20, "offset", 20));
        assertEquals(20, ids(vectorLast).size()); assertFalse(vectorLast.path("hasMore").asBoolean());
        assertTrue(vectorLast.path("searchLimited").asBoolean()); assertTrue(vectorLast.path("incomplete").asBoolean());
        var literalLast = search(Map.of("type", "note", "query", "共同标题", "limit", 20, "offset", 60));
        assertEquals(20, ids(literalLast).size()); assertFalse(literalLast.path("hasMore").asBoolean());
        assertTrue(literalLast.path("searchLimited").asBoolean());
        int before = provider.calls.get();
        var keywordLast = search(Map.of("type", "note", "query", "共同标题", "searchMode", "keyword", "limit", 20, "offset", 80));
        assertEquals(pages.subList(80, 85), ids(keywordLast)); assertFalse(keywordLast.path("incomplete").asBoolean());
        assertEquals(before, provider.calls.get());
    }
}
