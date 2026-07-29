package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drillnotebook.app.config.DatabaseInitializer;
import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.model.RetrievalQuery;
import com.drillnotebook.app.repository.NotebookRepository;
import com.drillnotebook.app.repository.RetrievalIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class RetrievalServiceTest {
    private JdbcTemplate jdbc;
    private NotebookRepository notebooks;
    private RetrievalService service;
    private long nextChunkId;

    @BeforeEach
    void setUp() throws Exception {
        var root = Files.createTempDirectory("retrieval-service-test");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + root.resolve("study.db"));
        new DatabaseInitializer(dataSource).initialize();
        jdbc = new JdbcTemplate(dataSource);
        notebooks = new NotebookRepository(jdbc, new ObjectMapper());
        service = new RetrievalService(new RetrievalIndexRepository(jdbc), notebooks);
        nextChunkId = 1;
    }

    @Test
    void matchesChineseAndEnglishTrigrams() {
        long notebookId = notebooks.insert("检索测试");
        insertChunk(notebookId, 101, 0, "中文向量检索", "人工智能", "正文包含向量检索能力");
        insertChunk(notebookId, 102, 0, "English retrieval", "Search", "Body contains deterministic ranking");

        assertEquals(101, retrieve("向量检", RetrievalQuery.Scope.ALL, null).get(0).sourceId());
        assertEquals(102, retrieve("retrieval", RetrievalQuery.Scope.ALL, null).get(0).sourceId());
    }

    @Test
    void titleMatchOutranksEquivalentBodyMatch() {
        long notebookId = notebooks.insert("权重测试");
        insertChunk(notebookId, 201, 0, "排序关键词", "", "完全相同的填充正文");
        insertChunk(notebookId, 202, 0, "普通标题", "", "完全相同的填充正文 排序关键词");

        List<RetrievalHit> hits = retrieve("排序关键词", RetrievalQuery.Scope.ALL, null);
        assertEquals(List.of(201L, 202L), hits.stream().map(RetrievalHit::sourceId).toList());
    }

    @Test
    void bm25TiesUseStableSourceAndChunkOrder() {
        long notebookId = notebooks.insert("稳定排序");
        insertChunk(notebookId, 302, 1, "稳定命中", "", "相同正文");
        insertChunk(notebookId, 301, 1, "稳定命中", "", "相同正文");
        insertChunk(notebookId, 301, 0, "稳定命中", "", "相同正文");

        List<String> expected = List.of("301:0", "301:1", "302:1");
        for (int run = 0; run < 3; run++) {
            assertEquals(expected, retrieve("稳定命中", RetrievalQuery.Scope.ALL, null).stream()
                    .map(hit -> hit.sourceId() + ":" + hit.chunkIndex()).toList());
        }
    }

    @Test
    void capsResultsAtForty() {
        long notebookId = notebooks.insert("上限测试");
        IntStream.range(0, 45).forEach(i ->
                insertChunk(notebookId, 400 + i, 0, "共同关键词", "", "正文"));

        List<RetrievalHit> hits = retrieve("共同关键词", RetrievalQuery.Scope.ALL, null);
        assertEquals(40, hits.size());
        assertEquals(400, hits.get(0).sourceId());
        assertEquals(439, hits.get(39).sourceId());
    }

    @Test
    void currentScopeIsIsolatedAndAllScopeSpansNotebooks() {
        long first = notebooks.insert("第一本");
        long second = notebooks.insert("第二本");
        insertChunk(first, 501, 0, "跨库检索", "", "第一本正文");
        insertChunk(second, 502, 0, "跨库检索", "", "第二本正文");

        assertEquals(List.of(501L), retrieve("跨库检索", RetrievalQuery.Scope.CURRENT, first)
                .stream().map(RetrievalHit::sourceId).toList());
        assertEquals(List.of(501L, 502L), retrieve("跨库检索", RetrievalQuery.Scope.ALL, first)
                .stream().map(RetrievalHit::sourceId).toList());
    }

    @Test
    void rejectsInvalidCurrentScopeBeforeEmptyQueryShortCircuit() {
        assertThrows(IllegalArgumentException.class,
                () -> retrieve("", RetrievalQuery.Scope.CURRENT, null));
        assertThrows(IllegalArgumentException.class,
                () -> retrieve("query", RetrievalQuery.Scope.CURRENT, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> retrieve("query", RetrievalQuery.Scope.CURRENT, 999L));
        assertThrows(IllegalArgumentException.class,
                () -> service.retrieve(new RetrievalQuery("query", null, null, RetrievalQuery.Corpus.NOTEBOOK)));
        assertThrows(IllegalArgumentException.class,
                () -> service.retrieve(new RetrievalQuery("query", RetrievalQuery.Scope.ALL, null, null)));
    }

    @Test
    void operatorLikeInputIsAlwaysSafeAndEmptyInputReturnsEmpty() {
        notebooks.insert("安全测试");
        assertDoesNotThrow(() -> retrieve("\" OR * NEAR(", RetrievalQuery.Scope.ALL, null));
        assertTrue(retrieve(null, RetrievalQuery.Scope.ALL, null).isEmpty());
        assertTrue(retrieve("   ", RetrievalQuery.Scope.ALL, null).isEmpty());
        assertTrue(retrieve("\"*(),。！", RetrievalQuery.Scope.ALL, null).isEmpty());
    }

    @Test
    void oneAndTwoCodepointQueriesUseLikeFallbackAndPreferMetadata() {
        long notebookId = notebooks.insert("短查询");
        insertChunk(notebookId, 601, 0, "算法标题", "", "普通正文");
        insertChunk(notebookId, 602, 0, "普通标题", "算法章节", "普通正文");
        insertChunk(notebookId, 603, 0, "普通标题", "", "正文包含算法");

        assertEquals(List.of(601L, 602L, 603L), retrieve("算法", RetrievalQuery.Scope.ALL, null)
                .stream().map(RetrievalHit::sourceId).toList());
        assertEquals(601, retrieve("算", RetrievalQuery.Scope.ALL, null).get(0).sourceId());
        assertEquals("%\\%\\_\\\\%", RetrievalService.buildLikePattern("%_\\"));
    }

    @Test
    void citationsAreCompleteRankedAndBoundedAroundMatch() {
        long notebookId = notebooks.insert("引用测试");
        String text = "前".repeat(300) + "确定性命中" + "后".repeat(300);
        long chunkId = insertChunk(notebookId, 701, 0, "引用标题", "章 / 节", text);

        RetrievalHit hit = retrieve("确定性命中", RetrievalQuery.Scope.ALL, null).get(0);
        Citation citation = hit.citation();
        assertEquals("NOTEBOOK", citation.corpusType());
        assertEquals(notebookId, citation.notebookId());
        assertEquals(701, citation.pageId());
        assertEquals(chunkId, citation.chunkId());
        assertEquals("引用标题", citation.title());
        assertEquals("章 / 节", citation.headingPath());
        assertEquals(List.of("bm25"), citation.matchTypes());
        assertEquals(1, citation.ftsRank());
        assertNull(citation.vectorRank());
        assertNull(citation.rrfScore());
        assertTrue(citation.snippet().codePointCount(0, citation.snippet().length()) <= 240);
        assertTrue(citation.snippet().contains("确定性命中"));
        assertTrue(citation.snippet().startsWith("…"));
        assertTrue(citation.snippet().endsWith("…"));
    }

    @Test
    void normalizesAndBuildsCanonicalCodepointShingles() {
        assertEquals("abc 中文 test", RetrievalService.normalizeQuery("  ＡＢＣ—中文，TEST  "));
        assertEquals("\"abc\" OR \"bcd\" OR \"cde\"",
                RetrievalService.buildMatchExpression("abcde"));
        assertEquals("\"aaa\"", RetrievalService.buildMatchExpression("aaaaa"));
        assertEquals("\"😀甲\"\"\"", RetrievalService.quoteFtsLiteral("😀甲\""));

        String supplementary = "😀甲乙丙";
        assertEquals("\"😀甲乙\" OR \"甲乙丙\"",
                RetrievalService.buildMatchExpression(supplementary));

        String many = "abcdefghijklmnopqrstuvwxyz0123456789";
        assertEquals(24, RetrievalService.buildMatchExpression(many).split(" OR ").length);
    }

    private List<RetrievalHit> retrieve(
            String text, RetrievalQuery.Scope scope, Long notebookId) {
        return service.retrieve(new RetrievalQuery(
                text, scope, notebookId, RetrievalQuery.Corpus.NOTEBOOK));
    }

    private long insertChunk(
            long notebookId,
            long sourceId,
            int chunkIndex,
            String title,
            String heading,
            String text) {
        long chunkId = nextChunkId++;
        jdbc.update(
                "INSERT INTO retrieval_chunk(id, corpus_type, corpus_id, source_id, chunk_index,"
                        + " title, heading_path, text, start_offset, end_offset, content_hash)"
                        + " VALUES (?, 'NOTEBOOK', ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                chunkId, notebookId, sourceId, chunkIndex, title, heading, text,
                text.length(), "hash-" + sourceId + "-" + chunkIndex);
        jdbc.update(
                "INSERT INTO retrieval_chunk_fts(rowid, title, heading_path, text) VALUES (?, ?, ?, ?)",
                chunkId, title, heading, text);
        return chunkId;
    }
}
