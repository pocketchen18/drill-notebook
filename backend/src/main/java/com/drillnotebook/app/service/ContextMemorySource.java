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

    /**
     * 按当前会话可见范围列出记忆标题（不返回正文）。
     *
     * @param scopeFilter GLOBAL / NOTEBOOK / ALL；调用方已校验 NOTEBOOK 只在笔记本会话合法
     */
    Listing list(String vaultId, String scopeType, long scopeId, String scopeFilter, long nowMillis,
                 int deadlineMillis, int limit);

    /** 一条列表项：标题已按 UI 口径从正文派生，不含记忆 ID、内部谓词或密文。 */
    record ListedItem(String title, String scopeType, long scopeId, Long updatedAtEpochMilli) {
    }

    /** 列表结果：{@code hasMore} 表示还有未返回的合格行；{@code truncated} 表示快照本身被截断。 */
    record Listing(List<ListedItem> items, boolean hasMore, boolean truncated, int corruptRows,
                   Availability availability) {
        public static final Listing EMPTY = new Listing(List.of(), false, false, 0, Availability.READY);

        public Listing {
            items = items == null ? List.of() : List.copyOf(items);
            availability = availability == null ? Availability.READY : availability;
            corruptRows = Math.max(0, corruptRows);
        }

        public static Listing unavailable(Availability availability) {
            return new Listing(List.of(), false, false, 0, availability);
        }
    }

    /** 一条候选事实：正文已解密，只到组装器为止，绝不写进审计或元数据。 */
    record Fact(String memoryId, int version, String kind, String predicate, String value, String scopeType,
                long scopeId, Long validUntilEpochMilli) {
        /** 旧调用方不关心有效期时保持兼容；null 表示永不过期。 */
        public Fact(String memoryId, int version, String kind, String predicate, String value, String scopeType,
                    long scopeId) {
            this(memoryId, version, kind, predicate, value, scopeType, scopeId, null);
        }
    }

    /** 选择结果：入选事实 + 诊断（看了多少候选、是否因超时降级、快照是否残缺）。 */
    record Selection(List<Fact> topical, List<Fact> style, int candidatesSeen, boolean degradedByDeadline,
                     Availability availability, boolean truncated, int corruptRows) {
        public static final Selection EMPTY = new Selection(List.of(), List.of(), 0, false, Availability.READY,
                false, 0);

        public Selection {
            topical = topical == null ? List.of() : List.copyOf(topical);
            style = style == null ? List.of() : List.copyOf(style);
            availability = availability == null ? Availability.READY : availability;
            corruptRows = Math.max(0, corruptRows);
        }

        /** P4 时期的四参数构造保持可用：没有快照概念的调用方默认"记忆区可用"。 */
        public Selection(List<Fact> topical, List<Fact> style, int candidatesSeen, boolean degradedByDeadline) {
            this(topical, style, candidatesSeen, degradedByDeadline, Availability.READY, false, 0);
        }

        /** P5 早期只带可用状态的五参数构造保持可用。 */
        public Selection(List<Fact> topical, List<Fact> style, int candidatesSeen, boolean degradedByDeadline,
                         Availability availability) {
            this(topical, style, candidatesSeen, degradedByDeadline, availability, false, 0);
        }

        public static Selection unavailable(Availability availability) {
            return new Selection(List.of(), List.of(), 0, availability == Availability.TIMEOUT, availability,
                    false, 0);
        }
    }

    /**
     * 记忆区本轮到底为什么是空的（§9.1-1"给出状态"）：
     * 光看"没有记忆"分不出是锁着、快照还在建、还是没有相关候选，而客户端必须能说清。
     */
    enum Availability {
        /** 快照就绪：空列表就是"没有相关事实"（§9.1-6）。 */
        READY,
        /** 解锁后快照还没建好或已过期正在重建：本轮不使用记忆。 */
        BUILDING,
        /** 记忆库未解锁或没有库。 */
        LOCKED,
        /** 检索超过总时限：本轮不使用记忆（§9.1-7）。 */
        TIMEOUT,
        /** 本轮请求没有打开记忆使用开关。 */
        NOT_REQUESTED,
        /** 策略或授权不允许本轮使用记忆。 */
        DENIED
    }
}
