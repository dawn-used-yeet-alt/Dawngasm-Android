package dev.dawngasm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.HeaderDataSourceFactory
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioStreamer
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.PlaybackQueue
import dev.dawngasm.app.data.TrackDownloader
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.model.TrackJson
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background [MediaSessionService] managing audio playback via [ExoPlayer],
 * system media notifications, lock-screen controls, media buttons, and queue navigation.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: ForwardingPlayer
    private var mediaSession: MediaSession? = null

    private val playbackQueue = PlaybackQueue()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playJob: Job? = null
    private val playGeneration = AtomicLong(0)

    private val headerFactory by lazy { HeaderDataSourceFactory(this, Network.client) }
    private val resolver by lazy { AudioResolver(this) }
    private val downloader by lazy { TrackDownloader(this) }

    override fun onCreate() {
        super.onCreate()
        HotaudioLog.i("PlaybackService.onCreate")

        ensureNotificationChannel()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        setMediaNotificationProvider(notificationProvider)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(headerFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 180_000,
                        /* bufferForPlaybackMs = */ 5_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 10_000,
                    )
                    .build(),
            )
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                setWakeMode(C.WAKE_MODE_NETWORK)
                repeatMode = Player.REPEAT_MODE_OFF
            }

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val endedTrackId = exoPlayer.currentMediaItem?.mediaId
                    val streamFailure = endedTrackId?.let { HotaudioStreamer.stateFor(it, null)?.error }
                    if (streamFailure != null) {
                        HotaudioLog.w(
                            "playback ended early track=$endedTrackId: ${streamFailure.message}",
                        )
                        exoPlayer.pause()
                    } else if (playbackQueue.hasNext) {
                        val next = playbackQueue.moveToNext()
                        if (next != null) {
                            playTrack(next)
                        }
                    }
                }
            }
        })

        forwardingPlayer = PlaybackForwardingPlayer(exoPlayer)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(PlaybackSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = exoPlayer
        if (!player.playWhenReady ||
            player.playbackState == Player.STATE_ENDED ||
            player.playbackState == Player.STATE_IDLE
        ) {
            HotaudioLog.i("PlaybackService.onTaskRemoved: stopping service")
            stopSelf()
        }
    }

    override fun onDestroy() {
        HotaudioLog.i("PlaybackService.onDestroy")
        serviceScope.cancel()
        playJob?.cancel()
        HotaudioStreamer.cancelAll()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun playTrack(track: Track, newQueue: List<Track>? = null) {
        HotaudioLog.i("PlaybackService.playTrack track=${track.id} title=${track.title}")
        val previous = playbackQueue.currentTrack
        if (newQueue != null) {
            playbackQueue.setQueue(newQueue, track)
        } else if (playbackQueue.currentTrack?.id != track.id) {
            playbackQueue.setQueue(playbackQueue.tracks, track)
        }

        val generation = playGeneration.incrementAndGet()
        playJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        if (previous != null && previous.id != track.id) {
            HotaudioStreamer.cancel(previous.id)
        }

        val localUri = track.localUri
        if (localUri != null && downloader.exists(localUri)) {
            HotaudioLog.i("PlaybackService: playing local track=${track.id} uri=$localUri")
            startPlayback(track, localUri.toUri(), emptyMap())
            return
        }

        playJob = serviceScope.launch {
            try {
                val audio = resolver.resolve(track)
                if (generation != playGeneration.get()) return@launch
                startPlayback(track, audio.url.toUri(), audio.headers)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != playGeneration.get()) return@launch
                HotaudioLog.e("PlaybackService: failed to resolve track=${track.id}", error)
                exoPlayer.pause()
                broadcastError(track, error.message ?: "Could not resolve audio URL")
            }
        }
    }

    private fun broadcastError(track: Track, message: String) {
        val args = Bundle().apply {
            putString(KEY_TRACK_JSON, TrackJson.toJson(track).toString())
            putString(KEY_ERROR_MESSAGE, message)
        }
        mediaSession?.broadcastCustomCommand(
            SessionCommand(COMMAND_ERROR, Bundle.EMPTY),
            args,
        )
    }

    private fun startPlayback(track: Track, uri: Uri, headers: Map<String, String>) {
        headerFactory.headers = headers + ("User-Agent" to Network.BROWSER_USER_AGENT)
        val metadata = buildMetadata(track)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
        if (uri.scheme.equals(HotaudioStreamer.SCHEME, ignoreCase = true)) {
            builder.setMimeType(MimeTypes.AUDIO_MP4)
        }
        exoPlayer.setMediaItem(builder.build())
        exoPlayer.prepare()
        exoPlayer.play()
    }

    private fun buildMetadata(track: Track): MediaMetadata {
        val extras = Bundle().apply {
            putString(KEY_TRACK_JSON, TrackJson.toJson(track))
        }
        return MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.displayAuthor)
            .setDisplayTitle(track.title)
            .setAlbumTitle(track.host.label)
            .setExtras(extras)
            .build()
    }

    private fun onSeekToNext() {
        if (playbackQueue.hasNext) {
            val next = playbackQueue.moveToNext()
            if (next != null) {
                playTrack(next)
            }
        } else {
            exoPlayer.pause()
        }
    }

    private fun onSeekToPrevious() {
        val (target, shouldSeekToZeroOnly) = playbackQueue.getPreviousTrack(exoPlayer.currentPosition)
        if (shouldSeekToZeroOnly || target == null || target.id == playbackQueue.currentTrack?.id) {
            exoPlayer.seekTo(0L)
        } else {
            playbackQueue.setQueue(playbackQueue.tracks, target)
            playTrack(target)
        }
    }

    private inner class PlaybackForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands().buildUpon()
            if (playbackQueue.hasNext) {
                commands.add(COMMAND_SEEK_TO_NEXT)
            } else {
                commands.remove(COMMAND_SEEK_TO_NEXT)
            }
            if (playbackQueue.hasPrevious) {
                commands.add(COMMAND_SEEK_TO_PREVIOUS)
            } else {
                commands.remove(COMMAND_SEEK_TO_PREVIOUS)
            }
            return commands.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                COMMAND_SEEK_TO_NEXT -> playbackQueue.hasNext
                COMMAND_SEEK_TO_PREVIOUS -> playbackQueue.hasPrevious
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasNextMediaItem(): Boolean = playbackQueue.hasNext

        override fun hasPreviousMediaItem(): Boolean = playbackQueue.hasPrevious

        override fun seekToNext() = onSeekToNext()
        override fun seekToNextMediaItem() = onSeekToNext()
        override fun seekToPrevious() = onSeekToPrevious()
        override fun seekToPreviousMediaItem() = onSeekToPrevious()
    }

    private inner class PlaybackSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val isAppOrTrusted = controller.isTrusted ||
                controller.packageName == packageName ||
                controller.packageName == "com.android.systemui"

            val sessionCommands = if (isAppOrTrusted) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(COMMAND_PLAY_TRACK, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_DISMISS, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_ERROR, Bundle.EMPTY))
                    .build()
            } else {
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            }

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_PLAY_TRACK -> {
                    val trackJson = args.getString(KEY_TRACK_JSON)
                    if (trackJson != null) {
                        val track = TrackJson.fromJson(trackJson)
                        val queueJson = args.getString(KEY_QUEUE_JSON)
                        val queue = TrackJson.listFromJson(queueJson).ifEmpty { listOf(track) }
                        playTrack(track, queue)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                COMMAND_DISMISS -> {
                    playJob?.cancel()
                    playbackQueue.clear()
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    stopSelf()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_description)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "dawngasm_playback"
        const val COMMAND_PLAY_TRACK = "dev.dawngasm.app.COMMAND_PLAY_TRACK"
        const val COMMAND_DISMISS = "dev.dawngasm.app.COMMAND_DISMISS"
        const val COMMAND_ERROR = "dev.dawngasm.app.COMMAND_ERROR"
        const val KEY_TRACK_JSON = "key_track_json"
        const val KEY_QUEUE_JSON = "key_queue_json"
        const val KEY_ERROR_MESSAGE = "key_error_message"
        private const val SEEK_STEP_MS = 15_000L
    }
}
