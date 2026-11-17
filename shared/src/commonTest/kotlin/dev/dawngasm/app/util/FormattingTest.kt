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

    @Test
    fun escapesWindowsReservedNames() {
        assertEquals("_CON", safeFileName("CON", "fallback"))
        assertEquals("_con", safeFileName("con", "fallback"))
        assertEquals("_COM1", safeFileName("COM1", "fallback"))
        assertEquals("_lpt9", safeFileName("lpt9", "fallback"))
        assertEquals("_CON.txt", safeFileName("CON.txt", "fallback"))
        assertEquals("CONSOLE", safeFileName("CONSOLE", "fallback"))
        assertEquals("_NUL", safeFileName("NUL.", "fallback"))
    }

    @Test
    fun trimsTrailingDotsAndSpaces() {
        assertEquals("title", safeFileName("title...", "fallback"))
        assertEquals("title", safeFileName("title   ", "fallback"))
        assertEquals("title", safeFileName("title. . .", "fallback"))
        // Truncation must not leave a trailing dot either.
        val truncated = safeFileName("x".repeat(109) + "......", "fallback")
        assertEquals(109, truncated.length)
        assertFalse(truncated.endsWith("."))
    }

    @Test
    fun respectsCustomLengthBudget() {
        assertEquals("abc", safeFileName("abc", "fallback", maxLength = 3))
        assertEquals("abc", safeFileName("abcdef", "fallback", maxLength = 3))
        assertEquals(24, safeFileName("x".repeat(100), "fallback", maxLength = 24).length)
    }
}
