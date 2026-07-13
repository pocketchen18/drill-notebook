package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
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
                category: Java
                tags: GC, JVM

                GC 回收不可达对象。
                """);
        assertEquals(2, sections.size());
        assertEquals("JVM 内存", sections.get(0).title());
        assertEquals(List.of("JVM", "内存"), sections.get(0).tags());
        assertEquals("垃圾回收", sections.get(1).title());
    }
}
