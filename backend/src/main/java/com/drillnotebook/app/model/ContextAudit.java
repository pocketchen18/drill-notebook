package com.drillnotebook.app.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本轮上下文的有界审计（contracts §4.5）：只描述"实际提供给模型什么、为什么少了"，
 * 不含正文、记忆值、摘要文本或附件字节。审计随 result_meta 加密落库，检查器只读它。
 *
 * <p>所有可变集合都在构造时收敛到契约上限：selected ≤64、omissions ≤16 组、coverage ≤128 段。
 * 收不住时按契约只砍诊断明细并置 {@code auditTruncated}，保留 planId、预算、已选来源与版本，
 * 绝不把"已经发出去过的来源"藏起来。</p>
 */
public record ContextAudit(String planId, String dispatchState, int estimatedInputTokens, int inputBudget,
                           String estimatorVersion, List<Selected> selected, List<RegionUsage> regions,
                           List<OmissionGroup> omissions, Scan scan, Summary summary, ResumeState resumeState,
                           boolean auditTruncated) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SELECTED = 64;
    public static final int MAX_OMISSION_GROUPS = 16;
    public static final int MAX_COVERAGE_RANGES = 128;
    /** result_meta 明文上限（§2.7），审计是它的一部分，必须自己能收进这个尺寸。 */
    public static final int MAX_AUDIT_BYTES = 16 * 1024;

    public static final String DISPATCH_NOT_SENT = "not_sent";
    public static final String DISPATCH_SENT = "sent";
    public static final String DISPATCH_UNKNOWN = "unknown";

    /** 区域是固定枚举：检查器与预算共用同一组名字，不允许自由字符串。 */
    public enum Region {
        SYSTEM, MEMORY, SESSION_STATE, RECENT_TURNS, PAGE, NOTEBOOK_RAG, USER_TEXT, TEXT_ATTACHMENT, IMAGE
    }

    /** 省略原因同样固定（§4.5 的 OmissionReason）。 */
    public enum Omission {
        BUDGET, COVERED_BY_SUMMARY, OUTSIDE_SCAN_WINDOW, WRONG_GENERATION, NON_COMPLETED, SOURCE_DELETED,
        SOURCE_CHANGED, LOCKED, NOT_AUTHORIZED, DUPLICATE, ATTACHMENT_POLICY, ITEM_LIMIT
    }

    public record Selected(String sourceKey, String kind, String id, String version, Long sourceTurnId) {
    }

    public record RegionUsage(Region region, int estimatedTokens, int itemCount) {
    }

    public record OmissionGroup(Omission reason, String kind, Integer count) {
    }

    public record Scan(Long firstTurnId, Long lastTurnId, int scannedTurns, boolean hasOlderUnscanned) {
    }

    public record Summary(long revision, long throughTurnId, boolean hasGaps, List<CoverageRange> coverageRanges) {
    }

    public record CoverageRange(long firstTurnId, long lastTurnId) {
    }

    public record ResumeState(String requestId, String status, String finishedAt) {
    }

    /** 变成要加密落库的形态：键名即契约字段名，不做本地化。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", SCHEMA_VERSION);
        map.put("planId", planId);
        map.put("dispatchState", dispatchState);
        map.put("estimatedInputTokens", estimatedInputTokens);
        map.put("inputBudget", inputBudget);
        map.put("estimatorVersion", estimatorVersion);
        List<Map<String, Object>> selectedRows = new ArrayList<>();
        for (Selected item : selected) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceKey", item.sourceKey());
            row.put("kind", item.kind());
            row.put("id", item.id());
            row.put("version", item.version());
            row.put("sourceTurnId", item.sourceTurnId());
            selectedRows.add(row);
        }
        map.put("selected", selectedRows);
        List<Map<String, Object>> regionRows = new ArrayList<>();
        for (RegionUsage region : regions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", region.region().name());
            row.put("estimatedTokens", region.estimatedTokens());
            row.put("itemCount", region.itemCount());
            regionRows.add(row);
        }
        map.put("regions", regionRows);
        List<Map<String, Object>> omissionRows = new ArrayList<>();
        for (OmissionGroup group : omissions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", group.reason().name());
            row.put("kind", group.kind());
            // count=null 表示"更早的没扫"，不能伪装成"都已处理"
            row.put("count", group.count());
            omissionRows.add(row);
        }
        map.put("omissions", omissionRows);
        Map<String, Object> scanMap = new LinkedHashMap<>();
        scanMap.put("firstTurnId", scan.firstTurnId());
        scanMap.put("lastTurnId", scan.lastTurnId());
        scanMap.put("scannedTurns", scan.scannedTurns());
        scanMap.put("hasOlderUnscanned", scan.hasOlderUnscanned());
        map.put("scan", scanMap);
        if (summary == null) {
            map.put("summary", null);
        } else {
            Map<String, Object> summaryMap = new LinkedHashMap<>();
            summaryMap.put("revision", summary.revision());
            summaryMap.put("throughTurnId", summary.throughTurnId());
            summaryMap.put("hasGaps", summary.hasGaps());
            List<Map<String, Object>> ranges = new ArrayList<>();
            for (CoverageRange range : summary.coverageRanges()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("firstTurnId", range.firstTurnId());
                row.put("lastTurnId", range.lastTurnId());
                ranges.add(row);
            }
            summaryMap.put("coverageRanges", ranges);
            map.put("summary", summaryMap);
        }
        if (resumeState == null) {
            map.put("resumeState", null);
        } else {
            Map<String, Object> resume = new LinkedHashMap<>();
            resume.put("requestId", resumeState.requestId());
            resume.put("status", resumeState.status());
            resume.put("finishedAt", resumeState.finishedAt());
            map.put("resumeState", resume);
        }
        map.put("auditTruncated", auditTruncated);
        return map;
    }

    public ContextAudit withDispatchState(String state) {
        return new ContextAudit(planId, state, estimatedInputTokens, inputBudget, estimatorVersion,
                selected, regions, omissions, scan, summary, resumeState, auditTruncated);
    }

    /**
     * 序列化后仍然超尺寸时的收缩（§4.5）：只砍诊断明细（coverage 与 omission 分组），
     * 保留 planId、预算、已选来源与版本，并如实标 auditTruncated——已发过的来源不能在检查器里消失。
     */
    public ContextAudit trimmedForDiagnostics() {
        Summary lean = summary == null ? null
                : new Summary(summary.revision(), summary.throughTurnId(), summary.hasGaps(), List.of());
        return new ContextAudit(planId, dispatchState, estimatedInputTokens, inputBudget, estimatorVersion,
                selected, regions, List.of(new OmissionGroup(Omission.ITEM_LIMIT, "DIAGNOSTICS", null)),
                scan, lean, resumeState, true);
    }

    /** 逐个累计、最后收敛的检查器草稿。 */
    public static final class Builder {
        private final String planId;
        private final String estimatorVersion;
        private final List<Selected> selected = new ArrayList<>();
        private final Map<Region, RegionUsage> regions = new LinkedHashMap<>();
        private final Map<String, OmissionGroup> omissions = new LinkedHashMap<>();
        private boolean itemLimitHit;
        /** 超出分组上限的省略合并成一条 OTHER 组，只丢诊断粒度，不丢数量。 */
        private int otherOmissions;

        public Builder(String planId, String estimatorVersion) {
            this.planId = planId;
            this.estimatorVersion = estimatorVersion;
        }

        /** 选中一个来源；超过 64 项不再记录明细，只置 ITEM_LIMIT（§4.5）。 */
        public void select(Selected item) {
            if (selected.size() >= MAX_SELECTED) {
                itemLimitHit = true;
                return;
            }
            selected.add(item);
        }

        public void region(Region region, int estimatedTokens, int itemCount) {
            regions.put(region, new RegionUsage(region, estimatedTokens, itemCount));
        }

        /** 同一原因 + 同一类别合并计数，避免审计里出现上百条同义行。 */
        public void omit(Omission reason, String kind, Integer count) {
            // count=null 是契约要求的"更早的没扫描"：数量未知，不能假造成 0 或"都已总结"
            if (count != null && count <= 0) return;
            String key = reason.name() + '|' + (kind == null ? "" : kind);
            OmissionGroup existing = omissions.get(key);
            if (existing == null) {
                if (omissions.size() >= MAX_OMISSION_GROUPS) {
                    if (count != null) otherOmissions += count;
                    return;
                }
                omissions.put(key, new OmissionGroup(reason, kind, count));
                return;
            }
            if (existing.count() == null || count == null) return;
            omissions.put(key, new OmissionGroup(reason, kind, existing.count() + count));
        }

        /** 撤销一条省略记录（如摘要被舍弃后原文已回补）：账本不能再声称它被替代过。 */
        public void clearOmission(Omission reason, String kind) {
            omissions.remove(reason.name() + '|' + (kind == null ? "" : kind));
        }

        public ContextAudit build(String dispatchState, int estimatedInputTokens, int inputBudget, Scan scan,
                                  Summary summary, ResumeState resumeState) {
            List<OmissionGroup> groups = new ArrayList<>(omissions.values());
            if (otherOmissions > 0) {
                groups.add(new OmissionGroup(Omission.ITEM_LIMIT, "OTHER", otherOmissions));
            }
            if (itemLimitHit) {
                groups.add(new OmissionGroup(Omission.ITEM_LIMIT, "SELECTION", null));
            }
            Summary bounded = summary == null ? null : new Summary(summary.revision(), summary.throughTurnId(),
                    summary.hasGaps(), summary.coverageRanges().size() > MAX_COVERAGE_RANGES
                            ? List.copyOf(summary.coverageRanges().subList(0, MAX_COVERAGE_RANGES))
                            : summary.coverageRanges());
            return new ContextAudit(planId, dispatchState, estimatedInputTokens, inputBudget, estimatorVersion,
                    List.copyOf(selected), List.copyOf(regions.values()), List.copyOf(groups), scan, bounded,
                    resumeState, false);
        }
    }
}
