package dev.dawngasm.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.dawngasm.app.data.DesktopTrackDownloader
import dev.dawngasm.app.data.DawngasmApi
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.player.DesktopAudioPlayer
import dev.dawngasm.app.ui.theme.DawngasmTheme
import java.awt.Dimension

private const val APP_VERSION = "2.0.0"

private enum class ClearanceTarget {
    HOTAUDIO,
    AUDIOCHAN,
}

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
                downloader = DesktopTrackDownloader(prefs),
                player = DesktopAudioPlayer(resolver),
            )
        }
        var clearanceTarget by remember { mutableStateOf<ClearanceTarget?>(null) }
        val settingsActions = remember(presenter) {
            DesktopSettingsPlatformActions(
                presenter = presenter,
                openHotaudio = { clearanceTarget = ClearanceTarget.HOTAUDIO },
                openAudiochan = { clearanceTarget = ClearanceTarget.AUDIOCHAN },
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
                    settingsPlatform = settingsActions,
                    appVersion = APP_VERSION,
                )

                when (clearanceTarget) {
                    ClearanceTarget.HOTAUDIO -> {
                        val saved = presenter.prefs.hotaudioClearance()
                        DesktopClearanceDialog(
                            title = "hotaudio clearance",
                            cookieLabel = "Cookie header (cf_clearance)",
                            initialCookie = saved?.first.orEmpty(),
                            initialUserAgent = saved?.second?.ifBlank { Network.BROWSER_USER_AGENT }
                                ?: Network.BROWSER_USER_AGENT,
                            showIpFamily = true,
                            initialIp = presenter.prefs.hotaudioClientIp().orEmpty(),
                            initialFamily = presenter.prefs.hotaudioIpFamily().orEmpty(),
                            onDismiss = { clearanceTarget = null },
                            onSave = { cookie, userAgent, ip, family ->
                                presenter.saveHotaudioClearance(cookie, userAgent, ip, family)
                                clearanceTarget = null
                            },
                        )
                    }

                    ClearanceTarget.AUDIOCHAN -> {
                        val saved = presenter.prefs.audiochanClearance()
                        DesktopClearanceDialog(
                            title = "audiochan clearance",
                            cookieLabel = "Cookie header (__Secure-audiochan_playback)",
                            initialCookie = saved?.first.orEmpty(),
                            initialUserAgent = saved?.second?.ifBlank { Network.BROWSER_USER_AGENT }
                                ?: Network.BROWSER_USER_AGENT,
                            showIpFamily = false,
                            initialIp = "",
                            initialFamily = "",
                            onDismiss = { clearanceTarget = null },
                            onSave = { cookie, userAgent, _, _ ->
                                presenter.saveAudiochanClearance(cookie, userAgent)
                                clearanceTarget = null
                            },
                        )
                    }

                    null -> Unit
                }
            }
        }
    }
}
