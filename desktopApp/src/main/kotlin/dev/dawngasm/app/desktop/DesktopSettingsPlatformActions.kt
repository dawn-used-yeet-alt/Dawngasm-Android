package dev.dawngasm.app.desktop

import dev.dawngasm.app.AppPresenter
import dev.dawngasm.app.data.DesktopFileSaver
import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.platform.SettingsPlatformActions
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Desktop settings actions: native folder chooser, file-manager reveal, clearance entry points. */
class DesktopSettingsPlatformActions(
    private val presenter: AppPresenter,
    private val openHotaudio: () -> Unit,
    private val openAudiochan: () -> Unit,
) : SettingsPlatformActions {
    override fun chooseDownloadFolder() {
        val initial = runCatching {
            DesktopFileSaver.resolveDirectory(presenter.prefs.downloadTreeUri()).toFile()
        }.getOrElse { DesktopPlatform.downloadDir.toFile() }

        val choose = {
            val chooser = JFileChooser(initial).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
                dialogTitle = "Choose download folder"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val selected: File? = chooser.selectedFile
                if (selected != null) {
                    presenter.setDownloadTree(selected.absolutePath)
                }
            }
        }

        if (SwingUtilities.isEventDispatchThread()) choose() else SwingUtilities.invokeLater(choose)
    }

    override fun openHotaudioClearance() = openHotaudio()

    override fun openAudiochanClearance() = openAudiochan()

    override fun hotaudioClearanceDetail(): String? {
        val ip = presenter.prefs.hotaudioClientIp()
        val family = presenter.prefs.hotaudioIpFamily()?.uppercase()
        return when {
            !ip.isNullOrBlank() && !family.isNullOrBlank() -> "$family: $ip"
            !ip.isNullOrBlank() -> ip
            !family.isNullOrBlank() -> family
            else -> null
        }
    }

    override fun canOpenDownloadFolder(): Boolean = true

    override fun openDownloadFolder() {
        val directory = DesktopFileSaver.resolveDirectory(presenter.prefs.downloadTreeUri())
        DesktopPlatform.openDirectory(directory)
    }
}
