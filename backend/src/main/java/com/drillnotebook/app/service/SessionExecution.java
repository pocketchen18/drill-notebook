package com.drillnotebook.app.service;

import java.util.Set;

/** Explicit in-process inheritance; never deserialized from client input. */
final class SessionExecution {
    static final ThreadLocal<SessionExecution> CURRENT = new ThreadLocal<>();
    static final java.util.concurrent.Semaphore UPSTREAM = new java.util.concurrent.Semaphore(8,true);
    final AiTurnBudget budget;
    final Set<Long> ancestors;
    SessionExecution(AiTurnBudget budget, Set<Long> ancestors) { this.budget = budget; this.ancestors = Set.copyOf(ancestors); }
}
