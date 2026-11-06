package dev.dawngasm.app.state

/**
 * High-frequency playback ticker, kept out of [AppUiState] so a 500 ms
 * position update only invalidates the mini player instead of the whole app.
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)
