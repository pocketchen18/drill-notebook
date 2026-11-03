package com.drillnotebook.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drillnotebook.app.service.AiService;
import com.drillnotebook.app.service.ChatHistoryService;
import com.drillnotebook.app.service.ChatStorageService;
import com.drillnotebook.app.service.ChatTurnRejectException;
import com.drillnotebook.app.service.RetrievalMaintenanceService;
import com.drillnotebook.app.service.RetrievalStatusService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 容量面与清理的路由（contracts §2.7、§3）：前端写死的这几个路径必须真的存在，
 * 否则它只在真机点开那一刻才暴露。快照只给逻辑落库字节，不给物理库大小；
 * 清理的拒绝必须保住各自的 409/410，让界面能分清"重新预览"和"服务坏了"。
 */
@WebMvcTest(AiController.class)
class AiHistoryStorageControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiService ai;
    @MockBean
    private ChatHistoryService history;
    @MockBean
    private ChatStorageService storage;
    @MockBean
    private RetrievalStatusService retrievalStatus;
    @MockBean
    private RetrievalMaintenanceService retrievalMaintenance;

    @Test
    void storageSnapshotServesTheLedgerNumbers() throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("historyBytes", 1024L);
        snapshot.put("reservedHistoryBytes", 0L);
        snapshot.put("usedHistoryBytes", 1024L);
        snapshot.put("historyLimitBytes", 268435456L);
        snapshot.put("attachmentBytes", 0L);
        snapshot.put("attachmentLimitBytes", 268435456L);
        snapshot.put("historyWarning", false);
        snapshot.put("attachmentWarning", false);
        snapshot.put("revision", 3L);
        when(storage.snapshot()).thenReturn(snapshot);

        mvc.perform(get("/api/ai/history/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedHistoryBytes").value(1024))
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.historyWarning").value(false));
    }

    @Test
    void cleanupPreviewAndExecuteAreRoutedOnTheirContractPaths() throws Exception {
        when(storage.previewCleanup(any())).thenReturn(new LinkedHashMap<>(Map.of(
                "previewId", "tok-1", "turnCount", 2L, "historyBytes", 4096L, "expiresAt", "2026-10-18T00:02:00.000Z")));
        when(storage.cleanup(any())).thenReturn(new LinkedHashMap<>(Map.of(
                "cleanedTurns", 2L, "releasedHistoryBytes", 4096L)));

        mvc.perform(post("/api/ai/history/cleanup/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"beforeOrAtMessageId\":99,\"maxTurns\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value("tok-1"))
                .andExpect(jsonPath("$.turnCount").value(2));
        mvc.perform(post("/api/ai/history/cleanup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewId\":\"tok-1\",\"expectedHistoryRevision\":7,\"operationId\":\"op-1\","
                                + "\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleanedTurns").value(2));

        var executed = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(storage).cleanup(executed.capture());
        assertEquals("tok-1", executed.getValue().get("previewId"));
        assertEquals(7L, ((Number) executed.getValue().get("expectedHistoryRevision")).longValue());
        assertEquals("op-1", executed.getValue().get("operationId"));
        assertEquals(true, executed.getValue().get("confirm"));
    }

    /** 清理是删内容的写操作：409/410 各有各的码，不能被统一异常抹成 500。 */
    @Test
    void cleanupRejectionsKeepTheirStatusCodes() throws Exception {
        when(storage.cleanup(any())).thenThrow(new ChatTurnRejectException(410, "CLEANUP_PREVIEW_EXPIRED",
                "预览无效或已过期，请重新预览", Map.of()));

        mvc.perform(post("/api/ai/history/cleanup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewId\":\"old\",\"expectedHistoryRevision\":7,\"confirm\":true}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("CLEANUP_PREVIEW_EXPIRED"));
    }

    @Test
    void limitsAreChangedThroughTheSamePathWithACasBaseline() throws Exception {
        when(storage.updateLimits(any())).thenReturn(new LinkedHashMap<>(Map.of("revision", 4L,
                "historyLimitBytes", 536870912L)));

        mvc.perform(put("/api/ai/history/storage").contentType("application/json")
                        .content("{\"expectedRevision\":3,\"historyLimitBytes\":536870912,"
                                + "\"attachmentLimitBytes\":268435456}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.historyLimitBytes").value(536870912));

        var body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(storage).updateLimits(body.capture());
        assertEquals(3L, ((Number) body.getValue().get("expectedRevision")).longValue());
    }
}
