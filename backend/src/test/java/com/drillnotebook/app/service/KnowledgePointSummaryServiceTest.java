package com.drillnotebook.app.service;

import com.drillnotebook.app.model.KnowledgePointRecord;
import com.drillnotebook.app.repository.KnowledgePointOriginalRepository;
import com.drillnotebook.app.repository.KnowledgePointOriginalRepository.OriginalRecord;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgePointSummaryServiceTest {

    private KnowledgePointSummaryService svc;
    private KnowledgePointRepository points;
    private KnowledgePointOriginalRepository originals;
    private AiService ai;
    private KnowledgePointImportService importer;

    @BeforeEach
    void setup() {
        points = mock(KnowledgePointRepository.class);
        originals = mock(KnowledgePointOriginalRepository.class);
        ai = mock(AiService.class);
        importer = mock(KnowledgePointImportService.class);
        svc = new KnowledgePointSummaryService(points, originals, ai, importer);
    }

    @Test
    void summarizePointStoresOriginalWritesSummaryUpdatesContent() {
        KnowledgePointRecord card = newCard(7L, "原始正文");
        when(points.findById(7L)).thenReturn(card);
        when(originals.existsOriginal(7L)).thenReturn(false);
        when(ai.summarizeKnowledgePoint("原始正文")).thenReturn("## 浓缩\n要点");

        Map<String, Object> result = svc.summarizePoint(7L);

        verify(originals).upsert(7L, "original", "原始正文");
        verify(originals).upsert(7L, "summary", "## 浓缩\n要点");
        verify(points).updateContentOnly(7L, "## 浓缩\n要点");
        assertEquals(1, result.get("summarized"));
    }

    @Test
    void resummarizePointWithoutOriginalReturnsZeroWithErrors() {
        when(originals.existsOriginal(7L)).thenReturn(false);

        Map<String, Object> result = svc.resummarizePoint(7L);

        assertEquals(0, result.get("summarized"));
        assertFalse(((List<?>) result.get("errors")).isEmpty());
    }

    @Test
    void resummarizePointReCondensatesFromOriginal() {
        when(originals.existsOriginal(7L)).thenReturn(true);
        OriginalRecord orig = new OriginalRecord(1L, 7L, "original", "原文", "2026-07-25");
        when(originals.find(7L, "original")).thenReturn(orig);
        when(ai.summarizeKnowledgePoint("原文")).thenReturn("## 新浓缩");

        Map<String, Object> result = svc.resummarizePoint(7L);

        verify(originals).upsert(7L, "summary", "## 新浓缩");
        verify(points).updateContentOnly(7L, "## 新浓缩");
        assertEquals(1, result.get("summarized"));
    }

    @Test
    void summarizeBankSkipsAlreadySummarizedCards() {
        when(points.findAll(1L)).thenReturn(List.of(newCard(11L, "卡1"), newCard(12L, "卡2")));
        when(originals.existsOriginal(11L)).thenReturn(true);
        when(originals.existsOriginal(12L)).thenReturn(false);
        when(ai.summarizeKnowledgePoint("卡2")).thenReturn("## 浓缩2");

        Map<String, Object> result = svc.summarizeBank(1L);

        verify(originals, never()).upsert(eq(11L), anyString(), anyString());
        verify(originals).upsert(12L, "original", "卡2");
        verify(originals).upsert(12L, "summary", "## 浓缩2");
        assertEquals(1, result.get("summarized"));
    }

    @Test
    void summarizeImportCallsImporterWithAiSummary() {
        when(ai.summarizeMarkdown("原文")).thenReturn("## 总结");
        when(importer.importMarkdown(1L, "## 总结")).thenReturn(Map.of("imported", 3, "failed", 0, "errors", List.of()));

        Map<String, Object> result = svc.summarizeImport(1L, "原文");

        verify(ai).summarizeMarkdown("原文");
        verify(importer).importMarkdown(1L, "## 总结");
        assertEquals("ai-summary", result.get("strategy"));
        assertEquals(3, result.get("imported"));
    }

    @Test
    void restoreOriginalBankRestoresAllCardsWithOriginal() {
        KnowledgePointRecord card1 = newCard(101L, "已总结1");
        KnowledgePointRecord card2 = newCard(102L, "已总结2");
        when(points.findAll(1L)).thenReturn(List.of(card1, card2));
        when(originals.find(101L, "original")).thenReturn(new OriginalRecord(1L, 101L, "original", "原文1", "2026-08-01"));
        when(originals.find(102L, "original")).thenReturn(null);

        Map<String, Object> result = svc.restoreOriginalBank(1L);

        verify(points).updateContentOnly(101L, "原文1");
        verify(points, never()).updateContentOnly(eq(102L), anyString());
        assertEquals(1, result.get("restored"));
    }

    @Test
    void restoreSummaryBankRestoresAllCardsWithSummary() {
        KnowledgePointRecord card1 = newCard(201L, "原文1");
        KnowledgePointRecord card2 = newCard(202L, "原文2");
        when(points.findAll(2L)).thenReturn(List.of(card1, card2));
        when(originals.find(201L, "summary")).thenReturn(new OriginalRecord(2L, 201L, "summary", "总结1", "2026-08-01"));
        when(originals.find(202L, "summary")).thenReturn(new OriginalRecord(3L, 202L, "summary", "总结2", "2026-08-01"));

        Map<String, Object> result = svc.restoreSummaryBank(2L);

        verify(points).updateContentOnly(201L, "总结1");
        verify(points).updateContentOnly(202L, "总结2");
        assertEquals(2, result.get("restored"));
    }

    private KnowledgePointRecord newCard(long id, String content) {
        KnowledgePointRecord card = new KnowledgePointRecord();
        card.id = id;
        card.content = content;
        return card;
    }
}
