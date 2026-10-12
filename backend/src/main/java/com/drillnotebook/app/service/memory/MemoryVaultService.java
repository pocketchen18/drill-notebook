package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService.GeneratedVaultKey;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryCryptoException;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 记忆库单例的创建、解锁、锁定、改密与重置（执行方案 §6.1，contracts §3 的 vault 行）。
 *
 * <p>钥匙只在本进程内存里存活：解锁成功后 {@link MemoryKey} 驻内存，锁定即清零并通知缓存/维护侧
 * （{@link #onLock}）。闲置满 {@link #IDLE_LOCK_AFTER} 由定时 tick 自动锁定；每次取用钥匙都续期。</p>
 *
 * <p>三条不能破的规矩：</p>
 * <ul>
 *   <li>模式只认库里存的那份，客户端说什么都没用；password 库不能被当 fingerprint 库解锁（§6.1.5）。</li>
 *   <li>创建不覆盖已有 DEK；忘记密码只有显式「重置不可解锁记忆库」这一条路，先删派生记录再换新 ID。</li>
 *   <li>主密码与解出的 DEK 绝不进响应、日志、URL 或队列表；错误只回码表和脱敏描述。</li>
 * </ul>
 *
 * <p>钥匙按次取用（{@link #requireKey()}），上层不得长期持有 {@link MemoryKey}：改密与锁定都会
 * 让旧钥匙立即失效，持旧引用会拿到 {@code MEMORY_LOCKED}，这是有意的 fail-closed 行为。</p>
 */
@Service
public class MemoryVaultService {
    /** 锁定状态下的通用响应码（contracts §3：读取类接口在锁定时返回 423）。 */
    public static final int HTTP_LOCKED = 423;
    /** §6.1.7 的默认闲置锁定时长。 */
    public static final Duration IDLE_LOCK_AFTER = Duration.ofMinutes(15);
    public static final String ACTION_VAULT_CREATE = "VAULT_CREATE";
    public static final String ACTION_VAULT_RESET = "VAULT_RESET";

    /** 显式点名的会话解锁材料：只活在内存里，锁定时清除。 */
    public record SessionUnlock(long sessionId, String password) {
    }

    private static final Logger log = LoggerFactory.getLogger(MemoryVaultService.class);
    private static final long IDLE_LOCK_NANOS = IDLE_LOCK_AFTER.toNanos();

    private final MemoryCryptoService crypto;
    private final MemoryRepository memory;
    private final MemoryOperationRepository operations;
    private final ChatSessionMetadataRepository sessions;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile MemoryKey unlocked;
    private volatile long lastUsedNanos;
    private final Map<Long, String> sessionMaterials = new ConcurrentHashMap<>();
    private final List<Runnable> lockHooks = new CopyOnWriteArrayList<>();
    private final List<Runnable> unlockHooks = new CopyOnWriteArrayList<>();

    public MemoryVaultService(MemoryCryptoService crypto, MemoryRepository memory,
                              MemoryOperationRepository operations, ChatSessionMetadataRepository sessions) {
        this.crypto = crypto;
        this.memory = memory;
        this.operations = operations;
        this.sessions = sessions;
    }

    // ---- 创建 ----

    /**
     * 首次显式创建记忆库：生成随机 DEK、用主材料包装一次入库，并在本进程解锁（向导随后要立刻加第一条记忆）。
     * 已有库返回 409 且绝不改动旧 DEK。
     */
    public MemoryApiResult create(String mode, String password, String operationId) {
        String normalized = normalizeMode(mode);
        if (normalized == null) {
            return MemoryApiResult.error(400, "MEMORY_MODE_INVALID", "记忆库保护模式只能是 fingerprint 或 password");
        }
        if (MemoryCryptoService.MODE_PASSWORD.equals(normalized) && blank(password)) {
            return MemoryApiResult.error(400, "MEMORY_PASSWORD_REQUIRED", "password 模式必须设置主密码");
        }
        String dedupeId = blank(operationId) ? null : operationId.trim();
        MemoryApiResult replay = begin(dedupeId, ACTION_VAULT_CREATE, 201);
        if (replay != null) {
            return replay;
        }
        GeneratedVaultKey generated = null;
        try {
            if (memory.readEnvelope().isPresent()) {
                recordFailure(dedupeId);
                return MemoryApiResult.error(409, "MEMORY_VAULT_EXISTS", "记忆库已存在，本次请求没有改动它");
            }
            String vaultId = UUID.randomUUID().toString();
            generated = crypto.createVaultKey(vaultId, materialFor(normalized, password), normalized);
            if (!memory.createVault(vaultId, generated.wrappedDek(), generated.wrapMeta(), normalized,
                    MemoryTimes.now())) {
                recordFailure(dedupeId);
                return MemoryApiResult.error(409, "MEMORY_VAULT_EXISTS", "记忆库已存在，本次请求没有改动它");
            }
            publishKey(generated.key());
            Map<String, Object> body = statusBody();
            recordSuccess(dedupeId, body);
            log.info("记忆库已创建: mode={}", normalized);
            return MemoryApiResult.of(201, body);
        } catch (MemoryCryptoException | SQLException error) {
            if (generated != null) {
                generated.key().erase();
            }
            recordFailure(dedupeId);
            return error instanceof MemoryCryptoException cryptoFailure
                    ? cryptoError(cryptoFailure) : storageError(error);
        }
    }

    // ---- 解锁 / 锁定 ----

    /**
     * 解锁：模式取库里存的那份，password 库必须给主密码；同时登记显式点名的会话解锁材料。
     * 密码只用于解 DEK，不进响应、日志与任何表。
     */
    public MemoryApiResult unlock(String password, List<SessionUnlock> sessionUnlocks) {
        Optional<MemoryRepository.VaultEnvelope> envelope;
        try {
            envelope = memory.readEnvelope();
        } catch (SQLException error) {
            return storageError(error);
        }
        if (envelope.isEmpty()) {
            return MemoryApiResult.error(409, "MEMORY_VAULT_NOT_INITIALIZED", "还没有记忆库，需要先显式创建");
        }
        MemoryRepository.VaultEnvelope vault = envelope.get();
        String mode = vault.mode();
        if (MemoryCryptoService.MODE_PASSWORD.equals(mode) && blank(password)) {
            return MemoryApiResult.error(400, "MEMORY_PASSWORD_REQUIRED", "password 记忆库需要主密码才能解锁");
        }
        List<SessionUnlock> requested = sessionUnlocks == null ? List.of() : sessionUnlocks;
        for (SessionUnlock entry : requested) {
            if (blank(entry.password())) {
                return MemoryApiResult.error(400, "MEMORY_PASSWORD_REQUIRED",
                        "会话 " + entry.sessionId() + " 的解锁没有提供会话密码");
            }
            try {
                if (sessions.read(entry.sessionId()).isEmpty()) {
                    return MemoryApiResult.error(400, "MEMORY_SESSION_NOT_FOUND", "会话 " + entry.sessionId() + " 不存在");
                }
            } catch (SQLException error) {
                return storageError(error);
            }
        }
        MemoryKey key;
        try {
            key = crypto.unlock(vault.vaultId(), vault.wrappedDek(), vault.wrapMeta(),
                    materialFor(mode, password), mode);
        } catch (MemoryCryptoException error) {
            return cryptoError(error);
        }
        publishKey(key);
        for (SessionUnlock entry : requested) {
            sessionMaterials.put(entry.sessionId(), entry.password());
        }
        log.info("记忆库已解锁: mode={}, 会话解锁 {} 个", mode, requested.size());
        try {
            return MemoryApiResult.of(200, statusBody());
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /** 锁定：清零钥匙、清除会话材料与缓存；已飞出的外部请求结果不得再提交（§6.1.7）。 */
    public MemoryApiResult lock() {
        lockInternal("显式锁定");
        return MemoryApiResult.noContent();
    }

    // ---- 改密（只重包装） ----

    /**
     * 改主密码／从指纹升级到密码：只换 DEK 的包装层，事实一行都不重写。
     * 反向（password → fingerprint）属于降级，接口本身就不提供，因此无从降级。
     */
    public MemoryApiResult changePassword(String oldPassword, String newPassword) {
        if (blank(newPassword)) {
            return MemoryApiResult.error(400, "MEMORY_PASSWORD_REQUIRED", "新主密码不能为空");
        }
        Optional<MemoryRepository.VaultEnvelope> envelope;
        try {
            envelope = memory.readEnvelope();
        } catch (SQLException error) {
            return storageError(error);
        }
        if (envelope.isEmpty()) {
            return MemoryApiResult.error(409, "MEMORY_VAULT_NOT_INITIALIZED", "还没有记忆库，需要先显式创建");
        }
        MemoryRepository.VaultEnvelope vault = envelope.get();
        String oldMode = vault.mode();
        if (MemoryCryptoService.MODE_PASSWORD.equals(oldMode) && blank(oldPassword)) {
            return MemoryApiResult.error(400, "MEMORY_PASSWORD_REQUIRED", "修改密码需要原来的主密码");
        }
        MemoryCryptoService.Rewrapped rewrapped;
        MemoryKey next;
        try {
            rewrapped = crypto.rewrap(vault.wrappedDek(), vault.wrapMeta(), materialFor(oldMode, oldPassword),
                    newPassword, MemoryCryptoService.MODE_PASSWORD);
            next = crypto.unlock(vault.vaultId(), rewrapped.wrappedDek(), rewrapped.wrapMeta(), newPassword,
                    MemoryCryptoService.MODE_PASSWORD);
        } catch (MemoryCryptoException error) {
            return cryptoError(error);
        }
        try {
            memory.updateVaultWrapper(vault.vaultId(), rewrapped.wrappedDek(), rewrapped.wrapMeta(),
                    MemoryCryptoService.MODE_PASSWORD);
        } catch (SQLException error) {
            next.erase();
            return storageError(error);
        }
        publishKey(next);
        log.info("记忆库主密码已更换（仅重包装，模式 {} → password）", oldMode);
        return MemoryApiResult.of(200, statusBodyQuietly());
    }

    // ---- 重置不可解锁的记忆库 ----

    /**
     * 忘记密码的唯一出路：确认后删掉旧库的全部派生记录，装入新 ID 与新 DEK。
     * 旧库的密文从此无法解开，所以旧值必须一起删除，不能留着当垃圾。
     */
    public MemoryApiResult reset(boolean confirm, String mode, String password, String operationId) {
        if (!confirm) {
            return MemoryApiResult.error(409, "MEMORY_RESET_CONFIRMATION_REQUIRED",
                    "重置会永久删除现有记忆库的全部密文，必须显式确认");
        }
        String normalized = normalizeMode(mode);
        if (normalized == null) {
            return MemoryApiResult.error(400, "MEMORY_MODE_INVALID", "记忆库保护模式只能是 fingerprint 或 password");
        }
        if (MemoryCryptoService.MODE_PASSWORD.equals(normalized) && blank(password)) {
            return MemoryApiResult.error(400, "MEMORY_PASSWORD_REQUIRED", "password 模式必须设置主密码");
        }
        String dedupeId = blank(operationId) ? null : operationId.trim();
        MemoryApiResult replay = begin(dedupeId, ACTION_VAULT_RESET, 200);
        if (replay != null) {
            return replay;
        }
        Optional<MemoryRepository.VaultEnvelope> envelope;
        try {
            envelope = memory.readEnvelope();
        } catch (SQLException error) {
            recordFailure(dedupeId);
            return storageError(error);
        }
        if (envelope.isEmpty()) {
            recordFailure(dedupeId);
            return MemoryApiResult.error(409, "MEMORY_VAULT_NOT_INITIALIZED", "还没有记忆库，需要先显式创建");
        }
        GeneratedVaultKey generated = null;
        try {
            String oldVaultId = envelope.get().vaultId();
            String vaultId = UUID.randomUUID().toString();
            generated = crypto.createVaultKey(vaultId, materialFor(normalized, password), normalized);
            memory.resetVault(oldVaultId, vaultId, generated.wrappedDek(), generated.wrapMeta(), normalized,
                    MemoryTimes.now());
            lockInternal("记忆库已重置");
            publishKey(generated.key());
            Map<String, Object> body = statusBody();
            recordSuccess(dedupeId, body);
            return MemoryApiResult.of(200, body);
        } catch (MemoryCryptoException | SQLException error) {
            if (generated != null) {
                generated.key().erase();
            }
            recordFailure(dedupeId);
            return error instanceof MemoryCryptoException cryptoFailure
                    ? cryptoError(cryptoFailure) : storageError(error);
        }
    }

    // ---- 状态与钥匙存取 ----

    /** GET 用的脱敏状态：不含包装层、DEK 与主材料。 */
    public MemoryApiResult status() {
        try {
            return MemoryApiResult.of(200, statusBody());
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    /** 取当前钥匙并续闲置计时；未解锁时抛 {@code MEMORY_LOCKED}（上层映射成 423）。 */
    public MemoryKey requireKey() throws MemoryCryptoException {
        MemoryKey key = unlocked;
        if (key == null || key.erased()) {
            throw new MemoryCryptoException("MEMORY_LOCKED", "记忆库尚未解锁或已锁定，需要重新解锁");
        }
        lastUsedNanos = System.nanoTime();
        return key;
    }

    public Optional<MemoryKey> currentKey() {
        return Optional.ofNullable(unlocked).filter(key -> !key.erased());
    }

    public Optional<String> sessionPassword(long sessionId) {
        return Optional.ofNullable(sessionMaterials.get(sessionId));
    }

    /** 锁定时的联动清理入口：P5 的索引缓存与 P6 的维护 worker 在此登记。 */
    public void onLock(Runnable hook) {
        lockHooks.add(hook);
    }

    /**
     * 拿到钥匙后的联动预热入口（§9.1-1：解锁后就该重建内存检索快照，而不是等第一条聊天）。
     * 钩子必须自己保证不阻塞——它在解锁请求线程里跑，只做提交后台任务这一类工作。
     */
    public void onUnlock(Runnable hook) {
        unlockHooks.add(hook);
    }

    /** 定时 tick：闲置超时自动锁定（生产 60 秒一跳；测试用合成时间直接调 {@link #lockIfIdle(long)}）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void lockIfIdle() {
        lockIfIdle(System.nanoTime());
    }

    boolean lockIfIdle(long nowNanos) {
        if (unlocked == null || nowNanos - lastUsedNanos < IDLE_LOCK_NANOS) {
            return false;
        }
        lockInternal("闲置超时自动锁定");
        return true;
    }

    // ---- 内部 ----

    /** 换入新钥匙（旧钥匙立即作废），然后在监视器外通知预热钩子。 */
    private void publishKey(MemoryKey key) {
        swapKey(key);
        notifyUnlocked();
    }

    private synchronized void swapKey(MemoryKey key) {
        MemoryKey previous = unlocked;
        unlocked = key;
        lastUsedNanos = System.nanoTime();
        if (previous != null) {
            previous.erase();
        }
    }

    /** 预热钩子在监视器外跑：一个慢钩子不该把 {@code requireKey()} 一起锁住。 */
    private void notifyUnlocked() {
        for (Runnable hook : unlockHooks) {
            try {
                hook.run();
            } catch (RuntimeException error) {
                log.warn("解锁联动预热失败: {}", error.getClass().getSimpleName());
            }
        }
    }

    private synchronized void lockInternal(String reason) {
        MemoryKey previous = unlocked;
        unlocked = null;
        sessionMaterials.clear();
        if (previous != null) {
            previous.erase();
        }
        for (Runnable hook : lockHooks) {
            try {
                hook.run();
            } catch (RuntimeException error) {
                log.warn("锁定联动清理失败: {}", error.getClass().getSimpleName());
            }
        }
        log.info("记忆库已锁定: {}", reason);
    }

    /** 脱敏状态体：给 GET/设置快照用，不含包装层、DEK 与主材料。 */
    public Map<String, Object> statusBody() throws SQLException {
        Optional<MemoryRepository.VaultEnvelope> vault = memory.readEnvelope();
        MemoryKey key = unlocked;
        boolean live = key != null && !key.erased();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("initialized", vault.isPresent());
        body.put("unlocked", live);
        body.put("sessionUnlockedCount", sessionMaterials.size());
        vault.ifPresent(row -> {
            body.put("vaultId", row.vaultId());
            body.put("mode", row.mode());
            body.put("epoch", row.epoch());
            body.put("revision", row.revision());
        });
        return body;
    }

    private Map<String, Object> statusBodyQuietly() {
        try {
            return statusBody();
        } catch (SQLException error) {
            return storageErrorBody(error);
        }
    }

    private String materialFor(String mode, String password) {
        return MemoryCryptoService.MODE_PASSWORD.equals(mode) ? password : crypto.fingerprintMaterial();
    }

    /** 相同 operationId + 相同动作已完成 → 回吐原响应；占用或仍在进行 → 409。返回 null 表示可以继续。 */
    private MemoryApiResult begin(String operationId, String action, int replayStatus) {
        if (operationId == null) {
            return null;
        }
        try {
            MemoryOperationRepository.StartResult started =
                    operations.start(operationId, action, null, MemoryTimes.now());
            return switch (started.outcome()) {
                case REPLAY -> MemoryApiResult.of(replayStatus, replayBody(started.resultMeta()));
                case IN_FLIGHT -> MemoryApiResult.error(409, "MEMORY_OPERATION_IN_FLIGHT",
                        "同一操作正在进行中", true, null);
                case CONFLICT -> MemoryApiResult.error(409, "MEMORY_OPERATION_ID_CONFLICT",
                        "operationId 已被其它动作占用");
                case NEW -> null;
            };
        } catch (SQLException error) {
            return storageError(error);
        }
    }

    private void recordSuccess(String operationId, Map<String, Object> body) throws SQLException {
        if (operationId != null) {
            operations.complete(operationId, writeBody(body), MemoryTimes.now());
        }
    }

    /** 元数据写不进去只影响重放时能否回吐原响应，不值得让已成功的动作报错。 */
    private String writeBody(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            log.warn("记忆库响应元数据序列化失败: {}", error.getClass().getSimpleName());
            return null;
        }
    }

    private void recordFailure(String operationId) {
        if (operationId == null) {
            return;
        }
        try {
            operations.abandon(operationId);
        } catch (SQLException error) {
            log.warn("operationId 登记回滚失败: {}", error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> replayBody(String json) throws SQLException {
        if (blank(json)) {
            return statusBody();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            return statusBody();
        }
    }

    private MemoryApiResult cryptoError(MemoryCryptoException error) {
        log.info("记忆库加密层拒绝: {}", error.code());
        return switch (error.code()) {
            case "MEMORY_UNLOCK_FAILED" ->
                    MemoryApiResult.error(401, error.code(), "主密码不对或包装密文已损坏，未解锁任何内容");
            case "MEMORY_SECURITY_MODE_MISMATCH" -> MemoryApiResult.error(409, error.code(),
                    "记忆库的保护模式与本次请求不匹配，不会自动降级");
            case "MEMORY_LOCKED" -> MemoryApiResult.error(HTTP_LOCKED, error.code(),
                    "记忆库已锁定，需要重新解锁");
            default -> MemoryApiResult.error(500, error.code(), "记忆库操作失败，未写入任何内容");
        };
    }

    private MemoryApiResult storageError(Exception error) {
        return new MemoryApiResult(500, storageErrorBody(error));
    }

    private Map<String, Object> storageErrorBody(Exception error) {
        log.warn("记忆库存储层失败: {}", error.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MEMORY_STORAGE_UNAVAILABLE");
        body.put("message", "记忆库存储层暂时不可用");
        return body;
    }

    private static String normalizeMode(String mode) {
        if (mode == null) {
            return null;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return MemoryCryptoService.MODE_FINGERPRINT.equals(normalized)
                || MemoryCryptoService.MODE_PASSWORD.equals(normalized) ? normalized : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
