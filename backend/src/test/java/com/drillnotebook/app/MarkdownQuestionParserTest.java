package com.drillnotebook.app;

import com.drillnotebook.app.service.MarkdownQuestionParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarkdownQuestionParserTest {
    private final MarkdownQuestionParser parser = new MarkdownQuestionParser();

    @Test
    void parsesSingleAndMultipleQuestions() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/sample-bank.md")) {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var result = parser.parse(source);
            assertEquals(5, result.size());
            assertEquals("single", result.get(0).type());
            assertEquals("B", result.get(0).answer());
            assertEquals(4, result.get(0).options().size());
            assertEquals("A,C", result.get(1).answer());
            assertEquals("fill", result.get(2).type());
            assertEquals("true_false", result.get(3).type());
            assertEquals("essay", result.get(4).type());
        }
    }

    @Test
    void rejectsMissingAnswer() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                ---
                type: single
                ---
                ### 题干
                A question
                A. option
                """));
    }

    @Test
    void parsesFillTrueFalseAndEssayQuestions() {
        var result = parser.parse("""
                ---
                type: fill
                answer: Java Virtual Machine
                ---
                ### 题干
                JVM 的英文全称是什么？
                ===
                ---
                type: true_false
                answer: 对
                ---
                ### 题干
                Java 字节码可以在 JVM 上运行。
                ===
                ---
                type: essay
                ---
                ### 题干
                解释垃圾回收的基本目标。
                ### 参考答案
                回收不可达对象并释放内存。
                ### 解析
                可结合可达性分析说明。
                """);
        assertEquals(3, result.size());
        assertEquals("Java Virtual Machine", result.get(0).answer());
        assertEquals("true", result.get(1).answer());
        assertEquals("回收不可达对象并释放内存。", result.get(2).answer());
        assertEquals(0, result.get(2).options().size());
    }

    @Test
    void allowsEssayWithoutReferenceAnswer() {
        var result = parser.parse("""
                ---
                type: essay
                ---
                ### 题干
                请说明你的设计方案。
                """);
        assertEquals("", result.get(0).answer());
    }

    @Test
    void keepsOptionShapedAnalysisLinesInAnalysis() {
        var result = parser.parse("""
                ---
                type: single
                answer: A
                ---
                ### 题干
                选择正确答案。
                A. 正确选项
                B. 错误选项
                ### 解析
                A. 这是解析中的编号，不是新选项。
                """);
        assertEquals(2, result.get(0).options().size());
        assertEquals("A. 这是解析中的编号，不是新选项。", result.get(0).analysis());
    }

    @Test
    void rejectsInvalidDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                ---
                type: fill
                answer: JVM
                difficulty: hard
                ---
                ### 题干
                Java 虚拟机的缩写是什么？
                """));
    }
}
