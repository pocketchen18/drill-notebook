package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MemoryTitlesTest {

    @Test
    void compactsWhitespaceAndKeepsShortValues() {
        assertEquals("第一行 第二行", MemoryTitles.displayTitle("  第一行\n第二行  "));
        assertEquals("普通记忆", MemoryTitles.displayTitle("普通记忆"));
    }

    @Test
    void truncatesToTwentyCodePointsWithoutSplittingSurrogates() {
        assertEquals("复".repeat(20), MemoryTitles.displayTitle("复".repeat(25)));
        assertEquals("😀".repeat(20), MemoryTitles.displayTitle("😀".repeat(25)));
    }

    @Test
    void fallsBackWhenValueIsBlank() {
        assertEquals(MemoryTitles.FALLBACK, MemoryTitles.displayTitle(null));
        assertEquals(MemoryTitles.FALLBACK, MemoryTitles.displayTitle(" \n\u3000 "));
    }
}
