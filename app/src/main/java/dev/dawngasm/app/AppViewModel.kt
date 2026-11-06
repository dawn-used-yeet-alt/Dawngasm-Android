package dev.dawngasm.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.data.TrackDownloader
import dev.dawngasm.app.model.SearchSyntax
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
    private val resolver = AudioResolver()
    private val downloader = TrackDownloader(application)
    private val headerFactory = HeaderDataSourceFactory(application, Network.client)

    private val player: ExoPlayer = ExoPlayer.Builder(application)
        .setMediaSourceFactory(DefaultMediaSourceFactory(headerFactory))
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
                updatePlayer { copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> updatePlayer { copy(isLoading = true) }
                    Player.STATE_READY -> updatePlayer {
                        copy(isLoading = false)
                    }
                    Player.STATE_ENDED -> {
                        _progress.value = PlaybackProgress()
                        if (queue.isNotEmpty()) {
                            nextTrack()
                        } else {
                            updatePlayer { copy(isPlaying = false) }
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val message = error.message ?: error.errorCodeName
                updatePlayer { copy(isLoading = false, isPlaying = false, error = message) }
                emitMessage("Playback failed: $message", isError = true)
            }
        })

        loadFeed(reset = true)
        startProgressLoop()
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

    fun applySearch(input: String) {
        _uiState.update { it.copy(query = input.trim()) }
        loadFeed(reset = true)
    }

    fun clearSearch() {
        _uiState.update { it.copy(query = "") }
        loadFeed(reset = true)
    }

    fun setSort(sort: String) {
        if (sort == _uiState.value.sort) return
        val query = SearchSyntax.withoutToken(_uiState.value.query, "sort:")
        _uiState.update { it.copy(sort = sort, query = query) }
        persistFeed()
        loadFeed(reset = true)
    }

    fun setWindow(window: String) {
        if (window == _uiState.value.window) return
        val query = SearchSyntax.withoutToken(_uiState.value.query, "top:")
        _uiState.update { it.copy(window = window, query = query) }
        persistFeed()
        loadFeed(reset = true)
    }

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

        val parsed = SearchSyntax.parse(state.query)
        val sort = parsed.sort ?: state.sort
        val window = parsed.window ?: state.window
        val text = parsed.text
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
                    blockedTags = state.blockedTags,
                    page = page,
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
                emitMessage(message, isError = true)
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
                emitMessage("Download failed: ${error.message ?: "unknown error"}", isError = true)
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

    // --- playback -----------------------------------------------------------

    fun play(track: Track, list: List<Track>? = null) {
        val source = list?.takeIf { it.isNotEmpty() } ?: _uiState.value.tracks
        queue = if (source.any { it.id == track.id }) source else listOf(track) + source

        val localUri = track.localUri
        if (localUri != null && downloader.exists(localUri)) {
            playGeneration.incrementAndGet()
            playJob?.cancel()
            startPlayback(track, Uri.parse(localUri), emptyMap(), playWhenReady = true)
            return
        }

        val generation = playGeneration.incrementAndGet()
        playJob?.cancel()
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
                val audio = resolver.resolve(track)
                if (generation != playGeneration.get()) return@launch
                startPlayback(track, Uri.parse(audio.url), audio.headers, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != playGeneration.get()) return@launch
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
        headerFactory.headers = headers + ("User-Agent" to Network.BROWSER_USER_AGENT)
        player.setMediaItem(MediaItem.fromUri(uri))
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
        playGeneration.incrementAndGet()
        playJob?.cancel()
        player.stop()
        player.clearMediaItems()
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

    private fun emitMessage(text: String, isError: Boolean = false) {
        _messages.tryEmit(UiMessage(text, isError))
    }

    override fun onCleared() {
        feedJob?.cancel()
        playJob?.cancel()
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
        return DefaultDataSource.Factory(appContext, http).createDataSource()
    }
}
