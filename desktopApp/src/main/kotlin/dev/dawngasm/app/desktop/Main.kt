package dev.dawngasm.app.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
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
import dev.dawngasm.app.data.HotaudioDesktopIntegration
import dev.dawngasm.app.data.HotaudioStreamServer
import dev.dawngasm.app.player.DesktopAudioPlayer
import dev.dawngasm.app.state.AppCommand
import dev.dawngasm.app.ui.theme.DawngasmTheme
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlinx.coroutines.flow.MutableSharedFlow

private const val APP_NAME = "Dawngasm"
private const val APP_VERSION = "2.0.0"

private const val DEFAULT_WINDOW_WIDTH = 420
private const val DEFAULT_WINDOW_HEIGHT = 860
private const val MIN_WINDOW_WIDTH = 420
private const val MIN_WINDOW_HEIGHT = 640
private const val MAX_WINDOW_DIMENSION = 10_000

private fun appShortcut(key: Key): KeyShortcut =
    if (DesktopPlatform.os == DesktopPlatform.Os.MACOS) {
        KeyShortcut(key, meta = true)
    } else {
        KeyShortcut(key, ctrl = true)
    }

private fun isVisibleOnAnyScreen(x: Int, y: Int, width: Int, height: Int): Boolean {
    val bounds = Rectangle(x, y, width, height)
    return runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .any { bounds.intersects(it.defaultConfiguration.bounds) }
    }.getOrDefault(true)
}

@Composable
private fun rememberDesktopWindowState(prefs: AppPreferences): WindowState {
    val width = (prefs.windowWidth() ?: DEFAULT_WINDOW_WIDTH)
        .coerceIn(MIN_WINDOW_WIDTH, MAX_WINDOW_DIMENSION)
    val height = (prefs.windowHeight() ?: DEFAULT_WINDOW_HEIGHT)
        .coerceIn(MIN_WINDOW_HEIGHT, MAX_WINDOW_DIMENSION)
    val x = prefs.windowX()
    val y = prefs.windowY()
    val position = if (x != null && y != null && isVisibleOnAnyScreen(x, y, width, height)) {
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition(Alignment.Center)
    }
    return rememberWindowState(
        placement = if (prefs.windowMaximized()) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = position,
        size = DpSize(width.dp, height.dp),
    )
}

/** Persists the normal (non-maximized) bounds so unmaximizing later restores them. */
private fun persistWindowState(prefs: AppPreferences, windowState: WindowState) {
    val maximized = windowState.placement == WindowPlacement.Maximized
    val width = if (maximized) {
        prefs.windowWidth() ?: DEFAULT_WINDOW_WIDTH
    } else {
        windowState.size.width.value.toInt().coerceIn(MIN_WINDOW_WIDTH, MAX_WINDOW_DIMENSION)
    }
    val height = if (maximized) {
        prefs.windowHeight() ?: DEFAULT_WINDOW_HEIGHT
    } else {
        windowState.size.height.value.toInt().coerceIn(MIN_WINDOW_HEIGHT, MAX_WINDOW_DIMENSION)
    }
    val position = windowState.position
    val x = if (!maximized && position is WindowPosition.Absolute) {
        position.x.value.toInt()
    } else {
        prefs.windowX() ?: 0
    }
    val y = if (!maximized && position is WindowPosition.Absolute) {
        position.y.value.toInt()
    } else {
        prefs.windowY() ?: 0
    }
    prefs.saveWindowBounds(x, y, width, height, maximized)
}

fun main(args: Array<String>) {
    val versionOnly = args.contains("--version")
    DesktopPlatform.ensureDirectories()
    HotaudioDesktopIntegration.install()
    // Run the packaged native probes even for --version so CI's smoke test
    // catches missing JNA/native payloads in the app image.
    DesktopHotaudio.install()
    if (versionOnly) {
        println("$APP_NAME $APP_VERSION")
        return
    }
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
        var aboutOpen by remember { mutableStateOf(false) }
        val commands = remember { MutableSharedFlow<AppCommand>(extraBufferCapacity = 16) }
        val sendCommand: (AppCommand) -> Unit = remember(commands) {
            { command -> commands.tryEmit(command) }
        }
        val settingsActions = remember(presenter) {
            DesktopSettingsPlatformActions(
                presenter = presenter,
                openHotaudio = { clearanceTarget = ClearanceTarget.HOTAUDIO },
                openAudiochan = { clearanceTarget = ClearanceTarget.AUDIOCHAN },
            )
        }

        val state by presenter.uiState.collectAsState()
        val progress by presenter.progress.collectAsState()

        val windowState = rememberDesktopWindowState(prefs)
        val quit: () -> Unit = {
            persistWindowState(prefs, windowState)
            HotaudioStreamServer.stop()
            exitApplication()
        }
        Window(
            onCloseRequest = quit,
            state = windowState,
            title = APP_NAME,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
            }
            DisposableEffect(presenter) {
                onDispose { presenter.player.release() }
            }
            val isMac = DesktopPlatform.os == DesktopPlatform.Os.MACOS
            MenuBar {
                if (isMac) {
                    Menu(APP_NAME) {
                        Item("About $APP_NAME", onClick = { aboutOpen = true })
                        Separator()
                        Item(
                            "Quit $APP_NAME",
                            shortcut = appShortcut(Key.Q),
                            onClick = quit,
                        )
                    }
                }
                Menu("File") {
                    Item(
                        "Refresh Feed",
                        shortcut = appShortcut(Key.R),
                        onClick = { sendCommand(AppCommand.Refresh) },
                    )
                    Item(
                        "Open Download Folder",
                        enabled = settingsActions.canOpenDownloadFolder(),
                        onClick = settingsActions::openDownloadFolder,
                    )
                    Separator()
                    if (!isMac) {
                        Item(
                            "Quit",
                            shortcut = appShortcut(Key.Q),
                            onClick = quit,
                        )
                    }
                }
                Menu("Edit") {
                    Item(
                        "Find…",
                        shortcut = appShortcut(Key.F),
                        onClick = { sendCommand(AppCommand.OpenSearch) },
                    )
                    Item("Clear Search", onClick = presenter::clearSearch)
                    Item("Clear Downloads", onClick = presenter::clearDownloads)
                }
                Menu("View") {
                    Item(
                        "Home",
                        shortcut = appShortcut(Key.One),
                        onClick = { sendCommand(AppCommand.ShowHome) },
                    )
                    Item(
                        "Library",
                        shortcut = appShortcut(Key.Two),
                        onClick = { sendCommand(AppCommand.ShowLibrary) },
                    )
                    Item(
                        "Settings",
                        shortcut = appShortcut(Key.Three),
                        onClick = { sendCommand(AppCommand.ShowSettings) },
                    )
                    Separator()
                    Item(
                        "Dismiss Player",
                        enabled = state.player.currentTrack != null,
                        onClick = { sendCommand(AppCommand.DismissPlayer) },
                    )
                }
                Menu("Help") {
                    Item("About $APP_NAME", onClick = { aboutOpen = true })
                    Item(
                        "Open Config Folder",
                        onClick = { DesktopPlatform.openDirectory(DesktopPlatform.configDir) },
                    )
                }
            }
            DawngasmTheme {
                DawngasmApp(
                    state = state,
                    progress = progress,
                    messages = presenter.messages,
                    actions = presenter,
                    settingsPlatform = settingsActions,
                    appVersion = APP_VERSION,
                    commands = commands,
                )

                if (aboutOpen) {
                    AlertDialog(
                        onDismissRequest = { aboutOpen = false },
                        title = { Text(APP_NAME) },
                        text = {
                            Column {
                                Text("Version $APP_VERSION")
                                Text(DesktopPlatform.displayName)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { aboutOpen = false }) {
                                Text("OK")
                            }
                        },
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
