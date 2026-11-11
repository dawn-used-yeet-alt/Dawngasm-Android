package dev.dawngasm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.ui.screens.AudiochanClearanceScreen
import dev.dawngasm.app.ui.theme.DawngasmTheme
import kotlinx.coroutines.flow.emptyFlow
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false }
) {
    val prefs = remember { AppPreferences() }
    var audiochanConnected by remember {
        mutableStateOf(prefs.audiochanClearance() != null)
    }
    var showAudiochanClearance by remember { mutableStateOf(false) }

    val actions = remember {
        object : DawngasmActions by NoOpDawngasmActions {
            override fun refreshAudiochanClearance() {
                audiochanConnected = prefs.audiochanClearance() != null
            }

            override fun clearAudiochanClearance() {
                prefs.clearAudiochanClearance()
                audiochanConnected = false
            }
        }
    }

    val settingsPlatform = remember {
        object : SettingsPlatformActions by NoOpSettingsPlatformActions {
            override fun openAudiochanClearance() {
                showAudiochanClearance = true
            }
        }
    }

    DawngasmTheme {
        if (showAudiochanClearance) {
            AudiochanClearanceScreen(
                onClearanceCaptured = { rawCookie, userAgent ->
                    prefs.saveAudiochanClearance(rawCookie, userAgent)
                    Network.syncCookie("https://audiochan.com/", rawCookie)
                    Network.syncCookie("https://api.audiochan.com/", rawCookie)
                    audiochanConnected = true
                    showAudiochanClearance = false
                },
                onClose = {
                    showAudiochanClearance = false
                },
            )
        } else {
            DawngasmApp(
                state = sampleAppUiState().copy(audiochanConnected = audiochanConnected),
                progress = PlaybackProgress(positionMs = 42_000L, durationMs = 180_000L),
                messages = emptyFlow(),
                actions = actions,
                settingsPlatform = settingsPlatform,
                appVersion = "1.0.0",
            )
        }
    }
}
