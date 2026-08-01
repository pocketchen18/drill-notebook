package com.drillnotebook.app.controller;

import com.drillnotebook.app.repository.BankRepository;
import com.drillnotebook.app.repository.KnowledgePointOriginalRepository;
import com.drillnotebook.app.repository.KnowledgePointRepository;
import com.drillnotebook.app.service.KnowledgePointImportService;
import com.drillnotebook.app.service.KnowledgePointSummaryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KnowledgePointController.class)
class KnowledgePointControllerSummaryTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private KnowledgePointSummaryService summarySvc;
    @MockBean
    private KnowledgePointOriginalRepository originals;
    @MockBean
    private KnowledgePointRepository points;
    @MockBean
    private KnowledgePointImportService importer;
    @MockBean
    private BankRepository banks;

    @Test
    void summarizeBankEndpointReturnsJson() throws Exception {
        when(summarySvc.summarizeBank(1L)).thenReturn(Map.of("summarized", 2, "failed", 0, "errors", List.of()));
        mvc.perform(post("/api/knowledge-points/summarize").param("bankId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summarized").value(2));
    }

    @Test
    void summarizeImportEndpointReturnsAiSummaryStrategy() throws Exception {
        when(summarySvc.summarizeImport(1L, "原文")).thenReturn(Map.of("imported", 3, "failed", 0, "errors", List.of(), "strategy", "ai-summary"));
        mvc.perform(post("/api/knowledge-points/summarize-import").contentType("application/json").content("{\"bankId\":1,\"content\":\"原文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("ai-summary"))
                .andExpect(jsonPath("$.imported").value(3));
    }

    @Test
    void resummarizeBankEndpointReturnsJson() throws Exception {
        when(summarySvc.resummarizeBank(1L)).thenReturn(Map.of("summarized", 2, "failed", 0, "errors", List.of()));
        mvc.perform(post("/api/knowledge-points/resummarize").param("bankId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summarized").value(2));
    }

    @Test
    void summarizePointEndpointReturnsJson() throws Exception {
        when(summarySvc.summarizePoint(7L)).thenReturn(Map.of("summarized", 1, "failed", 0, "errors", List.of()));
        mvc.perform(post("/api/knowledge-points/7/summarize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summarized").value(1));
    }

    @Test
    void resummarizePointEndpointReturnsJson() throws Exception {
        when(summarySvc.resummarizePoint(7L)).thenReturn(Map.of("summarized", 1, "failed", 0, "errors", List.of()));
        mvc.perform(post("/api/knowledge-points/7/resummarize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summarized").value(1));
    }

    @Test
    void getOriginalReturnsContentAndRole() throws Exception {
        KnowledgePointOriginalRepository.OriginalRecord rec =
                new KnowledgePointOriginalRepository.OriginalRecord(1L, 7L, "original", "原文内容", "2026-07-25");
        when(originals.find(7L, "original")).thenReturn(rec);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/knowledge-points/7/original"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("原文内容"))
                .andExpect(jsonPath("$.role").value("original"));
    }

    @Test
    void getOriginalReturns404WhenNotFound() throws Exception {
        when(originals.find(7L, "original")).thenReturn(null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/knowledge-points/7/original"))
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreOriginalReturnsContent() throws Exception {
        KnowledgePointOriginalRepository.OriginalRecord rec =
                new KnowledgePointOriginalRepository.OriginalRecord(1L, 7L, "original", "原文内容", "2026-07-25");
        when(originals.find(7L, "original")).thenReturn(rec);
        mvc.perform(post("/api/knowledge-points/7/restore-original"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("原文内容"));
        verify(points).updateContentOnly(7L, "原文内容");
    }

    @Test
    void restoreOriginalReturns412WhenNotFound() throws Exception {
        when(originals.find(7L, "original")).thenReturn(null);
        mvc.perform(post("/api/knowledge-points/7/restore-original"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void restoreSummaryReturnsContent() throws Exception {
        KnowledgePointOriginalRepository.OriginalRecord rec =
                new KnowledgePointOriginalRepository.OriginalRecord(1L, 7L, "summary", "总结内容", "2026-07-25");
        when(originals.find(7L, "summary")).thenReturn(rec);
        mvc.perform(post("/api/knowledge-points/7/restore-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("总结内容"));
        verify(points).updateContentOnly(7L, "总结内容");
    }

    @Test
    void restoreSummaryReturns412WhenNotFound() throws Exception {
        when(originals.find(7L, "summary")).thenReturn(null);
        mvc.perform(post("/api/knowledge-points/7/restore-summary"))
                .andExpect(status().isPreconditionFailed());
    }
}
