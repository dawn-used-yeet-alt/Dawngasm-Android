package dev.dawngasm.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Date
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.HotaudioDataSource
import dev.dawngasm.app.data.HotaudioLog
import dev.dawngasm.app.data.HotaudioRuntime
import dev.dawngasm.app.data.HotaudioStreamer
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.TrackDownloader
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.state.AppUiState
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.PlayerUiState
import dev.dawngasm.app.state.UiMessage
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

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val api = DawngasmApi()
    private val resolver = AudioResolver(application)
    private val downloader = TrackDownloader(application)
    private val headerFactory = HeaderDataSourceFactory(application, Network.client)

    private val player: ExoPlayer = ExoPlayer.Builder(application)
        .setMediaSourceFactory(DefaultMediaSourceFactory(headerFactory))
        // The hotaudio downloader writes in bursts, so keep a generous cushion
        // instead of stopping and restarting playback every couple of seconds.
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

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var feedJob: Job? = null
    private var playJob: Job? = null
    private var feedPage = 0
    private val feedGeneration = AtomicLong(0)
    private val playGeneration = AtomicLong(0)
    private var queue: List<Track> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                HotaudioLog.i("player isPlaying=$isPlaying")
                updatePlayer { copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                HotaudioLog.i(
                    "player state=${playerStateName(playbackState)} " +
                        "position=${player.currentPosition} duration=${player.duration} " +
                        "buffered=${player.bufferedPosition}",
                )
                when (playbackState) {
                    Player.STATE_BUFFERING -> updatePlayer { copy(isLoading = true) }
                    Player.STATE_READY -> updatePlayer {
                        copy(isLoading = false)
                    }
                    Player.STATE_ENDED -> {
                        _progress.value = PlaybackProgress()
                        val endedTrackId = player.currentMediaItem?.mediaId
                        val streamFailure = endedTrackId
                            ?.let { HotaudioStreamer.stateFor(it, null)?.error }
                        if (streamFailure != null) {
                            // The source ended because the download died, not
                            // because the track finished. Do not skip ahead.
                            HotaudioLog.w(
                                "playback ended early track=$endedTrackId: " +
                                    "${streamFailure.message}",
                            )
                            updatePlayer { copy(isPlaying = false, isLoading = false) }
                            emitMessage(
                                "Playback stopped before the end: ${streamFailure.message}",
                                isError = true,
                            )
                        } else if (queue.isNotEmpty()) {
                            nextTrack()
                        } else {
                            updatePlayer { copy(isPlaying = false) }
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failedTrackId = player.currentMediaItem?.mediaId
                val currentTrackId = _uiState.value.player.currentTrack?.id
                if (player.currentMediaItem == null ||
                    (failedTrackId != null && currentTrackId != null &&
                        failedTrackId != currentTrackId)
                ) {
                    HotaudioLog.w(
                        "ignoring stale player error track=$failedTrackId " +
                            "current=$currentTrackId code=${error.errorCodeName}",
                    )
                    return
                }
                val message = error.message ?: error.errorCodeName
                HotaudioLog.e(
                    "player error code=${error.errorCode} name=${error.errorCodeName} " +
                        "message=$message",
                    error,
                )
                updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
                emitMessage("Playback failed: $message", isError = true, throwable = error)
            }
        })

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

    fun refresh() {
        loadFeed(reset = true, refreshing = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        loadFeed(reset = false)
    }

    fun applySearch(request: SearchRequest) {
        // Keep inline top:/sort:/host: support for anyone who still types it, while
        // the sheet's dropdowns provide the visible controls.
        val parsed = SearchSyntax.parse(request.text)
        val combinedHosts = (request.hosts + parsed.hosts).distinct()
        val combinedExcludeHosts = (request.excludeHosts + parsed.excludeHosts).distinct()
        _uiState.update {
            it.copy(
                query = parsed.text,
                includeTags = request.includeTags.mapNotNull(SearchSyntax::normalizeTag).distinct(),
                excludeTags = request.excludeTags.mapNotNull(SearchSyntax::normalizeTag).distinct(),
                sort = parsed.sort ?: request.sort,
                window = parsed.window ?: request.window,
                hosts = combinedHosts,
                excludeHosts = combinedExcludeHosts,
            )
        }
        persistFeed()
        loadFeed(reset = true)
    }

    fun clearSearch() {
        _uiState.update {
            it.copy(
                query = "",
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SearchSyntax.DEFAULT_SORT,
                window = SearchSyntax.DEFAULT_WINDOW,
                hosts = emptyList(),
                excludeHosts = emptyList(),
            )
        }
        persistFeed()
        loadFeed(reset = true)
    }

    /** Tag autocomplete for the search sheet. Failures simply show no rows. */
    suspend fun suggestTags(prefix: String): List<TagSuggestion> =
        runCatching { api.tagSuggestions(prefix) }.getOrDefault(emptyList())

    fun addBlockedTag(raw: String) {
        val tag = raw.trim().lowercase()
        if (tag.isBlank()) return
        val next = (_uiState.value.blockedTags + tag).distinct()
        _uiState.update { it.copy(blockedTags = next) }
        prefs.saveBlockedTags(next)
        loadFeed(reset = true)
    }

    fun removeBlockedTag(tag: String) {
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

    fun toggleSave(track: Track) {
        val current = _uiState.value.saved
        val next = if (current.any { it.id == track.id }) {
            current.filterNot { it.id == track.id }
        } else {
            listOf(track) + current
        }
        prefs.saveSaved(next)
        _uiState.update { it.copy(saved = next) }
    }

    fun clearDownloads() {
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

    fun download(track: Track) {
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

    fun setDownloadTree(uri: String?) {
        prefs.saveDownloadTreeUri(uri)
        _uiState.update {
            it.copy(
                downloadTreeUri = uri,
                downloadFolderLabel = downloader.folderLabel(uri),
            )
        }
    }

    /** Refresh the hotaudio clearance status after the WebView activity. */
    fun refreshHotaudioClearance() {
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

    fun clearHotaudioClearance() {
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
    fun refreshAudiochanClearance() {
        HotaudioLog.i("refreshAudiochanClearance")
        val clearance = prefs.audiochanClearance()
        if (clearance != null) {
            Network.syncCookie("https://audiochan.com/", clearance.first)
            Network.syncCookie("https://api.audiochan.com/", clearance.first)
        }
        _uiState.update { it.copy(audiochanConnected = clearance != null) }
    }

    fun clearAudiochanClearance() {
        HotaudioLog.i("clearAudiochanClearance")
        prefs.clearAudiochanClearance()
        _uiState.update { it.copy(audiochanConnected = false) }
    }

    // --- playback -----------------------------------------------------------

    fun play(track: Track, list: List<Track>? = null) {
        HotaudioLog.i(
            "play track=${track.id} host=${track.host.label} title=${track.title} " +
                "page=${track.pageUrl} localUri=${track.localUri}",
        )
        val previous = _uiState.value.player.currentTrack

        // Tear down the old source before resolving the new one. Otherwise a
        // late error/retry from the previous media item can clobber the new
        // track's state or be attributed to it.
        val generation = playGeneration.incrementAndGet()
        playJob?.cancel()
        player.stop()
        player.clearMediaItems()

        if (previous != null && previous.id != track.id &&
            previous.id !in _uiState.value.downloadingIds
        ) {
            HotaudioLog.i("play: cancelling previous stream track=${previous.id}")
            HotaudioStreamer.cancel(previous.id)
        }

        val source = list?.takeIf { it.isNotEmpty() } ?: _uiState.value.tracks
        queue = if (source.any { it.id == track.id }) source else listOf(track) + source

        val localUri = track.localUri
        if (localUri != null && downloader.exists(localUri)) {
            HotaudioLog.i("play local file track=${track.id} uri=$localUri")
            startPlayback(track, Uri.parse(localUri), emptyMap(), playWhenReady = true)
            return
        }

        _uiState.update {
            it.copy(
                player = PlayerUiState(
                    currentTrack = track,
                    isLoading = true,
                    isPlaying = false,
                ),
            )
        }
        playJob = viewModelScope.launch {
            try {
                val startedAt = System.currentTimeMillis()
                val audio = resolver.resolve(track)
                HotaudioLog.i(
                    "play resolved track=${track.id} uri=${audio.url} " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms",
                )
                if (generation != playGeneration.get()) {
                    HotaudioLog.w("play superseded track=${track.id}")
                    return@launch
                }
                startPlayback(track, Uri.parse(audio.url), audio.headers, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                HotaudioLog.w("play cancelled track=${track.id}")
                throw cancelled
            } catch (error: Exception) {
                if (generation != playGeneration.get()) return@launch
                HotaudioLog.e("play failed track=${track.id}", error)
                updatePlayer {
                    copy(
                        isLoading = false,
                        isPlaying = false,
                        error = error.message,
                    )
                }
                emitMessage(
                    "Could not play this track: ${error.message ?: "unknown error"}",
                    isError = true,
                    throwable = error,
                )
            }
        }
    }

    private fun startPlayback(
        track: Track,
        uri: Uri,
        headers: Map<String, String>,
        playWhenReady: Boolean,
    ) {
        HotaudioLog.i(
            "startPlayback track=${track.id} uri=$uri scheme=${uri.scheme} " +
                "headers=${headers.keys} playWhenReady=$playWhenReady",
        )
        headerFactory.headers = headers + ("User-Agent" to Network.BROWSER_USER_AGENT)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.id)
        if (uri.scheme.equals(HotaudioStreamer.SCHEME, ignoreCase = true)) {
            builder.setMimeType(MimeTypes.AUDIO_MP4)
        }
        val mediaItem = builder.build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
        _progress.value = PlaybackProgress()
        _uiState.update {
            it.copy(
                player = it.player.copy(
                    currentTrack = track,
                    isLoading = true,
                    isPlaying = playWhenReady,
                    error = null,
                ),
            )
        }
        recordHistory(track)
    }

    fun togglePlayPause() {
        val state = _uiState.value.player
        if (state.currentTrack == null || state.isLoading) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    fun nextTrack() {
        val current = _uiState.value.player.currentTrack ?: return
        val list = queue.ifEmpty { _uiState.value.tracks }
        if (list.isEmpty()) {
            player.pause()
            return
        }
        val index = list.indexOfFirst { it.id == current.id }
        val next = list[(index + 1) % list.size]
        play(next, list)
    }

    fun dismissPlayer() {
        HotaudioLog.i("dismissPlayer")
        val current = _uiState.value.player.currentTrack
        playGeneration.incrementAndGet()
        playJob?.cancel()
        player.stop()
        player.clearMediaItems()
        if (current != null && current.id !in _uiState.value.downloadingIds) {
            HotaudioStreamer.cancel(current.id)
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
                if (_uiState.value.player.currentTrack != null) {
                    val position = player.currentPosition.coerceAtLeast(0L)
                    val duration = player.duration.takeIf { it > 0L }
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
        playJob?.cancel()
        HotaudioStreamer.cancelAll()
        player.release()
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}

/**
 * A [DataSource.Factory] whose request headers can be swapped before each
 * media item. audiochan needs Origin/Referer headers; soundgasm and whyp just
 * need a browser user agent. Sharing the OkHttp client keeps the cookie jar.
 */
private class HeaderDataSourceFactory(
    context: Context,
    private val client: okhttp3.OkHttpClient,
) : DataSource.Factory {
    private val appContext = context.applicationContext

    @Volatile
    var headers: Map<String, String> = emptyMap()

    override fun createDataSource(): DataSource {
        val http = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(headers)
        return RoutingDataSource(appContext, DefaultDataSource.Factory(appContext, http))
    }
}

/**
 * Sends `hotaudio://stream/...` URIs to [HotaudioDataSource] and everything
 * else to Media3's default file/HTTP stack.
 */
private class RoutingDataSource(
    private val context: Context,
    private val fallbackFactory: DataSource.Factory,
) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val next = if (dataSpec.uri.scheme.equals(HotaudioStreamer.SCHEME, ignoreCase = true)) {
            HotaudioLog.i("routing to HotaudioDataSource uri=${dataSpec.uri}")
            HotaudioDataSource(context)
        } else {
            fallbackFactory.createDataSource()
        }
        listeners.forEach(next::addTransferListener)
        delegate = next
        return next.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()
}

private fun playerStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
}
