package com.drillnotebook.app.service;

import java.util.Set;
import java.util.List;
import java.util.Map;

/** Explicit in-process inheritance; never deserialized from client input. */
final class SessionExecution {
    static final ThreadLocal<SessionExecution> CURRENT = new ThreadLocal<>();
    static final java.util.concurrent.Semaphore UPSTREAM = new java.util.concurrent.Semaphore(8,true);
    final AiTurnBudget budget;
    final Set<Long> ancestors;
    record Supplement(String requestId,Map<String,Object> body,int offset) { }
    interface Steering {
        List<Supplement> take(boolean finishing,int offset);
        void sending(List<Supplement> batch);
        void received(List<Supplement> batch);
    }
    Steering steering;
    /** 由会话工具派发而来的任务：不提供删除工具，删除只能出自用户直接提出的消息。 */
    boolean delegated;
    String acceptedRequestId;
    SessionExecution(AiTurnBudget budget, Set<Long> ancestors) { this.budget = budget; this.ancestors = Set.copyOf(ancestors); }
}
