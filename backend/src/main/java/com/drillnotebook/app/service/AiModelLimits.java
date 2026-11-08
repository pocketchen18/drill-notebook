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
    public static int ceiling(ConfigRow config, String field, int fallback) {
        Object value = params(config).get(field);
        return value instanceof Number number && number.longValue() > 0
                ? (int) Math.min(number.longValue(), fallback) : fallback;
    }
    public static int output(ConfigRow config, int taskLimit) { return ceiling(config, "maxOutputTokens", taskLimit); }
    public static boolean noKey(ConfigRow config) {
        return config != null && ("none".equals(params(config).get("authMode")) || "mock://local".equals(config.endpoint()));
    }
    /** 工具链每次续写重新估算，给最终回答和收口提示留空间，不裁断用户问题。 */
    public static int availableOutput(ConfigRow config, Object messages, Object tools, int fallbackWindow) {
        int input = estimate(messages) + estimate(tools);
        if (input > ceiling(config, "maxInputTokens", Integer.MAX_VALUE)) return 0;
        int window = ceiling(config, "contextWindow", fallbackWindow);
        return Math.max(0, window - input - Math.min(512, window / 10));
    }
    public static void validate(ConfigRow config, Object messages, Object tools, int output) {
        Map<String, Object> params = params(config);
        if (!params.containsKey("maxInputTokens") && !params.containsKey("contextWindow")) return;
        int input = estimate(messages) + estimate(tools);
        int limit = ceiling(config, "maxInputTokens", Integer.MAX_VALUE);
        int window = ceiling(config, "contextWindow", Integer.MAX_VALUE);
        if (input > limit || (long) input + output > window) {
            throw new ChatTurnRejectException(400, "CONTEXT_TOO_LARGE", "输入与输出预留超出此模型的上限，请缩短输入或调整模型设置",
                    Map.of("estimatedInputTokens", input, "maxInputTokens", limit, "outputReserve", output));
        }
    }
    private static int estimate(Object value) {
        int[] images = {0};
        return ESTIMATOR.estimateSerialized(countable(value, images)) + images[0] * TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS;
    }
    private static Object countable(Object value, int[] images) {
        if (value instanceof List<?> list) return list.stream().map(item -> countable(item, images)).toList();
        if (value instanceof Map<?, ?> map) {
            if ("image_url".equals(map.get("type")) || "image".equals(map.get("type"))) {
                images[0]++;
                return Map.of("type", "image");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), countable(item, images)));
            return copy;
        }
        return value;
    }
}
