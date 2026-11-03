package com.drillnotebook.app.service;

import com.drillnotebook.app.repository.ChatHistoryRepository;
import com.drillnotebook.app.repository.ChatHistoryRepository.CleanupMessage;
import com.drillnotebook.app.repository.ChatHistoryRepository.CleanupTurn;
import com.drillnotebook.app.repository.ChatSessionMetadataRepository;
import com.drillnotebook.app.repository.ChatStorageLedger;
import com.drillnotebook.app.repository.ChatTurnRepository;
import com.drillnotebook.app.repository.MemoryOperationRepository;
import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.service.memory.MemoryTimes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 聊天存储的容量面与显式清理（执行方案 §7.4、contracts §2.7 与 §3 的 {@code /history/storage}、
 * {@code /history/cleanup/*}）。
 *
 * <p>所有数字都是<b>逻辑落库字节</b>：消息五列与轮次四列密文元数据的 UTF-8 长度，
 * 既不是 Token，也不是 {@code .db} 的物理大小，更不是后台记忆那 20 次/30K 的维护预算——
 * §7.4 明令这三者各说各话，界面不许混称。</p>
 *
 * <p>清理走「预览钉范围、执行复算比对」两步：预览发一枚进程内密钥签名的 {@code previewId}，
 * 里面绑着<b>固定的 turn/message ID 集合、revision、字节与受影响记忆计数</b>（契约 §3 原话），
 * 执行时按同一条选取 SQL 重算并逐字段比对，对不上就不删——「不能仅按当时的日期条件重新扩大删除」。</p>
 */
@Service
public class ChatStorageService {
    /** 契约 §3：一次预览最多固定 200 个完整轮次。 */
    public static final int MAX_CLEANUP_TURNS = 200;
    /** 契约 §3：预览五分钟到期。够用户读完再点确认，又不足以让一份旧范围在会话里长期有效。 */
    static final long PREVIEW_TTL_MILLIS = 300_000L;
    /** operationId 去重台账里的动作名（contracts §3 尾段：POST 清理也要防双击重放）。 */
    private static final String CLEANUP_ACTION = "HISTORY_CLEANUP";

    private static final Logger log = LoggerFactory.getLogger(ChatStorageService.class);

    private final ChatStorageLedger ledger;
    private final ChatHistoryRepository history;
    private final ChatSessionMetadataRepository sessions;
    private final ChatTurnRepository turns;
    private final MemoryRepository memory;
    private final MemoryOperationRepository operations;
    private final ObjectMapper mapper;
    /**
     * 预览令牌的签名密钥：只在进程内存活，不入库、不落盘。
     * 重启后旧令牌一律验不过——契约 §3 的「进程内最多保留 20 个」是给驻留表设的上限，
     * 这里一份预览都不驻留（只签一枚自包含令牌），所以那条上限没有作用对象，见台账 D190。
     */
    private final byte[] previewKey;
    /**
     * 预览存活时间。生产走 {@link #PREVIEW_TTL_MILLIS}；{@link #usePreviewTtlMillis} 只把它拨短，
     * 让"过期预览 410"这一格不必靠真等五分钟来验。
     */
    private long previewTtlMillis = PREVIEW_TTL_MILLIS;

    public ChatStorageService(ChatStorageLedger ledger, ChatHistoryRepository history,
                              ChatSessionMetadataRepository sessions, ChatTurnRepository turns,
                              MemoryRepository memory, MemoryOperationRepository operations, ObjectMapper mapper) {
        this.ledger = ledger;
        this.history = history;
        this.sessions = sessions;
        this.turns = turns;
        this.memory = memory;
        this.operations = operations;
        this.mapper = mapper;
        this.previewKey = new byte[32];
        new SecureRandom().nextBytes(this.previewKey);
    }

    /**
     * 只读快照。刻意不调 {@code rebuild()} 也不触发进程内重建：读一次就把计数拉平，
     * 会抹掉对账门禁本来要暴露的漂移（§2.7）。
     */
    public Map<String, Object> snapshot() throws SQLException {
        return view(ledger.read());
    }

    /** 测试缝：把预览的存活时间拨短，过期那一格才不用靠真等五分钟来验。生产不调这里。 */
    void usePreviewTtlMillis(long millis) {
        this.previewTtlMillis = millis;
    }

    /**
     * 显式改额度（PUT）：三个字段都必须给全，缺一个就是 400——不能拿默认值替用户猜。
     * 新值不得低于已用+未结算预留，附件侧不得低于已用附件字节。<b>一条记录都不清</b>：
     * 要腾地方得走清理，不是把额度调小。
     */
    public Map<String, Object> updateLimits(Map<String, Object> body) throws SQLException {
        long expectedRevision = required(body, "expectedRevision");
        long historyLimitBytes = required(body, "historyLimitBytes");
        long attachmentLimitBytes = required(body, "attachmentLimitBytes");
        ChatStorageLedger.LimitResult result =
                ledger.updateLimits(expectedRevision, historyLimitBytes, attachmentLimitBytes);
        if (result == ChatStorageLedger.LimitResult.APPLIED) {
            return view(ledger.read());
        }
        // 失败也要回一份当前基线：前端拿它换 CAS 起点，不然用户只能重开抽屉（P9a 的 D93 教训）
        long latest = ledger.read().revision();
        switch (result) {
            case INVALID_VALUE -> throw new ChatTurnRejectException(400, "HISTORY_LIMIT_INVALID",
                    "容量额度必须是正整数字节数", Map.of("revision", latest));
            case BELOW_USED -> throw new ChatTurnRejectException(409, "HISTORY_LIMIT_BELOW_USED",
                    "新额度低于已占用与未结算预留，请先清理或改大一点", Map.of("revision", latest));
            default -> throw new ChatTurnRejectException(409, "HISTORY_LIMIT_REVISION_CONFLICT",
                    "容量账本已被别的改动推进，请按最新额度重填", Map.of("revision", latest));
        }
    }

    /**
     * 清理预览（contracts §3）：<b>只读</b>，不删任何东西，也不留任何计划外的副作用。
     * 返回的范围与 {@link #cleanup} 用同一条选取口径重算，所以报的字节就是将来扣的字节。
     */
    public Map<String, Object> previewCleanup(Map<String, Object> body) throws SQLException {
        long sessionId = required(body, "sessionId");
        long boundary = required(body, "beforeOrAtMessageId");
        long maxTurns = body.get("maxTurns") == null ? MAX_CLEANUP_TURNS : required(body, "maxTurns");
        if (maxTurns < 1 || maxTurns > MAX_CLEANUP_TURNS) {
            throw new ChatTurnRejectException(400, "CLEANUP_MAX_TURNS_INVALID",
                    "一次最多清理 " + MAX_CLEANUP_TURNS + " 个完整轮次", Map.of("maxTurns", maxTurns));
        }
        if (!history.messageBelongsTo(sessionId, boundary)) {
            throw new ChatTurnRejectException(400, "CLEANUP_BOUNDARY_UNKNOWN",
                    "边界必须是本会话里真实存在的一条消息", Map.of("beforeOrAtMessageId", boundary));
        }
        Scope scope = scopeOf(sessionId, boundary, (int) maxTurns);
        long expiresAt = System.currentTimeMillis() + previewTtlMillis;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previewId", issue(scope, expiresAt));
        result.put("sessionId", sessionId);
        result.put("beforeOrAtMessageId", boundary);
        result.put("maxTurns", (int) maxTurns);
        result.put("turnCount", scope.turnCount());
        result.put("messageCount", scope.messageIds().size());
        result.put("legacyCount", scope.legacyRows().size());
        result.put("incompleteLegacyCount", scope.incompleteLegacy().size());
        result.put("excludedMessageCount", scope.excludedCount());
        result.put("olderTurnCount", scope.olderTurnCount());
        // 契约 §3：实际 message ID 与固定 ID 集合要放进预览；孤立展示行必须被看见才谈得上清理。
        result.put("messageIds", scope.messageIds());
        result.put("turnRequestIds", scope.turnRequestIds());
        // 复核 N4：界面要让用户"看见孤立行"，就得知道是哪几条，而不是只知道有几条。
        result.put("incompleteLegacyMessageIds", scope.incompleteLegacyIds());
        // 契约 §3：受影响记忆计数。预览先说清"会连带删掉几条记忆"，确认才不是盲签。
        result.put("affectedEvidence", scope.impact().evidence());
        result.put("affectedProposals", scope.impact().proposals());
        result.put("autoFactsRemoved", scope.impact().autoFacts());
        result.put("protectedFactsKept", scope.impact().protectedFacts());
        result.put("historyBytes", scope.historyBytes());
        result.put("attachmentBytes", scope.attachmentBytes());
        result.put("historyRevision", scope.historyRevision());
        result.put("expiresAt", MemoryTimes.formatMillis(expiresAt));
        return result;
    }

    /**
     * 执行清理（contracts §3）：原子清掉 {@code previewId} 钉住的那一份范围，<b>不扩大也不缩小</b>。
     *
     * <p>五道门：签名与时效（410）→ 令牌里的 revision 与请求带的 revision 必须同一份（400）→
     * {@code operationId} 去重（双击重放只生效一次）→ 重算<b>身份集合</b>（变了就 409，绝不按边界重新扩大）→
     * 写事务内按<b>版本</b> CAS 认领 + 逐行复验 + <b>会话级</b>无 RUNNING（各 409）。
     * 集合、版本、RUNNING 三道各管一件事，所以指纹里不含 revision——含了就把 CAS 那条臂顶成死码。
     * {@code confirm} 必须是布尔 {@code true}，界面不许用"默认勾选"替用户确认。</p>
     */
    public Map<String, Object> cleanup(Map<String, Object> body) throws SQLException {
        String token = text(body, "previewId") == null ? null : text(body, "previewId").trim();
        if (token == null || token.isEmpty()) {
            throw new ChatTurnRejectException(400, "CLEANUP_PREVIEW_REQUIRED", "清理必须带预览返回的 previewId",
                    Map.of());
        }
        long expectedHistoryRevision = required(body, "expectedHistoryRevision");
        if (!Boolean.TRUE.equals(body == null ? null : body.get("confirm"))) {
            throw new ChatTurnRejectException(400, "CLEANUP_CONFIRM_REQUIRED", "清理原文必须显式 confirm=true",
                    Map.of());
        }
        Preview preview = verify(token);
        if (preview.historyRevision() != expectedHistoryRevision) {
            throw new ChatTurnRejectException(400, "CLEANUP_PREVIEW_REVISION_MISMATCH",
                    "提交的版本基线不是这份预览读到的那一份，请重新预览",
                    Map.of("previewRevision", preview.historyRevision(), "expectedHistoryRevision",
                            expectedHistoryRevision));
        }
        // 去重排在"范围是否变了"之前：第一趟已经删过，双击重放必须看到当初那份响应，而不是被 409 顶回来猜
        String dedupeId = blank(text(body, "operationId")) ? null : text(body, "operationId").trim();
        Map<String, Object> replay = begin(dedupeId, String.valueOf(preview.sessionId()));
        if (replay != null) {
            return replay;
        }
        Scope scope = scopeOf(preview.sessionId(), preview.boundary(), preview.maxTurns());
        if (!scope.hash().equals(preview.scopeHash())) {
            abandon(dedupeId);
            throw new ChatTurnRejectException(409, "CLEANUP_SOURCE_CHANGED",
                    "预览之后这些内容已经变了，请重新预览再确认", Map.of("historyRevision",
                            revision(preview.sessionId())));
        }
        ChatTurnRepository.CleanupOutcome outcome;
        try {
            outcome = turns.clearScope(scope.turns(), scope.legacyRows(), preview.sessionId(),
                    expectedHistoryRevision, MemoryTimes.now());
        } catch (ChatTurnRepository.CleanupRefusalException refusal) {
            abandon(dedupeId);
            throw new ChatTurnRejectException(409, "CLEANUP_" + refusal.reason(), refusal.getMessage(),
                    Map.of("historyRevision", revision(preview.sessionId())));
        } catch (SQLException error) {
            abandon(dedupeId);
            throw error;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", preview.sessionId());
        result.put("cleanedTurns", outcome.cleanedTurns());
        result.put("cleanedLegacyRows", outcome.legacyCount());
        result.put("messageCount", outcome.messageCount());
        result.put("releasedHistoryBytes", outcome.releasedHistoryBytes());
        result.put("releasedAttachmentBytes", outcome.releasedAttachmentBytes());
        result.put("evidenceDeleted", outcome.evidenceDeleted());
        result.put("proposalsDeleted", outcome.proposalsDeleted());
        result.put("autoFactsRemoved", outcome.autoFactsRemoved());
        result.put("protectedFactsKept", outcome.protectedFactsKept());
        result.put("checkpointsInvalidated", outcome.checkpointsInvalidated());
        result.put("jobsSuperseded", outcome.jobsSuperseded());
        result.put("historyRevision", outcome.historyRevision());
        result.put("storage", view(ledger.read()));
        record(dedupeId, result);
        log.info("历史原文已清理: sessionId={}, 轮次={}, legacy={}, 释放={}B, 证据={}, 检查点={}, 任务={}",
                preview.sessionId(), outcome.cleanedTurns(), outcome.legacyCount(),
                outcome.releasedHistoryBytes(), outcome.evidenceDeleted(), outcome.checkpointsInvalidated(),
                outcome.jobsSuperseded());
        return result;
    }

    /**
     * 边界内确定下来的清理范围，以及绑定它身份的指纹。
     *
     * <p>{@code legacyRows} 是<b>要删的全部 legacy 行</b>（配对完整的 + 落单的），
     * {@code incompleteLegacy} 只是其中落单的那部分，单独报给界面显示"残缺组"；
     * 被边界劈开的那一对两边都不删，只进 {@code excludedCount}。</p>
     */
    private record Scope(long sessionId, long boundary, int maxTurns, List<CleanupTurn> turns,
                         List<CleanupMessage> legacyRows, List<CleanupMessage> incompleteLegacy,
                         List<Long> messageIds, MemoryRepository.MemoryImpact impact, int excludedCount,
                         long olderTurnCount, long historyBytes, long attachmentBytes, long historyRevision,
                         String hash) {
        int turnCount() {
            return turns.size();
        }

        List<String> turnRequestIds() {
            return turns.stream().map(CleanupTurn::requestId).toList();
        }

        List<Long> turnIds() {
            return turns.stream().map(CleanupTurn::turnId).toList();
        }

        /** 落单的那几条 legacy 行（升序，来自 {@code rows} 的同一份对象）。 */
        List<Long> incompleteLegacyIds() {
            return incompleteLegacy.stream().map(CleanupMessage::messageId).toList();
        }
    }

    private Scope scopeOf(long sessionId, long boundary, int maxTurns) throws SQLException {
        List<CleanupTurn> selected = history.cleanupTurns(sessionId, boundary, maxTurns);
        List<CleanupMessage> inRange = history.cleanupMessages(sessionId, boundary);
        Set<Long> selectedTurnIds = new LinkedHashSet<>();
        for (CleanupTurn turn : selected) {
            selectedTurnIds.add(turn.turnId());
        }
        List<Long> messageIds = new ArrayList<>();
        long turnBytes = 0;
        long attachmentBytes = 0;
        for (CleanupTurn turn : selected) {
            turnBytes += turn.storedHistoryBytes();
            attachmentBytes += turn.attachmentBytes();
        }
        int pairedTurnMessages = 0;
        List<CleanupMessage> legacyAsc = new ArrayList<>();
        for (CleanupMessage message : inRange) {
            if (message.turnId() == null) {
                legacyAsc.add(message);
            } else if (selectedTurnIds.contains(message.turnId())) {
                pairedTurnMessages++;
                messageIds.add(message.messageId());
            }
        }
        legacyAsc.sort(java.util.Comparator.comparingLong(CleanupMessage::messageId));
        LegacyGroups groups = pairLegacy(legacyAsc, history.firstLegacyAfter(sessionId, boundary));
        messageIds.addAll(groups.deletedIds());
        long legacyBytes = 0;
        for (CleanupMessage row : groups.rows()) {
            legacyBytes += row.bytes();
        }
        int excluded = inRange.size() - pairedTurnMessages - groups.rows().size();
        // 超出 maxTurns 的更早轮次单独报数：界面不许把"这一批"说成"全部清完"
        long olderTurnCount = Math.max(0, history.cleanupTurnCount(sessionId, boundary) - selected.size());
        long historyBytes = Math.addExact(turnBytes, legacyBytes);
        java.util.Collections.sort(messageIds);
        // 空范围也得能预览（界面要说"没有可清理的"），这时记忆侧计数天然是零，不能去问一个没有对象的统计
        MemoryRepository.MemoryImpact impact = messageIds.isEmpty() && selectedTurnIds.isEmpty()
                ? new MemoryRepository.MemoryImpact(0, 0, 0, 0)
                : memory.memoryImpact(null, List.copyOf(selectedTurnIds), List.copyOf(messageIds));
        long historyRevision = revision(sessionId);
        // 指纹里刻意不含 historyRevision：契约 §3 的三道复核各管一件事，把版本塞进集合指纹会让
        // 事务内那条 CAS 认领永远轮不到（版本一动，指纹先红）。集合归指纹、版本归 CAS、RUNNING 归会话门。
        String hash = digest(sessionId + "|" + boundary + "|" + maxTurns + "|" + selected.size()
                + "|" + messageIds + "|" + String.join(",", selected.stream()
                .map(CleanupTurn::requestId).toList()) + "|" + groups.rows().size() + "|" + groups.incomplete().size()
                + "|" + excluded + "|" + olderTurnCount + "|" + historyBytes + "|" + attachmentBytes
                + "|" + impact.evidence() + "," + impact.proposals() + "," + impact.autoFacts() + ","
                + impact.protectedFacts());
        return new Scope(sessionId, boundary, maxTurns, List.copyOf(selected), List.copyOf(groups.rows()),
                List.copyOf(groups.incomplete()), List.copyOf(messageIds), impact, excluded, olderTurnCount,
                historyBytes, attachmentBytes, historyRevision, hash);
    }

    /** legacy 展示行的配对结果：{@code rows} 是要删的行，{@code incomplete} 是其中落单的那几条。 */
    private record LegacyGroups(List<CleanupMessage> rows, List<CleanupMessage> incomplete, List<Long> deletedIds) {
    }

    /**
     * 按契约 §3 的同一配对规则把迁移前的展示行归成"一问一答"：
     * 相邻的 user+assistant 算一组，<b>整组一起删</b>；落单的进残缺组，照删但单独报数，让界面先看见再确认；
     * 问句在边界内、答句在边界外的那一对<b>两边都不删</b>——只删前半就是 execution §7.4.5 禁的「误删半轮」。
     */
    private static LegacyGroups pairLegacy(List<CleanupMessage> ascending, CleanupMessage afterBoundary) {
        List<CleanupMessage> rows = new ArrayList<>();
        List<CleanupMessage> incomplete = new ArrayList<>();
        List<Long> deletedIds = new ArrayList<>();
        int index = 0;
        while (index < ascending.size()) {
            CleanupMessage row = ascending.get(index);
            if (!"user".equals(row.role())) {
                rows.add(row);
                incomplete.add(row);
                deletedIds.add(row.messageId());
                index++;
                continue;
            }
            boolean partnerInRange = index + 1 < ascending.size();
            CleanupMessage next = partnerInRange ? ascending.get(index + 1) : afterBoundary;
            if (next != null && "assistant".equals(next.role())) {
                if (!partnerInRange) {
                    // 答句越界：整对退出。这里一行都不许收——只删前半就是 §7.4.5 禁的「误删半轮」
                    index++;
                    continue;
                }
                rows.add(row);
                deletedIds.add(row.messageId());
                rows.add(next);
                deletedIds.add(next.messageId());
                index += 2;
                continue;
            }
            rows.add(row);
            incomplete.add(row);
            deletedIds.add(row.messageId());
            index++;
        }
        return new LegacyGroups(rows, incomplete, deletedIds);
    }


    // ---- previewId：进程内密钥签名的无状态令牌 ----

    /** 令牌里还原出来的范围：边界 + 大小 + 预览读到的 revision + 绑定身份集合的指纹，签名验过才敢信。 */
    private record Preview(long sessionId, long boundary, int maxTurns, long historyRevision, String scopeHash) {
    }

    private String issue(Scope scope, long expiresAt) throws SQLException {
        String payload = scope.sessionId() + "|" + scope.boundary() + "|" + scope.maxTurns() + "|"
                + scope.historyRevision() + "|" + scope.hash() + "|" + expiresAt;
        return encode(payload) + "." + encode(sign(payload));
    }

    private Preview verify(String token) {
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw expired();
        }
        String payload;
        byte[] signature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException error) {
            throw expired();
        }
        if (!MessageDigest.isEqual(signature, signQuietly(payload))) {
            throw expired();
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 6) {
            throw expired();
        }
        try {
            long expiresAt = Long.parseLong(parts[5]);
            if (expiresAt < System.currentTimeMillis()) {
                throw new ChatTurnRejectException(410, "CLEANUP_PREVIEW_EXPIRED", "预览已过期，请重新预览", Map.of());
            }
            return new Preview(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Integer.parseInt(parts[2]),
                    Long.parseLong(parts[3]), parts[4]);
        } catch (NumberFormatException error) {
            throw expired();
        }
    }

    private static ChatTurnRejectException expired() {
        return new ChatTurnRejectException(410, "CLEANUP_PREVIEW_EXPIRED", "预览无效或已过期，请重新预览", Map.of());
    }

    private byte[] sign(String payload) throws SQLException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(previewKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new SQLException("清理预览签名不可用", error);
        }
    }

    private byte[] signQuietly(String payload) {
        try {
            return sign(payload);
        } catch (SQLException error) {
            throw expired();
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String digest(String value) throws SQLException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new SQLException("清理预览指纹不可用", error);
        }
    }

    // ---- operationId 去重（§3 尾段：清理也是 POST，双击不能删两次）----

    /** 同一 operationId 已完成 → 回吐当初那份响应；仍在进行或被别的动作占用 → 409。返回 null 表示可以继续。 */
    private Map<String, Object> begin(String operationId, String targetId) throws SQLException {
        if (operationId == null) {
            return null;
        }
        MemoryOperationRepository.StartResult started = operations.start(operationId, CLEANUP_ACTION, targetId,
                MemoryTimes.now());
        return switch (started.outcome()) {
            case NEW -> null;
            case REPLAY -> replayed(started.resultMeta());
            case IN_FLIGHT -> throw new ChatTurnRejectException(409, "CLEANUP_OPERATION_IN_FLIGHT",
                    "同一清理操作正在进行中", Map.of());
            case CONFLICT -> throw new ChatTurnRejectException(409, "CLEANUP_OPERATION_ID_CONFLICT",
                    "operationId 已被别的动作或内容占用", Map.of());
        };
    }

    private Map<String, Object> replayed(String json) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (json != null && !json.isBlank()) {
            try {
                body.putAll(mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
                }));
            } catch (Exception error) {
                body.clear();
            }
        }
        body.put("replayed", true);
        return body;
    }

    private void record(String operationId, Map<String, Object> body) throws SQLException {
        if (operationId == null) {
            return;
        }
        try {
            operations.complete(operationId, mapper.writeValueAsString(body), MemoryTimes.now());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new SQLException("清理响应无法登记", error);
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

    private long revision(long sessionId) throws SQLException {
        return sessions.read(sessionId).orElseThrow().historyRevision();
    }

    private static Map<String, Object> view(ChatStorageLedger.Snapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("historyBytes", snapshot.historyBytes());
        result.put("reservedHistoryBytes", snapshot.reservedHistoryBytes());
        result.put("usedHistoryBytes", snapshot.usedHistory());
        result.put("historyLimitBytes", snapshot.historyLimitBytes());
        result.put("attachmentBytes", snapshot.attachmentBytes());
        result.put("attachmentLimitBytes", snapshot.attachmentLimitBytes());
        result.put("historyWarning", snapshot.historyWarning());
        result.put("attachmentWarning", snapshot.attachmentWarning());
        result.put("revision", snapshot.revision());
        return result;
    }

    private static long required(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof Number numeric)) {
            throw new ChatTurnRejectException(400, "HISTORY_LIMIT_FIELD_MISSING",
                    "缺少必填的字节数字段: " + field, Map.of("field", field));
        }
        return numeric.longValue();
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
