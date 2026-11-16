package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSettingsTest {
    @Test
    fun largeSavedHistoryAndDownloadsRoundTripWithUtf8() {
        val directory = Files.createTempDirectory("dawngasm-desktop-settings")
        val file = directory.resolve("settings.json")
        val tracks = (1..200).map { index ->
            Track(
                id = "track-$index",
                title = "Título 日本語 $index — a long title that exercises non-ASCII storage",
                author = "Author $index",
                host = AudioHost.entries[index % AudioHost.entries.size],
                pageUrl = "https://example.test/post/$index",
                subreddit = "gonewildaudio",
                score = index.toLong() * 17,
                createdUtc = 1_700_000_000L + index,
                localUri = if (index % 2 == 0) "file:///tmp/track-$index.m4a" else null,
            )
        }

        val first = AppPreferences(DesktopSettings(file))
        first.saveSaved(tracks)
        first.saveHistory(tracks)
        first.saveDownloads(tracks)

        assertTrue(Files.exists(file), "settings file should be written")
        assertTrue(Files.size(file) > 10_000L, "large track JSON should be stored without an 8 KB limit")

        val reloaded = AppPreferences(DesktopSettings(file))
        assertEquals(tracks, reloaded.saved())
        assertEquals(tracks, reloaded.history())
        assertEquals(tracks, reloaded.downloads())
    }

    @Test
    fun typedValuesRoundTrip() {
        val file = Files.createTempDirectory("dawngasm-desktop-typed").resolve("settings.json")
        DesktopSettings(file).apply {
            putInt("int", 42)
            putLong("long", 9_000_000_000L)
            putBoolean("bool", true)
            putFloat("float", 1.5f)
            putDouble("double", 2.25)
            putString("string", "välue ✓")
        }

        val reloaded = DesktopSettings(file)
        assertEquals(42, reloaded.getIntOrNull("int"))
        assertEquals(9_000_000_000L, reloaded.getLongOrNull("long"))
        assertEquals(true, reloaded.getBooleanOrNull("bool"))
        assertEquals(1.5f, reloaded.getFloatOrNull("float"))
        assertEquals(2.25, reloaded.getDoubleOrNull("double"))
        assertEquals("välue ✓", reloaded.getStringOrNull("string"))
    }

    @Test
    fun writesDoNotLeaveTempFilesBehind() {
        val directory = Files.createTempDirectory("dawngasm-desktop-atomic")
        val file = directory.resolve("settings.json")
        val settings = DesktopSettings(file)
        repeat(20) { index -> settings.putString("key-$index", "value-$index") }

        val leftovers = Files.list(directory).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "temp files should be moved or deleted: $leftovers")
    }
}
