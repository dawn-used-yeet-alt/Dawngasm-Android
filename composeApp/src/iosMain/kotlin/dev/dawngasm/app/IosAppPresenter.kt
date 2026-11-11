package dev.dawngasm.app

import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.CookieUtils
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.HotaudioBridgeProvider
import dev.dawngasm.app.data.HotaudioRuntime
import dev.dawngasm.app.data.HotaudioStreamer
import dev.dawngasm.app.data.IosFileSaver
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.PlatformFileOps
import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.SearchSyntax
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.player.AudioPlayer
import dev.dawngasm.app.player.IosAudioPlayer
import dev.dawngasm.app.state.AppUiState
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.state.PlayerUiState
import dev.dawngasm.app.state.UiMessage
import dev.dawngasm.app.util.PlatformLog
import dev.dawngasm.app.util.safeFileName
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_HISTORY = 100

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

class IosAppPresenter(
    val prefs: AppPreferences = AppPreferences(),
    private val api: DawngasmApi = DawngasmApi(),
    private val resolver: AudioResolver = AudioResolver(prefs),
    private val fileSaver: IosFileSaver = IosFileSaver(),
    val player: AudioPlayer = IosAudioPlayer(resolver),
) : DawngasmActions {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress(positionMs = 42_000L, durationMs = 180_000L))
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var feedJob: Job? = null
    private var feedPage = 0
    private var feedGeneration = 0L
    private val feedStack = ArrayDeque<FeedSnapshot>()

    init {
        // Sync stored cookies on startup
        prefs.audiochanClearance()?.let { (cookie, _) ->
            Network.syncCookie("https://audiochan.com/", cookie)
            Network.syncCookie("https://api.audiochan.com/", cookie)
        }
        if (prefs.hotaudioClearance() != null) {
            HotaudioRuntime.applyClearance(prefs)
        }

        // Observe audio player state
        scope.launch {
            var lastTrackId: String? = null
            player.state.collect { pState ->
                _uiState.update { current ->
                    current.copy(
                        player = PlayerUiState(
                            currentTrack = pState.currentTrack ?: current.player.currentTrack,
                            isPlaying = pState.isPlaying,
                            isLoading = pState.isLoading,
                            error = pState.error,
                        ),
                    )
                }
                if (pState.currentTrack != null) {
                    _progress.value = PlaybackProgress(
                        positionMs = pState.positionMs,
                        durationMs = pState.durationMs,
                    )
                }
                val curTrack = pState.currentTrack
                if (curTrack != null && curTrack.id != lastTrackId) {
                    lastTrackId = curTrack.id
                    recordHistory(curTrack)
                }
            }
        }
    }

    private fun initialState(): AppUiState {
        val blocked = prefs.blockedTags().ifEmpty { listOf("incest") }
        val sample = sampleAppUiState()
        val saved = prefs.saved().ifEmpty { sample.saved }
        val history = prefs.history().ifEmpty { sample.history }
        val downloads = prefs.downloads().ifEmpty { sample.downloads }
        val hasHotaudioClearance = prefs.hotaudioClearance() != null
        val hasAudiochanClearance = prefs.audiochanClearance() != null

        return AppUiState(
            isLoading = false,
            tracks = sample.tracks,
            saved = saved,
            history = history,
            downloads = downloads,
            query = "",
            sort = prefs.sort(),
            window = prefs.window(),
            blockedTags = blocked,
            downloadFolderLabel = fileSaver.folderLabel(null),
            hotaudioConnected = hasHotaudioClearance || sample.hotaudioConnected,
            audiochanConnected = hasAudiochanClearance || sample.audiochanConnected,
            player = sample.player,
        )
    }

    private fun emitMessage(text: String, isError: Boolean = false) {
        _messages.tryEmit(UiMessage(text = text, isError = isError))
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
        val generation = ++feedGeneration
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

        feedJob = scope.launch {
            try {
                val similarTracks = api.similar(track.id)
                if (generation != feedGeneration) return@launch
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
                if (generation != feedGeneration) return@launch
                val message = error.message ?: "Could not find similar posts"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = message,
                    )
                }
                emitMessage(message, isError = true)
            }
        }
    }

    override fun navigateBack() {
        if (feedStack.isEmpty()) return
        val previous = feedStack.removeLast()
        feedJob?.cancel()
        feedGeneration++
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
        val generation = ++feedGeneration
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

        feedJob = scope.launch {
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
                if (generation != feedGeneration) return@launch
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
                if (generation != feedGeneration) return@launch
                val message = error.message ?: "Could not reach the index"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = message,
                    )
                }
                emitMessage(message, isError = true)
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
        if (existing != null && fileSaver.exists(existing.localUri)) {
            emitMessage("Already downloaded")
            return
        }

        _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.id) }
        scope.launch {
            try {
                val audio = resolver.resolve(track)
                val base = safeFileName(
                    input = if (track.author.isNotBlank()) "${track.title} - ${track.author}" else track.title,
                    fallback = track.id,
                )
                val fileName = "$base.${audio.extension}"
                val bytes: ByteArray = if (track.host == AudioHost.HOTAUDIO || audio.url.startsWith("${HotaudioStreamer.SCHEME}:")) {
                    val spec = HotaudioStreamer.spec(track.id, track.pageUrl)
                    if (HotaudioStreamer.stateFor(track.id) == null) {
                        HotaudioStreamer.ensureStarted(spec, prefs)
                    }
                    val path = HotaudioStreamer.awaitComplete(spec)
                    PlatformFileOps.readBytes(path)
                        ?: throw IllegalStateException("Could not read downloaded hotaudio stream")
                } else {
                    val response = Network.client.get(audio.url) {
                        audio.headers.forEach { (name, value) -> header(name, value) }
                        header("User-Agent", Network.BROWSER_USER_AGENT)
                    }
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException("Server returned HTTP ${response.status.value}")
                    }
                    response.body<ByteArray>()
                }

                val savedUri = fileSaver.save(fileName, bytes)
                val local = track.copy(localUri = savedUri)
                val next = listOf(local) + _uiState.value.downloads.filterNot { it.id == track.id }
                prefs.saveDownloads(next)
                _uiState.update { it.copy(downloads = next) }
                emitMessage("Downloaded “${track.title}”")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PlatformLog.e("IosAppPresenter", "Download failed for ${track.id}", error)
                emitMessage("Download failed: ${error.message ?: "unknown error"}", isError = true)
            } finally {
                _uiState.update { it.copy(downloadingIds = it.downloadingIds - track.id) }
            }
        }
    }

    override fun setDownloadTree(uri: String?) {
        // iOS uses app Documents directory; treeUri is an Android SAF concept
    }

    // --- clearance ----------------------------------------------------------

    fun saveAudiochanClearance(rawCookie: String, userAgent: String) {
        prefs.saveAudiochanClearance(rawCookie, userAgent)
        Network.syncCookie("https://audiochan.com/", rawCookie)
        Network.syncCookie("https://api.audiochan.com/", rawCookie)
        refreshAudiochanClearance()
    }

    fun saveHotaudioClearance(rawCookie: String, userAgent: String, ip: String?, family: String?) {
        if (CookieUtils.hasCookie(rawCookie, "cf_clearance")) {
            prefs.saveHotaudioClearance(rawCookie, userAgent)
            if (family != null) {
                prefs.saveHotaudioIpFamily(family, ip)
            }
            HotaudioRuntime.applyClearance(prefs)
            refreshHotaudioClearance()
        }
    }

    override fun refreshHotaudioClearance() {
        val clearance = prefs.hotaudioClearance()
        if (clearance != null) {
            HotaudioRuntime.applyClearance(prefs)
        } else {
            HotaudioBridgeProvider.bridge?.clearClearance()
        }
        _uiState.update { it.copy(hotaudioConnected = clearance != null) }
    }

    override fun clearHotaudioClearance() {
        prefs.clearHotaudioClearance()
        HotaudioBridgeProvider.bridge?.clearClearance()
        HotaudioBridgeProvider.bridge?.setIpFamily("any")
        _uiState.update { it.copy(hotaudioConnected = false) }
    }

    override fun refreshAudiochanClearance() {
        val clearance = prefs.audiochanClearance()
        if (clearance != null) {
            Network.syncCookie("https://audiochan.com/", clearance.first)
            Network.syncCookie("https://api.audiochan.com/", clearance.first)
        }
        _uiState.update { it.copy(audiochanConnected = clearance != null) }
    }

    override fun clearAudiochanClearance() {
        prefs.clearAudiochanClearance()
        _uiState.update { it.copy(audiochanConnected = false) }
    }

    // --- playback -----------------------------------------------------------

    override fun play(track: Track, queue: List<Track>?) {
        val activeQueue = queue ?: _uiState.value.tracks
        player.play(track, activeQueue)
        recordHistory(track)
    }

    override fun togglePlayPause() {
        player.togglePlayPause()
    }

    override fun dismissPlayer() {
        player.stop()
        _uiState.update { it.copy(player = PlayerUiState()) }
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun nextTrack() {
        player.nextTrack()
    }
}
