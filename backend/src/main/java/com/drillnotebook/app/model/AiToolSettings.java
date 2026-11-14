package com.drillnotebook.app.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 内置工具的全局启停偏好，独立于供应商和模型；只保存与默认值不同的显式选择。 */
public record AiToolSettings(Map<String, Boolean> overrides) {
    public record Tool(String name, String group) { }

    /** 设置页的完整目录；模型能力与记忆授权仍在对话入口单独检查。 */
    public static final List<Tool> TOOLS = List.of(
            new Tool("search_web", "web"), new Tool("fetch_web", "web"),
            new Tool("read_current_page", "material"), new Tool("search_materials", "material"),
            new Tool("read_notebook", "material"), new Tool("read_knowledge", "material"),
            new Tool("read_question_bank", "material"), new Tool("read_wrong_questions", "material"),
            new Tool("create_material", "material"), new Tool("update_material", "material"),
            new Tool("search_memories", "memory"), new Tool("list_memories", "memory"),
            new Tool("create_memory", "memory"), new Tool("update_memory", "memory"),
            new Tool("list_sessions", "session"), new Tool("read_session", "session"),
            new Tool("create_session", "session"), new Tool("send_message", "session"),
            new Tool("delete_memory", "delete"), new Tool("delete_session", "delete"),
            new Tool("delete_material", "delete"), new Tool("remove_wrong_question", "delete"));
    /** 删除类工具默认关闭；随版本新增的删除工具同样从关闭开始。 */
    public static final List<String> DELETE_TOOLS = TOOLS.stream().filter(tool -> tool.group().equals("delete"))
            .map(Tool::name).toList();
    public static final AiToolSettings DEFAULTS = new AiToolSettings(Map.of());
    /** 读设置异常时不能使用默认开启值，以免绕过用户已经关闭的普通工具。 */
    public static final AiToolSettings ALL_DISABLED = new AiToolSettings(TOOLS.stream()
            .collect(java.util.stream.Collectors.toMap(Tool::name, tool -> false)));

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

    /** 全部内置工具都受全局开关管理。 */
    public static boolean managed(String tool) {
        return tool != null && TOOLS.stream().anyMatch(entry -> entry.name().equals(tool));
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
        Map<String, Boolean> merged = new LinkedHashMap<>(overrides);
        merged.putAll(changes);
        return new AiToolSettings(merged);
    }
}
