package dev.dawngasm.app.player

import dev.dawngasm.app.model.Track
import kotlinx.coroutines.flow.StateFlow

data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

interface AudioPlayer {
    val state: StateFlow<PlaybackState>
    fun play(track: Track, queue: List<Track>? = null)
    fun togglePlayPause()
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun nextTrack()
    fun previousTrack()
    fun stop()
    fun release()
}
