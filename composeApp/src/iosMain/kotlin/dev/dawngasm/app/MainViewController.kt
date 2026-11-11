package dev.dawngasm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.data.CookieUtils
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.platform.DawngasmActions
import dev.dawngasm.app.platform.SettingsPlatformActions
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.ui.screens.AudiochanClearanceScreen
import dev.dawngasm.app.ui.screens.HotaudioClearanceScreen
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
    var hotaudioConnected by remember {
        mutableStateOf(prefs.hotaudioClearance() != null)
    }
    var showAudiochanClearance by remember { mutableStateOf(false) }
    var showHotaudioClearance by remember { mutableStateOf(false) }

    val actions = remember {
        object : DawngasmActions by NoOpDawngasmActions {
            override fun refreshAudiochanClearance() {
                audiochanConnected = prefs.audiochanClearance() != null
            }

            override fun clearAudiochanClearance() {
                prefs.clearAudiochanClearance()
                audiochanConnected = false
            }

            override fun refreshHotaudioClearance() {
                hotaudioConnected = prefs.hotaudioClearance() != null
            }

            override fun clearHotaudioClearance() {
                prefs.clearHotaudioClearance()
                hotaudioConnected = false
            }
        }
    }

    val settingsPlatform = remember {
        object : SettingsPlatformActions by NoOpSettingsPlatformActions {
            override fun openAudiochanClearance() {
                showAudiochanClearance = true
            }

            override fun openHotaudioClearance() {
                showHotaudioClearance = true
            }

            override fun hotaudioClearanceDetail(): String? {
                val ip = prefs.hotaudioClientIp()
                val family = prefs.hotaudioIpFamily()?.uppercase()
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
            }
            showHotaudioClearance -> {
                HotaudioClearanceScreen(
                    onClearanceCaptured = { rawCookie, userAgent, ip, family ->
                        if (CookieUtils.hasCookie(rawCookie, "cf_clearance")) {
                            prefs.saveHotaudioClearance(rawCookie, userAgent)
                            if (family != null) {
                                prefs.saveHotaudioIpFamily(family, ip)
                            }
                            hotaudioConnected = true
                            showHotaudioClearance = false
                        }
                    },
                    onClose = {
                        showHotaudioClearance = false
                    },
                )
            }
            else -> {
                DawngasmApp(
                    state = sampleAppUiState().copy(
                        audiochanConnected = audiochanConnected,
                        hotaudioConnected = hotaudioConnected,
                    ),
                    progress = PlaybackProgress(positionMs = 42_000L, durationMs = 180_000L),
                    messages = emptyFlow(),
                    actions = actions,
                    settingsPlatform = settingsPlatform,
                    appVersion = "1.0.0",
                )
            }
        }
    }
}
