package dev.dawngasm.app.player

import dev.dawngasm.app.model.Track

interface IosAudioPlayerObserver {
    fun onTrackChanged(track: Track?)
    fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long)
}

object IosAudioIntegration {
    var observer: IosAudioPlayerObserver? = null
    var activePlayer: AudioPlayer? = null
}
