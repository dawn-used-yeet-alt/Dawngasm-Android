package dev.dawngasm.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dawngasm.app.AppPresenter
import dev.dawngasm.app.DawngasmApp
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DesktopFileSaver
import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.player.NoOpAudioPlayer
import dev.dawngasm.app.ui.theme.DawngasmTheme
import java.awt.Dimension

private const val APP_VERSION = "2.0.0"

fun main() {
    DesktopPlatform.ensureDirectories()
    application {
        val prefs = remember { AppPreferences() }
        val presenter = remember {
            val resolver = AudioResolver(prefs)
            AppPresenter(
                prefs = prefs,
                api = DawngasmApi(),
                resolver = resolver,
                fileSaver = DesktopFileSaver(),
                player = NoOpAudioPlayer(),
            )
        }
        val state by presenter.uiState.collectAsState()
        val progress by presenter.progress.collectAsState()

        val windowState = rememberWindowState(width = 420.dp, height = 860.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Dawngasm",
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(420, 640)
            }
            DisposableEffect(presenter) {
                onDispose { presenter.player.release() }
            }
            DawngasmTheme {
                DawngasmApp(
                    state = state,
                    progress = progress,
                    messages = presenter.messages,
                    actions = presenter,
                    settingsPlatform = DesktopSettingsPlatformActions,
                    appVersion = APP_VERSION,
                )
            }
        }
    }
}

/** Phase 7 replaces these with a native folder chooser and clearance windows. */
private object DesktopSettingsPlatformActions : SettingsPlatformActions {
    override fun chooseDownloadFolder() = Unit
    override fun openHotaudioClearance() = Unit
    override fun openAudiochanClearance() = Unit
    override fun hotaudioClearanceDetail(): String? = null
}
