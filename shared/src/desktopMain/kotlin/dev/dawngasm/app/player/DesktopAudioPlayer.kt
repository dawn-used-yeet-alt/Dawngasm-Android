package dev.dawngasm.app.player

import dev.dawngasm.app.data.AudioUrlResolver
import dev.dawngasm.app.data.PlaybackQueue
import dev.dawngasm.app.data.ResolvedAudio
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.util.PlatformLog
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
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
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * VLCJ-backed desktop player. VLC is used because it decodes every format the
 * hosts serve (mp3, m4a/AAC, flac, ogg) and can play direct HTTP URLs with a
 * custom User-Agent/Referer. Packaging bundles the native libraries in Phase
 * 13–15; until then a system VLC installation is required.
 */
class DesktopAudioPlayer(
    private val resolver: AudioUrlResolver,
) : AudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val queue = PlaybackQueue()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var ignoreEnded: Boolean = false

    private var playJob: Job? = null
    private var progressJob: Job? = null

    private val listener = object : MediaPlayerEventAdapter() {
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            _state.update { it.copy(isLoading = true) }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(isPlaying = true, isLoading = false, error = null) }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(isPlaying = false) }
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            if (!ignoreEnded) {
                _state.update { it.copy(isPlaying = false, isLoading = false) }
            }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            if (ignoreEnded) return
            scope.launch { handleTrackFinished() }
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            _state.update { it.copy(positionMs = newTime.coerceAtLeast(0L)) }
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (newLength > 0L) {
                _state.update { it.copy(durationMs = newLength) }
            }
        }

        override fun error(mediaPlayer: MediaPlayer) {
            fail("Playback failed. Is VLC installed?")
        }
    }

    override fun play(track: Track, queue: List<Track>?) {
        val activeQueue = queue ?: listOf(track)
        this.queue.setQueue(activeQueue, track)
        playJob?.cancel()
        ignoreEnded = false

        _state.value = PlaybackState(
            currentTrack = track,
            isLoading = true,
            isPlaying = false,
        )
        startProgressLoop()

        playJob = scope.launch {
            try {
                val localUri = track.localUri?.takeIf { it.isNotBlank() }
                val resolved: ResolvedAudio? = if (localUri == null) resolver.resolve(track) else null
                val source = localUri ?: resolved?.url
                    ?: throw IllegalStateException("No audio URL for ${track.id}")
                PlatformLog.i("DesktopAudioPlayer", "play track=${track.id} source=$source")
                startVlc(source, resolved?.headers.orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PlatformLog.e("DesktopAudioPlayer", "Could not play ${track.id}", error)
                fail(error.message ?: "Could not resolve audio URL")
            }
        }
    }

    private fun startVlc(source: String, headers: Map<String, String>) {
        val mediaPlayer = ensurePlayer() ?: return
        val options = headerOptions(headers)
        val started = mediaPlayer.media().play(source, *options.toTypedArray())
        if (!started) {
            fail("Could not start playback")
            return
        }
        _state.update {
            it.copy(
                isLoading = false,
                isPlaying = true,
                error = null,
                durationMs = 0L,
                positionMs = 0L,
            )
        }
    }

    private fun headerOptions(headers: Map<String, String>): List<String> = buildList {
        headers["User-Agent"]?.takeIf { it.isNotBlank() }?.let { add(":http-user-agent=$it") }
        headers["Referer"]?.takeIf { it.isNotBlank() }?.let { add(":http-referrer=$it") }
        headers["Cookie"]?.takeIf { it.isNotBlank() }?.let { add(":http-cookie=$it") }
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        return try {
            val mediaFactory = factory ?: MediaPlayerFactory("--no-video", "--quiet").also { factory = it }
            val mediaPlayer = mediaFactory.mediaPlayers().newMediaPlayer()
            mediaPlayer.events().addMediaPlayerEventListener(listener)
            player = mediaPlayer
            mediaPlayer
        } catch (error: Throwable) {
            PlatformLog.e("DesktopAudioPlayer", "VLC/libvlc unavailable", error)
            fail(
                "VLC was not found. Install VLC (libvlc) to enable playback. " +
                    "Details: ${error.message ?: error::class.simpleName}",
            )
            null
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
        runCatching { player?.controls()?.setPause(true) }
        _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        val mediaPlayer = player
        val track = _state.value.currentTrack
        if (mediaPlayer == null || track == null) {
            if (track != null) play(track, queue.tracks)
            return
        }
        runCatching { mediaPlayer.controls().play() }
            .onFailure { fail(it.message ?: "Could not resume playback") }
        _state.update { it.copy(isPlaying = true, isLoading = false) }
    }

    override fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        runCatching {
            player?.controls()?.setTime(target)
            _state.update { it.copy(positionMs = target) }
        }
    }

    override fun nextTrack() {
        val next = queue.moveToNext()
        if (next != null) {
            play(next, queue.tracks)
        } else {
            pause()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    override fun previousTrack() {
        val (previous, seekToZeroOnly) = queue.getPreviousTrack(_state.value.positionMs)
        if (seekToZeroOnly) {
            seekTo(0L)
        } else if (previous != null) {
            play(previous, queue.tracks)
        }
    }

    private fun handleTrackFinished() {
        val next = queue.moveToNext()
        if (next != null) {
            play(next, queue.tracks)
        } else {
            _state.update { it.copy(isPlaying = false, isLoading = false, positionMs = 0L) }
        }
    }

    override fun stop() {
        playJob?.cancel()
        ignoreEnded = true
        runCatching { player?.controls()?.stop() }
        _state.value = PlaybackState()
    }

    override fun release() {
        stop()
        progressJob?.cancel()
        progressJob = null
        runCatching { player?.release() }
        player = null
        runCatching { factory?.release() }
        factory = null
    }

    private fun fail(message: String) {
        PlatformLog.e("DesktopAudioPlayer", message)
        _state.update {
            it.copy(
                isLoading = false,
                isPlaying = false,
                error = message,
            )
        }
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val mediaPlayer = player
                if (mediaPlayer != null && _state.value.currentTrack != null) {
                    val position = runCatching { mediaPlayer.status().time() }.getOrDefault(0L)
                    val duration = runCatching { mediaPlayer.status().length() }.getOrDefault(0L)
                    _state.update { current ->
                        current.copy(
                            positionMs = position.coerceAtLeast(0L),
                            durationMs = if (duration > 0L) duration else current.durationMs,
                        )
                    }
                }
                delay(500L)
            }
        }
    }
}
