package com.drillnotebook.app.model;

import java.util.List;
import java.util.EnumSet;
import java.util.HashSet;

/** 每次请求的有界数值摘要；不保存提示词、工具参数、工具结果正文或附件字节。 */
public record RequestContextAudit(int schemaVersion, String estimatorVersion, String model, String kind,
                                  int estimatedInputTokens, int contextWindow, List<Category> categories,
                                  boolean detailsTruncated) {
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 32 * 1024;
    public static final int MAX_ITEMS = 256;

    public enum Kind { SYSTEM, TOOL_DEFINITIONS, USER, INJECTED_CONTEXT, ASSISTANT, TOOL_CALLS, TOOL_RESULTS, IMAGE, OTHER }
    public record Item(String key, String name, int estimatedTokens) { }
    public record Category(Kind kind, int estimatedTokens, int itemCount, List<Item> items) {
        public Category { items = List.copyOf(items); }
    }

    public RequestContextAudit {
        categories = List.copyOf(categories);
        if (schemaVersion != VERSION || estimatorVersion == null || model == null
                || !"CHAT".equals(kind) && !"NATIVE_WEB".equals(kind)
                || estimatedInputTokens < 0 || contextWindow < 0 || categories.size() > Kind.values().length)
            throw new IllegalArgumentException("请求上下文摘要格式不支持");
        var kinds = EnumSet.noneOf(Kind.class);
        long total = 0;
        int details = 0;
        for (Category category : categories) {
            if (category.kind() == null || category.estimatedTokens() < 0 || category.itemCount() < 0
                    || !kinds.add(category.kind()) || category.items().size() > category.itemCount()
                    || !detailsTruncated && category.items().size() != category.itemCount())
                throw new IllegalArgumentException("请求上下文分类无效");
            total += category.estimatedTokens();
            details += category.items().size();
            long shown = 0;
            var keys = new HashSet<String>();
            for (Item item : category.items()) {
                if (item.estimatedTokens() < 0 || item.key() == null || item.key().length() > 80
                        || !keys.add(item.key())
                        || item.name() != null && !item.name().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("请求上下文明细无效");
                shown += item.estimatedTokens();
            }
            if (shown > category.estimatedTokens()) throw new IllegalArgumentException("请求上下文明细合计无效");
        }
        if (details > MAX_ITEMS || total != estimatedInputTokens)
            throw new IllegalArgumentException("请求上下文合计无效");
    }
}
