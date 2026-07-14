package com.drillnotebook.app.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextExtractorTest {
    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsTextFromGeneratedPdf() throws IOException {
        byte[] pdfBytes = generateSimplePdf("Question one A. option B. option");
        String text = extractor.extract(pdfBytes);
        assertNotNull(text);
        assertTrue(text.contains("Question"), "提取的文本应包含 Question");
        assertTrue(text.contains("A."), "提取的文本应包含选项标记 A.");
    }

    @Test
    void rejectsCorruptPdf() {
        byte[] garbage = "not a pdf".getBytes();
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(garbage));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(new byte[0]));
    }

    /** 用 PDFBox 生成一个极简的、文本层干净的 PDF（含 ASCII 文本，避免中文字体嵌入复杂性）。 */
    private static byte[] generateSimplePdf(String content) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
