package com.drillnotebook.app.service;

import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * PDF 导入薄壳。委托 ImportOrchestrator 编排规则/AI 兜底，
 * 拿到 RuleResult 后复用 QuestionImportService.importParsed 入库。
 */
@Service
public class PdfImportService {

    private final ImportOrchestrator orchestrator;
    private final PdfRuleStrategy pdfRuleStrategy;
    private final PdfAiStrategy pdfAiStrategy;
    private final QuestionImportService importService;

    public PdfImportService(ImportOrchestrator orchestrator,
                            PdfRuleStrategy pdfRuleStrategy,
                            PdfAiStrategy pdfAiStrategy,
                            QuestionImportService importService) {
        this.orchestrator = orchestrator;
        this.pdfRuleStrategy = pdfRuleStrategy;
        this.pdfAiStrategy = pdfAiStrategy;
        this.importService = importService;
    }

    public QuestionImportService.ImportResult importPdf(long bankId, String content, boolean forceAi, String masterPassword) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("缺少 PDF 内容");
        byte[] pdfBytes = decodeBase64(content);
        ImportRequest req = new ImportRequest(bankId, null, pdfBytes, masterPassword, forceAi);
        RuleResult result = orchestrator.run(pdfRuleStrategy, pdfAiStrategy, req);
        return importService.importParsed(bankId, result.parsed(), result.strategy());
    }

    private static byte[] decodeBase64(String content) {
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("PDF 内容不是合法的 base64", error);
        }
    }
}
