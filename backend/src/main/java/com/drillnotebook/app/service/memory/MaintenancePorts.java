package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.repository.MemoryJobRepository.Kind;
import java.util.List;
import java.util.Map;
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
                             String userText, String assistantText, int estimatedTokens, long finishedMillis,
                             List<SourceBlock> blocks, String sourceCreatedAt) {
        public SourceTurn {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    /**
     * 提取可用的一个来源块。装载器只交 {@code USER_AUTHORED} 块（§4.3：附件块拿不到这个 origin，
     * 所以附件正文永远不可能被当用户亲述来提取），quote 必须在<b>这一块</b>内连续匹配。
     */
    public record SourceBlock(String blockId, String text) {
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

    /**
     * 一次提取调用的产出。{@code format} 是<b>整份返回</b>能不能用（§8.2 第 7 条据此决定修复还是终态），
     * {@code candidates} 已经过 {@link MemoryValidator} 的全部本地校验，{@code rejections} 只有短代码。
     */
    record ExtractionResult(MemoryValidator.FormatStatus format,
                            List<MemoryValidator.ValidatedCandidate> candidates, List<String> rejections,
                            Usage usage, Map<String, Integer> knownTargets, String rawText) {
        ExtractionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            rejections = rejections == null ? List.of() : List.copyOf(rejections);
            knownTargets = knownTargets == null ? Map.of() : Map.copyOf(knownTargets);
            usage = usage == null ? Usage.unknown() : usage;
        }

        boolean formatOk() {
            return format == MemoryValidator.FormatStatus.OK;
        }

        static ExtractionResult formatFailure(MemoryValidator.FormatStatus format, Usage usage,
                                              Map<String, Integer> knownTargets, String rawText) {
            return new ExtractionResult(format, List.of(), List.of(), usage, knownTargets, rawText);
        }
    }

    /** 长期事实提取端口（P7 接真实 {@code AiUpstreamClient} 与配套提示词）。 */
    public interface Extractor {
        /** 提示词版本：进任务行与幂等键，换提示词等于换一批输入。 */
        String promptVersion();

        /** 提示词与既有事实等"非来源"部分的估算占用，装载时先从 3,000 输入预算里扣掉（§8.2 第 4 条）。 */
        int framingTokens();

        /**
         * @param key 记忆库钥匙：只用于读既有事实正文与派生 HMAC，绝不进请求体、日志或异常信息
         * @param repairOf 上一次被判格式不合格的原文；{@code null} 表示首次请求。§8.2 第 7 条：最多修复一次，
         *                 而且修复请求不授予更多数据，只带同一份 schema 与同一段来源
         */
        ExtractionResult extract(Batch batch, MemoryCryptoService.MemoryKey key, String repairOf)
                throws AttemptFailure;
    }

    /** 任务续接摘要端口（P8 接 {@code SESSION_SUMMARY_V2}）。 */
    public interface SummaryDrafter {
        String promptVersion();

        int framingTokens();

        /** 空表示本次没有可发布的草稿（占位实现），任务照常终态而不是失败。 */
        Optional<SummaryDraft> draft(Batch batch) throws AttemptFailure;
    }
}
