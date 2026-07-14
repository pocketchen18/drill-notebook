package com.drillnotebook.app.service;

import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * PDF 导入编排层。
 * 流程：base64 解码 → PdfTextExtractor 提取文本 → 规则/AI 分支 → 复用 importParsed。
 */
@Service
public class PdfImportService {
    private static final double CONFIDENCE_THRESHOLD = 0.5;
    private static final int MIN_QUESTIONS_THRESHOLD = 3;

    private final PdfTextExtractor textExtractor;
    private final PdfTextAdapter textAdapter;
    private final MarkdownQuestionParser markdownParser;
    private final JsonQuestionParser jsonParser;
    private final AiService aiService;
    private final QuestionImportService importService;

    public PdfImportService(PdfTextExtractor textExtractor, PdfTextAdapter textAdapter,
                            MarkdownQuestionParser markdownParser, JsonQuestionParser jsonParser,
                            AiService aiService, QuestionImportService importService) {
        this.textExtractor = textExtractor;
        this.textAdapter = textAdapter;
        this.markdownParser = markdownParser;
        this.jsonParser = jsonParser;
        this.aiService = aiService;
        this.importService = importService;
    }

    public QuestionImportService.ImportResult importPdf(long bankId, String content, boolean forceAi, String masterPassword) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("缺少 PDF 内容");
        byte[] pdfBytes = decodeBase64(content);
        String rawText = textExtractor.extract(pdfBytes);
        if (rawText == null || rawText.isBlank()) throw new IllegalArgumentException("PDF 文本提取结果为空");
        if (forceAi) return importViaAi(bankId, rawText, masterPassword, true);
        return importViaRulesWithFallback(bankId, rawText, masterPassword);
    }

    private QuestionImportService.ImportResult importViaRulesWithFallback(long bankId, String rawText, String masterPassword) {
        PdfTextAdapter.AdapterResult adapted;
        try {
            adapted = textAdapter.adapt(rawText);
        } catch (Exception adapterError) {
            return tryAiFallback(bankId, rawText, masterPassword, null);
        }
        if (adapted.totalQuestions() < MIN_QUESTIONS_THRESHOLD || adapted.confidence() < CONFIDENCE_THRESHOLD) {
            return tryAiFallback(bankId, rawText, masterPassword, adapted);
        }
        try {
            var parsed = markdownParser.parse(adapted.markdown());
            return importService.importParsed(bankId, parsed, "rules");
        } catch (Exception parseError) {
            return tryAiFallback(bankId, rawText, masterPassword, adapted);
        }
    }

    private QuestionImportService.ImportResult tryAiFallback(long bankId, String rawText, String masterPassword,
                                                              PdfTextAdapter.AdapterResult adapted) {
        try {
            return importViaAi(bankId, rawText, masterPassword, false);
        } catch (Exception aiError) {
            if (adapted != null && adapted.totalQuestions() > 0) {
                try {
                    var parsed = markdownParser.parse(adapted.markdown());
                    return importService.importParsed(bankId, parsed, "rules");
                } catch (Exception ignored) { /* fall through */ }
            }
            String hint = "规则解析失败且 AI 兜底不可用：" + (aiError.getMessage() == null ? "未知错误" : aiError.getMessage());
            throw new IllegalArgumentException(hint);
        }
    }

    /**
     * 识别 PDF 提取出的文本是否含"规整 Markdown 转 PDF 再提取"的残迹。
     * 命中说明此 PDF 本不该走 Adapter（Adapter 是给版式 Y/X/Z 题目文本用的），最该走 AI。
     */
    private static boolean looksLikeMarkdownArtifact(String rawText) {
        if (rawText == null) return false;
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        long fences = normalized.lines().filter(l -> l.trim().equals("===")).count();
        long typeLines = normalized.lines().filter(l -> l.startsWith("type:") && l.contains("answer:")).count();
        return fences >= 2 && typeLines >= 2;
    }

    private QuestionImportService.ImportResult importViaAi(long bankId, String rawText, String masterPassword, boolean forced) {
        String json = aiService.parseQuestionsFromText(rawText, masterPassword);
        var parsed = jsonParser.parse(json);
        return importService.importParsed(bankId, parsed, forced ? "ai" : "ai-fallback");
    }

    private static byte[] decodeBase64(String content) {
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("PDF 内容不是合法的 base64", error);
        }
    }
}
