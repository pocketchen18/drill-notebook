package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.AiUpstreamClient;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.PreviousSummary;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceBlock;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 提取请求的"系统提示词里到底有什么"：结构体 {@code OUTPUT_SHAPE} 必须真的随请求发送。
 *
 * <p>真实模型不会凭空猜字段。这里只钉住发送内容本身，不联网。</p>
 */
class MaintenanceExtractorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void systemPromptCarriesTheOutputShapeInTheActualRequest() throws Exception {
        MaintenanceUpstream upstream = mock(MaintenanceUpstream.class);
        when(upstream.call(anyString(), anyString())).thenReturn(new AiUpstreamClient.Result(
                "{\"schemaVersion\":2,\"candidates\":[]}", null, "stop", true, false, false,
                AiUpstreamClient.Usage.unknown()));
        MemoryRepository repository = mock(MemoryRepository.class);
        when(repository.findPage(anyString(), anyString(), anyString(), anyLong(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());
        MaintenanceExtractor extractor = new MaintenanceExtractor(upstream, repository,
                mock(MemoryCryptoService.class), new TokenEstimator(MAPPER), MAPPER);
        MemoryCryptoService.MemoryKey key = new MemoryCryptoService.MemoryKey(new byte[32], new byte[32], "vault",
                "fingerprint");
        Batch batch = new Batch(Kind.EXTRACT, 1L, 1L, 1L, 1L, "GLOBAL", 0L, "Asia/Shanghai", 1L, 1L,
                PreviousSummary.NONE,
                List.of(new SourceTurn(1L, 1L, 101L, 102L, "我常住杭州，请记住", null, 12, 0L,
                        List.of(new SourceBlock("b1", "我常住杭州，请记住")), "2026-10-31T15:50:00Z")),
                0, 12, "rev", 0L, List.of());

        extractor.extract(batch, key, null);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(upstream).call(prompt.capture(), anyString());
        assertTrue(prompt.getValue().contains(MemoryValidator.OUTPUT_SHAPE), "缺少输出结构，模型只能自己猜字段");
        assertTrue(prompt.getValue().contains("\"schemaVersion\":2"));
        assertTrue(prompt.getValue().contains("\"targetMemoryId\""));
    }

    @Test
    void explicitRememberRequestIsAddedEvenWhenTheModelReturnsNoCandidates() throws Exception {
        MaintenanceUpstream upstream = mock(MaintenanceUpstream.class);
        when(upstream.call(anyString(), anyString())).thenReturn(new AiUpstreamClient.Result(
                "{\"schemaVersion\":2,\"candidates\":[]}", null, "stop", true, false, false,
                AiUpstreamClient.Usage.unknown()));
        MemoryRepository repository = mock(MemoryRepository.class);
        when(repository.findPage(anyString(), anyString(), anyString(), anyLong(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());
        MaintenanceExtractor extractor = new MaintenanceExtractor(upstream, repository,
                mock(MemoryCryptoService.class), new TokenEstimator(MAPPER), MAPPER);
        MemoryCryptoService.MemoryKey key = new MemoryCryptoService.MemoryKey(new byte[32], new byte[32], "vault",
                "fingerprint");
        String text = "之后回答每句话之后带一个完毕，请记住";
        Batch batch = new Batch(Kind.EXTRACT, 1L, 1L, 1L, 1L, "GLOBAL", 0L, "Asia/Shanghai", 1L, 1L,
                PreviousSummary.NONE,
                List.of(new SourceTurn(1L, 1L, 101L, 102L, text, null, 12, 0L,
                        List.of(new SourceBlock("b1", text)), "2026-10-31T15:50:00Z")),
                0, 12, "rev", 0L, List.of());

        var result = extractor.extract(batch, key, null);

        assertTrue(result.formatOk());
        assertEquals(1, result.candidates().size());
        var candidate = result.candidates().get(0);
        assertEquals("other", candidate.predicate());
        assertEquals("之后回答每句话之后带一个完毕", candidate.value());
        assertEquals("PREFERENCE", candidate.kind());
        assertEquals(101L, candidate.userMessageId());
        assertEquals("b1", candidate.sourceBlockId());
        assertEquals(text, candidate.quote());
    }
}
