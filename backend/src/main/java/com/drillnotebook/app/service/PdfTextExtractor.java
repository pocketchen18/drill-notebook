package com.drillnotebook.app.service;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * 从 PDF 字节流提取纯文本。
 * 处理中文 CMap、多页拼接、段落换行恢复。
 */
@Component
public class PdfTextExtractor {
    /**
     * @param pdfBytes PDF 文件的原始字节
     * @return 提取的纯文本（已规范化换行符为 \n）
     * @throws IllegalArgumentException PDF 损坏、格式不支持、输入为空
     */
    public String extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 内容为空");
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return text.replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException error) {
            throw new IllegalArgumentException("PDF 文件损坏或格式不支持", error);
        }
    }
}
