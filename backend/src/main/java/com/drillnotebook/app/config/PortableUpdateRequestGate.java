package com.drillnotebook.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevents a new business write racing the updater's final idle check and graceful context close. */
@Component
public class PortableUpdateRequestGate extends OncePerRequestFilter {
    private int writes;
    private boolean closing;

    public synchronized boolean hasWrites() { return writes > 0; }
    public synchronized boolean beginClose() {
        if (closing || writes > 0) return false;
        closing = true;
        return true;
    }
    public synchronized void resume() { closing = false; }

    /** Counts queued and running background writes together with HTTP writes. */
    public synchronized TaskLease enterTask() {
        if (closing) return null;
        writes++;
        return new TaskLease();
    }
    public final class TaskLease implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        @Override public void close() {
            if (released.compareAndSet(false, true)) synchronized (PortableUpdateRequestGate.this) { writes--; }
        }
    }
    public boolean executeBackground(Executor executor, Runnable task) {
        TaskLease lease = enterTask();
        if (lease == null) return false;
        try { executor.execute(() -> { try { task.run(); } finally { lease.close(); } }); }
        catch (RuntimeException error) { lease.close(); throw error; }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean write = request.getRequestURI().startsWith("/api/")
                && !request.getRequestURI().startsWith("/api/app/update/")
                && !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD") && !request.getMethod().equals("OPTIONS");
        if (!write) { chain.doFilter(request, response); return; }
        synchronized (this) {
            if (closing) {
                response.setStatus(503);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"UPDATE_RESTARTING\",\"message\":\"正在保存并准备更新，请稍候\"}");
                return;
            }
            writes++;
        }
        try { chain.doFilter(request, response); }
        finally { synchronized (this) { writes--; } }
    }
}
