package dev.dawngasm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dawngasm.app.platform.PlatformBackHandler
import dev.dawngasm.app.ui.components.HotaudioWebView

/**
 * Shared clearance flow for hotaudio.net. Hosts the WebView, tracks Cloudflare cf_clearance,
 * detects the client's public IP family, and delivers cookies and user-agent when verified.
 */
@Composable
fun HotaudioClearanceScreen(
    onClearanceCaptured: (cookie: String, userAgent: String, ip: String?, family: String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var detectedIp by remember { mutableStateOf<String?>(null) }
    var detectedFamily by remember { mutableStateOf<String?>(null) }
    var captureSignal by remember { mutableStateOf(0) }

    PlatformBackHandler(enabled = true) {
        onClose()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "hotaudio check",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (connected) {
                        val ipInfo = detectedIp?.let { " (${detectedFamily?.uppercase() ?: "IP"}: $it)" }.orEmpty()
                        "Verified$ipInfo — tap Done to save"
                    } else {
                        "Pass Cloudflare verification, then tap Done"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    captureSignal++
                },
            ) {
                Text("Done")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f)) {
            HotaudioWebView(
                url = "https://hotaudio.net/",
                modifier = Modifier.fillMaxSize(),
                onLoadingChange = { loading = it },
                onStatusChange = { connected = it },
                onTraceDetected = { ip, family ->
                    detectedIp = ip
                    detectedFamily = family
                },
                onClearanceCaptured = { rawCookie, userAgent ->
                    onClearanceCaptured(rawCookie, userAgent, detectedIp, detectedFamily)
                },
                captureSignal = captureSignal,
            )
        }

        Text(
            text = "The clearance cookie and client IP family are saved on this device only and refreshed as needed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}
