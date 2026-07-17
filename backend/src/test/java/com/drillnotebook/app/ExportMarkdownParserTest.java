package com.drillnotebook.app;

import com.drillnotebook.app.service.ExportMarkdownParser;
import com.drillnotebook.app.service.MarkdownQuestionParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportMarkdownParserTest {

    private final ExportMarkdownParser parser = new ExportMarkdownParser();

    @Test
    void parsesExportFormatRoundTrip() {
        String source = """
                # 我的题库

                ### Java 中用于定义类的关键字是什么？

                > 题型：单选；章节：Java 基础；标签：java、basics；难度：2

                A. function
                B. class
                C. struct
                D. type

                **答案：** B

                **解析：**

                Java 使用 class 关键字声明类。
                ---
                ### 以下哪些属于 Java 集合接口？

                > 题型：多选；章节：Java 基础；标签：java、collection；难度：3

                A. List
                B. Thread
                C. Set
                D. String

                **答案：** A、C

                **解析：**

                List 和 Set 都是 Java 集合框架中的接口。
                """;
        List<MarkdownQuestionParser.ParsedQuestion> result = parser.parse(source);
        assertEquals(2, result.size());

        MarkdownQuestionParser.ParsedQuestion q1 = result.get(0);
        assertEquals("single", q1.type());
        assertEquals("Java 中用于定义类的关键字是什么？", q1.stem());
        assertEquals(4, q1.options().size());
        assertEquals("B", q1.answer());
        assertEquals("Java 使用 class 关键字声明类。", q1.analysis());
        assertEquals(2, q1.difficulty());
        assertEquals("Java 基础", q1.chapter());
        assertEquals(List.of("java", "basics"), q1.tags());
        assertEquals(null, q1.groupId());
        assertEquals(null, q1.orderInGroup());

        MarkdownQuestionParser.ParsedQuestion q2 = result.get(1);
        assertEquals("multiple", q2.type());
        assertEquals("A,C", q2.answer());
    }

    @Test
    void parsesFillTrueFalseEssayTypes() {
        String source = """
                ### JVM 的英文全称是什么？

                > 题型：填空；难度：2

                **答案：** Java Virtual Machine

                **解析：**

                JVM 是 Java Virtual Machine 的缩写。
                ---
                ### Java 字节码可以由不同平台上的 JVM 执行。

                > 题型：判断；难度：1

                **答案：** true

                **解析：**

                JVM 屏蔽了底层平台差异。
                ---
                ### 简述 Java 垃圾回收的目标。

                > 题型：解答；难度：4

                **参考答案：**

                识别并回收不再可达的对象，释放堆内存。

                **解析：**

                常见实现以可达性分析判断对象是否仍可从 GC Roots 访问。
                """;
        List<MarkdownQuestionParser.ParsedQuestion> result = parser.parse(source);
        assertEquals(3, result.size());

        assertEquals("fill", result.get(0).type());
        assertEquals("Java Virtual Machine", result.get(0).answer());
        assertEquals(0, result.get(0).options().size());

        assertEquals("true_false", result.get(1).type());
        assertEquals("true", result.get(1).answer());

        assertEquals("essay", result.get(2).type());
        assertTrue(result.get(2).answer().contains("识别并回收不再可达的对象"));
    }

    @Test
    void infersTypeWhenMetadataMissing() {
        String source = """
                ### 单选题

                A. 选项一
                B. 选项二

                **答案：** A
                ---
                ### 多选题

                A. 选项一
                B. 选项二
                C. 选项三

                **答案：** A、B、C
                """;
        List<MarkdownQuestionParser.ParsedQuestion> result = parser.parse(source);
        assertEquals(2, result.size());
        assertEquals("single", result.get(0).type());
        assertEquals("A", result.get(0).answer());
        assertEquals("multiple", result.get(1).type());
        assertEquals("A,B,C", result.get(1).answer());
    }

    @Test
    void returnsEmptyListForTitleOnlyInput() {
        List<MarkdownQuestionParser.ParsedQuestion> result = parser.parse("# 只有标题\n\n没有题目");
        assertTrue(result.isEmpty());
    }

    @Test
    void throwsForBlankStem() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                ### 

                A. 选项
                B. 选项二

                **答案：** A
                """));
    }

    @Test
    void throwsForUnknownTypeLabel() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                ### 题干

                > 题型：未知题型

                A. x
                B. y

                **答案：** A
                """));
    }
}
