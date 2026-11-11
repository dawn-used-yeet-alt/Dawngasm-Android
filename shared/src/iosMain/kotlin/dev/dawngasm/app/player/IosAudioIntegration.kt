package dev.dawngasm.app.player

interface IosAudioPlayerObserver {
    fun onTrackChanged(title: String?, author: String?)
    fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long)
}

object IosAudioIntegration {
    var observer: IosAudioPlayerObserver? = null
    var activePlayer: AudioPlayer? = null
}
