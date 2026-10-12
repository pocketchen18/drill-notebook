package com.drillnotebook.app.service;

import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.AttachmentStub;
import com.drillnotebook.app.repository.ChatHistoryRepository.LegacyMessage;
import com.drillnotebook.app.repository.ChatHistoryRepository.TurnHistory;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.SessionContextRepository;
import com.drillnotebook.app.service.memory.MemoryCryptoService;
import com.drillnotebook.app.service.memory.MemoryPolicyService;
import com.drillnotebook.app.service.memory.MemoryVaultService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 唯一的历史投影（执行方案 §9.4、contracts §4.5）：固定顺序
 * 读会话版本 → 取有效摘要检查点 → 倒序有界扫描 → 过滤非法来源 → 交给组装器。
 *
 * <p>四条硬规则：</p>
 * <ul>
 *   <li>只排除被<b>有效 coverage</b> 替代的原轮次，绝不按 {@code through_turn_id} 一刀切；</li>
 *   <li>一批最多 16 轮、单请求最多扫 32 轮，更早的内容只能由有效检查点代表，不做全量读取再过滤；</li>
 *   <li>本轮正在跑的 RUNNING 轮次不算自己的历史——当前用户输入由请求单独放最后；</li>
 *   <li>coverage 也不能把最近 2 个完整轮次整段替掉（§9.2 近期区目标）。</li>
 * </ul>
 *
 * <p>展示资格与注入资格是两回事：这里只产出注入候选，历史展示分页走
 * {@code ChatHistoryRepository#page}，不经过本类。</p>
 */
@Service
public class ConversationProjection {
    /** 一批最多 16 轮（§7.2.2 尾段）。 */
    public static final int BATCH_TURNS = 16;
    /** 单请求最多扫 32 轮。 */
    public static final int MAX_SCANNED_TURNS = 32;
    /** 摘要加密 payload 明文上限（§2.5）：超过就不信任这份检查点。 */
    static final int MAX_SUMMARY_PAYLOAD_BYTES = 32 * 1024;
    /** coverage 段数上限（§2.5）：超了旧检查点失效，从当前窗口重建并标缺口。 */
    static final int MAX_COVERAGE_RANGES = 128;
    /** 最近这么多条完整轮次永远按原文保留，摘要替不掉。 */
    static final int MIN_RECENT_TURNS = 2;

    private static final Logger log = LoggerFactory.getLogger(ConversationProjection.class);
    /** 检查点存在却用不了时的统一提示：裁剪不能被说成"已经总结过"（T03）。 */
    public static final String NOTICE_SUMMARY_UNAVAILABLE = "SUMMARY_UNAVAILABLE";

    /** 一次投影所需的最小上下文：本轮 turnId 之前的历史才可能是候选。 */
    public record Request(long sessionId, long currentTurnId, long contextGeneration, String masterPassword,
                          int recentTokenBudget) {
    }

    /** 一条候选：正文已解密，附件只带身份（要不要取密文由组装器按图片策略决定）。 */
    public record Item(long turnId, String requestId, boolean legacy, Long userMessageId, Long assistantMessageId,
                       String userText, String assistantText, List<AttachmentStub> attachments, String startedAt,
                       int estimatedTokens) {
    }

    /**
     * 检查点的两用视图：{@code audit} 是按契约形状落进审计的那份（不含正文），
     * {@code modelSummary} 只在这一轮交给组装器，绝不落进 result_meta。
     */
    public record SummaryView(ContextAudit.Summary audit, String modelSummary, int estimatedTokens) {
    }

    /** 投影结果：候选按时间升序，外加扫描范围、有效摘要、中断态与会话版本。 */
    public record Projection(List<Item> candidates, ContextAudit.Scan scan, SummaryView summary,
                             ContextAudit.ResumeState resumeState, List<String> notices, long historyRevision,
                             List<Item> coveredCandidates, Long observedSummaryRevision) {
    }

    /** 扫描中间态：候选 + 各类排除的计数，最后统一进审计。 */
    private static final class Scan {
        private final List<Item> newestFirst = new ArrayList<>();
        /** 被摘要 coverage 排除的原文（新→旧）：摘要若被预算舍弃，按它做有界回补（§9.4）。 */
        private final List<Item> coveredFirst = new ArrayList<>();
        private int wrongGeneration;
        private int tombstoned;
        private int unfinished;
        private int legacyIncomplete;
        private int unreadable;
        private int covered;
        private int completeSeen;
        private int rowsRead;
        private Long oldestScannedTurn;
    }

    private final ChatHistoryRepository history;
    private final SessionContextRepository checkpoints;
    private final ChatSessionMetadataRepository sessions;
    private final ChatMessageCipher cipher;
    private final MemoryRepository memory;
    private final MemoryCryptoService crypto;
    private final MemoryVaultService vault;
    private final MemoryPolicyService policy;
    private final TokenEstimator estimator;
    private final ObjectMapper mapper;

    public ConversationProjection(ChatHistoryRepository history, SessionContextRepository checkpoints,
                                  ChatSessionMetadataRepository sessions, ChatMessageCipher cipher,
                                  MemoryRepository memory, MemoryCryptoService crypto, MemoryVaultService vault,
                                  MemoryPolicyService policy, TokenEstimator estimator, ObjectMapper mapper) {
        this.history = history;
        this.checkpoints = checkpoints;
        this.sessions = sessions;
        this.cipher = cipher;
        this.memory = memory;
        this.crypto = crypto;
        this.vault = vault;
        this.policy = policy;
        this.estimator = estimator;
        this.mapper = mapper;
    }

    /**
     * 构建候选。审计草稿由调用方传入：投影阶段的每一次排除都要落进同一份账本，
     * 而不是另建一套"为什么少了"的说法。
     */
    public Projection project(Request request, ContextAudit.Builder audit) throws SQLException {
        List<String> notices = new ArrayList<>();
        long historyRevision = sessions.read(request.sessionId())
                .map(ChatSessionMetadataRepository.State::historyRevision).orElse(0L);
        // 观测到的检查点版本与实际采用的摘要分开记：无效/锁定摘要降级成"没有摘要"时，
        // 发送前复核仍要能发现检查点真的被替换过（§4.5）
        Optional<SessionContextRepository.Checkpoint> checkpoint = checkpoints.read(request.sessionId());
        Long observedSummaryRevision = checkpoint.map(SessionContextRepository.Checkpoint::revision).orElse(null);
        SummaryView summaryView = readSummary(request, checkpoint, audit, notices);
        ContextAudit.Summary covered = summaryView == null ? null : summaryView.audit();

        Scan scan = scanTurns(request, covered);
        if (request.contextGeneration() == 1 && !enough(scan.newestFirst, request.recentTokenBudget())) {
            appendLegacy(request, scan, audit);
        }
        List<Item> candidates = withAttachmentIdentities(scan.newestFirst);
        Collections.reverse(candidates);
        List<Item> coveredCandidates = withAttachmentIdentities(scan.coveredFirst);
        Collections.reverse(coveredCandidates);

        if (scan.wrongGeneration > 0) {
            audit.omit(ContextAudit.Omission.WRONG_GENERATION, "TURN", scan.wrongGeneration);
        }
        if (scan.tombstoned > 0) {
            audit.omit(ContextAudit.Omission.SOURCE_DELETED, "TURN", scan.tombstoned);
        }
        if (scan.unfinished > 0) {
            audit.omit(ContextAudit.Omission.NON_COMPLETED, "TURN", scan.unfinished);
        }
        if (scan.legacyIncomplete > 0) {
            audit.omit(ContextAudit.Omission.NON_COMPLETED, "LEGACY", scan.legacyIncomplete);
        }
        if (scan.unreadable > 0) audit.omit(ContextAudit.Omission.SOURCE_CHANGED, "TURN", scan.unreadable);
        if (scan.covered > 0) audit.omit(ContextAudit.Omission.COVERED_BY_SUMMARY, "TURN", scan.covered);
        reportWindowEdges(request, scan, covered, audit);

        Long firstTurnId = null;
        Long lastTurnId = null;
        for (Item item : candidates) {
            if (item.legacy()) continue;
            if (firstTurnId == null || item.turnId() < firstTurnId) firstTurnId = item.turnId();
            if (lastTurnId == null || item.turnId() > lastTurnId) lastTurnId = item.turnId();
        }
        boolean hasOlder = hasOlder(request, scan);
        ContextAudit.Scan window = new ContextAudit.Scan(firstTurnId, lastTurnId, scan.rowsRead, hasOlder);
        return new Projection(List.copyOf(candidates), window, summaryView, resumeState(request),
                List.copyOf(notices), historyRevision, List.copyOf(coveredCandidates), observedSummaryRevision);
    }

    /** 倒序有界扫描：早停在"近期区已经填满"，而不是把所有历史读出来再挑。 */
    private Scan scanTurns(Request request, ContextAudit.Summary summary) throws SQLException {
        Scan scan = new Scan();
        long cursor = request.currentTurnId();
        int remaining = MAX_SCANNED_TURNS;
        while (remaining > 0) {
            int limit = Math.min(BATCH_TURNS, remaining);
            List<TurnHistory> batch = history.turnBatch(request.sessionId(), cursor, limit);
            if (batch.isEmpty()) break;
            remaining -= batch.size();
            scan.rowsRead += batch.size();
            cursor = batch.get(batch.size() - 1).turnId();
            scan.oldestScannedTurn = cursor;
            for (int index = 0; index < batch.size(); index++) {
                TurnHistory row = batch.get(index);
                if (row.contextGeneration() != request.contextGeneration()) {
                    // 切过作用域的旧正文永远不再进新代次的上下文（§2.2）
                    scan.wrongGeneration++;
                    continue;
                }
                if (row.contentDeletedAt() != null) {
                    // 原话已被用户清理：展示还有墓碑，但没有可注入的正文
                    scan.tombstoned++;
                    continue;
                }
                if (!ChatTurnRepository.STATUS_COMPLETED.equals(row.status())) {
                    // 只有 COMPLETED 才是合法上下文来源；RUNNING 是本轮自己
                    scan.unfinished++;
                    continue;
                }
                // 先解密再判去留：坏行要如实计入 SOURCE_CHANGED，不能因为"反正被摘要替了"就当它不存在
                String userText;
                String assistantText;
                try {
                    userText = read(row.userContent(), row.userContentCipher(), row.userContentMeta(), request);
                    assistantText = read(row.assistantContent(), row.assistantContentCipher(),
                            row.assistantContentMeta(), request);
                } catch (ChatCryptoException error) {
                    scan.unreadable++;                    continue;
                }
                if (userText == null || userText.isBlank() || assistantText == null || assistantText.isBlank()) {
                    scan.unfinished++;
                    continue;
                }
                scan.completeSeen++;
                // 最近两条完整轮次永远留原文：摘要替掉它们会让近期区凭空消失（T02）
                if (isCovered(summary, row.turnId()) && scan.completeSeen > MIN_RECENT_TURNS) {
                    // 先留一份原文：摘要被预算舍弃时 coverage 不再成立，要用它做有界回补（§9.4）
                    scan.coveredFirst.add(itemOf(row, userText, assistantText));
                    scan.covered++;
                    continue;
                }
                scan.newestFirst.add(itemOf(row, userText, assistantText));
            }
            if (batch.size() < limit) break;
            if (enough(scan.newestFirst, request.recentTokenBudget())) break;
        }
        return scan;
    }

    private Item itemOf(TurnHistory row, String userText, String assistantText) {
        return new Item(row.turnId(), row.requestId(), false, row.userMessageId(),
                row.assistantMessageId(), userText, assistantText, List.of(), row.startedAt(),
                estimator.estimateText(userText) + estimator.estimateText(assistantText));
    }

    /** 只在初始 generation 补 legacy（§2.2：切过作用域后旧正文不再自动带入上下文）。 */
    private void appendLegacy(Request request, Scan scan, ContextAudit.Builder audit) throws SQLException {
        int remaining = Math.max(0, MAX_SCANNED_TURNS - scan.rowsRead);
        if (remaining == 0) return;
        List<LegacyMessage> rows = history.legacyBatch(request.sessionId(),
                history.maxMessageId(request.sessionId()) + 1, remaining * 2);
        if (rows.isEmpty()) return;
        Collections.reverse(rows);
        List<Item> items = new ArrayList<>();
        for (int index = 0; index < rows.size() && items.size() < remaining; index++) {
            LegacyMessage message = rows.get(index);
            if (!"user".equals(message.role())) {
                scan.legacyIncomplete++;
                continue;
            }
            if (index + 1 >= rows.size() || !"assistant".equals(rows.get(index + 1).role())) {
                scan.legacyIncomplete++;
                continue;
            }
            LegacyMessage reply = rows.get(++index);
            String userText;
            String assistantText;
            try {
                userText = readLegacy(message, request);
                assistantText = readLegacy(reply, request);
            } catch (ChatCryptoException error) {
                scan.unreadable++;
                continue;
            }
            if (userText == null || userText.isBlank() || assistantText == null || assistantText.isBlank()) {
                scan.legacyIncomplete++;
                continue;
            }
            items.add(new Item(0, null, true, message.messageId(), reply.messageId(), userText, assistantText,
                    List.of(), reply.createdAt(),
                    estimator.estimateText(userText) + estimator.estimateText(assistantText)));
        }
        if (rows.size() >= remaining * 2) {
            // 一次取满说明更早处还有旧消息：范围外数量不知道，只报"没扫"
            audit.omit(ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "LEGACY", null);
        }
        // newestFirst 全程是"新→旧"：legacy 是最旧的一批，只能倒着接到尾部，
        // 否则末尾整体反转后会跑到最新的位置上，模型会把迁移前的旧对话当成刚刚发生过
        for (int index = items.size() - 1; index >= 0; index--) {
            scan.newestFirst.add(items.get(index));
        }
        scan.rowsRead += items.size();
    }

    /** 一批取回候选的附件身份：一次查询，不逐轮打 SQL。 */
    private List<Item> withAttachmentIdentities(List<Item> newestFirst) {
        List<Long> turnIds = new ArrayList<>();
        for (Item item : newestFirst) {
            if (!item.legacy()) turnIds.add(item.turnId());
        }
        if (turnIds.isEmpty()) return new ArrayList<>(newestFirst);
        Map<Long, List<AttachmentStub>> byTurn = new LinkedHashMap<>();
        try {
            for (AttachmentStub stub : history.attachmentStubs(turnIds)) {
                byTurn.computeIfAbsent(stub.turnId(), key -> new ArrayList<>()).add(stub);
            }
        } catch (SQLException error) {
            log.warn("附件身份读取失败，本轮按无历史附件处理: {}", error.toString());
            return new ArrayList<>(newestFirst);
        }
        List<Item> enriched = new ArrayList<>();
        for (Item item : newestFirst) {
            List<AttachmentStub> stubs = byTurn.getOrDefault(item.turnId(), List.of());
            enriched.add(stubs.isEmpty() ? item
                    : new Item(item.turnId(), item.requestId(), item.legacy(), item.userMessageId(),
                            item.assistantMessageId(), item.userText(), item.assistantText(), List.copyOf(stubs),
                            item.startedAt(), item.estimatedTokens()));
        }
        return enriched;
    }

    private boolean hasOlder(Request request, Scan scan) throws SQLException {
        long boundary = scan.oldestScannedTurn == null ? request.currentTurnId() : scan.oldestScannedTurn;
        if (boundary > 1 && history.hasOlderTurn(request.sessionId(), boundary)) return true;
        return request.contextGeneration() == 1 && history.hasLegacyMessages(request.sessionId());
    }

    /** 范围外的历史：有有效摘要时给出准确计数，没有摘要时只报"没扫"、数量留 null（§4.5）。 */
    private void reportWindowEdges(Request request, Scan scan, ContextAudit.Summary summary,
                                   ContextAudit.Builder audit) throws SQLException {
        long boundary = scan.oldestScannedTurn == null ? request.currentTurnId() : scan.oldestScannedTurn;
        if (boundary <= 1 || !history.hasOlderTurn(request.sessionId(), boundary)) return;
        if (summary == null) {
            audit.omit(ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY", null);
            return;
        }
        audit.omit(ContextAudit.Omission.OUTSIDE_SCAN_WINDOW, "HISTORY",
                history.countTurnsBefore(request.sessionId(), boundary));
    }

    private String read(String plain, String cipherText, String meta, Request request) {
        ChatMessageCipher.Read read = cipher.readLegacy(plain, cipherText, meta, request.masterPassword());
        return switch (read.status()) {
            case NEEDS_PASSWORD -> throw new ChatCryptoException("读取历史消息需要主密码");
            case UNREADABLE -> throw new ChatCryptoException("历史消息无法解密");
            default -> read.value();
        };
    }

    private String readLegacy(LegacyMessage row, Request request) {
        return read(row.content(), row.contentCipher(), row.contentMeta(), request);
    }

    private static boolean enough(List<Item> candidates, int recentTokenBudget) {
        if (candidates.size() < MIN_RECENT_TURNS) return false;
        int total = 0;
        for (Item item : candidates) total += item.estimatedTokens();
        return total >= recentTokenBudget;
    }

    /**
     * 检查点合法性（§2.5 + T04）：generation、摘要授权、库 epoch、payload 尺寸与解析、coverage 段数
     * 都过了才用。任何一条不过都只报"这份摘要不可用"——既不回退明文，也不按 through 整段排除历史。
     */
    private SummaryView readSummary(Request request, Optional<SessionContextRepository.Checkpoint> row,
                                    ContextAudit.Builder audit, List<String> notices) throws SQLException {
        if (row.isEmpty()) return null;
        SessionContextRepository.Checkpoint checkpoint = row.get();
        if (checkpoint.contextGeneration() != request.contextGeneration()) {
            return unavailable(audit, notices, ContextAudit.Omission.WRONG_GENERATION, 1);
        }
        // §2.1/§4.5：摘要能不能读密文、能不能外发，取决于 compressSession 与维护授权；
        // "库存在且解锁"只是本地保护状态，本身不是外发授权，不能拿它当通行证
        MemoryPolicyService.Eligibility eligible = policy.summaryEligibility();
        if (!eligible.allowed()) {
            return unavailable(audit, notices, summaryOmission(eligible.gate()), 1);
        }
        Optional<MemoryRepository.VaultEnvelope> envelope = memory.readEnvelope();
        if (envelope.isEmpty()) {
            return unavailable(audit, notices, ContextAudit.Omission.NOT_AUTHORIZED, 1);
        }
        if (envelope.get().epoch() != checkpoint.vaultEpoch()) {
            // 清空或重置库之后的旧检查点：不能恢复使用（§11.3、T04）
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_DELETED, 1);
        }
        Optional<MemoryCryptoService.MemoryKey> key = vault.currentKey();
        if (key.isEmpty()) {
            // 未解锁就是不能用记忆正文，绝不拿密文或占位充数（§4 不变量）
            return unavailable(audit, notices, ContextAudit.Omission.LOCKED, 1);
        }
        String payload;
        try {
            payload = crypto.decrypt(key.get(), "ai_session_context", String.valueOf(request.sessionId()),
                    checkpoint.revision(), checkpoint.payloadCipher(), checkpoint.cryptoMeta());
        } catch (MemoryCryptoService.MemoryCryptoException error) {
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_PAYLOAD_BYTES) {
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        Parsed parsed = parsePayload(payload);
        if (parsed == null) {
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (overrunsCheckedBound(parsed.ranges(), checkpoint.throughTurnId())) {
            // coverage 声称覆盖到"已检查上界"之后：越界部分没被这份摘要整理过，一旦采用，
            // 读侧会把那些轮次当"已被替代"而静默丢掉原文。整份拒收，宁可当没有摘要（§2.5）
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, 1);
        }
        if (parsed.ranges().size() > MAX_COVERAGE_RANGES) {
            // 段数爆表：旧检查点失效，只当"没摘要"用当前窗口重建（§2.5）
            return unavailable(audit, notices, ContextAudit.Omission.SOURCE_CHANGED, parsed.ranges().size());
        }
        boolean hasGaps = !contiguous(parsed.ranges(), checkpoint.throughTurnId())
                || history.countNonCompletedThrough(request.sessionId(), checkpoint.throughTurnId()) > 0;
        ContextAudit.Summary auditSummary = new ContextAudit.Summary(checkpoint.revision(),
                checkpoint.throughTurnId(), hasGaps, List.copyOf(parsed.ranges()));
        return new SummaryView(auditSummary, parsed.modelSummary(), estimator.estimateText(parsed.modelSummary()));
    }

    /** 授权门不通过时的省略原因：锁定照实说，其余（开关关闭、无授权、目标变了）都算"没被授权"。 */
    private static ContextAudit.Omission summaryOmission(MemoryPolicyService.Gate gate) {
        return gate == MemoryPolicyService.Gate.LOCKED
                ? ContextAudit.Omission.LOCKED : ContextAudit.Omission.NOT_AUTHORIZED;
    }

    private SummaryView unavailable(ContextAudit.Builder audit, List<String> notices,
                                    ContextAudit.Omission reason, int count) {
        audit.omit(reason, "SUMMARY", count);
        notices.add(NOTICE_SUMMARY_UNAVAILABLE);
        return null;
    }

    private record Parsed(String modelSummary, List<ContextAudit.CoverageRange> ranges) {
    }

    /** 检查点 payload 的明文形状（§2.5：模型可见部分与 serverCoverage 分开）；P8 写、P4 读。 */
    private Parsed parsePayload(String payload) {
        try {
            Map<String, Object> parsed = mapper.readValue(payload, new TypeReference<>() {
            });
            if (!(parsed.get("schemaVersion") instanceof Number version) || version.intValue() != 2) return null;
            List<ContextAudit.CoverageRange> ranges = new ArrayList<>();
            if (parsed.get("serverCoverage") instanceof List<?> list) {
                for (Object element : list) {
                    if (!(element instanceof Map<?, ?> map)) continue;
                    if (!(map.get("firstTurnId") instanceof Number first)
                            || !(map.get("lastTurnId") instanceof Number last)) continue;
                    if (first.longValue() <= 0 || last.longValue() < first.longValue()) continue;
                    ranges.add(new ContextAudit.CoverageRange(first.longValue(), last.longValue()));
                }
            }
            Object summary = parsed.get("modelSummary");
            return new Parsed(summary == null ? "" : String.valueOf(summary), ranges);
        } catch (Exception error) {
            return null;
        }
    }

    /** coverage 必须连续铺到 through，中间断了就是缺口（§2.5「不能跨被跳过的本会话轮次」）。 */
    private static boolean contiguous(List<ContextAudit.CoverageRange> ranges, long throughTurnId) {
        if (ranges.isEmpty()) return false;
        long expected = ranges.get(0).firstTurnId();
        for (ContextAudit.CoverageRange range : ranges) {
            if (range.firstTurnId() != expected) return false;
            expected = range.lastTurnId() + 1;
        }
        return expected > throughTurnId;
    }

    /** 任何一段 coverage 越过检查点自己声明的 through_turn_id 都算越界：那是没检查过的轮次。 */
    private static boolean overrunsCheckedBound(List<ContextAudit.CoverageRange> ranges, long throughTurnId) {
        for (ContextAudit.CoverageRange range : ranges) {
            if (range.lastTurnId() > throughTurnId) return true;
        }
        return false;
    }

    private static boolean isCovered(ContextAudit.Summary summary, long turnId) {
        if (summary == null) return false;
        for (ContextAudit.CoverageRange range : summary.coverageRanges()) {
            if (turnId >= range.firstTurnId() && turnId <= range.lastTurnId()) return true;
        }
        return false;
    }

    /**
     * 中断续接状态（§4.5 尾段）：只看当前 generation、本轮之前<b>最新一条</b>终态；
     * 它是 COMPLETED 就当没有中断，不往前翻历史失败。
     */
    private ContextAudit.ResumeState resumeState(Request request) throws SQLException {
        Optional<TurnHistory> latest = history.latestTerminalBefore(request.sessionId(), request.currentTurnId(),
                request.contextGeneration());
        if (latest.isEmpty() || ChatTurnRepository.STATUS_COMPLETED.equals(latest.get().status())) return null;
        return new ContextAudit.ResumeState(latest.get().requestId(), latest.get().status(),
                latest.get().finishedAt());
    }
}
