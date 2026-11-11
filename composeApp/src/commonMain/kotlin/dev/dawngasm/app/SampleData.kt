package dev.dawngasm.app

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.state.AppUiState
import dev.dawngasm.app.state.PlayerUiState
import dev.dawngasm.app.util.currentTimeMillis

/**
 * Demo content for the iOS shell and previews while Phase 3 wires the shared
 * data layer. It mirrors the shape of a populated Android app state so every
 * screen can be exercised on the simulator.
 */
fun sampleAppUiState(): AppUiState {
    val now = currentTimeMillis() / 1000L
    val tracks = listOf(
        Track(
            id = "1",
            title = "[F4M] A very long, warm and slightly ridiculous title that wraps onto a second line",
            author = "LateNightAudio",
            host = AudioHost.SOUNDGASM,
            pageUrl = "",
            score = 12_400,
            createdUtc = now - 7_200,
        ),
        Track(
            id = "2",
            title = "[M4F] Quiet rain and a slow story",
            author = "SleepyVibes",
            host = AudioHost.WHYP,
            pageUrl = "",
            score = 840,
            createdUtc = now - 86_400,
        ),
        Track(
            id = "3",
            title = "[FF4M] Two voices, one long afternoon",
            author = "AuraSphere",
            host = AudioHost.AUDIOCHAN,
            pageUrl = "",
            score = 3_100,
            createdUtc = now - 172_800,
        ),
    )
    return AppUiState(
        tracks = tracks,
        isLoading = false,
        saved = listOf(tracks[1]),
        history = tracks,
        downloads = listOf(tracks[2]),
        player = PlayerUiState(
            currentTrack = tracks.first(),
            isPlaying = true,
            isLoading = false,
        ),
        blockedTags = listOf("incest"),
        downloadFolderLabel = "App storage (Music/Dawngasm)",
        hotaudioConnected = true,
        audiochanConnected = false,
    )
}

/** No-op actions for the iOS demo shell until Phase 3. */
object NoOpDawngasmActions : DawngasmActions {
    override fun play(track: Track, queue: List<Track>?) = Unit
    override fun toggleSave(track: Track) = Unit
    override fun download(track: Track) = Unit
    override fun togglePlayPause() = Unit
    override fun nextTrack() = Unit
    override fun dismissPlayer() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun clearSearch() = Unit
    override fun refresh() = Unit
    override fun loadMore() = Unit
    override fun onScrollRestored() = Unit
    override fun showSimilar(track: Track, scrollIndex: Int, scrollOffset: Int) = Unit
    override fun navigateBack() = Unit
    override fun applySearch(request: SearchRequest) = Unit
    override fun addBlockedTag(raw: String) = Unit
    override fun removeBlockedTag(tag: String) = Unit
    override fun setDownloadTree(uri: String?) = Unit
    override fun clearDownloads() = Unit
    override fun refreshHotaudioClearance() = Unit
    override fun clearHotaudioClearance() = Unit
    override fun refreshAudiochanClearance() = Unit
    override fun clearAudiochanClearance() = Unit
    override suspend fun suggestTags(prefix: String): List<TagSuggestion> = emptyList()
}

/** No-op settings platform for the iOS demo shell until Phase 4. */
object NoOpSettingsPlatformActions : SettingsPlatformActions {
    override fun chooseDownloadFolder() = Unit
    override fun openHotaudioClearance() = Unit
    override fun openAudiochanClearance() = Unit
    override fun hotaudioClearanceDetail(): String? = null
}
