package com.drillnotebook.app.service.memory;

import com.drillnotebook.app.service.TokenEstimator;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.SessionSummary;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.SourcedText;
import com.drillnotebook.app.service.memory.SessionSummaryValidator.WorkItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 发布视图的确定性渲染与裁剪（执行方案 §8.4 第 4 条；contracts §6.3 第 2-3 条）。
 *
 * <p>规则很少但每条都有代价：只<b>整条</b>删除，绝不截断半句（截断会把"先不要改笔记"变成"先不要改"之外的
 * 意思，§6 提示词要求保留否定、数值与期限）；删除顺序按固定优先级，所以同一份结构在任何时候都渲染出同一份
 * 文本（§8.5 的"相同输入不再自动调用"要靠它）；省略条数只作诊断返回，不写进模型可见正文，
 * 也不混进 600 的会话状态预算（§8.4 第 4 条末段）。</p>
 */
public final class SessionSummaryRenderer {
    /** 优先级分层：数字越小越后删。§6.3 第 2 条给出顺序：目标/约束/纠正 → 决定 → 未完成状态 → 下一步 → 开放问题 → 讨论细节。 */
    private static final int TIER_TOPIC = 0;
    private static final int TIER_USER_CONFIRMED = 1;
    private static final int TIER_WORK_STATE = 2;
    private static final int TIER_NEXT_STEPS = 3;
    private static final int TIER_OPEN_QUESTIONS = 4;
    private static final int TIER_DISCUSSION = 5;
    private static final int TIER_REFERENCES = 6;
    /** 这几层就是"最小必要项"：连它们都装不进预算，就不发摘要而是提示 SUMMARY_UNAVAILABLE（§6.3 第 2 条）。 */
    private static final int PROTECTED_TIER = TIER_WORK_STATE;

    private static final String LABEL_TOPIC = "主题：";
    private static final String LABEL_GOAL = "目标：";
    private static final String LABEL_CONSTRAINT = "约束：";
    private static final String LABEL_DECISION = "决定：";
    private static final String LABEL_WORK = "工作：";
    private static final String LABEL_NEXT_STEP = "下一步：";
    private static final String LABEL_QUESTION = "待回答：";
    private static final String LABEL_NOTE = "讨论：";
    private static final String LABEL_REFERENCE = "引用：";

    private SessionSummaryRenderer() {
    }

    /**
     * 发布结果。{@code unavailable} 为真时 {@code modelSummary} 是空串：调用方必须走"没有可发布摘要"，
     * 而不是交一份空检查点冒充整理过（§6.3 第 5 条）。
     *
     * <p>{@code references} 是<b>真的留在发布文本里</b>的那几条引用：检查点台账只能记模型看得到的引用，
     * 否则读侧会因为一条从没发过的页面改版而把整份摘要判失效（§8.4 第 7 条）。</p>
     */
    public record Published(String modelSummary, int estimatedTokens, int omittedItems,
                            List<SessionSummaryValidator.Reference> references, boolean unavailable) {
        public Published {
            references = references == null ? List.of() : List.copyOf(references);
        }

        public static final Published UNAVAILABLE = new Published("", 0, 0, List.of(), true);

        public boolean publishable() {
            return !unavailable && !modelSummary.isBlank();
        }
    }

    /** 按预算渲染：先去重复项，再整条删除低优先内容，直到装得下或只剩最小必要项。 */
    public static Published render(SessionSummary summary, int budgetTokens, TokenEstimator estimator) {
        if (summary == null || summary.empty() || budgetTokens <= 0) {
            return Published.UNAVAILABLE;
        }
        List<Line> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (summary.topic() != null && !summary.topic().isBlank()) {
            add(lines, seen, TIER_TOPIC, LABEL_TOPIC + summary.topic());
        }
        if (summary.taskGoal() != null) {
            add(lines, seen, TIER_USER_CONFIRMED, keyOf(summary.taskGoal()), LABEL_GOAL + lineOf(summary.taskGoal()));
        }
        for (SourcedText item : summary.confirmedUserConstraints()) {
            add(lines, seen, TIER_USER_CONFIRMED, keyOf(item), LABEL_CONSTRAINT + lineOf(item));
        }
        for (SourcedText item : summary.confirmedDecisions()) {
            add(lines, seen, TIER_USER_CONFIRMED, keyOf(item), LABEL_DECISION + lineOf(item));
        }
        for (WorkItem item : summary.workItems()) {
            add(lines, seen, TIER_WORK_STATE, keyOf(item.source()) + "#" + item.state(), LABEL_WORK + workLine(item));
        }
        for (SourcedText item : summary.nextSteps()) {
            add(lines, seen, TIER_NEXT_STEPS, keyOf(item), LABEL_NEXT_STEP + lineOf(item));
        }
        for (SourcedText item : summary.openQuestions()) {
            add(lines, seen, TIER_OPEN_QUESTIONS, keyOf(item), LABEL_QUESTION + lineOf(item));
        }
        for (SourcedText item : summary.discussionNotes()) {
            add(lines, seen, TIER_DISCUSSION, keyOf(item), LABEL_NOTE + lineOf(item));
        }
        for (SessionSummaryValidator.Reference reference : summary.references()) {
            String line = LABEL_REFERENCE + "page" + reference.pageId() + "@" + reference.contentVersion()
                    + "（turn" + reference.sourceTurnId() + "）";
            add(lines, seen, TIER_REFERENCES, line, line, reference);
        }
        if (lines.isEmpty()) {
            return Published.UNAVAILABLE;
        }
        int omitted = 0;
        // 从最低优先层开始整条删（同层里删后面那条）；每次都按最终文本重算估算量，不拿"每行估算相加"冒领整体口径
        while (estimate(lines, estimator) > budgetTokens) {
            int weakest = -1;
            int weakestTier = PROTECTED_TIER;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).tier() > weakestTier) {
                    weakestTier = lines.get(index).tier();
                    weakest = index;
                }
            }
            if (weakest < 0) {
                return new Published("", 0, omitted, List.of(), true);
            }
            lines.remove(weakest);
            omitted++;
        }
        String text = join(lines);
        if (text.isBlank()) {
            return new Published("", 0, omitted, List.of(), true);
        }
        return new Published(text, estimate(lines, estimator), omitted, survivors(lines), false);
    }

    /** 留在发布文本里的引用，按行序：这就是检查点要记的那份台账。 */
    private static List<SessionSummaryValidator.Reference> survivors(List<Line> lines) {
        List<SessionSummaryValidator.Reference> kept = new ArrayList<>();
        for (Line line : lines) {
            if (line.reference() != null) {
                kept.add(line.reference());
            }
        }
        return List.copyOf(kept);
    }

    /**
     * 读侧的再裁剪（§6.3 第 3 条）：resumeState 与本批要占的会话状态预算挤不下时，
     * 只能整条删到装得下；删不动就交回空串，由调用方按"这次没用上摘要"处理。
     *
     * <p>分类靠行首标签而不是重新解析结构：{@link #render} 就是按这些标签发布的，
     * 所以同一条规则在写入侧和读取侧必然一致。</p>
     */
    public static String reduce(String published, int availableTokens, TokenEstimator estimator) {
        if (published == null || published.isBlank()) {
            return "";
        }
        if (estimator.estimateText(published) <= availableTokens) {
            return published;
        }
        List<String> lines = new ArrayList<>(List.of(published.split("\n")));
        while (!lines.isEmpty() && estimator.estimateText(String.join("\n", lines)) > availableTokens) {
            int weakest = -1;
            for (int index = 0; index < lines.size(); index++) {
                if (!isProtected(lines.get(index))) {
                    weakest = index;
                }
            }
            if (weakest < 0) {
                return "";
            }
            lines.remove(weakest);
        }
        return String.join("\n", lines);
    }

    /** 最小必要项之外的行才能删：主题、目标/约束/决定、工作状态在读侧也不许被预算挤掉。 */
    private static boolean isProtected(String line) {
        return !(line.startsWith(LABEL_NEXT_STEP) || line.startsWith(LABEL_QUESTION)
                || line.startsWith(LABEL_NOTE) || line.startsWith(LABEL_REFERENCE));
    }

    private static void add(List<Line> lines, Set<String> seen, int tier, String text) {
        add(lines, seen, tier, text, text, null);
    }

    /**
     * 去重复项比的是内容而不是整行：同一句话出现在不同栏目时只留优先级更高的那条（§6.3 第 2 条第一步）。
     * 调用方给的 {@code key} 去掉栏目名，工作项则把状态算进重键——同一句话不同状态是两个不同的说法。
     */
    private static void add(List<Line> lines, Set<String> seen, int tier, String key, String text) {
        add(lines, seen, tier, key, text, null);
    }

    private static void add(List<Line> lines, Set<String> seen, int tier, String key, String text,
                            SessionSummaryValidator.Reference reference) {
        String normalized = key.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !seen.add(normalized)) {
            return;
        }
        lines.add(new Line(tier, text.replaceAll("\\s+", " ").trim(), reference));
    }

    /** 文本项的去重键：内容 + 谁说的 + 出处轮次，不含栏目名。 */
    private static String keyOf(SourcedText item) {
        return item.text() + "@" + item.sourceRole() + "@" + sourceIds(item);
    }

    private static String lineOf(SourcedText item) {
        return item.text() + "（" + item.sourceRole() + "#" + sourceIds(item) + "）";
    }

    /** 工作项把状态放在正文之后、来源之前：状态是这句话的一部分，不是出处。 */
    private static String workLine(WorkItem item) {
        return item.source().text() + "〔" + item.state() + "〕" + "（" + item.source().sourceRole() + "#"
                + sourceIds(item.source()) + "）";
    }

    private static String sourceIds(SourcedText item) {
        StringBuilder sources = new StringBuilder();
        for (Long turnId : item.sourceTurnIds()) {
            sources.append(sources.isEmpty() ? "" : ",").append(turnId);
        }
        return sources.toString();
    }

    private static String join(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.text());
        }
        return text.toString();
    }

    private static int estimate(List<Line> lines, TokenEstimator estimator) {
        return estimator.estimateText(join(lines));
    }

    private record Line(int tier, String text, SessionSummaryValidator.Reference reference) {
    }
}
