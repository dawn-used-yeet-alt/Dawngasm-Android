package dev.dawngasm.app.player

import dev.dawngasm.app.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Placeholder player used until Phase 4 wires the desktop playback engine. */
class NoOpAudioPlayer : AudioPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun play(track: Track, queue: List<Track>?) {
        _state.value = PlaybackState(currentTrack = track)
    }

    override fun togglePlayPause() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun nextTrack() = Unit
    override fun previousTrack() = Unit

    override fun stop() {
        _state.value = PlaybackState()
    }

    override fun release() = Unit
}
