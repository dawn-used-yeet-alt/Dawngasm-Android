package dev.dawngasm.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioLinksTest {
    @Test
    fun findsSoundgasmInEscapedSelftext() {
        val result = AudioLinks.find(
            postUrl = "https://reddit.com/r/gonewildaudio/comments/abc",
            selftext = """listen here https:\/\/soundgasm.net\/u\/someone\/quiet-rain""",
        )
        assertEquals(AudioHost.SOUNDGASM, result?.first)
        assertEquals("https://soundgasm.net/u/someone/quiet-rain", result?.second)
    }

    @Test
    fun prefersSoundgasmOverOtherHosts() {
        val result = AudioLinks.find(
            postUrl = "https://whyp.it/tracks/ab12",
            selftext = "https://soundgasm.net/u/a/b https://audiochan.com/a/xyz",
        )
        assertEquals(AudioHost.SOUNDGASM, result?.first)
    }

    @Test
    fun findsAudiochanEmbed() {
        val result = AudioLinks.find(
            postUrl = "",
            selftext = "https://audiochan.com/embed/a/slug_123",
        )
        assertEquals(AudioHost.AUDIOCHAN, result?.first)
        assertEquals("https://audiochan.com/embed/a/slug_123", result?.second)
    }

    @Test
    fun findsAudiochanShareLink() {
        val result = AudioLinks.find(
            postUrl = "",
            selftext = "Listen https://audiochan.com/s/slug_123",
        )
        assertEquals(AudioHost.AUDIOCHAN, result?.first)
        assertEquals(AudioHost.AUDIOCHAN, AudioLinks.hostOf("https://audiochan.com/s/x"))
    }

    @Test
    fun findsWhypTrack() {
        val result = AudioLinks.find(
            postUrl = "",
            selftext = "Audio: https://whyp.it/tracks/ab12-cd34?token=abc",
        )
        assertEquals(AudioHost.WHYP, result?.first)
        assertEquals("https://whyp.it/tracks/ab12-cd34?token=abc", result?.second)
    }

    @Test
    fun findsHotaudioTrack() {
        val result = AudioLinks.find(
            postUrl = "",
            selftext = "Listen: https://hotaudio.net/u/creator/sample-track?key=abc",
        )
        assertEquals(AudioHost.HOTAUDIO, result?.first)
        assertEquals("https://hotaudio.net/u/creator/sample-track?key=abc", result?.second)
    }

    @Test
    fun detectsHotaudioHost() {
        assertEquals(AudioHost.HOTAUDIO, AudioLinks.hostOf("https://hotaudio.net/u/user/slug"))
        assertEquals(AudioHost.HOTAUDIO, AudioLinks.hostOf("https://www.hotaudio.net/u/user/slug?key=x"))
    }

    @Test
    fun ignoresUnsupportedHosts() {
        assertNull(AudioLinks.find("https://example.com/file.mp3", ""))
    }

    @Test
    fun trimsTrailingPunctuation() {
        assertEquals(
            "https://soundgasm.net/u/a/b",
            AudioLinks.cleanUrl("https://soundgasm.net/u/a/b)."),
        )
    }

    @Test
    fun normalisesHtmlEntitiesAndSlashes() {
        val normalised = AudioLinks.normalize("https:&#x2F;&#x2F;soundgasm.net&#47;u&#47;a&#47;b")
        assertEquals("https://soundgasm.net/u/a/b", normalised)
    }

    @Test
    fun extractsDistinctLowercaseTags() {
        val tags = AudioLinks.tagsInTitle("[F4M] [Romantic] [F4M] A title [SFW]")
        assertEquals(listOf("f4m", "romantic", "sfw"), tags)
    }

    @Test
    fun tagsTrimWhitespaceAndIgnoreEmptyBrackets() {
        val tags = AudioLinks.tagsInTitle("[  Sleepy  ] [] [rain]")
        assertEquals(listOf("sleepy", "rain"), tags)
    }

    @Test
    fun hostDetectionIsCaseInsensitive() {
        assertEquals(AudioHost.SOUNDGASM, AudioLinks.hostOf("HTTPS://SOUNDGASM.NET/u/A/B"))
        assertEquals(AudioHost.WHYP, AudioLinks.hostOf("https://WHYP.IT/tracks/ab12"))
        assertEquals(AudioHost.AUDIOCHAN, AudioLinks.hostOf("https://AudioChan.com/a/abc"))
        assertEquals(AudioHost.UNKNOWN, AudioLinks.hostOf("https://example.com"))
        assertTrue(AudioLinks.hostOf("https://soundgasm.net/u/a/b") == AudioHost.SOUNDGASM)
    }
}
