package dev.dawngasm.app

import androidx.compose.ui.window.ComposeUIViewController
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.ui.theme.DawngasmTheme
import kotlinx.coroutines.flow.emptyFlow
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false }
) {
    DawngasmTheme {
        DawngasmApp(
            state = sampleAppUiState(),
            progress = PlaybackProgress(positionMs = 42_000L, durationMs = 180_000L),
            messages = emptyFlow(),
            actions = NoOpDawngasmActions,
            settingsPlatform = NoOpSettingsPlatformActions,
            appVersion = "1.0.0",
        )
    }
}
