package dev.dawngasm.app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormattingTest {
    @Test
    fun formatsRelativeTimes() {
        val now = 1_700_000_000L
        assertEquals("just now", relativeTime(now - 10, now))
        assertEquals("5m ago", relativeTime(now - 300, now))
        assertEquals("2h ago", relativeTime(now - 7_200, now))
        assertEquals("3d ago", relativeTime(now - 259_200, now))
        assertEquals("2mo ago", relativeTime(now - 5_184_000, now))
        assertEquals("1y ago", relativeTime(now - 31_536_000, now))
        assertEquals("", relativeTime(0, now))
    }

    @Test
    fun formatsDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("61:01", formatDuration(3_661_000))
    }

    @Test
    fun compactsLargeScores() {
        assertEquals("999", compactScore(999))
        assertEquals("1.2k", compactScore(1_250))
        assertEquals("12.3k", compactScore(12_345))
        assertEquals("1.5M", compactScore(1_500_000))
    }

    @Test
    fun sanitisesFileNames() {
        assertEquals("a b c", safeFileName("a/b:c", "fallback"))
        assertEquals("fallback", safeFileName("   ", "fallback"))
        assertEquals("fallback", safeFileName("///", "fallback"))
        assertTrue(safeFileName("x".repeat(300), "fallback").length <= 110)
        assertFalse(safeFileName("title", "fallback").endsWith("."))
    }
}
