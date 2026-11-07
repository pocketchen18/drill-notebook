package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.AiConfigRepository.ConfigRow;
import java.util.HashMap;
import java.util.Map;

/** 活跃请求持有自己的配置快照；删除与登记使用同一个锁。 */
public final class AiConnectionUsage implements AutoCloseable {
    public static final Object LOCK = new Object();
    private static final Map<String, Integer> ACTIVE = new HashMap<>();
    private final String id;
    private AiConnectionUsage(String id) { this.id = id; }
    public static AiConnectionUsage acquire(ConfigRow row) {
        String id = AiModelLimits.params(row).getOrDefault("modelConfigId", "").toString();
        return acquireId(id);
    }
    public static AiConnectionUsage acquireProvider(String id) { return acquireId("provider:" + id); }
    private static AiConnectionUsage acquireId(String id) {
        synchronized (LOCK) { if (!id.isBlank()) ACTIVE.merge(id, 1, Integer::sum); }
        return new AiConnectionUsage(id);
    }
    public static boolean active(String id) { synchronized (LOCK) { return ACTIVE.containsKey(id); } }
    public static boolean providerActive(String id) { return active("provider:" + id); }
    @Override public void close() {
        synchronized (LOCK) { ACTIVE.computeIfPresent(id, (key, count) -> count <= 1 ? null : count - 1); }
    }
}
