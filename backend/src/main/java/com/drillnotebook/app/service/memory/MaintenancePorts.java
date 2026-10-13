package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import java.util.List;
import java.util.Optional;

/**
 * 后台维护的输入输出词汇（执行方案 §8 模块依赖：{@code MemoryMaintenanceWorker → MemoryExtractor}）。
 *
 * <p>这里只定义数据形态与两个外发端口，不含任何调度逻辑：</p>
 * <ul>
 *   <li>{@link Batch} 是装载器按 §8.2/§8.4 取出的<b>完整轮次</b>批次，已经带齐身份版本与估算量；</li>
 *   <li>{@link Extractor} 与 {@link SummaryDrafter} 是可能失败的外发步骤，失败用 {@link AttemptFailure}
 *       区分"可以重试"和"结构非法/授权失效"；</li>
 *   <li>端口不持钥匙、不写库：结果由 worker 在同一笔租约事务里复验后才落。</li>
 * </ul>
 */
public final class MaintenancePorts {

    private MaintenancePorts() {
    }

    /**
     * 一批可整理来源。{@code turns} 按轮次 ID 升序且都是完整轮次；提取只看用户发言
     * （{@code assistantText} 为 null，reasoning 与附件原文永不进入本结构，§4 不变量 2），
     * 摘要才带助手正文。
     */
    public record Batch(Kind kind, long sessionId, long fromTurnId, long throughTurnId, long contextGeneration,
                        String scopeType, long scopeId, String timezone, long vaultEpoch,
                        long policyRevision, long summaryRevision, String previousSummary, List<SourceTurn> turns,
                        int framingTokens, int estimatedTokens, String inputRevision, long latestSourceMillis) {
        public Batch {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }

        public boolean empty() {
            return turns.isEmpty();
        }
    }

    /** 一个完整来源轮次：身份（ID + source_revision）与正文一起带，落库前要按身份复验。 */
    public record SourceTurn(long turnId, long sourceRevision, Long userMessageId, Long assistantMessageId,
                             String userText, String assistantText, int estimatedTokens, long finishedMillis) {
    }

    /**
     * 提取产出：本包只关心"这批有没有可用候选"，用于决定终态
     * （{@code NO_NEW_INFORMATION} / {@code NO_VALID_CANDIDATES} / {@code APPLIED}，§2.4）。
     * 候选本体与冲突判定属 P7（§8.3）。
     */
    public record CandidateBatch(int candidateCount, int rejectedCount, Usage usage) {
        public static CandidateBatch none() {
            return new CandidateBatch(0, 0, Usage.unknown());
        }

        public CandidateBatch {
            usage = usage == null ? Usage.unknown() : usage;
        }
    }

    /** 摘要草稿：模型可见正文与它的估算量，净缩减据此计算（§8.4 第 4 条）。 */
    public record SummaryDraft(String modelSummary, int estimatedTokens, Usage usage) {
        public SummaryDraft {
            usage = usage == null ? Usage.unknown() : usage;
        }
    }

    /**
     * 厂商回的实际用量。两个都为 null 就是"不知道"，由 worker 按 {@code UNKNOWN} 消费预留，
     * 绝不拿估算冒充当过的钱（contracts §2.6）。
     */
    public record Usage(Long inputTokens, Long outputTokens) {
        public static Usage unknown() {
            return new Usage(null, null);
        }

        public boolean reported() {
            return inputTokens != null || outputTokens != null;
        }
    }

    /** 一次外发失败。{@code retryable} 对应 §8.1 的"网络/408/429/5xx 才重试"。 */
    public static final class AttemptFailure extends Exception {
        private final String code;
        private final boolean retryable;

        public AttemptFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public AttemptFailure(String code, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** 长期事实提取端口（P7 接真实 {@code AiUpstreamClient} 与配套提示词）。 */
    public interface Extractor {
        /** 提示词版本：进任务行与幂等键，换提示词等于换一批输入。 */
        String promptVersion();

        /** 提示词与既有事实等"非来源"部分的估算占用，装载时先从 3,000 输入预算里扣掉（§8.2 第 4 条）。 */
        int framingTokens();

        CandidateBatch extract(Batch batch) throws AttemptFailure;
    }

    /** 任务续接摘要端口（P8 接 {@code SESSION_SUMMARY_V2}）。 */
    public interface SummaryDrafter {
        String promptVersion();

        int framingTokens();

        /** 空表示本次没有可发布的草稿（占位实现），任务照常终态而不是失败。 */
        Optional<SummaryDraft> draft(Batch batch) throws AttemptFailure;
    }
}
