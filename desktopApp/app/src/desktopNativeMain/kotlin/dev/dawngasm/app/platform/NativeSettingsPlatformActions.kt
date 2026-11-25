package dev.dawngasm.app.platform

import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.data.PlatformFileOps

class NativeSettingsPlatformActions(
    private val prefs: AppPreferences,
    private val onOpenHotaudio: () -> Unit = {},
    private val onOpenAudiochan: () -> Unit = {},
) : SettingsPlatformActions {
    override fun chooseDownloadFolder() {
        // Desktop uses DAWNGASM_MUSIC_DIR or defaults to ~/Music/Dawngasm
    }

    override fun openHotaudioClearance() = onOpenHotaudio()

    override fun openAudiochanClearance() = onOpenAudiochan()

    override fun hotaudioClearanceDetail(): String? =
        prefs.hotaudioIpFamily()?.let { "Family: $it" }

    override fun canOpenDownloadFolder(): Boolean = true

    override fun openDownloadFolder() {
        val dir = prefs.downloadTreeUri()?.removePrefix("file://") ?: DesktopPlatform.downloadDir
        DesktopPlatform.openDirectory(dir)
    }

    override fun saveBackupFile(
        suggestedName: String,
        content: String,
        onResult: (Result<String>) -> Unit,
    ) {
        val dir = prefs.downloadTreeUri()?.removePrefix("file://") ?: DesktopPlatform.downloadDir
        val path = "$dir/$suggestedName"
        val result = runCatching {
            PlatformFileOps.writeText(path, content)
            "Saved to $path"
        }
        onResult(result)
    }
}
