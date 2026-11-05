package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Citation;
import com.drillnotebook.app.model.ChatContextPlan;
import com.drillnotebook.app.model.ContextAudit;
import com.drillnotebook.app.model.RetrievalHit;
import com.drillnotebook.app.service.ChatContextAssembler.Draft;
import com.drillnotebook.app.service.ChatContextAssembler.Image;
import com.drillnotebook.app.service.ChatContextAssembler.Item;
import com.drillnotebook.app.service.ChatContextAssembler.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 统一预算与冻结（执行方案 §9.2/§9.4、contracts §4.5）：
 * projection → assemble → <b>fit</b> → 序列化 → 计数校验 → freeze。
 *
 * <p>裁剪顺序固定（§9.2）：先历史图像，再低排名笔记片段、页面冗余、附件低优先段落、低相关记忆、
 * 最早近期轮次、旧摘要；当前用户问题与固定系统规则永不删，当前图片永不静默丢。
 * 本地重fit最多 3 次，仍装不下就 {@code CONTEXT_TOO_LARGE} 并指出该缩短哪类输入，
 * 绝不带着旧估算硬发请求。</p>
 */
@Component
public class ContextBudgeter {
    /** 窗口未知时的保守默认（§9.2：不能宣称数学上保证不超窗，只能给保守值并提示配置）。 */
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32000;
    /** 输出预留沿用现有默认 max_tokens。 */
    public static final int OUTPUT_RESERVE_TOKENS = 4096;
    public static final int MIN_SAFETY_MARGIN_TOKENS = 512;
    /** 本地 fit 上限（§4.5）：超过就拒绝，不做无限重组。 */
    public static final int MAX_FIT_ATTEMPTS = 3;

    private final TokenEstimator estimator;
    private final ObjectMapper mapper;

    public ContextBudgeter(TokenEstimator estimator, ObjectMapper mapper) {
        this.estimator = estimator;
        this.mapper = mapper;
    }

    /** 计划身份与厂商窗口：窗口是否由用户显式配置决定要不要报 WINDOW_UNSET。 */
    public record Target(String planId, long sessionId, long contextGeneration, long historyRevision,
                         Long summaryRevision, Long vaultEpoch, Long vaultRevision, long policyRevision,
                         int contextWindow, boolean windowKnown) {
    }

    /** 一次 fit 的可变工作集：只在这里减，序列化与审计都从它读。 */
    private static final class Kept {
        private Item summary;
        private Item resumeState;
        private final List<Item> memories = new ArrayList<>();
        private final List<Turn> turns = new ArrayList<>();
        private final List<Item> page = new ArrayList<>();
        private final List<Item> rag = new ArrayList<>();
        private final List<RetrievalHit> hits = new ArrayList<>();
        private final List<Item> attachments = new ArrayList<>();
        private final List<Image> currentImages = new ArrayList<>();

        /** 本轮要发的全部图片：当前轮的 + 仍在上下文里的历史轮的，计费与审计都按这份算。 */
        List<Image> allImages() {
            List<Image> images = new ArrayList<>(currentImages);
            for (Turn turn : turns) images.addAll(turn.images());
            return images;
        }

        /** 图片按"未知成本每张预留 4,096"计（§4.3），不进 8,000 文本预算，但要占窗口。 */
        int imageTokens() {
            return allImages().size() * TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS;
        }
    }

    public ChatContextPlan fit(Draft draft, ConversationProjection.Projection projection, Target target,
                               ContextAudit.Builder audit) {
        Kept kept = new Kept();
        kept.summary = draft.summary();
        kept.resumeState = draft.resumeState();
        kept.memories.addAll(draft.memories());
        kept.turns.addAll(draft.recentTurns());
        kept.page.addAll(draft.pageParts());
        kept.rag.addAll(draft.ragExcerpts());
        kept.hits.addAll(draft.ragHits());
        kept.attachments.addAll(draft.textAttachments());
        kept.currentImages.addAll(draft.currentImages());

        List<String> notices = new ArrayList<>(draft.notices());
        if (!target.windowKnown()) notices.add("WINDOW_UNSET");
        if (!kept.currentImages.isEmpty() || kept.turns.stream().anyMatch(turn -> !turn.images().isEmpty())) {
            notices.add("IMAGE_COST_UNKNOWN");
        }
        int window = target.contextWindow() > 0 ? target.contextWindow() : DEFAULT_CONTEXT_WINDOW_TOKENS;
        int margin = Math.max(MIN_SAFETY_MARGIN_TOKENS, window / 10);
        int allowedInput = Math.min(ChatContextAssembler.USER_TEXT_BUDGET,
                window - OUTPUT_RESERVE_TOKENS - margin);
        int userTokens = estimator.estimateText(draft.userText());
        if (userTokens > allowedInput) {
            // 当前问题永不静默截断：请用户缩短或改成引用片段（§9.2、T05）
            throw tooLarge(List.of("USER_TEXT"), userTokens, allowedInput, kept);
        }

        // 摘要没进这份计划（组装阶段就丢了）时 coverage 不再成立：先把被它替掉的原文有界回补（§9.4）
        boolean coveredRestored = false;
        if (kept.summary == null && !projection.coveredCandidates().isEmpty()) {
            restoreCovered(kept, projection, audit);
            coveredRestored = true;
        }
        if (capRecentTurns(kept, audit)) notices.add("CONTEXT_TRIMMED");

        int estimated = 0;
        int limit = 0;
        List<Map<String, Object>> messages = List.of();
        for (int attempt = 0; attempt <= MAX_FIT_ATTEMPTS; attempt++) {
            messages = serialize(draft, kept);
            // 估算排除图像 base64：结构开销保留，编码字节不计（§4.3"不能把 base64 长度当收费 Token"），
            // 图像成本已按每张 4,096 单独从窗口里扣除
            estimated = estimator.estimateSerialized(countable(messages));
            limit = Math.min(ChatContextPlan.INPUT_BUDGET_TOKENS,
                    window - OUTPUT_RESERVE_TOKENS - margin - kept.imageTokens());
            if (estimated <= limit) break;
            if (attempt == MAX_FIT_ATTEMPTS) throw tooLarge(describeOverflow(kept), estimated, limit, kept);
            if (!trim(kept, estimated - limit, audit)) {
                // 已经没有可裁的东西：剩下的只有"当前图片 + 当前问题 + 固定规则"，直接拒绝
                throw tooLarge(describeOverflow(kept), estimated, limit, kept);
            }
            // trim 可能把摘要丢掉了：coverage 随之失效，回补被它替代的原文并重新收敛近期区（§9.4）
            if (kept.summary == null && !coveredRestored && !projection.coveredCandidates().isEmpty()) {
                restoreCovered(kept, projection, audit);
                capRecentTurns(kept, audit);
                coveredRestored = true;
            }
            if (!notices.contains("CONTEXT_TRIMMED")) notices.add("CONTEXT_TRIMMED");
        }

        List<Map<String, Object>> citations = new ArrayList<>();
        for (int index = 0; index < kept.hits.size(); index++) {
            citations.add(citationView(kept.hits.get(index).citation(), index + 1));
        }
        buildAudit(audit, kept, draft, projection, estimated, limit);
        return new ChatContextPlan(target.planId(), target.sessionId(), target.contextGeneration(),
                target.historyRevision(), target.summaryRevision(), target.vaultEpoch(), target.vaultRevision(),
                target.policyRevision(), messages, notices, citations,
                audit.build(ContextAudit.DISPATCH_NOT_SENT, estimated, limit, projection.scan(),
                        kept.summary == null ? null : projection.summary().audit(),
                        projection.resumeState()));
    }

    /** 按 §9.2 的顺序裁一轮；返回是否真的裁掉了东西。 */
    private boolean trim(Kept kept, int excess, ContextAudit.Builder audit) {
        int saved = dropHistoryImages(kept, audit);
        if (saved >= excess) return saved > 0;
        saved += dropRag(kept, excess - saved, audit);
        if (saved >= excess) return saved > 0;
        saved += dropPage(kept, excess - saved, audit);
        if (saved >= excess) return saved > 0;
        saved += dropAttachments(kept, excess - saved, audit);
        if (saved >= excess) return saved > 0;
        saved += dropMemories(kept, excess - saved, audit);
        if (saved >= excess) return saved > 0;
        saved += dropOldestTurns(kept, excess - saved, audit);
        if (saved >= excess) return saved > 0;
        return saved + dropSummary(kept, audit) > 0;
    }

    /** 历史图像先走（§4.3"总预算不足先去历史图像"）；当前图片一张都不动。 */
    private int dropHistoryImages(Kept kept, ContextAudit.Builder audit) {
        int saved = 0;
        List<Turn> rebuilt = new ArrayList<>();
        for (Turn turn : kept.turns) {
            if (turn.images().isEmpty()) {
                rebuilt.add(turn);
                continue;
            }
            saved += turn.images().size() * TokenEstimator.IMAGE_UNKNOWN_ESTIMATED_TOKENS;
            audit.omit(ContextAudit.Omission.ATTACHMENT_POLICY, "IMAGE", turn.images().size());
            rebuilt.add(new Turn(turn.turnId(), turn.legacy(), turn.userMessageId(), turn.assistantMessageId(),
                    turn.userText(), turn.assistantText(), List.of(), turn.estimatedTokens()));
        }
        kept.turns.clear();
        kept.turns.addAll(rebuilt);
        return saved;
    }

    private int dropRag(Kept kept, int excess, ContextAudit.Builder audit) {
        // 从尾部（低排名）开始，片段与 citation 成对删除，编号不会与实际注入错位
        int saved = 0;
        while (!kept.rag.isEmpty() && saved < excess) {
            int index = kept.rag.size() - 1;
            saved += kept.rag.get(index).estimatedTokens();
            kept.rag.remove(index);
            kept.hits.remove(index);
        }
        if (saved > 0) audit.omit(ContextAudit.Omission.BUDGET, "NOTEBOOK_RAG", 1);
        return saved;
    }

    private int dropPage(Kept kept, int excess, ContextAudit.Builder audit) {
        int saved = 0;
        while (!kept.page.isEmpty() && saved < excess) {
            saved += kept.page.get(kept.page.size() - 1).estimatedTokens();
            kept.page.remove(kept.page.size() - 1);
        }
        if (saved > 0) audit.omit(ContextAudit.Omission.BUDGET, "PAGE", 1);
        return saved;
    }

    private int dropAttachments(Kept kept, int excess, ContextAudit.Builder audit) {
        int saved = 0;
        while (!kept.attachments.isEmpty() && saved < excess) {
            saved += kept.attachments.get(kept.attachments.size() - 1).estimatedTokens();
            kept.attachments.remove(kept.attachments.size() - 1);
        }
        if (saved > 0) audit.omit(ContextAudit.Omission.BUDGET, "TEXT_ATTACHMENT", 1);
        return saved;
    }

    private int dropMemories(Kept kept, int excess, ContextAudit.Builder audit) {
        int saved = 0;
        while (kept.memories.size() > 0 && saved < excess) {
            saved += kept.memories.get(kept.memories.size() - 1).estimatedTokens();
            kept.memories.remove(kept.memories.size() - 1);
        }
        if (saved > 0) audit.omit(ContextAudit.Omission.BUDGET, "MEMORY", 1);
        return saved;
    }

    /**
     * §9.2 的近期区上限：从最旧开始整轮舍弃，直到落回 2,400 以内或只剩最新一轮。
     * 最新一轮不可再分（"不能切到半句改变否定语义"），单独就超上限时保留它，交给总预算处理；
     * "尽可能保留最近至少 2 轮"是优先目标而不是硬保证——预算不够就继续按完整轮次舍弃历史。
     */
    private boolean capRecentTurns(Kept kept, ContextAudit.Builder audit) {
        int total = 0;
        for (Turn turn : kept.turns) total += turn.estimatedTokens();
        int dropped = 0;
        while (kept.turns.size() > 1 && total > ChatContextAssembler.RECENT_TURNS_BUDGET) {
            total -= kept.turns.get(0).estimatedTokens();
            kept.turns.remove(0);
            dropped++;
        }
        if (dropped > 0) audit.omit(ContextAudit.Omission.BUDGET, "TURN", dropped);
        return dropped > 0;
    }

    /**
     * 摘要被舍弃后的一次有界回补（§9.4"只排除被有效 coverage 替代的原轮次"）：把扫描窗口内
     * 已解密的被覆盖轮次按时间序并回候选，并撤销账本里的覆盖省略——原文回来了就不再算被替代。
     * 回补范围受扫描窗口约束（一份最多 32 轮），不做全库重读。
     */
    private void restoreCovered(Kept kept, ConversationProjection.Projection projection,
                                ContextAudit.Builder audit) {
        for (ConversationProjection.Item item : projection.coveredCandidates()) {
            Turn turn = new Turn(item.turnId(), false, item.userMessageId(), item.assistantMessageId(),
                    item.userText(), item.assistantText(), List.of(),
                    item.estimatedTokens() + 2 * TokenEstimator.MESSAGE_OVERHEAD_TOKENS);
            int position = 0;
            while (position < kept.turns.size()
                    && (kept.turns.get(position).turnId() == null
                    || kept.turns.get(position).turnId() < turn.turnId())) {
                position++;
            }
            kept.turns.add(position, turn);
        }
        audit.clearOmission(ContextAudit.Omission.COVERED_BY_SUMMARY, "TURN");
    }

    /** 从最早的开始整轮丢；"最近两轮保底"在总预算这里同样只是优先目标，不是硬性下限。 */
    private int dropOldestTurns(Kept kept, int excess, ContextAudit.Builder audit) {
        int saved = 0;
        while (!kept.turns.isEmpty() && saved < excess) {
            saved += kept.turns.get(0).estimatedTokens();
            kept.turns.remove(0);
        }
        if (saved > 0) audit.omit(ContextAudit.Omission.BUDGET, "TURN", 1);
        return saved;
    }

    private int dropSummary(Kept kept, ContextAudit.Builder audit) {
        if (kept.summary == null) return 0;
        int saved = kept.summary.estimatedTokens();
        kept.summary = null;
        audit.omit(ContextAudit.Omission.BUDGET, "SUMMARY", 1);
        return saved;
    }

    /** 最终消息序列：固定系统前缀 → 一个 CONTEXT_DATA_V1 数据块 → 近期对话 → 当前输入永远最后。 */
    private List<Map<String, Object>> serialize(Draft draft, Kept kept) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", List.of(textContent(draft.systemText()))));
        Map<String, Object> envelope = ChatContextAssembler.dataEnvelope(kept.summary, kept.resumeState,
                kept.memories, kept.rag, kept.page, kept.attachments);
        messages.add(message("user", List.of(textContent(write(envelope)))));
        for (Turn turn : kept.turns) {
            List<Object> userBlocks = new ArrayList<>();
            userBlocks.add(textContent(turn.userText()));
            for (Image image : turn.images()) userBlocks.add(imageContent(image));
            messages.add(message("user", userBlocks));
            messages.add(message("assistant", List.of(textContent(turn.assistantText()))));
        }
        List<Object> current = new ArrayList<>();
        current.add(textContent(draft.userText()));
        for (Image image : kept.currentImages) current.add(imageContent(image));
        messages.add(message("user", current));
        return messages;
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private static Map<String, Object> textContent(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /** 图片走 data URL：chat_completions 直接可用，Anthropic 适配器会拆成 base64 source。 */
    private static Map<String, Object> imageContent(Image image) {
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("url", "data:" + image.mimeType() + ";base64," + image.base64());
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image_url");
        block.put("image_url", url);
        return block;
    }

    /**
     * 估算视图：数据 URL 的 base64 载荷不计入文本 Token（§4.3"不能把 base64 长度当收费 Token"），
     * 只保留结构与前缀开销；每张图的成本按 {@link TokenEstimator#IMAGE_UNKNOWN_ESTIMATED_TOKENS}
     * 另行从窗口里扣除。纯文本消息原样参与估算。
     */
    private static List<Map<String, Object>> countable(List<Map<String, Object>> messages) {
        List<Map<String, Object>> view = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            if (!(message.get("content") instanceof List<?> blocks)) {
                view.add(message);
                continue;
            }
            List<Object> copied = new ArrayList<>(blocks.size());
            boolean changed = false;
            for (Object block : blocks) {
                Object lean = stripDataUrl(block);
                if (lean != block) changed = true;
                copied.add(lean);
            }
            if (!changed) {
                view.add(message);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", message.get("role"));
            row.put("content", copied);
            view.add(row);
        }
        return view;
    }

    private static Object stripDataUrl(Object block) {
        if (!(block instanceof Map<?, ?> map) || !"image_url".equals(map.get("type"))) return block;
        if (!(map.get("image_url") instanceof Map<?, ?> url)) return block;
        if (!(url.get("url") instanceof String dataUrl) || !dataUrl.startsWith("data:")) return block;
        int comma = dataUrl.indexOf(',');
        String prefix = comma < 0 ? dataUrl : dataUrl.substring(0, comma + 1);
        Map<String, Object> leanUrl = new LinkedHashMap<>();
        leanUrl.put("url", prefix);
        Map<String, Object> lean = new LinkedHashMap<>();
        lean.put("type", map.get("type"));
        lean.put("image_url", leanUrl);
        return lean;
    }

    private String write(Map<String, Object> envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception error) {
            throw new ChatTurnRejectException(500, "CONTEXT_UNSERIALIZABLE", "上下文无法序列化，本轮未外发",
                    Map.of());
        }
    }

    private Map<String, Object> citationView(Citation citation, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("corpusType", citation.corpusType());
        row.put("notebookId", citation.notebookId());
        row.put("pageId", citation.pageId());
        row.put("chunkId", citation.chunkId());
        row.put("title", citation.title());
        row.put("headingPath", citation.headingPath());
        row.put("snippet", citation.snippet());
        row.put("matchTypes", citation.matchTypes());
        row.put("ftsRank", citation.ftsRank());
        row.put("vectorRank", citation.vectorRank());
        row.put("rrfScore", citation.rrfScore());
        return row;
    }

    private void buildAudit(ContextAudit.Builder audit, Kept kept, Draft draft,
                            ConversationProjection.Projection projection, int estimated, int limit) {
        audit.region(ContextAudit.Region.SYSTEM, estimator.estimateText(draft.systemText()), 1);
        audit.region(ContextAudit.Region.MEMORY, sum(kept.memories), kept.memories.size());
        int sessionState = sum(listOfNotNull(kept.summary)) + sum(listOfNotNull(kept.resumeState));
        audit.region(ContextAudit.Region.SESSION_STATE, sessionState,
                (kept.summary == null ? 0 : 1) + (kept.resumeState == null ? 0 : 1));
        audit.region(ContextAudit.Region.RECENT_TURNS, kept.turns.stream().mapToInt(Turn::estimatedTokens).sum(),
                kept.turns.size());
        audit.region(ContextAudit.Region.PAGE, sum(kept.page), kept.page.size());
        audit.region(ContextAudit.Region.NOTEBOOK_RAG, sum(kept.rag), kept.rag.size());
        audit.region(ContextAudit.Region.USER_TEXT, estimator.estimateText(draft.userText()), 1);
        audit.region(ContextAudit.Region.TEXT_ATTACHMENT, sum(kept.attachments), kept.attachments.size());
        audit.region(ContextAudit.Region.IMAGE, kept.imageTokens(), kept.allImages().size());

        if (kept.summary != null) {
            // 摘要的权威版本就是检查点 revision：放进 selected.version，检查器据此判断"是否已被替换"
            audit.select(selected("SUMMARY", kept.summary.id(), kept.summary.id(), null));
        }
        if (kept.resumeState != null) {
            audit.select(selected("RESUME_STATE", kept.resumeState.id(), kept.resumeState.version(), null));
        }
        for (Item memory : kept.memories) {
            audit.select(selected("MEMORY", memory.id(), memory.version(), null));
        }
        for (Item excerpt : kept.rag) {
            audit.select(selected("NOTEBOOK_RAG", excerpt.id(), excerpt.version(), null));
        }
        for (Item page : kept.page) {
            audit.select(selected("PAGE", page.id(), page.version(), null));
        }
        for (Item attachment : kept.attachments) {
            audit.select(selected("TEXT_ATTACHMENT", attachment.id(), attachment.version(), null));
        }
        for (Image image : kept.allImages()) {
            audit.select(selected("IMAGE", image.attachmentId(), image.blockId(), image.sourceTurnId()));
        }
        for (Turn turn : kept.turns) {
            if (turn.turnId() == null) {
                audit.select(selected("LEGACY_MESSAGE", String.valueOf(turn.assistantMessageId()), null, null));
            } else {
                audit.select(selected("TURN", String.valueOf(turn.turnId()), null, turn.turnId()));
            }
        }
    }

    private static List<Item> listOfNotNull(Item item) {
        return item == null ? List.of() : List.of(item);
    }

    private static int sum(List<Item> items) {
        int total = 0;
        for (Item item : items) total += item.estimatedTokens();
        return total;
    }

    private static ContextAudit.Selected selected(String kind, String id, String version, Long sourceTurnId) {
        return new ContextAudit.Selected(sourceKey(kind, id, version, sourceTurnId), kind, id, version, sourceTurnId);
    }

    /** 来源选择键：本轮 plan 内的稳定标识，预览接口先验它属于 selected 才给片段。 */
    private static String sourceKey(String kind, String id, String version, Long sourceTurnId) {
        String raw = kind + '|' + id + '|' + (version == null ? "" : version) + '|'
                + (sourceTurnId == null ? "" : sourceTurnId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "sk" + java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private static List<String> describeOverflow(Kept kept) {
        List<String> categories = new ArrayList<>();
        if (!kept.currentImages.isEmpty()) categories.add("IMAGE");
        if (!kept.turns.isEmpty()) categories.add("RECENT_TURNS");
        if (!kept.rag.isEmpty()) categories.add("NOTEBOOK_RAG");
        if (!kept.page.isEmpty()) categories.add("PAGE");
        if (!kept.attachments.isEmpty()) categories.add("TEXT_ATTACHMENT");
        if (!kept.memories.isEmpty()) categories.add("MEMORY");
        categories.add("USER_TEXT");
        return categories;
    }

    private ChatTurnRejectException tooLarge(List<String> categories, int estimated, int limit, Kept kept) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("estimatedInputTokens", estimated);
        details.put("inputBudget", limit);
        details.put("categoriesToShorten", categories);
        details.put("imagesProvided", kept.currentImages.size());
        return new ChatTurnRejectException(413, "CONTEXT_TOO_LARGE",
                "本轮上下文装不下，请缩短：" + String.join("、", categories), details);
    }
}
