package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drillnotebook.app.repository.KnowledgePointRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgePointImportServiceTest {
    @Test
    void parsesMarkdownKnowledgeCards() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # JVM 内存
                分类：Java
                标签：JVM，内存

                堆用于保存对象。

                ## 垃圾回收

                GC 回收不可达对象。
                """, 1);
        assertEquals(1, sections.size());
        assertEquals("JVM 内存", sections.get(0).title());
        assertEquals(List.of("JVM", "内存"), sections.get(0).tags());
        assertTrue(sections.get(0).content().contains("## 垃圾回收"));
    }

    @Test
    void parsesByConfiguredHeadingLevel() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 第一章 JVM

                ## 内存结构
                堆、栈、方法区。

                ## 垃圾回收
                GC 算法。
                """, 2);
        assertEquals(2, sections.size());
        assertEquals("内存结构", sections.get(0).title());
        assertEquals("垃圾回收", sections.get(1).title());
        assertTrue(sections.get(0).content().contains("# 第一章 JVM"));
    }

    @Test
    void parseRejectsMissingHeadings() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgePointImportService.parse("只是一段普通文本，没有任何标题。", 2));
    }

    @Test
    void parseRejectsInvalidHeadingLevel() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgePointImportService.parse("# 标题", 0));
        assertThrows(IllegalArgumentException.class, () -> KnowledgePointImportService.parse("# 标题", 7));
    }

    @Test
    void importMarkdownUsesConfiguredLevel() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        Map<String, Object> result = service.importMarkdown(7L, """
                # 第一章 JVM

                ## 内存结构
                堆、栈、方法区。

                ## 垃圾回收
                GC 算法。
                """, 2);

        assertEquals(2, result.get("imported"));
        assertEquals(0, result.get("failed"));
        assertEquals("rules", result.get("strategy"));
    }

    @Test
    void inheritsCategoryFromShallowerHeading() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 第一章：操作系统基础概念

                ## 1. 分时操作系统
                - **定义**：把 CPU 时间划分为很短的"时间片"。

                ## 2. 并行与并发区别
                - **并发**：宏观上"同时进行"。
                """, 2);
        assertEquals(2, sections.size());
        assertEquals("1. 分时操作系统", sections.get(0).title());
        assertEquals("第一章：操作系统基础概念", sections.get(0).category());
        assertEquals("2. 并行与并发区别", sections.get(1).title());
        assertEquals("第一章：操作系统基础概念", sections.get(1).category());
    }

    @Test
    void explicitCategoryOverridesInherited() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 父章节

                ## 子节
                分类：显式分类
                正文内容。
                """, 2);
        assertEquals(1, sections.size());
        assertEquals("显式分类", sections.get(0).category());
    }

    @Test
    void importMarkdownUsesRulesWhenHeadingsPresent() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        Map<String, Object> result = service.importMarkdown(7L, """
                # 标题一
                分类：Java
                标签：JVM，内存

                堆用于保存对象。

                ## 标题二
                category: Java
                tags: GC, JVM

                GC 回收不可达对象。
                """, 1);

        assertEquals(1, result.get("imported"));
        assertEquals("rules", result.get("strategy"));
    }

    @Test
    void importMarkdownFallsBackToAiWhenRulesFail() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        when(aiService.parseKnowledgePointsFromText(anyString(), anyInt())).thenReturn(List.of(
                Map.of("title", "AI 标题", "content", "AI 正文", "category", "Java", "tags", List.of("JVM"))));
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        Map<String, Object> result = service.importMarkdown(7L, "无标题的纯文本，规则路径会拒绝。", 2);

        assertEquals(1, result.get("imported"));
        assertEquals(0, result.get("failed"));
        assertEquals("ai-fallback", result.get("strategy"));
    }

    @Test
    void importMarkdownFailsWhenBothRulesAndAiUnavailable() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        when(aiService.parseKnowledgePointsFromText(anyString(), anyInt())).thenReturn(List.of());
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importMarkdown(7L, "无标题的纯文本，规则路径会拒绝。", 2));
        assertTrue(error.getMessage().contains("规则解析失败且 AI 兜底不可用"));
    }

    @Test
    void importMarkdownFailsWhenAiReturnsEmpty() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        when(aiService.parseKnowledgePointsFromText(anyString(), anyInt())).thenReturn(List.of());
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        assertThrows(IllegalArgumentException.class,
                () -> service.importMarkdown(7L, "无标题的纯文本，规则路径会拒绝。", 2));
    }
}
