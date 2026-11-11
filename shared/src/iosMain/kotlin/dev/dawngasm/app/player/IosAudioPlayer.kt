package dev.dawngasm.app.player

import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.HotaudioStreamer
import dev.dawngasm.app.data.PlaybackQueue
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.PlatformLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
class IosAudioPlayer(
    private val resolver: AudioResolver = AudioResolver(),
) : AudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val queue = PlaybackQueue()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: AVPlayer? = null
    private var progressJob: Job? = null

    init {
        IosAudioIntegration.activePlayer = this
    }

    override fun play(track: Track, queue: List<Track>?) {
        val trackList = queue ?: listOf(track)
        this.queue.setQueue(trackList, track)
        _state.update {
            it.copy(
                currentTrack = track,
                isLoading = true,
                isPlaying = false,
                error = null,
                positionMs = 0L,
                durationMs = 0L,
            )
        }
        IosAudioIntegration.observer?.onTrackChanged(track.title, track.displayAuthor)
        IosAudioIntegration.observer?.onPlaybackStateChanged(
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
        )

        scope.launch {
            try {
                val resolved = resolver.resolve(track)
                val item = if (resolved.url.startsWith("hotaudio://", ignoreCase = true)) {
                    val trackId = HotaudioStreamer.trackIdFrom(resolved.url)
                    val streamState = trackId?.let { HotaudioStreamer.stateFor(it) }
                    val path = streamState?.spec?.filePath
                    if (path != null) {
                        AVPlayerItem(uRL = NSURL.fileURLWithPath(path))
                    } else {
                        val nsUrl = NSURL.URLWithString(resolved.url) ?: run {
                            _state.update { it.copy(isLoading = false, error = "Invalid URL") }
                            return@launch
                        }
                        AVPlayerItem(uRL = nsUrl)
                    }
                } else {
                    val nsUrl = NSURL.URLWithString(resolved.url) ?: run {
                        _state.update { it.copy(isLoading = false, error = "Invalid URL") }
                        return@launch
                    }
                    AVPlayerItem(uRL = nsUrl)
                }

                if (player == null) {
                    player = AVPlayer(playerItem = item)
                } else {
                    player?.replaceCurrentItemWithPlayerItem(item)
                }
                player?.play()
                _state.update {
                    it.copy(
                        currentTrack = track,
                        isLoading = false,
                        isPlaying = true,
                    )
                }
                IosAudioIntegration.observer?.onPlaybackStateChanged(
                    isPlaying = true,
                    positionMs = 0L,
                    durationMs = 0L,
                )
                startProgressLoop()
            } catch (e: Exception) {
                PlatformLog.e("IosAudioPlayer", "Error playing track: ${track.id}", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        error = e.message ?: "Failed to play audio",
                    )
                }
                IosAudioIntegration.observer?.onPlaybackStateChanged(
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
        }
    }

    override fun togglePlayPause() {
        if (_state.value.isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    override fun pause() {
        player?.pause()
        _state.update { it.copy(isPlaying = false) }
        IosAudioIntegration.observer?.onPlaybackStateChanged(
            isPlaying = false,
            positionMs = _state.value.positionMs,
            durationMs = _state.value.durationMs,
        )
    }

    override fun resume() {
        player?.play()
        _state.update { it.copy(isPlaying = true) }
        IosAudioIntegration.observer?.onPlaybackStateChanged(
            isPlaying = true,
            positionMs = _state.value.positionMs,
            durationMs = _state.value.durationMs,
        )
        startProgressLoop()
    }

    override fun seekTo(positionMs: Long) {
        val seconds = positionMs.toDouble() / 1000.0
        val time = CMTimeMakeWithSeconds(seconds, 1000)
        player?.seekToTime(time)
        _state.update { it.copy(positionMs = positionMs) }
        IosAudioIntegration.observer?.onPlaybackStateChanged(
            isPlaying = _state.value.isPlaying,
            positionMs = positionMs,
            durationMs = _state.value.durationMs,
        )
    }

    override fun nextTrack() {
        val next = queue.moveToNext() ?: return
        play(next, queue.tracks)
    }

    override fun previousTrack() {
        val (prev, seekToZero) = queue.getPreviousTrack(_state.value.positionMs)
        if (seekToZero) {
            seekTo(0L)
        } else if (prev != null) {
            play(prev, queue.tracks)
        }
    }

    override fun stop() {
        progressJob?.cancel()
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        _state.update {
            it.copy(
                isPlaying = false,
                isLoading = false,
                positionMs = 0L,
            )
        }
        IosAudioIntegration.observer?.onTrackChanged(null, null)
        IosAudioIntegration.observer?.onPlaybackStateChanged(
            isPlaying = false,
            positionMs = 0L,
            durationMs = _state.value.durationMs,
        )
    }

    override fun release() {
        stop()
        if (IosAudioIntegration.activePlayer === this) {
            IosAudioIntegration.activePlayer = null
        }
        player = null
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val p = player
                if (p != null && _state.value.isPlaying) {
                    val posSec = try {
                        val s = CMTimeGetSeconds(p.currentTime())
                        if (!s.isNaN() && !s.isInfinite() && s >= 0.0) s else 0.0
                    } catch (_: Throwable) { 0.0 }
                    val posMs = (posSec * 1000.0).toLong().coerceAtLeast(0L)

                    val durSec = try {
                        val item = p.currentItem
                        if (item != null) {
                            val s = CMTimeGetSeconds(item.duration)
                            if (!s.isNaN() && !s.isInfinite() && s >= 0.0) s else 0.0
                        } else 0.0
                    } catch (_: Throwable) { 0.0 }
                    val totalMs = (durSec * 1000.0).toLong().coerceAtLeast(0L)

                    val newDuration = if (totalMs > 0L) totalMs else _state.value.durationMs
                    _state.update {
                        it.copy(
                            positionMs = posMs,
                            durationMs = newDuration,
                        )
                    }
                    IosAudioIntegration.observer?.onPlaybackStateChanged(
                        isPlaying = true,
                        positionMs = posMs,
                        durationMs = newDuration,
                    )

                    // Auto-advance if track reached the end
                    if (totalMs > 0L && posMs >= totalMs) {
                        nextTrack()
                        break
                    }
                }
                delay(300)
            }
        }
    }
}
