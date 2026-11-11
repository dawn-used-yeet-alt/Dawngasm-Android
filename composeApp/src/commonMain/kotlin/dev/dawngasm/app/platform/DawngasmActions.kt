package dev.dawngasm.app.platform

import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track

/**
 * Everything the shared UI needs from the app shell. The Android shell adapts
 * `AppViewModel` to this contract; iOS provides a no-op/demo implementation
 * until Phase 3 adds the shared data layer.
 */
interface DawngasmActions {
    fun play(track: Track, queue: List<Track>?)
    fun toggleSave(track: Track)
    fun download(track: Track)
    fun togglePlayPause()
    fun nextTrack()
    fun dismissPlayer()
    fun seekTo(positionMs: Long)
    fun clearSearch()
    fun refresh()
    fun loadMore()
    fun onScrollRestored()
    fun showSimilar(track: Track, scrollIndex: Int, scrollOffset: Int)
    fun navigateBack()
    fun applySearch(request: SearchRequest)
    fun addBlockedTag(raw: String)
    fun removeBlockedTag(tag: String)
    fun setDownloadTree(uri: String?)
    fun clearDownloads()
    fun refreshHotaudioClearance()
    fun clearHotaudioClearance()
    fun refreshAudiochanClearance()
    fun clearAudiochanClearance()
    suspend fun suggestTags(prefix: String): List<TagSuggestion>
}

/**
 * Platform-owned settings actions that need an activity result launcher or a
 * native picker. Android keeps the existing SAF folder picker and clearance
 * activities; iOS uses stubs until Phase 4.
 */
interface SettingsPlatformActions {
    fun chooseDownloadFolder()
    fun openHotaudioClearance()
    fun openAudiochanClearance()

    /** Extra hotaudio clearance detail, e.g. `IPv4: 203.0.113.4`. */
    fun hotaudioClearanceDetail(): String?
}
