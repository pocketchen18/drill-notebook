package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.MaintenancePorts.AttemptFailure;
import com.drillnotebook.app.service.memory.MaintenancePorts.Batch;
import com.drillnotebook.app.service.memory.MaintenancePorts.CandidateBatch;
import org.springframework.stereotype.Component;

/**
 * P6 的确定性提取占位实现（§14「先用 deterministic fake extractor」）：不外发、不产出候选。
 *
 * <p>它存在的意义是让调度面（入队/租约/预算/水位/恢复）在真实模型接入之前就能被完整测试。
 * 真实提取与冲突规则属 P7（§8.2 第 6-9 条、§8.3），届时替换本类并接入独立 maintenance options。</p>
 *
 * <p>因为不产出候选，任务终态是 {@code NO_NEW_INFORMATION}：水位照常推进，
 * 但事实库没有变化，所以既不递增 vault 内容 revision，也不重建索引（§8.2 第 9 条）。</p>
 */
@Component
public class DeterministicMemoryExtractor implements MaintenancePorts.Extractor {
    /** 占位提示词只用来给输入预算留出真实提示词的体量，不会被发出去。 */
    private static final String PROMPT_PLACEHOLDER = "MEMORY_EXTRACT_V2 严格 JSON 输出：候选事实、来源轮次与原话引用";

    private final TokenEstimator estimator;

    public DeterministicMemoryExtractor(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    @Override
    public String promptVersion() {
        return MaintenanceRules.PROMPT_VERSION_EXTRACT;
    }

    @Override
    public int framingTokens() {
        return estimator.estimateText(PROMPT_PLACEHOLDER);
    }

    @Override
    public CandidateBatch extract(Batch batch) throws AttemptFailure {
        return CandidateBatch.none();
    }
}
