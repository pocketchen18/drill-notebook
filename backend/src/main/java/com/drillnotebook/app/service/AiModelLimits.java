package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository.ConfigRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型上限只能收紧应用任务预算；计数沿用保守估算，绝不截断当前问题。 */
public final class AiModelLimits {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenEstimator ESTIMATOR = new TokenEstimator(MAPPER);
    private AiModelLimits() { }
    public static Map<String, Object> params(ConfigRow config) {
        try { return config == null || config.params() == null ? Map.of() : MAPPER.readValue(config.params(), new TypeReference<>() { }); }
        catch (Exception error) { return Map.of(); }
    }
    public static boolean configured(Map<String, Object> params) {
        Object window = params.get("contextWindow"), output = params.get("maxOutputTokens");
        return positiveInteger(window) && positiveInteger(output) && ((Number) output).longValue() <= ((Number) window).longValue();
    }
    private static boolean positiveInteger(Object value) {
        return value instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() > 0 && n.longValue() <= Integer.MAX_VALUE;
    }
    public static void requireConfigured(Map<String, Object> params) {
        if (!configured(params)) throw new ChatTurnRejectException(400, "MODEL_LIMITS_REQUIRED",
                "请在模型设置中填写上下文窗口和最大输出 Token（正整数），且最大输出不能大于上下文窗口", Map.of());
    }
    public static int contextWindow(ConfigRow config) {
        Map<String, Object> params = params(config); requireConfigured(params);
        return ((Number) params.get("contextWindow")).intValue();
    }
    public static int output(ConfigRow config, int taskLimit) {
        Map<String, Object> params = params(config); requireConfigured(params);
        return Math.min(((Number) params.get("maxOutputTokens")).intValue(), taskLimit);
    }
    public static boolean noKey(ConfigRow config) {
        return config != null && ("none".equals(params(config).get("authMode")) || "mock://local".equals(config.endpoint()));
    }
    /** 工具链每次续写重新估算，给最终回答和收口提示留空间，不裁断用户问题。 */
    public static int availableOutput(ConfigRow config, Object messages, Object tools) {
        int input = estimate(messages) + estimate(tools);
        int window = contextWindow(config);
        return Math.max(0, window - input - Math.min(512, window / 10));
    }
    public static void validate(ConfigRow config, Object messages, Object tools, int output) {
        int input = estimate(messages) + estimate(tools);
        int window = contextWindow(config);
        if ((long) input + output > window) {
            throw new ChatTurnRejectException(400, "CONTEXT_TOO_LARGE", "输入与输出预留超出此模型的上限，请缩短输入或调整模型设置",
                    Map.of("estimatedInputTokens", input, "contextWindow", window, "outputReserve", output));
        }
    }
    private static int estimate(Object value) {
        return ESTIMATOR.estimateInput(value);
    }
}
