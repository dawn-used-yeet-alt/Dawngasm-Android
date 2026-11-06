package dev.dawngasm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
            AudioResolver.parseSoundgasm(html),
        )
    }

    @Test
    fun fallsBackToDirectSoundgasmLink() {
        val html = "<audio src=\"https://media.soundgasm.net/sounds/abc.ogg\"></audio>"
        assertEquals(
            "https://media.soundgasm.net/sounds/abc.ogg",
            AudioResolver.parseSoundgasm(html),
        )
    }

    @Test
    fun parsesWhypMp3AfterUnescaping() {
        val html = """{"url":"https:\/\/cdn.whyp.it\/ab12-cd34.mp3?token=xyz\u0026expires=99"}"""
        assertEquals(
            "https://cdn.whyp.it/ab12-cd34.mp3?token=xyz&expires=99",
            AudioResolver.parseWhyp(html),
        )
    }

    @Test
    fun fallsBackToWhypFlac() {
        val html = "https://cdn.whyp.it/ab12-cd34.flac?token=xyz"
        assertEquals(
            "https://cdn.whyp.it/ab12-cd34.flac?token=xyz",
            AudioResolver.parseWhyp(html),
        )
    }

    @Test
    fun parsesAudiochanSlug() {
        assertEquals(
            "slug_123",
            AudioResolver.parseAudiochanSlug("https://audiochan.com/a/slug_123"),
        )
        assertEquals(
            "slug-456",
            AudioResolver.parseAudiochanSlug("https://audiochan.com/embed/a/slug-456"),
        )
        assertEquals(
            "share_789",
            AudioResolver.parseAudiochanSlug("https://audiochan.com/s/share_789"),
        )
        assertNull(AudioResolver.parseAudiochanSlug("https://audiochan.com/"))
    }

    @Test
    fun readsAudiochanFileUrlWhenPublic() {
        val json = """{"audioFile":{"id":"x","url":"https://cdn.audiochan.com/file.m4a"}}"""
        assertEquals("https://cdn.audiochan.com/file.m4a", AudioResolver.parseAudiochanFileUrl(json))
        assertNull(AudioResolver.parseAudiochanFileUrl("""{"audioFile":{"url":null}}"""))
        assertNull(AudioResolver.parseAudiochanFileUrl("{}"))
    }

    @Test
    fun readsTopLevelAndNestedJsonFields() {
        assertEquals("abc", AudioResolver.parseJsonField("""{"id":"abc"}""", "id"))
        assertEquals("abc", AudioResolver.parseJsonField("""{"data":{"id":"abc"}}""", "id"))
        assertNull(AudioResolver.parseJsonField("""{"id":null}""", "id"))
        assertNull(AudioResolver.parseJsonField("not json", "id"))
    }

    @Test
    fun recognisesAudioExtensions() {
        assertEquals("mp3", AudioResolver.extensionFromUrl("https://x/y.mp3?token=1", "m4a"))
        assertEquals("flac", AudioResolver.extensionFromUrl("https://x/y.FLAC", "mp3"))
        assertEquals("m4a", AudioResolver.extensionFromUrl("https://x/stream", "m4a"))
        assertEquals("mp3", AudioResolver.extensionFromUrl("https://x/y.html", "mp3"))
    }
}
