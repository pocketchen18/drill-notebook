package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 模型能力声明与实际请求参数分离；未选默认值时不干预服务。 */
public record AiReasoningSettings(String control, List<String> efforts, String defaultEffort, Integer budget) {
    public static final List<String> EFFORTS = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    public static final List<String> FIELDS = List.of("reasoningControl", "reasoningEfforts", "defaultReasoningEffort", "thinkingBudgetTokens");
    public static final AiReasoningSettings AUTO = new AiReasoningSettings("effort", List.of(), "", null);
    public AiReasoningSettings { efforts = List.copyOf(efforts); }

    public static List<String> supported(String format) {
        return "anthropic".equals(format) ? List.of("low", "medium", "high", "xhigh", "max") : EFFORTS;
    }
    public static AiReasoningSettings from(AiConfigRepository.ConfigRow config) {
        return read(AiModelLimits.params(config), AiService.apiFormatOf(config));
    }
    public static AiReasoningSettings read(Map<String, Object> params, String format) {
        String control = params.get("reasoningControl") == null ? "effort" : String.valueOf(params.get("reasoningControl"));
        if (!List.of("effort", "budget", "none", "fixed", "unknown").contains(control)) fail("请选择有效的思考控制方式");
        if ("budget".equals(control) && !"anthropic".equals(format)) fail("当前协议尚不支持思考预算控制");
        Object raw = params.get("reasoningEfforts");
        if (raw != null && !(raw instanceof List<?>)) fail("思考等级必须为列表");
        List<?> values = raw instanceof List<?> list ? list : List.of();
        if (values.stream().anyMatch(v -> !(v instanceof String) || !EFFORTS.contains(v))) fail("不支持的思考等级");
        if ("effort".equals(control) && values.stream().anyMatch(v -> !supported(format).contains(v))) fail("当前协议不支持所选思考等级");
        List<String> efforts = EFFORTS.stream().filter(values::contains).toList();
        String selected = params.get("defaultReasoningEffort") == null ? "" : String.valueOf(params.get("defaultReasoningEffort"));
        if (!selected.isEmpty() && (!"effort".equals(control) || !efforts.contains(selected))) fail("默认等级必须属于已勾选的思考等级");
        Integer budget = null;
        Object tokens = params.get("thinkingBudgetTokens");
        if (tokens != null && !"".equals(tokens)) {
            if (!(tokens instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 1024 || n.longValue() >= Integer.MAX_VALUE) fail("思考预算须为至少 1024 的整数");
            budget = ((Number) tokens).intValue();
        }
        if (!"budget".equals(control)) budget = null;
        return new AiReasoningSettings(control, efforts, selected, budget);
    }
    public void putInto(Map<String, Object> params) {
        params.put("reasoningControl", control); params.put("reasoningEfforts", new ArrayList<>(efforts));
        if (defaultEffort.isEmpty()) params.remove("defaultReasoningEffort"); else params.put("defaultReasoningEffort", defaultEffort);
        if (budget == null) params.remove("thinkingBudgetTokens"); else params.put("thinkingBudgetTokens", budget);
    }
    public boolean specified() { return !defaultEffort.isEmpty() || budget != null; }
    public void apply(Map<String, Object> request, boolean anthropic, int maxTokens, boolean disableThinking) {
        if (disableThinking) return;
        if ("effort".equals(control) && !defaultEffort.isEmpty()) {
            if (!supported(anthropic ? "anthropic" : "chat_completions").contains(defaultEffort)) fail("当前协议不支持默认思考等级，请检查模型设置");
            if (anthropic) request.put("output_config", Map.of("effort", defaultEffort));
            else request.put("reasoning_effort", defaultEffort);
        } else if ("budget".equals(control) && budget != null) {
            if (!anthropic) fail("当前协议尚不支持思考预算控制");
            if (budget >= maxTokens) fail("思考预算须小于本次请求的输出上限（" + maxTokens + "），请降低预算");
            request.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
        }
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
