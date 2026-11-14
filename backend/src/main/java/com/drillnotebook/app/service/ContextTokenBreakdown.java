package com.drillnotebook.app.service;

import com.drillnotebook.app.model.RequestContextAudit.Kind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** 当前保留上下文的数值分类；与占用总量共用一次计数，不保存正文。 */
public record ContextTokenBreakdown(Map<Kind, Long> categories) {
    public ContextTokenBreakdown {
        var copy = new EnumMap<Kind, Long>(Kind.class);
        categories.forEach((kind, value) -> {
            if (kind == null || value == null || value < 0) throw new IllegalArgumentException("上下文分类数值无效");
            if (value > 0) copy.put(kind, value);
        });
        categories = Collections.unmodifiableMap(copy);
        // 在接受持久元数据时也检查总和溢出。
        long total = 0;
        for (long value : categories.values()) total = Math.addExact(total, value);
    }

    public long tokens() {
        long total = 0;
        for (long value : categories.values()) total = Math.addExact(total, value);
        return total;
    }

    static final class Builder {
        private final Map<Kind, Long> categories = new EnumMap<>(Kind.class);
        void add(Kind kind, long tokens) {
            if (tokens < 0) throw new IllegalArgumentException("上下文分类数值无效");
            if (tokens > 0) categories.merge(kind, tokens, Math::addExact);
        }
        void add(ContextTokenBreakdown value) { value.categories().forEach(this::add); }
        ContextTokenBreakdown build() { return new ContextTokenBreakdown(categories); }
    }
}
