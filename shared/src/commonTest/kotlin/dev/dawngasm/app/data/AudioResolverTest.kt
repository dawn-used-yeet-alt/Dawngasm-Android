package dev.dawngasm.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioResolverTest {
    @Test
    fun parsesSoundgasmStreamFromScript() {
        val html = """
            <script>
              var audio = {};
              audio.m4a = "https://media.soundgasm.net/sounds/abc123.m4a?token=1";
            </script>
        """.trimIndent()
        assertEquals(
            "https://media.soundgasm.net/sounds/abc123.m4a?token=1",
            CommonAudioResolver.parseSoundgasm(html),
        )
    }

    @Test
    fun fallsBackToDirectSoundgasmLink() {
        val html = "<audio src=\"https://media.soundgasm.net/sounds/abc.ogg\"></audio>"
        assertEquals(
            "https://media.soundgasm.net/sounds/abc.ogg",
            CommonAudioResolver.parseSoundgasm(html),
        )
    }

    @Test
    fun parsesWhypMp3AfterUnescaping() {
        val html = """{"url":"https:\/\/cdn.whyp.it\/ab12-cd34.mp3?token=xyz\u0026expires=99"}"""
        assertEquals(
            "https://cdn.whyp.it/ab12-cd34.mp3?token=xyz&expires=99",
            CommonAudioResolver.parseWhyp(html),
        )
    }

    @Test
    fun fallsBackToWhypFlac() {
        val html = "https://cdn.whyp.it/ab12-cd34.flac?token=xyz"
        assertEquals(
            "https://cdn.whyp.it/ab12-cd34.flac?token=xyz",
            CommonAudioResolver.parseWhyp(html),
        )
    }

    @Test
    fun parsesAudiochanSlug() {
        assertEquals(
            "slug_123",
            CommonAudioResolver.parseAudiochanSlug("https://audiochan.com/a/slug_123"),
        )
        assertEquals(
            "slug-456",
            CommonAudioResolver.parseAudiochanSlug("https://audiochan.com/embed/a/slug-456"),
        )
        assertEquals(
            "share_789",
            CommonAudioResolver.parseAudiochanSlug("https://audiochan.com/s/share_789"),
        )
        assertNull(CommonAudioResolver.parseAudiochanSlug("https://audiochan.com/"))
    }

    @Test
    fun readsAudiochanFileId() {
        val json = """{"audioFile":{"id":"file_123","url":null}}"""
        assertEquals("file_123", CommonAudioResolver.parseAudiochanFileId(json))
        val snakeCase = """{"audio_file":{"id":"file_456"}}"""
        assertEquals("file_456", CommonAudioResolver.parseAudiochanFileId(snakeCase))
        assertNull(CommonAudioResolver.parseAudiochanFileId("""{"audioFile":{"id":null}}"""))
        assertNull(CommonAudioResolver.parseAudiochanFileId("{}"))
    }

    @Test
    fun readsAudiochanFileUrlWhenPublic() {
        val json = """{"audioFile":{"id":"x","url":"https://cdn.audiochan.com/file.m4a"}}"""
        assertEquals("https://cdn.audiochan.com/file.m4a", CommonAudioResolver.parseAudiochanFileUrl(json))
        assertNull(CommonAudioResolver.parseAudiochanFileUrl("""{"audioFile":{"url":null}}"""))
        assertNull(CommonAudioResolver.parseAudiochanFileUrl("{}"))
    }

    @Test
    fun readsTopLevelAndNestedJsonFields() {
        assertEquals("abc", CommonAudioResolver.parseJsonField("""{"id":"abc"}""", "id"))
        assertEquals("abc", CommonAudioResolver.parseJsonField("""{"data":{"id":"abc"}}""", "id"))
        assertNull(CommonAudioResolver.parseJsonField("""{"id":null}""", "id"))
        assertNull(CommonAudioResolver.parseJsonField("not json", "id"))
    }

    @Test
    fun recognisesAudioExtensions() {
        assertEquals("mp3", CommonAudioResolver.extensionFromUrl("https://x/y.mp3?token=1", "m4a"))
        assertEquals("flac", CommonAudioResolver.extensionFromUrl("https://x/y.FLAC", "mp3"))
        assertEquals("m4a", CommonAudioResolver.extensionFromUrl("https://x/stream", "m4a"))
        assertEquals("mp3", CommonAudioResolver.extensionFromUrl("https://x/y.html", "mp3"))
    }
}
