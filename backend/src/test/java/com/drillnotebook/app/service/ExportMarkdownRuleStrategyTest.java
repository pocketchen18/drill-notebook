package com.drillnotebook.app.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExportMarkdownRuleStrategyTest {

    private final ExportMarkdownParser parser = new ExportMarkdownParser();
    private final ExportMarkdownRuleStrategy strategy = new ExportMarkdownRuleStrategy(parser);

    @Test
    void parsesExportFormatMarkdown() {
        // 用真实导出格式：### <stem> 直接跟题干
        ImportRequest realReq = new ImportRequest(1L,
                "# 我的题库\n\n### 单选题\n\nA. 选项一\nB. 选项二\n\n**答案：** A\n",
                null, null, false);
        RuleResult result = strategy.attempt(realReq);
        assertEquals(1, result.parsed().size());
        assertEquals("export-rules", result.strategy());
        assertFalse(result.lowQuality());
    }
}
