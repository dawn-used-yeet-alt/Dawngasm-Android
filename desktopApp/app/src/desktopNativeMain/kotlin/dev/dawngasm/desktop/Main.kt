@file:OptIn(kotlin.native.runtime.NativeRuntimeApi::class)

package dev.dawngasm.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.system.exitProcess

private fun configureNativeRuntime() {
    GC.autotune = true
    GC.heapTriggerCoefficient = 0.75
}

fun main(args: Array<String>) {
    configureNativeRuntime()
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
        val savedWidth = prefs.windowWidth()
        val savedHeight = prefs.windowHeight()
        val isDefaultOrNull = savedWidth == null || savedHeight == null ||
            (savedWidth == 800 && savedHeight == 600)
        val initialSize = if (!isDefaultOrNull && savedWidth!! > 0 && savedHeight!! > 0) {
            DpSize(savedWidth.dp, savedHeight.dp)
        } else {
            // Open wide enough for the desktop shell; resizing below the
            // breakpoint animates back to the compact, touch-first layout.
            DpSize(1280.dp, 820.dp)
        }
        val windowState = rememberWindowState(size = initialSize)

        fun persistWindowState() {
            val width = windowState.size.width
            val height = windowState.size.height
            if (width.isSpecified && height.isSpecified && width.value > 0 && height.value > 0) {
                val position = windowState.position
                val x = (position as? WindowPosition.Absolute)?.x?.value?.roundToInt() ?: 0
                val y = (position as? WindowPosition.Absolute)?.y?.value?.roundToInt() ?: 0
                val isMaximized = windowState.placement == WindowPlacement.Maximized
                prefs.saveWindowBounds(
                    x = x,
                    y = y,
                    width = width.value.roundToInt(),
                    height = height.value.roundToInt(),
                    maximized = isMaximized,
                )
            }
        }

        Window(
            onCloseRequest = {
                persistWindowState()
                runBlocking {
                    withTimeoutOrNull(1_500L) {
                        runCatching { presenter.flushSync() }
                    }
                }
                player.release()
                exitApplication()
            },
            state = windowState,
            title = "Dawngasm",
        ) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(60_000)
                    GC.schedule()
                }
            }
            LaunchedEffect(windowState.size, windowState.placement) {
                persistWindowState()
                GC.schedule()
            }
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
                        isDesktop = true,
                    )
                }

                clearanceTarget?.let { target ->
                    DesktopClearanceFlow(
                        target = target,
                        presenter = presenter,
                        onDismiss = {
                            clearanceTarget = null
                            GC.schedule()
                        },
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
