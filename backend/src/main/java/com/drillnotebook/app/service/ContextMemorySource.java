package com.drillnotebook.app.service;

import java.util.List;

/**
 * 上下文记忆来源（执行方案 §9.2 的"跨会话记忆"区）：组装器只认这个接口，
 * 排序算法属于 P5（内存 BM25 索引），P4 先接"手工记忆 + 最小心词相关性"这一份可用实现。
 *
 * <p>硬约束：只允许返回<b>已解锁、合法状态</b>的事实；无相关候选就交空表，
 * 绝不为了凑满 3 条把无关记忆塞进 prompt（§9.1-6、M12）。</p>
 */
public interface ContextMemorySource {

    /**
     * @param vaultId      当前记忆库 ID；锁定或没有库时调用方不应进来
     * @param scopeType    会话作用域（GLOBAL / NOTEBOOK）
     * @param scopeId      NOTEBOOK 时为正数笔记本 ID
     * @param queryText    当前用户输入，只用于词法相关性判定，不做查询改写
     * @param nowMillis    到期判定用的时钟（便于测试注入）
     * @param deadlineMillis 整次选择的总时限，超时按"本轮不使用记忆"降级
     */
    Selection select(String vaultId, String scopeType, long scopeId, String queryText, long nowMillis,
                     int deadlineMillis);

    /** 一条候选事实：正文已解密，只到组装器为止，绝不写进审计或元数据。 */
    record Fact(String memoryId, int version, String kind, String predicate, String value, String scopeType,
                long scopeId) {
    }

    /** 选择结果：入选事实 + 诊断（看了多少候选、是否因超时降级）。 */
    record Selection(List<Fact> topical, List<Fact> style, int candidatesSeen, boolean degradedByDeadline) {
        public static final Selection EMPTY = new Selection(List.of(), List.of(), 0, false);

        public Selection {
            topical = topical == null ? List.of() : List.copyOf(topical);
            style = style == null ? List.of() : List.copyOf(style);
        }
    }
}
