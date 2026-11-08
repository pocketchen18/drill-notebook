package com.drillnotebook.app.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.drillnotebook.app.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AiStreamLifecycleTest {
    @Test
    void disconnectBeforeFirstMetadataCancelsTheTurnBeforeModelDispatch() throws Exception {
        AiService ai = mock(AiService.class);
        var controller = new AiController(ai, null, null, null, null, new ObjectMapper());
        var started = new CountDownLatch(1);
        var ready = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var dispatched = new AtomicBoolean();
        var failure = new AtomicReference<RuntimeException>();
        when(ai.chatStream(any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                Consumer<Map<String, Object>> metadata = invocation.getArgument(2);
                metadata.accept(Map.of("requestId", "disconnected-turn", "sessionId", 1L));
                dispatched.set(true);
                return null;
            } catch (RuntimeException error) {
                failure.set(error);
                throw error;
            } finally {
                finished.countDown();
            }
        });
        try {
            var emitter = controller.chatStream(Map.of());
            assertEquals(0L, emitter.getTimeout(), "整轮期限交由预算控制，流不再固定五分钟关闭");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            emitter.complete();
            ready.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertFalse(dispatched.get(), "首帧发送失败必须在外发模型前停止");
            verify(ai).cancelActive("disconnected-turn");
        } finally {
            ready.countDown();
            controller.close();
        }
    }
}
