package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.AttemptFailure;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.SummaryDraft;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * P6 的确定性摘要占位实现（§14「先用 deterministic fake extractor」的摘要侧）：不外发、不产出草稿。
 *
 * <p>返回空草稿的任务终态是 {@code NO_NEW_INFORMATION} 并照常推进水位，
 * <b>不</b>写检查点：P6 不能让一个假的正文进入下一轮上下文，
 * 真正的 {@code SESSION_SUMMARY_V2} 调用、coverage 逐轮校验与 600 Token 渲染属 P8（§8.4）。</p>
 */
@Component
public class DeterministicSummaryDrafter implements MaintenancePorts.SummaryDrafter {
    private static final String PROMPT_PLACEHOLDER = "SESSION_SUMMARY_V2 分栏输出：已确认约束、决定、讨论要点、未决问题";

    private final TokenEstimator estimator;

    public DeterministicSummaryDrafter(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    @Override
    public String promptVersion() {
        return MaintenanceRules.PROMPT_VERSION_SUMMARY;
    }

    @Override
    public int framingTokens() {
        return estimator.estimateText(PROMPT_PLACEHOLDER);
    }

    @Override
    public Optional<SummaryDraft> draft(Batch batch) throws AttemptFailure {
        return Optional.empty();
    }
}
