package dev.dawngasm.app.player

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeAudioPlayer : AudioPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun play(track: Track, queue: List<Track>?) {
        _state.update {
            it.copy(currentTrack = track, isPlaying = true, isLoading = false)
        }
    }

    override fun togglePlayPause() {
        _state.update { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun pause() {
        _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        _state.update { it.copy(isPlaying = true) }
    }

    override fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun nextTrack() {}
    override fun previousTrack() {}
    override fun stop() {
        _state.update { it.copy(isPlaying = false, currentTrack = null) }
    }
    override fun release() {}
}

class AudioPlayerTest {
    @Test
    fun testAudioPlayerContract() {
        val player = FakeAudioPlayer()
        val track = Track(
            id = "t1",
            title = "Test Track",
            author = "author",
            host = AudioHost.SOUNDGASM,
            pageUrl = "https://example.com",
            subreddit = "test",
            score = 10,
            createdUtc = 12345,
        )

        assertFalse(player.state.value.isPlaying)
        player.play(track)
        assertTrue(player.state.value.isPlaying)
        assertEquals("t1", player.state.value.currentTrack?.id)

        player.pause()
        assertFalse(player.state.value.isPlaying)

        player.seekTo(5000L)
        assertEquals(5000L, player.state.value.positionMs)
    }
}
