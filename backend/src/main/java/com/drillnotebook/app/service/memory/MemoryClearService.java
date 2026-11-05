package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository.ScanKind;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemoryRepository.PurgePass;
import com.drillnotebook.app.repository.MemoryRepository.VaultEnvelope;
import com.drillnotebook.app.repository.MemoryRepository.VaultState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 清空全部 AI 记忆（执行方案 §11.3、contracts §3 的 {@code POST /memory/clear}）。
 *
 * <p>顺序是这件事的关键：<b>先立边界，再删数据</b>。停用学习与使用 → 推进 vault epoch 并把每个会话的扫描
 * 水位推到它当前的最大轮次 → 才分批删行。epoch 一读就废（内存快照按 epoch 取，作业提交按 epoch 复验），
 * 所以删到一半也不会被读到，崩溃重来也只是继续删，不需要"整库擦除"。</p>
 *
 * <p>删不完不能假 204：本趟还有剩余行就回 202 并把新 epoch 交给客户端，下一趟用新 epoch 继续。
 * 清空只承诺本地 SQL 行被删除，不承诺旧备份、SSD 残留或已经发出去的厂商日志（§11.3 末段）。</p>
 */
@Service
public class MemoryClearService {
    public static final String ACTION_VAULT_CLEAR = "MEMORY_VAULT_CLEAR";
    /** 一趟删除的行预算：够日常体量一次删完，又不至于让一次请求锁住太久。 */
    public static final int CLEAR_BATCH_ROWS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(MemoryClearService.class);

    private final MemoryRepository memory;
    private final MemoryPolicyService policy;
    private final ChatSessionMetadataRepository metadata;
    private final MemoryOperationRepository operations;
    private final ObjectMapper mapper;

    public MemoryClearService(MemoryRepository memory, MemoryPolicyService policy,
                              ChatSessionMetadataRepository metadata, MemoryOperationRepository operations,
                              ObjectMapper mapper) {
        this.memory = memory;
        this.policy = policy;
        this.metadata = metadata;
        this.operations = operations;
        this.mapper = mapper;
    }

    public MemoryApiResult clear(boolean confirm, Long expectedVaultEpoch, String operationId) {
        return clear(confirm, expectedVaultEpoch, operationId, CLEAR_BATCH_ROWS);
    }

    /** {@code budget} 是行预算，测试用小预算走 202 分支；生产入口只有上面那个重载。 */
    MemoryApiResult clear(boolean confirm, Long expectedVaultEpoch, String operationId, int budget) {
        if (!confirm) {
            return MemoryApiResult.error(409, "MEMORY_CLEAR_CONFIRMATION_REQUIRED",
                    "清空会删除本机全部 AI 记忆密文，必须显式确认");
        }
        if (expectedVaultEpoch == null) {
            return MemoryApiResult.error(400, "MEMORY_EPOCH_REQUIRED", "清空必须带上读到的记忆库 epoch");
        }
        Optional<VaultEnvelope> envelope;
        try {
            envelope = memory.readEnvelope();
        } catch (SQLException error) {
            return storageError(error);
        }
        if (envelope.isEmpty()) {
            return MemoryApiResult.error(409, "MEMORY_VAULT_NOT_INITIALIZED", "还没有记忆库，没有可清空的内容");
        }
        VaultEnvelope vault = envelope.get();
        // 去重要排在 epoch 判定之前：第一趟已经把边界推走，客户端拿旧 epoch 重试同一 operationId
        // 必须看到当初那份响应（含 202），而不是被 409 顶回来再猜自己到底清没清。
        String dedupeId = blank(operationId) ? null : operationId.trim();
        MemoryApiResult replay = begin(dedupeId, vault.vaultId());
        if (replay != null) {
            return replay;
        }
        if (vault.epoch() != expectedVaultEpoch) {
            abandon(dedupeId);
            return MemoryApiResult.error(409, "MEMORY_EPOCH_CHANGED", "记忆库已经又推进过一轮遗忘边界，请重载后确认",
                    false, Map.of("epoch", vault.epoch()));
        }
        try {
            MemoryApiResult disabled = disableLearningAndUse();
            if (disabled.status() >= 400) {
                abandon(dedupeId);
                return disabled;
            }
            VaultState rotated = memory.bumpEpoch(vault.vaultId());
            int floored = metadata.advanceAllScanWatermarks(ScanKind.MEMORY)
                    + metadata.advanceAllScanWatermarks(ScanKind.SUMMARY);
            PurgePass pass = memory.purgeVaultBatch(vault.vaultId(), budget);
            Map<String, Object> purged = new LinkedHashMap<>();
            purged.put("facts", pass.factsDeleted());
            purged.put("evidence", pass.evidenceDeleted());
            purged.put("proposals", pass.proposalsDeleted());
            purged.put("suppressions", pass.suppressionsDeleted());
            purged.put("jobs", pass.jobsDeleted());
            purged.put("contexts", pass.contextsDeleted());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cleared", !pass.remaining());
            body.put("epoch", rotated.epoch());
            body.put("revision", rotated.revision());
            body.put("memoryEnabled", false);
            body.put("autoExtract", false);
            body.put("scanWatermarksAdvanced", floored);
            body.put("purged", purged);
            if (pass.remaining()) {
                body.put("remaining", true);
                body.put("message", "还有内容没删完，请用返回的 epoch 再清一次");
            }
            int status = pass.remaining() ? 202 : 200;
            record(dedupeId, status, body);
            log.info("记忆库已清空: vaultId={}, epoch={}, 本趟删除={}行, 未完={}", vault.vaultId(), rotated.epoch(),
                    pass.factsDeleted() + pass.evidenceDeleted() + pass.proposalsDeleted() + pass.suppressionsDeleted()
                            + pass.jobsDeleted() + pass.contextsDeleted(), pass.remaining());
            return MemoryApiResult.of(status, body);
        } catch (SQLException error) {
            abandon(dedupeId);
            return storageError(error);
        }
    }

    /**
     * 停用"使用记忆"与"自动学习"（§11.3：清空后默认两者都关，重新启用只学新消息）。
     * 走的是设置行自己的 CAS，撞车就回 409 让用户重载，不偷偷改写别人刚改的开关。
     */
    private MemoryApiResult disableLearningAndUse() {
        Map<String, Object> snapshot = policy.snapshot().body();
        Object revision = snapshot.get("revision");
        if (!(revision instanceof Number number)) {
            return MemoryApiResult.error(500, "MEMORY_STORAGE_UNAVAILABLE", "记忆存储层暂时不可用");
        }
        return policy.update(number.longValue(), false, false, null, null, null);
    }

    // ---- operationId 去重（§3：清空也要防双击重放）----

    private MemoryApiResult begin(String operationId, String vaultId) {
        if (operationId == null) {
            return null;
        }
        try {
            MemoryOperationRepository.StartResult started = operations.start(operationId, ACTION_VAULT_CLEAR,
                    vaultId, MemoryTimes.now());
            return switch (started.outcome()) {
                case REPLAY -> replayed(started.resultMeta());
                case IN_FLIGHT -> MemoryApiResult.error(409, "MEMORY_OPERATION_IN_FLIGHT", "同一操作正在进行中",
                        true, null);
                case CONFLICT -> MemoryApiResult.error(409, "MEMORY_OPERATION_ID_CONFLICT",
                        "operationId 已被别的动作或内容占用");
                case NEW -> null;
            };
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /** 重放要连当初的状态码一起还回去：202 的清空不能被重放成"已经清完"。 */
    private MemoryApiResult replayed(String json) {
        Map<String, Object> stored = read(json);
        Map<String, Object> body = new LinkedHashMap<>(stored);
        Object status = body.remove("httpStatus");
        body.put("replayed", true);
        return MemoryApiResult.of(status instanceof Number number ? number.intValue() : 200, body);
    }

    private void record(String operationId, int status, Map<String, Object> body) throws SQLException {
        if (operationId == null) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>(body);
        meta.put("httpStatus", status);
        try {
            operations.complete(operationId, mapper.writeValueAsString(meta), MemoryTimes.now());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new SQLException("清空响应无法登记", error);
        }
    }

    private void abandon(String operationId) {
        if (operationId == null) {
            return;
        }
        try {
            operations.abandon(operationId);
        } catch (SQLException error) {
            log.warn("operationId 登记回滚失败: {}", error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> read(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static MemoryApiResult storageError(Exception error) {
        log.warn("清空记忆存储层失败: {}", error.getMessage());
        return MemoryApiResult.error(500, "MEMORY_STORAGE_UNAVAILABLE", "记忆存储层暂时不可用");
    }
}
