package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.MaintenanceRow;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.ChatMessageCipher;
import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.SourceTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 维护来源装载（执行方案 §2.5 扫描规则、§8.2 第 3-4 条、§8.4 第 3 条）。
 *
 * <p>本类只回答两件事：<b>这批来源能不能用</b>，以及<b>这批输入的准确身份是什么</b>。
 * 开关、授权、预算、冷却都不在这里判（那是队列与 worker 的门禁），因为同一批来源会被
 * 排队和认领两次装载，两次的结论必须完全一致。</p>
 *
 * <p>扫描纪律：</p>
 * <ul>
 *   <li>按 ID 升序、只读有界区间；碰到 RUNNING 立刻停在它之前，绝不把正在生成的轮次当来源；</li>
 *   <li>ABORTED/TRUNCATED/FAILED/INTERRUPTED、跨上下文代次、不高于遗忘边界、已被用户清理原文的
 *       轮次都<b>永久跳过并计数</b>，水位可以越过它们；</li>
 *   <li>提取只用用户发言，助手正文与 reasoning 一律不进提取输入（§4 不变量 2）；
 *       命中凭据格式的用户发言直接不发（§5.3），既不截半句也不当成"没有内容"；</li>
 *   <li>装不下就按完整轮次缩小；单轮本身仍超过剩余预算记 {@link Outcome#SOURCE_TOO_LARGE}，
 *       该轮永久跳过（§8.2 第 4 条）。</li>
 * </ul>
 */
@Service
public class MaintenanceSourceScanner {
    /** 一次装载最多检查的区间行数：批次上限 8 轮，多出来的名额给需要跳过的行。 */
    public static final int MAX_RANGE_ROWS = 32;
    /** 近期压力探测最多读多少行（与上下文投影的一批同宽，不做全量读取）。 */
    static final int RECENT_SCAN_TURNS = 16;

    /** 装载结论。除 {@code READY} 外都不该外发；{@code notice} 给出对应的终态短代码。 */
    public enum Outcome {
        READY,
        /** 区间里没有可用来源（全被永久跳过或本来就没有新轮次）；具体原因看 {@code notice}。 */
        NOTHING_NEW,
        /** 会话正文需要主密码，本次没有：等待解锁，不算永久缺源，也不推进水位。 */
        WAITING_UNLOCK
    }

    /** 跳过计数：只存数值和短代码，不存正文（§2.5）。 */
    public record Skips(int nonCompleted, int wrongGeneration, int belowFloor, int ineligible, int sourceDeleted,
                        int credentials, int unreadable, int tooLarge) {
        int total() {
            return nonCompleted + wrongGeneration + belowFloor + ineligible + sourceDeleted + credentials + unreadable
                    + tooLarge;
        }

        public static Skips none() {
            return new Skips(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 装载结果。{@code examinedThrough} 是<b>最后一个被真正检查过</b>的轮次 ID：
     * 只有它可以推进水位，装不下的那几轮留在它之后等下一批。
     */
    public record Loaded(Outcome outcome, Batch batch, Skips skips, long examinedThrough, String notice) {
        public boolean ready() {
            return outcome == Outcome.READY;
        }
    }

    /** 装载请求：区间、身份材料与解密材料。 */
    public record Request(Kind kind, long sessionId, long fromTurnId, long throughTurnId, long contextGeneration,
                          String scopeType, long scopeId, String timezone, long vaultEpoch, long policyRevision,
                          long summaryRevision, String previousSummary, String targetFingerprint,
                          String promptVersion, int framingTokens, long historyFloor, String sessionPassword,
                          MemoryCryptoService.MemoryKey key) {
    }

    private static final Logger log = LoggerFactory.getLogger(MaintenanceSourceScanner.class);

    private final ChatHistoryRepository history;
    private final ChatMessageCipher cipher;
    private final TokenEstimator estimator;
    private final MemoryCryptoService crypto;
    private final SessionContextRepository contexts;
    private final ObjectMapper mapper;

    public MaintenanceSourceScanner(ChatHistoryRepository history, ChatMessageCipher cipher,
                                    TokenEstimator estimator, MemoryCryptoService crypto,
                                    SessionContextRepository contexts, ObjectMapper mapper) {
        this.history = history;
        this.cipher = cipher;
        this.estimator = estimator;
        this.crypto = crypto;
        this.contexts = contexts;
        this.mapper = mapper;
    }

    /**
     * 当前有效摘要的正文（§8.4 第 3 条的输入之一）。只在与本会话当前代次、当前库 epoch 一致时才认，
     * 解不出来或超出 payload 上限就当没有——宁可重新整理，也不把别代次的内容续进新批次。
     *
     * <p>返回的 {@link PreviousSummary#revision} 同时是批次身份与检查点 CAS 的键。</p>
     */
    public PreviousSummary previousSummary(long sessionId, long contextGeneration, long vaultEpoch,
                                           MemoryCryptoService.MemoryKey key) {
        try {
            Optional<SessionContextRepository.Checkpoint> row = contexts.read(sessionId);
            if (row.isEmpty()) {
                return PreviousSummary.NONE;
            }
            SessionContextRepository.Checkpoint checkpoint = row.get();
            if (checkpoint.contextGeneration() != contextGeneration || checkpoint.vaultEpoch() != vaultEpoch) {
                return PreviousSummary.NONE;
            }
            String payload = crypto.decrypt(key, "ai_session_context", String.valueOf(sessionId),
                    checkpoint.revision(), checkpoint.payloadCipher(), checkpoint.cryptoMeta());
            if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length
                    > MaintenanceRules.SUMMARY_PAYLOAD_BYTE_LIMIT) {
                return PreviousSummary.NONE;
            }
            Map<String, Object> parsed = mapper.readValue(payload, new TypeReference<>() {
            });
            Object summary = parsed.get("modelSummary");
            return new PreviousSummary(checkpoint.revision(), summary == null ? null : String.valueOf(summary));
        } catch (Exception error) {
            return PreviousSummary.NONE;
        }
    }

    /** 旧摘要的 revision 与正文；revision 为 0 表示没有可用摘要。 */
    public record PreviousSummary(long revision, String modelSummary) {
        static final PreviousSummary NONE = new PreviousSummary(0L, null);
    }

    /** 装载一批来源并按 §8.5 派生批次身份。 */
    public Loaded load(Request request) throws SQLException {
        List<MaintenanceRow> rows = history.maintenanceRange(request.sessionId(), request.fromTurnId() - 1L,
                request.throughTurnId(), MAX_RANGE_ROWS);
        if (rows.isEmpty()) {
            return new Loaded(Outcome.NOTHING_NEW, null, Skips.none(), request.fromTurnId() - 1L,
                    MaintenanceRules.NOTICE_NO_NEW_INFORMATION);
        }
        boolean extract = request.kind() == Kind.EXTRACT;
        int remaining = MaintenanceRules.INPUT_TOKEN_BUDGET - Math.max(0, request.framingTokens());
        Counters counters = new Counters();
        List<SourceTurn> turns = new ArrayList<>();
        long previousSummaryTokens = request.previousSummary() == null ? 0
                : estimator.estimateText(request.previousSummary());
        int budget = remaining - (int) previousSummaryTokens;
        long examined = request.fromTurnId() - 1L;
        boolean needsPassword = false;
        for (MaintenanceRow row : rows) {
            examined = row.turnId();
            if (ChatTurnRepository.STATUS_RUNNING.equals(row.status())) {
                // 正在生成的轮次不是来源，也不是"已检查"：水位停在它之前
                examined = row.turnId() - 1L;
                break;
            }
            if (row.contextGeneration() != request.contextGeneration()) {
                counters.wrongGeneration++;
                continue;
            }
            if (row.turnId() <= request.historyFloor()) {
                counters.belowFloor++;
                continue;
            }
            if (row.contentDeletedAt() != null) {
                // 原话已被用户清理：记录 SOURCE_DELETED 并跳过，绝不向模型发送占位（§2.5）
                counters.sourceDeleted++;
                continue;
            }
            if (!ChatTurnRepository.STATUS_COMPLETED.equals(row.status())) {
                counters.nonCompleted++;
                continue;
            }
            if (!row.memoryEligible()) {
                counters.ineligible++;
                continue;
            }
            ChatMessageCipher.Read user = cipher.readLegacy(row.userContent(), row.userContentCipher(),
                    row.userContentMeta(), request.sessionPassword());
            if (user.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD) {
                needsPassword = true;
                break;
            }
            if (user.status() == ChatMessageCipher.ReadStatus.UNREADABLE) {
                // 密文损坏或密钥换了：这轮当永久跳过，不给模型发占位
                counters.unreadable++;
                continue;
            }
            String userText = user.value();
            if (userText == null || userText.isBlank()) {
                counters.ineligible++;
                continue;
            }
            if (extract && MemorySensitivity.classify(userText) == MemorySensitivity.Level.CREDENTIAL) {
                counters.credentials++;
                continue;
            }
            String assistantText = null;
            if (!extract) {
                ChatMessageCipher.Read reply = cipher.readLegacy(row.assistantContent(), row.assistantContentCipher(),
                        row.assistantContentMeta(), request.sessionPassword());
                if (reply.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD) {
                    needsPassword = true;
                    break;
                }
                if (reply.status() == ChatMessageCipher.ReadStatus.UNREADABLE) {
                    counters.unreadable++;
                    continue;
                }
                assistantText = reply.value();
            }
            int tokens = estimator.estimateText(userText)
                    + (assistantText == null ? 0 : estimator.estimateText(assistantText));
            if (turns.size() >= MaintenanceRules.MAX_TURNS_PER_BATCH || tokens > budget) {
                if (turns.isEmpty() && tokens > budget) {
                    // 单轮本身就装不下剩余预算：整轮永久跳过，不截半句冒充完整来源（§8.2 第 4 条）
                    counters.tooLarge++;
                    continue;
                }
                // 已有内容在前的话，这一轮属于下一批，不能算"已经检查过"
                examined = row.turnId() - 1L;
                break;
            }
            budget -= tokens;
            turns.add(new SourceTurn(row.turnId(), row.sourceRevision(), row.userMessageId(),
                    row.assistantMessageId(), userText, assistantText, tokens,
                    MemoryTimes.parseMillis(row.finishedAt())));
        }
        Skips skips = counters.toSkips();
        if (needsPassword) {
            // 密码没给：这批可用来源保持未扫，水位不越过它们（§2.5「不作永久缺源推进」）
            return new Loaded(Outcome.WAITING_UNLOCK, null, skips, examined, MaintenanceRules.NOTICE_WAITING_UNLOCK);
        }
        if (turns.isEmpty()) {
            // 整批都没有可发内容：终态短代码如实区分"本来就没新东西"与"全被永久跳过"（§2.5）
            return new Loaded(Outcome.NOTHING_NEW, null, skips, examined, skipNotice(skips));
        }
        List<Long> rangeIds = history.maintenanceIds(request.sessionId(), request.fromTurnId() - 1L,
                examined, MAX_RANGE_ROWS);
        List<MaintenanceRow> identityRows = history.maintenanceByIds(request.sessionId(), rangeIds);
        String identity = identityOf(request, identityRows, turns);
        int used = turns.stream().mapToInt(SourceTurn::estimatedTokens).sum();
        // from/through 用<b>请求区间</b>而不是"首末条可用轮次"：认领时按同样的区间重算身份才会一致
        Batch batch = new Batch(request.kind(), request.sessionId(), request.fromTurnId(), examined,
                request.contextGeneration(), request.scopeType(), request.scopeId(), request.timezone(),
                request.vaultEpoch(), request.policyRevision(), request.summaryRevision(),
                request.previousSummary(), List.copyOf(turns), Math.max(0, request.framingTokens()),
                used + Math.max(0, request.framingTokens()), identity,
                turns.get(turns.size() - 1).finishedMillis());
        return new Loaded(Outcome.READY, batch, skips, examined, skipNotice(skips));
    }

    /**
     * 批次身份（§8.5）：源 ID 集合与它们的内容版本、区间长度、上下文代次、作用域、库 epoch、
     * 策略版本、旧摘要 revision、目标与提示词/估算器版本一起派生 HMAC。
     *
     * <p>刻意<b>不</b>包含 vault 内容 revision 与既有事实版本：那样一次无关的记忆改动就会让旧批失效，
     * 而 §2.4 明确要求"无关记忆更新不会使旧批失效"。事实状态在提交事务里按 family/版本复验，
     * 那才是它该起作用的地方。区间外的新增轮次同样不在这里，所以新发言不会打断已认领的批次。</p>
     */
    private String identityOf(Request request, List<MaintenanceRow> rangeRows, List<SourceTurn> turns) {
        StringBuilder canonical = new StringBuilder("maintenance-batch-identity-v1")
                .append('|').append(request.kind().name())
                .append('|').append(request.sessionId())
                .append('|').append(request.fromTurnId())
                .append('|').append(request.contextGeneration())
                .append('|').append(request.scopeType()).append(':').append(request.scopeId())
                .append('|').append(request.timezone())
                .append('|').append(request.vaultEpoch())
                .append('|').append(request.policyRevision())
                .append('|').append(request.summaryRevision())
                .append('|').append(request.targetFingerprint())
                .append('|').append(request.promptVersion())
                .append('|').append(TokenEstimator.VERSION)
                .append('|').append(rangeRows.size());
        for (MaintenanceRow row : rangeRows) {
            canonical.append('|').append(row.turnId()).append(':').append(row.sourceRevision())
                    .append(':').append(row.status()).append(':').append(row.memoryEligible() ? 1 : 0)
                    .append(':').append(row.contentDeletedAt() == null ? 0 : 1);
        }
        // 摘要还要把实际参与渲染的正文计入身份：同一区间但正文被改写过不能蒙混过关
        if (request.kind() == Kind.SUMMARY) {
            for (SourceTurn turn : turns) {
                canonical.append('#').append(turn.turnId()).append(':').append(textHash(turn.userText()))
                        .append(':').append(textHash(turn.assistantText()));
            }
            canonical.append('~').append(textHash(request.previousSummary()));
        }
        try {
            return crypto.hmac(request.key(), canonical.toString());
        } catch (MemoryCryptoService.MemoryCryptoException error) {
            log.warn("批次身份计算失败，本批按不可用处理: {}", error.code());
            return null;
        }
    }

    /** 短文本摘要：只取 SHA-256 前 12 字节，用于身份而不用于还原内容。 */
    private static String textHash(String value) {
        if (value == null) return "-";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    /**
     * 近期正文压力（§8.4 第 1 条）：从最新一轮往回读，最多 {@link #RECENT_SCAN_TURNS} 行，
     * 累计到上限就停——这就是"有界"，不是把整个会话读一遍。
     *
     * <p>返回 -1 表示<b>测不准</b>（正文需要主密码而本次没有）：调用方据此不排队，
     * 而不是按 0 压力假装"很空"。</p>
     */
    public int recentPressure(long sessionId, long throughTurnId, long contextGeneration, String sessionPassword) {
        int total = 0;
        try {
            for (ChatHistoryRepository.TurnHistory row : history.turnBatch(sessionId, throughTurnId + 1L,
                    RECENT_SCAN_TURNS)) {
                if (row.contextGeneration() != contextGeneration || row.contentDeletedAt() != null) {
                    continue;
                }
                if (!ChatTurnRepository.STATUS_COMPLETED.equals(row.status())) {
                    continue;
                }
                ChatMessageCipher.Read user = cipher.readLegacy(row.userContent(), row.userContentCipher(),
                        row.userContentMeta(), sessionPassword);
                ChatMessageCipher.Read reply = cipher.readLegacy(row.assistantContent(),
                        row.assistantContentCipher(), row.assistantContentMeta(), sessionPassword);
                if (user.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD
                        || reply.status() == ChatMessageCipher.ReadStatus.NEEDS_PASSWORD) {
                    return -1;
                }
                total += estimator.estimateText(user.value()) + estimator.estimateText(reply.value());
                if (total >= MaintenanceRules.RECENT_TOKEN_BUDGET) {
                    return MaintenanceRules.RECENT_TOKEN_BUDGET;
                }
            }
            return total;
        } catch (SQLException error) {
            return -1;
        }
    }

    /** 永久跳过时的终态短代码：超限优先于删除，两者都没有则为空。 */
    private static String skipNotice(Skips skips) {
        if (skips.tooLarge() > 0) {
            return MaintenanceRules.NOTICE_SOURCE_TOO_LARGE;
        }
        if (skips.sourceDeleted() > 0) {
            return MaintenanceRules.NOTICE_SOURCE_DELETED;
        }
        return null;
    }

    /** 可变计数容器：只在装载循环内部使用。 */
    private static final class Counters {
        private int nonCompleted;
        private int wrongGeneration;
        private int belowFloor;
        private int ineligible;
        private int sourceDeleted;
        private int credentials;
        private int unreadable;
        private int tooLarge;

        Skips toSkips() {
            return new Skips(nonCompleted, wrongGeneration, belowFloor, ineligible, sourceDeleted, credentials,
                    unreadable, tooLarge);
        }
    }
}
