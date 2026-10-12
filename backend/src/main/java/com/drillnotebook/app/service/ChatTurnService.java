package com.drillnotebook.app.service;

import com.drillnotebook.app.config.ProcessIdentity;
import com.drillnotebook.app.model.UuidV7;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.ChatTurnRepository.Draft;
import com.drillnotebook.app.repository.ChatTurnRepository.StartOutcome;
import com.drillnotebook.app.repository.ChatTurnRepository.StartResult;
import com.drillnotebook.app.repository.ChatTurnRepository.Terminal;
import com.drillnotebook.app.repository.ChatTurnRepository.TerminalOutcome;
import com.drillnotebook.app.repository.ChatTurnRepository.TurnRow;
import com.drillnotebook.app.repository.UsageRepository;
import com.drillnotebook.app.repository.UsageRepository.ActualUsage;
import com.drillnotebook.app.repository.UsageRepository.Attempt;
import com.drillnotebook.app.repository.UsageRepository.AttemptRow;
import com.drillnotebook.app.service.memory.MemoryTimes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 轮次生命周期的唯一入口（执行方案 §7.2/§7.2.1）：开始预留 → 外发 → 收尾结算 → 失败兜底。
 *
 * <p>普通与流式两条路径必须共用 {@link #finalizeTurn}，否则「done 丢失」「部分正文停止」「只思考链」
 * 这些收尾会各长出一套语义。网络调用永远不在事务里：本类只负责事务边界与容量/句柄，
 * 上游调用由 {@code AiService} 在 {@link Lease} 之后发起。</p>
 *
 * <p>与记忆库解耦：未建 vault 时轮次照常工作，{@code memory_eligible} 只按会话开关落库。</p>
 */
@Service
public class ChatTurnService {
    private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);
    /** result_meta 明文上限（contracts §2.7）：超限只留终态，不冒险写无界 JSON。 */
    static final int MAX_RESULT_META_BYTES = 16 * 1024;
    /** 规范化选项只留标识字段（§2.2）；这个天花板是本包自定的，挡的是"拿元数据当垃圾场"。 */
    static final int MAX_REQUEST_OPTIONS_BYTES = 4 * 1024;

    /** 开始轮次所需的最小输入：正文必须已由调用方按协议取定，附件在 P3c 接入。 */
    public record BeginInput(String requestId, int protocolVersion, long sessionId, String userText,
                             String masterPassword, List<Map<String, Object>> attachments,
                             Map<String, Object> requestOptions, String retryOf) {
        public BeginInput {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            requestOptions = requestOptions == null ? Map.of() : Map.copyOf(requestOptions);
        }

        /** 不带 retryOf 的旧形态：普通发言与 v1 都走这里。 */
        public BeginInput(String requestId, int protocolVersion, long sessionId, String userText,
                          String masterPassword, List<Map<String, Object>> attachments,
                          Map<String, Object> requestOptions) {
            this(requestId, protocolVersion, sessionId, userText, masterPassword, attachments, requestOptions, null);
        }
    }

    /** 开始成功后的凭据：拿到它才可以外发，收尾时必须原样交回。 */
    public record Lease(String requestId, long turnId, long sessionId, long userMessageId, String masterPassword,
                       ActiveTurnRegistry.Handle handle) {
    }

    /** 开始结果：STARTED 带 Lease 可外发；REPLAY 带已落库的终态与可展示的回复。 */
    public record BeginResult(String kind, Lease lease, String replayStatus, String replayReply,
                              String replayReasoning) {
        public boolean replayed() {
            return "REPLAY".equals(kind);
        }

        public boolean started() {
            return "STARTED".equals(kind);
        }
    }

    /**
     * 上游回来的材料（§7.2 第 4 条要求的统一形态）：正文、思考链、厂商停止原因、是否触及捕获上限、
     * 是否看到终止帧、前端是否已断、厂商用量。
     */
    public record UpstreamReply(String text, String reasoning, String finishReason, boolean captureLimited,
                                boolean terminalObserved, boolean downstreamGone, AiUpstreamClient.Usage usage) {
        /** 旧的四参形态：按"上游正常结束、无用量信息"补齐，供结构化调用与测试沿用。 */
        public UpstreamReply(String text, String reasoning, String finishReason, boolean captureLimited) {
            this(text, reasoning, finishReason, captureLimited, true, false, AiUpstreamClient.Usage.unknown());
        }

        public static UpstreamReply of(String text, String reasoning, String finishReason) {
            return new UpstreamReply(text, reasoning, finishReason, false);
        }

        /** 把上游结果原样搬进轮次层，终态裁决只在 {@link #classify} 一处发生。 */
        public static UpstreamReply of(AiUpstreamClient.Result result) {
            return new UpstreamReply(result.text(), result.reasoning(), result.finishReason(),
                    result.captureLimited(), result.terminalObserved(), result.downstreamGone(), result.usage());
        }
    }

    /** 收尾结果：状态 + 是否真的落库 + 该给用户看什么。 */
    public record FinalResult(String status, String persistenceStatus, boolean stateDurable, String reply,
                              String reasoning) {
    }

    private final ChatTurnRepository turns;
    private final ChatSessionMetadataRepository sessions;
    private final ChatMessageCipher cipher;
    private final ActiveTurnRegistry registry;
    private final ProcessIdentity process;
    private final ObjectMapper mapper;
    private final UsageRepository usage;
    private final ChatAttachmentService attachments;
    /** 存储降级门闩：DB 连失败收尾都写不进时置位，新轮次一律 503，探测恢复后自动解除。 */
    private final AtomicBoolean storageDegraded = new AtomicBoolean();
    private final AtomicLong degradedSinceMillis = new AtomicLong();

    public ChatTurnService(ChatTurnRepository turns, ChatSessionMetadataRepository sessions,
                           ChatMessageCipher cipher, ActiveTurnRegistry registry, ProcessIdentity process,
                           ObjectMapper mapper, UsageRepository usage, ChatAttachmentService attachments) {
        this.turns = turns;
        this.sessions = sessions;
        this.cipher = cipher;
        this.registry = registry;
        this.process = process;
        this.mapper = mapper;
        this.usage = usage;
        this.attachments = attachments;
    }

    /** 开始一轮：校验身份 → 幂等判定 → 登记句柄 → 预留容量并落库 RUNNING + 用户消息。 */
    public BeginResult begin(BeginInput input) {
        require(input.userText() != null && !input.userText().isBlank(), 400, "INPUT_EMPTY", "消息不能为空");
        if (input.protocolVersion() == 2) {
            require(UuidV7.isCanonical(input.requestId()), 400, "REQUEST_ID_INVALID",
                    "requestId 必须是小写规范形 UUIDv7");
        } else if (!input.attachments().isEmpty()) {
            // 附件的 AAD 绑 requestId，而 v1 的 ID 是服务端后生成的：旧通道不接附件（§4.3/§7.1）
            throw reject(422, "ATTACHMENTS_REQUIRE_V2", "附件通道只在 protocolVersion=2 接受", Map.of());
        }
        if (storageDegraded.get()) {
            throw reject(503, "STORAGE_UNAVAILABLE", "存储暂时不可用，聊天入口已暂停",
                    Map.of("degradedSince", MemoryTimes.format(Instant.ofEpochMilli(degradedSinceMillis.get()))));
        }
        ChatSessionMetadataRepository.State state = readSession(input.sessionId());
        String requestId = input.protocolVersion() == 2 ? input.requestId()
                : UuidV7.generate(System.currentTimeMillis());
        String fingerprint = fingerprint(input, state);
        // 先查已有 ID 再登记句柄：抢在别的线程的句柄上登记会让它后来释放不掉（§7.2.1 伪代码同序）
        Optional<TurnRow> duplicate = findTurnQuietly(requestId);
        if (duplicate.isPresent()) return replay(duplicate.get(), input, fingerprint);
        if (input.protocolVersion() == 2) {
            // 查无记录的新 ID 必须落在时间窗口内，否则旧 ID 可以无限重放（§7.2.2）
            UuidV7.Freshness freshness = UuidV7.freshness(requestId, System.currentTimeMillis());
            if (freshness != UuidV7.Freshness.ACCEPTABLE) {
                throw switch (freshness) {
                    case TOO_OLD -> reject(410, "RESULT_EXPIRED", "requestId 超过 24 小时且没有记录", Map.of());
                    default -> reject(400, "REQUEST_ID_CLOCK_AHEAD", "requestId 时间戳超前于服务器时间", Map.of());
                };
            }
        }
        sweepSessionOrphan(input.sessionId());
        String retryOf = resolveRetryOf(input);

        // 附件必须在开始事务之前就有界校验并封装完：turn/user/附件同事务写入，失败一个都不留（§4.3）
        ChatAttachmentCipher.TurnKey turnKey = attachments.newTurnKey();
        ChatAttachmentService.Prepared prepared;
        ChatMessageCipher.Encrypted metaCipher;
        ChatMessageCipher.Encrypted userCipher;
        try {
            prepared = attachments.prepare(turnKey, requestId, input.attachments());
            ChatMessageCipher.Encrypted attachmentKey = prepared.payloads().isEmpty()
                    ? null : attachments.wrapKey(turnKey, input.masterPassword());
            metaCipher = cipher.encrypt(requestMeta(input, state, fingerprint, prepared.blocks(), attachmentKey),
                    input.masterPassword());
            userCipher = cipher.encrypt(input.userText(), input.masterPassword());
        } catch (ChatTurnRejectException error) {
            throw error;
        } catch (ChatCryptoException error) {
            // 加密失败就地停止：宁可不开始，也绝不把原话写成明文（§4 不变量）
            log.error("开始轮次时加密失败，未写入任何内容 requestId={}", requestId, error);
            throw reject(500, "CHAT_ENCRYPTION_FAILED", "本地加密不可用，聊天已暂停", Map.of());
        } finally {
            // 轮密钥只在封装期间存在，包装完立刻销毁；密文已经带上 AAD 身份，落库不需要明文密钥
            turnKey.zero();
        }
        Draft draft = new Draft(requestId, input.sessionId(), state.contextGeneration(),
                state.memoryScopeType(), state.memoryNotebookId() == null ? 0L : state.memoryNotebookId(),
                ZoneId.systemDefault().getId(), process.generation(),
                // v1 明确不做自动提取（§7.1）：只有 v2 且会话开了学习才可能成为记忆来源
                input.protocolVersion() == 2 && state.learnEnabled(), null,
                metaCipher.cipher(), metaCipher.meta(),
                "", userCipher.cipher(), userCipher.meta(), prepared.payloads(), MemoryTimes.now(), retryOf);
        // 句柄必须在 RUNNING 落库之前登记：反过来会留下「已提交 RUNNING 但查不到句柄」的窗口，
        // 孤儿清扫正好会在这个窗口里误杀正常请求（§7.2.1）。
        ActiveTurnRegistry.Handle handle = registry.acquire(requestId, input.sessionId())
                .orElseThrow(() -> reject(409, "SESSION_BUSY", "相同 requestId 的轮次正在进行",
                        Map.of("statusUrl", statusUrl(requestId))));
        StartResult started;
        try {
            started = turns.begin(draft);
        } catch (SQLException error) {
            registry.release(requestId, handle);
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "轮次无法开始，存储暂时不可用", Map.of());
        } catch (RuntimeException error) {
            registry.release(requestId, handle);
            throw error;
        }
        return switch (started.outcome()) {
            case STARTED -> new BeginResult("STARTED",
                    new Lease(requestId, started.turnId(), input.sessionId(), started.userMessageId(),
                            input.masterPassword(), handle), null, null, null);
            case DUPLICATE_REQUEST -> {
                registry.release(requestId, handle);
                yield replay(started.turn(), input, fingerprint);
            }
            case RUNNING_EXISTS -> {
                registry.release(requestId, handle);
                throw reject(409, "SESSION_BUSY", "该会话已有一轮正在进行",
                        Map.of("runningTurn", runningRequestIdOf(input.sessionId())));
            }
            case HISTORY_STORAGE_FULL, ATTACHMENT_STORAGE_FULL -> {
                registry.release(requestId, handle);
                throw reject(409, started.outcome().name(),
                        started.outcome() == StartOutcome.HISTORY_STORAGE_FULL
                                ? "历史存储已达上限，请先清理或调高额度" : "附件存储已达上限，请先清理或调高额度",
                        Map.of());
            }
        };
    }

    /**
     * 收尾：分类终态 → 加密正文与 result_meta → 一次事务写消息 + 轮次 + 结算预留。
     * 落库失败绝不当成成功：先重读，仍 RUNNING 就用不依赖正文加密的短事务置 FAILED，
     * 连 FAILED 都写不进才打开降级门闩（§7.2.1）。
     */
    public FinalResult finalizeTurn(Lease lease, UpstreamReply reply, Map<String, Object> audit) {
        String status = classify(lease, reply);
        try {
            ChatMessageCipher.Encrypted content = reply.text() == null || reply.text().isEmpty()
                    ? null : cipher.encrypt(reply.text(), lease.masterPassword());
            ChatMessageCipher.Encrypted reasoning = reply.reasoning() == null || reply.reasoning().isEmpty()
                    ? null : cipher.encrypt(reply.reasoning(), lease.masterPassword());
            ChatMessageCipher.Encrypted result = cipher.encrypt(resultMeta(status, reply, audit),
                    lease.masterPassword());
            TerminalOutcome outcome = turns.finalizeTurn(lease.requestId(),
                    new Terminal(status, content == null ? "" : null, content == null ? null : content.cipher(),
                            content == null ? null : content.meta(),
                            reasoning == null ? null : reasoning.cipher(), reasoning == null ? null : reasoning.meta(),
                            result.cipher(), result.meta(), finishReasonOf(status, reply), "saved", MemoryTimes.now()));
            if (!outcome.applied()) {
                // 已被别处收尾（重放/竞争）：这一轮不再写第二次，也不谎称自己保存过
                TurnRow existing = turns.findByRequestId(lease.requestId()).orElseThrow();
                return new FinalResult(existing.status(), existing.persistenceStatus(), true, reply.text(),
                        reply.reasoning());
            }
            return new FinalResult(status, "saved", true, reply.text(), reply.reasoning());
        } catch (SQLException | ChatCryptoException error) {
            log.warn("轮次收尾失败 requestId={}：{}", lease.requestId(), error.toString());
            return failHard(lease, reply, error);
        } finally {
            registry.release(lease.requestId(), lease.handle());
        }
    }

    /** 上游调用之前就失败（配置缺失、参数不合法）：轮次不留 RUNNING。 */
    public FinalResult abandon(Lease lease, String reason) {
        try {
            TerminalOutcome outcome = turns.failFinalization(lease.requestId(), reason, MemoryTimes.now());
            return new FinalResult(outcome.status(), "failed", true, null, null);
        } catch (SQLException error) {
            degrade(error);
            return new FinalResult(ChatTurnRepository.STATUS_FAILED, "failed", false, null, null);
        } finally {
            registry.release(lease.requestId(), lease.handle());
        }
    }

    // ---------- 用量独立结算（contracts §2.6、执行方案 §7.2.1） ----------

    /**
     * 外发之前登记 attempt 并置 SENT：CHAT 只计量、不占后台 20 次/30K 限额，所以预留为 0。
     *
     * <p>记账失败绝不拦住用户的聊天——回复比统计表重要；此时返回 null，后续结算静默跳过。</p>
     *
     * @return attempt ID，登记不可用时为 null
     */
    public String startUsage(Lease lease, String targetFingerprint) {
        ZoneId zone = ZoneId.systemDefault();
        Attempt attempt = new Attempt(UuidV7.generate(System.currentTimeMillis()), lease.requestId(),
                UsageRepository.PURPOSE_CHAT, targetFingerprint, LocalDate.now(zone).toString(), zone.getId(),
                0, MemoryTimes.now());
        try {
            if (!usage.reserve(attempt)) return null;
            usage.markSent(attempt.id());
            return attempt.id();
        } catch (SQLException error) {
            log.warn("用量登记失败，本轮不计量 requestId={}", lease.requestId(), error);
            return null;
        }
    }

    /** 上游返回后按 attempt 幂等结算：正文保存失败不撤销已发生的用量，写失败也不回滚回复。 */
    public void settleUsage(String attemptId, AiUpstreamClient.Usage observed) {
        settle(attemptId, actual(observed));
    }

    private void settle(String attemptId, ActualUsage actual) {
        if (attemptId == null) return;
        try {
            usage.settle(attemptId, actual, MemoryTimes.now());
        } catch (SQLException error) {
            log.warn("用量结算失败 attemptId={}", attemptId, error);
        }
    }

    /** 只有厂商明确回了数字才算 provider；缺字段一律按 unknown 结算，不拿估算冒充当过的钱。 */
    private static ActualUsage actual(AiUpstreamClient.Usage observed) {
        if (observed == null || !observed.reported()) return ActualUsage.unknown();
        return new ActualUsage("provider", observed.inputTokens(), observed.outputTokens(),
                observed.cacheReadTokens(), observed.cacheWriteTokens());
    }

    /**
     * 崩溃遗留的未结算 attempt（§7.2.1）：仍有活跃句柄的一律不碰，
     * 停在 RESERVED 的可证明未发出 → 释放；停在 SENT 的 → 按 UNKNOWN 消费预留。
     *
     * @return 处理的行数
     */
    public int cleanupUnsettledUsage() throws SQLException {
        int handled = 0;
        for (AttemptRow row : usage.findUnsettled()) {
            if (registry.isActive(row.operationId())) continue;
            if ("RESERVED".equals(row.state())) {
                if (usage.releaseUnsent(row.id(), MemoryTimes.now())) handled++;
            } else if (usage.markUnknown(row.id(), MemoryTimes.now())) {
                handled++;
            }
        }
        return handled;
    }

    /** 状态查询：只读，绝不触发重新生成或重新提取。 */
    public Map<String, Object> status(String requestId) {
        TurnRow turn = readTurn(requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", turn.requestId());
        body.put("sessionId", turn.sessionId());
        body.put("status", turn.status());
        body.put("stateDurable", true);
        body.put("persistenceStatus", turn.persistenceStatus());
        body.put("finishReason", turn.finishReason());
        body.put("startedAt", turn.startedAt());
        body.put("finishedAt", turn.finishedAt());
        body.put("active", registry.isActive(requestId));
        body.put("retryOf", turn.retryOf());
        body.put("contentDeleted", turn.contentDeletedAt() != null);
        return body;
    }

    /** 用户停止：置取消标志并关闭上游响应体；正文一个字都没收到时本轮终态是 ABORTED。 */
    public Map<String, Object> cancel(String requestId) {
        TurnRow turn = readTurn(requestId);
        Optional<ActiveTurnRegistry.Handle> handle = registry.find(requestId);
        boolean signalled = handle.map(ActiveTurnRegistry.Handle::requestCancel).orElse(false);
        Map<String, Object> body = new LinkedHashMap<>(status(requestId));
        body.put("cancelSignalled", signalled);
        if (!"RUNNING".equals(turn.status())) body.put("alreadyTerminal", true);
        return body;
    }

    /** 没有活跃句柄的 RUNNING 就是孤儿：置 INTERRUPTED，不重新外发（§7.2-8）。 */
    public int sweepOrphans() {
        int swept = 0;
        List<TurnRow> running;
        try {
            running = turns.findRunningTurns();
        } catch (SQLException error) {
            degrade(error);
            return 0;
        }
        for (TurnRow turn : running) {
            if (registry.isActive(turn.requestId())) continue;
            try {
                if (turns.markInterrupted(turn.requestId(), MemoryTimes.now()).applied()) swept++;
            } catch (SQLException error) {
                degrade(error);
                return swept;
            }
        }
        if (swept > 0) log.info("孤儿轮次已置 INTERRUPTED: {}", swept);
        try {
            int settled = cleanupUnsettledUsage();
            if (settled > 0) log.info("未结算用量已按状态收尾: {}", settled);
        } catch (SQLException error) {
            degrade(error);
        }
        return swept;
    }

    public boolean storageDegraded() {
        return storageDegraded.get();
    }

    /** 有界探测：只在降级期间跑；先清掉无句柄的孤儿轮次与未知用量再解除门闩，恢复后仍不自动外发。 */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void probeStorage() {
        if (!storageDegraded.get()) return;
        try {
            for (TurnRow turn : turns.findRunningTurns()) {
                if (registry.isActive(turn.requestId())) continue;
                turns.markInterrupted(turn.requestId(), MemoryTimes.now());
            }
            cleanupUnsettledUsage();
            storageDegraded.set(false);
            degradedSinceMillis.set(0);
            log.info("存储探测恢复，孤儿轮次已收尾，聊天写入口解除降级");
        } catch (SQLException error) {
            log.debug("存储仍不可用", error);
        }
    }

    /** 进程重启只把遗留 RUNNING 置 INTERRUPTED，绝不替用户重新发出聊天请求（§7.2 第 8 条）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        sweepOrphans();
    }

    private FinalResult failHard(Lease lease, UpstreamReply reply, Throwable cause) {
        try {
            TurnRow existing = turns.findByRequestId(lease.requestId()).orElseThrow();
            if (!ChatTurnRepository.STATUS_RUNNING.equals(existing.status())) {
                return new FinalResult(existing.status(), "saved", true, reply.text(), reply.reasoning());
            }
        } catch (SQLException unreadable) {
            degrade(unreadable);
            return new FinalResult(ChatTurnRepository.STATUS_FAILED, "failed", false, reply.text(), reply.reasoning());
        }
        try {
            TerminalOutcome outcome = turns.failFinalization(lease.requestId(),
                    cause instanceof ChatCryptoException ? "persistence_failed" : "storage_failed",
                    MemoryTimes.now());
            return new FinalResult(outcome.status(), "failed", true, reply.text(), reply.reasoning());
        } catch (SQLException error) {
            degrade(error);
            return new FinalResult(ChatTurnRepository.STATUS_FAILED, "failed", false, reply.text(), reply.reasoning());
        }
    }

    /**
     * 重复 requestId 的裁决：先看是不是仍在跑，再看墓碑，然后比对会话与输入指纹。
     * 同 ID 不同 session 或不同输入一律 409，解不开原元数据只报锁定/密码错误，
     * 绝不把它当成一个"新请求"重新外发（contracts §2.2、§7.2 第 1/7 条）。
     */
    private BeginResult replay(TurnRow turn, BeginInput input, String fingerprint) {
        if (ChatTurnRepository.STATUS_RUNNING.equals(turn.status())) {
            throw reject(409, "SESSION_BUSY", "相同 requestId 的轮次仍在进行",
                    Map.of("statusUrl", statusUrl(turn.requestId())));
        }
        if (turn.contentDeletedAt() != null) {
            throw reject(410, "RESULT_EXPIRED", "该轮次回复已被清理", Map.of());
        }
        if (turn.sessionId() != input.sessionId()) {
            throw reject(409, "REQUEST_CONFLICT", "相同 requestId 属于另一个会话",
                    Map.of("sessionId", turn.sessionId()), false);
        }
        String stored = storedFingerprint(turn.requestId(), input.masterPassword());
        if (stored != null && !stored.equals(fingerprint)) {
            throw reject(409, "REQUEST_CONFLICT", "相同 requestId 的输入与已记录的不一致", Map.of(), false);
        }
        String reply = readMessage(turn.assistantMessageId(), input.masterPassword());
        return new BeginResult("REPLAY", null, turn.status(), reply, null);
    }

    /** 读回原请求指纹；墓碑行没有元数据时返回 null（上面已单独按 410 处理）。 */
    private String storedFingerprint(String requestId, String masterPassword) {
        try {
            Optional<ChatTurnRepository.MetaColumns> meta = turns.findRequestMeta(requestId);
            if (meta.isEmpty() || meta.get().cipher() == null || meta.get().cipher().isBlank()) return null;
            ChatMessageCipher.Read read = cipher.read(meta.get().cipher(), meta.get().crypto(), masterPassword);
            switch (read.status()) {
                case NEEDS_PASSWORD -> throw reject(423, "CHAT_PASSWORD_REQUIRED", "重复请求需要主密码才能核对原请求",
                        Map.of(), true);
                case UNREADABLE -> throw reject(401, "CHAT_PASSWORD_INVALID", "主密码与原始请求不符", Map.of(), false);
                // 没有元数据可解（异常行）时不阻拦重放，交给下面的指纹比对
                case EMPTY -> {
                    return null;
                }
                default -> { }
            }
            return mapper.readTree(read.value()).path("inputFingerprint").asText(null);
        } catch (SQLException error) {
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "请求元数据暂时读不到", Map.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw reject(409, "CHAT_META_UNREADABLE", "请求元数据已损坏，无法核对重复请求", Map.of(), false);
        }
    }

    /** 查不到不报错、也不留下 RUNNING 误判：读失败按降级处理，比让异常穿过 begin 更清楚。 */
    private Optional<TurnRow> findTurnQuietly(String requestId) {
        try {
            return turns.findByRequestId(requestId);
        } catch (SQLException error) {
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "轮次状态暂时读不到", Map.of());
        }
    }

    /**
     * "重新生成"必须指向同一会话里的一条既有终态轮次（§7.2-7）：查不到给 400，跨会话或还在跑给 409，
     * 绝不把它当成一次普通发言静默忽略——否则前端的中断续接提示会被错误地清掉。
     */
    private String resolveRetryOf(BeginInput input) {
        if (input.retryOf() == null || input.retryOf().isBlank()) return null;
        if (input.protocolVersion() != 2) {
            throw reject(422, "RETRY_OF_REQUIRES_V2", "retryOf 只在 protocolVersion=2 接受", Map.of());
        }
        if (!UuidV7.isCanonical(input.retryOf())) {
            throw reject(400, "RETRY_ID_INVALID", "retryOf 必须是小写规范形 UUIDv7", Map.of());
        }
        TurnRow target = findTurnQuietly(input.retryOf())
                .orElseThrow(() -> reject(400, "RETRY_TARGET_UNKNOWN", "要重新生成的轮次不存在", Map.of()));
        if (target.sessionId() != input.sessionId()) {
            throw reject(409, "RETRY_TARGET_FOREIGN", "要重新生成的轮次属于另一个会话",
                    Map.of("sessionId", target.sessionId()));
        }
        if (ChatTurnRepository.STATUS_RUNNING.equals(target.status())) {
            throw reject(409, "RETRY_TARGET_ACTIVE", "上一轮还在进行，不能同时开两轮",
                    Map.of("statusUrl", statusUrl(target.requestId())));
        }
        return target.requestId();
    }

    /**
     * 每个新请求都做一次孤儿检查（§7.2.1）：本会话有 RUNNING 却没有活跃句柄，说明上一个进程或
     * 已退出的线程把它丢下了，先置 INTERRUPTED 再开始，否则唯一索引会一直 SESSION_BUSY。
     */
    private void sweepSessionOrphan(long sessionId) {
        Optional<TurnRow> running;
        try {
            running = turns.findRunning(sessionId);
        } catch (SQLException error) {
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "会话轮次状态暂时读不到", Map.of());
        }
        if (running.isEmpty() || registry.isActive(running.get().requestId())) return;
        try {
            turns.markInterrupted(running.get().requestId(), MemoryTimes.now());
            log.info("开始新轮次前清理孤儿 RUNNING: requestId={}", running.get().requestId());
        } catch (SQLException error) {
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "孤儿轮次无法收尾", Map.of());
        }
    }

    private static String statusUrl(String requestId) {
        return "/api/ai/turns/" + requestId;
    }

    /** 只为把 requestId 带给前端轮询状态，读不到就留空，不能因为一次附带读失败而换掉主错误。 */
    private String runningRequestIdOf(long sessionId) {
        try {
            return turns.findRunning(sessionId).map(TurnRow::requestId).orElse("");
        } catch (SQLException error) {
            log.warn("读取会话进行中轮次失败 sessionId={}", sessionId, error);
            return "";
        }
    }

    private String readMessage(Long messageId, String masterPassword) {
        if (messageId == null) return null;
        try {
            return turns.findMessage(messageId)
                    .map(row -> cipher.readLegacy(row.content(), row.contentCipher(), row.contentMeta(),
                            masterPassword).orSentinel())
                    .orElse(null);
        } catch (SQLException error) {
            degrade(error);
            return "[加密消息：无法解密]";
        }
    }

    private TurnRow readTurn(String requestId) {
        require(UuidV7.isCanonical(requestId), 400, "REQUEST_ID_INVALID", "requestId 必须是小写规范形 UUIDv7");
        try {
            Optional<TurnRow> found = turns.findByRequestId(requestId);
            if (found.isPresent()) return found.get();
            // 查无此 ID：只接受 24 小时内、且不比服务器时间超前 5 分钟的 ID（§7.2.2）
            UuidV7.Freshness freshness = UuidV7.freshness(requestId, System.currentTimeMillis());
            throw switch (freshness) {
                case ACCEPTABLE -> reject(404, "TURN_NOT_FOUND", "没有这个轮次", Map.of());
                case TOO_OLD -> reject(410, "RESULT_EXPIRED", "该请求已过期且没有记录", Map.of());
                case CLOCK_AHEAD -> reject(400, "REQUEST_ID_CLOCK_AHEAD", "requestId 时间戳超前于服务器时间",
                        Map.of());
            };
        } catch (SQLException error) {
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "轮次状态暂时读不到", Map.of());
        }
    }

    /**
     * 终态裁决（§7.2 第 4 条）：只有"看到终止帧 + 正文非空"才算 COMPLETED；
     * 用户停止或前端断开算 ABORTED；无终止帧/读流失败算 FAILED；触达捕获上限算 TRUNCATED。
     * 晚到的正文不能让被取消的结果重新 COMPLETED，也不能进入学习（§7.2.2）。
     */
    private static String classify(Lease lease, UpstreamReply reply) {
        boolean hasText = reply.text() != null && !reply.text().isBlank();
        if (lease.handle().cancelRequested() || reply.downstreamGone()) {
            return ChatTurnRepository.STATUS_ABORTED;
        }
        if (reply.captureLimited() || "length".equals(reply.finishReason())) {
            return ChatTurnRepository.STATUS_TRUNCATED;
        }
        if (!reply.terminalObserved() || !hasText) return ChatTurnRepository.STATUS_FAILED;
        return ChatTurnRepository.STATUS_COMPLETED;
    }

    private static String finishReasonOf(String status, UpstreamReply reply) {
        if (ChatTurnRepository.STATUS_TRUNCATED.equals(status)) {
            return reply.captureLimited() ? "OUTPUT_CAPTURE_LIMIT" : reply.finishReason();
        }
        if (ChatTurnRepository.STATUS_FAILED.equals(status)) {
            // 无终止帧/读流失败要留下可诊断的原因，不能一律写成 empty_response
            return reply.terminalObserved() ? "empty_response" : reply.finishReason();
        }
        return reply.finishReason();
    }

    private String resultMeta(String status, UpstreamReply reply, Map<String, Object> audit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", 1);
        meta.put("status", status);
        meta.put("finishReason", reply.finishReason());
        meta.put("captureLimited", reply.captureLimited);
        meta.put("terminalObserved", reply.terminalObserved);
        meta.put("downstreamGone", reply.downstreamGone);
        meta.put("usage", Map.of(
                "source", reply.usage() == null ? "unknown" : reply.usage().source(),
                "inputTokens", reply.usage() == null || reply.usage().inputTokens() == null
                        ? 0 : reply.usage().inputTokens(),
                "outputTokens", reply.usage() == null || reply.usage().outputTokens() == null
                        ? 0 : reply.usage().outputTokens()));
        if (audit != null) meta.put("audit", audit);
        return bounded(meta);
    }

    private String requestMeta(BeginInput input, ChatSessionMetadataRepository.State state, String fingerprint,
                               List<Map<String, Object>> attachmentBlocks,
                               ChatMessageCipher.Encrypted attachmentKey) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", 2);
        meta.put("protocolVersion", input.protocolVersion());
        meta.put("inputFingerprint", fingerprint);
        meta.put("contextGeneration", state.contextGeneration());
        meta.put("sourceScopeType", state.memoryScopeType());
        meta.put("sourceScopeId", state.memoryNotebookId() == null ? 0L : state.memoryNotebookId());
        meta.put("options", validatedOptions(input.requestOptions()));
        meta.put("attachmentCount", input.attachments().size());
        // 块身份是 v2 重放与 quote 绑定的权威来源（§2.2/§4.3）：u0 自带正文，附件只带 ID 与内容指纹
        meta.put("sourceBlocks", ChatAttachmentService.orderedBlocks(input.userText(), attachmentBlocks));
        if (attachmentKey != null) {
            meta.put("attachmentKey", Map.of("cipher", attachmentKey.cipher(), "meta", attachmentKey.meta()));
        }
        return serializeRequestMeta(meta);
    }

    /**
     * 进元数据的选项只留标识/版本/指纹（§2.2），客户端塞进来的超长值要挡住：
     * 否则一条请求就能把用户的历史额度当垃圾场，而这份 blob 除了占地方没有任何消费者。
     */
    private Map<String, Object> validatedOptions(Map<String, Object> options) {
        String json;
        try {
            json = mapper.writeValueAsString(options);
        } catch (Exception error) {
            throw reject(500, "CHAT_META_UNWRITABLE", "请求选项无法序列化", Map.of());
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_OPTIONS_BYTES) {
            throw reject(413, "REQUEST_OPTIONS_TOO_LARGE", "规范化选项超过 4 KiB，请只传标识字段",
                    Map.of("limitBytes", MAX_REQUEST_OPTIONS_BYTES));
        }
        return options;
    }

    /**
     * request_meta 整体不设"截断"退路：它含 attachmentKey，压成最小 JSON 会让附件解不开却仍占容量，
     * 等于把用户内容写成死数据。正文与附件各有自己的上限，超出由账本按 HISTORY_STORAGE_FULL 拒。
     */
    private String serializeRequestMeta(Map<String, Object> meta) {
        try {
            return mapper.writeValueAsString(meta);
        } catch (Exception error) {
            throw reject(500, "CHAT_META_UNWRITABLE", "请求元数据无法序列化", Map.of());
        }
    }

    /** 输入指纹：覆盖有效请求字段而非仅用户文本，写进密文里，避免对短发言做字典猜测。 */
    private String fingerprint(BeginInput input, ChatSessionMetadataRepository.State state) {
        StringBuilder canonical = new StringBuilder()
                .append("pv=").append(input.protocolVersion()).append('|')
                .append("session=").append(input.sessionId()).append('|')
                .append("scope=").append(state.memoryScopeType()).append(':')
                .append(state.memoryNotebookId() == null ? 0L : state.memoryNotebookId()).append('|')
                .append("text=").append(input.userText().codePointCount(0, input.userText().length())).append('|')
                .append("text=").append(input.userText()).append('|')
                .append("attachments=").append(ChatAttachmentService.identity(input.attachments())).append('|')
                .append("options=").append(new TreeMap<>(input.requestOptions()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private String bounded(Map<String, Object> meta) {
        try {
            String json = mapper.writeValueAsString(meta);
            if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_META_BYTES) return json;
            Map<String, Object> minimal = new LinkedHashMap<>();
            minimal.put("schemaVersion", meta.get("schemaVersion"));
            minimal.put("status", meta.get("status"));
            minimal.put("truncatedMeta", true);
            return mapper.writeValueAsString(minimal);
        } catch (Exception error) {
            return "{\"truncatedMeta\":true}";
        }
    }

    private ChatSessionMetadataRepository.State readSession(long sessionId) {
        try {
            return sessions.read(sessionId).orElseThrow(() -> reject(404, "SESSION_NOT_FOUND", "会话不存在",
                    Map.of()));
        } catch (SQLException error) {
            degrade(error);
            throw reject(503, "STORAGE_UNAVAILABLE", "会话状态暂时读不到", Map.of());
        }
    }

    private void degrade(SQLException error) {
        storageDegraded.set(true);
        degradedSinceMillis.compareAndSet(0, System.currentTimeMillis());
        log.error("AI 存储写入口降级：{}", error.toString());
    }

    private static void require(boolean condition, int status, String code, String message) {
        if (!condition) throw reject(status, code, message, Map.of());
    }

    private static ChatTurnRejectException reject(int status, String code, String message,
                                                  Map<String, Object> details) {
        return new ChatTurnRejectException(status, code, message, details);
    }

    /** 告诉前端这次失败能不能重试：等锁/等存储可以，指纹不符、密码错误不可以。 */
    private static ChatTurnRejectException reject(int status, String code, String message,
                                                  Map<String, Object> details, boolean retryable) {
        Map<String, Object> withRetry = new LinkedHashMap<>(details);
        withRetry.put("retryable", retryable);
        return new ChatTurnRejectException(status, code, message, withRetry);
    }
}
