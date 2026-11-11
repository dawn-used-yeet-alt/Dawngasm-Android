package dev.dawngasm.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioRuntime
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.TrackDownloader
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
import org.json.JSONObject

data class FeedSnapshot(
    val tracks: List<Track>,
    val query: String,
    val includeTags: List<String>,
    val excludeTags: List<String>,
    val sort: String,
    val window: String,
    val hosts: List<String>,
    val excludeHosts: List<String>,
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
    private val downloader = TrackDownloader(application)

    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == PlaybackService.COMMAND_ERROR) {
                val message = args.getString(PlaybackService.KEY_ERROR_MESSAGE) ?: "Playback failed"
                updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
                emitMessage("Could not play this track: $message", isError = true)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(controller, command, args)
        }
    }

    private val sessionToken = SessionToken(
        application,
        ComponentName(application, PlaybackService::class.java),
    )
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(application, sessionToken)
            .setListener(controllerListener)
            .buildAsync()

    @Volatile
    private var mediaController: MediaController? = null

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
            HotaudioLog.i("controller isPlaying=$isPlaying")
            updatePlayer { copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
            val track = mediaItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_JSON)
                ?.let { runCatching { TrackJson.fromJson(JSONObject(it)) }.getOrNull() }
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
            val message = error.message ?: error.errorCodeName
            HotaudioLog.e("controller player error code=${error.errorCode} message=$message", error)
            updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
            emitMessage("Playback failed: $message", isError = true, throwable = error)
        }
    }

    init {
        controllerFuture.addListener({
            try {
                val controller = controllerFuture.get()
                mediaController = controller
                controller.addListener(playerListener)
                val currentItem = controller.currentMediaItem
                val track = currentItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_JSON)
                    ?.let { runCatching { TrackJson.fromJson(JSONObject(it)) }.getOrNull() }
                if (track != null) {
                    updatePlayer {
                        copy(
                            currentTrack = track,
                            isPlaying = controller.isPlaying,
                            isLoading = controller.playbackState == Player.STATE_BUFFERING,
                        )
                    }
                }
            } catch (e: Exception) {
                HotaudioLog.e("AppViewModel: failed to connect MediaController", e)
            }
        }, ContextCompat.getMainExecutor(application))

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
        )
    }

    // --- feed ---------------------------------------------------------------

    override fun refresh() {
        val state = _uiState.value
        if (state.similarSourceTrack != null) {
            loadSimilar(state.similarSourceTrack, reset = true, refreshing = true)
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

    override fun play(track: Track, queue: List<Track>?) {
        HotaudioLog.i("AppViewModel.play track=${track.id} title=${track.title}")
        val activeQueue = queue ?: _uiState.value.tracks
        val args = Bundle().apply {
            putString(PlaybackService.KEY_TRACK_JSON, TrackJson.toJson(track).toString())
            putString(PlaybackService.KEY_QUEUE_JSON, TrackJson.listToJson(activeQueue))
        }
        val command = SessionCommand(PlaybackService.COMMAND_PLAY_TRACK, Bundle.EMPTY)

        val controller = mediaController
        if (controller != null) {
            controller.sendCustomCommand(command, args)
        } else {
            controllerFuture.addListener({
                try {
                    controllerFuture.get()?.sendCustomCommand(command, args)
                } catch (e: Exception) {
                    HotaudioLog.e("Failed to send play command", e)
                }
            }, ContextCompat.getMainExecutor(getApplication()))
        }

        _progress.value = PlaybackProgress()
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

    override fun togglePlayPause() {
        val controller = mediaController ?: return
        val state = _uiState.value.player
        if (state.currentTrack == null || state.isLoading) return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.currentMediaItem == null) {
                play(state.currentTrack, null)
            } else {
                if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0L)
                controller.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        val duration = _progress.value.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        controller.seekTo(target)
        _progress.value = _progress.value.copy(positionMs = target)
    }

    override fun nextTrack() {
        mediaController?.seekToNext()
    }

    fun previousTrack() {
        mediaController?.seekToPrevious()
    }

    override fun dismissPlayer() {
        HotaudioLog.i("dismissPlayer")
        val controller = mediaController
        if (controller != null) {
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_DISMISS, Bundle.EMPTY),
                Bundle.EMPTY,
            )
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
                val controller = mediaController
                if (controller != null && _uiState.value.player.currentTrack != null) {
                    val position = controller.currentPosition.coerceAtLeast(0L)
                    val duration = controller.duration.takeIf { it > 0L }
                        ?: _progress.value.durationMs
                    val next = PlaybackProgress(position, duration)
                    if (next != _progress.value) _progress.value = next
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
        MediaController.releaseFuture(controllerFuture)
        mediaController = null
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
