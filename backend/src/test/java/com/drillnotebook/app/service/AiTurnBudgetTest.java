package com.drillnotebook.app.service;

import com.drillnotebook.app.model.AiRuntimeSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiTurnBudgetTest {
    @Test void parallelReservationsCannotOverspendAndUnusedCapacityIsReleased() {
        var settings=new AiRuntimeSettings(50,100,30,15,4096,1024,1024,0);
        var budget=new AiTurnBudget(settings);
        int allocated=java.util.stream.IntStream.range(0,32).parallel().map(i->budget.reserve(1024)).sum();
        assertEquals(4096,allocated);
        assertEquals(0,budget.allocation(1024,0));
        budget.release(1024);
        assertEquals(1024,budget.reserve(1024));
        budget.consume(new AiUpstreamClient.Usage("provider",10,100,null,null),1024);
        assertEquals(924,budget.allocation(1024,0));
        assertEquals(50,java.util.stream.IntStream.range(0,100).parallel().filter(i->budget.tryStage()).count());
        assertEquals(100,java.util.stream.IntStream.range(0,200).parallel().filter(i->budget.tryExecute()).count());
    }
    @Test void actualUsageAllowsFiftyStagesAndSeparatesTheFinalReserve() {
        var budget = new AiTurnBudget(AiRuntimeSettings.DEFAULTS);
        for (int i = 0; i < 50; i++) {
            assertNull(budget.stopReason(8192));
            assertEquals(16384, budget.allocation(16384, 8192));
            budget.consume(new AiUpstreamClient.Usage("provider", 1000, 30, null, null), 16384);
            budget.stage(); budget.executed(); budget.executed();
        }
        assertEquals("budget_stages", budget.stopReason(8192));
        assertFalse(budget.canExecute());
        assertEquals(8192, budget.allocation(8192, 0));
    }

    @Test void missingUsageOnlyChargesItsOwnAllocation() {
        var settings = new AiRuntimeSettings(50, 100, 30, 15, 4096, 1024, 1024, 0);
        var budget = new AiTurnBudget(settings);
        for (int i = 0; i < 3; i++) budget.consume(AiUpstreamClient.Usage.unknown(), 1024);
        assertEquals("budget_output", budget.stopReason(1024));
        assertEquals(0, budget.allocation(1024, 1024));
        assertEquals(1024, budget.allocation(1024, 0));
    }

    @Test void deadlinesUseElapsedTimeAndReserveAClosingWindow() {
        AtomicLong clock = new AtomicLong();
        var budget = new AiTurnBudget(AiRuntimeSettings.DEFAULTS, clock::get);
        assertEquals(900, budget.requestSeconds());
        clock.set(1200_000_000_000L);
        assertEquals(600, budget.requestSeconds());
        clock.set(1740_000_000_000L);
        assertEquals("budget_time", budget.stopReason(8192));
        clock.set(1800_000_000_000L);
        assertEquals(0, budget.requestSeconds());
        assertFalse(budget.canExecute());
    }

    @Test void onlyIdenticalArgumentsAndResultsTriggerNoProgress() {
        var budget = new AiTurnBudget(AiRuntimeSettings.DEFAULTS);
        var mapper = new ObjectMapper();
        var a = new AiUpstreamClient.ToolCall("a", "search_memories", "{\"a\":1,\"b\":2}");
        var b = new AiUpstreamClient.ToolCall("b", "search_memories", "{\"b\":2,\"a\":1}");
        budget.observe(a, "{\"facts\":[1]}", mapper);
        budget.observe(b, "{\"facts\":[2]}", mapper);
        budget.observe(a, "{\"facts\":[2]}", mapper);
        assertNull(budget.stopReason(8192));
        budget.observe(b, "{\"facts\":[2]}", mapper);
        assertEquals("budget_repeated", budget.stopReason(8192));
    }
}
