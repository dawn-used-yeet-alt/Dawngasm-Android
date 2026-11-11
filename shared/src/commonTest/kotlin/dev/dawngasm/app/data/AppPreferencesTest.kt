package dev.dawngasm.app.data

import com.russhwolf.settings.MapSettings
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppPreferencesTest {

    private fun testTrack(id: String) = Track(
        id = id,
        title = "Title $id",
        author = "Author",
        host = AudioHost.SOUNDGASM,
        pageUrl = "https://example.com/$id",
    )

    @Test
    fun testDefaultsAndReset() {
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        assertEquals("top", prefs.sort())
        assertEquals("week", prefs.window())
        assertTrue(prefs.saved().isEmpty())
        assertTrue(prefs.history().isEmpty())
        assertTrue(prefs.downloads().isEmpty())
    }

    @Test
    fun testFeedPersistence() {
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        prefs.saveFeed("new", "month")
        assertEquals("new", prefs.sort())
        assertEquals("month", prefs.window())
    }

    @Test
    fun testBlockedTagsPersistence() {
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        prefs.saveBlockedTags(listOf("f4m", "audio", "ramble"))
        assertEquals(listOf("f4m", "audio", "ramble"), prefs.blockedTags())
    }

    @Test
    fun testClearancePersistence() {
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        assertNull(prefs.hotaudioClearance())
        assertNull(prefs.audiochanClearance())

        prefs.saveHotaudioClearance("cookie123", "UA_Agent")
        assertEquals("cookie123" to "UA_Agent", prefs.hotaudioClearance())

        prefs.clearHotaudioClearance()
        assertNull(prefs.hotaudioClearance())
    }

    @Test
    fun testTrackListsPersistence() {
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        val t1 = testTrack("1")
        val t2 = testTrack("2")

        prefs.saveSaved(listOf(t1, t2))
        assertEquals(listOf(t1, t2), prefs.saved())

        prefs.saveHistory(listOf(t2))
        assertEquals(listOf(t2), prefs.history())

        prefs.saveDownloads(listOf(t1))
        assertEquals(listOf(t1), prefs.downloads())
    }
}
