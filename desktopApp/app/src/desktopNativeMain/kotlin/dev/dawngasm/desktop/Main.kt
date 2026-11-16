package dev.dawngasm.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dawngasm.app.AppPresenter
import dev.dawngasm.app.DawngasmApp
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.AudioResolver
import dev.dawngasm.app.data.DesktopPlatform
import dev.dawngasm.app.data.NativeFileSaver
import dev.dawngasm.app.data.NativeSettings
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.platform.NativeSettingsPlatformActions
import dev.dawngasm.app.player.NativeAudioPlayer
import dev.dawngasm.app.ui.theme.DawngasmTheme
import kotlinx.coroutines.Dispatchers
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.any { it == "--self-test" || it == "--version" }) {
        val skipAudio = args.contains("--skip-audio-probe")
        runSelfTest(skipAudio = skipAudio)
        return
    }

    DesktopPlatform.ensureDirectories()
    val settings = NativeSettings()
    val prefs = AppPreferences(settings)
    val resolver = AudioResolver(prefs)
    val fileSaver = NativeFileSaver()
    val player = NativeAudioPlayer(resolver)
    val presenter = AppPresenter(
        prefs = prefs,
        resolver = resolver,
        fileSaver = fileSaver,
        player = player,
        coroutineDispatcher = Dispatchers.Default,
    )
    application {
        Window(
            onCloseRequest = {
                player.release()
                exitApplication()
            },
            state = rememberWindowState(),
            title = "Dawngasm",
        ) {
            val uiState by presenter.uiState.collectAsState()
            val progress by presenter.progress.collectAsState()
            var clearanceTarget by remember { mutableStateOf<ClearanceTarget?>(null) }
            val settingsActions = remember(presenter) {
                NativeSettingsPlatformActions(
                    prefs = prefs,
                    onOpenHotaudio = { clearanceTarget = ClearanceTarget.HOTAUDIO },
                    onOpenAudiochan = { clearanceTarget = ClearanceTarget.AUDIOCHAN },
                )
            }

            DawngasmTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DawngasmApp(
                        state = uiState,
                        progress = progress,
                        messages = presenter.messages,
                        actions = presenter,
                        settingsPlatform = settingsActions,
                        appVersion = DesktopInfo.VERSION,
                    )
                }

                clearanceTarget?.let { target ->
                    DesktopClearanceFlow(
                        target = target,
                        presenter = presenter,
                        onDismiss = { clearanceTarget = null },
                    )
                }
            }
        }
    }
}

private fun runSelfTest(skipAudio: Boolean = false) {
    println("dawngasm desktop ${DesktopInfo.VERSION}")
    println("os=${DesktopInfo.osName} arch=${DesktopInfo.archName}")

    DesktopPlatform.ensureDirectories()
    val settings = NativeSettings()
    val prefs = AppPreferences(settings)
    println("native storage: initialized (${DesktopPlatform.configDir})")

    val resolver = AudioResolver(prefs)
    val fileSaver = NativeFileSaver()
    val player = NativeAudioPlayer(resolver)
    val presenter = AppPresenter(
        prefs = prefs,
        resolver = resolver,
        fileSaver = fileSaver,
        player = player,
        coroutineDispatcher = Dispatchers.Default,
    )
    val settingsActions = NativeSettingsPlatformActions(prefs)
    val client = Network.httpClient
    println("presenter & audio player: initialized")
    println("network: ok engine=${client.engine::class.simpleName}")

    val playerReady = if (skipAudio) {
        println("kmplayer: audio probe skipped (--skip-audio-probe)")
        true
    } else {
        probePlayer()
    }
    probeNotifications()

    player.release()

    if (!playerReady) {
        println("self-test: failed")
        exitProcess(1)
    }
    println("self-test: ok")
}
