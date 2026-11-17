package dev.dawngasm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.AppPresenter
import dev.dawngasm.app.data.Network
import dev.dawngasm.app.ui.components.ClearanceScripts

enum class ClearanceTarget {
    HOTAUDIO,
    AUDIOCHAN,
}

private data class FlowDetails(
    val title: String,
    val url: String,
    val cookieUrls: List<String>,
    val requiredCookie: String,
    val initScript: String?,
)

private fun detailsFor(target: ClearanceTarget): FlowDetails = when (target) {
    ClearanceTarget.HOTAUDIO -> FlowDetails(
        title = "hotaudio clearance",
        url = "https://hotaudio.net/",
        cookieUrls = listOf("https://hotaudio.net/", "https://www.hotaudio.net/"),
        requiredCookie = "cf_clearance",
        initScript = null,
    )
    ClearanceTarget.AUDIOCHAN -> FlowDetails(
        title = "audiochan clearance",
        url = "https://audiochan.com/",
        cookieUrls = listOf("https://audiochan.com/", "https://api.audiochan.com/"),
        requiredCookie = "__Secure-audiochan_playback",
        initScript = ClearanceScripts.AUDIOCHAN_TURNSTILE_INJECTION,
    )
}

/**
 * Runs the native clearance helper for [target], saves the captured session,
 * and falls back to the manual dialog when the helper is unavailable.
 */
@Composable
fun DesktopClearanceFlow(
    target: ClearanceTarget,
    presenter: AppPresenter,
    onDismiss: () -> Unit,
) {
    val details = remember(target) { detailsFor(target) }
    var attempt by remember(target) { mutableStateOf(0) }
    var failure by remember(target, attempt) { mutableStateOf<String?>(null) }
    var manual by remember(target) { mutableStateOf(false) }
    val session = remember(target, attempt) { NativeClearanceHelper.Session() }

    DisposableEffect(session) {
        onDispose { session.cancel() }
    }

    if (manual) {
        when (target) {
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
                    onDismiss = {
                        session.cancel()
                        onDismiss()
                    },
                    onSave = { cookie, userAgent, ip, family ->
                        presenter.saveHotaudioClearance(cookie, userAgent, ip, family)
                        onDismiss()
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
                    onDismiss = {
                        session.cancel()
                        onDismiss()
                    },
                    onSave = { cookie, userAgent, _, _ ->
                        presenter.saveAudiochanClearance(cookie, userAgent)
                        onDismiss()
                    },
                )
            }
        }
        return
    }

    LaunchedEffect(target, attempt, session) {
        failure = null
        when (
            val result = session.capture(
                url = details.url,
                cookieUrls = details.cookieUrls,
                requiredCookie = details.requiredCookie,
                title = details.title,
                initScript = details.initScript,
            )
        ) {
            is NativeClearanceHelper.Result.Success -> {
                val captured = result.captured
                when (target) {
                    ClearanceTarget.HOTAUDIO -> {
                        presenter.saveHotaudioClearance(
                            captured.cookie,
                            captured.userAgent,
                            null,
                            null,
                        )
                    }
                    ClearanceTarget.AUDIOCHAN -> {
                        val userAgent = captured.userAgent.ifBlank {
                            dev.dawngasm.app.data.CommonAudioResolver.extractAudiochanUserAgent(captured.cookie).orEmpty()
                        }
                        presenter.saveAudiochanClearance(
                            captured.cookie,
                            userAgent,
                        )
                    }
                }
                onDismiss()
            }
            is NativeClearanceHelper.Result.Failure -> {
                failure = result.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            session.cancel()
            onDismiss()
        },
        title = { Text(details.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (failure == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "  Waiting for verification…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "A clearance window opened. Pass the browser check there; this " +
                            "dialog closes automatically once the cookie is captured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = failure.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "You can retry the native window or paste the cookie header manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (failure == null) {
                TextButton(
                    onClick = {
                        session.cancel()
                        onDismiss()
                    },
                ) {
                    Text("Cancel")
                }
            } else {
                TextButton(onClick = { attempt++ }) {
                    Text("Try again")
                }
            }
        },
        dismissButton = {
            if (failure == null) {
                TextButton(onClick = { attempt++ }) {
                    Text("Reopen window")
                }
            } else {
                TextButton(
                    onClick = {
                        session.cancel()
                        manual = true
                    },
                ) {
                    Text("Paste manually")
                }
            }
        },
    )
}
