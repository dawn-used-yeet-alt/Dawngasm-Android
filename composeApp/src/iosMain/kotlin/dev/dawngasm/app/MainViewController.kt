package dev.dawngasm.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.dawngasm.app.platform.NoOpSettingsPlatformActions
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.ui.screens.AudiochanClearanceScreen
import dev.dawngasm.app.ui.screens.HotaudioClearanceScreen
import dev.dawngasm.app.ui.theme.DawngasmTheme
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false }
) {
    val presenter = remember { IosAppPresenter() }
    val state by presenter.uiState.collectAsState()
    val progress by presenter.progress.collectAsState()

    var showAudiochanClearance by remember { mutableStateOf(false) }
    var showHotaudioClearance by remember { mutableStateOf(false) }

    val settingsPlatform = remember(presenter) {
        object : SettingsPlatformActions by NoOpSettingsPlatformActions {
            override fun openAudiochanClearance() {
                showAudiochanClearance = true
            }

            override fun openHotaudioClearance() {
                showHotaudioClearance = true
            }

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
        }
    }

    DawngasmTheme {
        when {
            showAudiochanClearance -> {
                AudiochanClearanceScreen(
                    onClearanceCaptured = { rawCookie, userAgent ->
                        presenter.saveAudiochanClearance(rawCookie, userAgent)
                        showAudiochanClearance = false
                    },
                    onClose = {
                        showAudiochanClearance = false
                    },
                )
            }
            showHotaudioClearance -> {
                HotaudioClearanceScreen(
                    onClearanceCaptured = { rawCookie, userAgent, ip, family ->
                        presenter.saveHotaudioClearance(rawCookie, userAgent, ip, family)
                        showHotaudioClearance = false
                    },
                    onClose = {
                        showHotaudioClearance = false
                    },
                )
            }
            else -> {
                DawngasmApp(
                    state = state,
                    progress = progress,
                    messages = presenter.messages,
                    actions = presenter,
                    settingsPlatform = settingsPlatform,
                    appVersion = "2.0.0",
                )
            }
        }
    }
}
