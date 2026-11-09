package com.drillnotebook.app.service;

import com.drillnotebook.app.model.AiRuntimeSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.LongSupplier;

/** 单轮预算只计实际用量；厂商缺少 usage 时才按本次额度保守预扣，不影响计费账本。 */
final class AiTurnBudget {
    private final AiRuntimeSettings settings;
    private final LongSupplier clock;
    private final long started;
    private int stages;
    private int executions;
    private long output;
    private long inFlight;
    private String lastFingerprint;
    private int repeated;
    private String reason;

    AiTurnBudget(AiRuntimeSettings settings) { this(settings, System::nanoTime); }
    AiTurnBudget(AiRuntimeSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        this.started = clock.getAsLong();
    }
    synchronized String stopReason(int reserve) {
        if (reason != null) return reason;
        if (secondsLeft() <= Math.min(60, settings.turnTimeoutMinutes() * 6)) return "budget_time";
        if (stages >= settings.maxToolStages()) return "budget_stages";
        if (executions >= settings.maxToolExecutions()) return "budget_executions";
        if (output >= settings.maxOutputTokens() - reserve) return "budget_output";
        return null;
    }
    synchronized int allocation(int requested, int reserve) {
        return (int) Math.max(0, Math.min(requested, (long) settings.maxOutputTokens() - output - inFlight - reserve));
    }
    synchronized int reserve(int requested) { int amount=allocation(requested,0); inFlight+=amount; return amount; }
    synchronized void release(int allocated) { inFlight=Math.max(0,inFlight-allocated); }
    synchronized void consume(AiUpstreamClient.Usage usage, int allocated) {
        release(allocated);
        output += usage != null && usage.outputTokens() != null ? Math.max(0, usage.outputTokens()) : allocated;
    }
    synchronized void stage() { stages++; }
    synchronized boolean tryStage() {
        if(stages>=settings.maxToolStages()) return false;
        stages++; return true;
    }
    synchronized boolean canExecute() { return executions < settings.maxToolExecutions() && secondsLeft() > 0 && reason == null; }
    synchronized void executed() { executions++; }
    synchronized boolean tryExecute() { if(!canExecute()) return false; executions++; return true; }
    synchronized void stop(String cause) { if (reason == null) reason = cause; }
    long secondsLeft() {
        long remaining = settings.turnTimeoutMinutes() * 60_000_000_000L - Math.max(0, clock.getAsLong() - started);
        return Math.max(0, remaining / 1_000_000_000L);
    }
    int requestSeconds() { return (int) Math.min(settings.requestTimeoutMinutes() * 60L, secondsLeft()); }
    synchronized void observe(AiUpstreamClient.ToolCall call, String result, ObjectMapper mapper) {
        if (settings.repeatedToolLimit() == 0) return;
        String fingerprint;
        try {
            // 排序后的参数/结果只保留在内存，不写入日志或历史。
            fingerprint = call.name() + canonical(mapper.readTree(call.arguments())) + canonical(mapper.readTree(result));
        } catch (Exception ignored) { fingerprint = call.name() + call.arguments() + result; }
        repeated = fingerprint.equals(lastFingerprint) ? repeated + 1 : 1;
        lastFingerprint = fingerprint;
        if (repeated >= settings.repeatedToolLimit()) reason = "budget_repeated";
    }
    private static String canonical(com.fasterxml.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            Map<String, String> fields = new java.util.TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            return fields.toString();
        }
        if (value.isArray()) {
            java.util.List<String> rows = new java.util.ArrayList<>();
            value.forEach(row -> rows.add(canonical(row)));
            return rows.toString();
        }
        return value.toString();
    }
}
