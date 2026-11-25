package dev.dawngasm.app.platform

import dev.dawngasm.app.data.ImportMode
import dev.dawngasm.app.data.SyncBackendType
import dev.dawngasm.app.data.SyncConfig
import dev.dawngasm.app.data.SyncStatus
import dev.dawngasm.app.model.AuthorSuggestion
import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.TagSuggestion
import dev.dawngasm.app.model.Track

/**
 * Everything the shared UI needs from the app shell. The Android shell adapts
 * `AppViewModel` to this contract; desktop and iOS use `AppPresenter`.
 */
interface DawngasmActions {
    fun play(track: Track, queue: List<Track>?)
    fun toggleSave(track: Track)
    fun download(track: Track)
    fun togglePlayPause()
    fun nextTrack()
    fun previousTrack()
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
    fun toggleMediaSessionService(enabled: Boolean)
    suspend fun suggestTags(prefix: String): List<TagSuggestion>
    suspend fun suggestAuthors(prefix: String): List<AuthorSuggestion>
    suspend fun exportEncryptedBackup(password: String): Result<String>
    suspend fun importEncryptedBackup(source: String, password: String, mode: ImportMode): Result<Unit>
    suspend fun configureSync(
        backend: SyncBackendType,
        remoteUrl: String,
        password: String,
        authToken: String?,
        gistFilename: String = SyncConfig.DEFAULT_GIST_FILENAME,
    ): Result<SyncStatus>
    suspend fun unlockSync(password: String): Result<SyncStatus>
    fun syncNow()
    fun disableSync()
    fun notifyMessage(text: String, isError: Boolean = false)
}

/**
 * Platform-owned settings actions that need an activity result launcher or a
 * native picker.
 */
interface SettingsPlatformActions {
    fun chooseDownloadFolder()
    fun openHotaudioClearance()
    fun openAudiochanClearance()

    /** Extra hotaudio clearance detail, e.g. `IPv4: 203.0.113.4`. */
    fun hotaudioClearanceDetail(): String?

    /** Whether this shell can reveal the download folder in the file manager. */
    fun canOpenDownloadFolder(): Boolean = false

    fun openDownloadFolder() = Unit

    /** Whether this shell exposes a native document picker for backup files. */
    fun canPickBackupFiles(): Boolean = false

    fun saveBackupFile(suggestedName: String, content: String, onResult: (Result<String>) -> Unit) {
        onResult(Result.failure(UnsupportedOperationException("File export is not supported on this platform")))
    }

    fun pickBackupFile(onResult: (Result<String>) -> Unit) {
        onResult(Result.failure(UnsupportedOperationException("File picker is not supported on this platform")))
    }
}
