package com.drillnotebook.app.service.web;

import com.drillnotebook.app.service.ActiveTurnRegistry;
import java.util.concurrent.TimeUnit;

public final class WebRequestContext {
    private final long deadline;
    private final ActiveTurnRegistry.Handle handle;

    public WebRequestContext(long milliseconds, ActiveTurnRegistry.Handle handle) {
        this(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(20_000, milliseconds))), handle, true);
    }
    private WebRequestContext(long deadline, ActiveTurnRegistry.Handle handle, boolean ignored) {
        this.deadline = deadline;
        this.handle = handle;
    }
    public WebRequestContext child(long milliseconds) {
        return new WebRequestContext(Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)), handle, true);
    }
    public long remainingMillis() { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    public ActiveTurnRegistry.Handle handle() { return handle; }
    public void check() {
        if (Thread.currentThread().isInterrupted() || handle != null && handle.cancelRequested()) throw new WebFailure(WebFailure.Code.CANCELLED);
        if (remainingMillis() <= 0) throw new WebFailure(WebFailure.Code.TIME_LIMIT);
    }
}
