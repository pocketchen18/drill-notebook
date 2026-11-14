package com.drillnotebook.app.service;

import com.drillnotebook.app.model.RequestContextAudit.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 当前轮保留的非对话正文：工具往返、注入资料、图片和续接指令，只计算当前工作集一次。 */
final class SessionContextExtras {
    private SessionContextExtras() { }

    static long estimate(List<Map<String, Object>> messages, int initialMessages,
                         Set<Map<String, Object>> supplements, ObjectMapper mapper) {
        return breakdown(messages, initialMessages, supplements, mapper).tokens();
    }

    static ContextTokenBreakdown breakdown(List<Map<String, Object>> messages, int initialMessages,
                                          Set<Map<String, Object>> supplements, ObjectMapper mapper) {
        var estimator = new TokenEstimator(mapper);
        var total = new ContextTokenBreakdown.Builder();
        for (int index = 0; index < messages.size(); index++) {
            Map<String, Object> message = messages.get(index);
            String role = String.valueOf(message.get("role"));
            if ("tool".equals(role) || message.containsKey("tool_calls")) {
                addMessage(total, message, "tool".equals(role) ? Kind.TOOL_RESULTS : Kind.ASSISTANT, estimator);
                continue;
            }
            if ("system".equals(role) || "developer".equals(role)) {
                // 固定前缀与工具定义由当前模型配置统一计一次；工具停止等新增指令留在当前轮。
                if (index >= initialMessages) addMessage(total, message, Kind.SYSTEM, estimator);
                continue;
            }
            if ("assistant".equals(role)) {
                // 续接时放回的部分回答已经在本轮输出数值中；保留的协议思考块另计。
                if (index >= initialMessages && message.get("anthropic_thinking") != null)
                    total.add(Kind.ASSISTANT, estimator.estimateInput(message.get("anthropic_thinking")));
                continue;
            }
            if (!"user".equals(role)) { total.add(Kind.OTHER, estimator.estimateInput(message)); continue; }
            boolean supplement = supplements.contains(message);
            if (index >= initialMessages && !supplement) {
                addMessage(total, message, Kind.USER, estimator); continue;
            }
            Object content = message.get("content");
            if (content instanceof List<?> blocks) {
                for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                    Object raw = blocks.get(blockIndex);
                    if (!(raw instanceof Map<?, ?> block)) continue;
                    String type = String.valueOf(block.get("type"));
                    if ("image".equals(type) || "image_url".equals(type)) {
                        total.add(Kind.IMAGE, estimator.estimateInput(block));
                    } else if (block.get("text") instanceof String text) {
                        if (supplement && blockIndex < blocks.size() - 1) total.add(Kind.INJECTED_CONTEXT, estimator.estimateInput(block));
                        else total.add(Kind.INJECTED_CONTEXT, injected(text, estimator, mapper));
                    }
                }
            } else if (content instanceof String text) {
                // v2 原有提问 / 回答使用 typed 文本块；这里的字符串是提交时绑定的页面定位。
                total.add(Kind.INJECTED_CONTEXT, estimator.estimateInput(text));
            }
        }
        return total.build();
    }

    /** 正文、工具参数和封装分开；分类总和保持原有整条消息的估算值。 */
    private static void addMessage(ContextTokenBreakdown.Builder total, Map<String, Object> message,
                                   Kind contentKind, TokenEstimator estimator) {
        long remaining = estimator.estimateInput(message);
        if (message.get("content") != null) {
            long content = Math.min(remaining, estimator.estimateInput(message.get("content")));
            total.add(contentKind, content); remaining -= content;
        }
        if (message.get("tool_calls") != null) {
            long calls = Math.min(remaining, estimator.estimateInput(message.get("tool_calls")));
            total.add(Kind.TOOL_CALLS, calls); remaining -= calls;
        }
        if (message.get("anthropic_thinking") != null) {
            long thinking = Math.min(remaining, estimator.estimateInput(message.get("anthropic_thinking")));
            total.add(Kind.ASSISTANT, thinking); remaining -= thinking;
        }
        total.add(Kind.OTHER, remaining);
    }

    private static long injected(String text, TokenEstimator estimator, ObjectMapper mapper) {
        if (!text.startsWith("{") || !text.contains("CONTEXT_DATA_V1")) return 0;
        try {
            Map<?, ?> data = mapper.readValue(text, Map.class);
            if (!"CONTEXT_DATA_V1".equals(data.get("kind"))) return 0;
            var copy = new LinkedHashMap<String, Object>();
            data.forEach((key, value) -> copy.put(String.valueOf(key), value));
            // 当前有效摘要由全局计数器计一次，覆盖的旧原文不再重复计入。
            copy.put("summary", null);
            return estimator.estimateInput(copy);
        } catch (Exception ignored) { return 0; }
    }
}
