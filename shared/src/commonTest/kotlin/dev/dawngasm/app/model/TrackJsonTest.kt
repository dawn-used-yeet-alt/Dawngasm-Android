package dev.dawngasm.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackJsonTest {

    private val sampleTrack = Track(
        id = "track_123",
        title = "Rainy Day Whispers [F4M]",
        author = "GentleVoice",
        host = AudioHost.SOUNDGASM,
        pageUrl = "https://soundgasm.net/u/GentleVoice/Rainy-Day",
        subreddit = "gonewildaudio",
        score = 42L,
        createdUtc = 1600000000L,
        localUri = "content://media/external/audio/123",
    )

    @Test
    fun testTrackRoundTrip() {
        val jsonString = TrackJson.encodeToString(sampleTrack)
        val decoded = TrackJson.decodeFromString(jsonString)
        assertEquals(sampleTrack, decoded)
    }

    @Test
    fun testTrackSerializationFormat() {
        val jsonString = TrackJson.encodeToString(sampleTrack)
        assertTrue(jsonString.contains("\"id\":\"track_123\""))
        assertTrue(jsonString.contains("\"host\":\"soundgasm\""))
        assertTrue(jsonString.contains("\"title\":\"Rainy Day Whispers [F4M]\""))
        assertTrue(jsonString.contains("\"localUri\":\"content://media/external/audio/123\""))
    }

    @Test
    fun testBackwardsCompatibilityWithLegacyJson() {
        // Legacy JSON produced by org.json in prior versions
        val legacyJson = """
            {
                "id": "legacy_1",
                "title": "Old Track",
                "author": "OldAuthor",
                "host": "whyp",
                "pageUrl": "https://whyp.it/tracks/1",
                "subreddit": "gwa",
                "score": 100,
                "createdUtc": 1500000000,
                "localUri": "file:///path/to/audio.m4a"
            }
        """.trimIndent()

        val decoded = TrackJson.decodeFromString(legacyJson)
        assertEquals("legacy_1", decoded.id)
        assertEquals("Old Track", decoded.title)
        assertEquals("OldAuthor", decoded.author)
        assertEquals(AudioHost.WHYP, decoded.host)
        assertEquals("https://whyp.it/tracks/1", decoded.pageUrl)
        assertEquals("gwa", decoded.subreddit)
        assertEquals(100L, decoded.score)
        assertEquals(1500000000L, decoded.createdUtc)
        assertEquals("file:///path/to/audio.m4a", decoded.localUri)
    }

    @Test
    fun testMissingOptionalFieldsDefaultGracefully() {
        val minimalJson = """
            {
                "id": "min_1",
                "title": "Minimal",
                "author": "Author",
                "host": "soundgasm",
                "pageUrl": "https://example.com"
            }
        """.trimIndent()

        val decoded = TrackJson.decodeFromString(minimalJson)
        assertEquals("min_1", decoded.id)
        assertEquals("", decoded.subreddit)
        assertEquals(0L, decoded.score)
        assertEquals(0L, decoded.createdUtc)
        assertNull(decoded.localUri)
    }

    @Test
    fun testUnknownHostDefaultsSafely() {
        val unknownHostJson = """
            {
                "id": "unk_1",
                "title": "Unknown Host",
                "author": "Author",
                "host": "nonexistent_host",
                "pageUrl": "https://example.com"
            }
        """.trimIndent()

        val decoded = TrackJson.decodeFromString(unknownHostJson)
        assertEquals(AudioHost.UNKNOWN, decoded.host)
    }

    @Test
    fun testIgnoreUnknownKeys() {
        val extraKeysJson = """
            {
                "id": "extra_1",
                "title": "Extra",
                "author": "Author",
                "host": "soundgasm",
                "pageUrl": "https://example.com",
                "futureField": 42,
                "extraMetadata": { "foo": "bar" }
            }
        """.trimIndent()

        val decoded = TrackJson.decodeFromString(extraKeysJson)
        assertEquals("extra_1", decoded.id)
    }

    @Test
    fun testListRoundTrip() {
        val list = listOf(
            sampleTrack,
            sampleTrack.copy(id = "track_456", host = AudioHost.WHYP, localUri = null),
        )
        val jsonString = TrackJson.listToJson(list)
        val decoded = TrackJson.listFromJson(jsonString)
        assertEquals(list, decoded)
    }

    @Test
    fun testListFromJsonEmptyOrMalformed() {
        assertTrue(TrackJson.listFromJson(null).isEmpty())
        assertTrue(TrackJson.listFromJson("").isEmpty())
        assertTrue(TrackJson.listFromJson("   ").isEmpty())
        assertTrue(TrackJson.listFromJson("{ not an array }").isEmpty())
    }
}
