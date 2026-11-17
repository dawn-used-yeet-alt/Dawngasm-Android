package dev.dawngasm.app.player

import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerEvent
import dev.brahmkshatriya.kmplayer.KMResult
import dev.brahmkshatriya.kmedianotification.KMediaNotification
import dev.brahmkshatriya.kmedianotification.MediaPlatform
import dev.brahmkshatriya.kmedianotification.MediaSession
import dev.brahmkshatriya.kmedianotification.MediaSessionState
import dev.brahmkshatriya.kmedianotification.NowPlayingItem
import dev.brahmkshatriya.kmedianotification.PlaybackState as NotificationPlaybackState
import dev.brahmkshatriya.kmedianotification.desktop
import dev.dawngasm.app.data.AudioUrlResolver
import dev.dawngasm.app.data.CommonAudioResolver
import dev.dawngasm.app.data.HotaudioStreamer
import dev.dawngasm.app.data.PlaybackQueue
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.PlatformLog
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NativeAudioPlayer(
    private val resolver: AudioUrlResolver,
) : AudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val queue = PlaybackQueue()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: KMPlayer? = null
    private var session: MediaSession? = null
    private var playJob: Job? = null

    init {
        initPlayer()
        initNotifications()
    }

    private fun initPlayer() {
        try {
            when (val res = KMPlayer.create(KMPlayerConfig(requireHls = false, audioOnly = true))) {
                is KMResult.Success -> {
                    player = res.value
                    scope.launch {
                        res.value.state.collect { kmState ->
                            _state.update { current ->
                                current.copy(
                                    isPlaying = kmState.isPlaying,
                                    isLoading = kmState.isBuffering,
                                    positionMs = kmState.position.inWholeMilliseconds,
                                    durationMs = kmState.duration?.inWholeMilliseconds ?: current.durationMs,
                                    error = kmState.error?.message ?: current.error,
                                )
                            }
                            updateNotification()
                        }
                    }
                    scope.launch {
                        runCatching {
                            res.value.events.collect { event ->
                                when (event) {
                                    is KMPlayerEvent.MediaEnded -> nextTrack()
                                    is KMPlayerEvent.PlaybackError -> {
                                        PlatformLog.e("NativeAudioPlayer", "Playback error: ${event.error.message}")
                                        val currentId = _state.value.currentTrack?.id
                                        if (currentId != null) {
                                            (resolver as? CommonAudioResolver)?.evictCache(currentId)
                                        }
                                        _state.update {
                                            it.copy(
                                                isLoading = false,
                                                isPlaying = false,
                                                error = event.error.message,
                                            )
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
                is KMResult.Failure -> {
                    PlatformLog.w("NativeAudioPlayer", "KMPlayer init failed: ${res.error.message}")
                }
            }
        } catch (e: Throwable) {
            PlatformLog.w("NativeAudioPlayer", "KMPlayer init exception: ${e.message}")
        }
    }

    private fun initNotifications() {
        try {
            val platform = MediaPlatform.desktop {
                linuxBusName = "org.mpris.MediaPlayer2.dawngasm"
                linuxDesktopEntry = "dev.dawngasm"
            }
            session = KMediaNotification.create(
                platform = platform,
                scope = scope,
                id = "dawngasm",
                displayName = "Dawngasm",
            ) {
                onPlay { resume() }
                onPause { pause() }
                onTogglePlayPause { togglePlayPause() }
                onNext { nextTrack() }
                onPrevious { previousTrack() }
                onSeekTo { req -> seekTo(req.position.inWholeMilliseconds) }
                onStop { stop() }
            }
        } catch (e: Throwable) {
            PlatformLog.w("NativeAudioPlayer", "KMediaNotification init exception: ${e.message}")
        }
    }

    private fun updateNotification() {
        val currentTrack = _state.value.currentTrack ?: return
        val isPlaying = _state.value.isPlaying
        val pos = _state.value.positionMs.milliseconds
        val dur = _state.value.durationMs.milliseconds

        try {
            session?.activate()
            session?.publish(
                MediaSessionState(
                    item = NowPlayingItem(
                        mediaId = currentTrack.id,
                        entryId = currentTrack.id,
                        title = currentTrack.title,
                        artists = listOf(currentTrack.author),
                    ),
                    playback = if (isPlaying) {
                        NotificationPlaybackState.playing(position = pos, duration = dur)
                    } else {
                        NotificationPlaybackState.paused(position = pos, duration = dur)
                    },
                )
            )
        } catch (e: Throwable) {
            PlatformLog.w("NativeAudioPlayer", "updateNotification exception: ${e.message}")
        }
    }

    override fun play(track: Track, queue: List<Track>?) {
        val activeQueue = queue ?: listOf(track)
        val previous = _state.value.currentTrack
        this.queue.setQueue(activeQueue, track)

        if (previous != null && previous.id != track.id) {
            HotaudioStreamer.cancel(previous.id)
        }

        playJob?.cancel()
        _state.update {
            it.copy(
                currentTrack = track,
                isPlaying = false,
                isLoading = true,
                positionMs = 0L,
                durationMs = 0L,
                error = null,
            )
        }

        playJob = scope.launch {
            try {
                PlatformLog.i("NativeAudioPlayer", "Resolving track ${track.id}")
                val resolved = resolver.resolve(track)
                val p = player
                if (p == null) {
                    PlatformLog.w("NativeAudioPlayer", "No player backend available")
                    _state.update { it.copy(isLoading = false, isPlaying = true) }
                    return@launch
                }

                val mediaUri: String = when {
                    !track.localUri.isNullOrBlank() -> track.localUri
                    resolved.url.startsWith("${HotaudioStreamer.SCHEME}:", ignoreCase = true) -> {
                        val trackId = HotaudioStreamer.trackIdFrom(resolved.url) ?: track.id
                        val spec = HotaudioStreamer.spec(trackId, track.pageUrl)
                        val completedPath = HotaudioStreamer.awaitComplete(spec)
                        "file://$completedPath"
                    }
                    track.host == AudioHost.AUDIOCHAN || resolved.headers.any { it.key.equals("Cookie", ignoreCase = true) } -> {
                        AudioStreamProxy.streamUrl(track.id, resolved)
                    }
                    else -> resolved.url
                }

                PlatformLog.i("NativeAudioPlayer", "Loading media uri=$mediaUri for track ${track.id}")
                p.load(mediaUri, playWhenReady = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                PlatformLog.e("NativeAudioPlayer", "Playback failed for track ${track.id}", e)
                (resolver as? CommonAudioResolver)?.evictCache(track.id)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        error = e.message ?: "Failed to play audio",
                    )
                }
            }
        }
    }

    override fun togglePlayPause() {
        val p = player
        if (p != null) {
            scope.launch {
                if (_state.value.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
        } else {
            _state.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    override fun pause() {
        val p = player
        if (p != null) {
            scope.launch { p.pause() }
        } else {
            _state.update { it.copy(isPlaying = false) }
        }
    }

    override fun resume() {
        val p = player
        if (p != null) {
            scope.launch { p.play() }
        } else {
            _state.update { it.copy(isPlaying = true) }
        }
    }

    override fun seekTo(positionMs: Long) {
        val p = player
        if (p != null) {
            scope.launch { p.seekTo(positionMs.milliseconds) }
        }
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun nextTrack() {
        val next = queue.getNextTrack() ?: return
        play(next, queue.tracks)
    }

    override fun previousTrack() {
        val (prev, seekOnly) = queue.getPreviousTrack(_state.value.positionMs)
        if (seekOnly || prev == null) {
            seekTo(0L)
        } else {
            play(prev, queue.tracks)
        }
    }

    override fun stop() {
        playJob?.cancel()
        val p = player
        if (p != null) {
            scope.launch { p.stop() }
        }
        _state.update {
            it.copy(isPlaying = false, isLoading = false, positionMs = 0L)
        }
    }

    override fun release() {
        stop()
        AudioStreamProxy.stop()
        player?.close()
        player = null
        session?.close()
        session = null
    }
}
