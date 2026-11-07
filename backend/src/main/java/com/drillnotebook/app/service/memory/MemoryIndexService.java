package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService.MemoryKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 记忆检索快照的构建与缓存（执行方案 §9.1 第 1 条、§14 P5）：解锁后<b>异步、可取消</b>地分页解密，
 * 建出一份 {@link MemorySearchIndex} 不可变快照；只存内存，不建 FTS 明文表、不落任何磁盘缓存。
 *
 * <p>三条硬规矩：</p>
 * <ul>
 *   <li><b>读取不触发同步构建</b>：{@link #ready} 只查已有快照，缺快照时由
 *       {@link #requestBuild} 提交后台任务；读侧可在已有预算内等待，超时才降级。</li>
 *   <li><b>版本一致</b>：快照绑定 {@code (vaultId, epoch, revision)}，任何写路径推进 revision 后旧快照
 *       立刻不可用；读路径永远只看到整份换入的不可变快照，不会读到半构建状态。</li>
 *   <li><b>锁定即清</b>：登记为 {@link MemoryVaultService#onLock} 钩子，钥匙没了缓存也就没了（S11）。</li>
 * </ul>
 *
 * <p>"可取消"的实现是分页边界检查世代号，而不是打断 JDBC：正在解的那一行做完就退出。
 * 中断一个 SQLite 连接只会把 SQLException 甩给别的查询，换不来更快的停止。</p>
 */
@Service
public class MemoryIndexService {
    /** 一页取多少行解密：太小会让后台任务被 SQL 往返拖长，太大则取消粒度变粗。 */
    static final int PAGE_ROWS = 200;
    /** 快照最多装多少条 ACTIVE（§9.1-7 的规模口径）：超出只保留最近记录的，按 recorded_at 倒序分页天然如此。 */
    public static final int MAX_INDEXED_FACTS = 4_000;
    /** 一次构建的总预算：超时就把这一份丢掉，下一轮再重建，不让后台任务无限跑下去。 */
    static final long BUILD_BUDGET_MILLIS = 20_000;
    /** contracts §5：keywords 最多 8 个，索引侧再兜一道，防坏载荷撑爆快照。 */
    static final int MAX_KEYWORDS_PER_FACT = 8;

    /** 一份不可变快照及其身份；{@code truncated} 表示库里还有没进索引的更旧事实。 */
    public record Snapshot(String vaultId, long epoch, long revision, MemorySearchIndex index, int corruptRows,
                          boolean truncated, long builtAtMillis) {

        boolean matches(String candidateVaultId, long candidateEpoch, long candidateRevision) {
            return vaultId.equals(candidateVaultId) && epoch == candidateEpoch && revision == candidateRevision;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexService.class);

    private final MemoryRepository memory;
    private final MemoryVaultService vault;
    private final MemoryCryptoService crypto;
    private final MemorySuppressionRepository suppressions;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    private volatile Snapshot snapshot;
    /** 已排队/在飞任务对应的库版本；同一版本重复请求不再重启构建，否则连续聊天会把构建永远打断。 */
    private volatile String queuedIdentity = "";
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Future<?>> inFlight = new AtomicReference<>();
    private final Object buildLock = new Object();

    public MemoryIndexService(MemoryRepository memory, MemoryVaultService vault, MemoryCryptoService crypto,
                              MemorySuppressionRepository suppressions, ObjectMapper mapper) {
        this.memory = memory;
        this.vault = vault;
        this.crypto = crypto;
        this.suppressions = suppressions;
        this.mapper = mapper;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "memory-index-build");
            thread.setDaemon(true);
            return thread;
        });
        // 锁定清缓存；解锁（含改密/重置后重新拿到钥匙）就预热，不等第一条聊天来触发
        vault.onLock(this::clear);
        vault.onUnlock(this::warmUp);
    }

    /** 当前可用快照，且必须与调用方看到的库版本一致；不一致时顺带提交后台重建。 */
    public Optional<Snapshot> ready(String vaultId, long epoch, long revision) {
        Snapshot current = snapshot;
        if (current != null && current.matches(vaultId, epoch, revision) && !locked()) {
            return Optional.of(current);
        }
        if (current != null && current.matches(vaultId, epoch, revision)) {
            clear();
        }
        requestBuild(vaultId, epoch, revision);
        return Optional.empty();
    }

    /** 等待同一份后台任务，不重复构建；超时不取消任务，后续读取仍可复用其结果。 */
    public Optional<Snapshot> awaitReady(String vaultId, long epoch, long revision, long deadlineNanos) {
        Optional<Snapshot> ready = ready(vaultId, epoch, revision);
        if (ready.isPresent()) return ready;
        Future<?> task;
        synchronized (buildLock) {
            task = identity(vaultId, epoch, revision).equals(queuedIdentity) ? inFlight.get() : null;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (task != null && remaining > 0) {
            try {
                task.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (CancellationException | ExecutionException | TimeoutException ignored) {
                // 版本切换、后台失败或预算耗尽时仍由读侧返回不可用，不拖住聊天。
            }
        }
        Snapshot current = snapshot;
        return current != null && current.matches(vaultId, epoch, revision) && !locked()
                ? Optional.of(current) : Optional.empty();
    }

    /** 钥匙不在了：此时"没有快照"的原因是被锁定，而不是还在建（客户端文案要分得清）。 */
    public boolean locked() {
        return vault.currentKey().isEmpty();
    }

    /** 提交一次构建：同一版本已经在排队/在飞就什么都不做；版本变了先取消旧任务再排队。 */
    public void requestBuild(String vaultId, long epoch, long revision) {
        Optional<MemoryKey> key = vault.currentKey();
        if (key.isEmpty() || !vaultId.equals(key.get().vaultId())) {
            return;
        }
        String identity = identity(vaultId, epoch, revision);
        synchronized (buildLock) {
            Snapshot current = snapshot;
            if ((current != null && current.matches(vaultId, epoch, revision)) || identity.equals(queuedIdentity)) {
                return;
            }
            long myGeneration = generation.incrementAndGet();
            queuedIdentity = identity;
            Future<?> previous = inFlight.getAndSet(null);
            if (previous != null && !previous.isDone()) {
                previous.cancel(false);
            }
            inFlight.set(executor.submit(() -> runBuild(myGeneration, key.get(), vaultId, epoch, revision)));
        }
    }

    /** 解锁钩子：拿当前库版本直接预热（不阻塞解锁响应）。 */
    void warmUp() {
        try {
            Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
            if (envelope.isEmpty()) {
                return;
            }
            requestBuild(envelope.get().vaultId(), envelope.get().epoch(), envelope.get().revision());
        } catch (SQLException error) {
            log.warn("解锁后预热记忆索引失败，本轮按无记忆降级: {}", error.toString());
        }
    }

    /** 锁定钩子：丢弃快照并取消在飞任务。密钥没了还留着解密正文，就是明文泄漏。 */
    public void clear() {
        synchronized (buildLock) {
            generation.incrementAndGet();
            Future<?> previous = inFlight.getAndSet(null);
            if (previous != null) {
                previous.cancel(false);
            }
            snapshot = null;
            queuedIdentity = "";
        }
    }

    /** 某作用域内被抑制的 family 键（§9.1 第 5 条的"移除…suppression"）。 */
    public Set<String> suppressedFamilies(String vaultId, String scopeType, long scopeId) throws SQLException {
        Set<String> keys = new HashSet<>(suppressions.activeKeys(vaultId, scopeType, scopeId,
                MemorySuppressionRepository.KIND_FAMILY));
        return Set.copyOf(keys);
    }

    /** 同步构建并换入快照：给测试和"这一轮就要用上"的场景用，与后台任务同一份实现。 */
    public Snapshot buildNow(String vaultId, long epoch, long revision) throws SQLException {
        MemoryKey key = vault.currentKey().orElse(null);
        if (key == null || !vaultId.equals(key.vaultId())) {
            throw new SQLException("记忆库未解锁，无法构建检索快照");
        }
        long myGeneration;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (buildLock) {
            myGeneration = generation.incrementAndGet();
            Future<?> previous = inFlight.getAndSet(completion);
            if (previous != null) previous.cancel(false);
            // 同版本读者复用本次构建，不能另起后台任务把写入后的同步构建打断。
            queuedIdentity = identity(vaultId, epoch, revision);
        }
        try {
            Snapshot built = build(key, vaultId, epoch, revision, myGeneration);
            if (!install(built, key, myGeneration)) {
                throw new SQLException("记忆索引快照构建被取消");
            }
            return built;
        } finally {
            synchronized (buildLock) {
                if (myGeneration == generation.get()) {
                    queuedIdentity = "";
                    inFlight.set(null);
                }
            }
            completion.complete(null);
        }
    }

    @PreDestroy
    public void shutdown() {
        clear();
        executor.shutdownNow();
    }

    private void runBuild(long myGeneration, MemoryKey key, String vaultId, long epoch, long revision) {
        try {
            if (myGeneration != generation.get() || key.erased()) {
                return;
            }
            Snapshot built = build(key, vaultId, epoch, revision, myGeneration);
            install(built, key, myGeneration);
        } catch (SQLException | RuntimeException error) {
            log.warn("记忆索引快照构建失败，本轮按无记忆降级: {}", error.toString());
        } finally {
            synchronized (buildLock) {
                if (myGeneration == generation.get()) {
                    queuedIdentity = "";
                    inFlight.set(null);
                }
            }
        }
    }

    /** 只有仍对应当前库版本的整份快照才能换入，锁定/较新的构建不能被旧任务覆盖。 */
    private boolean install(Snapshot built, MemoryKey key, long myGeneration) throws SQLException {
        if (built == null || key.erased()) return false;
        var current = memory.readVault(built.vaultId());
        if (current.isEmpty() || !built.matches(current.get().vaultId(), current.get().epoch(),
                current.get().revision())) return false;
        synchronized (buildLock) {
            if (myGeneration != generation.get() || key.erased()) return false;
            snapshot = built;
            return true;
        }
    }

    private static String identity(String vaultId, long epoch, long revision) {
        return vaultId + "/" + epoch + "/" + revision;
    }

    private Snapshot build(MemoryKey key, String vaultId, long epoch, long revision, long myGeneration)
            throws SQLException {
        long deadlineNanos = System.nanoTime() + BUILD_BUDGET_MILLIS * 1_000_000L;
        List<MemorySearchIndex.Doc> docs = new ArrayList<>();
        int corrupt = 0;
        String beforeRecordedAt = null;
        String beforeRowId = null;
        boolean truncated = false;
        while (true) {
            if (myGeneration != generation.get() || key.erased()) {
                return null;
            }
            if (System.nanoTime() > deadlineNanos) {
                log.warn("记忆索引构建超过预算，放弃这一份快照: vaultId={}", vaultId);
                return null;
            }
            List<MemoryRepository.FactRow> page = memory.findPage(vaultId, MemoryRepository.STATUS_ACTIVE, null,
                    null, beforeRecordedAt, beforeRowId, PAGE_ROWS);
            if (page.isEmpty()) {
                break;
            }
            for (MemoryRepository.FactRow row : page) {
                if (docs.size() >= MAX_INDEXED_FACTS) {
                    truncated = true;
                    break;
                }
                Map<String, Object> payload = decrypt(key, row);
                if (payload == null) {
                    corrupt++;
                    continue;
                }
                docs.add(toDoc(row, payload));
            }
            MemoryRepository.FactRow last = page.get(page.size() - 1);
            beforeRecordedAt = last.recordedAt();
            beforeRowId = last.rowId();
            if (truncated || page.size() < PAGE_ROWS) {
                break;
            }
        }
        return new Snapshot(vaultId, epoch, revision, MemorySearchIndex.build(docs), corrupt, truncated,
                System.currentTimeMillis());
    }

    private MemorySearchIndex.Doc toDoc(MemoryRepository.FactRow row, Map<String, Object> payload) {
        return new MemorySearchIndex.Doc(row.memoryId(), row.version(), text(payload.get("kind"), row.kind()),
                text(payload.get("predicate"), ""), text(payload.get("value"), ""), row.scopeType(), row.scopeId(),
                row.familyKeyHmac(), row.predicateKeyHmac(), parseInstant(row.validUntil()),
                parseInstant(row.lastConfirmedAt(), 0), keywords(payload.get("keywords")));
    }

    /** 载荷里的检索扩展词（contracts §5 的 keywords[]，上限 8）；手工事实没有这个字段。 */
    private static List<String> keywords(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String word = String.valueOf(item).trim();
            if (!word.isEmpty()) {
                words.add(word);
            }
            if (words.size() >= MAX_KEYWORDS_PER_FACT) {
                break;
            }
        }
        return words;
    }

    private Map<String, Object> decrypt(MemoryKey key, MemoryRepository.FactRow row) {
        try {
            String json = crypto.decrypt(key, "ai_memory_item", row.rowId(), row.version(), row.payloadCipher(),
                    row.cryptoMeta());
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            // 单行损坏只隔离这一条，不把乱码送进模型（M14）
            return null;
        }
    }

    private static long parseInstant(String value) {
        return parseInstant(value, Long.MAX_VALUE);
    }

    /** 时间串解析失败按"已经过期"处理：不能因为一条坏数据把事实一直注入下去。 */
    private static long parseInstant(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException error) {
            return fallback == Long.MAX_VALUE ? 0 : fallback;
        }
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
