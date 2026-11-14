package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ToolActivity;
import com.drillnotebook.app.model.AiRecovery;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 本进程正在外发或读取回复的轮次句柄表（执行方案 §7.2.1）。
 *
 * <p>「RUNNING 但没有活跃句柄」是孤儿轮次的唯一判据：DB 里的 RUNNING 说明不了进程还活着，
 * 按年龄清理会误杀慢请求，所以清理线程与开始事务都必须先看这张表。
 * 句柄只在读取任务确实退出后释放——取消不等于释放，不能为了解除 SESSION_BUSY 假装读完。</p>
 */
@Component
public class ActiveTurnRegistry {
    /** 同一时刻的数值快照，避免回复正文转入工具工作集时读到两份。 */
    public record ContextTokens(long outputTokens, Long extraTokens,
                                Map<com.drillnotebook.app.model.RequestContextAudit.Kind, Long> extraCategories) {
        public ContextTokens { extraCategories = Map.copyOf(extraCategories); }
        public ContextTokens(long outputTokens, Long extraTokens) { this(outputTokens, extraTokens, Map.of()); }
    }

    /** 一个进行中的轮次；{@code abortHook} 由上游客户端绑定（关闭响应体），取消时立刻执行。 */
    public static final class Handle {
        private final String requestId;
        private final long sessionId;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile Runnable abortHook;
        private volatile List<ToolActivity> toolActivities = List.of();
        private volatile AiRecovery recovery;
        /** 当前轮尚未落库的回复长度；只保存数值，不保留流式正文。 */
        private volatile ContextTokens contextTokens = new ContextTokens(0, null);

        Handle(String requestId, long sessionId) {
            this.requestId = requestId;
            this.sessionId = sessionId;
        }

        public String requestId() {
            return requestId;
        }

        public long sessionId() {
            return sessionId;
        }

        public boolean cancelRequested() {
            return cancelRequested.get();
        }

        public List<ToolActivity> toolActivities() {
            return toolActivities;
        }

        public AiRecovery recovery() { return recovery; }
        public void recovery(AiRecovery value) { recovery = value; }

        public ContextTokens contextTokens() { return contextTokens; }
        public long contextOutputTokens() { return contextTokens.outputTokens(); }
        public synchronized void contextOutputTokens(long value) {
            contextTokens = new ContextTokens(Math.max(0, value), contextTokens.extraTokens(), contextTokens.extraCategories());
        }
        public Long contextExtraTokens() { return contextTokens.extraTokens(); }
        public synchronized void contextExtraTokens(long value) {
            contextTokens = new ContextTokens(contextTokens.outputTokens(), Math.max(0, value));
        }
        public synchronized void contextExtras(ContextTokenBreakdown value) {
            contextTokens = new ContextTokens(contextTokens.outputTokens(), value.tokens(), value.categories());
        }
        public synchronized void contextTokens(long output, long extra) {
            contextTokens = new ContextTokens(Math.max(0, output), Math.max(0, extra));
        }
        public synchronized void contextTokens(long output, ContextTokenBreakdown extra) {
            contextTokens = new ContextTokens(Math.max(0, output), extra.tokens(), extra.categories());
        }

        public void toolActivities(List<ToolActivity> activities) {
            toolActivities = List.copyOf(activities);
        }

        /** 用户主动停止：置标志并中断尚未结束的读取；重复点击只有第一次算数。 */
        public boolean requestCancel() {
            boolean first = cancelRequested.compareAndSet(false, true);
            Runnable hook = abortHook;
            if (hook != null) hook.run();
            return first;
        }

        public void bindAbortHook(Runnable hook) {
            this.abortHook = hook;
            // 绑定前已经点过停止：立即生效，不能等下一帧
            if (hook != null && cancelRequested.get()) hook.run();
        }
    }

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    /**
     * 在提交 RUNNING 之前抢占句柄。
     *
     * <p>返回空表示这个 requestId 已被别的线程持有：调用方既不能接管，更不能替它释放——
     * 否则真正的读取线程退出时会发现句柄被抹掉，正常请求被误判成孤儿。</p>
     */
    public Optional<Handle> acquire(String requestId, long sessionId) {
        Handle created = new Handle(requestId, sessionId);
        return handles.putIfAbsent(requestId, created) == null ? Optional.of(created) : Optional.empty();
    }

    /** 释放只在读取任务确实退出后执行，且只释放自己登记的那个句柄。 */
    public void release(String requestId, Handle handle) {
        if (handle != null) handles.remove(requestId, handle);
    }

    public Optional<Handle> find(String requestId) {
        return Optional.ofNullable(handles.get(requestId));
    }

    public boolean isActive(String requestId) {
        return handles.containsKey(requestId);
    }

    public int size() {
        return handles.size();
    }
}
