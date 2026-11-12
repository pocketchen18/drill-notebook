package com.drillnotebook.app.controller;

import com.drillnotebook.app.config.PortableUpdateRequestGate;
import com.drillnotebook.app.service.ActiveTurnRegistry;
import com.drillnotebook.app.service.SessionDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PortableUpdateControllerTest {
    static final String CAPABILITY = "test-only-process-capability";
    @Test void unauthorizedRenderersCannotCheckOrExitTheBackend() throws Exception {
        var dispatch = mock(SessionDispatchService.class);
        var context = mock(ConfigurableApplicationContext.class);
        var gate = new PortableUpdateRequestGate();
        var mvc = MockMvcBuilders.standaloneSetup(new PortableUpdateController(CAPABILITY, gate, dispatch, context, new ActiveTurnRegistry())).addFilters(gate).build();
        mvc.perform(get("/api/app/update/ready")).andExpect(status().isForbidden());
        mvc.perform(post("/api/app/update/shutdown").header("X-Drill-Update-Exit", "wrong")).andExpect(status().isForbidden());
        verifyNoInteractions(dispatch, context);
    }
    @Test void activeAiAndQueuedBackgroundWritesPreventGracefulExit() throws Exception {
        var dispatch = mock(SessionDispatchService.class);
        when(dispatch.readyForPortableUpdate()).thenReturn(true);
        var context = mock(ConfigurableApplicationContext.class);
        var gate = new PortableUpdateRequestGate();
        var turns = new ActiveTurnRegistry();
        var mvc = MockMvcBuilders.standaloneSetup(new PortableUpdateController(CAPABILITY, gate, dispatch, context, turns)).addFilters(gate).build();
        var handle = turns.acquire("active-request", 1).orElseThrow();
        mvc.perform(get("/api/app/update/ready").header("X-Drill-Update-Exit", CAPABILITY)).andExpect(jsonPath("$.ready").value(false));
        mvc.perform(post("/api/app/update/shutdown").header("X-Drill-Update-Exit", CAPABILITY)).andExpect(status().isConflict());
        turns.release("active-request", handle);
        var lease = gate.enterTask();
        mvc.perform(post("/api/app/update/shutdown").header("X-Drill-Update-Exit", CAPABILITY)).andExpect(status().isConflict());
        lease.close(); lease.close(); assertFalse(gate.hasWrites());
        verify(context, never()).close();
    }
    @Test void successfulExitClosesContextNormallyAndBlocksAnyLaterWrite() throws Exception {
        var dispatch = mock(SessionDispatchService.class);
        when(dispatch.beginPortableUpdateShutdown()).thenReturn(true);
        var context = mock(ConfigurableApplicationContext.class);
        var gate = new PortableUpdateRequestGate();
        var closed = new CountDownLatch(1);
        doAnswer(call -> { closed.countDown(); return null; }).when(context).close();
        var mvc = MockMvcBuilders.standaloneSetup(new PortableUpdateController(CAPABILITY, gate, dispatch, context, new ActiveTurnRegistry())).addFilters(gate).build();
        mvc.perform(post("/api/app/update/shutdown").header("X-Drill-Update-Exit", CAPABILITY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post("/api/notes/123")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UPDATE_RESTARTING"));
        assertNull(gate.enterTask()); assertTrue(closed.await(3, TimeUnit.SECONDS));
        verify(context, times(1)).close();
    }
    @Test void rejectedDispatchExitReopensTheGate() throws Exception {
        var dispatch = mock(SessionDispatchService.class);
        var gate = new PortableUpdateRequestGate();
        var mvc = MockMvcBuilders.standaloneSetup(new PortableUpdateController(CAPABILITY, gate, dispatch,
                mock(ConfigurableApplicationContext.class), new ActiveTurnRegistry())).addFilters(gate).build();
        mvc.perform(post("/api/app/update/shutdown").header("X-Drill-Update-Exit", CAPABILITY)).andExpect(status().isConflict());
        var lease = gate.enterTask(); assertNotNull(lease); lease.close();
    }
    @Test void submittedBackgroundWorkIsCountedBeforeItRunsAndReleasedAfterFailure() throws Exception {
        var gate = new PortableUpdateRequestGate();
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            gate.executeBackground(executor, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertFalse(gate.beginClose()); assertTrue(gate.hasWrites());
            release.countDown();
            executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(gate.hasWrites()); assertTrue(gate.beginClose());
            assertFalse(gate.executeBackground(Runnable::run, () -> fail("Closing application accepted background work")));
        } finally { release.countDown(); executor.shutdownNow(); }
        var rejectionGate = new PortableUpdateRequestGate();
        assertThrows(RuntimeException.class, () -> rejectionGate.executeBackground(task -> { throw new IllegalStateException(); }, () -> {}));
        assertFalse(rejectionGate.hasWrites());
    }
}
