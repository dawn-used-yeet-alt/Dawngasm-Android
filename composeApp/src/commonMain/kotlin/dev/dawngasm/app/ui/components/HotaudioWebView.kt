package dev.dawngasm.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-rendered WebView for completing Cloudflare verification on hotaudio.net.
 */
@Composable
expect fun HotaudioWebView(
    url: String,
    modifier: Modifier = Modifier,
    onLoadingChange: (Boolean) -> Unit = {},
    onStatusChange: (hasClearance: Boolean) -> Unit = {},
    onTraceDetected: (ip: String, family: String) -> Unit = { _, _ -> },
    onClearanceCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
    captureSignal: Int = 0,
)
