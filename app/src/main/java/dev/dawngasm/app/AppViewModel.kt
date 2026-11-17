package dev.dawngasm.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.HeaderDataSourceFactory
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioRuntime
import dev.dawngasm.app.data.HotaudioStreamer
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.PlaybackQueue
import dev.dawngasm.app.data.AndroidTrackDownloader
import dev.dawngasm.app.model.AuthorSuggestion
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.model.TrackJson
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.state.AppUiState
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.PlayerUiState
import dev.dawngasm.app.state.UiMessage
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FeedSnapshot(
    val tracks: List<Track>,
    val query: String,
    val includeTags: List<String>,
    val excludeTags: List<String>,
    val sort: String,
    val window: String,
    val hosts: List<String>,
    val excludeHosts: List<String>,
    val authors: List<String> = emptyList(),
    val feedPage: Int,
    val hasMore: Boolean,
    val similarSourceTrack: Track? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
)

@OptIn(UnstableApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application), DawngasmActions {
    private val prefs = AppPreferences(application)
    private val api = DawngasmApi()
    private val resolver = AudioResolver(application)
    private val downloader = AndroidTrackDownloader(application)

    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == PlaybackService.COMMAND_ERROR) {
                if (_uiState.value.mediaSessionServiceEnabled) {
                    val message = args.getString(PlaybackService.KEY_ERROR_MESSAGE) ?: "Playback failed"
                    updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
                    emitMessage("Could not play this track: $message", isError = true)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(controller, command, args)
        }
    }

    private val sessionToken = SessionToken(
        application,
        ComponentName(application, PlaybackService::class.java),
    )
    private var controllerFuture: ListenableFuture<MediaController>? = null

    @Volatile
    private var mediaController: MediaController? = null

    private val headerFactory by lazy { HeaderDataSourceFactory(application, Network.client) }
    private var localPlayer: ExoPlayer? = null
    private val localQueue = PlaybackQueue()
    private var localPlayJob: Job? = null
    private val localPlayGeneration = AtomicLong(0)

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var feedJob: Job? = null
    private var feedPage = 0
    private val feedGeneration = AtomicLong(0)
    private val feedStack = ArrayDeque<FeedSnapshot>()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_uiState.value.mediaSessionServiceEnabled) {
                HotaudioLog.i("controller isPlaying=$isPlaying")
                updatePlayer { copy(isPlaying = isPlaying) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!_uiState.value.mediaSessionServiceEnabled) return
            when (playbackState) {
                Player.STATE_BUFFERING -> updatePlayer { copy(isLoading = true) }
                Player.STATE_READY -> updatePlayer { copy(isLoading = false) }
                Player.STATE_ENDED -> {
                    _progress.value = PlaybackProgress()
                    updatePlayer { copy(isPlaying = false, isLoading = false) }
                }
                else -> Unit
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!_uiState.value.mediaSessionServiceEnabled) return
            val track = mediaItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_JSON)
                ?.let { runCatching { TrackJson.fromJson(it) }.getOrNull() }
            if (track != null) {
                _progress.value = PlaybackProgress()
                updatePlayer {
                    copy(
                        currentTrack = track,
                        isLoading = mediaController?.playbackState == Player.STATE_BUFFERING,
                        isPlaying = mediaController?.isPlaying == true,
                        error = null,
                    )
                }
                recordHistory(track)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!_uiState.value.mediaSessionServiceEnabled) return
            val message = error.message ?: error.errorCodeName
            HotaudioLog.e("controller player error code=${error.errorCode} message=$message", error)
            updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
            emitMessage("Playback failed: $message", isError = true, throwable = error)
        }
    }

    private fun ensureMediaController(onReady: ((MediaController) -> Unit)? = null) {
        val existing = mediaController
        if (existing != null) {
            onReady?.invoke(existing)
            return
        }
        val existingFuture = controllerFuture
        if (existingFuture != null) {
            if (onReady != null) {
                existingFuture.addListener({
                    try {
                        existingFuture.get()?.let(onReady)
                    } catch (e: Exception) {
                        HotaudioLog.e("ensureMediaController: existing future failed", e)
                    }
                }, ContextCompat.getMainExecutor(getApplication()))
            }
            return
        }
        val future = MediaController.Builder(getApplication(), sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                controller.addListener(playerListener)
                val currentItem = controller.currentMediaItem
                val track = currentItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_JSON)
                    ?.let { runCatching { TrackJson.fromJson(it) }.getOrNull() }
                if (track != null && _uiState.value.mediaSessionServiceEnabled) {
                    updatePlayer {
                        copy(
                            currentTrack = track,
                            isPlaying = controller.isPlaying,
                            isLoading = controller.playbackState == Player.STATE_BUFFERING,
                        )
                    }
                }
                onReady?.invoke(controller)
            } catch (e: Exception) {
                HotaudioLog.e("AppViewModel: failed to connect MediaController", e)
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    init {
        if (prefs.mediaSessionServiceEnabled()) {
            ensureMediaController()
        }

        loadFeed(reset = true)
        startProgressLoop()
        HotaudioLog.i("AppViewModel.init: ensuring hotaudio runtime")
        HotaudioRuntime.initialize(application)
        HotaudioLog.i(
            "AppViewModel.init: hotaudio tlsReady=${HotaudioRuntime.isTlsReady()} " +
                "tlsError=${HotaudioRuntime.tlsError?.message}",
        )
    }

    private fun initialState(): AppUiState {
        val blocked = prefs.blockedTags()
        val tree = prefs.downloadTreeUri()
        return AppUiState(
            isLoading = true,
            saved = prefs.saved(),
            history = prefs.history(),
            downloads = prefs.downloads(),
            query = "",
            sort = prefs.sort(),
            window = prefs.window(),
            blockedTags = blocked,
            downloadTreeUri = tree,
            downloadFolderLabel = downloader.folderLabel(tree),
            hotaudioConnected = prefs.hotaudioClearance() != null,
            audiochanConnected = prefs.audiochanClearance() != null,
            mediaSessionServiceEnabled = prefs.mediaSessionServiceEnabled(),
        )
    }

    // --- feed ---------------------------------------------------------------

    override fun refresh() {
        val state = _uiState.value
        val source = state.similarSourceTrack
        if (source != null) {
            loadSimilar(source, reset = true, refreshing = true)
        } else {
            loadFeed(reset = true, refreshing = true)
        }
    }

    override fun loadMore() {
        val state = _uiState.value
        if (state.similarSourceTrack != null || state.isLoading || state.isLoadingMore || !state.hasMore) return
        loadFeed(reset = false)
    }

    override fun applySearch(request: SearchRequest) {
        // Keep inline top:/sort:/host: support for anyone who still types it, while
        // the sheet's dropdowns provide the visible controls.
        val parsed = SearchSyntax.parse(request.text)
        val combinedHosts = (request.hosts + parsed.hosts).distinct()
        val combinedExcludeHosts = (request.excludeHosts + parsed.excludeHosts).distinct()
        val combinedAuthors = (request.authors + parsed.authors).distinct()
        feedStack.clear()
        _uiState.update {
            it.copy(
                query = parsed.text,
                includeTags = request.includeTags.mapNotNull(SearchSyntax::normalizeTag).distinct(),
                excludeTags = request.excludeTags.mapNotNull(SearchSyntax::normalizeTag).distinct(),
                sort = parsed.sort ?: request.sort,
                window = parsed.window ?: request.window,
                hosts = combinedHosts,
                excludeHosts = combinedExcludeHosts,
                authors = combinedAuthors,
                similarSourceTrack = null,
                canGoBack = false,
            )
        }
        persistFeed()
        loadFeed(reset = true)
    }

    override fun clearSearch() {
        if (_uiState.value.similarSourceTrack != null) {
            navigateBack()
            return
        }
        feedStack.clear()
        _uiState.update {
            it.copy(
                query = "",
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SearchSyntax.DEFAULT_SORT,
                window = SearchSyntax.DEFAULT_WINDOW,
                hosts = emptyList(),
                excludeHosts = emptyList(),
                authors = emptyList(),
                similarSourceTrack = null,
                canGoBack = false,
            )
        }
        persistFeed()
        loadFeed(reset = true)
    }

    override fun showSimilar(track: Track, scrollIndex: Int, scrollOffset: Int) {
        val state = _uiState.value
        feedStack.addLast(
            FeedSnapshot(
                tracks = state.tracks,
                query = state.query,
                includeTags = state.includeTags,
                excludeTags = state.excludeTags,
                sort = state.sort,
                window = state.window,
                hosts = state.hosts,
                excludeHosts = state.excludeHosts,
                authors = state.authors,
                feedPage = feedPage,
                hasMore = state.hasMore,
                similarSourceTrack = state.similarSourceTrack,
                scrollIndex = scrollIndex,
                scrollOffset = scrollOffset,
            ),
        )
        loadSimilar(track, reset = true)
    }

    private fun loadSimilar(track: Track, reset: Boolean = true, refreshing: Boolean = false) {
        val generation = feedGeneration.incrementAndGet()
        feedJob?.cancel()

        _uiState.update {
            it.copy(
                tracks = if (reset && !refreshing) emptyList() else it.tracks,
                isLoading = reset && !refreshing,
                isRefreshing = refreshing,
                isLoadingMore = false,
                error = null,
                similarSourceTrack = track,
                canGoBack = feedStack.isNotEmpty(),
                pendingScrollRestore = if (reset) 0 to 0 else null,
            )
        }

        feedJob = viewModelScope.launch {
            try {
                val similarTracks = api.similar(track.id)
                if (generation != feedGeneration.get()) return@launch
                _uiState.update {
                    it.copy(
                        tracks = similarTracks,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        hasMore = false,
                        error = if (similarTracks.isEmpty()) "No similar posts found" else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != feedGeneration.get()) return@launch
                val message = error.message ?: "Could not find similar posts"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = message,
                    )
                }
                emitMessage(message, isError = true, throwable = error)
            }
        }
    }

    override fun navigateBack() {
        if (feedStack.isEmpty()) return
        val previous = feedStack.removeLast()
        feedJob?.cancel()
        feedGeneration.incrementAndGet()
        feedPage = previous.feedPage

        _uiState.update {
            it.copy(
                tracks = previous.tracks,
                query = previous.query,
                includeTags = previous.includeTags,
                excludeTags = previous.excludeTags,
                sort = previous.sort,
                window = previous.window,
                hosts = previous.hosts,
                excludeHosts = previous.excludeHosts,
                authors = previous.authors,
                hasMore = previous.hasMore,
                similarSourceTrack = previous.similarSourceTrack,
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                error = null,
                canGoBack = feedStack.isNotEmpty(),
                pendingScrollRestore = previous.scrollIndex to previous.scrollOffset,
            )
        }
    }

    override fun onScrollRestored() {
        _uiState.update { it.copy(pendingScrollRestore = null) }
    }

    /** Tag autocomplete for the search sheet. Failures simply show no rows. */
    override suspend fun suggestTags(prefix: String): List<TagSuggestion> =
        runCatching { api.tagSuggestions(prefix) }.getOrDefault(emptyList())

    /** Author autocomplete for the search sheet. Failures simply show no rows. */
    override suspend fun suggestAuthors(prefix: String): List<AuthorSuggestion> =
        runCatching { api.authorSuggestions(prefix) }.getOrDefault(emptyList())

    override fun addBlockedTag(raw: String) {
        val tag = raw.trim().lowercase()
        if (tag.isBlank()) return
        val next = (_uiState.value.blockedTags + tag).distinct()
        _uiState.update { it.copy(blockedTags = next) }
        prefs.saveBlockedTags(next)
        loadFeed(reset = true)
    }

    override fun removeBlockedTag(tag: String) {
        val next = _uiState.value.blockedTags.filterNot { it == tag }
        _uiState.update { it.copy(blockedTags = next) }
        prefs.saveBlockedTags(next)
        loadFeed(reset = true)
    }

    private fun persistFeed() {
        val state = _uiState.value
        prefs.saveFeed(state.sort, state.window)
    }

    private fun loadFeed(reset: Boolean, refreshing: Boolean = false) {
        val state = _uiState.value
        val generation = feedGeneration.incrementAndGet()
        feedJob?.cancel()

        val sort = state.sort
        val window = state.window
        val text = state.query
        val page = if (reset) 0 else feedPage + 1

        _uiState.update {
            it.copy(
                isLoading = reset && it.tracks.isEmpty(),
                isRefreshing = refreshing && reset && it.tracks.isNotEmpty(),
                isLoadingMore = !reset,
                error = null,
                similarSourceTrack = if (reset) null else it.similarSourceTrack,
                canGoBack = feedStack.isNotEmpty(),
            )
        }

        feedJob = viewModelScope.launch {
            try {
                val result = api.search(
                    sort = sort,
                    window = window,
                    query = text,
                    includeTags = state.includeTags,
                    excludeTags = (state.blockedTags + state.excludeTags).distinct(),
                    page = page,
                    hosts = state.hosts,
                    excludeHosts = state.excludeHosts,
                    authors = state.authors,
                )
                if (generation != feedGeneration.get()) return@launch
                feedPage = result.page
                _uiState.update { current ->
                    val merged = if (reset) {
                        result.tracks
                    } else {
                        val seen = current.tracks.mapTo(HashSet()) { it.id }
                        current.tracks + result.tracks.filter { seen.add(it.id) }
                    }
                    current.copy(
                        tracks = merged,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        hasMore = result.hasMore,
                        error = null,
                        sort = sort,
                        window = window,
                    )
                }
                persistFeed()
                prefs.saveFeed(sort, window)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != feedGeneration.get()) return@launch
                val message = error.message ?: "Could not reach the index"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = message,
                    )
                }
                emitMessage(message, isError = true, throwable = error)
            }
        }
    }

    // --- library ------------------------------------------------------------

    override fun toggleSave(track: Track) {
        val current = _uiState.value.saved
        val next = if (current.any { it.id == track.id }) {
            current.filterNot { it.id == track.id }
        } else {
            listOf(track) + current
        }
        prefs.saveSaved(next)
        _uiState.update { it.copy(saved = next) }
    }

    override fun clearDownloads() {
        prefs.saveDownloads(emptyList())
        _uiState.update { it.copy(downloads = emptyList()) }
    }

    private fun recordHistory(track: Track) {
        val next = (listOf(track) + _uiState.value.history.filterNot { it.id == track.id })
            .take(MAX_HISTORY)
        prefs.saveHistory(next)
        _uiState.update { it.copy(history = next) }
    }

    // --- downloads ----------------------------------------------------------

    override fun download(track: Track) {
        val state = _uiState.value
        if (track.id in state.downloadingIds) return
        val existing = state.downloads.firstOrNull { it.id == track.id }
        if (existing != null && downloader.exists(existing.localUri)) {
            emitMessage("Already downloaded")
            return
        }

        _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.id) }
        viewModelScope.launch {
            try {
                val audio = resolver.resolve(track)
                val saved = downloader.download(track, audio, _uiState.value.downloadTreeUri)
                val local = track.copy(localUri = saved.uri.toString())
                val next = listOf(local) + _uiState.value.downloads.filterNot { it.id == track.id }
                prefs.saveDownloads(next)
                _uiState.update { it.copy(downloads = next) }
                emitMessage("Downloaded “${track.title}”")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emitMessage("Download failed: ${error.message ?: "unknown error"}", isError = true, throwable = error)
            } finally {
                _uiState.update { it.copy(downloadingIds = it.downloadingIds - track.id) }
            }
        }
    }

    override fun setDownloadTree(uri: String?) {
        prefs.saveDownloadTreeUri(uri)
        _uiState.update {
            it.copy(
                downloadTreeUri = uri,
                downloadFolderLabel = downloader.folderLabel(uri),
            )
        }
    }

    /** Refresh the hotaudio clearance status after the WebView activity. */
    override fun refreshHotaudioClearance() {
        HotaudioLog.i("refreshHotaudioClearance")
        val clearance = prefs.hotaudioClearance()
        if (clearance != null) {
            HotaudioRuntime.applyClearance(prefs)
        } else {
            try {
                net.hotaudio.extractor.clearClearance()
                HotaudioLog.i("refreshHotaudioClearance: cleared native clearance")
            } catch (error: Throwable) {
                HotaudioLog.e("refreshHotaudioClearance: clearClearance failed", error)
            }
        }
        _uiState.update { it.copy(hotaudioConnected = clearance != null) }
    }

    override fun clearHotaudioClearance() {
        HotaudioLog.i("clearHotaudioClearance")
        prefs.clearHotaudioClearance()
        try {
            net.hotaudio.extractor.clearClearance()
            net.hotaudio.extractor.setIpFamily("any")
            HotaudioLog.i("clearHotaudioClearance: native state reset")
        } catch (error: Throwable) {
            HotaudioLog.e("clearHotaudioClearance: native reset failed", error)
        }
        _uiState.update { it.copy(hotaudioConnected = false) }
    }

    /** Refresh the audiochan clearance status after the WebView activity. */
    override fun refreshAudiochanClearance() {
        HotaudioLog.i("refreshAudiochanClearance")
        val clearance = prefs.audiochanClearance()
        if (clearance != null) {
            Network.syncCookie("https://audiochan.com/", clearance.first)
            Network.syncCookie("https://api.audiochan.com/", clearance.first)
        }
        _uiState.update { it.copy(audiochanConnected = clearance != null) }
    }

    override fun clearAudiochanClearance() {
        HotaudioLog.i("clearAudiochanClearance")
        prefs.clearAudiochanClearance()
        _uiState.update { it.copy(audiochanConnected = false) }
    }

    // --- playback -----------------------------------------------------------

    override fun toggleMediaSessionService(enabled: Boolean) {
        if (_uiState.value.mediaSessionServiceEnabled == enabled) return
        prefs.saveMediaSessionServiceEnabled(enabled)
        _uiState.update { it.copy(mediaSessionServiceEnabled = enabled) }

        if (enabled) {
            val isPlaying = localPlayer?.isPlaying == true
            val track = _uiState.value.player.currentTrack
            val pos = localPlayer?.currentPosition ?: _progress.value.positionMs
            val queue = localQueue.tracks

            stopLocalPlayback()

            ensureMediaController {
                if (isPlaying && track != null) {
                    playViaService(track, queue.ifEmpty { listOf(track) }, pos)
                }
            }
        } else {
            val isPlaying = mediaController?.isPlaying == true
            val track = _uiState.value.player.currentTrack
            val pos = mediaController?.currentPosition ?: _progress.value.positionMs
            val queue = _uiState.value.tracks

            stopServicePlayback()

            if (isPlaying && track != null) {
                playViaLocal(track, queue.ifEmpty { listOf(track) }, pos)
            }
        }
    }

    override fun play(track: Track, queue: List<Track>?) {
        val activeQueue = queue ?: _uiState.value.tracks
        if (_uiState.value.mediaSessionServiceEnabled) {
            playViaService(track, activeQueue)
        } else {
            playViaLocal(track, activeQueue)
        }
    }

    private fun playViaService(track: Track, queue: List<Track>, seekToMs: Long = 0L) {
        HotaudioLog.i("AppViewModel.playViaService track=${track.id} title=${track.title}")
        val args = Bundle().apply {
            putString(PlaybackService.KEY_TRACK_JSON, TrackJson.toJson(track))
            putString(PlaybackService.KEY_QUEUE_JSON, TrackJson.listToJson(queue))
        }
        val command = SessionCommand(PlaybackService.COMMAND_PLAY_TRACK, Bundle.EMPTY)

        ensureMediaController { controller ->
            controller.sendCustomCommand(command, args)
            if (seekToMs > 0L) {
                controller.seekTo(seekToMs)
            }
        }

        _progress.value = PlaybackProgress(positionMs = seekToMs)
        _uiState.update {
            it.copy(
                player = PlayerUiState(
                    currentTrack = track,
                    isLoading = true,
                    isPlaying = false,
                ),
            )
        }
        recordHistory(track)
    }

    private fun getOrCreateLocalPlayer(): ExoPlayer {
        localPlayer?.let { return it }
        val player = ExoPlayer.Builder(getApplication())
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
            .setSeekForwardIncrementMs(15_000L)
            .setSeekBackIncrementMs(15_000L)
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

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!_uiState.value.mediaSessionServiceEnabled) {
                    updatePlayer { copy(isPlaying = isPlaying) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_uiState.value.mediaSessionServiceEnabled) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> updatePlayer { copy(isLoading = true) }
                    Player.STATE_READY -> updatePlayer { copy(isLoading = false) }
                    Player.STATE_ENDED -> {
                        _progress.value = PlaybackProgress()
                        updatePlayer { copy(isPlaying = false, isLoading = false) }
                        val endedTrackId = player.currentMediaItem?.mediaId
                        val streamFailure = endedTrackId?.let { HotaudioStreamer.stateFor(it, null)?.error }
                        if (streamFailure != null) {
                            HotaudioLog.w("local playback ended early track=$endedTrackId: ${streamFailure.message}")
                            player.pause()
                        } else if (localQueue.hasNext) {
                            val next = localQueue.moveToNext()
                            if (next != null) {
                                playViaLocal(next, localQueue.tracks)
                            }
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (_uiState.value.mediaSessionServiceEnabled) return
                val message = error.message ?: error.errorCodeName
                HotaudioLog.e("local player error code=${error.errorCode} message=$message", error)
                updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
                emitMessage("Playback failed: $message", isError = true, throwable = error)
            }
        })
        localPlayer = player
        return player
    }

    private fun playViaLocal(track: Track, queue: List<Track>, seekToMs: Long = 0L) {
        HotaudioLog.i("AppViewModel.playViaLocal track=${track.id} title=${track.title}")
        val player = getOrCreateLocalPlayer()
        val previous = localQueue.currentTrack
        localQueue.setQueue(queue, track)

        val generation = localPlayGeneration.incrementAndGet()
        localPlayJob?.cancel()
        player.stop()
        player.clearMediaItems()

        if (previous != null && previous.id != track.id) {
            HotaudioStreamer.cancel(previous.id)
        }

        _progress.value = PlaybackProgress(positionMs = seekToMs)
        updatePlayer {
            copy(
                currentTrack = track,
                isLoading = true,
                isPlaying = false,
                error = null,
            )
        }
        recordHistory(track)

        val localUri = track.localUri
        if (localUri != null && downloader.exists(localUri)) {
            startLocalPlayback(player, track, localUri.toUri(), emptyMap(), seekToMs)
            return
        }

        localPlayJob = viewModelScope.launch {
            try {
                val audio = resolver.resolve(track)
                if (generation != localPlayGeneration.get()) return@launch
                startLocalPlayback(player, track, audio.url.toUri(), audio.headers, seekToMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != localPlayGeneration.get()) return@launch
                HotaudioLog.e("AppViewModel: failed to resolve track=${track.id}", error)
                player.pause()
                updatePlayer { copy(isLoading = false, isPlaying = false, error = error.message) }
                emitMessage("Could not play this track: ${error.message ?: "Could not resolve audio URL"}", isError = true)
            }
        }
    }

    private fun startLocalPlayback(
        player: ExoPlayer,
        track: Track,
        uri: Uri,
        headers: Map<String, String>,
        seekToMs: Long = 0L,
    ) {
        headerFactory.headers = headers + ("User-Agent" to Network.BROWSER_USER_AGENT)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.id)
        if (uri.scheme.equals(HotaudioStreamer.SCHEME, ignoreCase = true)) {
            builder.setMimeType(MimeTypes.AUDIO_MP4)
        }
        player.setMediaItem(builder.build())
        player.prepare()
        if (seekToMs > 0L) {
            player.seekTo(seekToMs)
        }
        player.play()
    }

    private fun stopLocalPlayback() {
        localPlayJob?.cancel()
        localPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.release()
        }
        localPlayer = null
        val current = localQueue.currentTrack
        if (current != null && current.id !in _uiState.value.downloadingIds) {
            HotaudioStreamer.cancel(current.id)
        }
        localQueue.clear()
    }

    private fun stopServicePlayback() {
        mediaController?.let { controller ->
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_DISMISS, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            controller.removeListener(playerListener)
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    override fun togglePlayPause() {
        val state = _uiState.value.player
        val currentTrack = state.currentTrack ?: return
        if (state.isLoading) return

        if (_uiState.value.mediaSessionServiceEnabled) {
            val controller = mediaController ?: return
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.currentMediaItem == null) {
                    play(currentTrack, null)
                } else {
                    if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0L)
                    controller.play()
                }
            }
        } else {
            val player = localPlayer ?: return
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.currentMediaItem == null) {
                    playViaLocal(currentTrack, localQueue.tracks.ifEmpty { listOf(currentTrack) })
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val duration = _progress.value.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        if (_uiState.value.mediaSessionServiceEnabled) {
            mediaController?.seekTo(target)
        } else {
            localPlayer?.seekTo(target)
        }
        _progress.value = _progress.value.copy(positionMs = target)
    }

    override fun nextTrack() {
        if (_uiState.value.mediaSessionServiceEnabled) {
            mediaController?.seekToNext()
        } else {
            if (localQueue.hasNext) {
                val next = localQueue.moveToNext()
                if (next != null) {
                    playViaLocal(next, localQueue.tracks)
                }
            } else {
                localPlayer?.pause()
            }
        }
    }

    override fun previousTrack() {
        if (_uiState.value.mediaSessionServiceEnabled) {
            mediaController?.seekToPrevious()
        } else {
            val currentPos = localPlayer?.currentPosition ?: 0L
            val (target, shouldSeekToZeroOnly) = localQueue.getPreviousTrack(currentPos)
            if (shouldSeekToZeroOnly || target == null || target.id == localQueue.currentTrack?.id) {
                localPlayer?.seekTo(0L)
            } else {
                localQueue.setQueue(localQueue.tracks, target)
                playViaLocal(target, localQueue.tracks)
            }
        }
    }

    override fun dismissPlayer() {
        HotaudioLog.i("dismissPlayer")
        if (_uiState.value.mediaSessionServiceEnabled) {
            val controller = mediaController
            if (controller != null) {
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.COMMAND_DISMISS, Bundle.EMPTY),
                    Bundle.EMPTY,
                )
            }
        } else {
            stopLocalPlayback()
        }
        _progress.value = PlaybackProgress()
        _uiState.update { it.copy(player = PlayerUiState()) }
    }

    private fun updatePlayer(block: PlayerUiState.() -> PlayerUiState) {
        _uiState.update { state ->
            state.copy(player = state.player.block())
        }
    }

    private fun startProgressLoop() {
        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.mediaSessionServiceEnabled) {
                    val controller = mediaController
                    if (controller != null && _uiState.value.player.currentTrack != null) {
                        val position = controller.currentPosition.coerceAtLeast(0L)
                        val duration = controller.duration.takeIf { it > 0L }
                            ?: _progress.value.durationMs
                        val next = PlaybackProgress(position, duration)
                        if (next != _progress.value) _progress.value = next
                    }
                } else {
                    val player = localPlayer
                    if (player != null && _uiState.value.player.currentTrack != null) {
                        val position = player.currentPosition.coerceAtLeast(0L)
                        val duration = player.duration.takeIf { it > 0L }
                            ?: _progress.value.durationMs
                        val next = PlaybackProgress(position, duration)
                        if (next != _progress.value) _progress.value = next
                    }
                }
                delay(500L)
            }
        }
    }

    private fun emitMessage(text: String, isError: Boolean = false, throwable: Throwable? = null) {
        if (isError) {
            handleDebugError(text, throwable)
        }
        _messages.tryEmit(UiMessage(text, isError))
    }

    private fun handleDebugError(message: String, throwable: Throwable?) {
        HotaudioLog.e("error: $message", throwable)
        if (!BuildConfig.DEBUG) return
        val stackTrace = throwable?.let { Log.getStackTraceString(it) } ?: ""
        val errorText = buildString {
            appendLine("Error: $message")
            if (throwable != null) {
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                if (stackTrace.isNotBlank()) {
                    appendLine("Stacktrace:")
                    appendLine(stackTrace)
                }
            }
        }.trim()

        try {
            val app = getApplication<Application>()
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Dawngasm Error", errorText))

            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            File(dir, "last_error.log").writeText("${Date()}\n$errorText\n")
            File(app.filesDir, "last_error.log").writeText("${Date()}\n$errorText\n")
            File(dir, "errors.log").appendText("${Date()}\n$errorText\n----------------------------------------\n")
        } catch (e: Throwable) {
            Log.e("AppViewModel", "Failed to copy/save debug error", e)
        }
    }

    override fun onCleared() {
        HotaudioLog.i("AppViewModel.onCleared")
        feedJob?.cancel()
        stopLocalPlayback()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
