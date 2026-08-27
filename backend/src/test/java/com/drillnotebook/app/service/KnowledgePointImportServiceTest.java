package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drillnotebook.app.repository.KnowledgePointRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgePointImportServiceTest {

    @Test
    void splitsEveryHeadingIncrementally() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 计算机网络
                网络通信概述。
                ## 传输层
                端到端通信。
                ### TCP
                三次握手。
                ### UDP
                无连接。
                ## 网络层
                寻址。
                """);
        assertEquals(5, sections.size());

        assertEquals("计算机网络", sections.get(0).title());
        assertEquals(List.of(), sections.get(0).headingPath());
        assertEquals(1, sections.get(0).level());
        assertEquals("网络通信概述。", sections.get(0).content());

        assertEquals("传输层", sections.get(1).title());
        assertEquals(List.of("计算机网络"), sections.get(1).headingPath());
        assertEquals(2, sections.get(1).level());
        assertEquals("端到端通信。", sections.get(1).content());

        assertEquals("TCP", sections.get(2).title());
        assertEquals(List.of("计算机网络", "传输层"), sections.get(2).headingPath());
        assertEquals(3, sections.get(2).level());
        assertEquals("三次握手。", sections.get(2).content());

        assertEquals("UDP", sections.get(3).title());
        assertEquals(List.of("计算机网络", "传输层"), sections.get(3).headingPath());
        assertEquals("无连接。", sections.get(3).content());

        assertEquals("网络层", sections.get(4).title());
        assertEquals(List.of("计算机网络"), sections.get(4).headingPath());
        assertEquals("寻址。", sections.get(4).content());
    }

    @Test
    void allowsEmptyPreambleOnParentHeading() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 章节
                ## 子节
                内容。
                """);
        assertEquals(2, sections.size());
        assertEquals("章节", sections.get(0).title());
        assertEquals("", sections.get(0).content());
        assertEquals("子节", sections.get(1).title());
        assertEquals("内容。", sections.get(1).content());
    }

    @Test
    void prependsPreambleToFirstHeading() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                前言文字。
                # 第一章
                正文。
                """);
        assertEquals(1, sections.size());
        assertEquals("第一章", sections.get(0).title());
        assertEquals("前言文字。\n正文。", sections.get(0).content());
    }

    @Test
    void inheritsCategoryFromParentHeading() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 操作系统
                ## 进程管理
                进程与线程。
                ## 内存管理
                分页。
                """);
        assertEquals(3, sections.size());
        assertNull(sections.get(0).category());
        assertEquals("进程管理", sections.get(1).title());
        assertEquals("操作系统", sections.get(1).category());
        assertEquals("内存管理", sections.get(2).title());
        assertEquals("操作系统", sections.get(2).category());
    }

    @Test
    void explicitCategoryOverridesInherited() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 父章节
                ## 子节
                分类：显式分类
                正文。
                """);
        assertEquals(2, sections.size());
        assertEquals("显式分类", sections.get(1).category());
    }

    @Test
    void extractsTagsFromBodyLines() {
        List<KnowledgePointImportService.Section> sections = KnowledgePointImportService.parse("""
                # 主题
                标签：重点，易错
                正文。
                """);
        assertEquals(List.of("重点", "易错"), sections.get(0).tags());
        assertEquals("正文。", sections.get(0).content());
    }

    @Test
    void rejectsMissingHeadings() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgePointImportService.parse("只有正文没有标题。"));
    }

    @Test
    void rejectsAllEmptyBodies() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgePointImportService.parse("# 只有标题\n## 还是只有标题"));
    }

    @Test
    void importMarkdownCountsEveryHeading() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        Map<String, Object> result = service.importMarkdown(7L, """
                # 第一章
                ## 第一节
                内容一。
                ## 第二节
                内容二。
                """);
        assertEquals(3, result.get("imported"));
        assertEquals(0, result.get("failed"));
        assertEquals("rules", result.get("strategy"));
    }

    @Test
    void importMarkdownFallsBackToAiWhenRulesFail() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        when(aiService.parseKnowledgePointsFromText(anyString())).thenReturn(List.of(
                Map.of("title", "AI 标题", "content", "AI 正文", "category", "Java", "tags", List.of("JVM"), "level", 1)));
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        Map<String, Object> result = service.importMarkdown(7L, "无标题的纯文本，规则路径会拒绝。");
        assertEquals(1, result.get("imported"));
        assertEquals("ai-fallback", result.get("strategy"));
    }

    @Test
    void importMarkdownFailsWhenBothRulesAndAiUnavailable() {
        KnowledgePointRepository points = mock(KnowledgePointRepository.class);
        AiService aiService = mock(AiService.class);
        when(aiService.parseKnowledgePointsFromText(anyString())).thenReturn(List.of());
        KnowledgePointImportService service = new KnowledgePointImportService(points, aiService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importMarkdown(7L, "无标题的纯文本，规则路径会拒绝。"));
        assertTrue(error.getMessage().contains("规则解析失败且 AI 兜底不可用"));
    }

    @Test
    void rebuildPathsRebuildsHeadingPathFromLevels() {
        List<KnowledgePointImportService.Section> flat = List.of(
                new KnowledgePointImportService.Section("计算机网络", "概述", null, List.of(), List.of(), 1),
                new KnowledgePointImportService.Section("传输层", "端到端", null, List.of(), List.of(), 2),
                new KnowledgePointImportService.Section("TCP", "三次握手", null, List.of(), List.of(), 3),
                new KnowledgePointImportService.Section("网络层", "寻址", null, List.of(), List.of(), 2));
        List<KnowledgePointImportService.Section> rebuilt = KnowledgePointImportService.rebuildPaths(flat);
        assertEquals(4, rebuilt.size());
        assertEquals(List.of(), rebuilt.get(0).headingPath());
        assertEquals(List.of("计算机网络"), rebuilt.get(1).headingPath());
        assertEquals(List.of("计算机网络", "传输层"), rebuilt.get(2).headingPath());
        assertEquals(List.of("计算机网络"), rebuilt.get(3).headingPath());
    }
}
