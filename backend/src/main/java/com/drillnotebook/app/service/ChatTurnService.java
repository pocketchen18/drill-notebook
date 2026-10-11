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
import com.drillnotebook.app.service.memory.MemoryTimes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
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

    /** 开始轮次所需的最小输入：正文必须已由调用方按协议取定，附件在 P3c 接入。 */
    public record BeginInput(String requestId, int protocolVersion, long sessionId, String userText,
                             String masterPassword, List<Map<String, Object>> attachments,
                             Map<String, Object> requestOptions) {
        public BeginInput {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            requestOptions = requestOptions == null ? Map.of() : Map.copyOf(requestOptions);
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

    /** 上游回来的材料：正文、思考链、厂商停止原因、是否触及捕获上限。 */
    public record UpstreamReply(String text, String reasoning, String finishReason, boolean captureLimited) {
        public static UpstreamReply of(String text, String reasoning, String finishReason) {
            return new UpstreamReply(text, reasoning, finishReason, false);
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
    /** 存储降级门闩：DB 连失败收尾都写不进时置位，新轮次一律 503，探测恢复后自动解除。 */
    private final AtomicBoolean storageDegraded = new AtomicBoolean();
    private final AtomicLong degradedSinceMillis = new AtomicLong();

    public ChatTurnService(ChatTurnRepository turns, ChatSessionMetadataRepository sessions,
                           ChatMessageCipher cipher, ActiveTurnRegistry registry, ProcessIdentity process,
                           ObjectMapper mapper) {
        this.turns = turns;
        this.sessions = sessions;
        this.cipher = cipher;
        this.registry = registry;
        this.process = process;
        this.mapper = mapper;
    }

    /** 开始一轮：校验身份 → 幂等判定 → 登记句柄 → 预留容量并落库 RUNNING + 用户消息。 */
    public BeginResult begin(BeginInput input) {
        require(input.userText() != null && !input.userText().isBlank(), 400, "INPUT_EMPTY", "消息不能为空");
        if (input.protocolVersion() == 2) {
            require(UuidV7.isCanonical(input.requestId()), 400, "REQUEST_ID_INVALID",
                    "requestId 必须是小写规范形 UUIDv7");
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

        String metaJson = requestMeta(input, state, fingerprint);
        ChatMessageCipher.Encrypted metaCipher;
        ChatMessageCipher.Encrypted userCipher;
        try {
            metaCipher = cipher.encrypt(metaJson, input.masterPassword());
            userCipher = cipher.encrypt(input.userText(), input.masterPassword());
        } catch (ChatCryptoException error) {
            // 加密失败就地停止：宁可不开始，也绝不把原话写成明文（§4 不变量）
            log.error("开始轮次时加密失败，未写入任何内容 requestId={}", requestId, error);
            throw reject(500, "CHAT_ENCRYPTION_FAILED", "本地加密不可用，聊天已暂停", Map.of());
        }
        Draft draft = new Draft(requestId, input.sessionId(), state.contextGeneration(),
                state.memoryScopeType(), state.memoryNotebookId() == null ? 0L : state.memoryNotebookId(),
                ZoneId.systemDefault().getId(), process.generation(),
                // v1 明确不做自动提取（§7.1）：只有 v2 且会话开了学习才可能成为记忆来源
                input.protocolVersion() == 2 && state.learnEnabled(), null,
                metaCipher.cipher(), metaCipher.meta(),
                "", userCipher.cipher(), userCipher.meta(), List.of(), MemoryTimes.now());
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
        return swept;
    }

    public boolean storageDegraded() {
        return storageDegraded.get();
    }

    /** 有界探测：只在降级期间跑；先清掉无句柄的孤儿轮次再解除门闩，恢复后仍不自动外发。 */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void probeStorage() {
        if (!storageDegraded.get()) return;
        try {
            for (TurnRow turn : turns.findRunningTurns()) {
                if (registry.isActive(turn.requestId())) continue;
                turns.markInterrupted(turn.requestId(), MemoryTimes.now());
            }
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

    private static String classify(Lease lease, UpstreamReply reply) {
        boolean hasText = reply.text() != null && !reply.text().isBlank();
        if (lease.handle().cancelRequested()) return ChatTurnRepository.STATUS_ABORTED;
        if (reply.captureLimited() || "length".equals(reply.finishReason())) {
            return ChatTurnRepository.STATUS_TRUNCATED;
        }
        return hasText ? ChatTurnRepository.STATUS_COMPLETED : ChatTurnRepository.STATUS_FAILED;
    }

    private static String finishReasonOf(String status, UpstreamReply reply) {
        if (ChatTurnRepository.STATUS_TRUNCATED.equals(status)) {
            return reply.captureLimited() ? "OUTPUT_CAPTURE_LIMIT" : reply.finishReason();
        }
        if (ChatTurnRepository.STATUS_FAILED.equals(status)) return "empty_response";
        return reply.finishReason();
    }

    private String resultMeta(String status, UpstreamReply reply, Map<String, Object> audit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", 1);
        meta.put("status", status);
        meta.put("finishReason", reply.finishReason());
        meta.put("captureLimited", reply.captureLimited);
        if (audit != null) meta.put("audit", audit);
        return bounded(meta);
    }

    private String requestMeta(BeginInput input, ChatSessionMetadataRepository.State state, String fingerprint) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", 2);
        meta.put("protocolVersion", input.protocolVersion());
        meta.put("inputFingerprint", fingerprint);
        meta.put("contextGeneration", state.contextGeneration());
        meta.put("sourceScopeType", state.memoryScopeType());
        meta.put("sourceScopeId", state.memoryNotebookId() == null ? 0L : state.memoryNotebookId());
        meta.put("options", input.requestOptions());
        meta.put("attachmentCount", input.attachments().size());
        return bounded(meta);
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
                .append("attachments=").append(input.attachments()).append('|')
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
