package com.drillnotebook.app.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 内置工具的全局启停偏好，独立于供应商和模型；只保存与默认值不同的显式选择。 */
public record AiToolSettings(Map<String, Boolean> overrides) {
    /** 删除类工具默认关闭；随版本新增的删除工具同样从关闭开始。 */
    public static final List<String> DELETE_TOOLS = List.of("delete_memory", "delete_session", "delete_material",
            "remove_wrong_question");
    public static final AiToolSettings DEFAULTS = new AiToolSettings(Map.of());

    public AiToolSettings {
        Map<String, Boolean> copy = new TreeMap<>();
        if (overrides != null) {
            overrides.forEach((name, enabled) -> {
                if (!managed(name) || enabled == null) throw new IllegalArgumentException("未知的工具开关: " + name);
                if (enabled != defaultEnabled(name)) copy.put(name, enabled);
            });
        }
        overrides = Collections.unmodifiableMap(copy);
    }

    /** 受开关管理的工具；其余工具只受模型的工具调用能力约束。 */
    public static boolean managed(String tool) {
        return tool != null && DELETE_TOOLS.contains(tool);
    }

    /** 删除类工具另有「仅限用户直接提出」的约束。 */
    public static boolean destructive(String tool) {
        return tool != null && DELETE_TOOLS.contains(tool);
    }

    public static boolean defaultEnabled(String tool) {
        return !destructive(tool);
    }

    public boolean enabled(String tool) {
        return !managed(tool) || overrides.getOrDefault(tool, defaultEnabled(tool));
    }

    public AiToolSettings with(Map<String, Boolean> changes) {
        Map<String, Boolean> merged = new LinkedHashMap<>();
        for (String tool : DELETE_TOOLS) merged.put(tool, enabled(tool));
        merged.putAll(changes);
        return new AiToolSettings(merged);
    }
}
