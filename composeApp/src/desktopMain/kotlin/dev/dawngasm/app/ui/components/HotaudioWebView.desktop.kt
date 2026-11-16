package dev.dawngasm.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * Phase 0 placeholder for the hotaudio.net clearance WebView. Phase 10/19
 * replace this with the native helper window.
 */
@Composable
actual fun HotaudioWebView(
    url: String,
    modifier: Modifier,
    onLoadingChange: (Boolean) -> Unit,
    onStatusChange: (hasClearance: Boolean) -> Unit,
    onTraceDetected: (ip: String, family: String) -> Unit,
    onClearanceCaptured: (cookie: String, userAgent: String) -> Unit,
    captureSignal: Int,
) {
    LaunchedEffect(url, captureSignal) {
        onLoadingChange(false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "hotaudio clearance window is coming in a later phase.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
