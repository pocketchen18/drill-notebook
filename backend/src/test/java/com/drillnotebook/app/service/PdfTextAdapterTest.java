package com.drillnotebook.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextAdapterTest {
    private final PdfTextAdapter adapter = new PdfTextAdapter();

    @Test
    void adaptsStructuredLayoutY() throws IOException {
        String raw = readResource("/fixtures/pdf/raw-structured.txt");
        PdfTextAdapter.AdapterResult result = adapter.adapt(raw);
        assertEquals(2, result.totalQuestions());
        assertTrue(result.markdown().contains("type: single"), "应推断 single");
        assertTrue(result.markdown().contains("answer: B"), "应归位答案 B");
        assertTrue(result.confidence() >= 0.5, "版式 Y 置信度应 >= 0.5");
    }

    @Test
    void adaptsScatteredLayoutX() throws IOException {
        String raw = readResource("/fixtures/pdf/raw-scattered.txt");
        PdfTextAdapter.AdapterResult result = adapter.adapt(raw);
        assertEquals(2, result.totalQuestions());
        assertTrue(result.markdown().contains("answer: B"), "应从参考答案章节归位");
    }

    @Test
    void adaptsMixedLayoutZ() throws IOException {
        String raw = readResource("/fixtures/pdf/raw-mixed.txt");
        PdfTextAdapter.AdapterResult result = adapter.adapt(raw);
        assertEquals(2, result.totalQuestions());
        assertTrue(result.markdown().contains("answer: B"), "题 1 答案 B（版式 Y）");
    }

    @Test
    void returnsLowConfidenceForGarbageInput() {
        PdfTextAdapter.AdapterResult result = adapter.adapt("完全无关的文本，没有题目");
        assertEquals(0, result.totalQuestions());
        assertEquals(0.0, result.confidence());
    }

    @Test
    void infersMultipleTypeFromCommaAnswer() {
        String raw = """
                1. 题干一
                A. 选项1
                B. 选项2
                答案：A、B
                """;
        PdfTextAdapter.AdapterResult result = adapter.adapt(raw);
        assertTrue(result.markdown().contains("type: multiple"), "顿号分隔答案应推断 multiple");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = PdfTextAdapterTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
