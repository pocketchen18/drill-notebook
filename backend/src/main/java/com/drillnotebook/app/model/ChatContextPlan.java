package com.drillnotebook.app.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次外发的唯一上下文投影（执行方案 §9.2/§9.4、contracts §4.5）：
 * 普通与流式两条路径、计数与检查器都消费同一个实例，不再各自拼 prompt。
 *
 * <p>本对象冻结后不可变：{@code messages} 就是最终要交给厂商的 typed 消息序列（内存态，
 * 不含 Authorization），{@code audit} 是它的有界来源账本。需要重组时丢弃旧计划换新 planId，
 * 绝不能把旧 metadata 绑到新请求上。</p>
 */
public record ChatContextPlan(String planId, long sessionId, long contextGeneration, long historyRevision,
                              Long summaryRevision, Long vaultEpoch, Long vaultRevision, long policyRevision,
                              List<Map<String, Object>> messages, List<String> notices,
                              List<Map<String, Object>> citations, ContextAudit audit) {

    public static final int INPUT_BUDGET_TOKENS = 8000;

    public ChatContextPlan {
        messages = deepCopy(messages);
        notices = notices == null ? List.of() : List.copyOf(notices);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 只改发送状态，不动内容与账本：发送前 not_sent，发出后 sent，读流出错按 unknown。 */
    public ChatContextPlan withDispatchState(String dispatchState) {
        return new ChatContextPlan(planId, sessionId, contextGeneration, historyRevision, summaryRevision,
                vaultEpoch, vaultRevision, policyRevision, messages, notices, citations,
                audit.withDispatchState(dispatchState));
    }

    /** 落进 result_meta 的审计（含 16 KiB 兜底收缩，尺寸由调用方量出）。 */
    public Map<String, Object> auditMap() {
        return audit.toMap();
    }

    /** 调用方在计划之外还要补提示（如笔记检索降级）时用的副本：计划本身不可变。 */
    public ChatContextPlan withExtraNotices(List<String> extra) {
        if (extra == null || extra.isEmpty()) return this;
        List<String> merged = new ArrayList<>(notices);
        for (String notice : extra) {
            if (!merged.contains(notice)) merged.add(notice);
        }
        return new ChatContextPlan(planId, sessionId, contextGeneration, historyRevision, summaryRevision,
                vaultEpoch, vaultRevision, policyRevision, messages, merged, citations, audit);
    }

    /** §4.2 的 contextUsage：估算口径必须如实标 estimated，不能冒充厂商计数。 */
    public Map<String, Object> contextUsage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("estimated", true);
        usage.put("inputBudget", audit.inputBudget());
        usage.put("estimatedInputTokens", audit.estimatedInputTokens());
        usage.put("recentTurnsIncluded", itemCount(ContextAudit.Region.RECENT_TURNS));
        usage.put("summaryThroughTurnId", audit.summary() == null ? null : audit.summary().throughTurnId());
        usage.put("summaryHasGaps", audit.summary() != null && audit.summary().hasGaps());
        usage.put("notices", notices);
        return usage;
    }

    /** §4.2 的 memoryContext：只说明"本轮提供了哪些记忆版本"，不构成逐字归因。 */
    public Map<String, Object> memoryContext() {
        List<Map<String, Object>> provided = new ArrayList<>();
        for (ContextAudit.Selected selected : audit.selected()) {
            if (!"MEMORY".equals(selected.kind())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memoryId", selected.id());
            row.put("version", selected.version() == null ? null : Integer.parseInt(selected.version()));
            provided.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provided", provided);
        result.put("estimatedTokens", regionTokens(ContextAudit.Region.MEMORY));
        result.put("vaultRevision", auditVaultRevision());
        return result;
    }

    /** SSE metadata 与响应共用这一份摘要：计划身份 + 预算 + 实际提供的引用。 */
    public Map<String, Object> metadataEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("planId", planId);
        event.put("sessionId", sessionId);
        event.put("dispatchState", audit.dispatchState());
        event.put("contextUsage", contextUsage());
        event.put("memoryContext", memoryContext());
        return event;
    }

    private Long auditVaultRevision() {
        return vaultRevision;
    }

    private int regionTokens(ContextAudit.Region region) {
        for (ContextAudit.RegionUsage row : audit.regions()) {
            if (row.region() == region) return row.estimatedTokens();
        }
        return 0;
    }

    private int itemCount(ContextAudit.Region region) {
        for (ContextAudit.RegionUsage row : audit.regions()) {
            if (row.region() == region) return row.itemCount();
        }
        return 0;
    }

    /** 嵌套的 content 块也要复制：调用方手里的 Map 之后可能被别处复用。 */
    private static List<Map<String, Object>> deepCopy(List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (source == null) return copy;
        for (Map<String, Object> message : source) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : message.entrySet()) {
                row.put(entry.getKey(), copyValue(entry.getValue()));
            }
            copy.add(java.util.Collections.unmodifiableMap(row));
        }
        return List.copyOf(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(copyValue(item));
            return List.copyOf(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), copyValue(item)));
            // 不可变但保序：厂商请求体的 JSON 键序要能进 golden 测试，Map.copyOf 的迭代序不保证
            return java.util.Collections.unmodifiableMap(copy);
        }
        return value;
    }
}
